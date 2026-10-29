// Сборка релизных архивов для всех систем: go run ./pack 0.7.0 → dist/.
// Архивы пишутся из Go, чтобы на Windows у файлов Linux/macOS стояли права на запуск.
package main

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

type entry struct {
	name string
	data []byte
	exec bool
}

const plist = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleName</key><string>Lite404 VPN</string>
<key>CFBundleDisplayName</key><string>Lite404 VPN</string>
<key>CFBundleIdentifier</key><string>com.lite404.vpn.desktop</string>
<key>CFBundleExecutable</key><string>lite404</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>%s</string>
<key>CFBundleVersion</key><string>%s</string>
<key>LSMinimumSystemVersion</key><string>11.0</string>
<key>LSUIElement</key><true/>
</dict></plist>
`

func build(goos, goarch, out, version string) []byte {
	ldflags := "-s -w -X main.version=" + version
	if goos == "windows" {
		ldflags += " -H windowsgui" // без чёрного окна консоли
	}
	cmd := exec.Command("go", "build", "-trimpath", "-ldflags", ldflags, "-o", out, ".")
	cmd.Env = append(os.Environ(), "GOOS="+goos, "GOARCH="+goarch, "CGO_ENABLED=0")
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		panic(fmt.Sprintf("%s/%s: %v", goos, goarch, err))
	}
	data, err := os.ReadFile(out)
	if err != nil {
		panic(err)
	}
	return data
}

func writeZip(path string, files []entry) {
	f, _ := os.Create(path)
	defer f.Close()
	z := zip.NewWriter(f)
	for _, e := range files {
		h := &zip.FileHeader{Name: e.name, Method: zip.Deflate, Modified: time.Now()}
		h.SetMode(0o644)
		if e.exec {
			h.SetMode(0o755)
		}
		w, _ := z.CreateHeader(h)
		w.Write(e.data)
	}
	z.Close()
}

func writeTarGz(path string, files []entry) {
	f, _ := os.Create(path)
	defer f.Close()
	gz := gzip.NewWriter(f)
	t := tar.NewWriter(gz)
	for _, e := range files {
		mode := int64(0o644)
		if e.exec {
			mode = 0o755
		}
		t.WriteHeader(&tar.Header{Name: e.name, Mode: mode, Size: int64(len(e.data)), Typeflag: tar.TypeReg, ModTime: time.Now()})
		t.Write(e.data)
	}
	t.Close()
	gz.Close()
}

// prepare — то, что встраивается в программу: стоковый клиент WDTT под каждую систему
// (как libclient.so в Android-приложении) и переводы интерфейса из Android-приложения.
func prepare() {
	os.MkdirAll("wdttbin", 0o755)
	for _, t := range [][2]string{{"windows", "amd64"}, {"linux", "amd64"}, {"linux", "arm64"}, {"darwin", "amd64"}, {"darwin", "arm64"}} {
		out, _ := filepath.Abs(fmt.Sprintf("wdttbin/wdtt-client-%s-%s", t[0], t[1]))
		if t[0] == "windows" {
			out += ".exe"
		}
		cmd := exec.Command("go", "build", "-trimpath", "-ldflags", "-s -w -checklinkname=0", "-o", out, ".")
		cmd.Dir = "../core/wdtt-client"
		cmd.Env = append(os.Environ(), "GOOS="+t[0], "GOARCH="+t[1], "CGO_ENABLED=0", "GOFLAGS=")
		cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
		if err := cmd.Run(); err != nil {
			panic(fmt.Sprintf("клиент WDTT %s/%s: %v", t[0], t[1], err))
		}
	}
	files, _ := filepath.Glob("../app/src/main/assets/i18n/*.tsv")
	for _, f := range files {
		data, _ := os.ReadFile(f)
		// строки, которые есть только в версии для компьютера
		if extra, err := os.ReadFile(filepath.Join("i18n-extra", filepath.Base(f))); err == nil {
			data = append(append(data, 10), extra...)
		}
		os.WriteFile(filepath.Join("web/i18n", filepath.Base(f)), data, 0o644)
	}
}

func main() {
	version := os.Args[1]
	prepare()
	readme, _ := os.ReadFile("README.txt")
	os.MkdirAll("dist/bin", 0o755)
	tmp := func(name string) string { return filepath.Join("dist", "bin", name) }
	readmeEntry := func(prefix string) entry { return entry{name: prefix + "README.txt", data: readme} }

	exe := build("windows", "amd64", tmp("lite404.exe"), version)
	writeZip(fmt.Sprintf("dist/Lite404-VPN-windows-x64.zip"), []entry{
		{name: "Lite404 VPN/Lite404 VPN.exe", data: exe}, readmeEntry("Lite404 VPN/"),
	})
	for _, arch := range []string{"amd64", "arm64"} {
		bin := build("linux", arch, tmp("lite404-linux-"+arch), version)
		label := map[string]string{"amd64": "x64", "arm64": "arm64"}[arch]
		dir := "lite404-vpn/"
		writeTarGz(fmt.Sprintf("dist/Lite404-VPN-linux-%s.tar.gz", label), []entry{
			{name: dir + "lite404-vpn", data: bin, exec: true}, readmeEntry(dir),
		})
	}
	for _, arch := range []string{"arm64", "amd64"} {
		bin := build("darwin", arch, tmp("lite404-darwin-"+arch), version)
		label := map[string]string{"amd64": "intel", "arm64": "apple-silicon"}[arch]
		app := "Lite404 VPN.app/Contents/"
		writeZip(fmt.Sprintf("dist/Lite404-VPN-macos-%s.zip", label), []entry{
			{name: app + "MacOS/lite404", data: bin, exec: true},
			{name: app + "Info.plist", data: []byte(fmt.Sprintf(plist, version, version))},
			readmeEntry(""),
		})
	}
	os.RemoveAll("dist/bin")
	list, _ := os.ReadDir("dist")
	for _, e := range list {
		if st, err := e.Info(); err == nil && strings.HasPrefix(e.Name(), "Lite404-VPN-") {
			fmt.Printf("%-48s %5.1f МБ\n", e.Name(), float64(st.Size())/1e6)
		}
	}
}
