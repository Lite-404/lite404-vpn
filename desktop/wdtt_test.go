package main

import (
	"os/exec"
	"testing"
	"time"
)

func TestWdttStructuredStatsAreImmediate(t *testing.T) {
	cmd := &exec.Cmd{}
	w := &Wdtt{cmd: cmd, workers: 30}
	w.handleEvent(cmd, `__WDTT_EVENT__|STATS|{"active":30,"bytes_up":123,"bytes_down":456}`, nil)
	if w.State().Active != 30 || w.lastAct.IsZero() {
		t.Fatal("live worker stats were delayed")
	}
}
func TestWdttRejectsOldProcessEvents(t *testing.T) {
	current := &exec.Cmd{}
	old := &exec.Cmd{}
	w := &Wdtt{cmd: current, active: 30}
	w.handleEvent(old, `__WDTT_EVENT__|STATS|{"active":0}`, nil)
	w.handleLine(old, "FATAL_AUTH неверный пароль")
	if w.State().Active != 30 {
		t.Fatal("old session overwrote current worker count")
	}
}
func TestWdttWatchdogStopsWithSession(t *testing.T) {
	cmd := &exec.Cmd{}
	w := &Wdtt{cmd: cmd, running: true}
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() { w.watchdog(cmd, stop); close(done) }()
	close(stop)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("old watchdog leaked after stopping")
	}
}

func TestWdttReconnectLetsReaderFinishAndHonorsCancellation(t *testing.T) {
	w := &Wdtt{running: true, generation: 7, exiting: make(chan struct{})}
	done := make(chan struct{})
	w.mu.Lock()
	go func() {
		w.mu.Lock()
		w.running = false
		w.generation++
		close(w.exiting)
		w.mu.Unlock()
		close(done)
	}()
	started := time.Now()
	err := w.spawnLocked()
	w.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	<-done
	if time.Since(started) > time.Second {
		t.Fatal("reconnect blocked reader on mutex")
	}
	if w.cmd != nil {
		t.Fatal("cancelled session spawned a new process")
	}
}
