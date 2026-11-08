// Package vpncore — ядро sing-box-extended для Android (gomobile bind): VPN по готовому TUN,
// перевод конфигов подписки (формат Xray) и замер задержки.
package vpncore

import (
	"errors"
	"strings"
	"sync"
	"time"

	"lite404/boxcore"
)

// Platform — реализует приложение (Kotlin).
type Platform interface {
	// Protect — VpnService.protect(fd): сокет ядра идёт мимо VPN.
	Protect(fd int32) bool
}

var (
	mu       sync.Mutex
	current  *boxcore.Instance
	platform *boxcore.MobilePlatform
	// последняя известная основная сеть: передаётся новому ядру до запуска
	ifName  string
	ifIndex int = -1
)

// Version — версия ядра.
func Version() string { return "sing-box " + boxcore.Version() }

// Convert — конфиг сервера (формат Xray) → конфиг sing-box. tunAddress — адреса TUN через запятую.
// logFile — журнал ядра ("" — без журнала), logLevel — debug|info|warn|error ("" — warn).
func Convert(xrayConfig, geoDir, tunAddress, logFile, logLevel string) (string, error) {
	var addrs []string
	for _, a := range strings.Split(tunAddress, ",") {
		if a = strings.TrimSpace(a); a != "" {
			addrs = append(addrs, a)
		}
	}
	o := boxcore.Options{GeoDir: geoDir, TunAddress: addrs, LogFile: logFile, LogLevel: logLevel}
	if logFile != "" && logLevel == "" {
		o.LogLevel = "warn"
	}
	out, err := boxcore.Convert([]byte(xrayConfig), o)
	return string(out), err
}

// Start — запустить VPN: конфиг sing-box и дескриптор TUN. Запущенное ранее ядро останавливается.
func Start(config string, tunFd int32, p Platform) error {
	if p == nil {
		return errors.New("нет платформы")
	}
	Stop()
	plat := &boxcore.MobilePlatform{TunFD: int(tunFd), Protect: func(fd int) bool { return p.Protect(int32(fd)) }}
	mu.Lock()
	name, index := ifName, ifIndex
	mu.Unlock()
	plat.SetDefaultInterface(name, index)
	inst, err := boxcore.Start([]byte(config), plat)
	if err != nil {
		return err
	}
	mu.Lock()
	current, platform = inst, plat
	mu.Unlock()
	return nil
}

// Stop — остановить VPN (если запущен).
func Stop() {
	mu.Lock()
	inst := current
	current, platform = nil, nil
	mu.Unlock()
	if inst != nil {
		inst.Close()
	}
}

// Running — ядро запущено.
func Running() bool {
	mu.Lock()
	defer mu.Unlock()
	return current != nil
}

// SetDefaultInterface — основная сеть телефона сменилась (index < 0 — сети нет).
func SetDefaultInterface(name string, index int32) {
	mu.Lock()
	ifName, ifIndex = name, int(index)
	p := platform
	mu.Unlock()
	if p != nil {
		p.SetDefaultInterface(name, int(index))
	}
}

// ResetNetwork — закрыть старые соединения (после смены сети).
func ResetNetwork() {
	mu.Lock()
	inst := current
	mu.Unlock()
	if inst != nil {
		inst.ResetNetwork()
	}
}

// URLTest — задержка через запущенный VPN, мс.
func URLTest(url string, timeoutMs int32) (int64, error) {
	mu.Lock()
	inst := current
	mu.Unlock()
	if inst == nil {
		return -1, errors.New("VPN не запущен")
	}
	return inst.URLTest("", url, time.Duration(timeoutMs)*time.Millisecond)
}

// Ping — задержка до сервера без подключения (временное ядро только с выходом к серверу), мс; -1 — не ответил.
func Ping(xrayConfig, url string, timeoutMs int32) int64 {
	ms, err := boxcore.Ping([]byte(xrayConfig), url, time.Duration(timeoutMs)*time.Millisecond, &boxcore.MobilePlatform{TunFD: -1})
	if err != nil {
		return -1
	}
	return ms
}
