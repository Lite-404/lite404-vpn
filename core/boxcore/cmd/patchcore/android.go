package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// When Android loads a Go shared library, the runtime can miss AT_HWCAP if it
// cannot read /proc/self/auxv (go.dev/issue/9229). Ask Bionic for the kernel's
// hardware flags before Go initializes its CPU and crypto packages.
func androidRuntimeOverlay(dir string) (string, error) {
	dir, err := filepath.Abs(dir)
	if err != nil {
		return "", err
	}
	src := filepath.Join(runtime.GOROOT(), "src", "runtime")
	callbacks := filepath.Join(src, "cgo", "callbacks_unix.go")
	if _, err := os.Stat(callbacks); os.IsNotExist(err) {
		callbacks = filepath.Join(src, "cgo", "callbacks.go")
	}
	const init = "func osinit() {"
	const initWithHWCap = `var _cgo_android_hwcap unsafe.Pointer

func osinit() {
	if GOOS == "android" && GOARCH == "arm64" && _cgo_android_hwcap != nil {
		var hwcap uintptr
		asmcgocall_no_g(_cgo_android_hwcap, unsafe.Pointer(&hwcap))
		if hwcap != 0 {
			archauxv(_AT_HWCAP, hwcap)
		}
	}`
	const callbackAnchor = "//go:cgo_import_static x_cgo_init"
	const callback = `//go:cgo_import_static x_cgo_android_hwcap
//go:linkname x_cgo_android_hwcap x_cgo_android_hwcap
//go:linkname _cgo_android_hwcap runtime._cgo_android_hwcap
var x_cgo_android_hwcap byte
var _cgo_android_hwcap = &x_cgo_android_hwcap

` + callbackAnchor
	const cAnchor = `#include "libcgo.h"`
	const cHook = cAnchor + `
#include <stdint.h>
#include <sys/auxv.h>

void x_cgo_android_hwcap(void *out)
{
	*(uintptr_t *)out = getauxval(AT_HWCAP);
}`
	edits := []struct {
		path, anchor, replacement string
	}{
		{filepath.Join(src, "os_linux.go"), init, initWithHWCap},
		{callbacks, callbackAnchor, callback},
		{filepath.Join(src, "cgo", "gcc_android.c"), cAnchor, cHook},
	}
	overlay := struct{ Replace map[string]string }{Replace: make(map[string]string)}
	for _, edit := range edits {
		data, err := os.ReadFile(edit.path)
		if err != nil {
			return "", err
		}
		source := string(data)
		if strings.Count(source, edit.anchor) != 1 {
			return "", fmt.Errorf("%s changed in this Go toolchain: %q", edit.path, edit.anchor)
		}
		path := filepath.Join(dir, filepath.Base(edit.path))
		err = os.WriteFile(path, []byte(strings.Replace(source, edit.anchor, edit.replacement, 1)), 0o644)
		if err != nil {
			return "", err
		}
		overlay.Replace[edit.path] = path
	}
	data, err := json.Marshal(overlay)
	if err != nil {
		return "", err
	}
	path := filepath.Join(dir, "overlay.json")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		return "", err
	}
	return path, nil
}
