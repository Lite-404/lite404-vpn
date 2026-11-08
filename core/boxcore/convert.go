package boxcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"unicode"
)

// Перевод конфига в формате Xray (JSON-подписка 3x-ui + то, что достраивает приложение) в конфиг sing-box.
// Поддерживается то, что реально бывает у нас: VLESS/VMess/Trojan/Shadowsocks/SOCKS/HTTP, WireGuard,
// транспорты TCP(raw)/WS/gRPC/HTTPUpgrade/XHTTP/H2, TLS и Reality, маршрутизация по доменам/IP/портам,
// geosite/geoip из .dat-файлов, DNS с правилами по доменам.

type obj = map[string]any

// Options — то, чего нет в самом конфиге Xray.
type Options struct {
	GeoDir     string   // каталог geosite.dat/geoip.dat и файлов ext:
	TunAddress []string // адреса TUN (как в VpnService.Builder), нужны при входе tun
	LogLevel   string   // trace debug info warn error; "" — из конфига (warn)
	LogFile    string   // журнал в файл (для диагностики на компьютере)
}

type converter struct {
	o        Options
	kinds    map[string]string // tag выхода → proxy|direct|block|dns
	proxyTag string            // выход по умолчанию (первый в списке, как в Xray)
	warn     []string
}

// Convert — конфиг Xray (JSON) → конфиг sing-box (JSON).
func Convert(xrayJSON []byte, o Options) ([]byte, error) {
	var root obj
	if err := json.Unmarshal(xrayJSON, &root); err != nil {
		return nil, fmt.Errorf("непонятный конфиг: %v", err)
	}
	c := &converter{o: o, kinds: map[string]string{}}
	defer releaseGeoFiles()
	out, err := c.convert(root)
	if err != nil {
		return nil, err
	}
	return json.Marshal(out)
}

func (c *converter) convert(root obj) (obj, error) {
	res := obj{}
	res["log"] = c.log(mapOf(root["log"]))

	// выходы
	var outbounds, endpoints []any
	for i, raw := range listOf(root["outbounds"]) {
		ob := mapOf(raw)
		if ob == nil {
			continue
		}
		tag := str(ob, "tag")
		if tag == "" {
			tag = fmt.Sprintf("out-%d", i)
		}
		kind, conv, isEndpoint, err := c.outbound(ob, tag)
		if err != nil {
			return nil, fmt.Errorf("сервер %q: %v", tag, err)
		}
		c.kinds[tag] = kind
		if kind == "proxy" && c.proxyTag == "" {
			c.proxyTag = tag
		}
		if conv == nil {
			continue
		}
		if isEndpoint {
			endpoints = append(endpoints, conv)
		} else {
			outbounds = append(outbounds, conv)
		}
	}
	if c.proxyTag == "" {
		return nil, errors.New("в конфиге нет сервера")
	}
	if _, ok := c.kinds["direct"]; !ok {
		outbounds = append(outbounds, obj{"type": "direct", "tag": "direct"})
		c.kinds["direct"] = "direct"
	}
	res["outbounds"] = outbounds
	if len(endpoints) > 0 {
		res["endpoints"] = endpoints
	}

	// входы
	var inbounds []any
	var sniff []any // правила sniff по входам (идут первыми)
	for _, raw := range listOf(root["inbounds"]) {
		ib := mapOf(raw)
		if ib == nil {
			continue
		}
		conv, err := c.inbound(ib)
		if err != nil {
			return nil, err
		}
		if conv == nil {
			continue
		}
		if s := mapOf(ib["sniffing"]); s != nil && truthy(s["enabled"]) {
			sniff = append(sniff, sniffRules(str(conv, "tag"), s)...)
		}
		inbounds = append(inbounds, conv)
	}
	if len(inbounds) > 0 {
		res["inbounds"] = inbounds
	}

	dns, err := c.dns(mapOf(root["dns"]))
	if err != nil {
		return nil, err
	}
	res["dns"] = dns

	route, err := c.route(mapOf(root["routing"]), sniff, strategyOf(mapOf(root["dns"])))
	if err != nil {
		return nil, err
	}
	res["route"] = route
	return res, nil
}

func (c *converter) log(l obj) obj {
	level := c.o.LogLevel
	if level == "" {
		switch strings.ToLower(str(l, "loglevel")) {
		case "debug":
			level = "debug"
		case "info":
			level = "info"
		case "error":
			level = "error"
		case "none":
			return obj{"disabled": true}
		default:
			level = "warn"
		}
	}
	out := obj{"level": level, "timestamp": true}
	if c.o.LogFile != "" {
		out["output"] = c.o.LogFile
	}
	return out
}

// ---------- выходы ----------

