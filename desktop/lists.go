package main

import (
	"net/netip"
	"regexp"
	"strings"

	"golang.org/x/net/idna"
)

// Свои списки основного VPN — как в Android (Lists.kt): готовые списки «напрямую» (категории
// geo-файлов профиля сервера) и свои адреса напрямую и через VPN. Действуют в обоих режимах маршрутизации.

type readyList struct {
	ID, Title, Hint string
	Sites, IPs      []string
}

// Названия и описания — ключи перевода.
var readyLists = []readyList{
	{"ru-services", "Российские сервисы", "Госуслуги, банки, магазины, доставка, соцсети, операторы связи",
		[]string{"geosite:whitelist"}, []string{"geoip:whitelist"}},
	{"ru-all", "Все российские сайты", "Все сайты в зонах .ru, .рф, .su и все российские IP-адреса",
		[]string{"domain:ru", "domain:xn--p1ai", "domain:su"}, []string{"geoip:direct"}},
	{"ru-only", "Сайты только для России", "Сайты, которые открываются только с российского IP: часть банков, сервисы проверки IP и др.",
		[]string{"geosite:category-ru"}, nil},
	{"games", "Игры", "Steam, Epic Games, Riot Games, Faceit, Escape from Tarkov, EA",
		[]string{"geosite:steam", "geosite:epicgames", "geosite:riot", "geosite:faceit", "geosite:escapefromtarkov", "geosite:origin"}, nil},
	{"microsoft", "Microsoft", "Обновления Windows, Office, Xbox", []string{"geosite:microsoft"}, nil},
	{"apple", "Apple", "iCloud, App Store, обновления", []string{"geosite:apple"}, nil},
}

// UserLists — выбор пользователя.
type UserLists struct {
	Ready  []string `json:"ready"`
	Direct string   `json:"direct"`
	Proxy  string   `json:"proxy"`
}

func (u UserLists) chosen() []readyList {
	var out []readyList
	for _, l := range readyLists {
		for _, id := range u.Ready {
			if id == l.ID {
				out = append(out, l)
				break
			}
		}
	}
	return out
}

// needsGeo — выбраны готовые списки из категорий geo-файлов.
func (u UserLists) needsGeo() bool {
	for _, l := range u.chosen() {
		for _, v := range append(append([]string{}, l.Sites...), l.IPs...) {
			if strings.HasPrefix(v, "geo") {
				return true
			}
		}
	}
	return false
}

var domainRe = regexp.MustCompile(`^[\p{L}\p{N}_*-]+(\.[\p{L}\p{N}_-]+)+$`)

func isIPOrPrefix(v string) bool {
	if _, err := netip.ParsePrefix(v); err == nil {
		return true
	}
	_, err := netip.ParseAddr(v)
	return err == nil
}

// parseList — текст (по строке на адрес) → домены в формате Xray и IP/подсети. # — комментарий.
func parseList(text string) (domains, ips []string) {
	seen := map[string]bool{}
	add := func(dst *[]string, v string) {
		if !seen[v] {
			seen[v] = true
			*dst = append(*dst, v)
		}
	}
	for _, line := range strings.Split(text, "\n") {
		line, _, _ = strings.Cut(line, "#")
		for _, v := range strings.FieldsFunc(line, func(r rune) bool { return r == ',' || r == ';' || r == ' ' || r == '\t' || r == '\r' }) {
			v = strings.ToLower(strings.TrimSpace(v))
			switch {
			case v == "":
				continue
			case strings.HasPrefix(v, "geosite:") || strings.HasPrefix(v, "domain:") || strings.HasPrefix(v, "full:") || strings.HasPrefix(v, "keyword:"):
				add(&domains, v)
				continue
			case strings.HasPrefix(v, "geoip:"):
				add(&ips, v)
				continue
			case isIPOrPrefix(v):
				add(&ips, v)
				continue
			}
			if i := strings.Index(v, "://"); i >= 0 {
				v = v[i+3:]
			}
			v, _, _ = strings.Cut(v, "/")
			v, _, _ = strings.Cut(v, "?")
			if isIPOrPrefix(v) {
				add(&ips, v)
				continue
			}
			v, _, _ = strings.Cut(v, ":")
			v = strings.Trim(strings.TrimPrefix(v, "*."), ".")
			if domainRe.MatchString(v) {
				if a, err := idna.ToASCII(v); err == nil {
					add(&domains, "domain:"+a)
				}
			}
		}
	}
	return
}

func filterGeo(vals []string, geo bool) []any {
	out := []any{}
	for _, v := range vals {
		if geo || !strings.HasPrefix(v, "geo") {
			out = append(out, v)
		}
	}
	return out
}

// userRules — правила пользователя в порядке важности. geo — geo-файлы профиля скачаны
// (без них категории geosite:/geoip: пропускаются).
func userRules(u UserLists, proxyTag string, geo bool) []any {
	var rules []any
	add := func(domains, ips []string, tag string) {
		if d := mapGeo(filterGeo(domains, geo), false); len(d) > 0 {
			rules = append(rules, obj{"type": "field", "domain": d, "outboundTag": tag})
		}
		if i := mapGeo(filterGeo(ips, geo), true); len(i) > 0 {
			rules = append(rules, obj{"type": "field", "ip": i, "outboundTag": tag})
		}
	}
	d, i := parseList(u.Proxy)
	add(d, i, proxyTag)
	d, i = parseList(u.Direct)
	add(d, i, "direct")
	var sites, ips []string
	for _, l := range u.chosen() {
		sites = append(sites, l.Sites...)
		ips = append(ips, l.IPs...)
	}
	add(sites, ips, "direct")
	return rules
}

// userDirectDomains — домены «напрямую» (для «домашнего» DNS).
func userDirectDomains(u UserLists, geo bool) []any {
	d, _ := parseList(u.Direct)
	for _, l := range u.chosen() {
		d = append(d, l.Sites...)
	}
	return mapGeo(filterGeo(d, geo), false)
}
