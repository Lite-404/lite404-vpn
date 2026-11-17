package main

import (
	"bytes"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"lite404/boxcore"
)

// The privileged worker only owns the TUN and forwards to the user's local core.
// It never reads the subscription, token, private keys, or arbitrary core configs.
type tunJob struct {
	Key           string   `json:"key"`
	ParentProcess string   `json:"parent_process"`
	WdttProcess   string   `json:"wdtt_process"`
	AppMode       string   `json:"app_mode"`
	Selected      []string `json:"selected"`
	LanDirect     bool     `json:"lan_direct"`
}
type tunReady struct {
	Endpoint string `json:"endpoint"`
	Error    string `json:"error,omitempty"`
}
type tunSession struct {
	dir, key, endpoint string
	stop               chan struct{}
	failed             chan error
	once               sync.Once
}

func startTunCommand(cmd *exec.Cmd, job string) error {
	if err := cmd.Start(); err != nil {
		return err
	}
	go func() {
		if err := cmd.Wait(); err != nil {
			ready := filepath.Join(filepath.Dir(job), "ready.json")
			if _, e := os.Stat(ready); os.IsNotExist(e) {
				data, _ := json.Marshal(tunReady{Error: "разрешение администратора отклонено или служба TUN завершилась"})
				os.WriteFile(ready, data, 0600)
			}
		}
	}()
	return nil
}

func buildTunConfig(job tunJob) ([]byte, error) {
	if len(job.Key) != 64 || filepath.Base(job.ParentProcess) != job.ParentProcess || job.ParentProcess == "" || filepath.Base(job.WdttProcess) != job.WdttProcess {
		return nil, errors.New("некорректное задание TUN")
	}
	name := "Lite404"
	if runtime.GOOS == "linux" {
		name = "lite404"
	}
	if runtime.GOOS == "darwin" {
		name = "utun404"
	}
	inbound := obj{"type": "tun", "tag": "tun", "interface_name": name, "address": []string{"172.31.254.1/30", "fdfe:404:404::1/126"}, "mtu": 1500, "auto_route": true, "strict_route": true, "stack": "gvisor", "dns_mode": "hijack"}
	if job.LanDirect {
		inbound["route_exclude_address"] = privateCIDRs
	}
	serviceNames := []string{job.ParentProcess}
	if job.WdttProcess != "" {
		serviceNames = append(serviceNames, job.WdttProcess)
	}
	rules := []any{obj{"port": 53, "action": "hijack-dns"}, obj{"process_name": serviceNames, "action": "route", "outbound": "direct"}, obj{"ip_cidr": []string{"127.0.0.0/8", "::1/128"}, "action": "route", "outbound": "direct"}}
	if job.AppMode == "include" || job.AppMode == "exclude" && len(job.Selected) > 0 {
		rule := obj{"action": "route", "outbound": "direct"}
		if len(job.Selected) > 0 {
			rule["process_name"] = job.Selected
			if job.AppMode == "include" {
				rule["invert"] = true
			}
		}
		rules = append(rules, rule)
	}
	root := obj{"log": obj{"disabled": true}, "inbounds": []any{inbound}, "outbounds": []any{obj{"type": "socks", "tag": "proxy", "server": "127.0.0.1", "server_port": socksPort, "version": "5"}, obj{"type": "direct", "tag": "direct"}}, "route": obj{"auto_detect_interface": true, "find_process": true, "rules": rules, "final": "proxy"}, "dns": obj{"servers": []any{obj{"type": "https", "tag": "dns", "server": "1.1.1.1", "server_port": 443, "path": "/dns-query", "detour": "direct"}}, "final": "dns", "strategy": "ipv4_only"}}
	data, err := json.Marshal(root)
	if err == nil {
		err = boxcore.Check(data)
	}
	return data, err
}

func (a *App) startTunAsyncLocked(lan bool) {
	if a.networkMode != "tun" || a.tun != nil || a.tunStarting {
		return
	}
	a.tunStarting = true
	cancel := make(chan struct{})
	a.tunCancel = cancel
	generation := a.generation
	exe, _ := os.Executable()
	wdttName := "wdtt-client-" + version
	if runtime.GOOS == "windows" {
		wdttName += ".exe"
	}
	job := tunJob{ParentProcess: filepath.Base(exe), WdttProcess: wdttName, AppMode: a.s.AppMode, Selected: append([]string{}, a.s.SelectedApps...), LanDirect: lan}
	go func() {
		session, err := startTunSession(job, cancel)
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.generation != generation {
			if session != nil {
				session.Close()
			}
			return
		}
		a.tunStarting = false
		a.tunCancel = nil
		if err != nil {
			a.errText = "TUN: " + err.Error()
			a.stopAllLocked()
			return
		}
		a.tun = session
		a.phase = "on"
		go a.watchTun(session, generation)
		if a.mode != "wdtt" && a.s.TestAfter {
			go a.runTest()
		}
	}()
}

