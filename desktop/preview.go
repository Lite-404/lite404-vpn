package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	"lite404/boxcore"
)

// Preferences are shared by the phone-shaped and wide desktop layouts.
func applyPreviewSettings(a *App, m obj) {
	if v, ok := m["networkMode"].(string); ok && (v == "tun" || v == "proxy") {
		a.s.NetworkMode = v
	}
	for key, dest := range map[string]*bool{"connectOnOpen": &a.s.ConnectOnOpen, "journalPrimary": &a.s.JournalPrimary, "journalReserve": &a.s.JournalReserve} {
		if v, ok := m[key].(bool); ok {
			*dest = v
		}
	}
	if v, ok := m["connectOnBoot"].(bool); ok {
		if err := setAutostart(v); err == nil {
			a.s.ConnectOnBoot = v
		} else {
			a.notice = "Не удалось изменить автозапуск: " + err.Error()
		}
	}
	for key, dest := range map[string]*string{"wdttRouting": &a.s.WdttRouting, "pingMethod": &a.s.PingMethod, "appMode": &a.s.AppMode} {
		if v, ok := m[key].(string); ok {
			allowed := map[string]string{"wdttRouting": "|smart|off|", "pingMethod": "|http|icmp|tcp|", "appMode": "|all|exclude|include|"}
			if strings.Contains(allowed[key], "|"+v+"|") && v != "" {
				*dest = v
			}
		}
	}
	if v, ok := m["wdttWorkers"].(float64); ok {
		a.s.WdttWorkers = min(max(int(v), 1), 30)
	}
	if v, ok := m["wdttLists"].(map[string]any); ok {
		raw, _ := json.Marshal(v)
		var l UserLists
		if json.Unmarshal(raw, &l) == nil && len(l.Direct) < 200000 && len(l.Proxy) < 200000 {
			a.s.WdttLists = l
		}
	}
	if v, ok := m["appearance"].(map[string]any); ok {
		a.s.Appearance = v
	}
	if v, ok := m["selectedApps"].([]any); ok {
		a.s.SelectedApps = nil
		for _, item := range v {
			if s, ok := item.(string); ok && len(s) > 0 && len(s) <= 256 && !strings.ContainsAny(s, "/\\\x00\r\n") {
				a.s.SelectedApps = append(a.s.SelectedApps, s)
			}
		}
	}
	a.wdtt.mu.Lock()
	a.wdtt.logging = a.s.JournalReserve
	a.wdtt.mu.Unlock()
}

func startConfiguredInstance(config []byte, s *Settings) (*boxcore.Instance, error) {
	converted, err := boxcore.Convert(config, boxcore.Options{GeoDir: geoDirPath})
	if err != nil {
		return nil, fmt.Errorf("конфиг сервера: %w", err)
	}
	if s.NetworkMode != "tun" {
		converted, err = applyProcessRouting(converted, s.AppMode, s.SelectedApps)
	}
	if err != nil {
		return nil, err
	}
	return boxcore.Start(converted, nil)
}

func applyProcessRouting(config []byte, mode string, selected []string) ([]byte, error) {
	if mode != "include" && (mode != "exclude" || len(selected) == 0) {
		return config, nil
	}
	var root obj
	if err := json.Unmarshal(config, &root); err != nil {
		return nil, err
	}
	route, _ := root["route"].(map[string]any)
	if route == nil {
		route = obj{}
		root["route"] = route
	}
	rules, _ := route["rules"].([]any)
	rule := obj{"action": "route", "outbound": "direct", "process_name": selected}
	if mode == "include" {
		if len(selected) == 0 {
			delete(rule, "process_name")
		} else {
			rule["invert"] = true
		}
	}
	route["rules"] = append([]any{rule}, rules...)
	return json.Marshal(root)
}

func pingMethod(server json.RawMessage, url, method string) int64 {
	if method != "tcp" && method != "icmp" {
		return Ping(server, url)
	}
	host, port := serverEndpoint(server)
	if host == "" {
		return -1
	}
	start := time.Now()
	if method == "tcp" {
		conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), 5*time.Second)
		if err != nil {
			return -1
		}
		conn.Close()
	} else {
		return icmpPing(host)
	}
	return max(1, time.Since(start).Milliseconds())
}

func serverEndpoint(raw json.RawMessage) (string, int) {
	var root obj
	if json.Unmarshal(raw, &root) != nil {
		return "", 0
	}
	outs, _ := root["outbounds"].([]any)
	for _, item := range outs {
		out, _ := item.(map[string]any)
		settings, _ := out["settings"].(map[string]any)
		candidates := []obj{settings}
		for _, key := range []string{"vnext", "servers"} {
			if rows, ok := settings[key].([]any); ok {
				for _, row := range rows {
					if r, ok := row.(map[string]any); ok {
						candidates = append(candidates, r)
					}
				}
			}
		}
		for _, r := range candidates {
			if host, ok := r["address"].(string); ok && host != "" {
				p, _ := r["port"].(float64)
				return host, int(p)
			}
		}
	}
	return "", 0
}

func commandPing(args ...string) int64 {
	cmd := exec.Command("ping", args...)
	hideWindow(cmd)
	start := time.Now()
	if err := cmd.Run(); err != nil {
		return -1
	}
	return max(1, time.Since(start).Milliseconds())
}

type desktopJournal struct {
	mu       sync.Mutex
	lines    []LogLine
	previous string
}

func (j *desktopJournal) record(text string, bad bool) {
	j.mu.Lock()
	defer j.mu.Unlock()
	if j.previous == text {
		return
	}
	j.previous = text
	j.lines = append(j.lines, LogLine{time.Now().UnixMilli(), text, bad})
	if len(j.lines) > 400 {
		j.lines = j.lines[len(j.lines)-400:]
	}
}
func (j *desktopJournal) clear() { j.mu.Lock(); j.lines = nil; j.previous = ""; j.mu.Unlock() }
func (a *App) journalLines(mode string) []LogLine {
	if mode == "wdtt" {
		return a.wdtt.Logs()
	}
	a.journal.mu.Lock()
	defer a.journal.mu.Unlock()
	return append([]LogLine{}, a.journal.lines...)
}

type desktopApp struct {
	Name    string `json:"name"`
	Process string `json:"process"`
	System  bool   `json:"system"`
}
