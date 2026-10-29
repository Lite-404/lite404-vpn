package main

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
)

const testServer = `{"remarks":"🇩🇪 Германия","log":{"loglevel":"warning"},
 "dns":{"servers":[{"address":"8.8.8.8"}],"queryStrategy":"UseIP","tag":"dns_out"},
 "inbounds":[{"listen":"127.0.0.1","port":10808,"protocol":"socks","tag":"mixed"}],
 "outbounds":[
  {"protocol":"vless","tag":"proxy","settings":{"address":"de.barked.fun","port":443,"id":"00000000-0000-0000-0000-000000000001","encryption":"none","flow":"xtls-rprx-vision"},
   "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"de.barked.fun","fingerprint":"firefox","publicKey":"Y6vSOCk7fzuGCpNH0mVBYDOEcyXODbHczfWBMf-KlnI","shortId":"8b3f3d","spiderX":"/"}}},
  {"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block"}],
 "routing":{"domainStrategy":"AsIs","rules":[{"type":"field","network":"tcp,udp","outboundTag":"proxy"}]}}`

// Профили — настоящие, из панели (общие с тестами Android). Geo-файлы: LITE404_GEO=папка с roscom-*.dat.
func TestBuildConfigStartsXray(t *testing.T) {
	if geo := os.Getenv("LITE404_GEO"); geo != "" {
		os.Setenv("XRAY_LOCATION_ASSET", geo)
	}
	for _, name := range []string{"off", "happ", "incy"} {
		var profile obj
		if name != "off" {
			data, err := os.ReadFile("../app/src/test/resources/routing-" + name + ".json")
			if err != nil {
				t.Fatal(err)
			}
			json.Unmarshal(data, &profile)
		}
		cfg, err := BuildConfig(json.RawMessage(testServer), profile, true)
		if err != nil {
			t.Fatal(err)
		}
		text := string(cfg)
		if !strings.Contains(text, "192.168.0.0/16") || !strings.Contains(text, `"port":10809`) {
			t.Fatalf("%s: нет LAN-правила или HTTP-входа", name)
		}
		if profile != nil && !strings.Contains(text, "ext:roscom-geosite.dat:whitelist") {
			t.Fatalf("%s: нет правил профиля", name)
		}
		if profile == nil || os.Getenv("LITE404_GEO") != "" {
			inst, err := startInstance(cfg)
			if err != nil {
				t.Fatalf("%s: %v", name, err)
			}
			inst.Close()
		}
	}
}

func TestLanDirectOff(t *testing.T) {
	cfg, err := BuildConfig(json.RawMessage(testServer), nil, false)
	if err != nil || strings.Contains(string(cfg), "192.168.0.0/16") {
		t.Fatalf("правило локальной сети осталось: %v", err)
	}
}

// Конфиг в том виде, в каком его печатает клиент WDTT (ключи — тестовые).
const testWG = `[Interface]
MTU = 1280
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.66.66.2/32
DNS = 10.66.66.1

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
PresharedKey = /UwcSPg38hW/D9Y3tcS1FOV0K1wuURMbS0sesJEP5ak=
Endpoint = 127.0.0.1:9000
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
`

func TestWireGuardFromWdtt(t *testing.T) {
	server, err := WireGuardServer(testWG)
	if err != nil {
		t.Fatal(err)
	}
	for _, lan := range []bool{true, false} {
		cfg, err := BuildConfig(server, nil, lan)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(string(cfg), `"protocol":"wireguard"`) || !strings.Contains(string(cfg), "10.66.66.1") {
			t.Fatalf("нет выхода WireGuard или DNS туннеля: %s", cfg)
		}
		inst, err := startInstance(cfg)
		if err != nil {
			t.Fatalf("Xray не принял WireGuard: %v", err)
		}
		inst.Close()
	}
}
