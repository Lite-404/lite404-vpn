package main

import (
	"bytes"
	"os/exec"
	"strings"
	"testing"
	"time"
)

type switchTestInput struct{ bytes.Buffer }

func (*switchTestInput) Close() error { return nil }

func TestWdttSwitchRetainsProcessAndWorkers(t *testing.T) {
	cmd := &exec.Cmd{}
	input := &switchTestInput{}
	w := &Wdtt{cmd: cmd, stdin: input, running: true, active: 30, server: WdttServer{Host: "old.test", Port: 1}}
	done := make(chan error, 1)
	applied := make(chan string, 1)
	go func() {
		done <- w.Switch(WdttServer{Host: "new.test", Port: 2}, func(config string) error { applied <- config; return nil })
	}()
	var id string
	deadline := time.Now().Add(time.Second)
	for id == "" && time.Now().Before(deadline) {
		w.mu.Lock()
		if w.switching != nil {
			id = w.switching.id
		}
		w.mu.Unlock()
		time.Sleep(time.Millisecond)
	}
	if id == "" {
		t.Fatal("switch not started")
	}
	if state := w.State(); state.Active != 30 || !state.Running || !state.Switching {
		t.Fatal("switch stopped current transport")
	}
	w.handleEvent(cmd, `__WDTT_EVENT__|SWITCH_CONFIG|{"id":"`+id+`","config":"new config"}`, nil)
	select {
	case config := <-applied:
		if config != "new config" {
			t.Fatal(config)
		}
	case <-time.After(time.Second):
		t.Fatal("config not applied")
	}
	deadline = time.Now().Add(time.Second)
	acknowledged := false
	for !acknowledged && time.Now().Before(deadline) {
		w.mu.Lock()
		acknowledged = strings.Contains(input.String(), "SWITCH_ACK|")
		w.mu.Unlock()
		time.Sleep(time.Millisecond)
	}
	if !acknowledged {
		t.Fatal("application did not acknowledge configuration")
	}
	w.handleEvent(cmd, `__WDTT_EVENT__|SWITCHED|{"id":"`+id+`"}`, nil)
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("switch did not finish")
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.cmd != cmd || w.server.Host != "new.test" || w.active != 30 {
		t.Fatal("process was replaced during switch")
	}
}

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
