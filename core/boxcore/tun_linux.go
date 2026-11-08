//go:build linux || android

package boxcore

import (
	"fmt"
	"syscall"
	"unsafe"

	"golang.org/x/sys/unix"
)

func dupFD(fd int) (int, error) {
	n, err := unix.Dup(fd)
	if err != nil {
		return -1, fmt.Errorf("dup TUN: %v", err)
	}
	unix.CloseOnExec(n)
	return n, nil
}

func tunName(fd int) (string, error) {
	var ifr [unix.IFNAMSIZ + 64]byte
	_, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), uintptr(unix.TUNGETIFF), uintptr(unsafe.Pointer(&ifr[0])))
	if errno != 0 {
		return "", syscall.Errno(errno)
	}
	return unix.ByteSliceToString(ifr[:]), nil
}
