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
import android.net.NetworkRequest
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

class TunnelService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var updateJob: Job? = null
    private var lastNotificationText: String? = null
    
    
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkChangeTime = 0L
    private val activeNetworks = mutableSetOf<Network>()
    private var isTunnelPaused = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        acquireWakeLock()
        setupNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // процесс перезапущен системой: туннель сам не поднимаем, чтобы не мешать обычному VPN
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

        
        CaptchaWebViewManager.onTunnelStop()

        TunnelManager.stop()
        releaseWakeLock()
        releaseWifiLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setupNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        activeNetworks.clear()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val wasEmpty = activeNetworks.isEmpty()
                activeNetworks.add(network)
                if (wasEmpty) {
                    if (isTunnelPaused) {
                        isTunnelPaused = false
                        Log.d("TunnelService", "Сеть появилась, возобновляем туннель")
                        TunnelManager.resume()
                        updateNotification(tr("Подключение..."))
                    } else {
                        handleNetworkChange()
                    }
                } else {
                    handleNetworkChange()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                activeNetworks.remove(network)
                if (activeNetworks.isEmpty() && TunnelManager.running.value && !isTunnelPaused) {
                    isTunnelPaused = true
                    Log.d("TunnelService", "Сеть потеряна, приостанавливаем туннель")
                    TunnelManager.pause()
                    updateNotification(tr("Ожидание сети (Фоновый сон)"))
                }
            }
        }

        
        
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
            
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e("TunnelService", "Не удалось подписаться на смену сети", e)
            networkCallback = null
        }
    }
    
    private fun handleNetworkChange() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeTime < 5000) return
        lastNetworkChangeTime = now

        if (TunnelManager.running.value && !isTunnelPaused) {
            Log.d("TunnelService", "Сеть изменилась, мягкий перезапуск Go-клиента")
            TunnelManager.restartTransport()
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
            val helper = runCatching { WireGuardHelper(applicationContext) }.getOrNull()
            delay(1000)
            while (isActive) {
                if (!TunnelManager.running.value && !isTunnelPaused) {
                    
                    stopSelf()
                    break
                }
                if (helper != null && TunnelManager.running.value && !isTunnelPaused) {
                    when (helper.watchdogState()) {
                        WireGuardHelper.WatchdogState.UP -> wasEverUp = true
                        WireGuardHelper.WatchdogState.DISABLED_BY_EMPTY_WHITELIST -> Unit
                        WireGuardHelper.WatchdogState.DOWN -> {
                            if (wasEverUp) {
                                Log.w("TunnelService", "Обнаружена пропажа или замена VPN-интерфейса! Экстренное выключение туннеля.")
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
