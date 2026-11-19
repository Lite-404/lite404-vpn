package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
	"github.com/pion/stun/v3"
	"github.com/pion/turn/v5"
)

type allocationCounter struct {
	net.PacketConn
	count atomic.Int32
}

func (c *allocationCounter) ReadFrom(p []byte) (int, net.Addr, error) {
	n, a, e := c.PacketConn.ReadFrom(p)
	if e == nil && turnMessageType(p[:n]) == stun.NewType(stun.MethodAllocate, stun.ClassRequest) {
		c.count.Add(1)
	}
	return n, a, e
}

func TestSwitchReusesAllocationAndKeepsOldServerUntilCommit(t *testing.T) {
	for _, workers := range []int{1, 30} {
		t.Run(fmt.Sprint(workers), func(t *testing.T) { testPeerSwitch(t, workers) })
	}
}
func testPeerSwitch(t *testing.T, workers int) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	ip := net.IPv4(127, 0, 0, 1)
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: ip})
	if err != nil {
		t.Fatal(err)
	}
	socket.SetReadBuffer(socketBufSize)
	socket.SetWriteBuffer(socketBufSize)
	counter := &allocationCounter{PacketConn: socket}
	server, err := turn.NewServer(turn.ServerConfig{Realm: "test", AuthHandler: func(r *turn.RequestAttributes) (string, []byte, bool) {
		return "user", turn.GenerateAuthKey("user", "test", "pass"), true
	}, LoggerFactory: &NullLoggerFactory{},
		PacketConnConfigs: []turn.PacketConnConfig{{PacketConn: counter, RelayAddressGenerator: &turn.RelayAddressGeneratorStatic{RelayAddress: ip, Address: ip.String()}}}})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	var closedA atomic.Int32
	makePeer := func(label string) net.Listener {
		cert, e := selfsign.GenerateSelfSigned()
		if e != nil {
			t.Fatal(e)
		}
		listener, e := dtls.Listen("udp4", &net.UDPAddr{IP: ip}, &dtls.Config{Certificates: []tls.Certificate{cert}, ExtendedMasterSecret: dtls.RequireExtendedMasterSecret})
		if e != nil {
			t.Fatal(e)
		}
		go func() {
			for {
				conn, e := listener.Accept()
				if e != nil {
					return
				}
				go func() {
					defer conn.Close()
					defer func() {
						if label == "A" {
							closedA.Add(1)
						}
					}()
					stop := context.AfterFunc(ctx, func() { conn.Close() })
					defer stop()
					buffer := make([]byte, 4096)
					for {
						n, e := conn.Read(buffer)
						if e != nil {
							return
						}
						message := string(buffer[:n])
						if strings.HasPrefix(message, "GETCONF:") {
							port := strings.Split(strings.TrimPrefix(message, "GETCONF:"), "|")[0]
							conn.Write([]byte("server=" + label + "\nEndpoint = 127.0.0.1:" + port))
							continue
						}
						conn.Write(append([]byte(label+":"), buffer[:n]...))
					}
				}()
			}
		}()
		return listener
	}
	a, b := wrapTestPeer(t, ctx, makePeer("A"), "old-password"), wrapTestPeer(t, ctx, makePeer("B"), "new-password")
	defer a.Close()
	defer b.Close()
	local, e := net.ListenPacket("udp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	stats := NewStats()
	d := NewDispatcher(ctx, local, stats)
	_, port, _ := net.SplitHostPort(local.LocalAddr().String())
	initial := make(chan string, 1)
	key, _ := deriveWrapKey("old-password")
	tp := &TurnParams{WrapKey: key, ObfsMode: "audio"}
	m := newPeerSwitcher(ctx, tp, a.Addr().(*net.UDPAddr), d, port, "device", "old-password", stats, initial)
	events := make(chan struct {
		kind    eventType
		payload map[string]any
	}, 10)
	m.emit = func(kind eventType, payload map[string]any) {
		events <- struct {
			kind    eventType
			payload map[string]any
		}{kind, payload}
	}
	tp.Switcher = m
	worker := make(chan error, workers)
	for id := 1; id <= workers; id++ {
		go func(id int) {
			_, err := RunSession(ctx, tp, a.Addr().(*net.UDPAddr), d, port, true, initial, id, &Credentials{User: "user", Pass: "pass", TurnURLs: []string{socket.LocalAddr().String()}, CacheStreamID: 9400}, "device", "old-password", stats)
			worker <- err
		}(id)
	}
	defer func() {
		cancel()
		m.Close()
		for range workers {
			select {
			case <-worker:
			case <-time.After(time.Second):
				t.Error("TURN worker leaked")
			}
		}
	}()
	waitWorkers := func(disp *Dispatcher) {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		for len(*disp.workers.Load()) != workers && time.Now().Before(deadline) {
			time.Sleep(10 * time.Millisecond)
		}
		if got := len(*disp.workers.Load()); got != workers {
			t.Fatalf("workers: got %d, want %d", got, workers)
		}
	}
	select {
	case <-initial:
	case <-ctx.Done():
		t.Fatal("initial config unavailable")
	}
	waitWorkers(d)
	allocations := counter.count.Load()
	if allocations == 0 {
		t.Fatal("no TURN allocation")
	}
	echo := func(address net.Addr, label string) {
		conn, e := net.Dial("udp", address.String())
		if e != nil {
			t.Fatal(e)
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(2 * time.Second))
		conn.Write([]byte("probe"))
		buf := make([]byte, 100)
		n, e := conn.Read(buf)
		if e != nil || string(buf[:n]) != label+":probe" {
			t.Fatalf("%s: %q %v", label, buf[:n], e)
		}
	}
	waitEvent := func(kind eventType) map[string]any {
		for {
			select {
			case e := <-events:
				if e.kind == kind {
					t.Logf("event %s id=%v", kind, e.payload["id"])
					return e.payload
				}
			case <-ctx.Done():
				t.Fatalf("missing %s", kind)
			}
		}
	}
	echo(local.LocalAddr(), "A")
	request, _ := json.Marshal(switchRequest{ID: "b", Peer: b.Addr().String(), Password: "new-password"})
	m.request(string(request))
	waitEvent("SWITCH_CONFIG")
	m.mu.Lock()
	nextAddress := m.pending.d.localConn.LocalAddr()
	m.mu.Unlock()
	echo(local.LocalAddr(), "A")
	echo(nextAddress, "B")
	m.acknowledge("b", true)
	waitEvent("SWITCHED")
	m.mu.Lock()
	activeB := m.active.d
	m.mu.Unlock()
	waitWorkers(activeB)
	echo(nextAddress, "B")
	if counter.count.Load() != allocations {
		t.Fatal("switch allocated TURN again")
	}
	// Application rejects a subsequent config: B must continue unchanged.
	request, _ = json.Marshal(switchRequest{ID: "a-abort", Peer: a.Addr().String(), Password: "old-password"})
	m.request(string(request))
	waitEvent("SWITCH_CONFIG")
	echo(nextAddress, "B")
	m.acknowledge("a-abort", false)
	waitEvent("SWITCH_FAILED")
	echo(nextAddress, "B")
	// Returning to A immediately after abort must not retain a stale peer pipe.
	request, _ = json.Marshal(switchRequest{ID: "a", Peer: a.Addr().String(), Password: "old-password"})
	m.request(string(request))
	waitEvent("SWITCH_CONFIG")
	m.mu.Lock()
	addressA := m.pending.d.localConn.LocalAddr()
	m.mu.Unlock()
	m.acknowledge("a", true)
	waitEvent("SWITCHED")
	m.mu.Lock()
	activeA := m.active.d
	m.mu.Unlock()
	waitWorkers(activeA)
	echo(addressA, "A")
	if counter.count.Load() != allocations {
		t.Fatal("repeated switch allocated TURN again")
	}
}

