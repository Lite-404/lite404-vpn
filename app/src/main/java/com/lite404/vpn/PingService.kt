package com.lite404.vpn

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.lite404.vpncore.Vpncore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Замер задержки до серверов — в отдельном процессе «:ping».
 *
 * Ядро (sing-box, Go) нельзя загружать в основной процесс: там уже работает WireGuard от WDTT (тоже Go).
 * Две среды Go в одном процессе падают с SIGILL — именно так вылетала версия 0.6.0.
 *
 * Вход: EXTRA_CONFIGS — JSON-массив конфигов серверов, EXTRA_URL, EXTRA_ID.
 * Выход: файл ping_<id>.json в папке приложения: {"0": мс, "1": -1, ..., "done": bool} —
 * перезаписывается после каждого ответившего сервера, чтобы пинг показывался сразу.
 */
class PingService : Service() {
    companion object {
        const val EXTRA_CONFIGS = "configs"
        const val EXTRA_URL = "url"
        const val EXTRA_ID = "id"

        fun resultFile(dir: File, id: String) = File(dir, "ping_$id.json")
    }

    private val pool = Executors.newFixedThreadPool(8)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configs = runCatching { JSONArray(intent?.getStringExtra(EXTRA_CONFIGS) ?: "[]") }.getOrDefault(JSONArray())
        val url = intent?.getStringExtra(EXTRA_URL) ?: Prefs.DEFAULT_PING_URL
        val id = intent?.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY.also { stopSelf(startId) }
        Thread {
            val n = configs.length()
            val result = JSONObject()
            val lock = Any()
            var finished = false // после итоговой записи опоздавшие замеры файл не трогают
            fun flush(done: Boolean) = synchronized(lock) {
                if (finished) return@synchronized
                finished = done
                result.put("done", done)
                val tmp = File(filesDir, "ping_$id.tmp")
                tmp.writeText(result.toString())
                tmp.renameTo(resultFile(filesDir, id))
            }
            runCatching { XrayCore.initEnv(this) }
            val left = CountDownLatch(n)
            for (i in 0 until n) {
                pool.execute {
                    // временное ядро только с выходом к серверу (его выбирает само ядро из конфига подписки)
                    val ms = runCatching { Vpncore.ping(configs.getString(i), url, 10_000) }
                        .getOrDefault(-1L).let { if (it <= 0) -1L else it }
                    synchronized(lock) { if (!finished) result.put(i.toString(), ms) }
                    runCatching { flush(false) }
                    left.countDown()
                }
            }
            left.await(25, TimeUnit.SECONDS)
            synchronized(lock) { for (i in 0 until n) if (!result.has(i.toString())) result.put(i.toString(), -1L) }
            runCatching { flush(true) }
            stopSelf(startId)
        }.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
