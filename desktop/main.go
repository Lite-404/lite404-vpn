// Lite404 VPN для компьютера — то же, что Android-приложение, с интерфейсом под ПК:
// основной VPN (ядро sing-box-extended, конфиги в формате Xray) и резервный VPN (WDTT через звонки VK). Трафик идёт через локальный прокси
// (SOCKS 10808 / HTTP 10809) и системный прокси; интерфейс — собственное окно приложения.
// Данные — по тому же токену, что и на телефоне.
package main

import (
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"lite404/boxcore"
)

var version = "dev"

const uiAddr = "127.0.0.1:47404"

//go:embed web
var webFS embed.FS

// Test — результат проверки соединения после подключения.
type Test struct {
	Running bool   `json:"running"`
	OK      bool   `json:"ok"`
	Text    string `json:"text"` // ключ перевода (как в Android-приложении), %s — Arg
	Arg     string `json:"arg"`
}

type App struct {
	mu          sync.Mutex
	s           *Settings
	cfg         *AppConfig
	inst        *boxcore.Instance
	instConf    string // WireGuard-конфиг, на котором поднят Xray в режиме «Резерв»
	mode        string // "" | xray | wdtt
	phase       string // off | connecting | on | stopping
	errText     string
	notice      string
	test        *Test
	conn        int // индекс сервера (xray) или страны (wdtt)
	since       time.Time
	wdttUp      time.Time // момент, когда у резервного VPN появились активные потоки
	tested      bool      // проверка соединения резервного VPN в этой сессии уже была
	pings       map[int]int64
	pinging     bool
	loading     bool
	proxyErr    string
	wdtt        *Wdtt
	speedUp     int64
	speedDn     int64
	lastUp      int64
	lastDn      int64
	upd         Updater
	journal     desktopJournal
	generation  uint64
	uiMu        sync.RWMutex
	ui          desktopUI
	uiReady     atomic.Bool
	tun         *tunSession
	tunStarting bool
	tunCancel   chan struct{}
	networkMode string // mode of the running session; preference applies on next connect
}

func (a *App) loadCached() {
	if len(a.s.Config) > 0 {
		var c AppConfig
		if json.Unmarshal(a.s.Config, &c) == nil {
			a.cfg = &c
		}
	}
}

// profile — профиль умной маршрутизации (из панели); nil — всё через VPN.
func (a *App) profile() obj {
	if a.s.Routing == "off" {
		return nil
	}
	return a.serverProfile()
}

// serverProfile — профиль с сервера независимо от режима (из него берутся geo-файлы для готовых списков).
func (a *App) serverProfile() obj {
	if a.cfg == nil {
		return nil
	}
	for _, name := range []string{"happ", "incy"} {
		if raw, ok := a.cfg.Routing[name]; ok {
			var p obj
			if json.Unmarshal(raw, &p) == nil {
				return p
			}
		}
	}
	return nil
}