func (c *converter) outbound(ob obj, tag string) (kind string, out obj, endpoint bool, err error) {
	settings := mapOf(ob["settings"])
	if settings == nil {
		settings = obj{}
	}
	proto := strings.ToLower(str(ob, "protocol"))
	switch proto {
	case "freedom":
		return "direct", obj{"type": "direct", "tag": tag}, false, nil
	case "blackhole":
		return "block", nil, false, nil
	case "dns":
		return "dns", nil, false, nil
	case "wireguard":
		ep, err := c.wireguard(settings, tag)
		return "proxy", ep, true, err
	}

	out = obj{"tag": tag}
	server, user := serverAndUser(settings)
	if server == nil {
		return "", nil, false, errors.New("нет адреса сервера")
	}
	addr := str(server, "address")
	port := toInt(server["port"])
	if addr == "" || port <= 0 || port > 65535 {
		return "", nil, false, errors.New("нет адреса сервера")
	}
	out["server"], out["server_port"] = addr, port

	switch proto {
	case "vless":
		out["type"] = "vless"
		out["uuid"] = str(user, "id")
		if enc := str(user, "encryption"); enc != "" && enc != "none" {
			return "", nil, false, fmt.Errorf("шифрование VLESS %q не поддерживается", enc)
		}
		if flow := str(user, "flow"); flow != "" {
			if !strings.HasPrefix(flow, "xtls-rprx-vision") {
				return "", nil, false, fmt.Errorf("flow %q не поддерживается", flow)
			}
			out["flow"] = "xtls-rprx-vision"
		}
		out["packet_encoding"] = "xudp"
	case "vmess":
		out["type"] = "vmess"
		out["uuid"] = str(user, "id")
		sec := str(user, "security")
		if sec == "" {
			sec = "auto"
		}
		out["security"] = sec
		if a := toInt(user["alterId"]); a > 0 {
			out["alter_id"] = a
		}
		out["packet_encoding"] = "xudp"
	case "trojan":
		out["type"] = "trojan"
		out["password"] = str(server, "password")
	case "shadowsocks":
		out["type"] = "shadowsocks"
		out["method"] = str(server, "method")
		out["password"] = str(server, "password")
		if truthy(server["uot"]) {
			out["udp_over_tcp"] = true
		}
	case "socks":
		out["type"] = "socks"
		out["version"] = "5"
		if u := firstMap(server["users"]); u != nil {
			out["username"], out["password"] = str(u, "user"), str(u, "pass")
		}
	case "http":
		out["type"] = "http"
		if u := firstMap(server["users"]); u != nil {
			out["username"], out["password"] = str(u, "user"), str(u, "pass")
		}
	default:
		return "", nil, false, fmt.Errorf("протокол %q не поддерживается", proto)
	}

	if err := c.stream(mapOf(ob["streamSettings"]), out); err != nil {
		return "", nil, false, err
	}
	if _, hasTransport := out["transport"]; hasTransport && out["flow"] != nil {
		delete(out, "flow") // Vision работает только поверх raw TCP
	}
	return "proxy", out, false, nil
}

// serverAndUser — адрес и учётка: старый формат (vnext/servers) и новый плоский (всё в settings).
func serverAndUser(s obj) (server, user obj) {
	if v := firstMap(s["vnext"]); v != nil {
		u := firstMap(v["users"])
		if u == nil {
			u = obj{}
		}
		return v, u
	}
	if v := firstMap(s["servers"]); v != nil {
		return v, v
	}
	if str(s, "address") != "" {
		return s, s
	}
	return nil, nil
}

