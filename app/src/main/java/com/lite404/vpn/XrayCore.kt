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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal enum class PingMethod { HTTP_GET, ICMP, TCP }

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
    internal suspend fun pingAll(
        ctx: Context, servers: List<XrayServer>, url: String, method: PingMethod, onResult: (Int, Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext
        if (method != PingMethod.HTTP_GET) {
            val slots = Semaphore(8)
            servers.mapIndexed { i, server ->
                async {
                    val ms = slots.withPermit { if (method == PingMethod.ICMP) icmpPing(server) else tcpPing(server) }
                    withContext(Dispatchers.Main) { onResult(i, ms) }
                }
            }.awaitAll()
            return@withContext
        }
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

    private fun endpoint(server: XrayServer): Pair<String, Int> {
        val outbounds = JSONObject(server.config).getJSONArray("outbounds")
        return (0 until outbounds.length()).firstNotNullOf { i ->
            val settings = outbounds.optJSONObject(i)?.optJSONObject("settings")
            val node = settings?.optJSONArray("vnext")?.optJSONObject(0)
                ?: settings?.optJSONArray("servers")?.optJSONObject(0)
            val host = node?.optString("address").orEmpty()
            val port = node?.optInt("port") ?: 0
            if (host.isNotBlank() && port in 1..65535) host to port else null
        }
    }

    private fun tcpPing(server: XrayServer): Long = runCatching {
        val endpoint = endpoint(server)
        val address = InetSocketAddress(endpoint.first, endpoint.second)
        Socket().use { socket ->
            val started = SystemClock.elapsedRealtime()
            socket.connect(address, 4_000)
            (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
        }
    }.getOrDefault(-1)

    private fun icmpPing(server: XrayServer): Long = runCatching {
        val host = endpoint(server).first
        check(!host.startsWith("-") && host.none { it.isWhitespace() })
        val process = ProcessBuilder("/system/bin/ping", "-n", "-c", "1", "-W", "3", host)
            .redirectErrorStream(true).start()
        try {
            if (!process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) return@runCatching -1L
            val result = process.inputStream.bufferedReader().readText()
            Regex("""time[=<]\s*([0-9.]+)""").find(result)?.groupValues?.get(1)?.toDoubleOrNull()
                ?.let { kotlin.math.ceil(it).toLong().coerceAtLeast(1) } ?: -1L
        } finally {
            process.destroyForcibly()
        }
    }.getOrDefault(-1)
}
