package main

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

func runningApps() []desktopApp {
	result := []desktopApp{}
	seen := map[string]bool{}
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return result
	}
	defer windows.CloseHandle(snap)
	var p windows.ProcessEntry32
	p.Size = uint32(unsafe.Sizeof(p))
	for err = windows.Process32First(snap, &p); err == nil; err = windows.Process32Next(snap, &p) {
		name := windows.UTF16ToString(p.ExeFile[:])
		key := strings.ToLower(name)
		if seen[key] || !strings.HasSuffix(key, ".exe") || strings.HasPrefix(key, "lite404") {
			continue
		}
		seen[key] = true
		// Protected service processes cannot be inspected by a normal user.
		system := true
		if handle, e := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, p.ProcessID); e == nil {
			buf := make([]uint16, 32768)
			size := uint32(len(buf))
			if windows.QueryFullProcessImageName(handle, 0, &buf[0], &size) == nil {
				system = strings.Contains(strings.ToLower(windows.UTF16ToString(buf[:size])), "\\windows\\")
			}
			windows.CloseHandle(handle)
		}
		result = append(result, desktopApp{strings.TrimSuffix(name, filepath.Ext(name)), name, system})
	}
	sort.Slice(result, func(i, j int) bool { return strings.ToLower(result[i].Name) < strings.ToLower(result[j].Name) })
	return result
}

func setAutostart(on bool) error {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	if !on {
		err = k.DeleteValue("Lite404")
		if err == registry.ErrNotExist {
			return nil
		}
		return err
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	return k.SetStringValue("Lite404", `"`+exe+`" --autostart`)
}
func icmpPing(host string) int64 { return commandPing("-n", "1", "-w", "4000", host) }
