package boxcore

import (
	"context"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/provider"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/dns/transport/hosts"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/protocol/block"
	"github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing-box/protocol/http"
	"github.com/sagernet/sing-box/protocol/mixed"
	"github.com/sagernet/sing-box/protocol/shadowsocks"
	"github.com/sagernet/sing-box/protocol/socks"
	"github.com/sagernet/sing-box/protocol/trojan"
	"github.com/sagernet/sing-box/protocol/tun"
	"github.com/sagernet/sing-box/protocol/vless"
	"github.com/sagernet/sing-box/protocol/vmess"
	"github.com/sagernet/sing-box/protocol/wireguard"
)

// Только то, что нужно приложению: входы TUN/SOCKS/HTTP, выходы VLESS/VMess/Trojan/Shadowsocks,
// WireGuard (резервный VPN на компьютере), DNS UDP/TCP/TLS/HTTPS. Остальное из sing-box не тянем.
func boxContext(ctx context.Context) context.Context {
	in := inbound.NewRegistry()
	tun.RegisterInbound(in)
	socks.RegisterInbound(in)
	http.RegisterInbound(in)
	mixed.RegisterInbound(in)

	out := outbound.NewRegistry()
	direct.RegisterOutbound(out)
	block.RegisterOutbound(out)
	group.RegisterSelector(out)
	socks.RegisterOutbound(out)
	http.RegisterOutbound(out)
	shadowsocks.RegisterOutbound(out)
	vmess.RegisterOutbound(out)
	trojan.RegisterOutbound(out)
	vless.RegisterOutbound(out)

	ep := endpoint.NewRegistry()
	wireguard.RegisterEndpoint(ep)

	dn := dns.NewTransportRegistry()
	transport.RegisterTCP(dn)
	transport.RegisterUDP(dn)
	transport.RegisterTLS(dn)
	transport.RegisterHTTPS(dn)
	hosts.RegisterTransport(dn)
	local.RegisterTransport(dn)

	return box.Context(ctx, in, out, ep, provider.NewRegistry(), dn, service.NewRegistry(), certificate.NewRegistry())
}
