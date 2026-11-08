package boxcore

import (
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"testing"
	"time"
)

// Соединение по IP с TLS SNI: на прокси-сервер должен уйти домен (правка ядра из _singbox), даже если
// перед правилом по IP стоит resolve (он не должен заменить домен на IP и не должен ходить в DNS).
// Запуск: go test -modfile=$(go run ./cmd/patchcore) … (без правки ядро не примет override_destination).
func TestSniffOverrideSendsDomainToProxy(t *testing.T) {
	// по IPv4: исходный адрес остаётся для правил по IP, DNS не нужен (сервер DNS недоступен)
	sniffCase(t, []byte{1, 1, 2, 3, 4}, `{"type":"udp","tag":"dns-direct","server":"127.0.0.9"}`)
	// по IPv6 (Chrome со своим DNS): правило ::/0 не должно отклонить — домен резолвится в IPv4
	v6 := append([]byte{4}, net.ParseIP("2001:db8::1").To16()...)
	sniffCase(t, v6, `{"type":"hosts","tag":"dns-direct","predefined":{"example.com":"1.2.3.4"}}`)
}

// sniffCase — SOCKS5-адрес addr (тип + байты), порт 443, TLS SNI example.com.
func sniffCase(t *testing.T, addr []byte, dnsServer string) {
	got := make(chan string, 1)
	upstream, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer upstream.Close()
	go fakeSocks(upstream, got)

	in, _ := net.Listen("tcp", "127.0.0.1:0")
	inPort := in.Addr().(*net.TCPAddr).Port
	in.Close()
	conf := fmt.Sprintf(`{"log":{"disabled":true},
	 "inbounds":[{"type":"socks","tag":"in","listen":"127.0.0.1","listen_port":%d}],
	 "outbounds":[{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":%d},{"type":"direct","tag":"direct"}],
	 "dns":{"servers":[`+dnsServer+`],"strategy":"ipv4_only"},
	 "route":{"rules":[`+mustJSONString(sniffRules("in", obj{"destOverride": []any{"http", "tls"}}))+`,
	   {"action":"resolve"},{"ip_cidr":["10.0.0.0/8"],"outbound":"direct"},{"ip_cidr":["::/0"],"action":"reject"}],
	  "final":"proxy","default_domain_resolver":{"server":"dns-direct"}}}`,
		inPort, upstream.Addr().(*net.TCPAddr).Port)
	inst, err := Start([]byte(conf), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer inst.Close()

	c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", inPort), 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	// SOCKS5 к addr:443 (по IP, как из TUN)
	c.Write([]byte{5, 1, 0})
	io.ReadFull(c, make([]byte, 2))
	c.Write(append(append([]byte{5, 1, 0}, addr...), 1, 187))
	io.ReadFull(c, make([]byte, 4))
	io.ReadFull(c, make([]byte, 6)) // ответ: IPv4 + порт (ядро отвечает 0.0.0.0:0)
	go tls.Client(c, &tls.Config{ServerName: "example.com"}).Handshake()

	select {
	case dest := <-got:
		if dest != "example.com:443" {
			t.Fatalf("на сервер ушло %q, нужно example.com:443", dest)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("прокси-сервер не получил соединение")
	}
}

func mustJSONString(v []any) string {
	s := string(mustJSON(v))
	return s[1 : len(s)-1] // элементы массива — в общий список правил
}

// fakeSocks — SOCKS5-сервер, который запоминает запрошенный адрес.
func fakeSocks(l net.Listener, got chan<- string) {
	c, err := l.Accept()
	if err != nil {
		return
	}
	defer c.Close()
	h := make([]byte, 2)
	io.ReadFull(c, h)
	io.ReadFull(c, make([]byte, h[1]))
	c.Write([]byte{5, 0})
	req := make([]byte, 4)
	io.ReadFull(c, req)
	var host string
	switch req[3] {
	case 1:
		b := make([]byte, 4)
		io.ReadFull(c, b)
		host = net.IP(b).String()
	case 3:
		n := make([]byte, 1)
		io.ReadFull(c, n)
		b := make([]byte, n[0])
		io.ReadFull(c, b)
		host = string(b)
	case 4:
		b := make([]byte, 16)
		io.ReadFull(c, b)
		host = net.IP(b).String()
	}
	p := make([]byte, 2)
	io.ReadFull(c, p)
	got <- net.JoinHostPort(host, fmt.Sprint(binary.BigEndian.Uint16(p)))
	c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
}
