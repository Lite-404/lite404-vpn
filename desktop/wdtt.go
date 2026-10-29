package main

// Резервный VPN (WDTT) на компьютере — так же, как в Android-приложении: стоковый клиент WDTT
// (core/wdtt-client, собран под систему и встроен в программу) запускается отдельным процессом,
// поднимает потоки через TURN-серверы звонков VK и выдаёт WireGuard-конфиг. WireGuard работает
// внутри Xray (без драйверов и прав администратора), дальше — тот же локальный/системный прокси.
// Капчу VK решает сам клиент (Go-решатель); окна WebView, как на Android, на компьютере нет —
// запросы WebView отклоняются сразу, и клиент повторяет своим решателем.

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	wdttWorkers = 27
	wdttPort    = 9000
)

type LogLine struct {
	Time  int64  `json:"t"`
	Text  string `json:"text"`
	Error bool   `json:"error"`
}

type Wdtt struct {
	mu        sync.Mutex
	cmd       *exec.Cmd
	stdin     io.WriteCloser
	running   bool // туннель включён пользователем (в том числе пока клиент перезапускается)
	active    int  // активных потоков
	stats     string
	logs      []LogLine
	crashes   []time.Time
	server    WdttServer
	hash      string
	startedAt time.Time
	lastAct   time.Time
	authFails int
	exiting   chan struct{} // закрывается, когда остановленный процесс клиента завершился

	onConfig func(conf string) // клиент выдал WireGuard-конфиг
	onFatal  func(reason string)
}

var tsPrefix = regexp.MustCompile(`^\d{4}/\d{2}/\d{2}\s\d{2}:\d{2}:\d{2}(\.\d+)?\s`)
var activeRe = regexp.MustCompile(`Активных:\s*(\d+)`)

func (w *Wdtt) log(text string, isErr bool) {
	w.logs = append(w.logs, LogLine{time.Now().UnixMilli(), text, isErr})
	if len(w.logs) > 400 {
		w.logs = w.logs[len(w.logs)-400:]
	}
}

// binary — клиент WDTT во временной папке программы (распаковывается при первом запуске версии).
func wdttBinary() (string, error) {
	if len(wdttClient) == 0 {
		return "", errors.New("резервный VPN для этой системы не собран")
	}
	name := "wdtt-client-" + version
	if strings.HasSuffix(wdttClientName, ".exe") {
		name += ".exe"
	}
	dir := filepath.Join(dataDir(), "bin")
	os.MkdirAll(dir, 0o700)
	path := filepath.Join(dir, name)
	if st, err := os.Stat(path); err == nil && st.Size() == int64(len(wdttClient)) {
		return path, nil
	}
	old, _ := filepath.Glob(filepath.Join(dir, "wdtt-client-*"))
	for _, f := range old {
		os.Remove(f)
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, wdttClient, 0o755); err != nil {
		return "", err
	}
	return path, os.Rename(tmp, path)
}

func (w *Wdtt) Start(server WdttServer, hash string) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.server, w.hash = server, hash
	w.logs, w.crashes, w.active, w.stats = nil, nil, 0, "Ожидание данных..."
	w.running = true
	w.log(fmt.Sprintf("[Основной] Хешей=1, Потоков=%d", wdttWorkers), false)
	return w.spawnLocked()
}

func (w *Wdtt) spawnLocked() error {
	bin, err := wdttBinary()
	if err != nil {
		w.running = false
		return err
	}
	if w.exiting != nil { // прошлый процесс ещё держит порт 9000
		select {
		case <-w.exiting:
		case <-time.After(2500 * time.Millisecond):
		}
		w.exiting = nil
	}
	killStaleClient()
	cmd := exec.Command(bin,
		"-peer", fmt.Sprintf("%s:%d", w.server.Host, w.server.Port),
		"-vk", w.hash,
		"-n", strconv.Itoa(wdttWorkers),
		"-listen", fmt.Sprintf("127.0.0.1:%d", wdttPort),
		"-fingerprint", "firefox",
		"-client-ids", "8202606,6287487",
		"-obfs", "audio",
		"-vk-auth-mode", "vkcalls",
		"-device-id", hwid(),
		"-password", w.server.Password,
		"-captcha-mode", "auto",
	)
	cmd.Dir = filepath.Join(dataDir(), "bin")
	hideWindow(cmd)
	stdin, _ := cmd.StdinPipe()
	out, _ := cmd.StdoutPipe()
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		w.running = false
		return fmt.Errorf("клиент резервного VPN не запустился: %v", err)
	}
	os.WriteFile(filepath.Join(dataDir(), "wdtt.pid"), []byte(strconv.Itoa(cmd.Process.Pid)), 0o600)
	w.cmd, w.stdin = cmd, stdin
	w.startedAt, w.lastAct, w.authFails = time.Now(), time.Time{}, 0
	go w.read(cmd, out)
	go w.watchdog(cmd)
	return nil
}

