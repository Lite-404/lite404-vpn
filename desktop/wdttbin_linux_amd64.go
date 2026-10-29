package main

import _ "embed"

// Стоковый клиент WDTT (core/wdtt-client) под эту систему — собирается в pack перед программой.
//
//go:embed wdttbin/wdtt-client-linux-amd64
var wdttClient []byte

const wdttClientName = "wdtt-client-linux-amd64"