func (c *converter) stream(ss obj, out obj) error {
	if ss == nil {
		return nil
	}
	if so := mapOf(ss["sockopt"]); so != nil {
		if d := str(so, "dialerProxy"); d != "" {
			out["detour"] = d
		}
	}
	security := strings.ToLower(str(ss, "security"))
	var tls obj
	switch security {
	case "", "none":
	case "tls":
		t := mapOf(ss["tlsSettings"])
		tls = obj{"enabled": true}
		if sn := str(t, "serverName"); sn != "" {
			tls["server_name"] = sn
		}
		if truthy(t["allowInsecure"]) {
			tls["insecure"] = true
		}
		if alpn := strList(t["alpn"]); len(alpn) > 0 {
			tls["alpn"] = alpn
		}
		if fp := str(t, "fingerprint"); fp != "" {
			tls["utls"] = obj{"enabled": true, "fingerprint": fingerprint(fp)}
		}
	case "reality":
		r := mapOf(ss["realitySettings"])
		if str(r, "mldsa65Verify") != "" {
			c.warn = append(c.warn, "mldsa65Verify пропущен")
		}
		tls = obj{
			"enabled":     true,
			"server_name": str(r, "serverName"),
			// отпечаток — как в подписке (на нодах Xray 26.7.28; Xray ≥ 26.9.9 требует X25519MLKEM768,
			// а у firefox в uTLS sing-box-extended его нет — при обновлении Xray на нодах это учесть)
			"utls":    obj{"enabled": true, "fingerprint": fingerprint(str(r, "fingerprint"))},
			"reality": obj{"enabled": true, "public_key": str(r, "publicKey"), "short_id": str(r, "shortId")},
		}
	default:
		return fmt.Errorf("защита %q не поддерживается", security)
	}
	if tls != nil {
		out["tls"] = tls
	}

	network := strings.ToLower(str(ss, "network"))
	switch network {
	case "", "tcp", "raw":
		t := mapOf(ss["tcpSettings"])
		if t == nil {
			t = mapOf(ss["rawSettings"])
		}
		if h := mapOf(mapOf(t)["header"]); h != nil && str(h, "type") != "" && str(h, "type") != "none" {
			return fmt.Errorf("маскировка TCP %q не поддерживается", str(h, "type"))
		}
	case "ws", "websocket":
		w := mapOf(ss["wsSettings"])
		tr := obj{"type": "ws"}
		path := str(w, "path")
		if u, err := url.Parse(path); err == nil && u.Query().Get("ed") != "" {
			if ed, err := strconv.Atoi(u.Query().Get("ed")); err == nil && ed > 0 {
				tr["max_early_data"] = ed
				tr["early_data_header_name"] = "Sec-WebSocket-Protocol"
				q := u.Query()
				q.Del("ed")
				u.RawQuery = q.Encode()
				path = u.String()
			}
		}
		if path != "" {
			tr["path"] = path
		}
		headers := obj{}
		for k, v := range mapOf(w["headers"]) {
			headers[k] = fmt.Sprint(v)
		}
		if h := str(w, "host"); h != "" {
			headers["Host"] = h
		}
		if len(headers) > 0 {
			tr["headers"] = headers
		}
		out["transport"] = tr
	case "grpc", "gun":
		g := mapOf(ss["grpcSettings"])
		out["transport"] = obj{"type": "grpc", "service_name": str(g, "serviceName")}
	case "httpupgrade":
		h := mapOf(ss["httpupgradeSettings"])
		tr := obj{"type": "httpupgrade"}
		if v := str(h, "host"); v != "" {
			tr["host"] = v
		}
		if v := str(h, "path"); v != "" {
			tr["path"] = v
		}
		out["transport"] = tr
	case "h2", "http":
		h := mapOf(ss["httpSettings"])
		tr := obj{"type": "http"}
		if hosts := strList(h["host"]); len(hosts) > 0 {
			tr["host"] = hosts
		}
		if v := str(h, "path"); v != "" {
			tr["path"] = v
		}
		out["transport"] = tr
	case "xhttp", "splithttp":
		x := mapOf(ss["xhttpSettings"])
		if x == nil {
			x = mapOf(ss["splithttpSettings"])
		}
		tr, err := c.xhttp(x, security)
		if err != nil {
			return err
		}
		out["transport"] = tr
	default:
		return fmt.Errorf("транспорт %q не поддерживается", network)
	}
	return nil
}

// Отпечатки uTLS у Xray и sing-box называются одинаково; пустой — chrome (как у Xray для Reality).
func fingerprint(fp string) string {
	fp = strings.ToLower(strings.TrimSpace(fp))
	switch fp {
	case "", "unsafe":
		return "chrome"
	case "randomizednoalpn":
		return "randomized"
	}
	return fp
}

// Ключи XHTTP, которые понимает sing-box-extended (клиентская часть V2RayXHTTPBaseOptions).
var xhttpKeys = map[string]bool{
	"host": true, "path": true, "headers": true, "x_padding_bytes": true, "no_grpc_header": true,
	"no_sse_header": true, "sc_max_each_post_bytes": true, "sc_min_posts_interval_ms": true,
	"sc_max_buffered_posts": true, "sc_stream_up_server_secs": true, "xmux": true,
	"x_padding_obfs_mode": true, "x_padding_key": true, "x_padding_header": true, "x_padding_placement": true,
	"x_padding_method": true, "uplink_http_method": true, "session_placement": true, "session_key": true,
	"seq_placement": true, "seq_key": true, "uplink_data_placement": true, "uplink_data_key": true,
	"uplink_chunk_size": true, "session_id_table": true, "session_id_length": true,
}

var xmuxKeys = map[string]bool{
	"max_concurrency": true, "max_connections": true, "c_max_reuse_times": true,
	"h_max_request_times": true, "h_max_reusable_secs": true, "h_keep_alive_period": true,
}

