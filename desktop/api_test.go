package main

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

// Имя из токена не резолвится → запрос уходит на IP из apiFallbackIPs, а SNI и Host остаются именем.
func TestFetchConfigFallbackIP(t *testing.T) {
	const host = "lite404-fallback.invalid"
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

	apiFallbackIPs[host] = []string{"127.0.0.1"}
	defer delete(apiFallbackIPs, host)

	tok := &Token{Addr: host + ":" + port, FP: hex.EncodeToString(sum[:]), Key: "k"}
	c, _, err := FetchConfig(tok, "hw")
	if err != nil {
		t.Fatalf("FetchConfig: %v", err)
	}
	if c.User.Name != "t" || gotSNI != host || gotHost != host+":"+port {
		t.Fatalf("name=%q sni=%q host=%q", c.User.Name, gotSNI, gotHost)
	}

	// Неверный отпечаток — и через запасной IP не пускаем
	tok.FP = strings.Repeat("0", 64)
	if _, _, err := FetchConfig(tok, "hw"); err == nil {
		t.Fatal("ожидалась ошибка отпечатка")
	}
	// Имени нет в apiFallbackIPs — ошибка как раньше
	tok = &Token{Addr: "other.invalid:" + port, FP: hex.EncodeToString(sum[:]), Key: "k"}
	if _, _, err := FetchConfig(tok, "hw"); err == nil || !strings.Contains(err.Error(), "нет связи") {
		t.Fatalf("err=%v", err)
	}
}

// Живая проверка: LITE404_LIVE=1 go test -run Live . — соединение прямо на IP master, SNI/Host app.barked.fun,
// отпечаток настоящего серта, ключ неверный → 403.
func TestFetchConfigLive(t *testing.T) {
	if os.Getenv("LITE404_LIVE") != "1" {
		t.Skip("LITE404_LIVE=1")
	}
	fp := "bcbe2cd027ef71e484db1fa42e57382a45b0c5a985fa3a944d47490837d919f7"
	pin := func(raw [][]byte, _ [][]*x509.Certificate) error {
		sum := sha256.Sum256(raw[0])
		if hex.EncodeToString(sum[:]) != fp {
			return errors.New("pin")
		}
		return nil
	}
	tok := &Token{Addr: "app.barked.fun:443", FP: fp, Key: "wrong"}
	resp, err := postConfig(tok, "hw", &tls.Config{InsecureSkipVerify: true, VerifyPeerCertificate: pin},
		&fallbackDial{addr: apiFallbackIPs["app.barked.fun"][0] + ":443"})
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 403 {
		t.Fatalf("код %d", resp.StatusCode)
	}
}
