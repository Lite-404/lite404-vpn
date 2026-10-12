package com.lite404.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

/**
 * Обычный VPN: ядро Xray (libv2ray) в отдельном процессе «:xray».
 * Состояние пишется в файл xray_state (его читает экран приложения).
 */
class XrayVpnService : VpnService() {
    companion object {
        private const val TAG = "XrayVpn"
        private const val CHANNEL = "xray_vpn"
        private const val NOTIF_ID = 404
        const val ACTION_START = "com.lite404.vpn.XRAY_START"
        const val ACTION_STOP = "com.lite404.vpn.XRAY_STOP"
        const val EXTRA_NAME = "name"
        const val EXTRA_APPS_MODE = "apps_mode"
        const val EXTRA_APPS = "apps"

        fun configFile(ctx: Context) = File(ctx.filesDir, "xray.json")
        private fun stateFile(ctx: Context) = File(ctx.filesDir, "xray_state")

        /** running | connecting | stopped | error:<текст> */
        fun state(ctx: Context): String {
            val s = runCatching { stateFile(ctx).readText() }.getOrDefault("stopped").ifBlank { "stopped" }
            // файл мог остаться от убитого процесса — проверяем, что служба действительно работает
            if ((s == "running" || s == "connecting") && !isAlive(ctx)) return "stopped"
            return s
        }

        @Suppress("DEPRECATION")
        private fun isAlive(ctx: Context): Boolean = runCatching {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            am.getRunningServices(200).any { it.service.className == XrayVpnService::class.java.name && it.started }
        }.getOrDefault(true)

        /** Остановить и дождаться остановки (до 4 с). */
        suspend fun stopAndWait(ctx: Context) {
            if (state(ctx).let { it != "running" && it != "connecting" }) return
            stop(ctx)
            repeat(40) {
                if (state(ctx).let { it != "running" && it != "connecting" }) return
                kotlinx.coroutines.delay(100)
            }
        }

        fun start(ctx: Context, config: String, name: String) {
            configFile(ctx).writeText(config)
            writeState(ctx, "connecting")
            val prefs = Prefs(ctx)
            val i = Intent(ctx, XrayVpnService::class.java).setAction(ACTION_START).putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_APPS_MODE, prefs.effectiveAppsMode())
                .putExtra(EXTRA_APPS, prefs.apps.toTypedArray())
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, XrayVpnService::class.java).setAction(ACTION_STOP))
        }

        private fun sinceFile(ctx: Context) = File(ctx.filesDir, "xray_since")

        /** Момент подключения (мс) или 0, если VPN не подключён. */
        fun since(ctx: Context): Long = runCatching { sinceFile(ctx).readText().trim().toLong() }.getOrDefault(0L)

        private fun writeState(ctx: Context, s: String) = runCatching {
            stateFile(ctx).writeText(s)
            if (s == "running") sinceFile(ctx).writeText(System.currentTimeMillis().toString()) else sinceFile(ctx).delete()
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_NAME) ?: ""
                foreground(name)
                val mode = intent.getStringExtra(EXTRA_APPS_MODE) ?: "off"
                val apps = intent.getStringArrayExtra(EXTRA_APPS)?.toList() ?: emptyList()
                Thread { startVpn(mode, apps) }.start()
            }
            else -> { // перезапуск системой — VPN не восстанавливаем
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

    @Synchronized
    private fun startVpn(appsMode: String, apps: List<String>) {
        try {
            stopping = true
            cleanup()
            stopping = false
            val config = configFile(this).readText()

            val b = Builder()
                .setSession("Lite 404 VPN")
                .setMtu(XrayConfig.MTU)
                .addAddress("10.10.14.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
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
            val fd = b.establish() ?: throw IllegalStateException(tr("нет разрешения на VPN"))
            tun = fd

            Libv2ray.initCoreEnv(geoDir().absolutePath, "")
            val c = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun onEmitStatus(code: Long, msg: String?): Long { Log.d(TAG, "status $code $msg"); return 0 }
                override fun shutdown(): Long { if (!stopping) stopVpn(); return 0 }
                override fun startup(): Long = 0
            })
            controller = c
            c.startLoop(config, fd.fd)
            writeState(this, "running")
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            writeState(this, "error:" + (e.message ?: e.javaClass.simpleName))
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** geoip.dat / geosite.dat из ядра → в папку приложения (Xray читает их оттуда для правил маршрутизации). */
    private fun geoDir(): File {
        val dir = File(filesDir, "geo").apply { mkdirs() }
        val ver = File(dir, ".version")
        val want = BuildConfig.VERSION_CODE.toString()
        if (runCatching { ver.readText() }.getOrNull() != want) {
            for (n in listOf("geoip.dat", "geosite.dat")) {
                assets.open(n).use { inp -> File(dir, n).outputStream().use { inp.copyTo(it) } }
            }
            ver.writeText(want)
        }
        return dir
    }

    private fun cleanup() {
        controller?.let { runCatching { it.stopLoop() } }
        controller = null
        runCatching { tun?.close() }
        tun = null
    }

    private fun stopVpn() {
        stopping = true
        cleanup()
        writeState(this, "stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { stopVpn() }

    override fun onDestroy() {
        if (!stopping) { cleanup(); writeState(this, "stopped") }
        super.onDestroy()
    }
}