func (a *App) refresh() error {
	a.mu.Lock()
	tok := a.s.Token
	a.loading = true
	a.mu.Unlock()
	defer func() { a.mu.Lock(); a.loading = false; a.mu.Unlock() }()
	t, err := ParseToken(tok)
	if err != nil {
		return err
	}
	c, raw, err := FetchConfig(t, hwid())
	if err != nil {
		return err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.cfg = c
	a.s.Config = raw
	a.s.save()
	return nil
}

// startProxy — поднять Xray с готовым конфигом и включить системный прокси. Под a.mu.
func (a *App) startProxyLocked(cfg []byte, lan bool) error {
	if a.inst != nil {
		a.inst.Close()
		a.inst = nil
	}
	settings := *a.s
	settings.NetworkMode = a.networkMode
	inst, err := startConfiguredInstance(cfg, &settings)
	if err != nil {
		return err
	}
	a.inst, a.lastUp, a.lastDn = inst, 0, 0
	a.proxyErr = ""
	if a.s.SystemProxy && a.networkMode != "tun" {
		if perr := setSystemProxy(true, lan); perr != nil {
			a.proxyErr = "Системный прокси не включился — укажите вручную HTTP 127.0.0.1:10809"
		} else {
			a.s.ProxyActive = true
			a.s.save()
		}
	}
	a.startTunAsyncLocked(lan)
	return nil
}

func (a *App) stopProxyLocked() {
	if a.tunCancel != nil {
		close(a.tunCancel)
		a.tunCancel = nil
	}
	if a.tun != nil {
		a.tun.Close()
		a.tun = nil
	}
	a.tunStarting = false
	if a.s.ProxyActive {
		setSystemProxy(false, false)
		a.s.ProxyActive = false
		a.s.save()
	}
	if a.inst != nil {
		a.inst.Close()
		a.inst = nil
	}
	a.instConf, a.speedUp, a.speedDn = "", 0, 0
}

// stopAll — выключить всё (оба режима). Под a.mu.
func (a *App) stopAllLocked() {
	a.generation++
	a.wdtt.Stop()
	a.stopProxyLocked()
	a.mode, a.phase, a.test, a.notice = "", "off", nil, ""
	a.since, a.wdttUp = time.Time{}, time.Time{}
}

func (a *App) connectXray(i int) error {
	a.mu.Lock()
	if a.cfg == nil || i < 0 || i >= len(a.cfg.Xray) {
		a.mu.Unlock()
		return errors.New("Нет серверов. Нажмите «Обновить подписку»")
	}
	if msg := ErrorText(a.cfg.Error); msg != "" {
		a.mu.Unlock()
		return errors.New(msg)
	}
	a.stopAllLocked()
	a.networkMode = a.s.NetworkMode
	server, profile, geoSrc, user := a.cfg.Xray[i], a.profile(), a.serverProfile(), a.s.Lists
	generation := a.generation
	a.mode, a.phase, a.conn, a.errText = "xray", "connecting", i, ""
	a.s.Server = i
	a.s.LastMode = "xray"
	a.s.save()
	a.mu.Unlock()

	notice := ""
	if profile != nil {
		if err := ensureGeo(profile); err != nil {
			profile = nil
			notice = "Не удалось скачать правила маршрутизации — сейчас всё идёт через VPN"
		}
	}
	geo := profile != nil
	if !geo && user.needsGeo() && geoSrc != nil { // готовым спискам нужны geo-файлы и при «Всё через VPN»
		geo = ensureGeo(geoSrc) == nil
	}
	if user.needsGeo() && !geo && notice == "" {
		notice = "Готовые списки недоступны — правила не скачались или не настроены на сервере"
	}
	cfg, err := BuildConfig(server, profile, *a.s.LanDirect, user, geo)
	a.mu.Lock()
	if a.mode != "xray" || a.conn != i || a.generation != generation { // подключение уже отменено или заменено
		a.mu.Unlock()
		return nil
	}
	if err == nil {
		err = a.startProxyLocked(cfg, *a.s.LanDirect)
	}
	if err != nil {
		a.mode, a.phase, a.errText = "", "off", err.Error()
		a.mu.Unlock()
		return err
	}
	a.phase, a.notice, a.since = "on", notice, time.Now()
	if a.networkMode == "tun" && a.tun == nil {
		a.phase = "connecting"
	}
	testAfter := a.s.TestAfter && a.networkMode != "tun"
	a.mu.Unlock()
	if testAfter {
		go a.runTest()
	}
	return nil
}

func (a *App) connectWdtt(i int) error {
	a.mu.Lock()
	if a.cfg == nil {
		a.mu.Unlock()
		return errors.New("Нет серверов. Нажмите «Обновить подписку»")
	}
	if a.cfg.Error == "expired" {
		a.mu.Unlock()
		return errors.New(ErrorText("expired"))
	}
	if a.cfg.VKHash == "" {
		a.mu.Unlock()
		return errors.New("Добавьте хеш VK в боте")
	}
	if i < 0 || i >= len(a.cfg.Wdtt) {
		a.mu.Unlock()
		return errors.New("Для вас нет серверов резервного VPN")
	}
	a.stopAllLocked()
	a.networkMode = a.s.NetworkMode
	server, hash := a.cfg.Wdtt[i], a.cfg.VKHash
	generation := a.generation
	geoProfile := a.serverProfile()
	needsGeo := a.s.WdttRouting == "smart" || a.s.WdttLists.needsGeo()
	workers := a.s.WdttWorkers
	a.mode, a.phase, a.conn, a.errText, a.tested = "wdtt", "connecting", i, "", false
	a.s.WdttServer = i
	a.s.LastMode = "wdtt"
	a.s.save()
	a.mu.Unlock()
	if needsGeo && geoProfile != nil {
		if err := ensureGeo(geoProfile); err != nil {
			a.mu.Lock()
			if a.generation == generation {
				a.mode, a.phase, a.errText = "", "off", err.Error()
			}
			a.mu.Unlock()
			return err
		}
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.generation != generation || a.mode != "wdtt" {
		return nil
	}
	if err := a.wdtt.Start(server, hash, workers, func(conf string) { a.onWdttConfig(conf, generation) }, func(reason string) { a.onWdttFatal(reason, generation) }); err != nil {
		a.mode, a.phase, a.errText = "", "off", err.Error()
		return err
	}
	if a.mode == "wdtt" {
		a.phase, a.since = "connecting", time.Now()
	}
	return nil
}

// onWdttConfig — клиент WDTT выдал WireGuard-конфиг: поднимаем Xray с выходом WireGuard.
func (a *App) onWdttConfig(conf string, generation uint64) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.mode != "wdtt" || a.generation != generation || conf == a.instConf && a.inst != nil {
		return
	}
	server, err := WireGuardServer(conf)
	var cfg []byte
	if err == nil {
		var profile obj
		if a.s.WdttRouting == "smart" {
			profile = a.serverProfile()
		}
		// Geo files are prepared before starting the WDTT process.
		_, geoErr := os.Stat(filepath.Join(geoDirPath, geoSite))
		cfg, err = BuildConfig(server, profile, a.s.LanWdtt, a.s.WdttLists, geoErr == nil)
	}
	if err == nil {
		err = a.startProxyLocked(cfg, a.s.LanWdtt)
	}
	if err != nil {
		a.wdtt.mu.Lock()
		a.wdtt.log("Ошибка запуска VPN: "+err.Error(), true)
		a.wdtt.mu.Unlock()
		return
	}
	a.instConf = conf
}

func (a *App) onWdttFatal(reason string, generation uint64) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.mode != "wdtt" || a.generation != generation {
		return
	}
	a.stopAllLocked()
	a.errText = reason
}

