package boxcore

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Ненастоящие ключи: формат как у настоящих (x25519 public key в base64url, shortId hex).
const (
	fakeUUID = "11111111-2222-3333-4444-555555555555"
	fakePBK  = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"
)

// Сервер из JSON-подписки 3x-ui — как сейчас отдаёт панель (новый плоский формат settings).
var subServer = `{
 "dns": {"queryStrategy": "UseIP", "servers": [{"address": "8.8.8.8", "skipFallback": false}], "tag": "dns_out"},
 "inbounds": [
  {"listen": "127.0.0.1", "port": 10808, "protocol": "socks", "settings": {"auth": "noauth", "udp": true, "userLevel": 8},
   "sniffing": {"destOverride": ["http", "tls", "quic", "fakedns"], "enabled": true}, "tag": "mixed"},
  {"listen": "127.0.0.1", "port": 10809, "protocol": "http", "settings": {"userLevel": 8}, "tag": "http"}],
 "log": {"loglevel": "warning"},
 "outbounds": [
  {"protocol": "vless", "tag": "proxy",
   "streamSettings": {"network": "tcp", "security": "reality", "tcpSettings": {"header": {"type": "none"}},
    "realitySettings": {"fingerprint": "firefox", "mldsa65Verify": "", "publicKey": "` + fakePBK + `", "serverName": "ee.barked.fun",
     "shortId": "a1b2c3d4", "show": false, "spiderX": "/"}},
   "settings": {"address": "203.0.113.10", "encryption": "none", "flow": "xtls-rprx-vision", "id": "` + fakeUUID + `", "level": 8, "port": 443}},
  {"protocol": "freedom", "settings": {"noises": [], "redirect": ""}, "tag": "direct"},
  {"protocol": "blackhole", "settings": {"response": {"type": "http"}}, "tag": "block"}],
 "policy": {"levels": {"8": {"connIdle": 300}}, "system": {"statsOutboundDownlink": true}},
 "remarks": "Эстония",
 "routing": {"domainStrategy": "AsIs", "rules": [{"network": "tcp,udp", "outboundTag": "proxy", "type": "field"}]},
 "stats": {}
}`

// То, что из него собирает Android (XrayConfig.build с профилем happ): TUN, dns-out, правила профиля, DNS профиля.
var androidConfig = `{
 "log": {"loglevel": "warning", "access": "none"},
 "inbounds": [{"tag": "tun", "protocol": "tun", "settings": {"name": "xray0", "MTU": 1500, "userLevel": 8},
   "sniffing": {"enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": false}}],
 "outbounds": [
  {"protocol": "vless", "tag": "proxy",
   "streamSettings": {"network": "tcp", "security": "reality",
    "realitySettings": {"fingerprint": "firefox", "publicKey": "` + fakePBK + `", "serverName": "ee.barked.fun", "shortId": "a1b2c3d4"}},
   "settings": {"vnext": [{"address": "203.0.113.10", "port": 443, "users": [{"id": "` + fakeUUID + `", "encryption": "none", "flow": "xtls-rprx-vision"}]}]}},
  {"protocol": "freedom", "tag": "direct"},
  {"protocol": "blackhole", "tag": "block"},
  {"tag": "dns-out", "protocol": "dns"},
  {"tag": "ipv6-block", "protocol": "blackhole"}],
 "routing": {"domainStrategy": "IPIfNonMatch", "rules": [
  {"type": "field", "inboundTag": ["tun"], "port": "53", "outboundTag": "dns-out"},
  {"type": "field", "ip": ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10", "fc00::/7", "fe80::/10"], "outboundTag": "direct"},
  {"type": "field", "ip": ["::/0"], "outboundTag": "ipv6-block"},
  {"type": "field", "domain": ["ext:roscom-geosite.dat:win-spy", "ext:roscom-geosite.dat:torrent", "ext:roscom-geosite.dat:category-ads"], "outboundTag": "block"},
  {"type": "field", "domain": ["ext:roscom-geosite.dat:private", "ext:roscom-geosite.dat:whitelist"], "outboundTag": "direct"},
  {"type": "field", "ip": ["ext:roscom-geoip.dat:private", "ext:roscom-geoip.dat:whitelist"], "outboundTag": "direct"},
  {"type": "field", "network": "tcp,udp", "outboundTag": "proxy"}]},
 "dns": {"servers": ["https://8.8.8.8/dns-query",
   {"address": "https+local://77.88.8.8/dns-query", "domains": ["ext:roscom-geosite.dat:private", "ext:roscom-geosite.dat:whitelist"], "skipFallback": true}],
  "queryStrategy": "UseIPv4", "hosts": {"lkfl2.nalog.ru": "213.24.64.175", "lknpd.nalog.ru": "213.24.64.181"}}
}`

