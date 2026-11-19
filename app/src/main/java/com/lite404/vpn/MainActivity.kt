package com.lite404.vpn

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val Bg: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Card: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Muted = Color(0xFFB0B0B5)
internal val Danger = Color(0xFFFF5A5A)

private object NoClickIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource) = object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = javaClass.hashCode()
}

const val BOT_URL = "https://t.me/lite404bot"
const val SOURCE_URL = "https://github.com/Lite-404/lite404-vpn" // адрес исходников — указать, когда репозиторий будет создан

/** Общее состояние приложения (данные с сервера). */
class AppModel(private val ctx: Context) {
    val prefs = Prefs(ctx)
    var token by mutableStateOf(Token.parse(prefs.token))
    var config by mutableStateOf(
        prefs.configJson.takeIf { it.isNotBlank() }?.let { runCatching { AppConfig.parse(it, prefs.configAt) }.getOrNull() }
    )
    var loading by mutableStateOf(false)
    var swipeTabsEnabled by mutableStateOf(prefs.swipeTabsEnabled)
        private set

    fun updateTabSwipe(enabled: Boolean) {
        prefs.swipeTabsEnabled = enabled
        swipeTabsEnabled = enabled
    }

    /** Текущее всплывающее уведомление (показывается плашкой сверху). */
    var toast by mutableStateOf<ToastData?>(null)
    fun notify(text: String?, kind: ToastKind = ToastKind.Info) {
        if (!text.isNullOrBlank()) toast = ToastData(text, kind)
    }
    var deviceLimit by mutableStateOf(false)

    /** Показать просьбу снять ограничение батареи (без него Android усыпляет туннель при выключенном экране). */
    var batteryPrompt by mutableStateOf(false)
    fun askBatteryOnce() {
        if (!prefs.batteryAsked && !Battery.isUnrestricted(ctx)) {
            prefs.batteryAsked = true
            batteryPrompt = true
        }
    }

    /** Загрузить данные с сервера. Возвращает true при успехе. */
    suspend fun refresh(t: Token? = token): Boolean {
        if (t == null) return false
        val before = token
        loading = true
        try {
            val body = withContext(Dispatchers.IO) { AppApi.fetch(ctx, t) }
            val now = System.currentTimeMillis()
            val c = AppConfig.parse(body, now)
            if (token != before) return false
            prefs.saveConfig(body, now)
            config = c
            if (c.error == "device_limit") deviceLimit = true
            notify(AppApi.errorText(c.error), ToastKind.Error)
            return true
        } catch (e: ApiException) {
            notify(e.message ?: tr("Ошибка сервера"), ToastKind.Error)
            if (e.code == "bad_token" && token != null) { /* токен отозван — оставляем экран, но показываем текст */ }
        } catch (e: Exception) {
            notify(tr("Нет связи с сервером: %s", e.message ?: e.javaClass.simpleName), ToastKind.Error)
        } finally {
            loading = false
        }
        return false
    }

    suspend fun login(raw: String): Boolean {
        val t = Token.parse(raw)
        if (t == null) { notify(tr("Это не токен Lite 404. Он начинается с L404-"), ToastKind.Error); return false }
        if (refresh(t)) {
            prefs.token = raw.trim().replace("\\s".toRegex(), "")
            token = t
            return true
        }
        return false
    }

    fun logout() {
        Connection.disconnect(ctx)
        prefs.clear()
        token = null
        config = null
    }

    fun reloadCached() {
        config = prefs.configJson.takeIf { it.isNotBlank() }?.let {
            runCatching { AppConfig.parse(it, prefs.configAt) }.getOrNull()
        }
    }
}

class MainActivity : ComponentActivity() {
    private val preferences by lazy { Prefs(this) }
    private var appearance by mutableStateOf<Appearance?>(null)

    companion object {
        var activeActivities = 0
        var isForeground: Boolean
            get() = activeActivities > 0
            set(value) {}
    }

    override fun onStart() {
        super.onStart()
        activeActivities++
        ManlCaptchaWebViewManager.checkAndShowPendingCaptcha(this)
        Connection.autoConnect(this, boot = false)
    }

