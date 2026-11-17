package main

import (
	"bytes"
	"encoding/xml"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

func runningApps() []desktopApp {
	result := []desktopApp{}
	seen := map[string]bool{}
	output, err := exec.Command("ps", "-axo", "uid=,comm=").Output()
	if err != nil {
		return result
	}
	for _, line := range strings.Split(string(output), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		owner, err := strconv.Atoi(fields[0])
		if err != nil {
			continue
		}
		path := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), fields[0]))
		name := filepath.Base(path)
		if seen[name] || strings.HasPrefix(strings.ToLower(name), "lite404") {
			continue
		}
		seen[name] = true
		label := name
		if index := strings.Index(path, ".app/"); index >= 0 {
			label = filepath.Base(path[:index])
		}
		result = append(result, desktopApp{label, name, owner != os.Getuid()})
	}
	sort.Slice(result, func(i, j int) bool { return strings.ToLower(result[i].Name) < strings.ToLower(result[j].Name) })
	return result
}

func setAutostart(on bool) error {
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	target := filepath.Join(home, "Library", "LaunchAgents", "com.lite404.vpn.desktop.plist")
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
	var encoded bytes.Buffer
	xml.EscapeText(&encoded, []byte(exe))
	data := `<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>Label</key><string>com.lite404.vpn.desktop</string><key>ProgramArguments</key><array><string>` + encoded.String() + `</string><string>--autostart</string></array><key>RunAtLoad</key><true/></dict></plist>`
	if err = os.MkdirAll(filepath.Dir(target), 0700); err != nil {
		return err
	}
	return os.WriteFile(target, []byte(data), 0600)
}
func icmpPing(host string) int64 { return commandPing("-n", "-c", "1", "-W", "4000", host) }
