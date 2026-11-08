package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"lite404/boxcore"
)

const (
	socksPort = 10808
	httpPort  = 10809
	geoIP     = "roscom-geoip.dat"
	geoSite   = "roscom-geosite.dat"
)

// Частные сети — всегда напрямую (локальная сеть, роутер, принтер).
var privateCIDRs = []string{
	"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10",
	"127.0.0.0/8", "224.0.0.0/4", "255.255.255.255/32", "fc00::/7", "fe80::/10",
}

type obj = map[string]any

// ServerName — название сервера из JSON-подписки 3x-ui (поле remarks).
func ServerName(raw json.RawMessage, i int) string {
	var s struct{ Remarks string }
	_ = json.Unmarshal(raw, &s)
	if s.Remarks != "" {
		return s.Remarks
	}
	return fmt.Sprintf("Сервер %d", i+1)
}

func mapGeo(values []any, ip bool) []any {
	out := []any{}
	for _, v := range values {
		s := strings.TrimSpace(fmt.Sprint(v))
		switch {
		case s == "":
		case !ip && strings.HasPrefix(s, "geosite:"):
			out = append(out, "ext:"+geoSite+":"+strings.TrimPrefix(s, "geosite:"))
		case ip && strings.HasPrefix(s, "geoip:"):
			out = append(out, "ext:"+geoIP+":"+strings.TrimPrefix(s, "geoip:"))
		default:
			out = append(out, s)
		}
	}
	return out
}

func list(p obj, key string) []any {
	v, _ := p[key].([]any)
	return v
}

// profileRules — правила профиля маршрутизации из панели (формат happ://routing) в порядке RouteOrder.
func profileRules(p obj, proxyTag string) []any {
	type group struct{ sites, ips, tag string }
	groups := map[string]group{
		"block":  {"BlockSites", "BlockIp", "block"},
		"proxy":  {"ProxySites", "ProxyIp", proxyTag},
		"direct": {"DirectSites", "DirectIp", "direct"},
	}
	order := []string{}
	ro, _ := p["RouteOrder"].(string)
	for _, name := range strings.Split(ro, "-") {
		if _, ok := groups[name]; ok {
			order = append(order, name)
		}
	}
	if len(order) == 0 {
		order = []string{"block", "proxy", "direct"}
	}
	var rules []any
	for _, name := range order {
		g := groups[name]
		if d := mapGeo(list(p, g.sites), false); len(d) > 0 {
			rules = append(rules, obj{"type": "field", "domain": d, "outboundTag": g.tag})
		}
		if ips := mapGeo(list(p, g.ips), true); len(ips) > 0 {
			rules = append(rules, obj{"type": "field", "ip": ips, "outboundTag": g.tag})
		}
	}
	return rules
}

func str(p obj, key, def string) string {
	if s, ok := p[key].(string); ok && s != "" {
		return s
	}
	return def
}

func profileDNS(p obj, extraDirect []any) obj {
	remote := str(p, "RemoteDNSDomain", str(p, "RemoteDns", "1.1.1.1"))
	domestic := str(p, "DomesticDNSDomain", str(p, "DomesticDns", "77.88.8.8"))
	servers := []any{remote}
	if direct := append(mapGeo(list(p, "DirectSites"), false), extraDirect...); len(direct) > 0 {
		local := domestic
		if strings.HasPrefix(domestic, "https://") {
			local = "https+local://" + strings.TrimPrefix(domestic, "https://") // мимо маршрутизации, напрямую
		}
		servers = append(servers, obj{"address": local, "domains": direct, "skipFallback": true})
	}
	dns := obj{"servers": servers, "queryStrategy": "UseIPv4"}
	if hosts, ok := p["DnsHosts"].(map[string]any); ok && len(hosts) > 0 {
		dns["hosts"] = hosts
	}
	return dns
}

