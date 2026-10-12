package com.lite404.vpn

import android.content.Context
import android.content.Intent
import android.os.Build

/** Запуск/остановка аварийного туннеля WDTT (стоковый движок WDTT). */
object WdttController {
    const val WORKERS = 27
    const val LISTEN_PORT = 9000

    /** Момент, когда туннель WDTT подключился (мс), или 0. Живёт, пока жив процесс приложения. */
    @Volatile var since = 0L

    suspend fun start(ctx: Context, server: WdttServer, vkHash: String) {
        val peer = "${server.host}:${server.port}"
        val store = SettingsStore(ctx)
        store.save(peer, vkHash, "", WORKERS, "udp", LISTEN_PORT)
        store.saveConnectionPassword(server.password)
        // туннелирование по приложениям — те же настройки, что у обычного VPN
        val prefs = Prefs(ctx)
        val mode = prefs.effectiveAppsMode()
        store.saveExceptionsMode(if (mode == "off") "" else prefs.apps.joinToString(","), mode == "include")
        val i = Intent(ctx, TunnelService::class.java).setAction("START")
            .putExtra("peer", peer)
            .putExtra("vk_hashes", vkHash)
            .putExtra("connection_password", server.password)
            .putExtra("workers_per_hash", WORKERS)
            .putExtra("port", LISTEN_PORT)
            .putExtra("protocol", "udp")
            .putExtra("obfs_mode", "audio")
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            CrashLog.save(e, "WDTT: не удалось запустить службу (без вылета)")
            throw IllegalStateException(tr("Система не дала запустить туннель. Откройте приложение и попробуйте снова"), e)
        }
    }

    /** Остановить WDTT и дождаться, пока туннель освободит VPN. */
    suspend fun stopAndWait(ctx: Context) {
        if (!TunnelManager.running.value) return
        stop(ctx)
        repeat(50) {
            if (!TunnelManager.running.value) return
            kotlinx.coroutines.delay(100)
        }
        TunnelManager.stopAndWait()
    }

    fun stop(ctx: Context) {
        if (!TunnelManager.running.value) return
        try {
            ctx.startService(Intent(ctx, TunnelService::class.java).setAction("STOP"))
        } catch (e: Exception) {
            // службу не дали вызвать — останавливаем туннель напрямую
            TunnelManager.stop()
        }
    }
}