func (a *App) disconnect() {
	a.mu.Lock()
	a.stopAllLocked()
	a.mu.Unlock()
}

// runTest — проверка, что интернет через VPN работает (как «Проверить» на Android).
func (a *App) runTest() {
	a.mu.Lock()
	inst, url := a.inst, a.s.PingURL
	if inst == nil || a.phase != "on" {
		a.mu.Unlock()
		return
	}
	a.test = &Test{Running: true, Text: "Проверяем соединение…"}
	a.mu.Unlock()
	var ms int64
	var err error
	for attempt := 0; attempt < 3; attempt++ { // туннель может разгоняться пару секунд
		if ms, err = measure(inst, url); err == nil {
			break
		}
		time.Sleep(2 * time.Second)
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.inst != inst {
		return
	}
	if err != nil {
		msg := err.Error()
		if len(msg) > 120 {
			msg = msg[:120]
		}
		a.test = &Test{OK: false, Text: "Сайты не открываются: %s", Arg: msg}
	} else {
		a.test = &Test{OK: true, Text: "Интернет работает · %s мс", Arg: fmt.Sprint(ms)}
	}
}

func (a *App) pingAll() {
	a.mu.Lock()
	if a.pinging || a.cfg == nil {
		a.mu.Unlock()
		return
	}
	servers, url, method := a.cfg.Xray, a.s.PingURL, a.s.PingMethod
	a.pinging, a.pings = true, map[int]int64{}
	a.mu.Unlock()
	var wg sync.WaitGroup
	for i, s := range servers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ms := pingMethod(s, url, method)
			a.mu.Lock()
			a.pings[i] = ms
			a.mu.Unlock()
		}()
	}
	wg.Wait()
	a.mu.Lock()
	a.pinging = false
	a.mu.Unlock()
}

