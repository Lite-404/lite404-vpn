package main

import (
	"crypto/sha256"
	"encoding/hex"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"testing"
)

// Старый токен (IP старого сервера, порт 2097) → имя app.barked.fun через свой DNS, порт API; SNI и Host = имя.
func TestFetchConfigForcedDNS(t *testing.T) {
	var gotSNI, gotHost string
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotSNI, gotHost = r.TLS.ServerName, r.Host
		if r.Header.Get("Authorization") != "Bearer k" {
			w.WriteHeader(403)
			return
		}
		w.Write([]byte(`{"user":{"name":"t","enabled":true},"xray":[],"wdtt":[]}`))
	}))
	srv.StartTLS()
	defer srv.Close()
	sum := sha256.Sum256(srv.Certificate().Raw)
	_, port, _ := net.SplitHostPort(srv.Listener.Addr().String())

	apiPort = port
	resolveAPI = func(h string) []string {
		if h == apiDomain {
			return []string{"127.0.0.1"}
		}
		return nil
	}
	defer func() { apiPort, resolveAPI = "443", resolveDoH }()

	tok := &Token{Addr: "88.151.114.227:2097", FP: hex.EncodeToString(sum[:]), Key: "k"}
	c, _, err := FetchConfig(tok, "hw")
	if err != nil {
		t.Fatalf("FetchConfig: %v", err)
	}
	if c.User.Name != "t" || gotSNI != apiDomain || gotHost != apiDomain+":"+port {
		t.Fatalf("name=%q sni=%q host=%q", c.User.Name, gotSNI, gotHost)
	}

	// Неверный отпечаток — не пускаем
	tok.FP = strings.Repeat("0", 64)
	if _, _, err := FetchConfig(tok, "hw"); err == nil {
		t.Fatal("ожидалась ошибка отпечатка")
	}
	// Имя не находится ни своим, ни системным DNS — ошибка связи
	tok = &Token{Addr: "other.invalid:443", FP: hex.EncodeToString(sum[:]), Key: "k"}
	if _, _, err := FetchConfig(tok, "hw"); err == nil || !strings.Contains(err.Error(), "нет связи") {
		t.Fatalf("err=%v", err)
	}
}

func TestAPIHost(t *testing.T) {
	for in, want := range map[string]string{
		"88.151.114.227:2097": "app.barked.fun",
		"APP.barked.fun:2097": "app.barked.fun",
		"app.barked.fun":      "app.barked.fun",
	} {
		if got := apiHost(in); got != want {
			t.Fatalf("%s: %s", in, got)
		}
	}
}

// Живая проверка: LITE404_LIVE=1 go test -run Live . — свой DNS находит каскад, старый токен с неверным ключом → 403.
func TestFetchConfigLive(t *testing.T) {
	if os.Getenv("LITE404_LIVE") != "1" {
		t.Skip("LITE404_LIVE=1")
	}
	if ips := resolveDoH(apiDomain); !slices.Contains(ips, "5.59.204.154") {
		t.Fatalf("DoH: %v", ips)
	}
	tok := &Token{Addr: "88.151.114.227:2097", FP: "bcbe2cd027ef71e484db1fa42e57382a45b0c5a985fa3a944d47490837d919f7", Key: "wrong"}
	if _, _, err := FetchConfig(tok, "hw"); err != ErrBadToken {
		t.Fatalf("err=%v", err)
	}
}