func (w *Wdtt) read(cmd *exec.Cmd, out io.Reader) {
	sc := bufio.NewScanner(out)
	sc.Buffer(make([]byte, 64<<10), 1<<20)
	var conf strings.Builder
	collecting := false
	for sc.Scan() {
		raw := sc.Text()
		line := strings.TrimSpace(tsPrefix.ReplaceAllString(raw, ""))
		w.mu.Lock()
		mine := w.cmd == cmd
		w.mu.Unlock()
		if !mine {
			continue
		}
		switch {
		case strings.Contains(raw, "╔") && strings.Contains(raw, "WireGuard"):
			collecting = true
			conf.Reset()
			continue
		case collecting && strings.Contains(raw, "╚"):
			collecting = false
			if w.onConfig != nil {
				go w.onConfig(conf.String())
			}
			continue
		case collecting:
			if c := strings.TrimSpace(strings.ReplaceAll(raw, "║", "")); c != "" {
				conf.WriteString(c + "\n")
			}
			continue
		case strings.HasPrefix(line, "CAPTCHA_SOLVE|"):
			// окна WebView нет — отвечаем сразу, клиент продолжит своим решателем
			w.mu.Lock()
			if w.stdin != nil {
				io.WriteString(w.stdin, "CAPTCHA_RESULT|error:webview unavailable on desktop\n")
			}
			w.log("[КАПЧА] Решение капчи встроенным решателем...", false)
			w.mu.Unlock()
			continue
		case strings.HasPrefix(line, "__WDTT_EVENT__"), line == "":
			continue
		}
		w.handleLine(line)
	}
	cmd.Wait()
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.cmd != cmd {
		return
	}
	w.cmd, w.stdin, w.active = nil, nil, 0
	if !w.running {
		return
	}
	// клиент завершился сам — поднимаем снова, но не чаще 5 раз в минуту
	now := time.Now()
	recent := w.crashes[:0]
	for _, t := range w.crashes {
		if now.Sub(t) < time.Minute {
			recent = append(recent, t)
		}
	}
	w.crashes = recent
	if len(w.crashes) >= 5 {
		w.log("[СТОП] Клиент WDTT падает слишком часто. Отключение.", true)
		w.running = false
		if w.onFatal != nil {
			go w.onFatal("Резервный VPN остановился. Подробности — в журнале (Настройки)")
		}
		return
	}
	w.crashes = append(w.crashes, now)
	w.log("⚠ Клиент WDTT завершился — перезапуск...", true)
	go func() {
		time.Sleep(2 * time.Second)
		w.mu.Lock()
		defer w.mu.Unlock()
		if w.running && w.cmd == nil {
			w.spawnLocked()
		}
	}()
}

func (w *Wdtt) handleLine(line string) {
	lower := strings.ToLower(line)
	isErr := strings.Contains(line, "Ошибка") || strings.Contains(lower, "error") || strings.Contains(line, "FAIL") ||
		strings.Contains(lower, "timeout") || strings.Contains(lower, "refused") || strings.Contains(line, "FATAL_AUTH")
	w.mu.Lock()
	defer w.mu.Unlock()
	switch {
	case strings.Contains(line, "FATAL_AUTH") && !strings.Contains(line, "DTLS timeout") && !strings.Contains(line, "WRAP_AUTH_TIMEOUT"):
		reason := "Ошибка авторизации"
		switch {
		case strings.Contains(line, "неверный пароль"):
			reason = "Неверный пароль подключения"
		case strings.Contains(line, "истёк"):
			reason = "Срок действия пароля истёк"
		case strings.Contains(line, "другому устройству"):
			reason = "Пароль привязан к другому устройству"
		case strings.Contains(line, "device_limit"):
			reason = "Достигнут лимит устройств. Удалите ненужное устройство в боте"
		}
		w.log("[СТОП] 🔒 "+reason+". Воркеры остановлены.", true)
		w.stopLocked()
		if w.onFatal != nil {
			go w.onFatal(reason)
		}
		return
	case strings.Contains(line, "WRAP_AUTH_TIMEOUT") || strings.Contains(line, "FATAL_AUTH"):
		if w.active == 0 {
			w.authFails++
		}
		w.log("[WRAP] Handshake не подтвердился, повторяем", true)
		return
	case strings.Contains(line, "[СТАТИСТИКА]"):
		msg := strings.TrimSpace(line[strings.Index(line, "[СТАТИСТИКА]")+len("[СТАТИСТИКА]"):])
		w.stats = msg
		if m := activeRe.FindStringSubmatch(msg); m != nil {
			w.active, _ = strconv.Atoi(m[1])
			if w.active > 0 {
				w.lastAct, w.authFails = time.Now(), 0
			}
		}
		return
	case strings.Contains(line, "Relay:"):
		w.log("[DTLS] Рукопожатие (Handshake)...", false)
	case strings.Contains(line, "DTLS ОК"):
		w.log("[DTLS] Соединение установлено ✓", false)
	case strings.Contains(line, "Активна ✓"):
		w.log("[READY] Туннель готов к работе ✓", false)
	case strings.Contains(line, "[КАПЧА]"), strings.Contains(line, "[TURN]"), strings.Contains(line, "[WRAP]"), isErr:
		if strings.Contains(lower, "lookup login.vk.ru") {
			line = "[СЕТЬ] DNS до VK недоступен: login.vk.ru"
		}
		w.log(line, isErr)
	}
}