func geoDir(t *testing.T) string {
	d := os.Getenv("LITE404_GEO")
	if d == "" {
		d = `C:/Users/User/android-build/geo`
	}
	if _, err := os.Stat(filepath.Join(d, "roscom-geosite.dat")); err != nil {
		t.Skip("нет geo-файлов (LITE404_GEO)")
	}
	return d
}

func convert(t *testing.T, conf string, o Options) map[string]any {
	t.Helper()
	out, err := Convert([]byte(conf), o)
	if err != nil {
		t.Fatalf("Convert: %v", err)
	}
	if err := Check(out); err != nil {
		t.Fatalf("sing-box не принял конфиг: %v\n%s", err, out)
	}
	var m map[string]any
	json.Unmarshal(out, &m)
	return m
}

// startNoTun — запускает ядро (на компьютере TUN без прав не поднять — вход tun убираем).
func startNoTun(t *testing.T, m map[string]any) {
	t.Helper()
	delete(m, "inbounds")
	b, _ := json.Marshal(m)
	inst, err := Start(b, nil)
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	if err := inst.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
}

func outOf(m map[string]any, tag string) map[string]any {
	for _, o := range m["outbounds"].([]any) {
		if o.(map[string]any)["tag"] == tag {
			return o.(map[string]any)
		}
	}
	return nil
}

func TestSubscriptionServer(t *testing.T) {
	m := convert(t, subServer, Options{})
	p := outOf(m, "proxy")
	if p["type"] != "vless" || p["server"] != "203.0.113.10" || p["server_port"] != 443.0 || p["uuid"] != fakeUUID || p["flow"] != "xtls-rprx-vision" {
		t.Fatalf("proxy: %v", p)
	}
	tls := p["tls"].(map[string]any)
	if tls["server_name"] != "ee.barked.fun" || tls["utls"].(map[string]any)["fingerprint"] != "firefox" ||
		tls["reality"].(map[string]any)["public_key"] != fakePBK || tls["reality"].(map[string]any)["short_id"] != "a1b2c3d4" {
		t.Fatalf("tls: %v", tls)
	}
	if p["transport"] != nil {
		t.Fatalf("raw TCP без транспорта: %v", p["transport"])
	}
	route := m["route"].(map[string]any)
	if route["final"] != "proxy" {
		t.Fatalf("final: %v", route["final"])
	}
	in := m["inbounds"].([]any)
	if len(in) != 2 || in[0].(map[string]any)["type"] != "socks" || in[0].(map[string]any)["listen_port"] != 10808.0 {
		t.Fatalf("inbounds: %v", in)
	}
	inst, err := Start(mustJSON(m), nil) // порты 10808/10809 на машине тестов могут быть заняты — без входов
	if err == nil {
		inst.Close()
	} else {
		delete(m, "inbounds")
		startNoTun(t, m)
	}
}

