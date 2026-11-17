package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
	"github.com/pion/stun/v3"
	"github.com/pion/turn/v5"
)

func TestSessionRelaysBothDirectionsAndReleasesItsWorkerOnStop(t *testing.T) {
	testSessionCleanup(t, false)
}

func TestSessionRetiresWhenTURNStopsRenewingItsAllocation(t *testing.T) {
	testSessionCleanup(t, true)
}

func testSessionCleanup(t *testing.T, rejectRenewal bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	ip := net.IPv4(127, 0, 0, 1)
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: ip})
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	const user, realm, password = "loopback", "turn-test", "test-only"
	testRelay := &renewalTestRelay{PacketConn: socket}
	server, err := turn.NewServer(turn.ServerConfig{
		Realm:              realm,
		AllocationLifetime: 3 * time.Second,
		AuthHandler: func(r *turn.RequestAttributes) (string, []byte, bool) {
			return user, turn.GenerateAuthKey(user, realm, password), r.Username == user
		},
		LoggerFactory: &NullLoggerFactory{},
		PacketConnConfigs: []turn.PacketConnConfig{{
			PacketConn:            testRelay,
			RelayAddressGenerator: &turn.RelayAddressGeneratorStatic{RelayAddress: ip, Address: ip.String()},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()

	cert, err := selfsign.GenerateSelfSigned()
	if err != nil {
		t.Fatal(err)
	}
	peer, err := dtls.Listen("udp4", &net.UDPAddr{IP: ip}, &dtls.Config{
		Certificates:         []tls.Certificate{cert},
		ExtendedMasterSecret: dtls.RequireExtendedMasterSecret,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()

	d := &Dispatcher{stats: NewStats(), ReturnCh: make(chan []byte, 1)}
	empty := []*WorkerSlot{}
	d.workers.Store(&empty)
	payload := []byte("turn loopback payload")
	go func() {
		conn, err := peer.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(6 * time.Second))
		pkt := make([]byte, 1500)
		if n, err := conn.Read(pkt); err == nil {
			_, _ = conn.Write(pkt[:n])
			<-ctx.Done()
		}
	}()

	done := make(chan error, 1)
	go func() {
		creds := Credentials{User: user, Pass: password, TurnURLs: []string{socket.LocalAddr().String()}, CacheStreamID: 9100}
		_, err := RunSession(ctx, &TurnParams{}, peer.Addr().(*net.UDPAddr), d, "0",
			false, nil, 1, &creds, "", "", d.stats)
		done <- err
	}()
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for len(*d.workers.Load()) == 0 {
		select {
		case err := <-done:
			t.Fatalf("session stopped during setup: %v", err)
		case <-ctx.Done():
			t.Fatal("session did not register")
		case <-ticker.C:
		}
	}
	slot := (*d.workers.Load())[0]
	if !slot.enqueue(payload) {
		t.Fatal("session did not accept outbound data")
	}
	select {
	case pkt := <-d.ReturnCh:
		defer putPktBuf(pkt)
		if !bytes.Equal(pkt, payload) {
			t.Fatalf("wrong relayed data: %q", pkt)
		}
	case <-ctx.Done():
		t.Fatal("outbound data did not return through TURN")
	}
	if rejectRenewal {
		testRelay.reject.Store(true)
	} else {
		cancel()
	}
	select {
	case err := <-done:
		expectedCause := errors.Is(err, context.Canceled)
		if rejectRenewal {
			expectedCause = err != nil && strings.Contains(err.Error(), "TURN Refresh: invalid credential")
		}
		if !expectedCause || len(*d.workers.Load()) != 0 || slot.enqueue(payload) {
			t.Fatalf("session did not unregister on stop: %v", err)
		}
	case <-time.After(4 * time.Second):
		t.Fatal("session I/O did not unblock on stop")
	}
}

type renewalTestRelay struct {
	net.PacketConn
	reject atomic.Bool
}

func (r *renewalTestRelay) ReadFrom(p []byte) (int, net.Addr, error) {
	for {
		n, from, err := r.PacketConn.ReadFrom(p)
		if err != nil || !r.reject.Load() || turnMessageType(p[:n]) != stun.NewType(stun.MethodRefresh, stun.ClassRequest) {
			return n, from, err
		}
		var id [stun.TransactionIDSize]byte
		copy(id[:], p[8:20])
		refusal := stun.MustBuild(
			stun.NewType(stun.MethodRefresh, stun.ClassErrorResponse),
			stun.NewTransactionIDSetter(id),
			stun.ErrorCodeAttribute{Code: stun.CodeWrongCredentials, Reason: []byte("expired test credentials")},
		)
		if _, err = r.PacketConn.WriteTo(refusal.Raw, from); err != nil {
			return 0, nil, err
		}
	}
}
