package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestTunRoutesKeepControlTrafficDirect(t *testing.T) {
	job := tunJob{Key: strings.Repeat("a", 64), ParentProcess: "Lite404 VPN.exe", WdttProcess: "wdtt-client-0.10.5.exe", AppMode: "include", Selected: []string{"browser.exe"}, LanDirect: true}
	raw, err := buildTunConfig(job)
	if err != nil {
		t.Fatal(err)
	}
	var cfg obj
	json.Unmarshal(raw, &cfg)
	route := cfg["route"].(map[string]any)
	rules := route["rules"].([]any)
	first := rules[0].(map[string]any)
	if first["action"] != "hijack-dns" {
		t.Fatal("DNS must be intercepted before process bypass")
	}
	service := rules[1].(map[string]any)
	if service["outbound"] != "direct" {
		t.Fatal("WDTT control plane entered VPN")
	}
	names := service["process_name"].([]any)
	if len(names) != 2 || names[1] != job.WdttProcess {
		t.Fatal("WDTT transport not excluded")
	}
	if route["auto_detect_interface"] != true || route["final"] != "proxy" {
		t.Fatal("TUN route escape missing")
	}
	inbound := cfg["inbounds"].([]any)[0].(map[string]any)
	if inbound["type"] != "tun" || inbound["auto_route"] != true {
		t.Fatal("TUN inactive")
	}
}
func TestTunRejectsArbitraryWorkerInput(t *testing.T) {
	if _, err := buildTunConfig(tunJob{}); err == nil {
		t.Fatal("invalid job accepted")
	}
}
