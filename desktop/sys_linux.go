package main

import (
	"os"
	"os/exec"
	"strings"
)

func machineID() string {
	for _, p := range []string{"/etc/machine-id", "/var/lib/dbus/machine-id"} {
		if b, err := os.ReadFile(p); err == nil && len(strings.TrimSpace(string(b))) > 0 {
			return string(b)
		}
	}
	return ""
}

// Системный прокси через gsettings (GNOME, Cinnamon, Budgie, MATE-приложения на GTK).
// В других окружениях укажите прокси вручную: HTTP 127.0.0.1:10809 или SOCKS5 127.0.0.1:10808.
func setSystemProxy(on, lan bool) error {
	if _, err := exec.LookPath("gsettings"); err != nil {
		return err
	}
	g := func(args ...string) error {
		return exec.Command("gsettings", append([]string{"set"}, args...)...).Run()
	}
	if !on {
		return g("org.gnome.system.proxy", "mode", "none")
	}
	g("org.gnome.system.proxy.http", "host", "127.0.0.1")
	g("org.gnome.system.proxy.http", "port", "10809")
	g("org.gnome.system.proxy.https", "host", "127.0.0.1")
	g("org.gnome.system.proxy.https", "port", "10809")
	g("org.gnome.system.proxy.socks", "host", "127.0.0.1")
	g("org.gnome.system.proxy.socks", "port", "10808")
	ignore := "['localhost', '127.0.0.0/8', '::1', '*.local']"
	if lan {
		ignore = "['localhost', '127.0.0.0/8', '::1', '10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '169.254.0.0/16', '*.local']"
	}
	g("org.gnome.system.proxy", "ignore-hosts", ignore)
	return g("org.gnome.system.proxy", "mode", "manual")
}

func ourProxyActive() bool {
	mode, _ := exec.Command("gsettings", "get", "org.gnome.system.proxy", "mode").Output()
	port, _ := exec.Command("gsettings", "get", "org.gnome.system.proxy.http", "port").Output()
	return strings.Contains(string(mode), "manual") && strings.TrimSpace(string(port)) == "10809"
}

func openBrowser(url string) { exec.Command("xdg-open", url).Start() }

const proxyHint = "Системный прокси включается через gsettings (GNOME и похожие). В других окружениях укажите прокси вручную"
