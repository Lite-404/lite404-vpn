package com.lite404.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

/**
 * Обычный VPN: ядро Xray (libv2ray) в отдельном процессе «:xray».
 * Состояние пишется в файлы (их читает приложение из основного процесса):
 *   xray_state  running | connecting | stopped | error:<текст>
 *   xray_test   ok:<мс> | fail:<текст> | testing — результат проверки соединения через туннель
 *
 * Смена сети (Wi-Fi ↔ мобильная, возврат сети после сна): туннель пересобирается на месте —
 * новый TUN поднимается раньше, чем закрывается старый, поэтому трафик не уходит мимо VPN,
 * а старые «мёртвые» соединения рвутся сразу, а не через таймаут в 5 минут.
 */
class XrayVpnService : VpnService() {
    companion object {
        private const val TAG = "XrayVpn"
        private const val CHANNEL = "xray_vpn"
        private const val NOTIF_ID = 404
        const val ACTION_START = "com.lite404.vpn.XRAY_START"
        const val ACTION_STOP = "com.lite404.vpn.XRAY_STOP"
        const val ACTION_TEST = "com.lite404.vpn.XRAY_TEST"
        const val EXTRA_NAME = "name"
        const val EXTRA_APPS_MODE = "apps_mode"
        const val EXTRA_APPS = "apps"
        const val EXTRA_TEST_URL = "test_url"
        const val EXTRA_TEST_AFTER = "test_after"

        /** Пауза после смены сети, чтобы не дёргать туннель на каждое мигание Wi-Fi. */
        private const val NETWORK_SETTLE_MS = 1500L
        /** Сколько ждём возврата сети, прежде чем считать её потерянной. */
        private const val NETWORK_LOSS_GRACE_MS = 3000L

        /** Номер экземпляра службы в процессе — чтобы отложенное завершение процесса не убило новый запуск. */
        @Volatile private var instances = 0

        fun configFile(ctx: Context) = File(ctx.filesDir, "xray.json")
        private fun stateFile(ctx: Context) = File(ctx.filesDir, "xray_state")
        private fun testFile(ctx: Context) = File(ctx.filesDir, "xray_test")
        private fun sinceFile(ctx: Context) = File(ctx.filesDir, "xray_since")

        /** running | connecting | stopped | error:<текст> */
        fun state(ctx: Context): String {
            val s = runCatching { stateFile(ctx).readText() }.getOrDefault("stopped").ifBlank { "stopped" }
            // файл мог остаться от убитого процесса — проверяем, что служба действительно работает
            if ((s == "running" || s == "connecting") && !isAlive(ctx)) return "stopped"
            return s
        }

        /** Результат последней проверки соединения: ok:<мс>, fail:<текст>, testing или "". */
        fun testResult(ctx: Context): String = runCatching { testFile(ctx).readText() }.getOrDefault("")

        @Suppress("DEPRECATION")
        private fun isAlive(ctx: Context): Boolean = runCatching {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            am.getRunningServices(200).any { it.service.className == XrayVpnService::class.java.name && it.started }
        }.getOrDefault(true)

        /** Остановить и дождаться остановки (до 6 с). */
        suspend fun stopAndWait(ctx: Context) {
            if (state(ctx).let { it != "running" && it != "connecting" }) return
            stop(ctx)
            repeat(60) {
                if (state(ctx).let { it != "running" && it != "connecting" } && !isAlive(ctx)) return
                kotlinx.coroutines.delay(100)
            }
        }

        fun start(ctx: Context, config: String, name: String) {
            configFile(ctx).writeText(config)
            writeState(ctx, "connecting")
            testFile(ctx).delete()
            val prefs = Prefs(ctx)
            val i = Intent(ctx, XrayVpnService::class.java).setAction(ACTION_START).putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_APPS_MODE, prefs.effectiveAppsMode())
                .putExtra(EXTRA_APPS, prefs.apps.toTypedArray())
                .putExtra(EXTRA_TEST_URL, prefs.pingUrl)
                .putExtra(EXTRA_TEST_AFTER, prefs.testAfterConnect)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, XrayVpnService::class.java).setAction(ACTION_STOP)) }
        }

        /** Проверить соединение через работающий туннель (результат — в [testResult]). */
        fun test(ctx: Context) {
            if (state(ctx) != "running") return
            writeTest(ctx, "testing")
            runCatching {
                ctx.startService(Intent(ctx, XrayVpnService::class.java).setAction(ACTION_TEST)
                    .putExtra(EXTRA_TEST_URL, Prefs(ctx).pingUrl))
            }
        }

        /** Момент подключения (мс) или 0, если VPN не подключён. */
        fun since(ctx: Context): Long = runCatching { sinceFile(ctx).readText().trim().toLong() }.getOrDefault(0L)

        private fun writeState(ctx: Context, s: String) = runCatching {
            stateFile(ctx).writeText(s)
            if (s == "running") sinceFile(ctx).writeText(System.currentTimeMillis().toString()) else sinceFile(ctx).delete()
        }

        private fun writeTest(ctx: Context, s: String) = runCatching { testFile(ctx).writeText(s) }
    }

    private var tun: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    @Volatile private var stopping = false
    @Volatile private var stopped = false
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("xray-control").apply { start() }
    private val bg = Handler(worker.looper)
    private var instance = 0

    // параметры текущего запуска — нужны для пересборки туннеля при смене сети
    private var appsMode = "off"
    private var apps: List<String> = emptyList()
    private var testUrl = Prefs.DEFAULT_PING_URL

    private var connectivity: ConnectivityManager? = null
    private var currentNetwork: Network? = null
    /** Последняя известная сеть (не сбрасывается при потере) — чтобы отличить «та же вернулась» от «сменилась». */
    private var lastNetwork: Network? = null
    private var networkLost = false
    private val rebuildTask = Runnable { rebuildForNetwork() }
    private val lossTask = Runnable {
        if (currentNetwork == null) {
            networkLost = true
            runCatching { setUnderlyingNetworks(null) }
            Log.d(TAG, "сеть пропала — ждём возврата")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (isVpnNetwork(network)) return   // наш же туннель — не «смена сети»
            bg.removeCallbacks(lossTask)
            val previous = lastNetwork
            currentNetwork = network
            lastNetwork = network
            runCatching { setUnderlyingNetworks(arrayOf(network)) }
            if (previous == null) return                        // первое уведомление сразу после подписки
            if (previous == network && !networkLost) return     // та же сеть, соединения живы — ничего не трогаем
            networkLost = false
            Log.d(TAG, "сеть сменилась — пересобираем туннель")
            bg.removeCallbacks(rebuildTask)
            bg.postDelayed(rebuildTask, NETWORK_SETTLE_MS)
        }

        override fun onLost(network: Network) {
            if (network != currentNetwork) return   // VPN-сети сюда не попадают: currentNetwork всегда не VPN
            currentNetwork = null
            bg.postDelayed(lossTask, NETWORK_LOSS_GRACE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = ++instances
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            ACTION_TEST -> {
                val url = intent.getStringExtra(EXTRA_TEST_URL) ?: testUrl
                bg.post { runTest(url, attempts = 1) }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_NAME) ?: ""
                stopped = false
                foreground(name)
                appsMode = intent.getStringExtra(EXTRA_APPS_MODE) ?: "off"
                apps = intent.getStringArrayExtra(EXTRA_APPS)?.toList() ?: emptyList()
                testUrl = intent.getStringExtra(EXTRA_TEST_URL) ?: Prefs.DEFAULT_PING_URL
                val testAfter = intent.getBooleanExtra(EXTRA_TEST_AFTER, true)
                bg.post {
                    if (startVpn() && testAfter) runTest(testUrl, attempts = 2)
                }
            }
            else -> { // перезапуск системой — обычный VPN сам не восстанавливаем
                writeState(this, "stopped"); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun foreground(name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setContentTitle("Lite 404 VPN")
            .setContentText(if (name.isBlank()) tr("VPN включён") else tr("VPN включён") + " · $name")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, tr("Отключить"), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    /** Новый TUN-интерфейс с текущими настройками. Пока он не закрыт, VPN не прерывается. */
    private fun establishTun(): ParcelFileDescriptor {
        val b = Builder()
            .setSession("Lite 404 VPN")
            .setMtu(XrayConfig.MTU)
            .addAddress("10.10.14.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
        // IPv6 тоже заворачиваем в туннель (иначе утечка мимо VPN); Xray отвечает на него отказом сразу
        runCatching { b.addAddress("fd66:6c34:3034::1", 126); b.addRoute("::", 0) }
        // туннелирование по приложениям; своё приложение всегда мимо VPN, иначе ядро Xray зациклится само на себя
        if (appsMode == "include") {
            var added = 0
            for (a in apps) if (a != packageName && runCatching { b.addAllowedApplication(a) }.isSuccess) added++
            if (added == 0) throw IllegalStateException(tr("выбранные приложения не установлены — измените список на вкладке «Приложения»"))
        } else {
            runCatching { b.addDisallowedApplication(packageName) }
            if (appsMode == "exclude") for (a in apps) if (a != packageName) runCatching { b.addDisallowedApplication(a) }
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        currentNetwork?.let { b.setUnderlyingNetworks(arrayOf(it)) }
        return b.establish() ?: throw IllegalStateException(tr("нет разрешения на VPN"))
    }

    /** Ядро само сообщает о падении через shutdown(); реагируем, только если это текущее ядро, а не старое после пересборки. */
    private fun newController(): CoreController {
        var self: CoreController? = null
        val c = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun onEmitStatus(code: Long, msg: String?): Long { Log.d(TAG, "status $code $msg"); return 0 }
            override fun shutdown(): Long {
                if (!stopping && self != null && self === controller) stopVpn()
                return 0
            }
            override fun startup(): Long = 0
        })
        self = c
        return c
    }

    /** Запуск. true — туннель поднят. */
    private fun startVpn(): Boolean = synchronized(lock) {
        if (stopped) return false
        try {
            stopping = true
            cleanup()
            stopping = false
            val config = configFile(this).readText()
            XrayCore.initEnv(this)
            watchNetwork()
            val fd = establishTun()
            tun = fd
            val c = newController()
            controller = c
            c.startLoop(config, fd.fd)
            if (stopped) return false
            writeState(this, "running")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            writeState(this, "error:" + (e.message ?: e.javaClass.simpleName))
            cleanup()
            main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            false
        }
    }

    /** Пересборка при смене сети: новый TUN → новое ядро → закрыть старое. Старые соединения рвутся сразу. */
    private fun rebuildForNetwork() {
        synchronized(lock) {
            if (stopped || controller == null) return
            val oldTun = tun
            var newTun: ParcelFileDescriptor? = null
            try {
                newTun = establishTun()
                stopping = true
                runCatching { controller?.stopLoop() }
                stopping = false
                val c = newController()
                controller = c
                tun = newTun
                c.startLoop(configFile(this).readText(), newTun.fd)
                runCatching { oldTun?.close() }
                Log.d(TAG, "туннель пересобран под новую сеть")
            } catch (e: Throwable) {
                stopping = false
                if (tun !== newTun) runCatching { newTun?.close() }
                if (tun !== oldTun) runCatching { oldTun?.close() }
                Log.e(TAG, "пересборка не удалась — полный перезапуск", e)
                CrashLog.save(e, "Xray: пересборка при смене сети (без вылета)")
                startVpn()
            }
        }
    }

    /** Проверка: запрос [url] через туннель. Пишет ok:<мс> или fail:<текст> в xray_test. */
    private fun runTest(url: String, attempts: Int) {
        writeTest(this, "testing")
        var last = ""
        repeat(attempts) { n ->
            val c = synchronized(lock) { controller }
            if (c == null) { writeTest(this, "fail:" + tr("туннель не работает")); return }
            try {
                val ms = c.measureDelay(url)
                if (ms > 0) { writeTest(this, "ok:$ms"); return }
                last = tr("нет ответа")
            } catch (e: Exception) {
                last = e.message?.substringAfterLast(": ")?.take(80) ?: e.javaClass.simpleName
            }
            if (n < attempts - 1) Thread.sleep(1500)
        }
        writeTest(this, "fail:$last")
    }

    private fun isVpnNetwork(network: Network): Boolean =
        connectivity?.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun watchNetwork() {
        if (connectivity != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        connectivity = cm
        // приложение исключено из VPN, поэтому «сеть по умолчанию» для него — настоящая (Wi-Fi / мобильная)
        currentNetwork = cm.activeNetwork?.takeIf {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false
        }
        lastNetwork = currentNetwork
        runCatching { cm.registerDefaultNetworkCallback(networkCallback, bg) }
            .onFailure { Log.w(TAG, "не удалось следить за сетью", it) }
    }

    private fun unwatchNetwork() {
        connectivity?.let { runCatching { it.unregisterNetworkCallback(networkCallback) } }
        connectivity = null
        bg.removeCallbacks(rebuildTask)
        bg.removeCallbacks(lossTask)
    }

    private fun cleanup() {
        controller?.let { runCatching { it.stopLoop() } }
        controller = null
        runCatching { tun?.close() }
        tun = null
    }

    /** Остановка один раз, ядро гасится в фоне под тем же замком, что и запуск. */
    private fun stopVpn() {
        if (stopped) return
        stopped = true
        stopping = true
        bg.post {
            unwatchNetwork()
            synchronized(lock) { runCatching { cleanup() } }
            writeState(this, "stopped")
            writeTest(this, "")
            main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }

    override fun onRevoke() { stopVpn() }

    override fun onDestroy() {
        unwatchNetwork()
        if (!stopping) { stopping = true; runCatching { synchronized(lock) { cleanup() } } }
        writeState(this, "stopped")
        worker.quitSafely()
        super.onDestroy()
        // процесс «:xray» нужен только ядру — завершаем его целиком, чтобы ядро и VPN точно освободились.
        // Но только если за это время не запустили службу заново (иначе убьём новый запуск → вылет).
        val mine = instance
        main.postDelayed({ if (instances == mine) android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
    }
}
