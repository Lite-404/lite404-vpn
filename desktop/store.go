package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Settings — всё, что хранится между запусками (в папке настроек пользователя: Lite404/settings.json).
type Settings struct {
	Token       string          `json:"token"`
	Config      json.RawMessage `json:"config,omitempty"` // последний ответ сервера — чтобы работать без связи с ним
	Server      int             `json:"server"`
	Routing     string          `json:"routing"`      // smart | off
	LanDirect   *bool           `json:"lan_direct"`   // локальная сеть мимо VPN (nil — по умолчанию да)
	SystemProxy bool            `json:"system_proxy"` // включать системный прокси при подключении
	PingURL     string          `json:"ping_url"`
	ProxyActive bool            `json:"proxy_active"` // прокси включён нами — снять после аварийного выхода
}

func dataDir() string {
	base, err := os.UserConfigDir()
	if err != nil {
		base = "."
	}
	dir := filepath.Join(base, "Lite404")
	os.MkdirAll(filepath.Join(dir, "geo"), 0o700)
	return dir
}

func loadSettings() *Settings {
	s := &Settings{Routing: "smart", SystemProxy: true, PingURL: "https://www.gstatic.com/generate_204"}
	if data, err := os.ReadFile(filepath.Join(dataDir(), "settings.json")); err == nil {
		json.Unmarshal(data, s)
	}
	if s.Routing != "off" {
		s.Routing = "smart"
	}
	if s.LanDirect == nil {
		t := true
		s.LanDirect = &t
	}
	return s
}

func (s *Settings) save() {
	data, _ := json.MarshalIndent(s, "", " ")
	path := filepath.Join(dataDir(), "settings.json")
	tmp := path + ".tmp"
	if os.WriteFile(tmp, data, 0o600) == nil {
		os.Rename(tmp, path)
	}
}

// hwid — постоянный идентификатор компьютера для лимита устройств (хэш, сам ID не уходит на сервер).
func hwid() string {
	id := strings.TrimSpace(machineID())
	if id == "" { // нет системного ID — свой случайный, сохраняется в папке настроек
		path := filepath.Join(dataDir(), "device-id")
		if data, err := os.ReadFile(path); err == nil {
			id = strings.TrimSpace(string(data))
		} else {
			id = time.Now().String() + os.Getenv("USER") + os.Getenv("USERNAME")
			os.WriteFile(path, []byte(id), 0o600)
		}
	}
	sum := sha256.Sum256([]byte("lite404-desktop:" + id))
	return hex.EncodeToString(sum[:16])
}

// ensureGeo — geo-файлы профиля маршрутизации (свои категории вроде whitelist есть только в них).
// Раз в сутки обновляются; качаются напрямую, мимо VPN.
func ensureGeo(profile obj) error {
	dir := filepath.Join(dataDir(), "geo")
	for name, key := range map[string]string{geoIP: "Geoipurl", geoSite: "Geositeurl"} {
		url := str(profile, key, "")
		file := filepath.Join(dir, name)
		meta, _ := os.ReadFile(file + ".url")
		st, err := os.Stat(file)
		fresh := err == nil && string(meta) == url && time.Since(st.ModTime()) < 24*time.Hour
		if strings.HasPrefix(url, "https://") && !fresh {
			if derr := download(url, file); derr == nil {
				os.WriteFile(file+".url", []byte(url), 0o600)
			}
		}
		if st, err := os.Stat(file); err != nil || st.Size() == 0 {
			return errors.New("не удалось скачать правила маршрутизации")
		}
	}
	return nil
}

func download(url, target string) error {
	client := &http.Client{Timeout: 40 * time.Second, Transport: &http.Transport{Proxy: nil}}
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return errors.New(resp.Status)
	}
	tmp := target + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	n, err := io.Copy(f, io.LimitReader(resp.Body, 64<<20))
	f.Close()
	if err != nil || n < 1000 {
		os.Remove(tmp)
		return errors.New("файл не скачался")
	}
	return os.Rename(tmp, target)
}
