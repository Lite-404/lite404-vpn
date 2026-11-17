package main

import (
	"os"
	"os/exec"
	"strconv"
	"strings"
)

func prepareTunRuntime() error { return nil }

func launchTunWorker(job string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	quote := func(value string) string { return "'" + strings.ReplaceAll(value, "'", "'\\''") + "'" }
	script := "do shell script " + strconv.Quote(quote(exe)+" --tun-worker "+quote(job)+" >/dev/null 2>&1 &") + " with administrator privileges"
	return startTunCommand(exec.Command("osascript", "-e", script), job)
}
