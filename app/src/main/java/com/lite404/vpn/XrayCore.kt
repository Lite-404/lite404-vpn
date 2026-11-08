package com.lite404.vpn

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.lite404.vpncore.Vpncore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Общее для ядра (sing-box-extended, библиотека vpncore): geo-файлы и пинг. Конфиги серверов приходят
 * в формате Xray (JSON-подписка 3x-ui); в формат sing-box их переводит само ядро (Vpncore.convert).
 *
 * ВАЖНО: функции, которые вызывают Vpncore (initEnv), можно использовать только в процессах «:xray» и «:ping».
 * В основном процессе работает WireGuard (тоже Go) — вторая среда Go в том же процессе роняет приложение.
 */
object XrayCore {
    const val VERSION_FILE = "xray_version"
    @Volatile private var envReady = false

    /**
     * Папка geo-файлов профиля маршрутизации (roscom-geoip.dat / roscom-geosite.dat качает [Routing.ensureGeo]).
     * Стандартные geoip.dat/geosite.dat больше не нужны: geoip:private ядро понимает само.
     */
    fun geoDir(ctx: Context): File = File(ctx.filesDir, "geo").apply { mkdirs() }

    /** Только для процессов «:xray» и «:ping». */
    fun initEnv(ctx: Context) {
        if (envReady) return
        synchronized(this) {
            if (!envReady) {
                geoDir(ctx)
                envReady = true
                // версию ядра показывает экран настроек (сам он ядро загружать не может)
                runCatching { File(ctx.filesDir, VERSION_FILE).writeText(Vpncore.version()) }
            }
        }
    }

    /**
     * Пинг всех серверов (полный запрос [url] через каждый сервер), мс; -1 — нет ответа.
     * Замер делает служба в процессе «:ping»; здесь читаем её файл и отдаём каждый результат
     * в [onResult] (в главном потоке) сразу, как сервер ответил, не дожидаясь остальных.
     */
    suspend fun pingAll(ctx: Context, servers: List<XrayServer>, url: String, onResult: (Int, Long) -> Unit) = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext
        val app = ctx.applicationContext
        val id = UUID.randomUUID().toString().take(8)
        val file = PingService.resultFile(app.filesDir, id)
        val configs = JSONArray().apply { servers.forEach { put(it.config) } }
        val reported = HashSet<Int>()
        suspend fun report(i: Int, ms: Long) {
            if (reported.add(i)) withContext(Dispatchers.Main) { onResult(i, ms) }
        }
        val started = runCatching {
            app.startService(Intent(app, PingService::class.java)
                .putExtra(PingService.EXTRA_CONFIGS, configs.toString())
                .putExtra(PingService.EXTRA_URL, url)
                .putExtra(PingService.EXTRA_ID, id))
        }.isSuccess
        if (started) {
            val end = System.currentTimeMillis() + 30_000
            var done = false
            while (!done && System.currentTimeMillis() < end) {
                delay(150)
                val json = if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrNull() else null
                if (json != null) {
                    for (i in servers.indices) if (json.has(i.toString())) report(i, json.optLong(i.toString(), -1L))
                    done = json.optBoolean("done")
                }
            }
            file.delete()
        }
        for (i in servers.indices) report(i, -1L)
    }
}
