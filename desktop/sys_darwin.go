package main

import (
	"os/exec"
	"regexp"
	"strings"
)

func machineID() string {
	out, _ := exec.Command("ioreg", "-rd1", "-c", "IOPlatformExpertDevice").Output()
	if m := regexp.MustCompile(`"IOPlatformUUID" = "([^"]+)"`).FindSubmatch(out); m != nil {
		return string(m[1])
	}
	return ""
}

// Сетевые службы macOS (Wi-Fi, Ethernet…), без отключённых (они помечены «*»).
func networkServices() []string {
	out, _ := exec.Command("networksetup", "-listallnetworkservices").Output()
	var list []string
	for i, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if i == 0 || line == "" || strings.HasPrefix(line, "*") {
			continue
		}
		list = append(list, line)
	}
	return list
}

func setSystemProxy(on bool) error {
	var err error
	for _, svc := range networkServices() {
		run := func(args ...string) {
			if e := exec.Command("networksetup", args...).Run(); e != nil {
				err = e
			}
		}
		if on {
			run("-setwebproxy", svc, "127.0.0.1", "10809")
			run("-setsecurewebproxy", svc, "127.0.0.1", "10809")
			run("-setsocksfirewallproxy", svc, "127.0.0.1", "10808")
			run("-setproxybypassdomains", svc, "localhost", "127.0.0.1", "*.local", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16")
		} else {
			run("-setwebproxystate", svc, "off")
			run("-setsecurewebproxystate", svc, "off")
			run("-setsocksfirewallproxystate", svc, "off")
		}
	}
	return err
}

func ourProxyActive() bool {
	for _, svc := range networkServices() {
		out, _ := exec.Command("networksetup", "-getwebproxy", svc).Output()
		s := string(out)
		if strings.Contains(s, "Enabled: Yes") && strings.Contains(s, "127.0.0.1") && strings.Contains(s, "10809") {
			return true
		}
	}
	return false
}

func openBrowser(url string) { exec.Command("open", url).Start() }

const proxyHint = "Системный прокси macOS включается автоматически"