// tick — раз в секунду: скорость, момент появления потоков WDTT, проверка соединения резервного VPN.
func (a *App) tick() {
	var network networkTracker
	for range time.Tick(time.Second) {
		if network.changed(physicalNetworkKey(), time.Now()) {
			a.wdtt.RestartTransport()
		}
		w := a.wdtt.State()
		a.mu.Lock()
		if a.inst != nil {
			up, dn := Traffic(a.inst)
			if a.lastUp > 0 || a.lastDn > 0 {
				a.speedUp, a.speedDn = max(up-a.lastUp, 0), max(dn-a.lastDn, 0)
			}
			a.lastUp, a.lastDn = up, dn
		}
		if a.s.JournalPrimary && a.s.LastMode != "wdtt" {
			text := "Основной VPN · " + a.phase
			if a.errText != "" {
				text += " · " + a.errText
			}
			if a.test != nil {
				text += " · " + strings.ReplaceAll(a.test.Text, "%s", a.test.Arg)
			}
			a.journal.record(text, a.errText != "")
		}
		runTest := false
		if a.mode == "wdtt" {
			if w.Active > 0 && a.inst != nil && (a.networkMode != "tun" || a.tun != nil) {
				a.phase = "on"
			} else {
				a.phase = "connecting"
			}
			if w.Active > 0 && a.wdttUp.IsZero() {
				a.wdttUp = time.Now()
			}
			if a.phase == "on" && !a.tested && a.s.TestAfter {
				a.tested, runTest = true, true
			}
		}
		a.mu.Unlock()
		if runTest {
			go func() { time.Sleep(2 * time.Second); a.runTest() }()
		}
	}
}

func (a *App) state() obj {
	w := a.wdtt.State()
	a.mu.Lock()
	defer a.mu.Unlock()
	st := obj{
		"version": version, "xrayVersion": "sing-box " + boxcore.Version(), "os": osName(),
		"hasToken": a.s.Token != "", "loading": a.loading,
		"mode": a.mode, "phase": a.phase, "error": a.errText, "notice": a.notice, "test": a.test, "conn": a.conn,
		"server": a.s.Server, "wdttServer": a.s.WdttServer,
		"routing": a.s.Routing, "lanDirect": *a.s.LanDirect, "lanWdtt": a.s.LanWdtt,
		"systemProxy": a.s.SystemProxy, "pingUrl": a.s.PingURL, "pingUrls": pingURLs,
		"pingOnOpen": a.s.PingOnOpen, "testAfter": a.s.TestAfter, "lang": a.s.Lang,
		"lists": a.s.Lists, "readyLists": readyLists,
		"wdttLists": a.s.WdttLists, "wdttRouting": a.s.WdttRouting, "wdttWorkers": a.s.WdttWorkers,
		"pingMethod": a.s.PingMethod, "appMode": a.s.AppMode, "selectedApps": a.s.SelectedApps,
		"connectOnOpen": a.s.ConnectOnOpen, "connectOnBoot": a.s.ConnectOnBoot, "appearance": a.s.Appearance,
		"journalPrimary": a.s.JournalPrimary, "journalReserve": a.s.JournalReserve,
		"pinging": a.pinging, "pings": a.pings, "proxyHint": proxyHint, "proxyError": a.proxyErr,
		"socks": fmt.Sprintf("127.0.0.1:%d", socksPort), "http": fmt.Sprintf("127.0.0.1:%d", httpPort),
		"wdttActive": w.Active, "wdttStats": w.Stats, "speedUp": a.speedUp, "speedDown": a.speedDn,
		"wdttAvailable": len(wdttClient) > 0, "now": time.Now().UnixMilli(),
		"update":       a.upd.State(),
		"nativeWindow": a.nativeUI() != nil, "windowReady": a.uiReady.Load(),
		"networkMode": a.s.NetworkMode, "tunActive": a.tun != nil, "tunStarting": a.tunStarting,
	}
	if a.phase == "on" && a.mode == "xray" {
		st["since"] = a.since.UnixMilli()
	}
	if a.mode == "wdtt" && !a.wdttUp.IsZero() {
		st["since"] = a.wdttUp.UnixMilli()
	}
	if a.token() != nil {
		st["serverAddr"] = a.token().Addr
	}
	if a.cfg != nil {
		names := []string{}
		for i, s := range a.cfg.Xray {
			names = append(names, ServerName(s, i))
		}
		countries := []string{}
		for _, s := range a.cfg.Wdtt {
			countries = append(countries, s.Name)
		}
		st["servers"], st["countries"] = names, countries
		st["user"], st["subError"], st["subErrorCode"] = a.cfg.User, ErrorText(a.cfg.Error), a.cfg.Error
		st["hasHash"] = a.cfg.VKHash != ""
		st["smartReady"] = len(a.cfg.Routing) > 0
	}
	return st
}

