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
func TestBuildConfigStartsCore(t *testing.T) {
	if geo := os.Getenv("LITE404_GEO"); geo != "" {
		geoDirPath = geo
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
		cfg, err := BuildConfig(json.RawMessage(testServer), profile, true, UserLists{}, true)
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
	cfg, err := BuildConfig(json.RawMessage(testServer), nil, false, UserLists{}, false)
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
		cfg, err := BuildConfig(server, nil, lan, UserLists{}, false)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(string(cfg), `"protocol":"wireguard"`) || !strings.Contains(string(cfg), "10.66.66.1") {
			t.Fatalf("нет выхода WireGuard или DNS туннеля: %s", cfg)
		}
		inst, err := startInstance(cfg)
		if err != nil {
			t.Fatalf("ядро не приняло WireGuard: %v", err)
		}
		inst.Close()
	}
}

func TestUserLists(t *testing.T) {
	d, i := parseList("https://Example.ru/path\n# комментарий\n203.0.113.0/24, 198.51.100.7\nпример.рф\nмусор!!\n*.youtube.com")
	if strings.Join(d, " ") != "domain:example.ru domain:xn--e1afmkfd.xn--p1ai domain:youtube.com" || strings.Join(i, " ") != "203.0.113.0/24 198.51.100.7" {
		t.Fatalf("parse: %v %v", d, i)
	}
	geo := os.Getenv("LITE404_GEO")
	if geo == "" {
		t.Skip("LITE404_GEO")
	}
	geoDirPath = geo
	var profile obj
	data, _ := os.ReadFile("../app/src/test/resources/routing-happ.json")
	json.Unmarshal(data, &profile)
	user := UserLists{Ready: []string{"ru-services", "games", "ru-all"}, Direct: "example.ru\n203.0.113.0/24", Proxy: "youtube.com"}
	for _, p := range []obj{profile, nil} {
		cfg, err := BuildConfig(json.RawMessage(testServer), p, true, user, true)
		if err != nil {
			t.Fatal(err)
		}
		s := string(cfg)
		s = s[strings.Index(s, `"routing"`):]
		yt, ex, steam := strings.Index(s, "domain:youtube.com"), strings.Index(s, "domain:example.ru"), strings.Index(s, "roscom-geosite.dat:steam")
		if yt < 0 || ex < yt || steam < ex {
			t.Fatalf("порядок правил: %d %d %d", yt, ex, steam)
		}
		inst, err := startInstance(cfg)
		if err != nil {
			t.Fatalf("ядро: %v", err)
		}
		inst.Close()
	}
}