// watchdog — как на Android: процесс без потоков 90 с — перезапуск; 3 неподтверждённых handshake
// за первые 30 с без единого активного потока — неверный пароль.
func (w *Wdtt) watchdog(cmd *exec.Cmd) {
	time.Sleep(10 * time.Second)
	var zeroSince time.Time
	for {
		w.mu.Lock()
		if w.cmd != cmd || !w.running {
			w.mu.Unlock()
			return
		}
		if w.active > 0 {
			zeroSince = time.Time{}
		} else if zeroSince.IsZero() {
			zeroSince = time.Now()
		} else if w.authFails >= 3 && time.Since(w.startedAt) > 30*time.Second && w.lastAct.IsZero() {
			w.log("[СТОП] 🔒 Неверный пароль подключения или несовместимый WRAP. Воркеры остановлены.", true)
			w.stopLocked()
			w.mu.Unlock()
			if w.onFatal != nil {
				w.onFatal("Неверный пароль подключения")
			}
			return
		} else if time.Since(zeroSince) > 90*time.Second {
			w.log("⚠ Зомби-процесс (0 воркеров 90с). Перезапуск...", true)
			w.restartLocked()
			w.mu.Unlock()
			return
		}
		w.mu.Unlock()
		time.Sleep(5 * time.Second)
	}
}

func (w *Wdtt) restartLocked() {
	w.killLocked()
	go func() {
		time.Sleep(2 * time.Second)
		w.mu.Lock()
		defer w.mu.Unlock()
		if w.running && w.cmd == nil {
			w.spawnLocked()
		}
	}()
}

// RestartTransport — смена сети: переподключить потоки, не выключая туннель.
func (w *Wdtt) RestartTransport() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.running && w.cmd != nil {
		w.log("[СЕТЬ] Перезапуск транспорта из-за смены сети...", false)
		w.restartLocked()
	}
}

func (w *Wdtt) killLocked() {
	cmd := w.cmd
	w.cmd, w.active = nil, 0
	if w.stdin != nil {
		io.WriteString(w.stdin, "STOP\n")
		w.stdin.Close()
		w.stdin = nil
	}
	if cmd != nil && cmd.Process != nil {
		done := make(chan struct{})
		w.exiting = done
		go func() {
			go func() { cmd.Process.Wait(); close(done) }()
			select {
			case <-done:
			case <-time.After(1500 * time.Millisecond):
				cmd.Process.Kill()
			}
		}()
	}
	os.Remove(filepath.Join(dataDir(), "wdtt.pid"))
}

func (w *Wdtt) stopLocked() {
	w.running = false
	w.killLocked()
}

func (w *Wdtt) Stop() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.stopLocked()
}

type WdttState struct {
	Running bool      `json:"running"`
	Active  int       `json:"active"`
	Stats   string    `json:"stats"`
	Logs    []LogLine `json:"-"`
}

func (w *Wdtt) State() WdttState {
	w.mu.Lock()
	defer w.mu.Unlock()
	return WdttState{Running: w.running, Active: w.active, Stats: w.stats}
}

func (w *Wdtt) Logs() []LogLine {
	w.mu.Lock()
	defer w.mu.Unlock()
	return append([]LogLine(nil), w.logs...)
}

func (w *Wdtt) ClearLogs() {
	w.mu.Lock()
	w.logs = nil
	w.mu.Unlock()
}

// killStaleClient — клиент, оставшийся от аварийно завершённой программы (на Windows он сам не выходит).
func killStaleClient() {
	data, err := os.ReadFile(filepath.Join(dataDir(), "wdtt.pid"))
	if err != nil {
		return
	}
	if pid, err := strconv.Atoi(strings.TrimSpace(string(data))); err == nil && pid > 0 {
		if p, err := os.FindProcess(pid); err == nil {
			killIfWdtt(p, pid)
		}
	}
	os.Remove(filepath.Join(dataDir(), "wdtt.pid"))
}