// xhttpBase — настройки XHTTP Xray (camelCase, с вложенным extra) → поля sing-box (snake_case).
func xhttpBase(x obj) obj {
	merged := obj{}
	for k, v := range x {
		merged[k] = v
	}
	for k, v := range mapOf(x["extra"]) { // extra в ссылках/подписке дополняет основные настройки
		if k != "host" && k != "path" && k != "mode" {
			merged[k] = v
		}
	}
	out := obj{}
	for k, v := range merged {
		key := snake(k)
		if !xhttpKeys[key] || v == nil {
			continue
		}
		switch key {
		case "headers":
			h := obj{}
			for hk, hv := range mapOf(v) {
				if !strings.EqualFold(hk, "host") {
					h[hk] = fmt.Sprint(hv)
				}
			}
			if len(h) > 0 {
				out[key] = h
			}
		case "xmux":
			m := obj{}
			for mk, mv := range mapOf(v) {
				if sk := snake(mk); xmuxKeys[sk] && mv != nil {
					m[sk] = mv
				}
			}
			if len(m) > 0 {
				out[key] = m
			}
		case "host", "path":
			if s := fmt.Sprint(v); s != "" {
				out[key] = s
			}
		default:
			out[key] = v
		}
	}
	if _, ok := out["x_padding_bytes"]; !ok {
		out["x_padding_bytes"] = "100-1000" // по умолчанию у Xray; sing-box без паддинга не работает
	}
	return out
}

func (c *converter) xhttp(x obj, security string) (obj, error) {
	if x == nil {
		x = obj{}
	}
	tr := xhttpBase(x)
	tr["type"] = "xhttp"
	ds := mapOf(x["downloadSettings"])
	if ds == nil {
		ds = mapOf(mapOf(x["extra"])["downloadSettings"])
	}
	mode := strings.ToLower(str(x, "mode"))
	if mode == "" || mode == "auto" { // как выбирает клиент Xray
		mode = "packet-up"
		if security == "reality" {
			mode = "stream-one"
			if ds != nil {
				mode = "stream-up"
			}
		}
	}
	switch mode {
	case "packet-up", "stream-up", "stream-one":
	default:
		return nil, fmt.Errorf("режим XHTTP %q не поддерживается", mode)
	}
	tr["mode"] = mode
	if ds != nil {
		if mode == "stream-one" {
			return nil, errors.New("XHTTP: downloadSettings несовместимы с режимом stream-one")
		}
		d := xhttpBase(mapOf(ds["xhttpSettings"]))
		addr, port := str(ds, "address"), toInt(ds["port"])
		if addr == "" || port <= 0 {
			return nil, errors.New("XHTTP: в downloadSettings нет адреса")
		}
		d["server"], d["server_port"] = addr, port
		tmp := obj{}
		if err := c.stream(obj{"security": ds["security"], "tlsSettings": ds["tlsSettings"], "realitySettings": ds["realitySettings"], "sockopt": ds["sockopt"]}, tmp); err != nil {
			return nil, fmt.Errorf("XHTTP downloadSettings: %v", err)
		}
		if t, ok := tmp["tls"]; ok {
			d["tls"] = t
		}
		if det, ok := tmp["detour"]; ok {
			d["detour"] = det
		}
		tr["download"] = d
	}
	return tr, nil
}

// snake — camelCase → snake_case с аббревиатурами: noGRPCHeader → no_grpc_header, sessionIDTable → session_id_table.
func snake(s string) string {
	r := []rune(s)
	var b strings.Builder
	for i, ch := range r {
		if unicode.IsUpper(ch) {
			if i > 0 && (unicode.IsLower(r[i-1]) || unicode.IsDigit(r[i-1]) ||
				(unicode.IsUpper(r[i-1]) && i+1 < len(r) && unicode.IsLower(r[i+1]))) {
				b.WriteByte('_')
			}
			b.WriteRune(unicode.ToLower(ch))
		} else {
			b.WriteRune(ch)
		}
	}
	return b.String()
}

func (c *converter) wireguard(s obj, tag string) (obj, error) {
	key := str(s, "secretKey")
	if key == "" {
		return nil, errors.New("WireGuard: нет ключа")
	}
	var addrs []string
	for _, a := range strList(s["address"]) {
		p, err := toPrefix(a)
		if err != nil {
			return nil, fmt.Errorf("WireGuard: адрес %q", a)
		}
		addrs = append(addrs, p)
	}
	if len(addrs) == 0 {
		return nil, errors.New("WireGuard: нет адреса")
	}
	ep := obj{"type": "wireguard", "tag": tag, "private_key": key, "address": addrs}
	if mtu := toInt(s["mtu"]); mtu > 0 {
		ep["mtu"] = mtu
	}
	var peers []any
	for _, raw := range listOf(s["peers"]) {
		p := mapOf(raw)
		host, portStr, err := net.SplitHostPort(str(p, "endpoint"))
		port, _ := strconv.Atoi(portStr)
		if err != nil || port <= 0 {
			return nil, fmt.Errorf("WireGuard: адрес сервера %q", str(p, "endpoint"))
		}
		peer := obj{"address": host, "port": port, "public_key": str(p, "publicKey")}
		if psk := str(p, "preSharedKey"); psk != "" {
			peer["pre_shared_key"] = psk
		}
		allowed := strList(p["allowedIPs"])
		if len(allowed) == 0 {
			allowed = []string{"0.0.0.0/0", "::/0"}
		}
		peer["allowed_ips"] = allowed
		if ka := toInt(p["keepAlive"]); ka > 0 {
			peer["persistent_keepalive_interval"] = ka
		}
		peers = append(peers, peer)
	}
	if len(peers) == 0 {
		return nil, errors.New("WireGuard: нет сервера")
	}
	ep["peers"] = peers
	return ep, nil
}

