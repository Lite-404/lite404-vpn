package com.lite404.vpn

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VpnMode { XRAY, WDTT }
enum class Phase { OFF, CONNECTING, ON, STOPPING }

/** Результат проверки соединения через туннель. */
data class LinkTest(val ok: Boolean, val text: String, val running: Boolean = false)

data class ConnState(
    val mode: VpnMode? = null,
    val phase: Phase = Phase.OFF,
    val error: String? = null,
    val test: LinkTest? = null,
)

/**
 * Единая точка включения/выключения/переключения VPN.
 *
 * Раньше каждая вкладка сама гасила «чужой» туннель и рисовала кнопку по своему опросу — при переключении
 * Xray → WDTT кнопка успевала показать «выключено», а параллельные нажатия могли запустить два туннеля.
 * Теперь операции идут строго по очереди (mutex), а экран показывает одно состояние: как только нажали —
 * сразу «подключение», без промежуточного «выключено».
 */
object Connection {
    private val guard = CoroutineExceptionHandler { _, e -> CrashLog.save(e, "Connection: ошибка (без вылета)") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + guard)
    private val ops = Mutex()
    private val _state = MutableStateFlow(ConnState())
    val state: StateFlow<ConnState> = _state

    /** Что сейчас выполняется (на время операции экран не пересчитывает состояние по службам). */
    @Volatile private var pending: ConnState? = null
    /** Пользователь нажал «стоп», пока шло подключение: подключение перестаёт ждать и уступает очередь. */
    @Volatile private var abort = false
    private var monitor: Job? = null
    private var wdttTestJob: Job? = null
    private var wdttTestedSession = false
    private var wdttZeroSince = 0L
    private const val WDTT_FLAP_MS = 8_000L

    /** Запустить слежение за службами (вызывается экраном; повторный вызов ничего не делает). */
    fun attach(ctx: Context) {
        if (monitor?.isActive == true) return
        val app = ctx.applicationContext
        monitor = scope.launch {
            while (true) {
                refresh(app)
                delay(if (_state.value.phase == Phase.ON || _state.value.phase == Phase.OFF) 1000 else 300)
            }
        }
    }

    fun connectXray(ctx: Context, server: XrayServer, config: String) = run(ctx, ConnState(VpnMode.XRAY, Phase.CONNECTING)) { app ->
        if (WdttController.active()) {
            WdttController.stopAndWait(app)
            delay(600)   // система освобождает VPN-интерфейс WireGuard
        }
        Prefs(app).desiredMode = "xray"
        XrayVpnService.start(app, config, server.name)
        waitUntil(25_000) { XrayVpnService.state(app).let { it == "running" || it.startsWith("error:") } }
    }

    fun connectWdtt(ctx: Context, server: WdttServer, vkHash: String) = run(ctx, ConnState(VpnMode.WDTT, Phase.CONNECTING)) { app ->
        if (XrayVpnService.state(app).let { it == "running" || it == "connecting" }) {
            XrayVpnService.stopAndWait(app)
            delay(600)   // процесс «:xray» завершается и отдаёт VPN
        }
        if (WdttController.active()) {
            WdttController.stopAndWait(app)   // смена страны: сначала гасим текущий туннель WDTT
            delay(400)
        }
        wdttTestedSession = false
        Prefs(app).desiredMode = "wdtt"   // после остановки старого туннеля: его остановка сбрасывает режим
        WdttController.start(app, server, vkHash)
        waitUntil(10_000) { TunnelManager.running.value }
    }

    fun disconnect(ctx: Context) = run(ctx, ConnState(_state.value.mode, Phase.STOPPING)) { app ->
        abort = false
        Prefs(app).desiredMode = ""
        XrayVpnService.stopAndWait(app)
        WdttController.stopAndWait(app)
    }

    /** Проверить, что интернет через туннель работает. */
    fun test(ctx: Context) {
        val app = ctx.applicationContext
        when (_state.value.mode.takeIf { _state.value.phase == Phase.ON }) {
            VpnMode.XRAY -> XrayVpnService.test(app)
            VpnMode.WDTT -> testWdtt(app)
            null -> Unit
        }
    }

    // ── внутреннее ──

    private fun run(ctx: Context, shown: ConnState, block: suspend (Context) -> Unit) {
        val app = ctx.applicationContext
        attach(app)
        if (ops.isLocked) {
            // идёт операция: повторное «подключить» игнорируем (двойное нажатие), а «стоп» встаёт в очередь
            if (shown.phase != Phase.STOPPING) return
            abort = true
        }
        pending = shown
        _state.value = shown
        scope.launch {
            ops.withLock {
                try {
                    block(app)
                } catch (e: Exception) {
                    CrashLog.save(e, "Connection: ошибка операции (без вылета)")
                    _state.value = ConnState(error = e.message ?: e.javaClass.simpleName)
                } finally {
                    if (pending === shown) pending = null
                    refresh(app)
                }
            }
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!abort && !done() && System.currentTimeMillis() < end) delay(150)
    }

