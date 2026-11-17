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

	"github.com/cbeuw/connutil"
	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
	"github.com/pion/transport/v4/stdnet"
	"github.com/pion/turn/v5"
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

func RunSession(
	ctx context.Context,
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
) (bool, error) {
	configDelivered := false
	sessCtx, sessCancel := context.WithCancelCause(ctx)
	defer sessCancel(nil)

	if len(creds.TurnURLs) == 0 {
		return false, fmt.Errorf("нет TURN URL в учетных данных")
	}
	selectedURL := creds.TurnURLs[sessionID%len(creds.TurnURLs)]

	urlhost, urlport, err := net.SplitHostPort(selectedURL)
	if err != nil {
		return false, fmt.Errorf("разбор TURN URL %q: %w", selectedURL, err)
	}
	if tp.Host != "" {
		urlhost = tp.Host
	}
	if tp.Port != "" {
		urlport = tp.Port
	}
	turnAddr := net.JoinHostPort(urlhost, urlport)

	resolved, err := net.ResolveUDPAddr("udp", turnAddr)
	if err != nil {
		return false, fmt.Errorf("резолв TURN: %w", err)
	}
	c, err := net.DialUDP("udp", nil, resolved)
	if err != nil {
		return false, fmt.Errorf("подключение TURN UDP: %w", err)
	}
	defer c.Close()
	_ = c.SetReadBuffer(socketBufSize)
	_ = c.SetWriteBuffer(socketBufSize)
	turnConn := &connectedUDPConn{UDPConn: c, fail: sessCancel}

	log.Printf("[СЕССИЯ #%d] TURN UDP (%s)", sessionID, turnAddr)

	var addrFamily turn.RequestedAddressFamily
	if peer.IP.To4() != nil {
		addrFamily = turn.RequestedAddressFamilyIPv4
	} else {
		addrFamily = turn.RequestedAddressFamilyIPv6
	}

	// Pion's default stdnet.NewNet enumerates network interfaces through
	// NETLINK_ROUTE. Some Huawei/Honor ROMs deny that operation to apps.
	// The zero-value stdnet.Net provides everything this UDP client uses
	// without performing the unnecessary interface enumeration.
	tc, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr:         turnAddr,
		TURNServerAddr:         turnAddr,
		Conn:                   turnConn,
		Net:                    new(stdnet.Net),
		Username:               creds.User,
		Password:               creds.Pass,
		RequestedAddressFamily: addrFamily,
		LoggerFactory:          &NullLoggerFactory{},
	})
	if err != nil {
		return false, fmt.Errorf("TURN клиент: %w", err)
	}
	defer tc.Close()

	if err = tc.Listen(); err != nil {
		return false, fmt.Errorf("TURN Listen: %w", err)
	}

	relay, err := tc.Allocate()
	if err != nil {
		if isAuthError(err) {
			handleAuthError(creds.CacheStreamID)
		}
		errStr := err.Error()
		if strings.Contains(errStr, "Quota") || strings.Contains(errStr, "486") {
			return false, fmt.Errorf("TURN квота: %w", err)
		}
		return false, fmt.Errorf("TURN Allocate: %w", err)
	}
	defer relay.Close()

	getStreamCache(creds.CacheStreamID).errorCount.Store(0)

	log.Printf("[СЕССИЯ #%d] Relay: %s", sessionID, relay.LocalAddr())

	pipeA, pipeB := connutil.LimitedAsyncPacketPipe(relayPipeBuffer)
	defer pipeA.Close()

	var sessionWg sync.WaitGroup
	sessionWg.Add(1)
	go func() {
		defer sessionWg.Done()
		t := time.NewTicker(10 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-sessCtx.Done():
				return
			case <-t.C:
				tc.SendBindingRequest()
			}
		}
	}()

	var relayWg sync.WaitGroup
	relayWg.Add(2)

	useWrap := len(tp.WrapKey) == wrapKeyLen

	var obfsCfg *ObfsConfig
	var obfsWriteState *ObfsState
	if useWrap {
		obfsCfg = NewObfsConfig(tp.ObfsMode)
		obfsWriteState = NewObfsState()
	}

	stopRelay := context.AfterFunc(sessCtx, func() {
		_ = relay.SetReadDeadline(time.Now())
		_ = pipeA.Close()
	})
	defer stopRelay()

	go func() {
		defer relayWg.Done()
		defer sessCancel(nil)

		readBufLen := readBufSize + 80
		buf := make([]byte, readBufLen)
		plain := make([]byte, readBufSize)
		for {
			n, _, readErr := relay.ReadFrom(buf)
			if readErr != nil {
				sessCancel(fmt.Errorf("чтение TURN relay: %w", readErr))
				return
			}
			payload := buf[:n]
			if useWrap {
				if !obfsIsRTPPacket(payload) {
					log.Printf("[СЕССИЯ #%d] OBFS unwrap: unexpected packet (n=%d)", sessionID, n)
					continue
				}
				m, wrapErr := obfsUnwrapPacket(tp.WrapKey, payload, plain)
				if wrapErr != nil {
					log.Printf("[СЕССИЯ #%d] OBFS unwrap: %v (n=%d)", sessionID, wrapErr, n)
					continue
				}
				payload = plain[:m]
			}
			if _, writeErr := pipeA.WriteTo(payload, peer); writeErr != nil {
				sessCancel(fmt.Errorf("запись TURN → DTLS: %w", writeErr))
				return
			}
		}
	}()

	go func() {
		defer relayWg.Done()
		defer sessCancel(nil)
		b := make([]byte, readBufSize)
		for {
			n, _, readErr := pipeA.ReadFrom(b)
			if readErr != nil {
				sessCancel(fmt.Errorf("чтение DTLS → TURN: %w", readErr))
				return
			}
			out := b[:n]
			if useWrap {
				if obfsCfg != nil && obfsWriteState != nil {
					wrapped, wrapErr := obfsWrapPacket(tp.WrapKey, out, obfsCfg, obfsWriteState)
					if wrapErr != nil {
						log.Printf("[СЕССИЯ #%d] OBFS wrap: %v", sessionID, wrapErr)
						return
					}
					out = wrapped
				}
			}
			if _, writeErr := relay.WriteTo(out, peer); writeErr != nil {
				sessCancel(fmt.Errorf("запись в TURN relay: %w", writeErr))
				return
			}
		}
	}()

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

	dtlsConn, err := dtls.Client(pipeB, peer, dtlsCfg)
	if err != nil {
		<-handshakeSem
		return false, fmt.Errorf("DTLS клиент: %w", err)
	}
	defer dtlsConn.Close()
	stopDTLS := context.AfterFunc(sessCtx, func() {
		_ = dtlsConn.SetDeadline(time.Now())
	})
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
			log.Printf("[ВОРКЕР #%d] Ошибка конфига: %v", sessionID, confErr)
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
			log.Printf("[ВОРКЕР #%d] Сервер ещё не выдал WireGuard-конфиг, повторим позже", sessionID)
		}
	}

	slot := &WorkerSlot{
		ID:     sessionID,
		SendCh: make(chan []byte, workerSendBuf),
	}
	d.Register(slot)
	rememberUsableTurnCredentials(creds)
	log.Printf("[ВОРКЕР #%d] [READY] Туннель готов к работе ✓", sessionID)
	emitReady()

	var proxyWg sync.WaitGroup
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
			_ = pipeB.SetWriteDeadline(deadline)
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
	d.Unregister(slot)
	_ = pipeA.Close()
	_ = relay.Close()
	tc.Close()
	_ = c.Close()
	proxyWg.Wait()
	relayWg.Wait()
	sessionWg.Wait()
	log.Printf("[СЕССИЯ #%d] Завершена", sessionID)
	return configDelivered, context.Cause(sessCtx)
}
