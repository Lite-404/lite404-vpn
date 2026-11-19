package com.lite404.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import android.util.Log
import com.lite404.vpncore.Platform
import com.lite404.vpncore.Vpncore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

/**
 * Обычный VPN: ядро sing-box-extended (библиотека vpncore) в отдельном процессе «:xray».
 * Конфиг сервера приходит в формате Xray (xray.json), ядро переводит его в свой формат при запуске.
 * Состояние пишется в файлы (их читает приложение из основного процесса):
 *   xray_state  running | connecting | stopped | error:<текст>
 *   xray_test   ok:<мс> | fail:<текст> | testing — результат проверки соединения через туннель
 *
 * При смене сети передаём новый интерфейс ядру. Оно закрывает старые соединения,
 * сохраняя TUN и правила Android VPN.
 */
class XrayVpnService : VpnService() {
    companion object {
        private const val TAG = "XrayVpn"
        private const val CHANNEL = "xray_vpn"
        private const val NOTIF_ID = 404
        const val ACTION_START = "com.lite404.vpn.XRAY_START"
        const val ACTION_STOP = "com.lite404.vpn.XRAY_STOP"
        const val ACTION_TEST = "com.lite404.vpn.XRAY_TEST"
        private const val EXTRA_REQUEST = "request"
        const val EXTRA_NAME = "name"
        const val EXTRA_APPS_MODE = "apps_mode"
        const val EXTRA_APPS = "apps"
        const val EXTRA_TEST_URL = "test_url"
        const val EXTRA_TEST_AFTER = "test_after"
        const val EXTRA_LAN_DIRECT = "lan_direct"
        private const val EXTRA_MODE = "vpn_mode"

        fun mode(ctx: Context): VpnMode = runCatching {
            VpnMode.valueOf(File(ctx.filesDir, "core_mode").readText())
        }.getOrDefault(VpnMode.XRAY)

        private const val TUN_ADDRESSES = "10.10.14.1/30,fd66:6c34:3034::1/126"
        // Ядро общее для процесса, включая смену экземпляра сервиса.
        private val bg by lazy {
            val thread = HandlerThread("xray-control").apply { start() }
            Handler(thread.looper)
        }

        private fun configFile(ctx: Context) = AtomicFile(File(ctx.filesDir, "xray.json"))
        private fun stateFile(ctx: Context) = AtomicFile(File(ctx.filesDir, "xray_state"))
        private fun testFile(ctx: Context) = AtomicFile(File(ctx.filesDir, "xray_test"))
        private fun sinceFile(ctx: Context) = File(ctx.filesDir, "xray_since")
        private fun resumeFile(ctx: Context) = AtomicFile(File(ctx.filesDir, "xray_resume.json"))
        @Volatile private var owner: XrayVpnService? = null

        private fun restoreIntent(ctx: Context): Intent? = runCatching {
            val saved = JSONObject(resumeFile(ctx).readText())
            val mode = saved.getString("mode")
            if (Prefs(ctx).desiredMode != (if (mode == VpnMode.WDTT.name) "wdtt" else "xray") ||
                VpnService.prepare(ctx) != null) return@runCatching null
            val apps = saved.getJSONArray("apps")
            Intent(ctx, XrayVpnService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_REQUEST, saved.getString("request"))
                .putExtra(EXTRA_NAME, saved.optString("name"))
                .putExtra(EXTRA_MODE, mode).putExtra(EXTRA_APPS_MODE, saved.getString("apps_mode"))
                .putExtra(EXTRA_APPS, Array(apps.length()) { apps.getString(it) })
                .putExtra(EXTRA_LAN_DIRECT, saved.getBoolean("lan"))
                .putExtra(EXTRA_TEST_URL, saved.getString("test_url"))
                .putExtra(EXTRA_TEST_AFTER, saved.getBoolean("test_after"))
        }.getOrNull()

        /** Called by the surviving main process after an unexpected core-process exit. */
        fun resumeSaved(ctx: Context): Boolean {
            val intent = restoreIntent(ctx) ?: return false
            ctx.startForegroundService(intent)
            return true
        }

        private fun AtomicFile.readText() = readFully().toString(Charsets.UTF_8)

        private fun AtomicFile.writeText(value: String) {
            val output = startWrite()
            try {
                output.write(value.toByteArray(Charsets.UTF_8))
                finishWrite(output)
            } catch (e: Exception) {
                failWrite(output)
                throw e
            }
        }

        /** running | connecting | stopped | error:<текст> */
        fun state(ctx: Context): String {
            val record = runCatching { stateFile(ctx).readText() }.getOrDefault("")
            val s = record.substringAfter('\n').ifBlank { "stopped" }
            // файл мог остаться от убитого процесса — проверяем, что служба действительно работает
            if ((s == "running" || s == "connecting") && !isAlive(ctx)) return "stopped"
            return s
        }

        fun startFinished(ctx: Context, request: String): Boolean {
            val record = runCatching { stateFile(ctx).readText() }.getOrDefault("")
            val status = record.substringAfter('\n')
            return record.substringBefore('\n') == request && (status == "running" || status.startsWith("error:"))
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
            val previous = state(ctx)
            if (previous != "running" && previous != "connecting" && !isAlive(ctx)) return
            ctx.startService(Intent(ctx, XrayVpnService::class.java).setAction(ACTION_STOP).putExtra("keep_transport", true))
            repeat(70) {
                if (runCatching { stateFile(ctx).readText() }.getOrNull() == "stopped" && !isAlive(ctx)) return
                delay(100)
            }
            check(!isAlive(ctx)) { tr("Не удалось остановить VPN") }
        }

        fun start(ctx: Context, config: String, name: String, mode: VpnMode = VpnMode.XRAY): String {
            val request = UUID.randomUUID().toString()
            configFile(ctx).writeText("$request\n$config")
            val prefs = Prefs(ctx)
            File(ctx.filesDir, "core_mode").writeText(mode.name)
            val transport = setOf("com.vkontakte.android", "com.vk.calls")
            val apps = if (mode != VpnMode.WDTT) prefs.apps else
                if (prefs.effectiveAppsMode() != "include") prefs.apps + transport else prefs.apps - transport
            val i = Intent(ctx, XrayVpnService::class.java).setAction(ACTION_START).putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_REQUEST, request)
                .putExtra(EXTRA_APPS_MODE, prefs.effectiveAppsMode())
                .putExtra(EXTRA_APPS, apps.toTypedArray())
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_TEST_URL, prefs.pingUrl)
                .putExtra(EXTRA_TEST_AFTER, prefs.testAfterConnect)
                .putExtra(EXTRA_LAN_DIRECT, if (mode == VpnMode.WDTT) prefs.lanDirectWdtt else prefs.lanDirectXray)
            ctx.startForegroundService(i)
            return request
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

