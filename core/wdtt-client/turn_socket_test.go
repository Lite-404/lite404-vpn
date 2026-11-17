package main

import (
	"strings"
	"testing"
	"time"

	"github.com/pion/stun/v3"
)

func TestTURNRefreshRefusalOnlyCancelsItsOwnAllocation(t *testing.T) {
	conn := &connectedUDPConn{}
	request := stun.MustBuild(stun.NewType(stun.MethodRefresh, stun.ClassRequest), stun.TransactionID)
	reply := func(id [stun.TransactionIDSize]byte, code stun.ErrorCode) []byte {
		return stun.MustBuild(
			stun.NewType(stun.MethodRefresh, stun.ClassErrorResponse),
			stun.NewTransactionIDSetter(id),
			stun.ErrorCodeAttribute{Code: code, Reason: []byte("test")},
		).Raw
	}

	conn.noteRequest(request.Raw)
	late := time.Now().Add(keepaliveTimeout + time.Second)
	if !conn.refreshOverdue(late, keepaliveTimeout) {
		t.Fatal("an unanswered refresh should expire")
	}
	if err := conn.inspectRefresh(reply(stun.NewTransactionID(), stun.CodeAllocMismatch)); err != nil {
		t.Fatalf("unrelated response stopped the worker: %v", err)
	}
	if err := conn.inspectRefresh(reply(request.TransactionID, stun.CodeStaleNonce)); err != nil {
		t.Fatalf("Pion can retry a stale nonce: %v", err)
	}
	if conn.refreshOverdue(late, keepaliveTimeout) {
		t.Fatal("the nonce retry must clear the previous transaction")
	}
	conn.noteRequest(request.Raw)
	if err := conn.inspectRefresh(reply(request.TransactionID, stun.CodeAllocMismatch)); err == nil {
		t.Fatal("expired allocation was left in rotation")
	}
	conn.noteRequest(request.Raw)
	if err := conn.inspectRefresh(reply(request.TransactionID, stun.CodeWrongCredentials)); err == nil ||
		!strings.Contains(err.Error(), "invalid credential") {
		t.Fatalf("credential rejection should trigger reauthentication, got %v", err)
	}
}

func TestRetiredWorkerRejectsPacketsFromAnOlderDispatcherSnapshot(t *testing.T) {
	slot := &WorkerSlot{SendCh: make(chan []byte, 2)}
	if !slot.enqueue([]byte("queued")) {
		t.Fatal("live worker did not accept a packet")
	}
	slot.retire()
	if slot.enqueue([]byte("late")) {
		t.Fatal("retired worker received a packet")
	}
	if _, ok := <-slot.SendCh; ok {
		t.Fatal("retirement did not clear the remaining queue")
	}
}
