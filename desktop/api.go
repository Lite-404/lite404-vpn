package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"runtime"
	"strings"
	"time"
)

// Token — токен из бота: "L404-" + base64url({"a":"host:port","f":"sha256 сертификата","k":"секрет"}).
type Token struct {
	Addr string `json:"a"`
	FP   string `json:"f"`
	Key  string `json:"k"`
}

func ParseToken(raw string) (*Token, error) {
	s := strings.Join(strings.Fields(raw), "")
	if !strings.HasPrefix(s, "L404-") {
		return nil, errors.New("это не токен Lite 404 — он начинается с L404-")
	}
	body := strings.TrimRight(strings.TrimPrefix(s, "L404-"), "=")
	data, err := base64.RawURLEncoding.DecodeString(body)
	if err != nil {
		return nil, errors.New("токен повреждён — скопируйте его из бота целиком")
	}
	var t Token
	if json.Unmarshal(data, &t) != nil || t.Addr == "" || t.Key == "" || len(t.FP) != 64 {
		return nil, errors.New("токен повреждён — скопируйте его из бота целиком")
	}
	t.FP = strings.ToLower(t.FP)
	return &t, nil
}

type UserInfo struct {
	Name         string `json:"name"`
	Enabled      bool   `json:"enabled"`
	ExpiresAt    int64  `json:"expires_at"`
	Up           int64  `json:"up"`
	Down         int64  `json:"down"`
	Total        int64  `json:"total"`
	DevicesLimit int    `json:"devices_limit"`
	Devices      int    `json:"devices"`
}

// AppConfig — ответ /app/v1/config: обычные серверы (JSON-подписка 3x-ui), профили маршрутизации из панели.
// WdttServer — страна резервного VPN: адрес ноды и пароль подключения (= UUID клиента в 3x-ui).
type WdttServer struct {
	Name     string `json:"name"`
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Password string `json:"password"`
}

type AppConfig struct {
	User    UserInfo                   `json:"user"`
	VKHash  string                     `json:"vk_hash"`
	Wdtt    []WdttServer               `json:"wdtt"`
	Xray    []json.RawMessage          `json:"xray"`
	Routing map[string]json.RawMessage `json:"routing"`
	Error   string                     `json:"error"`
}

var ErrBadToken = errors.New("токен не подходит — возьмите новый в боте @lite404bot")

func osName() string {
	switch runtime.GOOS {
	case "darwin":
		return "macOS"
	case "windows":
		return "Windows"
	default:
		return "Linux"
	}
}

// FetchConfig — запрос данных по токену. Сертификат сервера сверяется с отпечатком из токена
// (как в Android-приложении), доверие к цепочке CA не требуется.
func FetchConfig(t *Token, hwid string) (*AppConfig, []byte, error) {
	pin := func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
		if len(rawCerts) == 0 {
			return errors.New("сервер не прислал сертификат")
		}
		sum := sha256.Sum256(rawCerts[0])
		if hex.EncodeToString(sum[:]) != t.FP {
			return errors.New("сертификат сервера не совпадает с токеном")
		}
		return nil
	}
	tlsConf := &tls.Config{InsecureSkipVerify: true, VerifyPeerCertificate: pin}
	host := apiHost(t.Addr)
	tok := *t
	tok.Addr = net.JoinHostPort(host, apiPort)
	var resp *http.Response
	err := errors.New("DNS не нашёл адрес сервера")
	// 1) Имя через свой DNS (DoH по IP) → соединение на найденный IP, SNI и Host = имя
	for _, ip := range resolveAPI(host) {
		if resp, err = postConfig(&tok, hwid, tlsConf, &fallbackDial{addr: net.JoinHostPort(ip, apiPort)}); err == nil {
			break
		}
	}
	// 2) Свой DNS недоступен — через системный DNS
	if err != nil {
		resp, err = postConfig(&tok, hwid, tlsConf, nil)
	}
	if err != nil {
		return nil, nil, fmt.Errorf("нет связи с сервером: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	switch {
	case resp.StatusCode == 403:
		return nil, nil, ErrBadToken
	case resp.StatusCode == 429:
		return nil, nil, errors.New("слишком часто — попробуйте через минуту")
	case resp.StatusCode != 200:
		return nil, nil, fmt.Errorf("сервер ответил %d", resp.StatusCode)
	}
	var c AppConfig
	if err := json.Unmarshal(body, &c); err != nil {
		return nil, nil, errors.New("сервер прислал непонятный ответ")
	}
	return &c, body, nil
}

