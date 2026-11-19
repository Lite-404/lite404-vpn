package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

type switchRequest struct {
	ID       string `json:"id"`
	Peer     string `json:"peer"`
	Password string `json:"password"`
}
type peerGeneration struct {
	ctx            context.Context
	cancel         context.CancelFunc
	id             string
	peer           *net.UDPAddr
	tp             TurnParams
	password, port string
	d              *Dispatcher
	config         chan string
	requested      atomic.Bool
	ack            chan bool
	wg             sync.WaitGroup
	sessions       map[*turnLease]bool
	ready          map[*turnLease]bool
	configText     string
}

func (g *peerGeneration) close() {
	g.cancel()
	if g.d != nil {
		g.d.Shutdown()
	}
	g.wg.Wait()
}

type peerSwitcher struct {
	ctx             context.Context
	mu              sync.Mutex
	active, pending *peerGeneration
	leases          map[*turnLease]bool
	stats           *Stats
	device          string
	initialConfig   chan<- string
	wg              sync.WaitGroup
	closed          bool
	emit            func(eventType, map[string]any)
	cached          map[string]*cachedPeer
	cacheTTL        time.Duration
}

type cachedPeer struct {
	generation *peerGeneration
	expires    time.Time
	timer      *time.Timer
}

// A small, expiring warm cache allows immediate returns without abandoning
// half-closed DTLS associations on the server. TURN allocations stay shared.
func (m *peerSwitcher) cache(g *peerGeneration) {
	m.mu.Lock()
	if m.active == g {
		m.mu.Unlock()
		return
	}
	if m.closed || g.ctx.Err() != nil || g.configText == "" {
		m.mu.Unlock()
		g.close()
		return
	}
	if m.cached == nil {
		m.cached = make(map[string]*cachedPeer)
	}
	key := g.peer.String()
	if old := m.cached[key]; old != nil {
		old.timer.Stop()
	}
	ttl := m.cacheTTL
	if ttl <= 0 {
		ttl = time.Minute
	}
	entry := &cachedPeer{generation: g, expires: time.Now().Add(ttl)}
	m.cached[key] = entry
	entry.timer = time.AfterFunc(ttl, func() {
		m.mu.Lock()
		if m.cached[key] != entry {
			m.mu.Unlock()
			return
		}
		delete(m.cached, key)
		m.mu.Unlock()
		g.close()
	})
	var retired *peerGeneration
	if len(m.cached) > 2 {
		var oldestKey string
		var oldest time.Time
		for k, v := range m.cached {
			if k != key && (oldestKey == "" || v.expires.Before(oldest)) {
				oldestKey, oldest = k, v.expires
			}
		}
		if victim := m.cached[oldestKey]; victim != nil {
			victim.timer.Stop()
			retired = victim.generation
			delete(m.cached, oldestKey)
		}
	}
	m.mu.Unlock()
	if retired != nil {
		retired.close()
	}
}

var activeSwitcher atomic.Pointer[peerSwitcher]

