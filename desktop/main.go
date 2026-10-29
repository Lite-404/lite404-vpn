// Lite404 VPN для компьютера: Xray внутри, локальный прокси (SOCKS 10808 / HTTP 10809) и системный прокси,
// интерфейс — страница на 127.0.0.1:47404 в браузере. Данные — по тому же токену, что и в Android-приложении.
package main

import (
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sync"
	"syscall"
	"time"

	"github.com/xtls/xray-core/core"
)

var version = "dev"

const uiAddr = "127.0.0.1:47404"

//go:embed web/index.html
var webFS embed.FS

type App struct {
	mu       sync.Mutex
	s        *Settings
	cfg      *AppConfig
	inst     *core.Instance
	conn     int    // индекс подключённого сервера, -1 — нет
	status   string // off | connecting | on
	notice   string
	pings    map[int]int64
	pinging  bool
	since    time.Time
	proxyErr string
}

func (a *App) loadCached() {
	if len(a.s.Config) > 0 {
		var c AppConfig
		if json.Unmarshal(a.s.Config, &c) == nil {
			a.cfg = &c
		}
	}
}

func (a *App) profile() obj {
	if a.cfg == nil || a.s.Routing == "off" {
		return nil
	}
	for _, name := range []string{a.s.Routing, "happ", "incy"} {
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
	a.mu.Unlock()
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

func (a *App) connect(i int) error {
	a.stopXray()
	a.mu.Lock()
	if a.cfg == nil || i < 0 || i >= len(a.cfg.Xray) {
		a.mu.Unlock()
		return errors.New("нет такого сервера — обновите список")
	}
	if msg := ErrorText(a.cfg.Error); msg != "" {
		a.mu.Unlock()
		return errors.New(msg)
	}
	server := a.cfg.Xray[i]
	profile := a.profile()
	a.status, a.notice = "connecting", ""
	a.mu.Unlock()

	notice := ""
	if profile != nil {
		if err := ensureGeo(profile); err != nil {
			profile = nil
			notice = "Не удалось скачать правила маршрутизации — сейчас всё идёт через VPN"
		}
	}
	cfg, err := BuildConfig(server, profile)
	var inst *core.Instance
	if err == nil {
		inst, err = startInstance(cfg)
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if err != nil {
		a.status = "off"
		return err
	}
	a.inst, a.conn, a.status, a.notice, a.since = inst, i, "on", notice, time.Now()
	a.s.Server = i
	a.proxyErr = ""
	if a.s.SystemProxy {
		if perr := setSystemProxy(true); perr != nil {
			a.proxyErr = "Системный прокси не включился — укажите вручную HTTP 127.0.0.1:10809"
		} else {
			a.s.ProxyActive = true
		}
	}
	a.s.save()
	return nil
}

func (a *App) stopXray() {
	a.mu.Lock()
	inst := a.inst
	a.inst, a.conn, a.status = nil, -1, "off"
	if a.s.ProxyActive {
		setSystemProxy(false)
		a.s.ProxyActive = false
		a.s.save()
	}
	a.mu.Unlock()
	if inst != nil {
		inst.Close()
	}
}

// check — проверка соединения после подключения: запрос через VPN.
func (a *App) check() (int64, error) {
	a.mu.Lock()
	inst, url := a.inst, a.s.PingURL
	a.mu.Unlock()
	if inst == nil {
		return -1, errors.New("не подключено")
	}
	return measure(inst, url)
}

func (a *App) pingAll() {
	a.mu.Lock()
	if a.pinging || a.cfg == nil {
		a.mu.Unlock()
		return
	}
	servers, url := a.cfg.Xray, a.s.PingURL
	a.pinging, a.pings = true, map[int]int64{}
	a.mu.Unlock()
	var wg sync.WaitGroup
	for i, s := range servers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ms := Ping(s, url)
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

func (a *App) state() obj {
	a.mu.Lock()
	defer a.mu.Unlock()
	st := obj{
		"version": version, "status": a.status, "connected": a.conn, "notice": a.notice,
		"hasToken": a.s.Token != "", "server": a.s.Server, "routing": a.s.Routing,
		"systemProxy": a.s.SystemProxy, "pingUrl": a.s.PingURL, "pinging": a.pinging,
		"pings": a.pings, "proxyHint": proxyHint, "proxyError": a.proxyErr,
		"socks": fmt.Sprintf("127.0.0.1:%d", socksPort), "http": fmt.Sprintf("127.0.0.1:%d", httpPort),
	}
	if a.status == "on" {
		st["since"] = a.since.Unix()
	}
	if a.cfg != nil {
		names := []string{}
		for i, s := range a.cfg.Xray {
			names = append(names, ServerName(s, i))
		}
		st["servers"] = names
		st["user"] = a.cfg.User
		st["error"] = ErrorText(a.cfg.Error)
		profiles, norm := obj{}, map[string]string{}
		for name, raw := range a.cfg.Routing {
			var p obj
			if json.Unmarshal(raw, &p) == nil {
				profiles[name] = describe(p)
				n, _ := json.Marshal(p) // ключи по алфавиту — сравнение без учёта порядка
				norm[name] = string(n)
			}
		}
		st["profiles"] = profiles
		st["sameProfiles"] = norm["happ"] != "" && norm["happ"] == norm["incy"]
	}
	return st
}

// describe — «RoscomVPN Whitelist · напрямую: private, whitelist · …».
func describe(p obj) string {
	names := func(key string) string {
		out := ""
		for _, v := range list(p, key) {
			s := fmt.Sprint(v)
			if i := indexColon(s); i >= 0 {
				s = s[i+1:]
			}
			if s != "" {
				if out != "" {
					out += ", "
				}
				out += s
			}
		}
		return out
	}
	text := str(p, "Name", "Профиль из панели")
	if s := names("DirectSites"); s != "" {
		text += " · напрямую: " + s
	}
	if s := names("ProxySites"); s != "" {
		text += " · через VPN: " + s
	}
	if s := names("BlockSites"); s != "" {
		text += " · блокируются: " + s
	}
	return text
}

func indexColon(s string) int {
	for i := range s {
		if s[i] == ':' {
			return i
		}
	}
	return -1
}

func (a *App) handler() http.Handler {
	mux := http.NewServeMux()
	page, _ := webFS.ReadFile("web/index.html")
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(page)
	})
	reply := func(w http.ResponseWriter, err error, extra obj) {
		out := a.state()
		for k, v := range extra {
			out[k] = v
		}
		if err != nil {
			out["message"] = err.Error()
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(out)
	}
	var body = func(r *http.Request) obj {
		var m obj
		json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<20)).Decode(&m)
		if m == nil {
			m = obj{}
		}
		return m
	}
	mux.HandleFunc("GET /api/state", func(w http.ResponseWriter, r *http.Request) { reply(w, nil, nil) })
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
		go a.pingAll()
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/refresh", func(w http.ResponseWriter, r *http.Request) {
		err := a.refresh()
		if err == nil {
			go a.pingAll()
		}
		reply(w, err, nil)
	})
	mux.HandleFunc("POST /api/connect", func(w http.ResponseWriter, r *http.Request) {
		i, _ := body(r)["server"].(float64)
		if err := a.connect(int(i)); err != nil {
			reply(w, err, nil)
			return
		}
		ms, err := a.check()
		if err != nil {
			reply(w, nil, obj{"check": -1, "checkError": "Подключено, но интернет через VPN не отвечает — попробуйте другой сервер"})
			return
		}
		reply(w, nil, obj{"check": ms})
	})
	mux.HandleFunc("POST /api/disconnect", func(w http.ResponseWriter, r *http.Request) {
		a.stopXray()
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/check", func(w http.ResponseWriter, r *http.Request) {
		ms, err := a.check()
		if err != nil {
			reply(w, errors.New("Интернет через VPN не отвечает"), obj{"check": -1})
			return
		}
		reply(w, nil, obj{"check": ms})
	})
	mux.HandleFunc("POST /api/ping", func(w http.ResponseWriter, r *http.Request) {
		go a.pingAll()
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/settings", func(w http.ResponseWriter, r *http.Request) {
		m := body(r)
		a.mu.Lock()
		if v, ok := m["routing"].(string); ok && (v == "happ" || v == "incy" || v == "off") {
			a.s.Routing = v
		}
		if v, ok := m["pingUrl"].(string); ok && (len(v) > 8 && (v[:7] == "http://" || v[:8] == "https://")) {
			a.s.PingURL = v
		}
		if v, ok := m["systemProxy"].(bool); ok && v != a.s.SystemProxy {
			a.s.SystemProxy = v
			if a.status == "on" { // применяем сразу
				if v && setSystemProxy(true) == nil {
					a.s.ProxyActive = true
				} else if !v && a.s.ProxyActive {
					setSystemProxy(false)
					a.s.ProxyActive = false
				}
			}
		}
		a.s.save()
		a.mu.Unlock()
		reply(w, nil, nil)
	})
	mux.HandleFunc("POST /api/logout", func(w http.ResponseWriter, r *http.Request) {
		a.stopXray()
		a.mu.Lock()
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
	a.stopXray()
	os.Exit(0)
}

func main() {
	dir := dataDir()
	if f, err := os.OpenFile(filepath.Join(dir, "lite404.log"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600); err == nil {
		log.SetOutput(f)
		os.Stderr = f
	}
	os.Setenv("XRAY_LOCATION_ASSET", filepath.Join(dir, "geo"))

	ln, err := net.Listen("tcp", uiAddr)
	if err != nil { // уже запущено — просто открываем окно
		openBrowser("http://" + uiAddr + "/")
		return
	}
	a := &App{s: loadSettings(), conn: -1, status: "off"}
	a.loadCached()
	if a.s.ProxyActive || ourProxyActive() { // прошлый запуск завершился аварийно — прокси на нас висит без Xray
		setSystemProxy(false)
		a.s.ProxyActive = false
		a.s.save()
	}
	if a.s.Token != "" {
		go func() {
			if err := a.refresh(); err != nil {
				log.Printf("refresh: %v", err)
			}
			a.pingAll()
		}()
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() { <-sig; a.quit() }()

	if os.Getenv("LITE404_NO_BROWSER") == "" {
		openBrowser("http://" + uiAddr + "/")
	}
	srv := &http.Server{Handler: a.handler(), ReadHeaderTimeout: 10 * time.Second}
	log.Fatal(srv.Serve(ln))
}
