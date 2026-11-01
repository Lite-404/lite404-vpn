package main

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func TestNewerVersion(t *testing.T) {
	cases := []struct {
		remote, local string
		want          bool
	}{
		{"0.9.0", "0.8.0", true},
		{"v0.9.0", "0.8.0", true},
		{"0.10.0", "0.9.9", true},
		{"1.0.0", "0.99.99", true},
		{"0.8.0", "0.8.0", false},
		{"0.7.9", "0.8.0", false},
		{"0.9.0", "dev", false},
		{"garbage", "0.8.0", false},
		{"0.9", "0.8.0", false},
	}
	for _, c := range cases {
		if got := newerVersion(c.remote, c.local); got != c.want {
			t.Errorf("newerVersion(%q, %q) = %v, want %v", c.remote, c.local, got, c.want)
		}
	}
}

func TestExtractZip(t *testing.T) {
	var buf bytes.Buffer
	z := zip.NewWriter(&buf)
	for name, data := range map[string]string{
		binaryEntry():                         "BIN",
		"Lite404 VPN.app/Contents/Info.plist": "PLIST",
		"Lite404 VPN/README.txt":              "readme",
	} {
		w, _ := z.Create(name)
		w.Write([]byte(data))
	}
	z.Close()
	if !strings.HasSuffix(assetName(), ".zip") {
		t.Skip("на этой системе архив tar.gz")
	}
	bin, _, err := extractUpdate(assetName(), buf.Bytes())
	if err != nil || string(bin) != "BIN" {
		t.Fatalf("bin=%q err=%v", bin, err)
	}
	if _, _, err := extractUpdate(assetName(), []byte("not a zip")); err == nil {
		t.Fatal("битый архив принят")
	}
}

func TestExtractTarGz(t *testing.T) {
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for _, e := range [][2]string{{"lite404-vpn/README.txt", "readme"}, {"lite404-vpn/lite404-vpn", "LINUXBIN"}} {
		tw.WriteHeader(&tar.Header{Name: e[0], Mode: 0o755, Size: int64(len(e[1])), Typeflag: tar.TypeReg})
		tw.Write([]byte(e[1]))
	}
	tw.Close()
	gz.Close()
	if !strings.HasSuffix(assetName(), ".tar.gz") {
		// на Windows/macOS проверяем разбор tar напрямую: ищем путь программы Linux
		if binaryEntry() != "lite404-vpn/lite404-vpn" {
			t.Skip("на этой системе архив zip")
		}
	}
	bin, _, err := extractUpdate("x.tar.gz", buf.Bytes())
	if err != nil || string(bin) != "LINUXBIN" {
		t.Fatalf("bin=%q err=%v", bin, err)
	}
}

func TestDownloadVerifiesHash(t *testing.T) {
	payload := []byte(strings.Repeat("lite404", 10000))
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(payload) }))
	defer srv.Close()
	sum := sha256.Sum256(payload)
	a := &App{s: &Settings{}}
	got, err := a.downloadUpdate(UpdateFile{URL: srv.URL, Size: int64(len(payload)), SHA256: hex.EncodeToString(sum[:])})
	if err != nil || !bytes.Equal(got, payload) {
		t.Fatalf("download: %v", err)
	}
	if _, err := a.downloadUpdate(UpdateFile{URL: srv.URL, Size: int64(len(payload)), SHA256: strings.Repeat("0", 64)}); err == nil {
		t.Fatal("неверный SHA-256 принят")
	}
	if _, err := a.downloadUpdate(UpdateFile{URL: srv.URL, Size: 10, SHA256: hex.EncodeToString(sum[:])}); err == nil {
		t.Fatal("файл больше заявленного принят")
	}
}

func TestCheckUpdate(t *testing.T) {
	m := UpdateManifest{Version: "99.0.0", Notes: "n", Files: map[string]UpdateFile{
		assetName(): {URL: "https://example.invalid/x", Size: 1, SHA256: strings.Repeat("a", 64)},
	}}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { json.NewEncoder(w).Encode(m) }))
	defer srv.Close()
	os.Setenv("LITE404_UPDATE_URL", srv.URL)
	defer os.Unsetenv("LITE404_UPDATE_URL")
	old := version
	defer func() { version = old }()
	a := &App{s: &Settings{}}

	version = "0.8.0"
	got, err := a.checkUpdate()
	if err != nil || got == nil || got.Version != "99.0.0" {
		t.Fatalf("ожидалось обновление: %v %v", got, err)
	}
	version = "99.0.0"
	if got, err := a.checkUpdate(); err != nil || got != nil {
		t.Fatalf("обновления быть не должно: %v %v", got, err)
	}
}
