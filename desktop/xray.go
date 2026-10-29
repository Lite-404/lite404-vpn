package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	gonet "net"
	"net/http"
	"strings"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all" // регистрирует все протоколы и транспорты
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

func profileDNS(p obj) obj {
	remote := str(p, "RemoteDNSDomain", str(p, "RemoteDns", "1.1.1.1"))
	domestic := str(p, "DomesticDNSDomain", str(p, "DomesticDns", "77.88.8.8"))
	servers := []any{remote}
	if direct := mapGeo(list(p, "DirectSites"), false); len(direct) > 0 {
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
// profile == nil — всё через VPN (кроме локальной сети).
func BuildConfig(server json.RawMessage, profile obj) ([]byte, error) {
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
	rules := []any{
		obj{"type": "field", "ip": privateCIDRs, "outboundTag": "direct"},
		obj{"type": "field", "ip": []any{"::/0"}, "outboundTag": "ipv6-block"}, // у серверов нет IPv6
	}
	if profile != nil {
		rules = append(rules, profileRules(profile, proxyTag)...)
		routing["domainStrategy"] = str(profile, "DomainStrategy", "IPIfNonMatch")
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
		root["dns"] = profileDNS(profile)
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

func startInstance(config []byte) (*core.Instance, error) {
	pb, err := serial.LoadJSONConfig(bytes.NewReader(config))
	if err != nil {
		return nil, fmt.Errorf("Xray не принял конфиг: %v", err)
	}
	inst, err := core.New(pb)
	if err != nil {
		return nil, fmt.Errorf("Xray: %v", err)
	}
	if err := inst.Start(); err != nil {
		inst.Close()
		return nil, fmt.Errorf("Xray не запустился: %v", err)
	}
	return inst, nil
}

// measure — полный HTTP-запрос через инстанс Xray (как measureDelay в мобильном ядре), мс.
func measure(inst *core.Instance, url string) (int64, error) {
	client := &http.Client{
		Timeout: 12 * time.Second,
		Transport: &http.Transport{
			DisableKeepAlives: true,
			DialContext: func(ctx context.Context, network, addr string) (gonet.Conn, error) {
				dest, err := net.ParseDestination(network + ":" + addr)
				if err != nil {
					return nil, err
				}
				return core.Dial(ctx, inst, dest)
			},
		},
	}
	start := time.Now()
	resp, err := client.Get(url)
	if err != nil {
		return -1, err
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 204 && resp.StatusCode != 200 {
		return -1, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return time.Since(start).Milliseconds(), nil
}

// Ping — задержка до сервера без подключения: временный инстанс только с исходящим соединением.
func Ping(server json.RawMessage, url string) int64 {
	var root obj
	if json.Unmarshal(server, &root) != nil {
		return -1
	}
	outs, _ := root["outbounds"].([]any)
	if len(outs) == 0 {
		return -1
	}
	cfg, _ := json.Marshal(obj{"log": obj{"loglevel": "none"}, "outbounds": []any{outs[0]}})
	inst, err := startInstance(cfg)
	if err != nil {
		return -1
	}
	defer inst.Close()
	ms, err := measure(inst, url)
	if err != nil {
		return -1
	}
	return ms
}
