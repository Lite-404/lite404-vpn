package main

// Резервный VPN (WDTT) на компьютере — так же, как в Android-приложении: стоковый клиент WDTT
// (core/wdtt-client, собран под систему и встроен в программу) запускается отдельным процессом,
// поднимает потоки через TURN-серверы звонков VK и выдаёт WireGuard-конфиг. WireGuard работает
// внутри Xray (без драйверов и прав администратора), дальше — тот же локальный/системный прокси.
// Капчу VK решает сам клиент (Go-решатель); окна WebView, как на Android, на компьютере нет —
// запросы WebView отклоняются сразу, и клиент повторяет своим решателем.

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
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
	wdttWorkers = 30
	wdttPort    = 9000
)

type LogLine struct {
	Time  int64  `json:"t"`
	Text  string `json:"text"`
	Error bool   `json:"error"`
}

type Wdtt struct {
	mu         sync.Mutex
	cmd        *exec.Cmd
	stdin      io.WriteCloser
	running    bool // туннель включён пользователем (в том числе пока клиент перезапускается)
	active     int  // активных потоков
	stats      string
	logs       []LogLine
	crashes    []time.Time
	server     WdttServer
	hash       string
	startedAt  time.Time
	lastAct    time.Time
	authFails  int
	workers    int
	logging    bool
	exiting    chan struct{} // закрывается, когда остановленный процесс клиента завершился
	done       chan struct{}
	watchStop  chan struct{}
	generation uint64
	switching  *wdttSwitch

	onConfig func(conf string) // клиент выдал WireGuard-конфиг
	onFatal  func(reason string)
}

type wdttSwitch struct {
	id     string
	server WdttServer
	apply  func(string) error
	done   chan error
}

func (w *Wdtt) canSwitch(hash string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.running && w.cmd != nil && w.active > 0 && w.hash == hash && w.switching == nil
}
func (w *Wdtt) Switch(server WdttServer, apply func(string) error) error {
	w.mu.Lock()
	if w.switching != nil || w.cmd == nil || w.stdin == nil || !w.running {
		w.mu.Unlock()
		return errors.New("резервный VPN ещё не готов к переключению")
	}
	if w.server.Host == server.Host && w.server.Port == server.Port && w.server.Password == server.Password {
		w.mu.Unlock()
		return nil
	}
	request := &wdttSwitch{id: strconv.FormatInt(time.Now().UnixNano(), 10), server: server, apply: apply, done: make(chan error, 1)}
	w.switching = request
	payload, _ := json.Marshal(map[string]any{"id": request.id, "peer": net.JoinHostPort(server.Host, strconv.Itoa(server.Port)), "password": server.Password})
	_, err := fmt.Fprintln(w.stdin, "SWITCH|"+string(payload))
	w.mu.Unlock()
	if err == nil {
		select {
		case err = <-request.done:
		case <-time.After(65 * time.Second):
			err = errors.New("превышено время переключения сервера")
		}
	}
	w.mu.Lock()
	if w.switching == request {
		if w.stdin != nil {
			ack, _ := json.Marshal(map[string]any{"id": request.id, "success": false})
			fmt.Fprintln(w.stdin, "SWITCH_ACK|"+string(ack))
		}
		w.switching = nil
	}
	w.mu.Unlock()
	return err
}

var tsPrefix = regexp.MustCompile(`^\d{4}/\d{2}/\d{2}\s\d{2}:\d{2}:\d{2}(\.\d+)?\s`)
var activeRe = regexp.MustCompile(`Активных:\s*(\d+)`)

func (w *Wdtt) log(text string, isErr bool) {
	if !w.logging {
		return
	}
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

func (w *Wdtt) Start(server WdttServer, hash string, workers int, onConfig func(string), onFatal func(string)) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.server, w.hash = server, hash
	w.generation++
	w.onConfig, w.onFatal = onConfig, onFatal
	w.workers = min(max(workers, 1), 30)
	w.logs, w.crashes, w.active, w.stats = nil, nil, 0, "Ожидание данных..."
	w.running = true
	w.log(fmt.Sprintf("Потоков: %d", w.workers), false)
	return w.spawnLocked()
}

