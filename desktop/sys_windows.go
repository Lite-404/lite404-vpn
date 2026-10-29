package main

import (
	"os/exec"
	"syscall"

	"golang.org/x/sys/windows/registry"
)

const inetKey = `Software\Microsoft\Windows\CurrentVersion\Internet Settings`

func machineID() string {
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, `SOFTWARE\Microsoft\Cryptography`, registry.QUERY_VALUE|registry.WOW64_64KEY)
	if err != nil {
		return ""
	}
	defer k.Close()
	v, _, _ := k.GetStringValue("MachineGuid")
	return v
}

// Системный прокси Windows (его используют браузеры, Telegram Desktop, большинство программ).
func setSystemProxy(on bool) error {
	k, err := registry.OpenKey(registry.CURRENT_USER, inetKey, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	if on {
		k.SetStringValue("ProxyServer", "127.0.0.1:10809")
		k.SetStringValue("ProxyOverride", "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.2*;172.30.*;172.31.*;192.168.*;169.254.*;<local>")
		k.SetDWordValue("ProxyEnable", 1)
	} else {
		k.SetDWordValue("ProxyEnable", 0)
	}
	refreshInternetSettings()
	return nil
}

func refreshInternetSettings() {
	wininet := syscall.NewLazyDLL("wininet.dll")
	set := wininet.NewProc("InternetSetOptionW")
	set.Call(0, 39, 0, 0) // INTERNET_OPTION_SETTINGS_CHANGED
	set.Call(0, 37, 0, 0) // INTERNET_OPTION_REFRESH
}

// ourProxyActive — включён ли системный прокси именно на нас (после аварийного выхода).
func ourProxyActive() bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, inetKey, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	en, _, _ := k.GetIntegerValue("ProxyEnable")
	srv, _, _ := k.GetStringValue("ProxyServer")
	return en == 1 && srv == "127.0.0.1:10809"
}

func openBrowser(url string) {
	cmd := exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	cmd.Start()
}

const proxyHint = "Системный прокси Windows включается автоматически"