func (a *App) token() *Token {
	t, _ := ParseToken(a.s.Token)
	return t
}

func (a *App) handler() http.Handler {
	mux := http.NewServeMux()
	page, _ := webFS.ReadFile("web/index.html")
	i18n, _ := fs.Sub(webFS, "web/i18n")
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(page)
	})
	mux.Handle("GET /i18n/", http.StripPrefix("/i18n/", http.FileServerFS(i18n)))
	assets, _ := fs.Sub(webFS, "web/assets")
	mux.Handle("GET /assets/", http.StripPrefix("/assets/", http.FileServerFS(assets)))
	reply := func(w http.ResponseWriter, err error, extra obj) {
		out := a.state()
		for k, v := range extra {
			out[k] = v
		}
		if err != nil {
			out["message"] = err.Error()
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		json.NewEncoder(w).Encode(out)
	}
	body := func(r *http.Request) obj {
		var m obj
		json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<20)).Decode(&m)
		if m == nil {
			m = obj{}
		}
		return m
	}
	num := func(m obj, k string) int { f, _ := m[k].(float64); return int(f) }
	mux.HandleFunc("GET /api/state", func(w http.ResponseWriter, r *http.Request) { reply(w, nil, nil) })
	mux.HandleFunc("POST /api/window/show", func(w http.ResponseWriter, r *http.Request) {
		if ui := a.nativeUI(); ui != nil {
			ui.Show()
			reply(w, nil, nil)
		} else {
			reply(w, errors.New("Окно приложения не запущено"), nil)
		}
	})
	mux.HandleFunc("POST /api/window/hide", func(w http.ResponseWriter, r *http.Request) {
		if ui := a.nativeUI(); ui != nil {
			ui.Hide()
		}
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/window/ready", func(w http.ResponseWriter, r *http.Request) {
		if r.Context().Value(nativeAssetRequest{}) != true {
			http.Error(w, "native window required", 403)
			return
		}
		a.uiReady.Store(true)
		reply(w, nil, nil)
	})
	mux.HandleFunc("GET /api/logs", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(a.journalLines(r.URL.Query().Get("mode")))
	})
	mux.HandleFunc("POST /api/logs/clear", func(w http.ResponseWriter, r *http.Request) {
		if body(r)["mode"] == "wdtt" {
			a.wdtt.ClearLogs()
		} else {
			a.journal.clear()
		}
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/token", func(w http.ResponseWriter, r *http.Request) {
		tok, _ := body(r)["token"].(string)
		if _, err := ParseToken(tok); err != nil {
			reply(w, err, nil)
			return
		}
		a.mu.Lock()
		old := a.s.Token
		a.s.Token = tok
		a.mu.Unlock()
		if err := a.refresh(); err != nil {
			a.mu.Lock()
			a.s.Token = old
			a.mu.Unlock()
			reply(w, err, nil)
			return
		}
		if a.s.PingOnOpen {
			go a.pingAll()
		}
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/refresh", func(w http.ResponseWriter, r *http.Request) {
		reply(w, a.refresh(), nil)
	})
	mux.HandleFunc("POST /api/connect", func(w http.ResponseWriter, r *http.Request) {
		m := body(r)
		var err error
		if m["mode"] == "wdtt" {
			err = a.connectWdtt(num(m, "server"))
		} else {
			err = a.connectXray(num(m, "server"))
		}
		reply(w, err, nil)
	})
	mux.HandleFunc("POST /api/select", func(w http.ResponseWriter, r *http.Request) {
		m := body(r)
		a.mu.Lock()
		if m["mode"] == "wdtt" {
			a.s.WdttServer = num(m, "server")
		} else {
			a.s.Server = num(m, "server")
		}
		a.s.save()
		a.mu.Unlock()
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/disconnect", func(w http.ResponseWriter, r *http.Request) {
		a.disconnect()
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/check", func(w http.ResponseWriter, r *http.Request) {
		go a.runTest()
		time.Sleep(100 * time.Millisecond)
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/ping", func(w http.ResponseWriter, r *http.Request) {
		go a.pingAll()
		time.Sleep(50 * time.Millisecond)
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/settings", func(w http.ResponseWriter, r *http.Request) {
		m := body(r)
		a.mu.Lock()
		applyPreviewSettings(a, m)
		if v, ok := m["lanDirect"].(bool); ok {
			a.s.LanDirect = &v
		}
		if v, ok := m["lanWdtt"].(bool); ok {
			a.s.LanWdtt = v
		}
		if v, ok := m["lists"].(map[string]any); ok {
			l := UserLists{}
			if r, ok := v["ready"].([]any); ok {
				for _, id := range r {
					if s, ok := id.(string); ok {
						l.Ready = append(l.Ready, s)
					}
				}
			}
			l.Direct, _ = v["direct"].(string)
			l.Proxy, _ = v["proxy"].(string)
			if len(l.Direct) <= 200_000 && len(l.Proxy) <= 200_000 {
				a.s.Lists = l
			}
		}
		if v, ok := m["routing"].(string); ok && (v == "smart" || v == "off") {
			a.s.Routing = v
		}
		if v, ok := m["pingUrl"].(string); ok {
			for _, u := range pingURLs {
				if u == v {
					a.s.PingURL = v
				}
			}
		}
		if v, ok := m["pingOnOpen"].(bool); ok {
			a.s.PingOnOpen = v
		}
		if v, ok := m["testAfter"].(bool); ok {
			a.s.TestAfter = v
		}
		if v, ok := m["lang"].(string); ok && len(v) <= 5 {
			a.s.Lang = v
		}
		if v, ok := m["systemProxy"].(bool); ok && v != a.s.SystemProxy {
			a.s.SystemProxy = v
			if a.inst != nil && a.networkMode != "tun" { // применяем сразу в режиме прокси
				lan := *a.s.LanDirect
				if a.mode == "wdtt" {
					lan = a.s.LanWdtt
				}
				if v && setSystemProxy(true, lan) == nil {
					a.s.ProxyActive = true
				} else if !v && a.s.ProxyActive {
					setSystemProxy(false, false)
					a.s.ProxyActive = false
				}
			}
		}
		a.s.save()
		a.mu.Unlock()
		reply(w, nil, nil)
	})
	mux.HandleFunc("GET /api/apps", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(runningApps())
	})
	mux.HandleFunc("POST /api/update/check", func(w http.ResponseWriter, r *http.Request) {
		m, err := a.checkUpdate()
		var u any
		if m != nil {
			u = obj{"version": m.Version, "notes": m.Notes, "size": m.Files[assetName()].Size}
		}
		reply(w, err, obj{"update": u, "available": u != nil})
	})
	mux.HandleFunc("POST /api/update/apply", func(w http.ResponseWriter, r *http.Request) {
		reply(w, a.startUpdate(), nil)
	})
	mux.HandleFunc("POST /api/logout", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		a.stopAllLocked()
		a.s.Token, a.s.Config, a.cfg, a.pings = "", nil, nil, nil
		a.s.save()
		a.mu.Unlock()
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/quit", func(w http.ResponseWriter, r *http.Request) {
		reply(w, nil, nil)
		go func() { time.Sleep(300 * time.Millisecond); a.quit() }()
	})
	// Защита: только с этой страницы (свой заголовок не пошлёт чужой сайт без CORS), только Host 127.0.0.1/localhost.
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != uiAddr && r.Host != "localhost:47404" {
			http.Error(w, "forbidden", 403)
			return
		}
		if r.Method != "GET" && r.Header.Get("X-Lite404") != "1" {
			http.Error(w, "forbidden", 403)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func (a *App) quit() {
	if ui := a.nativeUI(); ui != nil {
		ui.Quit()
		return
	}
	a.disconnect()
	time.Sleep(300 * time.Millisecond) // клиент WDTT получает STOP и выходит
	os.Exit(0)
}

func main() {
	if len(os.Args) == 3 && os.Args[1] == "--tun-worker" {
		if err := runTunWorker(os.Args[2]); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "--version" { // проверка новой версии при обновлении
		fmt.Println(version)
		return
	}
	afterUpdate := os.Getenv("LITE404_AFTER_UPDATE") != ""
	os.Unsetenv("LITE404_AFTER_UPDATE")
	dir := dataDir()
	if f, err := os.OpenFile(filepath.Join(dir, "lite404.log"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600); err == nil {
		log.SetOutput(f)
		os.Stderr = f
	}
	geoDirPath = filepath.Join(dir, "geo")

	ln, err := net.Listen("tcp", uiAddr)
	for i := 0; err != nil && afterUpdate && i < 60; i++ { // старая версия ещё выходит
		time.Sleep(500 * time.Millisecond)
		ln, err = net.Listen("tcp", uiAddr)
	}
	if err != nil { // уже запущено — просто открываем окно
		if !afterUpdate {
			client := &http.Client{Timeout: 3 * time.Second}
			req, _ := http.NewRequest("POST", "http://"+uiAddr+"/api/window/show", nil)
			req.Header.Set("X-Lite404", "1")
			if resp, e := client.Do(req); e == nil {
				resp.Body.Close()
			}
		}
		return
	}
	cleanupUpdate()
	a := &App{s: loadSettings(), conn: -1, phase: "off", wdtt: &Wdtt{}}
	a.wdtt.logging = a.s.JournalReserve
	a.loadCached()
	killStaleClient()
	if a.s.ProxyActive || ourProxyActive() { // прошлый запуск завершился аварийно — прокси на нас висит без Xray
		setSystemProxy(false, false)
		a.s.ProxyActive = false
		a.s.save()
	}
	if a.s.Token != "" {
		go func() {
			if err := a.refresh(); err != nil {
				log.Printf("refresh: %v", err)
			}
			if a.s.PingOnOpen {
				a.pingAll()
			}
			boot := len(os.Args) > 1 && os.Args[1] == "--autostart"
			if a.s.ConnectOnOpen || boot && a.s.ConnectOnBoot {
				if a.s.LastMode == "wdtt" {
					a.connectWdtt(a.s.WdttServer)
				} else {
					a.connectXray(a.s.Server)
				}
			}
		}()
	}
	go a.tick()
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() { <-sig; a.quit() }()

	// Loopback is a control endpoint, never the application's user interface.
	handler := a.handler()
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/api/") {
			http.NotFound(w, r)
			return
		}
		handler.ServeHTTP(w, r)
	}), ReadHeaderTimeout: 10 * time.Second}
	if os.Getenv("LITE404_NO_BROWSER") != "" {
		log.Fatal(srv.Serve(ln))
		return
	}
	go func() {
		if err := srv.Serve(ln); err != nil && err != http.ErrServerClosed {
			log.Printf("control API: %v", err)
			a.quit()
		}
	}()
	defer srv.Close()
	hidden := len(os.Args) > 1 && os.Args[1] == "--autostart"
	if err := a.runNativeUI(hidden); err != nil {
		log.Printf("native window: %v", err)
		a.disconnect()
	}
}
