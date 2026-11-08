//go:build !linux && !android

package boxcore

import "errors"

func dupFD(int) (int, error) {
	return -1, errors.New("TUN по дескриптору — только на Android")
}
func tunName(int) (string, error) { return "", errors.New("нет имени TUN") }
