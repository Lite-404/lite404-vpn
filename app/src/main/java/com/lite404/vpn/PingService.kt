package com.lite404.vpn

import android.app.Service
import android.content.Intent
import android.os.IBinder
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Замер задержки до серверов — в отдельном процессе «:ping».
 *
 * Ядро Xray (Go) нельзя загружать в основной процесс: там уже работает WireGuard от WDTT (тоже Go).
 * Две среды Go в одном процессе падают с SIGILL — именно так вылетала версия 0.6.0.
 *
 * Вход: EXTRA_CONFIGS — JSON-массив конфигов серверов, EXTRA_URL, EXTRA_ID.
 * Выход: файл ping_<id>.json в папке приложения: {"0": мс, "1": -1, ...}.
 */
class PingService : Service() {
    companion object {
        const val EXTRA_CONFIGS = "configs"
        const val EXTRA_URL = "url"
        const val EXTRA_ID = "id"

        fun resultFile(dir: File, id: String) = File(dir, "ping_$id.json")
    }

    private val pool = Executors.newFixedThreadPool(4)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configs = runCatching { JSONArray(intent?.getStringExtra(EXTRA_CONFIGS) ?: "[]") }.getOrDefault(JSONArray())
        val url = intent?.getStringExtra(EXTRA_URL) ?: Prefs.DEFAULT_PING_URL
        val id = intent?.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY.also { stopSelf(startId) }
        Thread {
            val result = JSONObject()
            runCatching { XrayCore.initEnv(this) }
            val jobs = (0 until configs.length()).map { i ->
                pool.submit<Long> {
                    runCatching { Libv2ray.measureOutboundDelay(XrayConfig.forPing(configs.getString(i)), url) }
                        .getOrDefault(-1L).let { if (it <= 0) -1L else it }
                }
            }
            jobs.forEachIndexed { i, f -> result.put(i.toString(), runCatching { f.get(20, TimeUnit.SECONDS) }.getOrDefault(-1L)) }
            val tmp = File(filesDir, "ping_$id.tmp")
            tmp.writeText(result.toString())
            tmp.renameTo(resultFile(filesDir, id))
            stopSelf(startId)
        }.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