func TestAndroidConfig(t *testing.T) {
	dir := geoDir(t)
	m := convert(t, androidConfig, Options{GeoDir: dir, TunAddress: []string{"10.10.14.1/30", "fd66:6c34:3034::1/126"}})
	tun := m["inbounds"].([]any)[0].(map[string]any)
	if tun["type"] != "tun" || tun["mtu"] != 1500.0 || tun["stack"] != "gvisor" {
		t.Fatalf("tun: %v", tun)
	}
	rules := m["route"].(map[string]any)["rules"].([]any)
	r := func(i int) map[string]any { return rules[i].(map[string]any) }
	if r(0)["action"] != "sniff" {
		t.Fatalf("первое правило: %v", r(0))
	}
	if r(1)["action"] != "hijack-dns" || r(1)["port"].([]any)[0] != 53.0 || r(1)["inbound"].([]any)[0] != "tun" {
		t.Fatalf("DNS: %v", r(1))
	}
	if r(2)["action"] != "resolve" || r(2)["strategy"] != "ipv4_only" {
		t.Fatalf("resolve перед первым правилом по IP: %v", r(2))
	}
	if r(3)["outbound"] != "direct" || len(r(3)["ip_cidr"].([]any)) != 7 {
		t.Fatalf("LAN: %v", r(3))
	}
	if r(4)["action"] != "reject" || r(4)["ip_cidr"].([]any)[0] != "::/0" {
		t.Fatalf("IPv6: %v", r(4))
	}
	if r(5)["action"] != "reject" || len(r(5)["domain_suffix"].([]any))+len(asList(r(5)["domain"])) < 10 {
		t.Fatalf("реклама/торренты: %v", short(r(5)))
	}
	if r(6)["outbound"] != "direct" || len(r(6)["domain_suffix"].([]any)) < 100 {
		t.Fatalf("сайты напрямую: %v", short(r(6)))
	}
	if r(7)["outbound"] != "direct" || len(r(7)["ip_cidr"].([]any)) < 100 {
		t.Fatalf("IP напрямую: %v", short(r(7)))
	}
	if r(8)["outbound"] != "proxy" || len(r(8)) != 2 {
		t.Fatalf("остальное: %v", r(8))
	}
	dns := m["dns"].(map[string]any)
	if dns["strategy"] != "ipv4_only" || dns["final"] != "dns-0" {
		t.Fatalf("dns: %v", short(dns))
	}
	servers := map[string]map[string]any{}
	for _, s := range dns["servers"].([]any) {
		servers[s.(map[string]any)["tag"].(string)] = s.(map[string]any)
	}
	if s := servers["dns-0"]; s["type"] != "https" || s["server"] != "8.8.8.8" || s["detour"] != "proxy" {
		t.Fatalf("удалённый DNS: %v", s)
	}
	if s := servers["dns-1"]; s["type"] != "https" || s["server"] != "77.88.8.8" || s["detour"] != nil {
		t.Fatalf("местный DNS: %v", s)
	}
	if servers["dns-hosts"]["predefined"].(map[string]any)["lknpd.nalog.ru"].([]any)[0] != "213.24.64.181" {
		t.Fatalf("hosts: %v", servers["dns-hosts"])
	}
	startNoTun(t, m)
}

func TestXHTTP(t *testing.T) {
	conf := `{"outbounds":[{"tag":"proxy","protocol":"vless",
	 "settings":{"vnext":[{"address":"example.com","port":443,"users":[{"id":"` + fakeUUID + `","encryption":"none","flow":"xtls-rprx-vision"}]}]},
	 "streamSettings":{"network":"xhttp","security":"tls",
	  "tlsSettings":{"serverName":"cdn.example.com","alpn":["h2"],"fingerprint":"chrome"},
	  "xhttpSettings":{"host":"cdn.example.com","path":"/xh","mode":"auto",
	   "extra":{"xPaddingBytes":"100-1000","noGRPCHeader":true,"scMaxEachPostBytes":1000000,"xmux":{"maxConcurrency":"16-32","hKeepAlivePeriod":30},
	    "headers":{"Host":"bad","X-Test":"1"},"unknownServerOnly":5,
	    "downloadSettings":{"address":"dl.example.com","port":8443,"network":"xhttp","security":"reality",
	     "realitySettings":{"serverName":"dl.example.com","publicKey":"` + fakePBK + `","shortId":"ab"},
	     "xhttpSettings":{"path":"/down","xPaddingBytes":"200-300"}}}}}}]}`
	m := convert(t, conf, Options{})
	p := outOf(m, "proxy")
	if p["flow"] != nil {
		t.Fatalf("Vision поверх XHTTP не бывает: %v", p["flow"])
	}
	tr := p["transport"].(map[string]any)
	if tr["type"] != "xhttp" || tr["mode"] != "packet-up" || tr["host"] != "cdn.example.com" || tr["path"] != "/xh" ||
		tr["x_padding_bytes"] != "100-1000" || tr["no_grpc_header"] != true || tr["sc_max_each_post_bytes"] != 1e6 {
		t.Fatalf("xhttp: %v", tr)
	}
	if h := tr["headers"].(map[string]any); h["Host"] != nil || h["X-Test"] != "1" {
		t.Fatalf("headers: %v", h)
	}
	if x := tr["xmux"].(map[string]any); x["max_concurrency"] != "16-32" || x["h_keep_alive_period"] != 30.0 {
		t.Fatalf("xmux: %v", x)
	}
	d := tr["download"].(map[string]any)
	if d["server"] != "dl.example.com" || d["server_port"] != 8443.0 || d["path"] != "/down" ||
		d["tls"].(map[string]any)["reality"].(map[string]any)["public_key"] != fakePBK {
		t.Fatalf("download: %v", d)
	}
	startNoTun(t, m)

	// Reality + auto без downloadSettings — у Xray это stream-one
	conf2 := strings.Replace(subServer, `"network": "tcp"`, `"network": "xhttp", "xhttpSettings": {"path": "/x", "mode": "auto"}`, 1)
	m = convert(t, conf2, Options{})
	if tr := outOf(m, "proxy")["transport"].(map[string]any); tr["mode"] != "stream-one" || tr["path"] != "/x" {
		t.Fatalf("reality xhttp: %v", tr)
	}
	startNoTun(t, m)
}