func newPeerSwitcher(ctx context.Context, tp *TurnParams, peer *net.UDPAddr, d *Dispatcher, port, device, password string,
	stats *Stats, config chan<- string) *peerSwitcher {
	gctx, cancel := context.WithCancel(ctx)
	g := &peerGeneration{ctx: gctx, cancel: cancel, peer: peer, tp: *tp, password: password, port: port, d: d, config: make(chan string, 1)}
	return &peerSwitcher{ctx: ctx, active: g, leases: make(map[*turnLease]bool), stats: stats, device: device, initialConfig: config, emit: emitEvent}
}
func (m *peerSwitcher) currentPeer() *net.UDPAddr {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.active.peer
}
func (m *peerSwitcher) register(l *turnLease) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		l.Close(context.Canceled)
		return
	}
	m.leases[l] = true
	g := m.active
	m.startPeerLocked(l, g, g.requested.CompareAndSwap(false, true), nil)
}
func (m *peerSwitcher) unregister(l *turnLease) { m.mu.Lock(); delete(m.leases, l); m.mu.Unlock() }
func (m *peerSwitcher) startPeerLocked(l *turnLease, g *peerGeneration, config bool, ready chan<- struct{}) {
	if g.sessions == nil {
		g.sessions = make(map[*turnLease]bool)
		g.ready = make(map[*turnLease]bool)
	}
	if g.sessions[l] {
		return
	}
	g.sessions[l] = true
	m.wg.Add(1)
	g.wg.Add(1)
	initial := g.id == ""
	go func() {
		defer m.wg.Done()
		defer g.wg.Done()
		delivered, err := runPeerSession(g.ctx, l, &g.tp, g.peer, g.d, g.port, config, g.config, l.id, l.creds, m.device, g.password, m.stats, func() {
			m.mu.Lock();g.ready[l]=true;m.mu.Unlock()
			if config && initial {
				select {
				case conf := <-g.config:
					m.mu.Lock()
					g.configText = conf
					m.mu.Unlock()
					select {
					case m.initialConfig <- conf:
					case <-g.ctx.Done():
					}
				default:
				}
			}
			if ready != nil {
				select {
				case ready <- struct{}{}:
				default:
				}
			}
		})
		if config && !delivered {
			g.requested.Store(false)
		}
		m.mu.Lock()
		delete(g.sessions, l)
		delete(g.ready,l)
		current := m.active == g && !m.closed
		m.mu.Unlock()
		if current && g.ctx.Err() == nil {
			l.Close(err)
		}
	}()
}
func (m *peerSwitcher) request(raw string) {
	var request switchRequest
	if json.Unmarshal([]byte(raw), &request) != nil || request.ID == "" || len(request.ID) > 80 || request.Password == "" {
		return
	}
	m.mu.Lock()
	if m.closed || m.pending != nil {
		m.mu.Unlock()
		m.fail(request.ID, "уже выполняется переключение")
		return
	}
	gctx, cancel := context.WithCancel(m.ctx)
	g := &peerGeneration{ctx: gctx, cancel: cancel, id: request.ID, password: request.Password, config: make(chan string, 1), ack: make(chan bool, 1)}
	m.pending = g
	m.wg.Add(1)
	m.mu.Unlock()
	go func() { defer m.wg.Done(); m.prepare(g, request) }()
}
func (m *peerSwitcher) fail(id, message string) {
	m.emit("SWITCH_FAILED", map[string]any{"id": id, "message": message})
}
func (m *peerSwitcher) acknowledge(id string, success bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if g := m.pending; g != nil && g.id == id {
		select {
		case g.ack <- success:
		default:
		}
	}
}
func (m *peerSwitcher) prepare(g *peerGeneration, request switchRequest) {
	committed := false
	failure := ""
	defer func() {
		if !committed {
			if g.configText != "" {
				m.cache(g)
			} else {
				g.close()
			}
		}
		m.mu.Lock()
		if m.pending == g {
			m.pending = nil
		}
		m.mu.Unlock()
		if failure != "" {
			m.fail(request.ID, failure)
		}
	}()
	fail := func(message string) { failure = message }
	host, port, err := net.SplitHostPort(request.Peer)
	if err != nil {
		fail("некорректный адрес сервера")
		return
	}
	portNumber, err := strconv.Atoi(port)
	if err != nil || portNumber < 1 || portNumber > 65535 {
		fail("некорректный порт сервера")
		return
	}
	resolveCtx, cancel := context.WithTimeout(g.ctx, 5*time.Second)
	addresses, err := net.DefaultResolver.LookupIPAddr(resolveCtx, host)
	cancel()
	if err != nil || len(addresses) == 0 {
		fail("не удалось найти новый сервер")
		return
	}
	m.mu.Lock()
	old := m.active
	for _, address := range addresses {
		if (address.IP.To4() != nil) == (old.peer.IP.To4() != nil) {
			g.peer = &net.UDPAddr{IP: address.IP, Port: portNumber, Zone: address.Zone}
			break
		}
	}
	if g.peer == nil || g.peer.String() == old.peer.String() {
		m.mu.Unlock()
		fail("для этого адреса требуется обычное переподключение")
		return
	}
	if entry := m.cached[g.peer.String()]; entry != nil && entry.generation.password == request.Password && entry.generation.ctx.Err() == nil {
		entry.timer.Stop()
		delete(m.cached, g.peer.String())
		g.cancel()
		g = entry.generation
		g.id = request.ID
		g.ack = make(chan bool, 1)
		m.pending = g
		m.mu.Unlock()
		m.offer(g, old, g.configText, &committed)
		return
	}
	g.tp = old.tp
	leases := make([]*turnLease, 0, len(m.leases))
	for l := range m.leases {
		if l.ctx.Err() == nil && old.ready[l] {
			leases = append(leases, l)
		}
	}
	m.mu.Unlock()
	if len(leases) == 0 {
		fail("нет готовых TURN-соединений")
		return
	}
	if len(g.tp.WrapKey) > 0 {
		key, err := deriveWrapKey(request.Password)
		if err != nil {
			fail("не удалось подготовить защищённое соединение")
			return
		}
		g.tp.WrapKey = key
	}
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		fail("не удалось открыть локальный порт")
		return
	}
	if udp, ok := conn.(*net.UDPConn); ok {
		udp.SetReadBuffer(socketBufSize)
		udp.SetWriteBuffer(socketBufSize)
	}
	_, g.port, _ = net.SplitHostPort(conn.LocalAddr().String())
	g.d = NewDispatcher(g.ctx, conn, m.stats)
	g.d.muted.Store(true)
	sort.Slice(leases, func(i, j int) bool { return leases[i].id < leases[j].id })
	ready := make(chan struct{}, len(leases))
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return
	}
	m.startPeerLocked(leases[0], g, true, ready)
	g.requested.Store(true)
	m.mu.Unlock()
	timeout := time.NewTimer(25 * time.Second)
	defer timeout.Stop()
	select {
	case <-ready:
	case <-timeout.C:
		fail("новый сервер не ответил, прежний остаётся подключён")
		return
	case <-g.ctx.Done():
		return
	}
	var config string
	select {
	case config = <-g.config:
		g.configText = config
	default:
		fail("новый сервер не выдал конфигурацию")
		return
	}
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return
	}
	for _, l := range leases[1:] {
		m.startPeerLocked(l, g, false, ready)
	}
	m.mu.Unlock()
	// Complete the handshakes before asking the app to commit. Aborting a
	// half-open DTLS handshake leaves the server's UDP association occupied
	// until its timeout, preventing a fast return to the same country.
	for range leases[1:] {
		select {
		case <-ready:
		case <-timeout.C:
			fail("не все потоки нового сервера готовы; прежний сервер сохранён")
			return
		case <-g.ctx.Done():
			return
		}
	}
	// The new generation has a separate local port, so its WireGuard packets
	// never go to the old server while the application applies the config.
	m.offer(g, old, config, &committed)
}

