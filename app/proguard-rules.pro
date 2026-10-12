# ядро Xray (gomobile)
-keep class libv2ray.** { *; }
-keep class go.** { *; }
# WireGuard (движок WDTT)
-keep class com.wireguard.** { *; }
-dontwarn com.wireguard.**
# код приложения (вызывается из WebView/JS и по рефлексии в движке WDTT)
-keep class com.lite404.vpn.** { *; }
