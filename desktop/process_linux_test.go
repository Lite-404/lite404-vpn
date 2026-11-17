package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestLinuxAutostartRoundTrip(t *testing.T) {
	config := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", config)
	if err := setAutostart(true); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(config, "autostart", "lite404-vpn.desktop")
	data, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	for _, part := range []string{"[Desktop Entry]", "Type=Application", "Exec=\"", " --autostart", "Terminal=false"} {
		if !strings.Contains(string(data), part) {
			t.Fatalf("missing %q", part)
		}
	}
	if err := setAutostart(false); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(target); !os.IsNotExist(err) {
		t.Fatalf("autostart entry was not removed: %v", err)
	}
	if err := setAutostart(false); err != nil {
		t.Fatal(err)
	}
}

func TestLinuxRunningAppsFindsUserProcess(t *testing.T) {
	child := exec.Command("sleep", "10")
	if err := child.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { child.Process.Kill(); child.Wait() }()
	for _, app := range runningApps() {
		if app.Process == "sleep" {
			if app.System {
				t.Fatal("user process marked as system")
			}
			return
		}
	}
	t.Fatal("running user process is missing")
}