    override fun onStop() {
        super.onStop()
        activeActivities--
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        I18n.init(this)
        TunnelManager.initObservers(this)
        Updater.cleanup(this)
        appearance = Appearance(preferences)
        setContent {
            val style = appearance ?: return@setContent
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (I18n.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, density.fontScale * style.textScale),
                LocalGlass provides !style.lightweight
            ) {
                MaterialTheme(colorScheme = liteColors(style)) {
                    CompositionLocalProvider(LocalRippleConfiguration provides null, LocalIndication provides NoClickIndication) {
                        Surface(Modifier.fillMaxSize(), color = Bg) { Root(style.haptics) { appearance = Appearance(preferences) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Root(haptics: Boolean, onAppearanceChanged: () -> Unit) {
    val ctx = LocalContext.current
    val model = remember { AppModel(ctx.applicationContext) }
    val subscriptionScope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(model, lifecycleOwner) {
        val preferences = ctx.applicationContext.getSharedPreferences("lite404", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "config") model.reloadCached()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) subscriptionScope.launch {
                runCatching { SubscriptionSync.refresh(ctx.applicationContext) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    // разрешение на уведомления (Android 13+)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (model.token != null) model.refresh()
    }
    var tab by rememberSaveable { mutableStateOf(Tab.VPN) }
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    var settingsHomeRequest by rememberSaveable { mutableIntStateOf(0) }
    val serversExpanded = rememberSaveable { mutableStateOf(false) }
    val serverPicker = remember(serversExpanded) { ServerPicker(serversExpanded) }
    val swipe = remember { mutableStateOf<TabSwipeProgress?>(null) }
    var finishSwipe by remember { mutableStateOf<TabSwipeRelease?>(null) }
    val direction = LocalLayoutDirection.current
    fun selectTab(selected: Tab) {
        Snapshot.withMutableSnapshot {
            if (selected == Tab.SETTINGS) settingsHomeRequest++
            tab = selected
            swipe.value = null
            finishSwipe = null
            showUpdates = false
            serverPicker.expanded = false
        }
    }
    fun neighbor(step: Int): Tab? =
        Tab.entries.getOrNull(tab.ordinal + if (direction == LayoutDirection.Rtl) -step else step)

    LaunchedEffect(finishSwipe) {
        val release = finishSwipe ?: return@LaunchedEffect
        val preview = swipe.value ?: return@LaunchedEffect
        val commit = release.step == preview.step
        val target = if (commit) 1f else 0f
        val settle = TabSwipeSettle(preview.fraction, target, release.velocity)
        if (preview.fraction != target) {
            animate(0f, 1f, animationSpec = tween(settle.durationMillis, easing = LinearEasing)) { progress, _ ->
                swipe.value = preview.copy(fraction = settle.positionAt(progress))
            }
        }
        val destination = if (commit) neighbor(preview.step) else null
        if (destination != null) selectTab(destination)
        else {
            swipe.value = null
            finishSwipe = null
        }
    }
    val glass = LocalGlass.current
    val dockLayer = if (glass && Build.VERSION.SDK_INT >= 31) rememberGraphicsLayer() else null
    val dockBackdrop = remember(dockLayer) { dockLayer?.let(::DockBackdrop) }
    AppPointerSurface(serverPicker.dismissOnOutsideTap, haptics) {
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (model.token != null) {
                val dockItems = remember { Tab.entries.map { DockItem(tr(it.title), it.glyph) } }
                if (glass) {
                    LiquidDock(dockItems, selection = { tab.ordinal }, backdrop = dockBackdrop,
                        swipeOffset = { swipe.value?.let { it.step * it.fraction } }) { index -> selectTab(Tab.entries[index]) }
                } else {
                    PlainDock(dockItems, tab.ordinal) { index -> selectTab(Tab.entries[index]) }
                }
            }
        }
    ) { pad ->
        if (model.token == null) {
            Box(Modifier.fillMaxSize().padding(pad)) { TokenScreen(model) }
        } else {
            val dockClearance = pad.calculateBottomPadding()
            Box(
                Modifier.fillMaxSize().captureDockBackdrop(dockBackdrop).padding(
                    start = pad.calculateStartPadding(direction),
                    top = pad.calculateTopPadding(),
                    end = pad.calculateEndPadding(direction)
                ).tabSwipe(
                    enabled = model.swipeTabsEnabled,
                    dockClearance = dockClearance,
                    hasNeighbor = { finishSwipe == null && neighbor(it) != null },
                    onProgress = { swipe.value = it },
                    onFinish = { release -> if (swipe.value != null) finishSwipe = release }
                )
            ) {
                MainScreen(model, tab, showUpdates, dockClearance, swipe, ::neighbor, serverPicker, settingsHomeRequest, onAppearanceChanged) {
                    selectTab(Tab.SETTINGS)
                    showUpdates = true
                    Updater.check()
                }
            }
        }
    }
    TopToastHost(model, Modifier.align(Alignment.TopCenter))
    }
    if (model.batteryPrompt) {
        AlertDialog(
            onDismissRequest = { model.batteryPrompt = false },
            title = { Text(tr("Работа в фоне")) },
            text = { Text(tr("Чтобы VPN не «засыпал» при выключенном экране и быстро работал после разблокировки, разрешите приложению работать без ограничений батареи.")) },
            confirmButton = { TextButton(onClick = { model.batteryPrompt = false; Battery.request(ctx) }) { Text(tr("Разрешить")) } },
            dismissButton = { TextButton(onClick = { model.batteryPrompt = false }) { Text(tr("Не сейчас")) } }
        )
    }
    if (model.deviceLimit) {
        AlertDialog(
            onDismissRequest = { model.deviceLimit = false },
            title = { Text(tr("Лимит устройств")) },
            text = { Text(tr("На вашей подписке уже подключено максимум устройств. Откройте бота → «Устройства» и удалите ненужное, затем нажмите «Обновить подписку».")) },
            confirmButton = {
                TextButton(onClick = { model.deviceLimit = false; openUrl(ctx, BOT_URL) }) { Text(tr("Открыть бота")) }
            },
            dismissButton = { TextButton(onClick = { model.deviceLimit = false }) { Text(tr("Закрыть")) } }
        )
    }
}

// ───────────────────────── экран токена ─────────────────────────

@Composable
private fun TokenScreen(model: AppModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Lite 404 VPN", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Accent)
        Spacer(Modifier.height(8.dp))
        Text(tr("Введите токен из бота"), color = Muted)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("L404-…") },
            maxLines = 4,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                },
                modifier = Modifier.weight(1f)
            ) { Text(tr("Вставить")) }
            Button(
                onClick = { scope.launch { model.login(text) } },
                enabled = text.isNotBlank() && !model.loading,
                modifier = Modifier.weight(1f)
            ) {
                if (model.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                else Text(tr("Войти"))
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(tr("Токен выдаёт бот @lite404bot"), color = Muted, fontSize = 13.sp)
        TextButton(onClick = { openUrl(ctx, BOT_URL) }) { Text(tr("Открыть бота")) }
    }
}

// ───────────────────────── главный экран ─────────────────────────

private enum class Tab(val title: String, val glyph: MotionGlyph) {
    VPN("VPN", MotionGlyph.Lock),
    WDTT("Резерв", MotionGlyph.Reserve),
    APPS("Приложения", MotionGlyph.Apps),
    SETTINGS("Настройки", MotionGlyph.Gear)
}

/** Общий опрос состояния туннелей и скорости. */
private class LiveState {
    var xray by mutableStateOf("stopped")
    var wdtt by mutableStateOf(false)
    var up by mutableLongStateOf(0)    // байт/с
    var down by mutableLongStateOf(0)
    var xraySince by mutableLongStateOf(0)
    var wdttSince by mutableLongStateOf(0)
    var now by mutableLongStateOf(System.currentTimeMillis())
}

@Composable
private fun MainScreen(
    model: AppModel,
    tab: Tab,
    showUpdates: Boolean,
    dockClearance: Dp,
    swipe: State<TabSwipeProgress?>,
    neighbor: (Int) -> Tab?,
    serverPicker: ServerPicker,
    settingsHomeRequest: Int,
    onAppearanceChanged: () -> Unit,
    onCheckUpdates: () -> Unit
) {
    val ctx = LocalContext.current
    val live = remember { LiveState() }
    LaunchedEffect(Unit) { Connection.attach(ctx) }
    val wdttRunning by TunnelManager.running.collectAsState()
    LaunchedEffect(Unit) {
        val uid = Process.myUid()
        var lastTx = TrafficStats.getUidTxBytes(uid)
        var lastRx = TrafficStats.getUidRxBytes(uid)
        while (true) {
            live.xray = XrayVpnService.state(ctx)
            live.wdtt = TunnelManager.running.value
            val on = live.xray == "running" || live.wdtt
            // у каждого туннеля своё время подключения
            live.xraySince = if (live.xray == "running") XrayVpnService.since(ctx).takeIf { it > 0 } ?: live.xraySince.takeIf { it > 0 } ?: System.currentTimeMillis() else 0L
            val wdttUp = live.wdtt && TunnelManager.wireGuardReady.value && TunnelManager.activeWorkers.value > 0
            if (wdttUp && WdttController.since == 0L) WdttController.since = System.currentTimeMillis()
            if (!live.wdtt) WdttController.since = 0L
            live.wdttSince = if (wdttUp) WdttController.since else 0L
            val tx = TrafficStats.getUidTxBytes(uid)
            val rx = TrafficStats.getUidRxBytes(uid)
            live.up = if (on) (tx - lastTx).coerceAtLeast(0) else 0
            live.down = if (on) (rx - lastRx).coerceAtLeast(0) else 0
            lastTx = tx; lastRx = rx
            live.now = System.currentTimeMillis()
            delay(1000)
        }
    }
    LaunchedEffect(wdttRunning) { live.wdtt = wdttRunning }

    val previewStep by remember(swipe) { derivedStateOf { swipe.value?.step } }
    val preview = previewStep?.let(neighbor)
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val pageWidth = constraints.maxWidth
        for (page in listOfNotNull(tab, preview)) {
            key(page) {
                val active = page == tab
                val semantics = if (active) Modifier else Modifier.clearAndSetSemantics {}
                Box(Modifier.fillMaxSize().absoluteOffset {
                    val drag = swipe.value
                    if (drag == null) IntOffset.Zero
                    else {
                        val progress = if (active) -drag.fraction else 1f - drag.fraction
                        IntOffset((progress * drag.step * pageWidth).roundToInt(), 0)
                    }
                }.then(semantics)) {
                    when (page) {
                        Tab.VPN -> VpnTab(model, live, dockClearance, active, serverPicker, onCheckUpdates)
                        Tab.WDTT -> WdttTab(model, live, dockClearance, active, serverPicker)
                        Tab.APPS -> AppsTab(model, live, dockClearance)
                        Tab.SETTINGS -> SettingsTab(model, showUpdates, dockClearance, active, settingsHomeRequest, onAppearanceChanged)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberVpnPermission(onGranted: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(ctx) == null) onGranted()
    }
    return {
        val i = VpnService.prepare(ctx)
        if (i == null) onGranted() else launcher.launch(i)
    }
}

@Composable
private fun PowerIcon(color: Color) {
    Canvas(Modifier.size(56.dp)) {
        val w = size.width * 0.09f
        val r = size.minDimension * 0.36f
        drawArc(color, -60f, 300f, false, topLeft = Offset(center.x - r, center.y - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
        drawLine(color, Offset(center.x, center.y - r * 1.15f), Offset(center.x, center.y - r * 0.15f), w, StrokeCap.Round)
    }
}

private fun ConnState.canSelectServer() = phase == Phase.OFF || phase == Phase.ON

@Composable
private fun VpnTab(
    model: AppModel, live: LiveState, dockClearance: Dp, active: Boolean,
    serverPicker: ServerPicker, onCheckUpdates: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = model.config
    val servers = cfg?.xray ?: emptyList()
    var savedIndex by remember { mutableIntStateOf(model.prefs.xrayIndex) }
    val index = savedIndex.coerceIn(0, servers.lastIndex.coerceAtLeast(0))
    val conn by Connection.state.collectAsState()
    val pings = remember(servers) { mutableStateMapOf<Int, Long>() }
    var pinging by remember(servers) { mutableStateOf(false) }

    fun pingServers() {
        if (pinging || servers.isEmpty()) return
        pinging = true
        pings.clear()
        scope.launch {
            try {
                XrayCore.pingAll(ctx, servers, model.prefs.pingUrl, model.prefs.pingMethod) { i, ms -> pings[i] = ms }
            } finally {
                pinging = false
            }
        }
    }
    LaunchedEffect(servers, active) { if (active && model.prefs.pingOnOpen) pingServers() }

    fun connect() {
        val s = servers.getOrNull(savedIndex.coerceIn(0, servers.lastIndex.coerceAtLeast(0))) ?: return
        model.askBatteryOnce()
        Connection.connectXray(ctx, s, Routing.profile(cfg, model.prefs.routingMode), Routing.profile(cfg, Routing.MODE_SMART)) {
            model.notify(it, ToastKind.Info)
        }
    }
    val askVpn = rememberVpnPermission { connect() }
    val mine = conn.mode == VpnMode.XRAY
    val globe = when {
        mine && conn.phase == Phase.ON -> GlobeState.On
        mine && (conn.phase == Phase.CONNECTING || conn.phase == Phase.STOPPING) -> GlobeState.Busy
        else -> GlobeState.Off
    }
    val user = cfg?.user
    val test = conn.test.takeIf { globe == GlobeState.On }
    VpnDashboard(
        time = if (globe == GlobeState.On && live.xraySince > 0L)
            duration((live.now - live.xraySince).coerceAtLeast(0L)).replace(':', ' ') else "00 00 00",
        upload = speed(if (globe == GlobeState.On) live.up else 0),
        download = speed(if (globe == GlobeState.On) live.down else 0),
        traffic = user?.let { "${bytes(it.up + it.down)} / ${if (it.total <= 0) "∞" else bytes(it.total)}" },
        expiry = user?.let(::subscriptionExpiry),
        dockClearance = dockClearance,
        globe = globe,
        powerLabel = when (globe) {
            GlobeState.Off -> tr("Подключиться")
            GlobeState.On -> tr("Отключить")
            GlobeState.Busy -> tr("Подключение...")
        },
        onPower = {
            when {
                globe != GlobeState.Off -> Connection.disconnect(ctx)
                cfg?.error == "device_limit" -> model.deviceLimit = true
                cfg?.error == "expired" -> model.notify(AppApi.errorText("expired"), ToastKind.Error)
                servers.isEmpty() -> model.notify(AppApi.errorText(cfg?.error) ?: tr("Нет серверов. Нажмите «Обновить подписку»"), ToastKind.Error)
                else -> askVpn()
            }
        },
        refresh = {
            SubscriptionRefresh(loading = model.loading) {
                scope.launch {
                    if (model.refresh() && model.config?.error == null) {
                        model.notify(tr("Подписка обновлена"), ToastKind.Success)
                    }
                }
            }
        },
        names = remember(servers) { servers.map { it.name } },
        selected = index,
        canSelect = conn.canSelectServer(),
        active = active,
        serverPicker = serverPicker,
        pings = pings,
        pinging = pinging,
        onPing = ::pingServers,
        onSelect = { i ->
            if (i != index && Connection.state.value.canSelectServer()) {
                savedIndex = i
                model.prefs.xrayIndex = i
                if (Connection.state.value.mode == VpnMode.XRAY) askVpn()
            }
        },
        connectionPing = test?.latencyMs,
        testing = test?.running == true,
        onTest = { Connection.test(ctx) }
    ) {
        StatusLine(conn, VpnMode.XRAY, onCheckUpdates, inlineTest = false)
    }
}

internal fun subscriptionExpiry(u: UserInfo): Pair<String, Color> {
    val expiry = maxOf(u.expiresAt, u.paidUntil)
    val days = if (expiry <= 0) null else ((expiry - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
    return when {
        !u.enabled -> tr("Подписка выключена") to Danger
        days == null -> tr("Подписка бессрочная") to Muted
        else -> {
            val date = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(expiry))
            daysLeftText(days, date).substringBefore('·').trimEnd() to
                (if (days <= 3) Color(0xFFFFC233) else Color.White)
        }
    }
}

@Composable
private fun SubscriptionRefresh(loading: Boolean, refresh: () -> Unit) {
    val label = tr("Обновить подписку")
    var click by remember { mutableIntStateOf(0) }
    val motion = rememberIconMotion(click.takeIf { it > 0 }, RefreshIconDuration)
    IconButton(
        onClick = { click++; refresh() },
        enabled = !loading,
        modifier = Modifier.semantics { contentDescription = label }
    ) {
        if (loading && motion.value >= RefreshIconDuration) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            MotionIcon(MotionGlyph.Refresh, Accent, time = { motion.value }, modifier = Modifier.size(32.dp))
        }
    }
}

/** Строка под кнопкой: ошибка, «другой режим включён» или результат проверки соединения. */
@Composable
private fun StatusLine(conn: ConnState, tabMode: VpnMode, onCheckUpdates: (() -> Unit)? = null, inlineTest: Boolean = true) {
    val ctx = LocalContext.current
    val error = conn.error?.takeIf { conn.phase == Phase.OFF && conn.errorMode == tabMode }
    val failedTest = conn.test?.takeIf { conn.phase == Phase.ON && conn.mode == tabMode && !it.ok && !it.running }
    if (error != null || failedTest != null) {
        val message = error?.let { tr("Ошибка: ") + it } ?: failedTest?.text.orEmpty()
        var showDetails by remember(message) { mutableStateOf(false) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message,
                    modifier = Modifier.weight(1f, fill = false)
                        .clickable { showDetails = true }.padding(vertical = 6.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = Danger, fontSize = 13.sp
                )
                if (failedTest != null && inlineTest) {
                    TextButton(onClick = { Connection.test(ctx) }) { Text(tr("Проверить"), fontSize = 13.sp) }
                }
            }
            if (onCheckUpdates != null) {
                Text(
                    tr("Настройки") + " → " + tr("Проверить обновления"),
                    modifier = Modifier.clickable(onClick = onCheckUpdates).padding(vertical = 6.dp),
                    color = Accent, fontSize = 13.sp, textAlign = TextAlign.Center
                )
            }
        }
        if (showDetails) {
            AlertDialog(
                onDismissRequest = { showDetails = false },
                text = {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) { Text(message) }
                },
                confirmButton = { TextButton(onClick = { showDetails = false }) { Text(tr("Закрыть")) } }
            )
        }
        return
    }
    when {
        conn.mode != null && conn.mode != tabMode && conn.phase != Phase.OFF ->
            Text(
                if (tabMode == VpnMode.XRAY) tr("Сейчас включён резервный VPN. Нажмите кнопку — переключимся на обычный")
                else tr("Сейчас включён обычный VPN. Нажмите кнопку — переключимся на резервный"),
                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center
            )
        inlineTest && conn.mode == tabMode && conn.phase == Phase.ON -> Row(verticalAlignment = Alignment.CenterVertically) {
            val t = conn.test
            if (t != null) {
                if (t.running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                else Text("●", color = if (t.ok) Accent else Danger, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(t.text, color = if (t.ok || t.running) Muted else Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            if (t?.running != true) {
                TextButton(onClick = { Connection.test(ctx) }) { Text(tr("Проверить"), color = Accent, fontSize = 13.sp) }
            }
        }
    }
}


@Composable
private fun WdttTab(model: AppModel, live: LiveState, dockClearance: Dp, active: Boolean, serverPicker: ServerPicker) {
    val ctx = LocalContext.current
    val cfg = model.config
    val servers = cfg?.wdtt.orEmpty()
    val hash = cfg?.vkHash.orEmpty()
    var savedIndex by remember { mutableIntStateOf(model.prefs.wdttIndex) }
    val switching by TunnelManager.switchingServer.collectAsState()
    val activePeer by TunnelManager.selectedPeer.collectAsState()
    LaunchedEffect(activePeer, switching, servers) {
        if (!switching && TunnelManager.running.value) {
            servers.indexOfFirst { "${it.host}:${it.port}" == activePeer }.takeIf { it >= 0 }?.let {
                savedIndex = it
                model.prefs.wdttIndex = it
            }
        }
    }
    val index = savedIndex.coerceIn(0, servers.lastIndex.coerceAtLeast(0))
    val expanded = active && serverPicker.expanded
    BackHandler(expanded) { serverPicker.expanded = false }
    val workers by TunnelManager.activeWorkers.collectAsState()
    val conn by Connection.state.collectAsState()
    LaunchedEffect(conn.canSelectServer(), servers.isEmpty()) {
        if (!conn.canSelectServer() || servers.isEmpty()) serverPicker.expanded = false
    }
    val mine = conn.mode == VpnMode.WDTT
    val connected = mine && conn.phase == Phase.ON
    val transitioning = mine && (conn.phase == Phase.CONNECTING || conn.phase == Phase.STOPPING)
    val signal = if (connected || transitioning) Color(0xFFFFC36B) else Muted
    val count = if (mine) WdttController.activeWorkerLimit else model.prefs.wdttWorkers
    val askVpn = rememberVpnPermission {
        servers.getOrNull(savedIndex.coerceIn(0, servers.lastIndex.coerceAtLeast(0)))?.let {
            model.askBatteryOnce()
            Connection.connectWdtt(ctx, it, hash)
        }
    }
    val test = conn.test.takeIf { connected }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)
            .padding(top = 20.dp, bottom = dockClearance + 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row {
                Text("Lite ", color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Light)
                Text("404", fontSize = 24.sp, fontFamily = FontFamily.Monospace)
            }
            cfg?.user?.let {
                val (label, color) = subscriptionExpiry(it)
                Text(label, Modifier.padding(start = 12.dp), color = color, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(21.dp))
        Text(tr("Резерв"), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(14.dp))
        Text("●  " + tr(when {
            switching -> "Переключение сервера..."
            connected -> "Подключено"
            transitioning -> "Подключение..."
            else -> "Отключено"
        }), color = signal, fontSize = 12.sp)
        Text(if (connected && live.wdttSince > 0L) duration(live.now - live.wdttSince).replace(':', ' ') else "00 00 00",
            fontSize = 34.sp, lineHeight = 40.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
        Text("↓  " + speed(if (connected) live.down else 0) + "     ↑  " + speed(if (connected) live.up else 0),
            color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(17.dp))
        Box(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ReservePower(signal, if (mine && conn.phase != Phase.OFF) tr("Отключить") else tr("Подключиться")) {
                    when {
                        mine && conn.phase != Phase.OFF -> Connection.disconnect(ctx)
                        cfg?.error == "expired" -> model.notify(AppApi.errorText("expired"), ToastKind.Error)
                        hash.isBlank() -> model.notify(tr("Добавьте хеш VK в боте"), ToastKind.Error)
                        servers.isEmpty() -> model.notify(tr("Для вас нет серверов резервного VPN"), ToastKind.Error)
                        else -> askVpn()
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MotionIcon(MotionGlyph.Reserve, signal, time = { DockIconDuration.toFloat() }, modifier = Modifier.size(19.dp))
                    Text("${if (mine) workers else 0} / $count", color = signal, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(tr("потоков"), color = Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(14.dp))
                Spacer(Modifier.height(58.dp))
                TextButton(enabled = connected && !expanded && test?.running != true, onClick = { Connection.test(ctx) }) {
                    if (test?.running == true) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp, color = signal)
                    else Speedometer(signal, 17.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Проверить"))
                }
                test?.takeIf { it.ok }?.let { Text(it.text, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center) }
            }
            VpnServerPanel(
                names = remember(servers) { servers.map { it.name } },
                selected = index,
                enabled = active && conn.canSelectServer(),
                expanded = expanded,
                onExpanded = { serverPicker.expanded = it },
                pings = emptyMap(), pinging = false, onPing = {},
                onSelect = { i ->
                    if (i != index && Connection.state.value.canSelectServer()) {
                        savedIndex = i
                        model.prefs.wdttIndex = i
                        if (Connection.state.value.mode == VpnMode.WDTT) askVpn()
                    }
                },
                anchorY = 150.dp + 12.dp + 19.dp + 14.dp,
                maxReveal = 112.dp,
                onPositioned = { if (active) serverPicker.panelCoordinates = it },
                showPing = false
            )
        }
        StatusLine(conn, VpnMode.WDTT, inlineTest = false)
        if (hash.isBlank()) {
            Text(tr("Хеш VK не задан. Добавьте его в боте и нажмите «Обновить подписку» на вкладке VPN."),
                color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            TextButton(onClick = { openUrl(ctx, BOT_URL) }) { Text(tr("Открыть бота")) }
        }
    }
}

@Composable
private fun ReservePower(signal: Color, label: String, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val held by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (held) .962f else 1f, label = "Reserve power")
    Box(
        Modifier.size(150.dp).semantics { contentDescription = label }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize().graphicsLayer { scaleX = scale; scaleY = scale }) {
            val ring = size.minDimension * .48f
            drawCircle(signal.copy(alpha = .07f), radius = ring)
            drawCircle(signal.copy(alpha = .65f), radius = ring, style = Stroke(1.dp.toPx()))
            drawCircle(signal.copy(alpha = .28f), radius = ring * .88f,
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
        }
        Box(Modifier.size(78.dp).graphicsLayer { scaleX = scale; scaleY = scale }.background(signal, CircleShape),
            contentAlignment = Alignment.Center) { PowerIcon(Color.Black) }
    }
}

private data class AppItem(val pkg: String, val label: String, val system: Boolean, val launchable: Boolean)

@Composable
private fun AppsTab(model: AppModel, live: LiveState, dockClearance: Dp) {
    val ctx = LocalContext.current
    val prefs = model.prefs
    val pm = ctx.packageManager
    val modes = remember { listOf("off", "exclude", "include") }
    var mode by remember { mutableStateOf(prefs.appsMode) }
    var selected by remember { mutableStateOf(prefs.appsFor(mode)) }
    var sortedSelection by remember { mutableStateOf(selected) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    val glass = LocalGlass.current
    val all by produceState<List<AppItem>?>(null) {
        value = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(0).asSequence()
                .filter { it.packageName != ctx.packageName }
                .map {
                    val launchable = pm.getLaunchIntentForPackage(it.packageName) != null
                    val system = (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    AppItem(it.packageName, it.loadLabel(pm).toString(), system && !launchable, launchable)
                }.sortedBy { it.label.lowercase() }.toList()
        }
    }
    fun save(nextMode: String, set: Set<String>) {
        if (mode == nextMode && selected == set) return
        if (mode != nextMode) sortedSelection = set
        mode = nextMode
        selected = set
        prefs.saveApps(nextMode, set)
        if (live.xray == "running" || live.wdtt) changed = true
    }
    LaunchedEffect(selected, glass) {
        if (glass && (selected - sortedSelection).isNotEmpty()) delay(CheckmarkDuration.toLong())
        sortedSelection = selected
    }
    val list = remember(all, showSystem, selected, sortedSelection, query) {
        all.orEmpty().filter {
            (showSystem || !it.system || it.pkg in selected) &&
                (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
        }.sortedByDescending { it.pkg in sortedSelection }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = dockClearance + 10.dp)
    ) {
        item {
            Text(tr("Приложения"), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(tr("Маршрутизация для основного и резервного VPN"), color = Muted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 13.dp))
            GlassSegments(listOf(tr("Все через VPN"), tr("Все кроме выбранных"), tr("Только выбранные")),
                modes.indexOf(mode).coerceAtLeast(0),
                { option ->
                    val next = modes[option]
                    save(next, if (next == "off") emptySet() else prefs.appsFor(next))
                }, icon = { index, color -> AppModeIcon(index, color) })
        }
        if (changed) item {
            Text(tr("Переподключите VPN, чтобы изменения заработали"), color = Color(0xFFFFC233), fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 7.dp))
        }
        if (mode == "include" && selected.isEmpty()) item {
            Text(tr("Выберите хотя бы одно приложение — пока все идут через VPN"), color = Color(0xFFFFC233), fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (mode != "off") {
            item {
                AppsSearch(query) { query = it }
                PreferenceSwitch("Показывать системные", showSystem) { showSystem = it }
                if (mode == "exclude") {
                    TextButton(enabled = all != null, onClick = {
                        val suggested = all.orEmpty().asSequence().filter { it.launchable && AutoApps.matches(it.pkg, it.label) }
                            .map { it.pkg }.toSet()
                        val updated = selected + suggested
                        save("exclude", updated)
                        model.notify(if (suggested.isEmpty()) tr("Рекомендуемые приложения не найдены")
                            else tr("Рекомендуемые приложения отмечены: %s", suggested.size), ToastKind.Info)
                    }) {
                        Icon(Icons.Default.Star, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(tr("Выбрать рекомендуемые"))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Выбрано: %s", selected.size), color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(enabled = selected.isNotEmpty(), onClick = { clear = true }) { Text(tr("Очистить выбор")) }
                }
            }
            if (all == null) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (list.isEmpty()) item {
                Text(tr("Приложения не найдены"), color = Muted, modifier = Modifier.padding(12.dp))
            }
            items(list, key = { it.pkg }) { app ->
                val on = app.pkg in selected
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 2.dp).heightIn(min = 53.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Card)
                        .toggleable(value = on, role = Role.Checkbox) {
                            save(mode, if (it) selected + app.pkg else selected - app.pkg)
                        }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(app.pkg)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.pkg, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    AppSelectionMark(on, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    if (clear) AlertDialog(
        onDismissRequest = { clear = false },
        title = { Text(tr("Очистить выбор приложений?")) },
        confirmButton = { TextButton(onClick = { clear = false; save(mode, emptySet()) }) { Text(tr("Очистить"), color = Danger) } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text(tr("Отмена")) } }
    )
}

@Composable
private fun AppsSearch(query: String, onChange: (String) -> Unit) {
    val glass = LocalGlass.current
    val color = Accent
    val surface = Card
    val shape = RoundedCornerShape(13.dp)
    var focused by remember { mutableStateOf(false) }
    val backdrop = if (glass) Modifier.background(Brush.linearGradient(listOf(Color.White.copy(alpha = .081f), Color.Transparent, Color.White.copy(alpha = .048f))))
        .pointerSheen() else Modifier
    Row(Modifier.fillMaxWidth().padding(top = 13.dp).heightIn(min = 48.dp).clip(shape).background(surface)
        .then(backdrop).border(1.dp, if (focused) color.copy(alpha = .68f) else Color.White.copy(alpha = .10f), shape)
        .padding(start = 11.dp, end = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = if (focused) color else Muted, modifier = Modifier.size(20.dp))
        BasicTextField(query, onChange,
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }.padding(vertical = 9.dp)
                .semantics { contentDescription = tr("Поиск приложений") },
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(color),
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { field ->
                if (query.isEmpty()) Text(tr("Поиск приложений"), color = Muted, fontSize = 15.sp)
                field()
            })
        if (query.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Default.Close, tr("Очистить поиск"), tint = Muted, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun AppModeIcon(index: Int, color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val unit = size.minDimension / 24
        fun point(x: Int, y: Int) = Offset(x * unit, y * unit)
        val stroke = Stroke(1.7f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x: Int, y: Int, x2: Int, y2: Int) =
            drawLine(color, point(x, y), point(x2, y2), stroke.width, cap = StrokeCap.Round)
        fun check(x: Int, y: Int) {
            line(x, y + 2, x + 2, y + 4)
            line(x + 2, y + 4, x + 6, y)
        }
        if (index == 2) {
            check(2, 3); check(2, 12)
            line(12, 6, 22, 6); line(12, 15, 22, 15); line(12, 21, 22, 21)
        } else {
            val shield = Path().apply {
                moveTo(12 * unit, 2 * unit)
                lineTo(20 * unit, 6 * unit)
                lineTo(20 * unit, 12 * unit)
                quadraticTo(20 * unit, 18 * unit, 12 * unit, 22 * unit)
                quadraticTo(4 * unit, 18 * unit, 4 * unit, 12 * unit)
                lineTo(4 * unit, 6 * unit)
                close()
            }
            drawPath(shield, color, style = stroke)
            if (index == 0) check(9, 9) else line(9, 12, 15, 12)
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = ctx.packageManager.getApplicationIcon(pkg)
                val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                d.setBounds(0, 0, 96, 96); d.draw(c)
                bmp.asImageBitmap()
            }.getOrNull()
        }
    }
    val m = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
    img?.let { androidx.compose.foundation.Image(it, null, m) } ?: Box(m.background(Color(0xFF2A2A2E)))
}

/** «Проверить обновления»: проверка → окно с версией → загрузка → установщик Android. */
@Composable
internal fun UpdateBlock(model: AppModel, active: Boolean) {
    val ctx = LocalContext.current
    val st by Updater.state.collectAsState()
    when (val s = st) {
        is Updater.State.Checking -> Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(tr("Проверяем обновления…"), color = Muted, fontSize = 14.sp)
        }
        is Updater.State.Downloading -> Column(Modifier.padding(vertical = 8.dp)) {
            Text(tr("Скачиваем обновление… %s%", (s.progress * 100).toInt()), fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { Updater.cancel(ctx) }) { Text(tr("Отмена")) }
        }
        else -> TextButton(onClick = { Updater.check() }) { Text(tr("Проверить обновления")) }
    }
    if (st is Updater.State.UpToDate) Text(tr("У вас последняя версия"), color = Muted, fontSize = 13.sp)
    if (!active) return
    when (val s = st) {
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            title = { Text(tr("Доступна версия %s", s.release.version)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (s.release.notes.isNotBlank()) { Text(s.release.notes, fontSize = 14.sp); Spacer(Modifier.height(10.dp)) }
                    Text(tr("Размер: %s МБ", String.format(Locale.US, "%.1f", s.release.size / 1048576.0)), color = Muted, fontSize = 13.sp)
                    Text(tr("Во время установки VPN отключится — после обновления включите его снова."), color = Muted, fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton(onClick = { Updater.download(ctx, s.release) }) { Text(tr("Обновить")) } },
            dismissButton = { TextButton(onClick = { Updater.dismiss() }) { Text(tr("Позже")) } }
        )
        is Updater.State.Ready -> {
            // Android 8+ отдельно спрашивает, можно ли этому приложению ставить APK; ждём разрешения и ставим сами
            var allowed by remember { mutableStateOf(Updater.canInstall(ctx)) }
            LaunchedEffect(s.file) { while (true) { allowed = Updater.canInstall(ctx); delay(1000) } }
            LaunchedEffect(s.file, allowed) { if (allowed) Updater.install(ctx, s.file) }
            AlertDialog(
                onDismissRequest = { Updater.dismiss() },
                title = { Text(tr("Обновление скачано")) },
                text = {
                    Text(
                        if (allowed) tr("Подтвердите установку в окне Android.")
                        else tr("Разрешите Lite 404 VPN устанавливать приложения: включите переключатель и вернитесь назад — установка начнётся сама.")
                    )
                },
                confirmButton = {
                    TextButton(onClick = { if (allowed) Updater.install(ctx, s.file) else Updater.openInstallPermission(ctx) }) {
                        Text(if (allowed) tr("Установить") else tr("Открыть настройки"))
                    }
                },
                dismissButton = { TextButton(onClick = { Updater.dismiss() }) { Text(tr("Позже")) } }
            )
        }
        is Updater.State.Failed -> LaunchedEffect(s) { model.notify(s.message, ToastKind.Error); Updater.dismiss() }
        else -> {}
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp), content = content)
    Spacer(Modifier.height(16.dp))
}

@Composable
internal fun ListsScreen(model: AppModel, dockClearance: Dp, onBack: () -> Unit, reserve: Boolean = false) {
    val prefs = model.prefs
    var ready by remember { mutableStateOf(if (reserve) prefs.wdttReadyLists else prefs.readyLists) }
    var direct by remember { mutableStateOf(if (reserve) prefs.wdttDirect else prefs.customDirect) }
    var proxy by remember { mutableStateOf(if (reserve) prefs.wdttProxy else prefs.customProxy) }
    val geoReady = Routing.profile(model.config, Routing.MODE_SMART) != null
    Column(Modifier.fillMaxSize().pageEntrance().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        .padding(top = 20.dp, bottom = dockClearance + 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Назад"), tint = Accent) }
            Text(tr("Свои списки"), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(tr(if (reserve) "Правила резервного VPN" else "Правила основного VPN"), color = Muted, fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        SettingsBlock("Готовые списки — напрямую") {
            if (!geoReady) Text(tr("Не настроено на сервере"), color = Muted, fontSize = 13.sp)
            Lists.READY.forEach { list ->
                val checked = list.id in ready
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .toggleable(checked, enabled = geoReady, role = Role.Checkbox) {
                        ready = if (it) ready + list.id else ready - list.id
                        if (reserve) prefs.wdttReadyLists = ready else prefs.readyLists = ready
                    }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(list.title), fontSize = 15.sp)
                        Text(tr(list.hint), color = Muted, fontSize = 12.sp)
                    }
                    AppSelectionMark(checked, Modifier.padding(start = 10.dp))
                }
            }
        }
        RouteEditor("Напрямую", direct, {
            direct = it
            if (reserve) prefs.wdttDirect = it else prefs.customDirect = it
        }, "example.ru\n203.0.113.0/24")
        Spacer(Modifier.height(14.dp))
        RouteEditor("Через VPN", proxy, {
            proxy = it
            if (reserve) prefs.wdttProxy = it else prefs.customProxy = it
        }, "example.com")
        Text(tr("Сайты, IP-адреса и подсети — по одному в строке. Правила «Через VPN» имеют приоритет."),
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
        Text(tr("Изменения применятся при следующем подключении."), color = Muted, fontSize = 12.sp)
    }
}

// ───────────────────────── утилиты ─────────────────────────

internal fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun bytes(b: Long): String {
    if (b < 1024) return "$b " + tr("Б")
    val u = listOf(tr("КБ"), tr("МБ"), tr("ГБ"), tr("ТБ"))
    var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, u[i])
}

private fun speed(bps: Long): String = bytes(bps) + tr("/с")

private fun duration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

private fun daysLeftText(days: Long, date: String): String =
    if (I18n.lang == "ru") "Осталось $days ${plural(days, "день", "дня", "дней")} · до $date"
    else tr("Осталось %s · до %s", days, date)

private fun plural(n: Long, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1L && m100 != 11L -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}


// ---------- Всплывающие уведомления сверху ----------

enum class ToastKind { Success, Error, Info }

class ToastData(val text: String, val kind: ToastKind) {
    val id: Long = System.nanoTime()
}

/** Плашка, выезжающая сверху: тёмная карточка, цветная иконка, автоскрытие. Нажатие — скрыть. */
@Composable
private fun TopToastHost(model: AppModel, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<ToastData?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(model.toast) {
        val t = model.toast ?: return@LaunchedEffect
        if (visible) { visible = false; delay(220) }
        shown = t
        visible = true
        delay(if (t.kind == ToastKind.Error) 3500L else 2500L)
        visible = false
        delay(300)
        if (model.toast?.id == t.id) model.toast = null
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(280)) { -it } + fadeIn(tween(280)),
        exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(220)),
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        val t = shown ?: return@AnimatedVisibility
        val tint = when (t.kind) {
            ToastKind.Success -> Accent
            ToastKind.Error -> Danger
            ToastKind.Info -> Color.White
        }
        val icon = when (t.kind) {
            ToastKind.Success -> Icons.Filled.CheckCircle
            ToastKind.Error -> Icons.Filled.Warning
            ToastKind.Info -> Icons.Filled.Info
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Card,
            border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth().clickable { visible = false }
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Text(t.text, color = Color.White, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
