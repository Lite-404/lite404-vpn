package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Run only as root inside an isolated Linux network namespace with a dummy
// default interface. No production routing or credentials are used.
func TestTunCapturesTrafficAndRemovesInterface(t *testing.T) {
	if os.Getenv("LITE404_TEST_TUN_NAMESPACE") != "1" {
		t.Skip("requires isolated root network namespace")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:10808")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	target := make(chan string, 4)
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				conn.SetDeadline(time.Now().Add(8 * time.Second))
				r := bufio.NewReader(conn)
				header := make([]byte, 2)
				if _, err := io.ReadFull(r, header); err != nil {
					return
				}
				methods := make([]byte, int(header[1]))
				if _, err := io.ReadFull(r, methods); err != nil {
					return
				}
				conn.Write([]byte{5, 0})
				request := make([]byte, 10)
				if _, err := io.ReadFull(r, request); err != nil {
					return
				}
				target <- net.IP(request[4:8]).String()
				conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 80})
				for {
					line, err := r.ReadString('\n')
					if err != nil {
						return
					}
					if line == "\r\n" {
						break
					}
				}
				io.WriteString(conn, "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nTUN OK")
			}()
		}
	}()
	dir := t.TempDir()
	job := tunJob{Key: strings.Repeat("b", 64), ParentProcess: "lite404-gui", WdttProcess: "wdtt-client", AppMode: "all"}
	raw, _ := json.Marshal(job)
	path := filepath.Join(dir, "job.json")
	os.WriteFile(path, raw, 0600)
	done := make(chan error, 1)
	go func() { done <- runTunWorker(path) }()
	session := &tunSession{dir: dir, key: job.Key, stop: make(chan struct{})}
	defer session.Close()
	deadline := time.Now().Add(8 * time.Second)
	for session.endpoint == "" && time.Now().Before(deadline) {
		data, _ := os.ReadFile(filepath.Join(dir, "ready.json"))
		var result tunReady
		if json.Unmarshal(data, &result) == nil {
			if result.Error != "" {
				t.Fatal(result.Error)
			}
			session.endpoint = result.Endpoint
		}
		time.Sleep(30 * time.Millisecond)
	}
	if session.endpoint == "" {
		t.Fatal("worker not ready")
	}
	if err := session.request("/pulse"); err != nil {
		t.Fatal(err)
	}
	conn, err := net.DialTimeout("tcp", "198.18.0.7:80", 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	fmt.Fprint(conn, "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n")
	data, err := io.ReadAll(conn)
	conn.Close()
	if err != nil || !strings.Contains(string(data), "TUN OK") {
		t.Fatalf("TUN forwarding: %q %v", data, err)
	}
	select {
	case got := <-target:
		if got != "198.18.0.7" {
			t.Fatal(got)
		}
	case <-time.After(time.Second):
		t.Fatal("SOCKS bypassed")
	}
	session.Close()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("worker leaked")
	}
	if _, err := net.InterfaceByName("lite404"); err == nil {
		t.Fatal("TUN interface leaked after disconnect")
	}
}
