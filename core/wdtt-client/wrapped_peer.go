package main

import (
	"net"
	"sync"
)

// DTLS writes directly to TURN. In particular CloseNotify must reach the old
// server before closing its virtual peer, allowing an immediate return to it.
type wrappedPeer struct {
	net.PacketConn
	key        []byte
	cfg        *ObfsConfig
	state      *ObfsState
	mu         sync.Mutex
	readMu     sync.Mutex
	readBuffer []byte
}

func newWrappedPeer(conn net.PacketConn, key []byte, mode string) *wrappedPeer {
	p := &wrappedPeer{PacketConn: conn, key: key}
	if len(key) == wrapKeyLen {
		p.cfg = NewObfsConfig(mode)
		p.state = NewObfsState()
		p.readBuffer = make([]byte, readBufSize+80)
	}
	return p
}
func (p *wrappedPeer) WriteTo(data []byte, to net.Addr) (int, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.cfg == nil {
		return p.PacketConn.WriteTo(data, to)
	}
	wrapped, err := obfsWrapPacket(p.key, data, p.cfg, p.state)
	if err != nil {
		return 0, err
	}
	_, err = p.PacketConn.WriteTo(wrapped, to)
	if err != nil {
		return 0, err
	}
	return len(data), nil
}
func (p *wrappedPeer) ReadFrom(data []byte) (int, net.Addr, error) {
	if p.cfg == nil {
		return p.PacketConn.ReadFrom(data)
	}
	p.readMu.Lock()
	defer p.readMu.Unlock()
	for {
		n, from, err := p.PacketConn.ReadFrom(p.readBuffer)
		if err != nil {
			return 0, from, err
		}
		if !obfsIsRTPPacket(p.readBuffer[:n]) {
			continue
		}
		plain, err := obfsUnwrapPacket(p.key, p.readBuffer[:n], data)
		if err == nil {
			return plain, from, nil
		}
	}
}
