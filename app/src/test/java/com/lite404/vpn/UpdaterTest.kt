package com.lite404.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdaterTest {
    private val sha = "a".repeat(64)
    private fun file(name: String, size: Long = 100) =
        "\"$name\":{\"url\":\"https://github.com/Lite-404/lite404-vpn/releases/download/v0.9.0/$name\",\"size\":$size,\"sha256\":\"$sha\"}"

    private val manifest = """{"version":"0.9.0","android_code":21,"tag":"v0.9.0","notes":"Что нового","files":{
        ${file("Lite404-VPN-android-arm64.apk", 111)},
        ${file("Lite404-VPN-android-armv7.apk", 222)},
        ${file("Lite404-VPN-android-universal.apk", 333)},
        ${file("Lite404-VPN-windows-x64.zip")}}}"""

    @Test fun apkByProcessor() {
        assertEquals("Lite404-VPN-android-arm64.apk", Updater.apkName(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("Lite404-VPN-android-armv7.apk", Updater.apkName(arrayOf("armeabi-v7a")))
        assertEquals("Lite404-VPN-android-universal.apk", Updater.apkName(arrayOf("x86_64")))
        assertEquals("Lite404-VPN-android-universal.apk", Updater.apkName(emptyArray()))
    }

    @Test fun parseManifest() {
        val r = Updater.parse(manifest, arrayOf("arm64-v8a"))
        assertEquals("0.9.0", r.version)
        assertEquals(21, r.code)
        assertEquals("Что нового", r.notes)
        assertEquals("Lite404-VPN-android-arm64.apk", r.file)
        assertEquals(111L, r.size)
        assertEquals(sha, r.sha256)
        assertEquals("https://github.com/Lite-404/lite404-vpn/releases/download/v0.9.0/Lite404-VPN-android-arm64.apk", r.url)
        assertEquals(333L, Updater.parse(manifest, arrayOf("x86")).size)
    }

    @Test fun missingApkFallsBackToUniversal() {
        val noArm = manifest.replace(file("Lite404-VPN-android-armv7.apk", 222) + ",", "")
        val r = Updater.parse(noArm, arrayOf("armeabi-v7a"))
        assertEquals("Lite404-VPN-android-universal.apk", r.file)
    }

    @Test fun badHashRejected() {
        val bad = manifest.replace(sha, "1234")
        assertThrows(IllegalArgumentException::class.java) { Updater.parse(bad, arrayOf("arm64-v8a")) }
    }
}