// A real UDP relay that adds the production RTP/AEAD framing around the DTLS
// server. Each TURN relay address retains its own UDP association and sequence.
type wrappedTestListener struct {
	net.Listener
	socket *net.UDPConn
}

func (l *wrappedTestListener) Addr() net.Addr { return l.socket.LocalAddr() }
func (l *wrappedTestListener) Close() error   { l.socket.Close(); return l.Listener.Close() }
func wrapTestPeer(t *testing.T, ctx context.Context, inner net.Listener, password string) net.Listener {
	t.Helper()
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	socket.SetReadBuffer(socketBufSize)
	socket.SetWriteBuffer(socketBufSize)
	key, _ := deriveWrapKey(password)
	var mu sync.Mutex
	peers := map[string]*net.UDPConn{}
	context.AfterFunc(ctx, func() {
		socket.Close()
		mu.Lock()
		defer mu.Unlock()
		for _, c := range peers {
			c.Close()
		}
	})
	go func() {
		packet, plain := make([]byte, 4096), make([]byte, 4096)
		for {
			n, from, err := socket.ReadFromUDP(packet)
			if err != nil {
				return
			}
			n, err = obfsUnwrapPacket(key, packet[:n], plain)
			if err != nil {
				continue
			}
			mu.Lock()
			conn := peers[from.String()]
			if conn == nil {
				conn, err = net.DialUDP("udp4", nil, inner.Addr().(*net.UDPAddr))
				if err != nil {
					mu.Unlock()
					return
				}
				peers[from.String()] = conn
				go func(conn *net.UDPConn, to *net.UDPAddr) {
					buf := make([]byte, 4096)
					state := NewObfsState()
					cfg := NewObfsConfig("audio")
					for {
						n, err := conn.Read(buf)
						if err != nil {
							return
						}
						out, err := obfsWrapPacket(key, buf[:n], cfg, state)
						if err == nil {
							socket.WriteToUDP(out, to)
						}
					}
				}(conn, from)
			}
			mu.Unlock()
			conn.Write(plain[:n])
		}
	}()
	return &wrappedTestListener{Listener: inner, socket: socket}
}

func TestWarmServerCacheIsBoundedAndExpires(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	makeGeneration := func(port int) *peerGeneration {
		gctx, stop := context.WithCancel(ctx)
		conn, err := net.ListenPacket("udp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		return &peerGeneration{ctx: gctx, cancel: stop, peer: &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port},
			d: NewDispatcher(gctx, conn, NewStats()), configText: "test config"}
	}
	m := &peerSwitcher{ctx: ctx, active: makeGeneration(1), cacheTTL: 30 * time.Millisecond}
	defer m.Close()
	entries := []*peerGeneration{makeGeneration(2), makeGeneration(3), makeGeneration(4)}
	for _, g := range entries {
		m.cache(g)
	}
	m.mu.Lock()
	size := len(m.cached)
	m.mu.Unlock()
	if size != 2 {
		t.Fatalf("unbounded cache: %d", size)
	}
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		m.mu.Lock()
		size = len(m.cached)
		m.mu.Unlock()
		if size == 0 {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if size != 0 {
		t.Fatal("inactive sessions did not expire")
	}
	for _, g := range entries {
		if g.ctx.Err() == nil {
			t.Fatal("cached session leaked")
		}
	}
}
