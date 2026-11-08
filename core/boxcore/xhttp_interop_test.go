package boxcore

import (
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Совместимость с настоящим сервером Xray: LITE404_XRAY=путь к xray(.exe) go test -run XrayInterop -v
// Поднимает локальный Xray с VLESS (XHTTP в разных режимах, без защиты и с Reality; Reality+Vision по TCP)
// и ходит через него ядром sing-box-extended, переводя клиентский конфиг в формате Xray.
func TestXrayInterop(t *testing.T) {
	xray := os.Getenv("LITE404_XRAY")
	if xray == "" {
		t.Skip("LITE404_XRAY")
	}
	keys, err := exec.Command(xray, "x25519").Output()
	if err != nil {
		t.Fatal(err)
	}
	var priv, pub string
	for _, line := range strings.Split(string(keys), "\n") {
		k, v, _ := strings.Cut(line, ":")
		switch {
		case strings.HasPrefix(k, "PrivateKey"):
			priv = strings.TrimSpace(v)
		case strings.HasPrefix(k, "Password"), strings.HasPrefix(k, "Public"):
			pub = strings.TrimSpace(v)
		}
	}
	if priv == "" || pub == "" {
		t.Fatal("нет ключей x25519")
	}

	// цель запросов — внешняя: выход Xray (freedom) на localhost не ходит
	const target = "http://www.gstatic.com/generate_204"
	// «маска» Reality — настоящий сайт с TLS 1.3 (с локальным тестовым сертификатом Xray авторизацию не проходит)
	const mask = "www.microsoft.com"

	freePort := func() int {
		l, _ := net.Listen("tcp", "127.0.0.1:0")
		defer l.Close()
		return l.Addr().(*net.TCPAddr).Port
	}
	type tc struct {
		name, network, security, mode string
		vision                        bool
	}
	cases := []tc{
		{"xhttp packet-up без защиты", "xhttp", "none", "packet-up", false},
		{"xhttp auto без защиты", "xhttp", "none", "auto", false},
		{"xhttp reality auto (stream-one)", "xhttp", "reality", "auto", false},
		{"xhttp reality stream-up", "xhttp", "reality", "stream-up", false},
		{"xhttp reality packet-up", "xhttp", "reality", "packet-up", false},
		{"tcp reality vision", "tcp", "reality", "", true},
	}
	const uuid = "0b7f2f43-9d5c-4f8e-9a51-7a1f2f6f1c11"
	fp := os.Getenv("LITE404_FP")
	if fp == "" {
		fp = "chrome"
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			port := freePort()
			stream := fmt.Sprintf(`"network":%q,"security":%q`, c.network, c.security)
			client := stream
			if c.network == "xhttp" {
				stream += fmt.Sprintf(`,"xhttpSettings":{"path":"/xh","mode":%q}`, c.mode)
				client += fmt.Sprintf(`,"xhttpSettings":{"path":"/xh","mode":%q,"extra":{"xPaddingBytes":"100-1000"}}`, c.mode)
			}
			if c.security == "reality" {
				stream += fmt.Sprintf(`,"realitySettings":{"target":"%s:443","serverNames":[%q],"privateKey":%q,"shortIds":["ab12"]}`, mask, mask, priv)
				client += fmt.Sprintf(`,"realitySettings":{"serverName":%q,"publicKey":%q,"shortId":"ab12","fingerprint":%q}`, mask, pub, fp)
			}
			flow := ""
			if c.vision {
				flow = "xtls-rprx-vision"
			}
			server := fmt.Sprintf(`{"log":{"loglevel":"warning"},
			 "inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"clients":[{"id":%q,"flow":%q}],"decryption":"none"},
			  "streamSettings":{%s}}],
			 "outbounds":[{"protocol":"freedom"}]}`, port, uuid, flow, stream)
			dir := t.TempDir()
			cfgPath := filepath.Join(dir, "server.json")
			os.WriteFile(cfgPath, []byte(server), 0o600)
			cmd := exec.Command(xray, "run", "-c", cfgPath)
			logf, _ := os.Create(filepath.Join(dir, "xray.log"))
			cmd.Stdout, cmd.Stderr = logf, logf
			if err := cmd.Start(); err != nil {
				t.Fatal(err)
			}
			defer func() { cmd.Process.Kill(); cmd.Wait(); logf.Close() }()
			for i := 0; i < 50; i++ {
				if c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port)); err == nil {
					c.Close()
					break
				}
				time.Sleep(100 * time.Millisecond)
			}

			clientConf := fmt.Sprintf(`{"outbounds":[{"tag":"proxy","protocol":"vless",
			 "settings":{"address":"127.0.0.1","port":%d,"id":%q,"encryption":"none","flow":%q},"streamSettings":{%s}}]}`, port, uuid, flow, client)
			conf, err := PingConfig([]byte(clientConf))
			if err != nil {
				t.Fatal(err)
			}
			inst, err := Start(conf, nil)
			if err != nil {
				t.Fatalf("%v\n%s", err, conf)
			}
			defer inst.Close()
			for i := 0; i < 3; i++ { // несколько запросов подряд — проверка xmux/повторного использования
				ms, err := inst.URLTest("", target, 20*time.Second)
				if err != nil {
					log, _ := os.ReadFile(filepath.Join(dir, "xray.log"))
					t.Fatalf("запрос %d: %v\nконфиг: %s\nжурнал Xray:\n%s", i, err, conf, log)
				}
				t.Logf("запрос %d: %d мс", i, ms)
			}
			if strings.Contains(c.name, "xhttp") && !strings.Contains(string(conf), `"type":"xhttp"`) {
				t.Fatal("транспорт не XHTTP")
			}
		})
	}
}