// ---------- входы ----------

func (c *converter) inbound(ib obj) (obj, error) {
	tag := str(ib, "tag")
	s := mapOf(ib["settings"])
	switch strings.ToLower(str(ib, "protocol")) {
	case "tun":
		if len(c.o.TunAddress) == 0 {
			return nil, errors.New("для TUN не заданы адреса")
		}
		mtu := toInt(s["MTU"])
		if mtu == 0 {
			mtu = toInt(s["mtu"])
		}
		if mtu == 0 {
			mtu = 1500
		}
		if tag == "" {
			tag = "tun"
		}
		return obj{"type": "tun", "tag": tag, "address": c.o.TunAddress, "mtu": mtu, "stack": "gvisor"}, nil
	case "socks", "http", "mixed":
		listen := str(ib, "listen")
		if listen == "" {
			listen = "127.0.0.1"
		}
		port := toInt(ib["port"])
		if port <= 0 {
			return nil, fmt.Errorf("вход %q без порта", tag)
		}
		return obj{"type": strings.ToLower(str(ib, "protocol")), "tag": tag, "listen": listen, "listen_port": port}, nil
	}
	return nil, nil // остальные входы приложению не нужны
}

// ---------- маршрутизация ----------

// sniffRules — sniffing входа Xray. routeOnly=false (по умолчанию в Xray): найденный в TLS/HTTP домен
// подставляется в соединение и уходит на сервер вместо IP — адрес резолвит сервер, как у Xray.
// Только для TCP: для UDP (QUIC) ядро не умеет вернуть ответы с исходного адреса.
// override_destination — правка ядра из _singbox (без -overlay конфиг не примется).
func sniffRules(tag string, s obj) []any {
	cond := func(r obj) obj {
		if tag != "" {
			r["inbound"] = []string{tag}
		}
		return r
	}
	override := false
	if !truthy(s["routeOnly"]) {
		for _, d := range strList(s["destOverride"]) {
			if d == "tls" || d == "http" {
				override = true
			}
		}
	}
	if !override {
		return []any{cond(obj{"action": "sniff"})}
	}
	return []any{
		cond(obj{"network": "tcp", "action": "sniff", "override_destination": true}),
		cond(obj{"network": "udp", "action": "sniff"}),
	}
}

func (c *converter) route(r obj, sniff []any, strategy string) (obj, error) {
	rules := append([]any{}, sniff...)
	ds := strings.ToLower(str(r, "domainStrategy"))
	needResolve := ds == "ipifnonmatch" || ds == "ipondemand"
	for _, raw := range listOf(r["rules"]) {
		rule := mapOf(raw)
		if rule == nil {
			continue
		}
		conv, hasIP, err := c.rule(rule)
		if err != nil {
			return nil, err
		}
		if conv == nil {
			continue
		}
		if hasIP && needResolve {
			// IPIfNonMatch: до первого правила по IP домены резолвим, чтобы сработали и правила по подсетям
			res := obj{"action": "resolve"}
			if strategy != "" {
				res["strategy"] = strategy
			}
			rules = append(rules, res)
			needResolve = false
		}
		rules = append(rules, conv)
	}
	route := obj{"rules": rules, "final": c.proxyTag, "auto_detect_interface": false,
		"default_domain_resolver": obj{"server": "dns-direct"}}
	return route, nil
}