        private fun writeState(ctx: Context, s: String, request: String? = null) = runCatching {
            stateFile(ctx).writeText(if (request == null) s else "$request\n$s")
            if (s == "running") sinceFile(ctx).writeText(System.currentTimeMillis().toString()) else sinceFile(ctx).delete()
        }

        private fun writeTest(ctx: Context, s: String) = runCatching { testFile(ctx).writeText(s) }
    }

    private data class TunOptions(val appsMode: String, val apps: List<String>, val lanDirect: Boolean, val mode: VpnMode)

    private data class Uplink(val network: Network, val name: String, val index: Int)

    private val main = Handler(Looper.getMainLooper())
    private val tests = CoroutineScope(SupervisorJob() + bg.asCoroutineDispatcher())
    private val platform = object : Platform {
        override fun protect(fd: Int): Boolean = this@XrayVpnService.protect(fd)
    }

    // Параметры сервиса читает и меняет только xray-control. Поколение команды меняется в главном потоке.
    @Volatile private var command = 0L
    private var lastStartId = 0
    private var closing = false
    private var tun: ParcelFileDescriptor? = null
    private var tunOptions: TunOptions? = null
    private var coreConfig: String? = null
    private var activeRequest: String? = null
    private var activeCommand = 0L
    private var needsRecovery = false
    private var recovery: Runnable? = null
    private var recoveryAttempt = 0
    private var testJob: Job? = null
    private var testUrl = Prefs.DEFAULT_PING_URL
    private var testAfterConnect = false
    private var notificationName = ""
    private var notificationMode = VpnMode.XRAY

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var uplink: Uplink? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent == null || intent.action == SERVICE_INTERFACE) {
            val restored = restoreIntent(this)
            VpnJournal.record(this, mode(this), "VPN: системный перезапуск службы · восстановление=${restored != null}")
            if (restored != null) return onStartCommand(restored, flags, startId)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                closing = false
                val request = ++command
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                val options = TunOptions(
                    intent.getStringExtra(EXTRA_APPS_MODE) ?: "off",
                    intent.getStringArrayExtra(EXTRA_APPS)?.toList().orEmpty(),
                    intent.getBooleanExtra(EXTRA_LAN_DIRECT, true),
                    runCatching { VpnMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrDefault(VpnMode.XRAY)
                )
                notificationName = name
                notificationMode = options.mode
                foreground(tr("Подключение..."))
                bg.post {
                    if (request != command) return@post
                    owner = this
                    testUrl = intent.getStringExtra(EXTRA_TEST_URL) ?: Prefs.DEFAULT_PING_URL
                    testAfterConnect = intent.getBooleanExtra(EXTRA_TEST_AFTER, true)
                    val saved = JSONObject().put("request", intent.getStringExtra(EXTRA_REQUEST))
                        .put("name", name).put("mode", options.mode.name).put("apps_mode", options.appsMode)
                        .put("apps", JSONArray(options.apps)).put("lan", options.lanDirect)
                        .put("test_url", testUrl).put("test_after", testAfterConnect)
                    runCatching { resumeFile(this).writeText(saved.toString()) }
                        .onFailure { VpnJournal.record(this, options.mode, "VPN: не удалось сохранить параметры восстановления") }
                    startVpn(request, intent.getStringExtra(EXTRA_REQUEST).orEmpty(), options)
                }
            }
            ACTION_TEST -> if (!closing) {
                val request = command
                bg.post {
                    if (request == command && coreConfig != null && uplink != null) {
                        runTest(intent.getStringExtra(EXTRA_TEST_URL) ?: testUrl, attempts = 1)
                    }
                }
            }
            ACTION_STOP -> {
                resumeFile(this).delete()
                if (intent?.getBooleanExtra("keep_transport", false) != true) stopReserveTransport()
                VpnJournal.record(this, mode(this), "VPN: остановка")
                stopVpn()
            }
            else -> VpnJournal.record(this, mode(this), "VPN: проигнорирована посторонняя команда службы")
        }
        return if (closing) START_NOT_STICKY else START_STICKY
    }

    private fun foreground(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        val n = VpnNotifications.build(this, CHANNEL, notificationMode, notificationName, status)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    /** Новый TUN-интерфейс с текущими настройками. Пока он не закрыт, VPN не прерывается. */
    private fun establishTun(options: TunOptions): ParcelFileDescriptor {
        val b = Builder()
            .setSession("Lite 404 VPN")
            .setMtu(XrayConfig.MTU)
            .addAddress("10.10.14.1", 30)
            .addDnsServer("1.1.1.1")
        // весь интернет, кроме локальной сети (192.168.x, 10.x, 172.16–31.x): к ней — напрямую, мимо VPN
        if (options.lanDirect) for ((ip, prefix) in Lan.PUBLIC_IPV4) b.addRoute(ip, prefix) else b.addRoute("0.0.0.0", 0)
        // IPv6 тоже заворачиваем в туннель (иначе утечка мимо VPN); ядро отвечает на него отказом сразу
        runCatching { b.addAddress("fd66:6c34:3034::1", 126); b.addRoute("::", 0) }
        // туннелирование по приложениям; своё приложение всегда мимо VPN, иначе ядро зациклится само на себя
        if (options.appsMode == "include") {
            var added = 0
            for (a in options.apps) if (a != packageName && runCatching { b.addAllowedApplication(a) }.isSuccess) added++
            if (added == 0) throw IllegalStateException(tr("выбранные приложения не установлены — измените список на вкладке «Приложения»"))
        } else {
            runCatching { b.addDisallowedApplication(packageName) }
            if (options.appsMode == "exclude" || options.mode == VpnMode.WDTT)
                for (a in options.apps) if (a != packageName &&
                    (options.appsMode == "exclude" || a in setOf("com.vkontakte.android", "com.vk.calls")))
                    runCatching { b.addDisallowedApplication(a) }
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        b.setUnderlyingNetworks(uplink?.let { arrayOf(it.network) } ?: emptyArray())
        return b.establish() ?: throw IllegalStateException(tr("нет разрешения на VPN"))
    }

    private fun startVpn(commandId: Long, request: String, options: TunOptions) {
        try {
            val configRecord = configFile(this).readText()
            if (configRecord.substringBefore('\n') != request) return
            clearTest()
            writeState(this, "connecting", request)
            VpnJournal.record(this, mode(this), "VPN: подключение")
            XrayCore.initEnv(this)
            val ext = getExternalFilesDir(null)
            val log = if (ext != null && File(ext, "core.debug").exists()) {
                File(ext, "core.log").apply { delete() }.absolutePath
            } else ""
            val config = Vpncore.convert(
                configRecord.substringAfter('\n'), XrayCore.geoDir(this).absolutePath, TUN_ADDRESSES,
                log, if (log.isNotEmpty()) "debug" else ""
            )
            if (commandId != command) return
            watchNetwork()
            val fd = tun?.takeIf { tunOptions == options } ?: establishTun(options).also { next ->
                val previous = tun
                tun = next
                tunOptions = options
                runCatching { previous?.close() }
            }
            Vpncore.start(config, fd.fd, platform)
            coreConfig = config
            activeRequest = request
            activeCommand = commandId
            needsRecovery = uplink == null
            if (commandId != command) return
            writeState(this, "running", request)
            main.post { if (commandId == command && !closing) foreground(tr("Подключено")) }
            Log.i(TAG, "VPN started")
            VpnJournal.record(this, mode(this), "VPN: туннель запущен")
            if (testAfterConnect && uplink != null) runTest(testUrl, attempts = 2)
        } catch (e: Exception) {
            Log.e(TAG, "VPN start failed", e)
            VpnJournal.record(this, mode(this), "VPN: ошибка запуска · ${e.message ?: e.javaClass.simpleName}")
            cleanup()
            if (commandId == command) {
                writeState(this, "error:" + (e.message ?: e.javaClass.simpleName), request)
                finish(commandId)
            }
        }
    }

    private fun runTest(url: String, attempts: Int) {
        testJob?.cancel()
        writeTest(this, "testing")
        testJob = tests.launch {
            var last = tr("нет ответа")
            repeat(attempts) { attempt ->
                val result = withContext(Dispatchers.IO) { runCatching { Vpncore.urlTest(url, 8_000) } }
                val ms = result.getOrDefault(-1L)
                if (ms >= 0) {
                    VpnJournal.record(this@XrayVpnService, mode(this@XrayVpnService), "Проверка соединения: $ms мс")
                    writeTest(this@XrayVpnService, "ok:$ms")
                    return@launch
                }
                result.exceptionOrNull()?.let { last = it.message?.substringAfterLast(": ")?.take(80) ?: it.javaClass.simpleName }
                if (attempt + 1 < attempts) delay(1000)
            }
            writeTest(this@XrayVpnService, "fail:$last")
            VpnJournal.record(this@XrayVpnService, mode(this@XrayVpnService), "Проверка соединения: $last")
        }
    }

    private fun clearTest() {
        testJob?.cancel()
        testJob = null
        writeTest(this, "")
    }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = connectivity
        val initial = cm.activeNetwork?.takeIf {
            cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
        }
        initial?.let { updateUplink(it, cm.getLinkProperties(it)) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var pending: Network? = null
            override fun onAvailable(network: Network) {
                pending = network
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (networkCallback === this && network == pending) updateUplink(network, properties)
            }

            override fun onLost(network: Network) {
                if (networkCallback !== this) return
                if (network == pending) pending = null
                if (network == uplink?.network) setUplink(null)
            }
        }
        cm.watchUnderlyingNetwork(callback, bg)
        networkCallback = callback
    }

    private fun updateUplink(network: Network, properties: LinkProperties?) {
        val name = properties?.interfaceName ?: return
        val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: return
        if (index >= 0) setUplink(Uplink(network, name, index))
    }

    private fun setUplink(next: Uplink?) {
        val previous = uplink
        if (previous == next) return
        uplink = next
        if (tun != null) setUnderlyingNetworks(next?.let { arrayOf(it.network) } ?: emptyArray())
        // Новый Android Network может получить то же имя интерфейса. Сообщаем ядру о разрыве и в этом случае.
        if (previous != null && next != null && previous.name == next.name && previous.index == next.index) {
            Vpncore.setDefaultInterface("", -1)
        }
        Vpncore.setDefaultInterface(next?.name.orEmpty(), next?.index ?: -1)
        Log.i(TAG, "Uplink: ${next?.name ?: "offline"}")
        VpnJournal.record(this, mode(this), "Сеть: ${next?.name ?: "нет подключения"}")
        val config = coreConfig ?: return
        if (activeCommand != command) return
        clearTest()
        if (next == null) {
            needsRecovery = true
            return
        }
        if (needsRecovery && !recoverCore(config)) return
        if (testAfterConnect) runTest(testUrl, attempts = 2)
    }

    private fun recoverCore(config: String): Boolean {
        val fd = tun ?: return false
        needsRecovery = false
        return try {
            // После полного обрыва ядро может оставить старое состояние сети. TUN остаётся в Android.
            Vpncore.start(config, fd.fd, platform)
            recoveryAttempt = 0
            writeState(this, "running", activeRequest)
            true
        } catch (e: Exception) {
            Log.e(TAG, "VPN recovery failed", e)
            VpnJournal.record(this, mode(this), "VPN: ошибка восстановления · ${e.message ?: e.javaClass.simpleName}")
            if (activeCommand == command) {
                needsRecovery = true
                writeState(this, "connecting", activeRequest)
                recovery?.let(bg::removeCallbacks)
                val commandId = command
                val retry = Runnable {
                    recovery = null
                    if (!closing && owner === this && commandId == command && uplink != null && coreConfig != null)
                        recoverCore(config)
                }
                recovery = retry
                recoveryAttempt++
                bg.postDelayed(retry, (500L shl recoveryAttempt.coerceAtMost(5)).coerceAtMost(30_000))
            }
            false
        }
    }

    private fun cleanup() {
        recovery?.let(bg::removeCallbacks)
        recovery = null
        clearTest()
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        val hadUplink = uplink != null
        uplink = null
        runCatching { tun?.close() }
        tun = null
        tunOptions = null
        if (owner === this) runCatching { Vpncore.stop() }
        coreConfig = null
        needsRecovery = false
        if (hadUplink && owner === this) runCatching { Vpncore.setDefaultInterface("", -1) }
    }

    private fun stopVpn() {
        if (closing) return
        closing = true
        val request = ++command
        bg.post {
            cleanup()
            if (request != command) return@post
            writeState(this, "stopped")
            finish(request)
        }
    }

    private fun finish(request: Long) {
        main.post {
            if (request != command) return@post
            closing = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(lastStartId)
        }
    }

    private fun stopReserveTransport() {
        if (mode(this) == VpnMode.WDTT) runCatching {
            startService(Intent(this, TunnelService::class.java).setAction("STOP"))
        }
    }

    override fun onRevoke() {
        resumeFile(this).delete()
        VpnJournal.record(this, mode(this), "VPN: Android отозвал VPN-разрешение или включён другой VPN")
        main.post { stopReserveTransport(); stopVpn() }
    }

    override fun onDestroy() {
        VpnJournal.record(this, mode(this), "VPN: служба уничтожена · штатная остановка=$closing")
        ++command
        tests.cancel()
        bg.post { cleanup() }
        super.onDestroy()
    }
}