// API приложения всегда на порту 443. Старые токены выданы на IP старого сервера (88.151.114.227:2097) —
// их ведём на домен app.barked.fun.
const apiDomain = "app.barked.fun"

var (
	apiPort        = "443"
	legacyAPIHosts = map[string]bool{"88.151.114.227": true}
	// Свой DNS для имени API: DoH по IP-адресу, мимо системного DNS (у провайдера он бывает сломан или подменён).
	apiDoH     = []string{"https://1.1.1.1/dns-query?type=A&name=", "https://8.8.8.8/resolve?type=A&name="}
	resolveAPI = resolveDoH
)

// apiHost — имя сервера API из адреса токена (порт токена не используется).
func apiHost(addr string) string {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		host = addr
	}
	host = strings.ToLower(host)
	if legacyAPIHosts[host] {
		return apiDomain
	}
	return host
}

// resolveDoH — IPv4-адреса имени через DoH (JSON API Cloudflare/Google); пусто — DoH недоступен.
func resolveDoH(host string) []string {
	c := &http.Client{Timeout: 5 * time.Second, Transport: &http.Transport{Proxy: nil}}
	for _, u := range apiDoH {
		req, _ := http.NewRequest("GET", u+url.QueryEscape(host), nil)
		req.Header.Set("Accept", "application/dns-json")
		resp, err := c.Do(req)
		if err != nil {
			continue
		}
		var r struct {
			Answer []struct {
				Type int    `json:"type"`
				Data string `json:"data"`
			} `json:"Answer"`
		}
		err = json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&r)
		resp.Body.Close()
		if err != nil {
			continue
		}
		var ips []string
		for _, a := range r.Answer {
			if ip := net.ParseIP(a.Data); a.Type == 1 && ip != nil && ip.To4() != nil {
				ips = append(ips, a.Data)
			}
		}
		if len(ips) > 0 {
			return ips
		}
	}
	return nil
}

type fallbackDial struct{ addr string }

func (f *fallbackDial) dial(ctx context.Context, network, _ string) (net.Conn, error) {
	return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, network, f.addr)
}

// postConfig — POST /app/v1/config; via != nil — соединение на заданный адрес вместо DNS.
func postConfig(t *Token, hwid string, tlsConf *tls.Config, via *fallbackDial) (*http.Response, error) {
	tr := &http.Transport{
		Proxy:               nil, // напрямую, даже если включён системный прокси
		TLSClientConfig:     tlsConf,
		TLSHandshakeTimeout: 15 * time.Second,
	}
	if via != nil {
		tr.DialContext = via.dial
	}
	client := &http.Client{Timeout: 25 * time.Second, Transport: tr}
	req, _ := http.NewRequest("POST", "https://"+t.Addr+"/app/v1/config", bytes.NewReader([]byte("{}")))
	req.Header.Set("Authorization", "Bearer "+t.Key)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-HWID", hwid)
	req.Header.Set("X-Device-OS", osName())
	req.Header.Set("X-Device-Model", "Lite404 VPN Desktop")
	req.Header.Set("User-Agent", fmt.Sprintf("Lite404VPN/%s (%s)", version, osName()))
	resp, err := client.Do(req)
	if err != nil {
		tr.CloseIdleConnections()
	}
	return resp, err
}

func ErrorText(code string) string {
	switch code {
	case "":
		return ""
	case "device_limit":
		return "Достигнут лимит устройств. Удалите ненужное устройство в боте"
	case "json_sub_disabled":
		return "На сервере выключена JSON-подписка"
	case "expired":
		return "Подписка закончилась или выключена. Продлите её в боте"
	default:
		return "Ошибка подписки: " + code
	}
}