// rule — правило Xray → правило sing-box; nil — правило пропускается.
func (c *converter) rule(r obj) (out obj, hasIP bool, err error) {
	if str(r, "balancerTag") != "" {
		c.warn = append(c.warn, "правило с balancerTag пропущено")
		return nil, false, nil
	}
	target := str(r, "outboundTag")
	kind, ok := c.kinds[target]
	if !ok {
		c.warn = append(c.warn, "правило на неизвестный выход "+target)
		return nil, false, nil
	}
	out = obj{}
	conds := 0

	if list := strList(r["domain"]); len(list) > 0 {
		d, err := c.domains(list)
		if err != nil {
			return nil, false, err
		}
		if d.empty() {
			return nil, false, nil // все домены правила недоступны — правило не должно ловить всё подряд
		}
		putDomains(out, d)
		conds++
	}
	if list := strList(r["ip"]); len(list) > 0 {
		cidrs, private, err := c.ips(list)
		if err != nil {
			return nil, false, err
		}
		if len(cidrs) == 0 && !private {
			return nil, false, nil
		}
		if len(cidrs) > 0 {
			out["ip_cidr"] = cidrs
		}
		if private {
			out["ip_is_private"] = true
		}
		hasIP = true
		conds++
	}
	if list := strList(r["source"]); len(list) > 0 {
		cidrs, private, err := c.ips(list)
		if err != nil {
			return nil, false, err
		}
		if len(cidrs) == 0 && !private {
			return nil, false, nil
		}
		if len(cidrs) > 0 {
			out["source_ip_cidr"] = cidrs
		}
		if private {
			out["source_ip_is_private"] = true
		}
		conds++
	}
	for _, f := range []struct{ x, single, rng string }{{"port", "port", "port_range"}, {"sourcePort", "source_port", "source_port_range"}} {
		if v, ok := r[f.x]; ok && v != nil {
			ports, ranges, err := portsOf(v)
			if err != nil {
				return nil, false, err
			}
			if len(ports) > 0 {
				out[f.single] = ports
			}
			if len(ranges) > 0 {
				out[f.rng] = ranges
			}
			conds++
		}
	}
	if nets := splitList(r["network"]); len(nets) > 0 {
		var n []string
		for _, x := range nets {
			if x == "tcp" || x == "udp" {
				n = append(n, x)
			}
		}
		if len(n) > 0 && len(n) < 2 { // tcp+udp — это все соединения, условие не нужно
			out["network"] = n
		}
		conds++
	}
	if p := strList(r["protocol"]); len(p) > 0 {
		out["protocol"] = p
		conds++
	}
	if in := strList(r["inboundTag"]); len(in) > 0 {
		out["inbound"] = in
		conds++
	}
	if conds == 0 {
		return nil, false, nil // у Xray правило без условий не срабатывает
	}
	switch kind {
	case "block":
		out["action"] = "reject"
	case "dns":
		out["action"] = "hijack-dns"
	default:
		out["action"] = "route"
		out["outbound"] = target
	}
	return out, hasIP, nil
}

func putDomains(out obj, d *GeoDomains) {
	if len(d.Full) > 0 {
		out["domain"] = d.Full
	}
	if len(d.Suffix) > 0 {
		out["domain_suffix"] = d.Suffix
	}
	if len(d.Keyword) > 0 {
		out["domain_keyword"] = d.Keyword
	}
	if len(d.Regex) > 0 {
		out["domain_regex"] = d.Regex
	}
}

// geoRef — "geosite:ru" / "ext:file.dat:ru" → файл и категория.
func geoRef(v, prefix, defFile string) (file, tag string, ok bool) {
	switch {
	case strings.HasPrefix(v, prefix+":"):
		return defFile, strings.TrimPrefix(v, prefix+":"), true
	case strings.HasPrefix(v, "ext:"):
		f, t, found := strings.Cut(strings.TrimPrefix(v, "ext:"), ":")
		if !found {
			return "", "", false
		}
		return f, t, true
	}
	return "", "", false
}

func (c *converter) domains(list []string) (*GeoDomains, error) {
	d := &GeoDomains{}
	for _, v := range list {
		v = strings.TrimSpace(v)
		switch {
		case v == "" || strings.HasPrefix(v, "!"):
		case strings.HasPrefix(v, "domain:"):
			d.Suffix = append(d.Suffix, strings.TrimPrefix(v, "domain:"))
		case strings.HasPrefix(v, "full:"):
			d.Full = append(d.Full, strings.TrimPrefix(v, "full:"))
		case strings.HasPrefix(v, "keyword:"):
			d.Keyword = append(d.Keyword, strings.TrimPrefix(v, "keyword:"))
		case strings.HasPrefix(v, "regexp:"):
			d.Regex = append(d.Regex, strings.TrimPrefix(v, "regexp:"))
		case strings.HasPrefix(v, "dotless:"):
			c.warn = append(c.warn, "dotless пропущен")
		case strings.HasPrefix(v, "geosite:") || strings.HasPrefix(v, "ext:"):
			file, tag, ok := geoRef(v, "geosite", "geosite.dat")
			if !ok {
				continue
			}
			g, err := LoadGeoSite(c.o.GeoDir, file, tag)
			if err != nil {
				c.warn = append(c.warn, err.Error())
				continue
			}
			d.Full = append(d.Full, g.Full...)
			d.Suffix = append(d.Suffix, g.Suffix...)
			d.Keyword = append(d.Keyword, g.Keyword...)
			d.Regex = append(d.Regex, g.Regex...)
		default: // у Xray строка без префикса — поиск подстроки
			d.Keyword = append(d.Keyword, v)
		}
	}
	return d, nil
}

