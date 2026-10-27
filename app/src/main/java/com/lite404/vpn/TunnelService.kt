package com.lite404.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TUNNEL_NOTIFICATION_CHANNEL_ID = "wdtt_tunnel_v4"
private const val TUNNEL_NOTIFICATION_ID = 1
private const val NETWORK_SETTLE_MS = 2_000L
private const val RECOVERY_CHECK_MS = 25_000L
private const val LONG_LOSS_MS = 45_000L

class TunnelService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var updateJob: Job? = null
    private var lastNotificationText: String? = null
    
    
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // Сеть, через которую сейчас идёт туннель (Wi-Fi или мобильная); null — пропала
    private var currentNetwork: Network? = null
    private var lostNetwork: Network? = null
    /** Последняя известная сеть (не сбрасывается при потере): отличает «та же вернулась» от «сменилась». */
    private var lastNetwork: Network? = null
    private var networkJob: Job? = null
    private var isTunnelPaused = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        acquireWakeLock()
        setupNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Система убила процесс во сне и перезапустила службу. Как в оригинальном WDTT — поднимаем
            // туннель снова, но только если пользователь не выключал WDTT и не переключился на обычный VPN.
            // startForeground — первым делом: без него Android роняет приложение через несколько секунд.
            val wanted = Prefs(this).desiredMode == "wdtt"
            if (!startPersistentForeground(createNotification(tr("Восстановление соединения...")))) {
                stopTunnel()
                return START_NOT_STICKY
            }
            if (wanted) {
                restoreTunnel()
                return START_STICKY
            }
            stopTunnel()
            return START_NOT_STICKY
        }

        when (intent.action) {
            "START" -> {
                val notification = createNotification(tr("Запуск..."))
                if (!startPersistentForeground(notification)) {
                    stopTunnel()
                    return START_NOT_STICKY
                }

                val params = TunnelParams(
                    peer = intent.getStringExtra("peer") ?: "",
                    vkHashes = intent.getStringExtra("vk_hashes") ?: "",
                    secondaryVkHash = intent.getStringExtra("secondary_vk_hash") ?: "",
                    workersPerHash = intent.getIntExtra("workers_per_hash", 16),
                    port = intent.getIntExtra("port", 9000),
                    sni = intent.getStringExtra("sni") ?: "",
                    connectionPassword = intent.getStringExtra("connection_password") ?: "",
                    protocol = intent.getStringExtra("protocol") ?: "udp",
                    vkAuthMode = sanitizeVkAuthMode(intent.getStringExtra("vk_auth_mode")),
                    captchaMode = sanitizeCaptchaMode(intent.getStringExtra("captcha_mode")),
                    captchaSolveMethod = intent.getStringExtra("captcha_solve_method") ?: "auto",
                    fingerprint = intent.getStringExtra("fingerprint") ?: "firefox",
                    clientIds = intent.getStringExtra("client_ids") ?: "8202606,6287487",
                    obfsMode = intent.getStringExtra("obfs_mode")?.takeIf { it.isNotBlank() } ?: "audio"
                )
                startTunnel(params)
            }
            "STOP" -> stopTunnel()
        }
        return START_STICKY
    }

    private fun restoreTunnel() {
        val notification = createNotification(tr("Восстановление соединения..."))
        startPersistentForeground(notification)
        
        val appContext = applicationContext
        TunnelManager.scope.launch {
            try {
                val store = SettingsStore(appContext)
                val basePeer = store.peer.first()
                val manualPortsEnabled = store.manualPortsEnabled.first()
                val serverDtlsPort = if (manualPortsEnabled) store.serverDtlsPort.first() else 56000
                val peerWithPort = if (basePeer.isBlank() || basePeer.contains(":")) basePeer else "$basePeer:$serverDtlsPort"
                val params = TunnelParams(
                    peer = peerWithPort,
                    vkHashes = store.vkHashes.first(),
                    secondaryVkHash = store.secondaryVkHash.first(),
                    workersPerHash = store.workersPerHash.first(),
                    port = store.listenPort.first(),
                    sni = store.sni.first(),
                    connectionPassword = store.connectionPassword.first(),
                    vkAuthMode = sanitizeVkAuthMode(store.vkAuthMode.first()),
                    captchaMode = sanitizeCaptchaMode(store.captchaMode.first()),
                    captchaSolveMethod = store.captchaSolveMethod.first(),
                    fingerprint = store.selectedFingerprint.first(),
                    clientIds = store.activeClientIds.first()
                )
                if (params.peer.isNotEmpty() && params.vkHashes.isNotEmpty()) {
                    launch(Dispatchers.Main) {
                        startTunnel(params)
                    }
                } else {
                    launch(Dispatchers.Main) {
                        stopTunnel()
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    stopTunnel()
                }
            }
        }
    }

    private fun startTunnel(params: TunnelParams) {
        updateNotification(tr("Подключение..."))
        acquireWakeLock()
        acquireWifiLock()

        
        
        CaptchaWebViewManager.onTunnelStart(applicationContext)

        TunnelManager.start(this, params)
        startStatsUpdater()
    }

    private fun stopTunnel() {
        updateJob?.cancel()
        networkJob?.cancel()
        // остановлен по-настоящему — после перезапуска процесса системой не восстанавливать
        Prefs(this).let { if (it.desiredMode == "wdtt") it.desiredMode = "" }

        
        CaptchaWebViewManager.onTunnelStop()

        TunnelManager.stop()
        releaseWakeLock()
        releaseWifiLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Следим только за сетью по умолчанию (приложение исключено из VPN, поэтому это Wi-Fi или мобильная).
     *  • Кратковременная пропажа сети (экран выключен, Wi-Fi уснул, смена вышки) — процесс НЕ трогаем:
     *    потоки WDTT переподключаются сами, а полный перезапуск означал бы новые креды VK и капчу.
     *  • Сети нет дольше [LONG_LOSS_MS] — приостанавливаем (экономим батарею), при возврате поднимаем заново.
     *  • Сеть сменилась (Wi-Fi ↔ мобильная) — мягкий перезапуск транспорта, туннель WireGuard не трогаем.
     *  • Та же сеть вернулась — через [RECOVERY_CHECK_MS] проверяем, ожили ли потоки; нет — перезапуск транспорта.
     */
    private fun setupNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        currentNetwork = cm.activeNetwork?.takeIf {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false
        }
        lastNetwork = currentNetwork
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                TunnelManager.scope.launch(Dispatchers.Main) { onNetworkAvailable(network) }
            }

            override fun onLost(network: Network) {
                TunnelManager.scope.launch(Dispatchers.Main) { onNetworkLost(network) }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e("TunnelService", "Не удалось подписаться на смену сети", e)
        }
    }

    private fun onNetworkAvailable(network: Network) {
        val wasLost = lostNetwork != null
        val previous = lastNetwork
        currentNetwork = network
        lastNetwork = network
        lostNetwork = null
        TunnelManager.networkAvailable = true
        if (isTunnelPaused) {
            networkJob?.cancel()
            isTunnelPaused = false
            Log.d("TunnelService", "Сеть вернулась после долгой паузы — поднимаем туннель")
            TunnelManager.resume()
            updateNotification(tr("Подключение..."))
            return
        }
        // Android присылает onAvailable сразу после подписки и при любых мелочах той же сети.
        // Перезапуск Go-клиента — дорогой (новые креды VK; старые потоки ещё висят на сервере и
        // вместе с новыми упираются в лимит), поэтому трогаем транспорт только при реальной смене
        // сети или если после настоящей потери сети потоки так и не ожили.
        if (previous == null || (previous == network && !wasLost)) return
        if (!TunnelManager.running.value) return
        networkJob?.cancel()
        networkJob = TunnelManager.scope.launch(Dispatchers.Main) {
            if (previous != network) {
                delay(NETWORK_SETTLE_MS)
                Log.d("TunnelService", "Сеть сменилась — мягкий перезапуск транспорта")
                TunnelManager.restartTransport()
            } else {
                delay(RECOVERY_CHECK_MS)
                if (TunnelManager.running.value && TunnelManager.activeWorkers.value == 0 &&
                    !ManlCaptchaWebViewManager.isCaptchaPending
                ) {
                    Log.d("TunnelService", "Потоки не ожили после возврата сети — перезапуск транспорта")
                    TunnelManager.restartTransport()
                }
            }
        }
    }

    private fun onNetworkLost(network: Network) {
        if (network != currentNetwork) return
        currentNetwork = null
        lostNetwork = network
        TunnelManager.networkAvailable = false
        networkJob?.cancel()
        networkJob = TunnelManager.scope.launch(Dispatchers.Main) {
            delay(LONG_LOSS_MS)
            if (currentNetwork == null && TunnelManager.running.value && !isTunnelPaused) {
                isTunnelPaused = true
                Log.d("TunnelService", "Сети нет дольше ${LONG_LOSS_MS / 1000} с — приостанавливаем туннель")
                TunnelManager.pause()
                updateNotification(tr("Ожидание сети (Фоновый сон)"))
            }
        }
    }

    private fun sanitizeCaptchaMode(mode: String?): String {
        return when (mode?.lowercase()) {
            "auto" -> "auto"
            "rjs" -> "rjs"
            "wv" -> "wv"
            else -> "auto"
        }
    }

    private fun sanitizeVkAuthMode(mode: String?): String {
        return when (mode?.lowercase()) {
            "legacy" -> "legacy"
            else -> "vkcalls"
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "wdtt:tunnel_cpu"
        ).apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        
        
        
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        
        wifiLock = wm.createWifiLock(mode, "wdtt:wifi_perf").apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        wifiLock = null
    }

    private fun startStatsUpdater() {
        updateJob?.cancel()
        updateJob = TunnelManager.scope.launch(Dispatchers.Main) {
            
            
            
            
            
            var wasEverUp = false
            var downChecks = 0
            val helper = runCatching { WireGuardHelper(applicationContext) }.getOrNull()
            delay(1000)
            while (isActive) {
                // «не работает» во время запуска/перезапуска транспорта — это нормально, не гасим службу
                val busy = TunnelManager.starting.value || isTunnelPaused
                if (!TunnelManager.running.value && !busy) {
                    stopSelf()
                    break
                }
                if (helper != null && TunnelManager.running.value && !busy) {
                    when (helper.watchdogState()) {
                        WireGuardHelper.WatchdogState.UP -> { wasEverUp = true; downChecks = 0 }
                        WireGuardHelper.WatchdogState.DISABLED_BY_EMPTY_WHITELIST -> Unit
                        WireGuardHelper.WatchdogState.DOWN -> {
                            // интерфейс пересоздаётся при перезапуске транспорта — ждём 3 проверки подряд (~6 с),
                            // прежде чем решить, что VPN действительно отобрали (например, включили другой VPN)
                            if (wasEverUp && ++downChecks >= 3) {
                                Log.w("TunnelService", "VPN-интерфейс пропал или заменён другим VPN — выключаем туннель")
                                stopTunnel()
                                break
                            }
                        }
                    }
                }
                if (!isTunnelPaused) {
                    updateNotification(buildTunnelNotificationText())
                }
                delay(2000)
            }
        }
    }

    private fun buildTunnelNotificationText(): String {
        val statsText = TunnelManager.stats.value.trim()
        return when {
            statsText.isEmpty() -> tr("Туннель активен")
            statsText == "Ожидание данных..." -> "Туннель активен"
            else -> statsText
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TUNNEL_NOTIFICATION_CHANNEL_ID,
            tr("WDTT Туннель"),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = tr("Уведомление о работе туннеля")
            setShowBadge(false)
            
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(text: String, actionName: String = "STOP", actionTitle: String = tr("Отключить")): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = PendingIntent.getService(
            this, if (actionName == "STOP") 1 else 2,
            Intent(this, TunnelService::class.java).apply { action = actionName },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, TUNNEL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("WDTT")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setOngoing(true)
            .setLocalOnly(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, actionTitle, stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFAULT)
            
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true) 
            .setSilent(true) 
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setWhen(0L)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Возвращает false, если система запретила фоновую службу (вместо вылета). */
    private fun startPersistentForeground(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(TUNNEL_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(TUNNEL_NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e("TunnelService", "startForeground не удался", e)
            CrashLog.save(e, "WDTT: система не дала запустить службу (без вылета)")
            false
        }
    }

    private fun updateNotification(text: String) {
        if (lastNotificationText == text) return
        lastNotificationText = text
        runCatching {
            val notification = createNotification(text)
            getSystemService(NotificationManager::class.java).notify(TUNNEL_NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            runCatching { connectivityManager?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        runCatching { stopTunnel() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