func TestTransports(t *testing.T) {
	for _, c := range []struct{ net, settings, want string }{
		{"ws", `"wsSettings":{"path":"/ws?ed=2048","host":"h.example.com"}`, `"max_early_data":2048`},
		{"grpc", `"grpcSettings":{"serviceName":"svc"}`, `"service_name":"svc"`},
		{"httpupgrade", `"httpupgradeSettings":{"path":"/u","host":"h"}`, `"type":"httpupgrade"`},
	} {
		conf := `{"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"1.2.3.4","port":443,"password":"p"}]},
		 "streamSettings":{"network":"` + c.net + `","security":"tls","tlsSettings":{"serverName":"h.example.com"},` + c.settings + `}}]}`
		out, err := Convert([]byte(conf), Options{})
		if err != nil || Check(out) != nil || !strings.Contains(string(out), c.want) {
			t.Fatalf("%s: %v %s", c.net, err, out)
		}
	}
	if _, err := Convert([]byte(`{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"address":"1.2.3.4","port":1,"id":"x"},"streamSettings":{"network":"kcp"}}]}`), Options{}); err == nil {
		t.Fatal("kcp должен давать понятную ошибку")
	}
}

// Резервный VPN на компьютере: WireGuard внутри ядра (как WireGuardServer в desktop).
func TestWireGuard(t *testing.T) {
	conf := `{"dns":{"servers":["1.1.1.1"]},
	 "inbounds":[{"tag":"socks","listen":"127.0.0.1","port":10899,"protocol":"socks"}],
	 "outbounds":[{"tag":"proxy","protocol":"wireguard","settings":{"secretKey":"eCtXsJZ27+4PbhDkHnB923tkUn2Gj59wZw5wFA75MnU=",
	  "address":["10.66.66.2"],"mtu":1280,"noKernelTun":true,"domainStrategy":"ForceIPv4",
	  "peers":[{"publicKey":"Kr8nCr4cAnoQB9w2HH0ExzK3RH0mWhkBqf4eLwI9QH0=","endpoint":"127.0.0.1:56000","keepAlive":25,"allowedIPs":["0.0.0.0/0","::/0"]}]}},
	  {"tag":"direct","protocol":"freedom"}]}`
	m := convert(t, conf, Options{})
	ep := m["endpoints"].([]any)[0].(map[string]any)
	if ep["type"] != "wireguard" || ep["address"].([]any)[0] != "10.66.66.2/32" || ep["peers"].([]any)[0].(map[string]any)["port"] != 56000.0 {
		t.Fatalf("wireguard: %v", ep)
	}
	if m["route"].(map[string]any)["final"] != "proxy" {
		t.Fatal("final")
	}
	startNoTun(t, m)
}