func (c *converter) ips(list []string) (cidrs []string, private bool, err error) {
	for _, v := range list {
		v = strings.TrimSpace(v)
		switch {
		case v == "" || strings.HasPrefix(v, "!"):
		case strings.EqualFold(v, "geoip:private"):
			private = true
		case strings.HasPrefix(v, "geoip:") || strings.HasPrefix(v, "ext:"):
			file, tag, ok := geoRef(v, "geoip", "geoip.dat")
			if !ok {
				continue
			}
			g, err := LoadGeoIP(c.o.GeoDir, file, tag)
			if err != nil {
				c.warn = append(c.warn, err.Error())
				continue
			}
			cidrs = append(cidrs, g...)
		default:
			p, err := toPrefix(v)
			if err != nil {
				c.warn = append(c.warn, "непонятный IP "+v)
				continue
			}
			cidrs = append(cidrs, p)
		}
	}
	return cidrs, private, nil
}

func toPrefix(v string) (string, error) {
	if strings.Contains(v, "/") {
		p, err := netip.ParsePrefix(v)
		if err != nil {
			return "", err
		}
		return p.Masked().String(), nil
	}
	a, err := netip.ParseAddr(v)
	if err != nil {
		return "", err
	}
	return netip.PrefixFrom(a, a.BitLen()).String(), nil
}

// portsOf — "53", 443, "1000-2000,8443" → порты и диапазоны sing-box ("1000:2000").
func portsOf(v any) (ports []int, ranges []string, err error) {
	for _, part := range splitList(v) {
		if a, b, ok := strings.Cut(part, "-"); ok {
			lo, e1 := strconv.Atoi(strings.TrimSpace(a))
			hi, e2 := strconv.Atoi(strings.TrimSpace(b))
			if e1 != nil || e2 != nil || lo > hi {
				return nil, nil, fmt.Errorf("порты %q", part)
			}
			ranges = append(ranges, fmt.Sprintf("%d:%d", lo, hi))
			continue
		}
		p, err := strconv.Atoi(part)
		if err != nil {
			return nil, nil, fmt.Errorf("порт %q", part)
		}
		ports = append(ports, p)
	}
	return ports, ranges, nil
}

// ---------- DNS ----------

func strategyOf(d obj) string {
	switch strings.ToLower(str(d, "queryStrategy")) {
	case "useipv4":
		return "ipv4_only"
	case "useipv6":
		return "ipv6_only"
	}
	return ""
}

// dnsServer — адрес DNS в формате Xray → сервер sing-box. local — запросы напрямую, мимо прокси.
func (c *converter) dnsServer(addr string, port int, tag string) (obj, error) {
	local := false
	for _, p := range []string{"https+local://", "tcp+local://", "quic+local://"} {
		if strings.HasPrefix(addr, p) {
			addr = strings.Replace(addr, "+local", "", 1)
			local = true
		}
	}
	s := obj{"tag": tag}
	switch {
	case strings.HasPrefix(addr, "https://"):
		u, err := url.Parse(addr)
		if err != nil || u.Hostname() == "" {
			return nil, fmt.Errorf("DNS %q", addr)
		}
		s["type"], s["server"] = "https", u.Hostname()
		if p := u.Port(); p != "" {
			s["server_port"], _ = strconv.Atoi(p)
		}
		if u.Path != "" && u.Path != "/dns-query" {
			s["path"] = u.Path
		}
	case strings.HasPrefix(addr, "tcp://"), strings.HasPrefix(addr, "tls://"), strings.HasPrefix(addr, "udp://"):
		typ, rest, _ := strings.Cut(addr, "://")
		host, p, err := net.SplitHostPort(rest)
		if err != nil {
			host = rest
		} else if n, _ := strconv.Atoi(p); n > 0 {
			s["server_port"] = n
		}
		s["type"], s["server"] = typ, strings.Trim(host, "[]")
	case strings.HasPrefix(addr, "quic://"):
		return nil, nil // DNS over QUIC не собран — пропускаем
	case addr == "localhost" || addr == "fakedns":
		return nil, nil
	default:
		host := addr
		if h, p, err := net.SplitHostPort(addr); err == nil {
			host = h
			if n, _ := strconv.Atoi(p); n > 0 {
				port = n
			}
		}
		if _, err := netip.ParseAddr(host); err != nil {
			return nil, fmt.Errorf("DNS %q", addr)
		}
		s["type"], s["server"] = "udp", host
		if port > 0 && port != 53 {
			s["server_port"] = port
		}
	}
	if !local {
		s["detour"] = c.proxyTag // у Xray запросы встроенного DNS идут через маршрутизацию, то есть через сервер
	}
	return s, nil
}

