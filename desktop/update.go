package main

// Обновление программы из релизов GitHub. В каждом релизе лежит Lite404-VPN-update.json:
// версия, описание и для каждого файла — прямая ссылка, размер и SHA-256. Программа скачивает
// архив под свою систему, проверяет его, ставит новую версию на место текущей и перезапускается.

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

const updateManifestURL = "https://github.com/Lite-404/lite404-vpn/releases/latest/download/Lite404-VPN-update.json"

type UpdateFile struct {
	URL    string `json:"url"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

type UpdateManifest struct {
	Version string                `json:"version"`
	Notes   string                `json:"notes"`
	Files   map[string]UpdateFile `json:"files"`
}

// Updater — состояние обновления для интерфейса.
type Updater struct {
	mu       sync.Mutex
	found    *UpdateManifest
	phase    string // "" | download | install | restart | error
	progress float64
	err      string
}

func (u *Updater) set(phase string, progress float64, err string) {
	u.mu.Lock()
	u.phase, u.progress, u.err = phase, progress, err
	u.mu.Unlock()
}

func (u *Updater) State() obj {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.phase == "" {
		return nil
	}
	return obj{"phase": u.phase, "progress": u.progress, "error": u.err}
}

// assetName — архив релиза для этой системы (имена совпадают с прямыми ссылками в боте).
func assetName() string {
	switch runtime.GOOS + "/" + runtime.GOARCH {
	case "windows/amd64":
		return "Lite404-VPN-windows-x64.zip"
	case "linux/amd64":
		return "Lite404-VPN-linux-x64.tar.gz"
	case "linux/arm64":
		return "Lite404-VPN-linux-arm64.tar.gz"
	case "darwin/arm64":
		return "Lite404-VPN-macos-apple-silicon.zip"
	case "darwin/amd64":
		return "Lite404-VPN-macos-intel.zip"
	}
	return ""
}

// binaryEntry — путь программы внутри архива (как его собирает pack).
func binaryEntry() string {
	switch runtime.GOOS {
	case "windows":
		return "Lite404 VPN/Lite404 VPN.exe"
	case "darwin":
		return "Lite404 VPN.app/Contents/MacOS/lite404"
	}
	return "lite404-vpn/lite404-vpn"
}

func parseVersion(s string) []int {
	parts := strings.Split(strings.TrimPrefix(strings.TrimSpace(s), "v"), ".")
	if len(parts) != 3 {
		return nil
	}
	out := make([]int, 3)
	for i, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 {
			return nil
		}
		out[i] = n
	}
	return out
}

// newerVersion — remote новее local. Сборки без номера версии (dev) не обновляются.
func newerVersion(remote, local string) bool {
	r, l := parseVersion(remote), parseVersion(local)
	if r == nil || l == nil {
		return false
	}
	for i := range r {
		if r[i] != l[i] {
			return r[i] > l[i]
		}
	}
	return false
}

// updateGet — запрос напрямую; если не вышло, а VPN включён — через свой HTTP-прокси.
func (a *App) updateGet(link string) (*http.Response, error) {
	try := func(proxy func(*http.Request) (*url.URL, error)) (*http.Response, error) {
		c := &http.Client{Timeout: 15 * time.Minute, Transport: &http.Transport{
			Proxy: proxy, TLSHandshakeTimeout: 20 * time.Second, ResponseHeaderTimeout: 30 * time.Second,
		}}
		resp, err := c.Get(link)
		if err == nil && resp.StatusCode != http.StatusOK {
			resp.Body.Close()
			err = fmt.Errorf("HTTP %d", resp.StatusCode)
		}
		return resp, err
	}
	resp, err := try(nil)
	if err == nil {
		return resp, nil
	}
	a.mu.Lock()
	on := a.inst != nil
	a.mu.Unlock()
	if !on {
		return nil, err
	}
	proxy, _ := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", httpPort))
	return try(http.ProxyURL(proxy))
}

// checkUpdate — nil, если обновления нет.
func (a *App) checkUpdate() (*UpdateManifest, error) {
	name := assetName()
	if name == "" {
		return nil, errors.New("Для этой системы обновление не выпускается")
	}
	link := updateManifestURL
	if v := os.Getenv("LITE404_UPDATE_URL"); v != "" { // для проверки на тестовом сервере
		link = v
	}
	resp, err := a.updateGet(link)
	if err != nil {
		return nil, fmt.Errorf("Нет связи с сервером: %s", netErrText(err))
	}
	defer resp.Body.Close()
	var m UpdateManifest
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&m); err != nil {
		return nil, errors.New("Ошибка сервера")
	}
	f, ok := m.Files[name]
	if !ok || f.URL == "" || f.Size <= 0 || len(f.SHA256) != 64 {
		return nil, nil // в релизе нет файла для этой системы
	}
	a.upd.mu.Lock()
	defer a.upd.mu.Unlock()
	if !newerVersion(m.Version, version) {
		a.upd.found = nil
		return nil, nil
	}
	a.upd.found = &m
	return &m, nil
}

func netErrText(err error) string {
	var ue *url.Error
	if errors.As(err, &ue) {
		return ue.Err.Error()
	}
	return err.Error()
}

// startUpdate — скачать, проверить, поставить и перезапустить (в фоне; ход виден в state).
func (a *App) startUpdate() error {
	a.upd.mu.Lock()
	m, busy := a.upd.found, a.upd.phase == "download" || a.upd.phase == "install" || a.upd.phase == "restart"
	a.upd.mu.Unlock()
	if busy {
		return nil
	}
	if m == nil {
		return errors.New("Сначала проверьте обновления")
	}
	a.upd.set("download", 0, "")
	go func() {
		if err := a.runUpdate(m); err != nil {
			log.Printf("update: %v", err)
			a.upd.set("error", 0, err.Error())
		}
	}()
	return nil
}

func (a *App) runUpdate(m *UpdateManifest) error {
	f := m.Files[assetName()]
	archive, err := a.downloadUpdate(f)
	if err != nil {
		return err
	}
	a.upd.set("install", 1, "")
	bin, plist, err := extractUpdate(assetName(), archive)
	if err != nil {
		return err
	}
	exe, err := os.Executable()
	if err == nil {
		exe, err = filepath.EvalSymlinks(exe)
	}
	if err != nil {
		return err
	}
	if err := replaceExecutable(exe, bin, m.Version); err != nil {
		return err
	}
	if plist != nil { // macOS: версия в свойствах приложения
		if p := filepath.Join(filepath.Dir(filepath.Dir(exe)), "Info.plist"); fileExists(p) {
			os.WriteFile(p, plist, 0o644)
		}
	}
	os.RemoveAll(filepath.Join(dataDir(), "update"))
	a.upd.set("restart", 1, "")
	log.Printf("update: %s → %s, перезапуск", version, m.Version)
	a.restart(exe)
	return nil
}

func (a *App) downloadUpdate(f UpdateFile) ([]byte, error) {
	resp, err := a.updateGet(f.URL)
	if err != nil {
		return nil, fmt.Errorf("Не удалось скачать обновление: %s", netErrText(err))
	}
	defer resp.Body.Close()
	h := sha256.New()
	var buf bytes.Buffer
	chunk := make([]byte, 64<<10)
	for {
		n, err := resp.Body.Read(chunk)
		if n > 0 {
			if int64(buf.Len()+n) > f.Size {
				return nil, errors.New("Файл обновления повреждён. Попробуйте ещё раз.")
			}
			buf.Write(chunk[:n])
			h.Write(chunk[:n])
			a.upd.set("download", float64(buf.Len())/float64(f.Size), "")
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("Не удалось скачать обновление: %s", netErrText(err))
		}
	}
	if int64(buf.Len()) != f.Size || !strings.EqualFold(hex.EncodeToString(h.Sum(nil)), f.SHA256) {
		return nil, errors.New("Файл обновления повреждён. Попробуйте ещё раз.")
	}
	return buf.Bytes(), nil
}

// extractUpdate — программа (и Info.plist для macOS) из архива релиза.
func extractUpdate(name string, archive []byte) (bin, plist []byte, err error) {
	const maxSize = 300 << 20
	want := binaryEntry()
	if strings.HasSuffix(name, ".zip") {
		zr, err := zip.NewReader(bytes.NewReader(archive), int64(len(archive)))
		if err != nil {
			return nil, nil, errors.New("Файл обновления повреждён. Попробуйте ещё раз.")
		}
		for _, f := range zr.File {
			if f.Name != want && f.Name != "Lite404 VPN.app/Contents/Info.plist" {
				continue
			}
			rc, err := f.Open()
			if err != nil {
				return nil, nil, err
			}
			data, err := io.ReadAll(io.LimitReader(rc, maxSize))
			rc.Close()
			if err != nil {
				return nil, nil, err
			}
			if f.Name == want {
				bin = data
			} else {
				plist = data
			}
		}
	} else {
		gz, err := gzip.NewReader(bytes.NewReader(archive))
		if err != nil {
			return nil, nil, errors.New("Файл обновления повреждён. Попробуйте ещё раз.")
		}
		tarR := tar.NewReader(gz)
		for {
			h, err := tarR.Next()
			if err == io.EOF {
				break
			}
			if err != nil {
				return nil, nil, errors.New("Файл обновления повреждён. Попробуйте ещё раз.")
			}
			if h.Name == want {
				if bin, err = io.ReadAll(io.LimitReader(tarR, maxSize)); err != nil {
					return nil, nil, err
				}
			}
		}
	}
	if len(bin) == 0 {
		return nil, nil, errors.New("Файл обновления повреждён. Попробуйте ещё раз.")
	}
	return bin, plist, nil
}

// replaceExecutable — новая программа рядом (.new), проверка запуском, затем замена.
// Windows не даёт перезаписать запущенный файл, но даёт переименовать: старый уходит в .old
// и удаляется при следующем запуске.
func replaceExecutable(exe string, bin []byte, want string) error {
	next := exe + ".new"
	os.Remove(next)
	if err := os.WriteFile(next, bin, 0o755); err != nil {
		return errors.New("Нет прав на запись в папку программы. Скачайте новую версию в боте.")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, next, "--version").Output()
	if err != nil || strings.TrimSpace(string(out)) != want {
		os.Remove(next)
		return errors.New("Новая версия не запускается на этом компьютере. Скачайте её в боте.")
	}
	if runtime.GOOS == "windows" {
		old := exe + ".old"
		os.Remove(old)
		if err := os.Rename(exe, old); err != nil {
			os.Remove(next)
			return errors.New("Нет прав на запись в папку программы. Скачайте новую версию в боте.")
		}
		if err := os.Rename(next, exe); err != nil {
			os.Rename(old, exe)
			os.Remove(next)
			return errors.New("Нет прав на запись в папку программы. Скачайте новую версию в боте.")
		}
		return nil
	}
	if err := os.Rename(next, exe); err != nil {
		os.Remove(next)
		return errors.New("Нет прав на запись в папку программы. Скачайте новую версию в боте.")
	}
	return nil
}

// restart — отключить VPN, запустить новую версию и выйти. Новая подождёт, пока освободится порт интерфейса.
func (a *App) restart(exe string) {
	a.disconnect()
	time.Sleep(300 * time.Millisecond) // клиент WDTT получает STOP и выходит
	cmd := exec.Command(exe)
	cmd.Env = append(os.Environ(), "LITE404_AFTER_UPDATE=1")
	cmd.Dir = filepath.Dir(exe)
	detach(cmd)
	if err := cmd.Start(); err != nil {
		log.Printf("update: перезапуск не удался: %v", err)
		a.upd.set("error", 0, "Обновление установлено. Запустите программу заново.")
		return
	}
	time.Sleep(500 * time.Millisecond) // ответ интерфейсу успевает уйти
	os.Exit(0)
}

// cleanupUpdate — остатки прошлого обновления.
func cleanupUpdate() {
	if exe, err := os.Executable(); err == nil {
		if exe, err = filepath.EvalSymlinks(exe); err == nil {
			os.Remove(exe + ".old")
			os.Remove(exe + ".new")
		}
	}
	os.RemoveAll(filepath.Join(dataDir(), "update"))
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}
