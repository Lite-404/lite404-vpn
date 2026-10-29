//go:build !((windows && amd64) || (linux && (amd64 || arm64)) || (darwin && (amd64 || arm64)))

package main

var wdttClient []byte

const wdttClientName = ""