func (m *peerSwitcher) offer(g, old *peerGeneration, config string, committed *bool) {
	id := g.id
	m.emit("SWITCH_CONFIG", map[string]any{"id": id, "config": config})
	fail := func(message string) { m.fail(id, message) }
	ackTimer := time.NewTimer(30 * time.Second)
	defer ackTimer.Stop()
	select {
	case success := <-g.ack:
		if !success {
			fail("не удалось применить новую конфигурацию; сохранён прежний сервер")
			return
		}
	case <-ackTimer.C:
		fail("приложение не подтвердило переключение")
		return
	case <-g.ctx.Done():
		return
	}
	m.mu.Lock()
	if m.closed || m.active != old || len(*g.d.workers.Load()) == 0 {
		m.mu.Unlock()
		fail("новые соединения недоступны")
		return
	}
	old.d.muted.Store(true)
	m.active = g
	for l := range m.leases {
		if l.ctx.Err() == nil && !g.sessions[l] {
			m.startPeerLocked(l, g, false, nil)
		}
	}
	m.pending = nil
	g.d.muted.Store(false)
	g.d.publishWorkerCount(len(*g.d.workers.Load()))
	*committed = true
	m.mu.Unlock()
	m.cache(old)
	m.emit("SWITCHED", map[string]any{"id": id})
}
func (m *peerSwitcher) Close() {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return
	}
	m.closed = true
	active, pending := m.active, m.pending
	var cached []*peerGeneration
	for _, entry := range m.cached {
		entry.timer.Stop()
		cached = append(cached, entry.generation)
	}
	m.cached = nil
	for l := range m.leases {
		l.Close(errors.New("клиент остановлен"))
	}
	m.mu.Unlock()
	active.close()
	if pending != nil {
		pending.cancel()
	}
	for _, g := range cached {
		g.close()
	}
	m.wg.Wait()
}

func (m *peerSwitcher) control(line string) error {
	var result struct {
		ID      string `json:"id"`
		Success bool   `json:"success"`
	}
	if err := json.Unmarshal([]byte(line), &result); err != nil {
		return fmt.Errorf("invalid switch response")
	}
	m.acknowledge(result.ID, result.Success)
	return nil
}
