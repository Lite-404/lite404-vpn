package com.lite404.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import java.io.File

/** Общее для ядра Xray: geo-файлы и замер задержки (пинг) без подключения. */
object XrayCore {
    @Volatile private var envReady = false

    /** geoip.dat / geosite.dat из ядра → в папку приложения (Xray читает их оттуда для правил маршрутизации). */
    fun geoDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "geo").apply { mkdirs() }
        val ver = File(dir, ".version")
        val want = BuildConfig.VERSION_CODE.toString()
        if (runCatching { ver.readText() }.getOrNull() != want) {
            for (n in listOf("geoip.dat", "geosite.dat")) {
                ctx.assets.open(n).use { inp -> File(dir, n).outputStream().use { inp.copyTo(it) } }
            }
            ver.writeText(want)
        }
        return dir
    }

    fun initEnv(ctx: Context) {
        if (envReady) return
        synchronized(this) {
            if (!envReady) {
                Libv2ray.initCoreEnv(geoDir(ctx).absolutePath, "")
                envReady = true
            }
        }
    }

    /**
     * Задержка до сервера: полный запрос [url] через этот сервер (рукопожатие REALITY + HTTP), мс.
     * Приложение само исключено из VPN, поэтому замер идёт напрямую к серверу и при включённом туннеле.
     * -1 — сервер не ответил.
     */
    suspend fun ping(ctx: Context, serverJson: String, url: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            initEnv(ctx)
            Libv2ray.measureOutboundDelay(XrayConfig.forPing(serverJson), url)
        }.getOrDefault(-1L).let { if (it <= 0) -1L else it }
    }

    /** Пинг всех серверов параллельно (не больше 4 одновременно). Ключ — индекс сервера. */
    suspend fun pingAll(ctx: Context, servers: List<XrayServer>, url: String): Map<Int, Long> = coroutineScope {
        val gate = Semaphore(4)
        servers.mapIndexed { i, s -> async { gate.withPermit { i to ping(ctx, s.config, url) } } }.awaitAll().toMap()
    }
}