    /** Фактическое состояние — из служб. Во время операции показываем то, что пользователь запросил. */
    private fun refresh(app: Context) {
        pending?.let { _state.value = it.copy(test = _state.value.test); return }
        val xray = XrayVpnService.state(app)
        val wdttRunning = TunnelManager.running.value
        val wdttStarting = TunnelManager.starting.value
        val workers = TunnelManager.activeWorkers.value
        val prev = _state.value
        // Туннель был подключён, а потоки на миг пропали (переподключается поток, сменился TURN) —
        // кнопку не перекрашиваем в «подключение», пока потоков нет меньше WDTT_FLAP_MS.
        val now = System.currentTimeMillis()
        if (workers > 0 || !(wdttRunning || wdttStarting)) wdttZeroSince = 0L
        else if (wdttZeroSince == 0L) wdttZeroSince = now
        val wdttBlip = prev.mode == VpnMode.WDTT && prev.phase == Phase.ON &&
            wdttZeroSince != 0L && now - wdttZeroSince < WDTT_FLAP_MS
        val next = when {
            xray == "running" -> ConnState(VpnMode.XRAY, Phase.ON, test = xrayTest(app))
            xray == "connecting" -> ConnState(VpnMode.XRAY, Phase.CONNECTING)
            wdttRunning && workers > 0 -> ConnState(VpnMode.WDTT, Phase.ON, test = prev.test.takeIf { prev.mode == VpnMode.WDTT })
            wdttBlip -> prev
            wdttRunning || wdttStarting || TunnelManager.paused.value -> ConnState(VpnMode.WDTT, Phase.CONNECTING, test = prev.test.takeIf { prev.mode == VpnMode.WDTT })
            xray.startsWith("error:") -> ConnState(error = xray.removePrefix("error:"))
            // WDTT сам остановился (не по кнопке) — показываем причину из журнала туннеля
            prev.mode == VpnMode.WDTT && prev.phase != Phase.OFF && prev.phase != Phase.STOPPING ->
                ConnState(error = lastWdttError() ?: tr("Резервный VPN остановился. Подробности — в журнале (Настройки)"))
            else -> ConnState(error = prev.error.takeIf { prev.phase == Phase.OFF })
        }
        _state.value = next
        if (next.mode == VpnMode.WDTT && next.phase == Phase.ON && !wdttTestedSession && Prefs(app).testAfterConnect) {
            wdttTestedSession = true
            testWdtt(app, settleMs = 3000)
        }
        if (next.phase == Phase.OFF) wdttTestedSession = false
    }

    private fun lastWdttError(): String? =
        TunnelManager.logs.value.filter { it.isError }.minByOrNull { it.priority }?.message
            ?.replace(Regex("""^\[[^]]+]\s*"""), "")?.removePrefix("Ошибка: ")?.take(160)

    private fun xrayTest(app: Context): LinkTest? {
        val r = XrayVpnService.testResult(app)
        return when {
            r == "testing" -> LinkTest(ok = false, text = tr("Проверяем соединение…"), running = true)
            r.startsWith("ok:") -> LinkTest(true, tr("Интернет работает · %s мс", r.removePrefix("ok:")))
            r.startsWith("fail:") -> LinkTest(false, tr("Сайты не открываются: %s", r.removePrefix("fail:")))
            else -> null
        }
    }

    /**
     * WDTT: ядро не умеет делать запрос через туннель из приложения (оно само исключено из VPN),
     * поэтому проверяем по факту — свежее рукопожатие WireGuard с сервером и живые потоки.
     */
    private fun testWdtt(app: Context, settleMs: Long = 0) {
        wdttTestJob?.cancel()
        wdttTestJob = scope.launch {
            _state.value = _state.value.copy(test = LinkTest(false, tr("Проверяем соединение…"), running = true))
            delay(settleMs)
            var result: LinkTest? = null
            for (attempt in 1..6) {
                val age = runCatching { WireGuardHelper(app).handshakeAgeMs() }.getOrNull()
                val workers = TunnelManager.activeWorkers.value
                if (age != null && age < 180_000 && workers > 0) {
                    result = LinkTest(true, tr("Туннель работает · потоков %s", workers))
                    break
                }
                delay(2000)
            }
            _state.value = _state.value.copy(
                test = result ?: LinkTest(false, tr("Сервер не отвечает через туннель. Попробуйте другую страну"))
            )
        }
    }
}