func (a *App) watchTun(session *tunSession, generation uint64) {
	select {
	case <-session.stop:
		return
	case err := <-session.failed:
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.generation == generation && a.tun == session {
			a.errText = "TUN: служба остановилась · " + err.Error()
			a.stopAllLocked()
		}
	}
}

func startTunSession(job tunJob, cancel <-chan struct{}) (*tunSession, error) {
	parent := filepath.Join(dataDir(), "tun")
	if err := os.MkdirAll(parent, 0700); err != nil {
		return nil, err
	}
	dir, err := os.MkdirTemp(parent, "session-")
	if err != nil {
		return nil, err
	}
	key := make([]byte, 32)
	if _, err = rand.Read(key); err != nil {
		return nil, err
	}
	job.Key = hex.EncodeToString(key)
	raw, _ := json.Marshal(job)
	path := filepath.Join(dir, "job.json")
	if err = os.WriteFile(path, raw, 0600); err != nil {
		return nil, err
	}
	session := &tunSession{dir: dir, key: job.Key, stop: make(chan struct{}), failed: make(chan error, 1)}
	if err = launchTunWorker(path); err != nil {
		session.Close()
		return nil, err
	}
	deadline := time.Now().Add(75 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-cancel:
			session.Close()
			return nil, errors.New("подключение отменено")
		default:
		}
		if data, e := os.ReadFile(filepath.Join(dir, "ready.json")); e == nil {
			var result tunReady
			if json.Unmarshal(data, &result) != nil {
				time.Sleep(80 * time.Millisecond)
				continue
			}
			if result.Error != "" {
				session.Close()
				return nil, errors.New(result.Error)
			}
			if !strings.HasPrefix(result.Endpoint, "127.0.0.1:") {
				break
			}
			session.endpoint = result.Endpoint
			go session.heartbeat()
			return session, nil
		}
		time.Sleep(80 * time.Millisecond)
	}
	session.Close()
	return nil, errors.New("не получено разрешение системы или служба TUN не запустилась")
}
func (s *tunSession) request(path string) error {
	if s.endpoint == "" {
		return nil
	}
	req, _ := http.NewRequest("POST", "http://"+s.endpoint+path, bytes.NewReader(nil))
	req.Header.Set("Authorization", "Bearer "+s.key)
	client := &http.Client{Timeout: 7 * time.Second, Transport: &http.Transport{Proxy: nil, DisableKeepAlives: true}}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)
	if resp.StatusCode != 200 {
		return fmt.Errorf("TUN HTTP %d", resp.StatusCode)
	}
	return nil
}
func (s *tunSession) heartbeat() {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.stop:
			return
		case <-ticker.C:
			if err := s.request("/pulse"); err != nil {
				s.failed <- err
				return
			}
		}
	}
}
func (s *tunSession) Close() {
	s.once.Do(func() {
		close(s.stop)
		s.request("/stop")
		for _, name := range []string{"job.json", "ready.json"} {
			os.Remove(filepath.Join(s.dir, name))
		}
		os.Remove(s.dir)
	})
}

func runTunWorker(path string) error {
	info, err := os.Stat(path)
	if err != nil || time.Since(info.ModTime()) > 90*time.Second {
		return errors.New("задание TUN истекло или отменено")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var job tunJob
	if json.Unmarshal(raw, &job) != nil {
		return errors.New("invalid TUN job")
	}
	dir := filepath.Dir(path)
	writeReady := func(value tunReady) {
		raw, _ := json.Marshal(value)
		os.WriteFile(filepath.Join(dir, "ready.json"), raw, 0644)
	}
	config, err := buildTunConfig(job)
	if err != nil {
		writeReady(tunReady{Error: err.Error()})
		return err
	}
	if _, err = os.Stat(path); err != nil {
		return err
	}
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort), 2*time.Second)
	if err != nil {
		writeReady(tunReady{Error: "локальное ядро VPN уже остановлено"})
		return err
	}
	conn.Close()
	if err = prepareTunRuntime(); err != nil {
		writeReady(tunReady{Error: "драйвер TUN: " + err.Error()})
		return err
	}
	instance, err := boxcore.Start(config, nil)
	if err != nil {
		writeReady(tunReady{Error: err.Error()})
		return err
	}
	defer instance.Close()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	done := make(chan struct{})
	var once sync.Once
	var last atomic.Int64
	last.Store(time.Now().UnixNano())
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+job.Key)) != 1 {
			http.Error(w, "forbidden", 403)
			return
		}
		switch r.URL.Path {
		case "/pulse":
			last.Store(time.Now().UnixNano())
		case "/stop":
			once.Do(func() { close(done) })
		default:
			http.NotFound(w, r)
			return
		}
		w.WriteHeader(200)
	})
	server := &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second}
	defer server.Close()
	go server.Serve(listener)
	writeReady(tunReady{Endpoint: listener.Addr().String()})
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return nil
		case <-ticker.C:
			if _, err := os.Stat(path); err != nil {
				return nil
			}
			if time.Since(time.Unix(0, last.Load())) > 12*time.Second {
				return nil
			}
		}
	}
}
