package main

import (
	"net"
	"sort"
	"strings"
	"time"
)

// Ignore virtual interfaces so creating our own TUN never restarts WDTT.
func physicalNetworkKey() string {
	interfaces, _ := net.Interfaces()
	var keys []string
	for _, iface := range interfaces {
		name := strings.ToLower(iface.Name)
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || virtualInterface(name) {
			continue
		}
		addresses, _ := iface.Addrs()
		for _, address := range addresses {
			ip, _, err := net.ParseCIDR(address.String())
			if err == nil && ip.To4() != nil && !ip.IsLinkLocalUnicast() && !ip.IsLoopback() {
				keys = append(keys, iface.Name+"="+ip.String())
			}
		}
	}
	sort.Strings(keys)
	return strings.Join(keys, "|")
}

func virtualInterface(name string) bool {
	for _, prefix := range []string{"lite404", "utun", "tun", "tap", "wg", "veth", "docker", "br-", "virbr", "vethernet", "tailscale", "throne"} {
		if strings.HasPrefix(name, prefix) {
			return true
		}
	}
	return false
}

type networkTracker struct {
	initialized       bool
	stable, candidate string
	since             time.Time
}

func (n *networkTracker) changed(key string, now time.Time) bool {
	if !n.initialized {
		n.initialized, n.stable, n.candidate = true, key, key
		return false
	}
	if key != n.candidate {
		n.candidate, n.since = key, now
		return false
	}
	if key != n.stable && now.Sub(n.since) >= 2*time.Second {
		n.stable = key
		return key != ""
	}
	return false
}