func (c *converter) dns(d obj) (obj, error) {
	var servers, rules []any
	final := ""
	// для имён серверов (если адрес — домен) и DNS по имени — прямой запрос
	servers = append(servers, obj{"type": "udp", "tag": "dns-direct", "server": "1.1.1.1"})

	if hosts := mapOf(d["hosts"]); len(hosts) > 0 {
		pre := obj{}
		var names []string
		for name, v := range hosts {
			if strings.Contains(name, ":") { // domain:/geosite: и т.п. — в sing-box hosts только точные имена
				if !strings.HasPrefix(name, "full:") {
					continue
				}
				name = strings.TrimPrefix(name, "full:")
			}
			var ips []string
			for _, ip := range splitList(v) {
				if _, err := netip.ParseAddr(ip); err == nil {
					ips = append(ips, ip)
				}
			}
			if len(ips) > 0 {
				pre[name] = ips
				names = append(names, name)
			}
		}
		if len(names) > 0 {
			servers = append(servers, obj{"type": "hosts", "tag": "dns-hosts", "predefined": pre})
			rules = append(rules, obj{"domain": names, "action": "route", "server": "dns-hosts"})
		}
	}

	for i, raw := range listOf(d["servers"]) {
		tag := fmt.Sprintf("dns-%d", i)
		var addr string
		var port int
		var domains []string
		switch v := raw.(type) {
		case string:
			addr = v
		case map[string]any:
			addr, port, domains = str(v, "address"), toInt(v["port"]), strList(v["domains"])
		default:
			continue
		}
		s, err := c.dnsServer(strings.TrimSpace(addr), port, tag)
		if err != nil {
			return nil, err
		}
		if s == nil {
			continue
		}
		servers = append(servers, s)
		if len(domains) > 0 {
			g, err := c.domains(domains)
			if err != nil {
				return nil, err
			}
			if !g.empty() {
				rule := obj{"action": "route", "server": tag}
				putDomains(rule, g)
				rules = append(rules, rule)
			}
			continue
		}
		if final == "" {
			final = tag
		}
	}
	if final == "" { // в подписке нет общего DNS — Cloudflare через сервер
		servers = append(servers, obj{"type": "udp", "tag": "dns-remote", "server": "1.1.1.1", "detour": c.proxyTag})
		final = "dns-remote"
	}
	out := obj{"servers": servers, "final": final}
	if len(rules) > 0 {
		out["rules"] = rules
	}
	if s := strategyOf(d); s != "" {
		out["strategy"] = s
	}
	return out, nil
}

// ---------- мелочи ----------

func mapOf(v any) obj {
	m, _ := v.(map[string]any)
	return m
}

func listOf(v any) []any {
	l, _ := v.([]any)
	return l
}

func firstMap(v any) obj {
	l := listOf(v)
	if len(l) == 0 {
		return nil
	}
	return mapOf(l[0])
}

func str(m obj, key string) string {
	if m == nil {
		return ""
	}
	switch v := m[key].(type) {
	case string:
		return v
	case float64:
		return strconv.FormatFloat(v, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(v)
	}
	return ""
}

func toInt(v any) int {
	switch x := v.(type) {
	case float64:
		return int(x)
	case int:
		return x
	case json.Number:
		n, _ := x.Int64()
		return int(n)
	case string:
		n, _ := strconv.Atoi(strings.TrimSpace(x))
		return n
	}
	return 0
}

func truthy(v any) bool {
	b, _ := v.(bool)
	return b
}

// strList — массив строк или одна строка.
func strList(v any) []string {
	switch x := v.(type) {
	case string:
		if x == "" {
			return nil
		}
		return []string{x}
	case []any:
		var out []string
		for _, e := range x {
			switch s := e.(type) {
			case string:
				out = append(out, s)
			case float64:
				out = append(out, strconv.FormatFloat(s, 'f', -1, 64))
			}
		}
		return out
	}
	return nil
}

// splitList — "tcp,udp" / ["tcp","udp"] / 53 → элементы.
func splitList(v any) []string {
	var out []string
	for _, s := range strList(v) {
		for _, p := range strings.Split(s, ",") {
			if p = strings.TrimSpace(strings.ToLower(p)); p != "" {
				out = append(out, p)
			}
		}
	}
	if f, ok := v.(float64); ok {
		out = append(out, strconv.Itoa(int(f)))
	}
	return out
}

// PingConfig — конфиг для замера задержки: только выход к серверу из конфига подписки (формат Xray).
func PingConfig(xrayConfig []byte) ([]byte, error) {
	var root obj
	if err := json.Unmarshal(xrayConfig, &root); err != nil {
		return nil, fmt.Errorf("непонятный конфиг: %v", err)
	}
	var proxy obj
	for _, raw := range listOf(root["outbounds"]) {
		ob := mapOf(raw)
		switch strings.ToLower(str(ob, "protocol")) {
		case "", "freedom", "blackhole", "dns":
			continue
		}
		if proxy == nil || str(ob, "tag") == "proxy" {
			proxy = ob
		}
		if str(ob, "tag") == "proxy" {
			break
		}
	}
	if proxy == nil {
		return nil, errors.New("в конфиге нет сервера")
	}
	return Convert(mustJSON(obj{"log": obj{"loglevel": "none"}, "outbounds": []any{proxy}}), Options{})
}

func mustJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}
