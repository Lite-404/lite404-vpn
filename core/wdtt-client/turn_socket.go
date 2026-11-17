package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/pion/stun/v3"
)

type connectedUDPConn struct {
	*net.UDPConn
	fail           func(error)
	refreshMu      sync.Mutex
	refreshID      [stun.TransactionIDSize]byte
	refreshStarted time.Time
}

func (c *connectedUDPConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	c.noteRequest(p)
	return c.Write(p)
}

func (c *connectedUDPConn) noteRequest(p []byte) {
	if turnMessageType(p) == stun.NewType(stun.MethodRefresh, stun.ClassRequest) {
		c.refreshMu.Lock()
		if c.refreshStarted.IsZero() || !bytes.Equal(c.refreshID[:], p[8:20]) {
			copy(c.refreshID[:], p[8:20])
			c.refreshStarted = time.Now()
		}
		c.refreshMu.Unlock()
	}
}

func (c *connectedUDPConn) refreshOverdue(now time.Time, timeout time.Duration) bool {
	c.refreshMu.Lock()
	defer c.refreshMu.Unlock()
	return !c.refreshStarted.IsZero() && now.Sub(c.refreshStarted) >= timeout
}

func (c *connectedUDPConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, addr, err := c.UDPConn.ReadFrom(p)
	if err != nil {
		c.fail(fmt.Errorf("чтение сокета TURN: %w", err))
	} else if err := c.inspectRefresh(p[:n]); err != nil {
		c.fail(err)
	}
	return n, addr, err
}

func turnMessageType(p []byte) stun.MessageType {
	var kind stun.MessageType
	if stun.IsMessage(p) {
		kind.ReadValue(binary.BigEndian.Uint16(p[:2]))
	}
	return kind
}

func (c *connectedUDPConn) inspectRefresh(p []byte) error {
	kind := turnMessageType(p)
	if kind.Method != stun.MethodRefresh ||
		(kind.Class != stun.ClassSuccessResponse && kind.Class != stun.ClassErrorResponse) {
		return nil
	}
	var id [stun.TransactionIDSize]byte
	copy(id[:], p[8:20])
	c.refreshMu.Lock()
	matches := !c.refreshStarted.IsZero() && c.refreshID == id
	if matches {
		c.refreshStarted = time.Time{}
	}
	c.refreshMu.Unlock()
	if !matches || kind.Class == stun.ClassSuccessResponse {
		return nil
	}

	// Pion 5.0.2 can leave the allocation open even on a terminal Refresh error.
	msg := &stun.Message{Raw: p}
	var code stun.ErrorCodeAttribute
	if msg.Decode() != nil || code.GetFrom(msg) != nil {
		return nil
	}
	switch code.Code {
	case stun.CodeUnauthorized, stun.CodeWrongCredentials:
		return fmt.Errorf("TURN Refresh: invalid credential (код %d)", code.Code)
	case stun.CodeAllocMismatch:
		return fmt.Errorf("TURN Refresh: аллокация больше не существует (код %d)", code.Code)
	default:
		// A stale nonce and transient server errors are handled by Pion.
		return nil
	}
}
