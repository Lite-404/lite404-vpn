#!/usr/bin/env bash
# Сборка ядра sing-box-extended для Android: app/libs/vpncore.aar (gomobile bind).
# Нужны Go 1.26+, Android SDK и NDK; gomobile/gobind: go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
# Пример: ANDROID_HOME=~/android-sdk ANDROID_NDK_HOME=~/android-sdk/ndk/28.2.13676358 ./build-aar.sh
set -euo pipefail
cd "$(dirname "$0")"
OUT="${OUT:-../../app/libs/vpncore.aar}"
TARGETS="${TARGETS:-android/arm64,android/arm,android/amd64}"
# Набор частей sing-box: TUN на gvisor, uTLS/Reality, WireGuard; badlinkname — как у официальной сборки
TAGS="with_gvisor,with_utls,with_wireguard,badlinkname,tfogo_checklinkname0"
VERSION="$(go list -m -f '{{.Replace.Version}}' github.com/sagernet/sing-box)"
gomobile bind -v -target "$TARGETS" -androidapi 28 -javapkg com.lite404 -tags "$TAGS" -trimpath \
  -ldflags "-s -w -buildid= -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=${VERSION#v}" \
  -o "$OUT" ./vpncore
ls -la "$OUT"
