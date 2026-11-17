package main

import (
	"bytes"
	"context"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"log/slog"
	"net/http"
	"path/filepath"
	"runtime"
	"sync/atomic"
	"time"

	"github.com/wailsapp/wails/v3/pkg/application"
	"github.com/wailsapp/wails/v3/pkg/events"
)

type desktopUI interface {
	Show()
	Hide()
	Quit()
}
type nativeAssetRequest struct{}
type nativeShell struct {
	app      *application.App
	window   *application.WebviewWindow
	quitting atomic.Bool
}

func (a *App) nativeUI() desktopUI { a.uiMu.RLock(); defer a.uiMu.RUnlock(); return a.ui }
func (s *nativeShell) Show()       { s.window.Restore(); s.window.Show(); s.window.Focus() }
func (s *nativeShell) Hide() {
	if runtime.GOOS == "linux" {
		s.window.Minimise()
	} else {
		s.window.Hide()
	}
}
func (s *nativeShell) Quit() { s.quitting.Store(true); s.app.Quit() }

// Native assets go directly to the system WebView, without a browser or HTTP navigation.
func (a *App) nativeAssets() http.Handler {
	handler := a.handler()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		request := r.Clone(context.WithValue(r.Context(), nativeAssetRequest{}, true))
		request.Host = uiAddr
		handler.ServeHTTP(w, request)
	})
}

func (a *App) runNativeUI(hidden bool) error {
	shell := &nativeShell{}
	appIcon := desktopIcon(color.RGBA{180, 255, 57, 255})
	gui := application.New(application.Options{
		Name: "Lite 404", Description: "Lite 404 VPN", Icon: appIcon,
		Assets:                      application.AssetOptions{Handler: a.nativeAssets(), DisableLogging: true},
		LogLevel:                    slog.LevelWarn,
		DisableDefaultSignalHandler: true,
		Windows:                     application.WindowsOptions{DisableQuitOnLastWindowClosed: true, WebviewUserDataPath: filepath.Join(dataDir(), "webview")},
		Linux:                       application.LinuxOptions{DisableQuitOnLastWindowClosed: true, ApplicationID: "com.lite404.vpn.desktop"},
		OnShutdown:                  func() { shell.quitting.Store(true); a.disconnect(); time.Sleep(300 * time.Millisecond) },
	})
	shell.app = gui
	window := gui.Window.NewWithOptions(application.WebviewWindowOptions{
		Name: "main", Title: "Lite 404", Width: 1060, Height: 800, MinWidth: 720, MinHeight: 620,
		BackgroundColour: application.NewRGB(11, 11, 12), Hidden: hidden && runtime.GOOS != "linux",
		Windows: application.WindowsWindow{Theme: application.Dark},
	})
	shell.window = window
	if hidden && runtime.GOOS == "linux" {
		// Keep a taskbar entry on desktops without a notification-area extension.
		gui.Event.OnApplicationEvent(events.Common.ApplicationStarted, func(*application.ApplicationEvent) { window.Minimise() })
	}
	window.RegisterHook(events.Common.WindowClosing, func(event *application.WindowEvent) {
		if !shell.quitting.Load() {
			event.Cancel()
			shell.Hide()
		}
	})
	a.uiMu.Lock()
	a.ui = shell
	a.uiMu.Unlock()
	defer func() { a.uiMu.Lock(); a.ui = nil; a.uiMu.Unlock() }()

	tray := gui.SystemTray.New()
	tray.SetIcon(appIcon)
	tray.SetTooltip("Lite 404 · VPN отключён")
	menu := gui.NewMenu()
	status := menu.Add("VPN отключён")
	status.SetEnabled(false)
	menu.AddSeparator()
	menu.Add("Открыть Lite 404").OnClick(func(*application.Context) { shell.Show() })
	connect := func(reserve bool) {
		go func() {
			a.mu.Lock()
			index := a.s.Server
			if reserve {
				index = a.s.WdttServer
			}
			a.mu.Unlock()
			var err error
			if reserve {
				err = a.connectWdtt(index)
			} else {
				err = a.connectXray(index)
			}
			if err != nil {
				a.mu.Lock()
				a.errText = err.Error()
				a.mu.Unlock()
				shell.Show()
			}
		}()
	}
	primary := menu.Add("Подключить основной VPN").OnClick(func(*application.Context) { connect(false) })
	reserve := menu.Add("Подключить резервный VPN").OnClick(func(*application.Context) { connect(true) })
	stop := menu.Add("Отключить VPN").OnClick(func(*application.Context) { go a.disconnect() })
	menu.AddSeparator()
	menu.Add("Выйти").OnClick(func(*application.Context) { shell.Quit() })
	tray.SetMenu(menu)
	if runtime.GOOS == "darwin" {
		tray.OnClick(func() { tray.OpenMenu() })
	} else {
		tray.OnClick(shell.Show)
	}
	tray.OnDoubleClick(shell.Show)
	tray.OnRightClick(func() { tray.OpenMenu() })
	go func() {
		previous := ""
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for !shell.quitting.Load() {
			a.mu.Lock()
			phase, mode := a.phase, a.mode
			a.mu.Unlock()
			text := "VPN отключён"
			if phase == "connecting" {
				text = "Подключение…"
			} else if phase == "on" {
				if mode == "wdtt" {
					text = "Резервный VPN подключён"
				} else {
					text = "Основной VPN подключён"
				}
			}
			if text != previous {
				previous = text
				status.SetLabel(text)
				tray.SetTooltip("Lite 404 · " + text)
				stop.SetEnabled(phase != "off")
				primary.SetEnabled(phase == "off" || mode != "xray")
				reserve.SetEnabled(phase == "off" || mode != "wdtt")
				menu.Update()
			}
			<-ticker.C
		}
	}()
	return gui.Run()
}

func desktopIcon(accent color.RGBA) []byte {
	img := image.NewRGBA(image.Rect(0, 0, 64, 64))
	for y := 0; y < 64; y++ {
		for x := 0; x < 64; x++ {
			dx, dy := 0, 0
			if x < 14 {
				dx = 14 - x
			} else if x > 49 {
				dx = x - 49
			}
			if y < 14 {
				dy = 14 - y
			} else if y > 49 {
				dy = y - 49
			}
			if dx*dx+dy*dy <= 196 {
				img.SetRGBA(x, y, color.RGBA{18, 19, 20, 255})
			}
		}
	}
	draw.Draw(img, image.Rect(16, 13, 26, 51), image.NewUniform(accent), image.Point{}, draw.Src)
	draw.Draw(img, image.Rect(16, 41, 49, 51), image.NewUniform(accent), image.Point{}, draw.Src)
	var out bytes.Buffer
	png.Encode(&out, img)
	return out.Bytes()
}
