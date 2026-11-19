package main

import (
	"context"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/cbeuw/connutil"
	"github.com/pion/transport/v4/stdnet"
	"github.com/pion/turn/v5"
)

// One allocation survives changes of the WDTT server. Only this owner reads
// from TURN; packets are demultiplexed by their real source before unwrapping.
type turnLease struct {
	ctx      context.Context
	cancel   context.CancelCauseFunc
	once     sync.Once
	mu       sync.Mutex
	peers    map[string]*leasePeer
	closedAt map[string]time.Time
	conn     *net.UDPConn
	client   *turn.Client
	relay    net.PacketConn
	turnConn *connectedUDPConn
	creds    *Credentials
	id       int
	ipv4     bool
	wg       sync.WaitGroup
}

type leasePeer struct {
	net.PacketConn
	in      net.PacketConn
	lease   *turnLease
	address *net.UDPAddr
	once    sync.Once
}

func (p *leasePeer) WriteTo(data []byte, _ net.Addr) (int, error) {
	if err := p.lease.ctx.Err(); err != nil {
		return 0, err
	}
	return p.lease.relay.WriteTo(data, p.address)
}
func (p *leasePeer) Close() error {
	p.once.Do(func() {
		p.lease.mu.Lock()
		if p.lease.peers[p.address.String()] == p {
			delete(p.lease.peers, p.address.String())
			if p.lease.closedAt == nil {
				p.lease.closedAt = make(map[string]time.Time)
			}
			p.lease.closedAt[p.address.String()] = time.Now()
		}
		p.lease.mu.Unlock()
		p.in.Close()
	})
	return nil
}

func openTurnLease(ctx context.Context, tp *TurnParams, peer *net.UDPAddr, id int, creds *Credentials) (lease *turnLease, err error) {
	if len(creds.TurnURLs) == 0 {
		return nil, fmt.Errorf("нет TURN URL в учетных данных")
	}
	host, port, err := net.SplitHostPort(creds.TurnURLs[id%len(creds.TurnURLs)])
	if err != nil {
		return nil, err
	}
	if tp.Host != "" {
		host = tp.Host
	}
	if tp.Port != "" {
		port = tp.Port
	}
	address := net.JoinHostPort(host, port)
	resolved, err := net.ResolveUDPAddr("udp", address)
	if err != nil {
		return nil, err
	}
	c, err := net.DialUDP("udp", nil, resolved)
	if err != nil {
		return nil, err
	}
	c.SetReadBuffer(socketBufSize)
	c.SetWriteBuffer(socketBufSize)
	lctx, cancel := context.WithCancelCause(ctx)
	l := &turnLease{ctx: lctx, cancel: cancel, conn: c, id: id, creds: creds, peers: make(map[string]*leasePeer), ipv4: peer.IP.To4() != nil}
	defer func() {
		if err != nil {
			l.Close(err)
		}
	}()
	l.turnConn = &connectedUDPConn{UDPConn: c, fail: cancel}
	family := turn.RequestedAddressFamilyIPv6
	if l.ipv4 {
		family = turn.RequestedAddressFamilyIPv4
	}
	l.client, err = turn.NewClient(&turn.ClientConfig{STUNServerAddr: address, TURNServerAddr: address, Conn: l.turnConn,
		Net: new(stdnet.Net), Username: creds.User, Password: creds.Pass, RequestedAddressFamily: family, LoggerFactory: &NullLoggerFactory{}})
	if err != nil {
		return nil, err
	}
	if err = l.client.Listen(); err != nil {
		return nil, err
	}
	l.relay, err = l.client.Allocate()
	if err != nil {
		if isAuthError(err) {
			handleAuthError(creds.CacheStreamID)
		}
		if strings.Contains(err.Error(), "Quota") || strings.Contains(err.Error(), "486") {
			return nil, fmt.Errorf("TURN квота: %w", err)
		}
		return nil, fmt.Errorf("TURN Allocate: %w", err)
	}
	getStreamCache(creds.CacheStreamID).errorCount.Store(0)
	l.wg.Add(2)
	go l.readLoop()
	go func() {
		defer l.wg.Done()
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-lctx.Done():
				return
			case <-ticker.C:
				l.client.SendBindingRequest()
			}
		}
	}()
	context.AfterFunc(lctx, func() { l.Close(context.Cause(lctx)) })
	return l, nil
}
func (l *turnLease) openPeer(address *net.UDPAddr) (*leasePeer, error) {
	l.mu.Lock()
	// A TURN allocation keeps the same source port. Allow a retired server's
	// DTLS listener to release that UDP association after CloseNotify before
	// sending a fresh ClientHello to exactly the same address.
	quiet := 200*time.Millisecond - time.Since(l.closedAt[address.String()])
	if quiet > 0 {
		l.mu.Unlock()
		select {
		case <-time.After(quiet):
		case <-l.ctx.Done():
			return nil, context.Cause(l.ctx)
		}
		l.mu.Lock()
	}
	defer l.mu.Unlock()
	if err := l.ctx.Err(); err != nil {
		return nil, err
	}
	if (address.IP.To4() != nil) != l.ipv4 {
		return nil, fmt.Errorf("новый сервер использует другое семейство IP")
	}
	if l.peers[address.String()] != nil {
		return nil, fmt.Errorf("соединение с этим сервером уже существует")
	}
	in, out := connutil.LimitedAsyncPacketPipe(relayPipeBuffer)
	p := &leasePeer{PacketConn: out, in: in, lease: l, address: address}
	l.peers[address.String()] = p
	return p, nil
}
func (l *turnLease) readLoop() {
	defer l.wg.Done()
	buffer := make([]byte, readBufSize+80)
	for {
		n, from, err := l.relay.ReadFrom(buffer)
		if err != nil {
			l.cancel(fmt.Errorf("чтение TURN relay: %w", err))
			return
		}
		l.mu.Lock()
		peer := l.peers[from.String()]
		l.mu.Unlock()
		if peer != nil {
			_, _ = peer.in.WriteTo(buffer[:n], from)
		}
	}
}
func (l *turnLease) Close(reason error) {
	l.once.Do(func() {
		l.cancel(reason)
		l.mu.Lock()
		peers := make([]*leasePeer, 0, len(l.peers))
		for _, p := range l.peers {
			peers = append(peers, p)
		}
		l.mu.Unlock()
		for _, p := range peers {
			p.Close()
		}
		if l.relay != nil {
			l.relay.Close()
		}
		if l.client != nil {
			l.client.Close()
		}
		l.conn.Close()
	})
}

func RunSession(ctx context.Context, tp *TurnParams, peer *net.UDPAddr, d *Dispatcher, localPort string,
	getConfig bool, configCh chan<- string, id int, creds *Credentials, device, password string, stats *Stats) (bool, error) {
	if tp.Switcher != nil {
		peer = tp.Switcher.currentPeer()
	}
	lease, err := openTurnLease(ctx, tp, peer, id, creds)
	if err != nil {
		return false, err
	}
	defer func() { lease.Close(nil); lease.wg.Wait() }()
	if tp.Switcher == nil {
		return runPeerSession(ctx, lease, tp, peer, d, localPort, getConfig, configCh, id, creds, device, password, stats, nil)
	}
	tp.Switcher.register(lease)
	defer tp.Switcher.unregister(lease)
	<-lease.ctx.Done()
	return true, context.Cause(lease.ctx)
}
