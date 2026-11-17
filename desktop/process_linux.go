package main

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

func runningApps() []desktopApp {
	result := []desktopApp{}
	seen := map[string]bool{}
	entries, _ := os.ReadDir("/proc")
	for _, entry := range entries {
		if _, err := strconv.Atoi(entry.Name()); err != nil {
			continue
		}
		dir := filepath.Join("/proc", entry.Name())
		exe, err := os.Readlink(filepath.Join(dir, "exe"))
		if err != nil {
			continue
		}
		name := strings.TrimSuffix(filepath.Base(exe), " (deleted)")
		if seen[name] || strings.HasPrefix(strings.ToLower(name), "lite404") {
			continue
		}
		seen[name] = true
		status, _ := os.ReadFile(filepath.Join(dir, "status"))
		owner := -1
		for _, line := range strings.Split(string(status), "\n") {
			if strings.HasPrefix(line, "Uid:") {
				fmt.Sscan(strings.TrimPrefix(line, "Uid:"), &owner)
				break
			}
		}
		result = append(result, desktopApp{name, name, owner != os.Getuid()})
	}
	sort.Slice(result, func(i, j int) bool { return strings.ToLower(result[i].Name) < strings.ToLower(result[j].Name) })
	return result
}

func setAutostart(on bool) error {
	config, err := os.UserConfigDir()
	if err != nil {
		return err
	}
	target := filepath.Join(config, "autostart", "lite404-vpn.desktop")
	if !on {
		err = os.Remove(target)
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(target), 0700); err != nil {
		return err
	}
	// Exec uses desktop-entry quoting; percent signs are literal field codes.
	quoted := strings.NewReplacer("\\", "\\\\", "\"", "\\\"", "$", "\\$", "`", "\\`", "%", "%%").Replace(exe)
	data := "[Desktop Entry]\nType=Application\nName=Lite 404 VPN\nExec=\"" + quoted + "\" --autostart\nTerminal=false\nX-GNOME-Autostart-enabled=true\n"
	return os.WriteFile(target, []byte(data), 0600)
}
func icmpPing(host string) int64 { return commandPing("-n", "-c", "1", "-W", "4", host) }
