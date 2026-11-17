package main

import (
	"encoding/json"
	"testing"
)

func TestProcessRoutingModes(t *testing.T) {
	original := []byte(`{"route":{"rules":[{"domain":["example.com"],"action":"route","outbound":"proxy"}]}}`)
	for _, tt := range []struct {
		mode     string
		selected []string
		wantRule bool
		invert   bool
	}{
		{"all", []string{"browser.exe"}, false, false},
		{"exclude", nil, false, false},
		{"exclude", []string{"browser.exe"}, true, false},
		{"include", []string{"browser.exe"}, true, true},
		{"include", nil, true, false},
	} {
		got, err := applyProcessRouting(original, tt.mode, tt.selected)
		if err != nil {
			t.Fatal(err)
		}
		var root obj
		json.Unmarshal(got, &root)
		rules := root["route"].(map[string]any)["rules"].([]any)
		if !tt.wantRule {
			if string(got) != string(original) {
				t.Fatalf("%s unexpectedly changed config", tt.mode)
			}
			continue
		}
		if len(rules) != 2 {
			t.Fatalf("%s lost existing rule", tt.mode)
		}
		rule := rules[0].(map[string]any)
		if rule["outbound"] != "direct" || rule["action"] != "route" {
			t.Fatalf("bad direct rule: %v", rule)
		}
		inverted, _ := rule["invert"].(bool)
		if inverted != tt.invert {
			t.Fatalf("wrong invert: %v", rule)
		}
		if len(tt.selected) == 0 {
			if _, ok := rule["process_name"]; ok {
				t.Fatal("empty include must bypass all traffic")
			}
		}
	}
}

func TestServerEndpointFormats(t *testing.T) {
	for _, raw := range []string{
		`{"outbounds":[{"protocol":"vless","settings":{"address":"server.test","port":443}}]}`,
		`{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"server.test","port":443}]}}]}`,
		`{"outbounds":[{"protocol":"trojan","settings":{"servers":[{"address":"server.test","port":443}]}}]}`,
	} {
		h, p := serverEndpoint(json.RawMessage(raw))
		if h != "server.test" || p != 443 {
			t.Fatalf("%s:%d for %s", h, p, raw)
		}
	}
}

func TestProcessRoutingCoreStarts(t *testing.T) {
	cfg, err := BuildConfig(json.RawMessage(testServer), nil, true, UserLists{}, false)
	if err != nil {
		t.Fatal(err)
	}
	for _, mode := range []string{"exclude", "include"} {
		instance, err := startConfiguredInstance(cfg, &Settings{AppMode: mode, SelectedApps: []string{"browser.exe"}})
		if err != nil {
			t.Fatalf("%s: %v", mode, err)
		}
		instance.Close()
	}
}
