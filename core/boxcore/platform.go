package boxcore

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"os"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
)

// MobilePlatform — платформа для Android: TUN уже создан приложением (VpnService.Builder), ядру
// передаётся его дескриптор; основная сеть телефона тоже приходит от приложения (netlink обычным
// приложениям на Android 11+ закрыт).
type MobilePlatform struct {
	TunFD   int               // дескриптор TUN; -1 — без TUN (замер задержки)
	Protect func(fd int) bool // VpnService.protect: сокет ядра мимо VPN

	mu        sync.Mutex
	iface     *control.Interface
	callbacks list.List[tun.DefaultInterfaceUpdateCallback]
	myNames   []string
	tunAddr   []netip.Addr
	nm        adapter.NetworkManager
}

var (
	_ adapter.PlatformInterface   = (*MobilePlatform)(nil)
	_ tun.DefaultInterfaceMonitor = (*MobilePlatform)(nil)
)

// SetDefaultInterface — основная сеть телефона (имя и индекс из LinkProperties); index < 0 — сети нет.
func (p *MobilePlatform) SetDefaultInterface(name string, index int) {
	p.mu.Lock()
	var next *control.Interface
	if index >= 0 && name != "" {
		next = &control.Interface{Index: index, Name: name, Flags: net.FlagUp | net.FlagRunning}
	}
	old := p.iface
	if old != nil && next != nil && old.Name == next.Name && old.Index == next.Index {
		p.mu.Unlock()
		return
	}
	p.iface = next
	cbs := p.callbacks.Array()
	nm := p.nm
	p.mu.Unlock()
	if nm != nil {
		_ = nm.UpdateInterfaces()
	}
	for _, cb := range cbs {
		cb(next, 0)
	}
}

func (p *MobilePlatform) Initialize(nm adapter.NetworkManager) error {
	p.mu.Lock()
	p.nm = nm
	p.mu.Unlock()
	return nil
}

func (p *MobilePlatform) UsePlatformAutoDetectInterfaceControl() bool { return p.Protect != nil }

func (p *MobilePlatform) AutoDetectInterfaceControl(fd int) error {
	if p.Protect != nil && !p.Protect(fd) {
		return errors.New("protect не удался")
	}
	return nil
}

func (p *MobilePlatform) BindInterfaceControl(fd int, _ string) error {
	return p.AutoDetectInterfaceControl(fd)
}

func (p *MobilePlatform) UsePlatformInterface() bool { return true }

func (p *MobilePlatform) OpenInterface(options *tun.Options, _ option.TunPlatformOptions) (tun.Tun, error) {
	if p.TunFD < 0 {
		return nil, errors.New("нет TUN")
	}
	fd, err := dupFD(p.TunFD) // ядро закрывает свою копию, приложение — свою
	if err != nil {
		return nil, err
	}
	options.FileDescriptor = fd
	if name, err := tunName(fd); err == nil && name != "" {
		options.Name = name
	}
	if options.InterfaceMonitor != nil {
		options.InterfaceMonitor.RegisterMyInterface(options.Name)
	}
	p.mu.Lock()
	p.tunAddr = nil
	for _, a := range options.Inet4Address {
		p.tunAddr = append(p.tunAddr, a.Addr())
	}
	for _, a := range options.Inet6Address {
		p.tunAddr = append(p.tunAddr, a.Addr())
	}
	p.mu.Unlock()
	return tun.New(*options)
}

func (p *MobilePlatform) ProcessPlatformOptions(option.TunPlatformOptions) error { return nil }

func (p *MobilePlatform) UsePlatformDefaultInterfaceMonitor() bool { return true }

func (p *MobilePlatform) CreateDefaultInterfaceMonitor(logger.Logger) tun.DefaultInterfaceMonitor {
	return p
}

func (p *MobilePlatform) UsePlatformNetworkInterfaces() bool { return true }

func (p *MobilePlatform) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.iface == nil {
		return nil, nil
	}
	return []adapter.NetworkInterface{{Interface: *p.iface}}, nil
}

func (p *MobilePlatform) UnderNetworkExtension() bool              { return false }
func (p *MobilePlatform) NetworkExtensionIncludeAllNetworks() bool { return false }
func (p *MobilePlatform) ClearDNSCache()                           {}
func (p *MobilePlatform) RequestPermissionForWIFIState() error     { return nil }

func (p *MobilePlatform) ReadWIFIState(context.Context) adapter.WIFIState {
	return adapter.WIFIState{}
}

// Поиск приложения-владельца соединения не нужен (правил по приложениям нет), но на Android sing-box
// включает его всегда; «свой» поиск сразу отвечает «не найдено» — без netlink-запроса на каждое соединение.
func (p *MobilePlatform) UsePlatformConnectionOwnerFinder() bool { return true }

func (p *MobilePlatform) FindConnectionOwner(*adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	return nil, os.ErrNotExist
}

func (p *MobilePlatform) UsePlatformWIFIMonitor() bool                 { return false }
func (p *MobilePlatform) UsePlatformNotification() bool                { return false }
func (p *MobilePlatform) SendNotification(*adapter.Notification) error { return nil }
func (p *MobilePlatform) CancelNotification(string, int32) error       { return nil }

func (p *MobilePlatform) MyInterfaceAddress() []netip.Addr {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.tunAddr
}

func (p *MobilePlatform) UsePlatformNeighborResolver() bool { return false }

func (p *MobilePlatform) StartNeighborMonitor(adapter.NeighborUpdateListener) error {
	return os.ErrInvalid
}

func (p *MobilePlatform) CloseNeighborMonitor(adapter.NeighborUpdateListener) error { return nil }
func (p *MobilePlatform) UsePlatformShell() bool                                    { return false }
func (p *MobilePlatform) CheckPlatformShell() error                                 { return os.ErrInvalid }

func (p *MobilePlatform) OpenShellSession(*adapter.PlatformUser, string, []string, string, int32, int32) (adapter.ShellSession, error) {
	return nil, os.ErrInvalid
}

func (p *MobilePlatform) LookupUser(string) (*adapter.PlatformUser, error) { return nil, os.ErrInvalid }
func (p *MobilePlatform) LookupSFTPServer() (string, error)                { return "", os.ErrInvalid }
func (p *MobilePlatform) ReadSystemSSHHostKey() ([]byte, error)            { return nil, os.ErrInvalid }
func (p *MobilePlatform) TailscaleHostname() string                        { return "" }
func (p *MobilePlatform) UsePlatformBridge() bool                          { return false }

func (p *MobilePlatform) CreateBridge(adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, os.ErrInvalid
}

// tun.DefaultInterfaceMonitor — события сети приходят из приложения (SetDefaultInterface).

func (p *MobilePlatform) Start() error { return nil }
func (p *MobilePlatform) Close() error { return nil }

func (p *MobilePlatform) DefaultInterface() *control.Interface {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.iface
}

func (p *MobilePlatform) OverrideAndroidVPN() bool { return false }
func (p *MobilePlatform) AndroidVPNEnabled() bool  { return false }

func (p *MobilePlatform) RegisterCallback(cb tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.callbacks.PushBack(cb)
}

func (p *MobilePlatform) UnregisterCallback(e *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.callbacks.Remove(e)
}

func (p *MobilePlatform) RegisterMyInterface(name string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.myNames = append(p.myNames, name)
}

func (p *MobilePlatform) MyInterfaces() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.myNames
}
