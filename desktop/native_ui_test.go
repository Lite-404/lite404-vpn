package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func nativeTestApp() *App {
	lan := true
	return &App{s: &Settings{LanDirect: &lan}, wdtt: &Wdtt{}, phase: "off"}
}

func TestNativeReadyRejectsNetworkRequest(t *testing.T) {
	a := nativeTestApp()
	request := httptest.NewRequest("POST", "http://"+uiAddr+"/api/window/ready", nil)
	request.Header.Set("X-Lite404", "1")
	result := httptest.NewRecorder()
	a.handler().ServeHTTP(result, request)
	if result.Code != http.StatusForbidden || a.uiReady.Load() {
		t.Fatal("network caller marked native view ready")
	}
	result = httptest.NewRecorder()
	a.nativeAssets().ServeHTTP(result, request)
	if result.Code != http.StatusOK || !a.uiReady.Load() {
		t.Fatalf("native view failed to acknowledge readiness: %d", result.Code)
	}
}

func TestNativeAssetsUseInternalHost(t *testing.T) {
	a := nativeTestApp()
	request := httptest.NewRequest("GET", "http://wails.localhost/api/state", nil)
	result := httptest.NewRecorder()
	a.nativeAssets().ServeHTTP(result, request)
	if result.Code != http.StatusOK {
		t.Fatalf("internal native request rejected: %d", result.Code)
	}
	if request.Host != "wails.localhost" {
		t.Fatal("original request was changed")
	}
	result = httptest.NewRecorder()
	a.handler().ServeHTTP(result, request)
	if result.Code != http.StatusForbidden {
		t.Fatal("native host accepted by external control handler")
	}
}