// BuildConfig — конфиг сервера из JSON-подписки → локальный прокси SOCKS/HTTP с маршрутизацией.
// profile == nil — всё через VPN. lanDirect — локальная сеть мимо VPN. user — свои списки,
// geo — geo-файлы профиля скачаны (нужны готовым спискам).
func BuildConfig(server json.RawMessage, profile obj, lanDirect bool, user UserLists, geo bool) ([]byte, error) {
	var root obj
	if err := json.Unmarshal(server, &root); err != nil {
		return nil, errors.New("непонятный конфиг сервера")
	}
	delete(root, "remarks")
	root["log"] = obj{"loglevel": "warning", "access": "none"}
	sniff := obj{"enabled": true, "destOverride": []any{"http", "tls", "quic"}, "routeOnly": false}
	root["inbounds"] = []any{
		obj{"tag": "socks", "listen": "127.0.0.1", "port": socksPort, "protocol": "socks", "settings": obj{"udp": true}, "sniffing": sniff},
		obj{"tag": "http", "listen": "127.0.0.1", "port": httpPort, "protocol": "http", "sniffing": sniff},
	}
	outs, _ := root["outbounds"].([]any)
	tags := map[string]bool{}
	for _, o := range outs {
		if m, ok := o.(map[string]any); ok {
			tags[fmt.Sprint(m["tag"])] = true
		}
	}
	if !tags["direct"] {
		outs = append(outs, obj{"tag": "direct", "protocol": "freedom"})
	}
	if !tags["block"] {
		outs = append(outs, obj{"tag": "block", "protocol": "blackhole"})
	}
	outs = append(outs, obj{"tag": "ipv6-block", "protocol": "blackhole"})
	root["outbounds"] = outs
	proxyTag := "proxy"
	if len(outs) > 0 {
		if m, ok := outs[0].(map[string]any); ok && fmt.Sprint(m["tag"]) != "" {
			proxyTag = fmt.Sprint(m["tag"])
		}
	}

	routing, _ := root["routing"].(map[string]any)
	if routing == nil {
		routing = obj{}
	}
	old, _ := routing["rules"].([]any)
	var rules []any
	if lanDirect {
		rules = append(rules, obj{"type": "field", "ip": privateCIDRs, "outboundTag": "direct"})
	}
	rules = append(rules, obj{"type": "field", "ip": []any{"::/0"}, "outboundTag": "ipv6-block"}) // у серверов нет IPv6
	var pr []any
	if profile != nil {
		pr = profileRules(profile, proxyTag)
	}
	// блокировка рекламы и торрентов из профиля — первой, затем свои списки, затем остальной профиль
	for _, r := range pr {
		if r.(obj)["outboundTag"] == "block" {
			rules = append(rules, r)
		}
	}
	ur := userRules(user, proxyTag, geo)
	rules = append(rules, ur...)
	for _, r := range pr {
		if r.(obj)["outboundTag"] != "block" {
			rules = append(rules, r)
		}
	}
	if profile != nil {
		routing["domainStrategy"] = str(profile, "DomainStrategy", "IPIfNonMatch")
	} else {
		for _, r := range ur {
			if _, ok := r.(obj)["ip"]; ok { // сайты по имени тоже проверяются по своим IP-спискам
				routing["domainStrategy"] = "IPIfNonMatch"
			}
		}
	}
	for _, r := range old { // правила самой подписки (обычно «всё в proxy»); внешние geo-файлы не поддерживаем
		m, ok := r.(map[string]any)
		if !ok || strings.Contains(fmt.Sprint(m["domain"], m["ip"]), "ext:") {
			continue
		}
		rules = append(rules, m)
	}
	routing["rules"] = rules
	root["routing"] = routing
	if profile != nil {
		root["dns"] = profileDNS(profile, userDirectDomains(user, geo))
	} else {
		dns, _ := root["dns"].(map[string]any)
		if dns == nil {
			dns = obj{"servers": []any{"1.1.1.1", "8.8.8.8"}}
		}
		dns["queryStrategy"] = "UseIPv4"
		root["dns"] = dns
	}
	return json.Marshal(root)
}

