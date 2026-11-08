package boxcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync/atomic"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/bufio"
	"github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

// Version — версия ядра для экрана «О приложении».
func Version() string {
	return C.Version
}

// Instance — запущенное ядро sing-box.
type Instance struct {
	box    *box.Box
	cancel context.CancelFunc
	ctx    context.Context
	up     atomic.Int64
	down   atomic.Int64
}

// Check — конфиг sing-box разбирается и проходит проверку (без запуска).
func Check(config []byte) error {
	ctx := boxContext(context.Background())
	_, err := json.UnmarshalExtendedContext[option.Options](ctx, config)
	return err
}

// Start — запустить ядро с конфигом sing-box. platform — для Android (TUN, сокеты мимо VPN), иначе nil.
func Start(config []byte, platform adapter.PlatformInterface) (*Instance, error) {
	ctx, cancel := context.WithCancel(boxContext(context.Background()))
	opts, err := json.UnmarshalExtendedContext[option.Options](ctx, config)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("ядро не приняло конфиг: %v", err)
	}
	if platform != nil {
		service.MustRegister[adapter.PlatformInterface](ctx, platform)
	}
	b, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("ядро: %v", err)
	}
	inst := &Instance{box: b, cancel: cancel, ctx: ctx}
	b.Router().AppendTracker(inst)
	if err := b.Start(); err != nil {
		b.Close()
		cancel()
		return nil, fmt.Errorf("ядро не запустилось: %v", err)
	}
	return inst, nil
}

// Close — остановить ядро. Повторный вызов безопасен.
func (i *Instance) Close() error {
	if i == nil || i.box == nil {
		return nil
	}
	done := make(chan error, 1)
	go func() { done <- i.box.Close() }()
	var err error
	select {
	case err = <-done:
	case <-time.After(5 * time.Second): // зависшее соединение не должно держать остановку
		err = errors.New("ядро закрывалось слишком долго")
	}
	i.cancel()
	return err
}

// ResetNetwork — сменилась сеть: закрыть старые соединения.
func (i *Instance) ResetNetwork() {
	if i != nil && i.box != nil {
		i.box.Router().ResetNetwork()
	}
}

// Traffic — байты, отданные и полученные через ядро с момента запуска.
func (i *Instance) Traffic() (up, down int64) {
	return i.up.Load(), i.down.Load()
}

// счётчик трафика (adapter.ConnectionTracker)
func (i *Instance) RoutedConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) net.Conn {
	return bufio.NewInt64CounterConn(conn, []*atomic.Int64{&i.up}, []*atomic.Int64{&i.down})
}

func (i *Instance) RoutedPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) N.PacketConn {
	return bufio.NewInt64CounterPacketConn(conn, []*atomic.Int64{&i.up}, nil, []*atomic.Int64{&i.down}, nil)
}

func (i *Instance) RoutedFlow(ctx context.Context, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) tun.FlowTracker {
	return nil
}

// Outbound — выход (или WireGuard) по тегу.
func (i *Instance) dialer(tag string) (N.Dialer, error) {
	if tag == "" {
		d := i.box.Outbound().Default()
		if d == nil {
			return nil, errors.New("нет выхода по умолчанию")
		}
		return d, nil
	}
	if o, ok := i.box.Outbound().Outbound(tag); ok { // WireGuard (endpoint) тоже числится выходом
		return o, nil
	}
	return nil, fmt.Errorf("нет выхода %q", tag)
}

// URLTest — полный HTTP-запрос через выход tag ("" — по умолчанию), мс. Как measureDelay у Xray.
func (i *Instance) URLTest(tag, link string, timeout time.Duration) (int64, error) {
	d, err := i.dialer(tag)
	if err != nil {
		return -1, err
	}
	client := &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			DisableKeepAlives: true,
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return d.DialContext(ctx, N.NetworkTCP, M.ParseSocksaddr(addr))
			},
		},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	ctx, cancel := context.WithTimeout(i.ctx, timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return -1, err
	}
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return -1, err
	}
	io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	resp.Body.Close()
	if resp.StatusCode != 204 && resp.StatusCode != 200 {
		return -1, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return time.Since(start).Milliseconds(), nil
}

// Ping — задержка до сервера без подключения: временное ядро только с выходом к серверу.
// xrayConfig — конфиг сервера из подписки (формат Xray).
func Ping(xrayConfig []byte, link string, timeout time.Duration, platform adapter.PlatformInterface) (int64, error) {
	conf, err := PingConfig(xrayConfig)
	if err != nil {
		return -1, err
	}
	inst, err := Start(conf, platform)
	if err != nil {
		return -1, err
	}
	defer inst.Close()
	return inst.URLTest("", link, timeout)
}