func TestPingConfig(t *testing.T) {
	out, err := PingConfig([]byte(subServer))
	if err != nil || Check(out) != nil {
		t.Fatal(err)
	}
	var m map[string]any
	json.Unmarshal(out, &m)
	if m["inbounds"] != nil || outOf(m, "proxy") == nil || m["log"].(map[string]any)["disabled"] != true {
		t.Fatalf("%s", out)
	}
}

func TestRuleWithMissingGeoIsDropped(t *testing.T) {
	conf := `{"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"address":"1.2.3.4","port":443,"password":"p"}},{"tag":"direct","protocol":"freedom"}],
	 "routing":{"rules":[{"type":"field","domain":["ext:nofile.dat:x"],"outboundTag":"direct"},{"type":"field","domain":["domain:ya.ru","full:a.b","keyword:kw","regexp:^r$","plain"],"outboundTag":"direct"}]}}`
	m := convert(t, conf, Options{GeoDir: t.TempDir()})
	rules := m["route"].(map[string]any)["rules"].([]any)
	if len(rules) != 1 {
		t.Fatalf("правило без доменов не должно ловить всё: %v", rules)
	}
	r := rules[0].(map[string]any)
	if r["domain_suffix"].([]any)[0] != "ya.ru" || r["domain"].([]any)[0] != "a.b" || len(r["domain_keyword"].([]any)) != 2 || r["domain_regex"].([]any)[0] != "^r$" {
		t.Fatalf("%v", r)
	}
}

func TestSnake(t *testing.T) {
	for in, want := range map[string]string{"noGRPCHeader": "no_grpc_header", "xPaddingBytes": "x_padding_bytes", "uplinkHTTPMethod": "uplink_http_method",
		"sessionIDTable": "session_id_table", "cMaxReuseTimes": "c_max_reuse_times", "scMaxEachPostBytes": "sc_max_each_post_bytes", "host": "host"} {
		if got := snake(in); got != want {
			t.Errorf("%s → %s, ждали %s", in, got, want)
		}
	}
}

// Живая проверка на настоящих серверах: LITE404_LIVE_SUB=файл с JSON-подпиской (массив конфигов) go test -run Live
func TestLiveSubscription(t *testing.T) {
	path := os.Getenv("LITE404_LIVE_SUB")
	if path == "" {
		t.Skip("LITE404_LIVE_SUB")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var configs []json.RawMessage
	if err := json.Unmarshal(raw, &configs); err != nil {
		t.Fatal(err)
	}
	for _, c := range configs {
		var name struct{ Remarks string }
		json.Unmarshal(c, &name)
		ms, err := Ping(c, "https://www.gstatic.com/generate_204", 15*time.Second, nil)
		t.Logf("%s: %d мс %v", name.Remarks, ms, err)
	}
}

func asList(v any) []any {
	l, _ := v.([]any)
	return l
}

func short(v any) string {
	s, _ := json.Marshal(v)
	if len(s) > 300 {
		return string(s[:300]) + "…"
	}
	return string(s)
}

// Живая проверка как у версии для компьютера: вход SOCKS → сервер подписки, запрос через SOCKS.
func TestLiveSocks(t *testing.T) {
	path := os.Getenv("LITE404_LIVE_SUB")
	if path == "" {
		t.Skip("LITE404_LIVE_SUB")
	}
	raw, _ := os.ReadFile(path)
	var configs []map[string]any
	if err := json.Unmarshal(raw, &configs); err != nil {
		t.Fatal(err)
	}
	c := configs[0]
	c["inbounds"] = []any{map[string]any{"tag": "socks", "listen": "127.0.0.1", "port": 31999, "protocol": "socks",
		"sniffing": map[string]any{"enabled": true}}}
	conv, err := Convert(mustJSON(c), Options{})
	if err != nil {
		t.Fatal(err)
	}
	inst, err := Start(conv, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer inst.Close()
	proxy, _ := url.Parse("socks5h://127.0.0.1:31999")
	client := &http.Client{Timeout: 20 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(proxy)}}
	resp, err := client.Get("https://ifconfig.me/ip")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	up, down := inst.Traffic()
	t.Logf("%v: выход %s, трафик %d/%d байт", c["remarks"], body, up, down)
	if up == 0 || down == 0 {
		t.Fatal("счётчик трафика не работает")
	}
}
