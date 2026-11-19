package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
)

const (
	workerSendBuf      = 128
	readBufSize        = 1600
	socketBufSize      = 625 * 1024
	keepaliveByte      = 0xFF
	keepaliveInterval  = 15 * time.Second
	keepaliveTimeout   = 3 * keepaliveInterval
	packetWriteTimeout = 4 * time.Second
	relayPipeBuffer    = 64 * 1024
)

var handshakeSem = make(chan struct{}, dtlsParallelism())

func dtlsParallelism() int {
	if managedClient {
		return 15
	}
	return 3
}

func runPeerSession(
	ctx context.Context,
	lease *turnLease,
	tp *TurnParams,
	peer *net.UDPAddr,
	d *Dispatcher,
	localPort string,
	getConfig bool,
	configCh chan<- string,
	sessionID int,
	creds *Credentials,
	deviceID, password string,
	stats *Stats,
	ready func(),
) (bool, error) {
	configDelivered := false
	sessCtx, sessCancel := context.WithCancelCause(ctx)
	defer sessCancel(nil)

	relay, err := lease.openPeer(peer)
	if err != nil {
		return false, err
	}
	defer relay.Close()
	turnConn := lease.turnConn
	stopLease := context.AfterFunc(lease.ctx, func() { sessCancel(context.Cause(lease.ctx)) })
	defer stopLease()

	transport := newWrappedPeer(relay, tp.WrapKey, tp.ObfsMode)
	useWrap := len(tp.WrapKey) == wrapKeyLen
	var proxyWg sync.WaitGroup
	defer func() { sessCancel(nil); transport.Close(); proxyWg.Wait() }()

	cert, err := selfsign.GenerateSelfSigned()
	if err != nil {
		return false, fmt.Errorf("генерация сертификата: %w", err)
	}

	select {
	case handshakeSem <- struct{}{}:
	case <-sessCtx.Done():
		return false, sessCtx.Err()
	}

	dtlsCfg := &dtls.Config{
		Certificates:          []tls.Certificate{cert},
		InsecureSkipVerify:    true,
		ExtendedMasterSecret:  dtls.RequireExtendedMasterSecret,
		CipherSuites:          []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
		ConnectionIDGenerator: dtls.OnlySendCIDGenerator(),
	}

	dtlsConn, err := dtls.Client(transport, peer, dtlsCfg)
	if err != nil {
		<-handshakeSem
		return false, fmt.Errorf("DTLS клиент: %w", err)
	}
	var closeOnce sync.Once
	closeDTLS := func() {
		closeOnce.Do(func() {
			_ = dtlsConn.SetWriteDeadline(time.Now().Add(500 * time.Millisecond))
			_ = dtlsConn.Close()
		})
	}
	defer closeDTLS()
	stopDTLS := context.AfterFunc(sessCtx, closeDTLS)
	defer stopDTLS()

	hctx, hcancel := context.WithTimeout(sessCtx, 20*time.Second)
	log.Printf("[ВОРКЕР #%d] [DTLS] Рукопожатие (Handshake)...", sessionID)
	err = dtlsConn.HandshakeContext(hctx)
	hcancel()
	<-handshakeSem

	if err != nil {
		if sessCtx.Err() != nil {
			return false, context.Cause(sessCtx)
		}
		if useWrap {
			errStr := strings.ToLower(err.Error())
			if strings.Contains(errStr, "deadline") || strings.Contains(errStr, "timeout") {
				return false, fmt.Errorf("WRAP_AUTH_TIMEOUT: DTLS timeout, пароль/WRAP не подтверждён")
			}
		}
		return false, fmt.Errorf("DTLS хендшейк: %w", err)
	}
	log.Printf("[ВОРКЕР #%d] [DTLS] Соединение установлено ✓", sessionID)

	if getConfig && configCh != nil {
		conf, confErr := RequestConfig(dtlsConn, localPort, deviceID, password)
		if confErr != nil {
			errStr := confErr.Error()
			if strings.Contains(errStr, "FATAL_AUTH") {
				return false, confErr
			}
			return false, confErr
		} else if conf != "" {
			select {
			case configCh <- conf:
				configDelivered = true
				log.Printf("[ВОРКЕР #%d] Конфиг получен", sessionID)
			default:
				configDelivered = true
				log.Printf("[ВОРКЕР #%d] Конфиг уже был доставлен другим воркером", sessionID)
			}
		} else {
			return false, fmt.Errorf("сервер ещё не выдал WireGuard-конфиг")
		}
	}

	slot := &WorkerSlot{
		ID:     sessionID,
		SendCh: make(chan []byte, workerSendBuf),
	}
	d.Register(slot)
	defer d.Unregister(slot)
	if ready != nil {
		ready()
	}
	rememberUsableTurnCredentials(creds)
	log.Printf("[ВОРКЕР #%d] [READY] Туннель готов к работе ✓", sessionID)
	emitReady()

	proxyWg.Add(2)
	readyAt := time.Now()
	var lastEcho atomic.Int64

	go func() {
		defer proxyWg.Done()
		defer sessCancel(nil)
		t := time.NewTicker(keepaliveInterval)
		defer t.Stop()
		ping := []byte{keepaliveByte}
		write := func(pkt []byte) error {
			deadline := time.Now().Add(packetWriteTimeout)
			_ = dtlsConn.SetWriteDeadline(deadline)
			if err := sessCtx.Err(); err != nil {
				return err
			}
			_, err := dtlsConn.Write(pkt)
			return err
		}
		for {
			select {
			case <-sessCtx.Done():
				return
			case <-t.C:
				if turnConn.refreshOverdue(time.Now(), keepaliveTimeout) {
					sessCancel(fmt.Errorf("TURN Refresh: нет ответа в течение %s", keepaliveTimeout))
					return
				}
				// Older servers may not echo 0xff. Only expect replies once
				// the peer has demonstrated support on this connection.
				if last := time.Duration(lastEcho.Load()); last > 0 && time.Since(readyAt)-last >= keepaliveTimeout {
					sessCancel(fmt.Errorf("DTLS: нет ответа на keepalive в течение %s", keepaliveTimeout))
					return
				}
				if err := write(ping); err != nil {
					sessCancel(fmt.Errorf("отправка DTLS keepalive: %w", err))
					return
				}
			case pkt, ok := <-slot.SendCh:
				if !ok {
					return
				}
				writeErr := write(pkt)
				putPktBuf(pkt)
				if writeErr != nil {
					sessCancel(fmt.Errorf("запись DTLS: %w", writeErr))
					return
				}
			}
		}
	}()

	go func() {
		defer proxyWg.Done()
		defer sessCancel(nil)
		for {
			pkt := getPktBuf(2048)
			if sessCtx.Err() != nil {
				putPktBuf(pkt)
				return
			}
			n, readErr := dtlsConn.Read(pkt)
			if readErr != nil {
				putPktBuf(pkt)
				sessCancel(fmt.Errorf("чтение DTLS: %w", readErr))
				return
			}

			if n == 1 && pkt[0] == keepaliveByte {
				lastEcho.Store(int64(time.Since(readyAt)))
				putPktBuf(pkt)
				continue
			}

			pkt = pkt[:n]
			select {
			case d.ReturnCh <- pkt:
			case <-sessCtx.Done():
				putPktBuf(pkt)
				return
			}
		}
	}()

	<-sessCtx.Done()
	log.Printf("[СЕССИЯ #%d] Завершена", sessionID)
	return configDelivered, context.Cause(sessCtx)
}
