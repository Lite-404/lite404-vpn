package main

import _ "embed"

// Стоковый клиент WDTT (core/wdtt-client) под эту систему — собирается в pack перед программой.
//
//go:embed wdttbin/wdtt-client-windows-amd64.exe
var wdttClient []byte

const wdttClientName = "wdtt-client-windows-amd64.exe"
