package com.lite404.vpn

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import org.json.JSONObject

/** Notification actions get their own short foreground lifetime, including from
 * the lock screen. No foreground service, locks or transport remain while paused. */
class VpnControlService : Service() {
    companion object {
        const val PAUSE = "com.lite404.vpn.PAUSE"
        const val RESUME = "com.lite404.vpn.RESUME"
        const val STOP = "com.lite404.vpn.STOP"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var stopRequested = false
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = runCatching { VpnMode.valueOf(intent?.getStringExtra("mode").orEmpty()) }.getOrDefault(VpnMode.XRAY)
        val name = intent?.getStringExtra("server").orEmpty()
        VpnNotifications.channel(this)
        val starting = VpnNotifications.build(this, VpnNotifications.CONTROL_CHANNEL, mode, name,
            tr(if (intent?.action == RESUME) "Подключение..." else "Остановка..."), controls = false)
        if (Build.VERSION.SDK_INT >= 34) startForeground(VpnNotifications.PAUSED_ID, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(VpnNotifications.PAUSED_ID, starting)
        if (operation?.isActive == true) {
            if (intent?.action == STOP) stopRequested = true
            return START_NOT_STICKY
        }
        stopRequested = false
        operation = scope.launch {
            var keepNotice = false
            val pausedBefore = Prefs(this@VpnControlService).notificationPause
            try {
                val prefs = Prefs(this@VpnControlService)
                when (intent?.action) {
                    PAUSE -> {
                        // Ignore a stale button from the other VPN, rather than stopping it.
                        val actual = Connection.state.value
                        val desired = if (mode == VpnMode.WDTT) "wdtt" else "xray"
                        if (prefs.desiredMode != desired || (actual.mode != null && actual.mode != mode)) return@launch
                        Connection.disconnect(this@VpnControlService)?.join()
                        if (prefs.desiredMode.isNotEmpty()) return@launch
                        prefs.notificationPause = JSONObject().put("mode", mode.name).put("server", name).toString()
                        VpnJournal.record(this@VpnControlService, mode, "VPN: пауза из уведомления")
                        keepNotice = true
                    }
                    RESUME -> {
                        val saved = runCatching { JSONObject(prefs.notificationPause) }.getOrNull() ?: return@launch
                        if (saved.optString("mode") != mode.name || prefs.desiredMode.isNotEmpty()) return@launch
                        check(VpnService.prepare(this@VpnControlService) == null) { tr("Откройте приложение и разрешите подключение VPN") }
                        check(prefs.token.isNotBlank()) { tr("Откройте приложение и войдите в аккаунт") }
                        val config = AppConfig.parse(prefs.configJson, prefs.configAt)
                        check(config.error == null) { AppApi.errorText(config.error) ?: tr("Обновите подписку в приложении") }
                        val job = if (mode == VpnMode.WDTT) {
                            val server = config.wdtt.getOrNull(prefs.wdttIndex) ?: error(tr("Нет серверов"))
                            check(config.vkHash.isNotBlank()) { tr("Добавьте хеш VK в боте") }
                            Connection.connectWdtt(this@VpnControlService, server, config.vkHash, cachedGeoOnly = true)
                        } else {
                            val server = config.xray.getOrNull(prefs.xrayIndex) ?: error(tr("Нет серверов"))
                            Connection.connectXray(this@VpnControlService, server,
                                Routing.profile(config, prefs.routingMode), Routing.profile(config, Routing.MODE_SMART), cachedGeoOnly = true)
                        }
                        check(job != null) { tr("Дождитесь завершения текущей операции") }
                        job.join()
                        val state = Connection.state.value
                        check(state.phase != Phase.OFF) { state.error ?: tr("Не удалось подключиться к VPN") }
                    }
                    STOP -> Connection.disconnect(this@VpnControlService)?.join()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                VpnJournal.record(this@VpnControlService, mode, "VPN: действие из уведомления не выполнено · ${e.message}")
                if (intent?.action == RESUME && pausedBefore.isNotBlank() && Connection.state.value.phase == Phase.OFF) {
                    Prefs(this@VpnControlService).apply {
                        desiredMode = ""
                        notificationPause = pausedBefore
                    }
                }
                keepNotice = Prefs(this@VpnControlService).notificationPause.isNotBlank()
            } finally {
                if (stopRequested) {
                    Connection.disconnect(this@VpnControlService)?.join()
                    keepNotice = false
                    VpnNotifications.clearPaused(this@VpnControlService)
                }
                stopForeground(if (keepNotice) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
                if (keepNotice) getSystemService(NotificationManager::class.java).notify(VpnNotifications.PAUSED_ID,
                    VpnNotifications.build(this@VpnControlService, VpnNotifications.CONTROL_CHANNEL, mode, name, tr("Остановлено"), paused = true))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
