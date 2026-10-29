package main

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
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
	client := &http.Client{
		Timeout: 25 * time.Second,
		Transport: &http.Transport{
			Proxy:           nil, // напрямую, даже если включён системный прокси
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true, VerifyPeerCertificate: pin},
		},
	}
	req, _ := http.NewRequest("POST", "https://"+t.Addr+"/app/v1/config", bytes.NewReader([]byte("{}")))
	req.Header.Set("Authorization", "Bearer "+t.Key)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-HWID", hwid)
	req.Header.Set("X-Device-OS", osName())
	req.Header.Set("X-Device-Model", "Lite404 VPN Desktop")
	req.Header.Set("User-Agent", fmt.Sprintf("Lite404VPN/%s (%s)", version, osName()))
	resp, err := client.Do(req)
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