func (w *Wdtt) spawnLocked() error {
	if w.exiting != nil { // прошлый процесс ещё держит порт 9000
		exiting, generation := w.exiting, w.generation
		// The log reader must acquire w.mu before it can reach its sole Wait.
		// Waiting while holding the mutex delayed every reconnect by 2.5s.
		w.mu.Unlock()
		select {
		case <-exiting:
		case <-time.After(2500 * time.Millisecond):
		}
		w.mu.Lock()
		if w.generation != generation || !w.running || w.cmd != nil {
			return nil
		}
		w.exiting = nil
	}
	bin, err := wdttBinary()
	if err != nil {
		w.running = false
		return err
	}
	killStaleClient()
	cmd := exec.Command(bin,
		"-peer", fmt.Sprintf("%s:%d", w.server.Host, w.server.Port),
		"-vk", w.hash,
		"-n", strconv.Itoa(w.workers),
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
	cmd.Env = append(os.Environ(), "WDTT_EVENTS=1", "WDTT_TURN_CACHE="+filepath.Join(dataDir(), "wdtt-turn-cache.json"), "WDTT_NETWORK_HANDLE="+physicalNetworkKey())
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
	w.done = make(chan struct{})
	w.watchStop = make(chan struct{})
	w.startedAt, w.lastAct, w.authFails = time.Now(), time.Time{}, 0
	go w.read(cmd, out, w.done, w.onConfig)
	go w.watchdog(cmd, w.watchStop)
	return nil
}

func (w *Wdtt) read(cmd *exec.Cmd, out io.Reader, done chan struct{}, onConfig func(string)) {
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
		case strings.HasPrefix(line, "__WDTT_EVENT__|"):
			w.handleEvent(cmd, line, onConfig)
			continue
		case strings.Contains(raw, "╔") && strings.Contains(raw, "WireGuard"):
			collecting = true
			conf.Reset()
			continue
		case collecting && strings.Contains(raw, "╚"):
			collecting = false
			if onConfig != nil {
				go onConfig(conf.String())
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
			if w.cmd == cmd && w.stdin != nil {
				io.WriteString(w.stdin, "CAPTCHA_RESULT|error:webview unavailable on desktop\n")
			}
			w.log("[КАПЧА] Решение капчи встроенным решателем...", false)
			w.mu.Unlock()
			continue
		case strings.HasPrefix(line, "__WDTT_EVENT__"), line == "":
			continue
		}
		w.handleLine(cmd, line)
	}
	cmd.Wait()
	close(done) // one owner of Wait; signal before taking w.mu (spawn waits under it)
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.cmd != cmd {
		return
	}
	if w.watchStop != nil {
		close(w.watchStop)
		w.watchStop = nil
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
	generation := w.generation
	go func() {
		time.Sleep(2 * time.Second)
		w.mu.Lock()
		defer w.mu.Unlock()
		if w.running && w.cmd == nil && w.generation == generation {
			w.spawnLocked()
		}
	}()
}

func (w *Wdtt) handleLine(cmd *exec.Cmd, line string) {
	lower := strings.ToLower(line)
	isErr := strings.Contains(line, "Ошибка") || strings.Contains(lower, "error") || strings.Contains(line, "FAIL") ||
		strings.Contains(lower, "timeout") || strings.Contains(lower, "refused") || strings.Contains(line, "FATAL_AUTH")
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.cmd != cmd {
		return
	}
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
func (w *Wdtt) watchdog(cmd *exec.Cmd, stop <-chan struct{}) {
	select {
	case <-stop:
		return
	case <-time.After(10 * time.Second):
	}
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
		select {
		case <-stop:
			return
		case <-time.After(5 * time.Second):
		}
	}
}

func (w *Wdtt) restartLocked() {
	w.killLocked()
	generation := w.generation
	go func() {
		time.Sleep(2 * time.Second)
		w.mu.Lock()
		defer w.mu.Unlock()
		if w.running && w.cmd == nil && w.generation == generation {
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
	if request := w.switching; request != nil {
		request.done <- errors.New("транспорт перезапущен или отключён")
		w.switching = nil
	}
	cmd := w.cmd
	if w.watchStop != nil {
		close(w.watchStop)
		w.watchStop = nil
	}
	w.cmd, w.active = nil, 0
	if w.stdin != nil {
		io.WriteString(w.stdin, "STOP\n")
		w.stdin.Close()
		w.stdin = nil
	}
	if cmd != nil && cmd.Process != nil {
		done := w.done
		w.exiting = done
		go func() {
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
	w.generation++
	w.running = false
	w.killLocked()
}

func (w *Wdtt) handleEvent(cmd *exec.Cmd, line string, onConfig func(string)) {
	parts := strings.SplitN(line, "|", 3)
	if len(parts) != 3 {
		return
	}
	var event struct {
		Active  int    `json:"active"`
		Up      int64  `json:"bytes_up"`
		Down    int64  `json:"bytes_down"`
		Config  string `json:"config"`
		Message string `json:"message"`
		Fatal   bool   `json:"fatal"`
		ID      string `json:"id"`
	}
	if parts[2] != "" && json.Unmarshal([]byte(parts[2]), &event) != nil {
		return
	}
	w.mu.Lock()
	if w.cmd != cmd {
		w.mu.Unlock()
		return
	}
	switch parts[1] {
	case "SWITCH_CONFIG":
		if request := w.switching; request != nil && request.id == event.ID {
			go func() {
				err := request.apply(event.Config)
				w.mu.Lock()
				defer w.mu.Unlock()
				if w.cmd == cmd && w.switching == request && w.stdin != nil {
					ack, _ := json.Marshal(map[string]any{"id": request.id, "success": err == nil})
					fmt.Fprintln(w.stdin, "SWITCH_ACK|"+string(ack))
				}
			}()
		}
	case "SWITCHED", "SWITCH_FAILED":
		if request := w.switching; request != nil && request.id == event.ID {
			var err error
			if parts[1] == "SWITCHED" {
				w.server = request.server
			} else {
				err = errors.New(event.Message)
				w.log(event.Message, true)
			}
			w.switching = nil
			request.done <- err
		}
	case "STATS":
		w.active = event.Active
		w.stats = fmt.Sprintf("Активных: %d | Трафик: %.2f МБ", event.Active, float64(event.Up+event.Down)/(1024*1024))
		if event.Active > 0 {
			w.lastAct = time.Now()
			w.authFails = 0
		}
	case "CONFIG":
		if event.Config != "" && onConfig != nil {
			go onConfig(event.Config)
		}
	case "ERROR":
		w.log(event.Message, true)
		if event.Fatal {
			w.stopLocked()
			if w.onFatal != nil {
				go w.onFatal(event.Message)
			}
		}
	}
	w.mu.Unlock()
}

func (w *Wdtt) Stop() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.stopLocked()
}

type WdttState struct {
	Switching bool      `json:"switching"`
	Running   bool      `json:"running"`
	Active    int       `json:"active"`
	Stats     string    `json:"stats"`
	Logs      []LogLine `json:"-"`
}

func (w *Wdtt) State() WdttState {
	w.mu.Lock()
	defer w.mu.Unlock()
	return WdttState{Running: w.running, Active: w.active, Stats: w.stats, Switching: w.switching != nil}
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