// geoDirPath — каталог geo-файлов профиля маршрутизации (задаёт main).
var geoDirPath = "geo"

// startInstance — конфиг в формате Xray → sing-box → запуск ядра.
func startInstance(config []byte) (*boxcore.Instance, error) {
	o := boxcore.Options{GeoDir: geoDirPath}
	if os.Getenv("LITE404_DEBUG") != "" { // подробный журнал ядра для диагностики
		o.LogLevel, o.LogFile = "debug", filepath.Join(dataDir(), "core.log")
	}
	conv, err := boxcore.Convert(config, o)
	if err != nil {
		return nil, fmt.Errorf("конфиг сервера: %v", err)
	}
	return boxcore.Start(conv, nil)
}

// measure — полный HTTP-запрос через запущенное ядро, мс.
func measure(inst *boxcore.Instance, url string) (int64, error) {
	return inst.URLTest("", url, 12*time.Second)
}

// Ping — задержка до сервера без подключения: временное ядро только с выходом к серверу.
func Ping(server json.RawMessage, url string) int64 {
	ms, err := boxcore.Ping(server, url, 12*time.Second, nil)
	if err != nil {
		return -1
	}
	return ms
}

// Traffic — байты через ядро (отдано, получено) с момента подключения.
func Traffic(inst *boxcore.Instance) (up, down int64) {
	return inst.Traffic()
}

// WireGuardServer — WireGuard-конфиг от клиента WDTT → «сервер» в формате JSON-подписки:
// один выход WireGuard внутри ядра (пользовательский стек, без драйвера и прав администратора).
func WireGuardServer(conf string) (json.RawMessage, error) {
	var section string
	var secret, pub, psk, endpoint string
	var addrs, dnsList []any
	mtu, keepAlive := 1280, 25
	for _, line := range strings.Split(conf, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "[") {
			section = strings.ToLower(strings.Trim(line, "[]"))
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		k, v = strings.ToLower(strings.TrimSpace(k)), strings.TrimSpace(v)
		list := func() []any {
			var out []any
			for _, x := range strings.Split(v, ",") {
				if x = strings.TrimSpace(x); x != "" {
					out = append(out, x)
				}
			}
			return out
		}
		switch section + "." + k {
		case "interface.privatekey":
			secret = v
		case "interface.address":
			for _, a := range list() {
				if !strings.Contains(a.(string), ":") { // у серверов нет IPv6
					addrs = append(addrs, a)
				}
			}
		case "interface.dns":
			for _, d := range list() {
				if !strings.Contains(d.(string), ":") {
					dnsList = append(dnsList, d)
				}
			}
		case "interface.mtu":
			if n, err := strconv.Atoi(v); err == nil && n >= 1280 {
				mtu = n
			}
		case "peer.publickey":
			pub = v
		case "peer.presharedkey":
			psk = v
		case "peer.endpoint":
			endpoint = v
		case "peer.persistentkeepalive":
			if n, err := strconv.Atoi(v); err == nil && n > 0 {
				keepAlive = n
			}
		}
	}
	if secret == "" || pub == "" || endpoint == "" || len(addrs) == 0 {
		return nil, errors.New("клиент резервного VPN прислал неполный конфиг")
	}
	if len(dnsList) == 0 {
		dnsList = []any{"1.1.1.1"}
	}
	peer := obj{"publicKey": pub, "endpoint": endpoint, "keepAlive": keepAlive, "allowedIPs": []any{"0.0.0.0/0", "::/0"}}
	if psk != "" {
		peer["preSharedKey"] = psk
	}
	server := obj{
		"remarks": "WDTT",
		"dns":     obj{"servers": dnsList},
		"outbounds": []any{obj{
			"tag": "proxy", "protocol": "wireguard",
			"settings": obj{"secretKey": secret, "address": addrs, "peers": []any{peer}, "mtu": mtu,
				"noKernelTun": true, "domainStrategy": "ForceIPv4"},
		}},
	}
	return json.Marshal(server)
}
