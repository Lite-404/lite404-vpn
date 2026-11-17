package main

import (
	"bytes"
	_ "embed"
	"golang.org/x/sys/windows"
	"os"
	"path/filepath"
)

// Keep upgrades from older, single-EXE releases functional as well as ZIP installs.
//
//go:embed runtime/windows/wintun.dll
var wintunRuntime []byte

//go:embed runtime/windows/WINTUN-LICENSE.txt
var wintunLicense []byte

func prepareTunRuntime() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	for name, data := range map[string][]byte{"wintun.dll": wintunRuntime, "WINTUN-LICENSE.txt": wintunLicense} {
		path := filepath.Join(filepath.Dir(exe), name)
		if existing, err := os.ReadFile(path); err == nil && bytes.Equal(existing, data) {
			continue
		}
		if err := os.WriteFile(path, data, 0644); err != nil {
			return err
		}
	}
	return nil
}

func launchTunWorker(job string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	verb, _ := windows.UTF16PtrFromString("runas")
	file, _ := windows.UTF16PtrFromString(exe)
	params, _ := windows.UTF16PtrFromString("--tun-worker \"" + job + "\"")
	dir, _ := windows.UTF16PtrFromString(filepath.Dir(exe))
	return windows.ShellExecute(0, verb, file, params, dir, windows.SW_HIDE)
}
