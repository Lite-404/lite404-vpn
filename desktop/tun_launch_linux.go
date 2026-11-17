package main

import (
	"os"
	"os/exec"
)

func prepareTunRuntime() error { return nil }

func launchTunWorker(job string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	if os.Geteuid() == 0 {
		return startTunCommand(exec.Command(exe, "--tun-worker", job), job)
	}
	return startTunCommand(exec.Command("pkexec", exe, "--tun-worker", job), job)
}
