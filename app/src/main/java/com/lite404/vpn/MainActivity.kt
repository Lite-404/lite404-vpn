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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0B0B0C)
private val Card = Color(0xFF17171A)
private val Accent = Color(0xFFB4FF39)
private val Muted = Color(0xFF8A8A90)
private val Danger = Color(0xFFFF5A5A)

private val colors = darkColorScheme(
    primary = Accent, onPrimary = Color.Black, background = Bg, onBackground = Color.White,
    surface = Card, onSurface = Color.White, surfaceVariant = Card, onSurfaceVariant = Muted,
    secondaryContainer = Color(0xFF2A3A10), onSecondaryContainer = Accent, error = Danger
)

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
        loading = true
        try {
            val body = withContext(Dispatchers.IO) { AppApi.fetch(ctx, t) }
            val now = System.currentTimeMillis()
            val c = AppConfig.parse(body, now)
            prefs.configJson = body
            prefs.configAt = now
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
}

class MainActivity : ComponentActivity() {
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
    }

    override fun onStop() {
        super.onStop()
        activeActivities--
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        I18n.init(this)
        TunnelManager.initObservers(this)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (I18n.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = colors) {
                    Surface(Modifier.fillMaxSize(), color = Bg) { Root() }
                }
            }
        }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val model = remember { AppModel(ctx.applicationContext) }
    // разрешение на уведомления (Android 13+)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (model.token != null) model.refresh()
    }
    var tab by rememberSaveable { mutableStateOf(Tab.VPN) }
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (model.token != null) NavigationBar(containerColor = Card) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = {
                            Text(tr(t.title), fontSize = 11.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                        },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (model.token == null) TokenScreen(model) else MainScreen(model, tab)
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

private enum class Tab(val title: String, val icon: ImageVector) {
    VPN("VPN", Icons.Default.Lock),
    WDTT("Резерв", Icons.Default.Warning),
    APPS("Приложения", Icons.AutoMirrored.Filled.List),
    SETTINGS("Настройки", Icons.Default.Settings)
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
private fun MainScreen(model: AppModel, tab: Tab) {
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
            val wdttUp = live.wdtt && TunnelManager.activeWorkers.value > 0
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

    when (tab) {
        Tab.VPN -> VpnTab(model, live)
        Tab.WDTT -> WdttTab(model, live)
        Tab.APPS -> AppsTab(model, live)
        Tab.SETTINGS -> SettingsTab(model)
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
private fun SpeedRow(live: LiveState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        SpeedCell("↑", speed(if (live.wdtt) live.up else 0))
        SpeedCell(tr("время"), if (live.wdttSince == 0L) "00:00:00" else duration(live.now - live.wdttSince))
        SpeedCell("↓", speed(if (live.wdtt) live.down else 0))
    }
}

@Composable
private fun SpeedCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PowerButton(state: String, onClick: () -> Unit) {
    // state: on | connecting | off
    val ring = when (state) { "on" -> Accent; "connecting" -> Color(0xFFFFC233); else -> Color(0xFF3A3A40) }
    Box(
        Modifier.size(170.dp).clip(CircleShape).background(Card).border(4.dp, ring, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PowerIcon(ring)
            Text(
                when (state) { "on" -> "ON"; "connecting" -> "…"; else -> "OFF" },
                color = ring, fontWeight = FontWeight.Bold, fontSize = 18.sp
            )
        }
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

@Composable
private fun <T> Picker(label: String, items: List<T>, selected: Int, name: (T) -> String, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable(enabled = items.size > 1) { open = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Muted, fontSize = 12.sp)
                Text(items.getOrNull(selected)?.let(name) ?: "—", fontSize = 16.sp)
            }
            if (items.size > 1) Icon(Icons.Default.ArrowDropDown, null, tint = Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { i, it ->
                DropdownMenuItem(text = { Text(name(it)) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}

@Composable
private fun VpnTab(model: AppModel, live: LiveState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = model.config
    val servers = cfg?.xray ?: emptyList()
    var index by remember { mutableIntStateOf(model.prefs.xrayIndex.coerceIn(0, (servers.size - 1).coerceAtLeast(0))) }
    val conn by Connection.state.collectAsState()
    val pings = remember { mutableStateMapOf<Int, Long>() }
    var pinging by remember { mutableStateOf(false) }

    fun pingServers() {
        if (pinging || servers.isEmpty()) return
        pinging = true
        pings.clear()
        scope.launch {
            try {
                pings.putAll(XrayCore.pingAll(ctx, servers, model.prefs.pingUrl))
            } finally {
                pinging = false
            }
        }
    }
    LaunchedEffect(servers.size) { if (model.prefs.pingOnOpen) pingServers() }

    fun connect() {
        val s = servers.getOrNull(index) ?: return
        model.askBatteryOnce()
        Connection.connectXray(ctx, s, Routing.profile(cfg, model.prefs.routingMode)) { model.notify(it, ToastKind.Info) }
    }
    val askVpn = rememberVpnPermission { connect() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Logo()
        Spacer(Modifier.height(48.dp))
        SpeedCell(tr("время подключения"), if (live.xraySince == 0L) "00:00:00" else duration(live.now - live.xraySince))
        Spacer(Modifier.height(20.dp))
        cfg?.let { DaysLeft(it.user) }
        Spacer(Modifier.height(16.dp))
        val mine = conn.mode == VpnMode.XRAY
        val st = when {
            mine && conn.phase == Phase.ON -> "on"
            mine && (conn.phase == Phase.CONNECTING || conn.phase == Phase.STOPPING) -> "connecting"
            else -> "off"
        }
        PowerButton(st) {
            when {
                st != "off" -> Connection.disconnect(ctx)
                cfg?.error == "device_limit" -> model.deviceLimit = true
                cfg?.error == "expired" -> model.notify(AppApi.errorText("expired"), ToastKind.Error)
                servers.isEmpty() -> model.notify(AppApi.errorText(cfg?.error) ?: tr("Нет серверов. Нажмите «Обновить подписку»"), ToastKind.Error)
                else -> askVpn()
            }
        }
        Spacer(Modifier.height(10.dp))
        StatusLine(conn, VpnMode.XRAY)
        Spacer(Modifier.height(18.dp))
        // трафик слева, обновление подписки — иконкой справа
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(tr("Трафик"), color = Muted, fontSize = 12.sp)
                val u = cfg?.user
                Text(
                    if (u == null) "—" else "${bytes(u.up + u.down)} / ${if (u.total <= 0) "∞" else bytes(u.total)}",
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(onClick = { scope.launch { if (model.refresh() && model.config?.error == null) model.notify(tr("Подписка обновлена"), ToastKind.Success) } }, enabled = !model.loading) {
                if (model.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, tr("Обновить подписку"), tint = Accent)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Серверы"), color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { pingServers() }, enabled = !pinging && servers.isNotEmpty()) {
                if (pinging) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text(tr("Пинг"), color = Accent, fontSize = 13.sp)
            }
        }
        if (servers.isEmpty()) Text(tr("Нет серверов"), color = Muted, modifier = Modifier.fillMaxWidth().padding(4.dp))
        servers.forEachIndexed { i, srv ->
            ServerRow(srv.name, i == index, pings[i], pinging && i !in pings) {
                if (i != index) {
                    index = i; model.prefs.xrayIndex = i
                    if (mine && conn.phase == Phase.ON) askVpn() // переподключение к новому серверу
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Строка под кнопкой: ошибка, «другой режим включён» или результат проверки соединения. */
@Composable
private fun StatusLine(conn: ConnState, tabMode: VpnMode) {
    val ctx = LocalContext.current
    when {
        conn.mode == null && conn.error != null && conn.phase == Phase.OFF ->
            Text(tr("Ошибка: ") + conn.error, color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
        conn.mode != null && conn.mode != tabMode && conn.phase != Phase.OFF ->
            Text(
                if (tabMode == VpnMode.XRAY) tr("Сейчас включён резервный VPN. Нажмите кнопку — переключимся на обычный")
                else tr("Сейчас включён обычный VPN. Нажмите кнопку — переключимся на резервный"),
                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center
            )
        conn.mode == tabMode && conn.phase == Phase.ON -> Row(verticalAlignment = Alignment.CenterVertically) {
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
private fun ServerRow(name: String, selected: Boolean, ping: Long?, pinging: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card)
            .border(1.5.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 16.sp, modifier = Modifier.weight(1f))
        when {
            pinging -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Muted)
            ping == null -> Unit
            ping < 0 -> Text(tr("нет ответа"), color = Danger, fontSize = 13.sp)
            else -> Text(
                tr("%s мс", ping), fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                color = when { ping < 250 -> Accent; ping < 600 -> Color(0xFFFFC233); else -> Danger }
            )
        }
        Spacer(Modifier.width(12.dp))
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun DaysLeft(u: UserInfo) {
    val days = if (u.expiresAt <= 0) null else ((u.expiresAt - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
    val (text, color) = when {
        !u.enabled -> tr("Подписка выключена") to Danger
        days == null -> tr("Подписка бессрочная") to Muted
        else -> daysLeftText(days, SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(u.expiresAt))) to
            (if (days <= 3) Color(0xFFFFC233) else Muted)
    }
    Text(text, color = color, fontSize = 14.sp, textAlign = TextAlign.Center)
}

/** Логотип: «Lite» и «VPN» акцентным цветом, крупное «404». */
@Composable
private fun Logo() {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
        Text("Lite", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            modifier = Modifier.padding(bottom = 9.dp, end = 6.dp))
        Text("404", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
            letterSpacing = (-4).sp, lineHeight = 56.sp)
        Text("VPN", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp,
            modifier = Modifier.padding(bottom = 9.dp, start = 8.dp))
    }
}

@Composable
private fun InfoLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Muted, modifier = Modifier.width(110.dp), fontSize = 14.sp)
        Text(v, fontSize = 14.sp)
    }
}

@Composable
private fun Header(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun WdttTab(model: AppModel, live: LiveState) {
    val ctx = LocalContext.current
    val cfg = model.config
    val servers = cfg?.wdtt ?: emptyList()
    val hash = cfg?.vkHash ?: ""
    var index by remember { mutableIntStateOf(model.prefs.wdttIndex.coerceIn(0, (servers.size - 1).coerceAtLeast(0))) }
    val stats by TunnelManager.stats.collectAsState()
    val conn by Connection.state.collectAsState()

    fun connect() {
        val s = servers.getOrNull(index) ?: return
        model.askBatteryOnce()
        Connection.connectWdtt(ctx, s, hash)
    }
    val askVpn = rememberVpnPermission { connect() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Header(tr("Резервный VPN через VK"), tr("Включайте, когда другие VPN не подключаются"))
        Spacer(Modifier.height(12.dp))
        SpeedRow(live)
        Spacer(Modifier.height(28.dp))
        val mine = conn.mode == VpnMode.WDTT
        val st = when {
            mine && conn.phase == Phase.ON -> "on"
            mine && (conn.phase == Phase.CONNECTING || conn.phase == Phase.STOPPING) -> "connecting"
            else -> "off"
        }
        PowerButton(st) {
            when {
                st != "off" -> Connection.disconnect(ctx)
                cfg?.error == "expired" -> model.notify(AppApi.errorText("expired"), ToastKind.Error)
                hash.isBlank() -> model.notify(tr("Добавьте хеш VK в боте"), ToastKind.Error)
                servers.isEmpty() -> model.notify(tr("Для вас нет серверов резервного VPN"), ToastKind.Error)
                else -> askVpn()
            }
        }
        Spacer(Modifier.height(10.dp))
        StatusLine(conn, VpnMode.WDTT)
        if (mine && conn.phase != Phase.OFF) Text(stats, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        if (hash.isBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(tr("Хеш VK не задан. Добавьте его в боте и нажмите «Обновить подписку» на вкладке VPN."), color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            TextButton(onClick = { openUrl(ctx, BOT_URL) }) { Text(tr("Открыть бота")) }
        }
        Spacer(Modifier.height(24.dp))
        Picker(tr("Страна"), servers, index, { it.name }) {
            index = it; model.prefs.wdttIndex = it
            // включён WDTT — сразу переподключаемся к новой стране
            if (mine && conn.phase == Phase.ON) askVpn()
        }
    }
}

private data class AppItem(val pkg: String, val label: String, val system: Boolean)

@Composable
private fun AppsTab(model: AppModel, live: LiveState) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    var mode by remember { mutableStateOf(model.prefs.appsMode) }
    var selected by remember { mutableStateOf(model.prefs.apps) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    val all by produceState<List<AppItem>?>(null) {
        value = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(0)
                .filter { it.packageName != ctx.packageName }
                .map {
                    val sys = (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    AppItem(it.packageName, it.loadLabel(pm).toString(), sys && pm.getLaunchIntentForPackage(it.packageName) == null)
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    fun save(m: String = mode, set: Set<String> = selected) {
        mode = m; selected = set
        model.prefs.appsMode = m; model.prefs.apps = set
        if (live.xray == "running" || live.wdtt) changed = true
    }
    val list = all?.filter {
        (showSystem || !it.system || it.pkg in selected) &&
            (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
    }?.sortedByDescending { it.pkg in selected } ?: emptyList()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(tr("Приложения"), tr("Какие приложения идут через VPN. Действует и для обычного, и для резервного VPN"))
        Spacer(Modifier.height(12.dp))
        listOf(
            "off" to tr("Все приложения через VPN"),
            "exclude" to tr("Все, кроме выбранных"),
            "include" to tr("Только выбранные")
        ).forEach { (m, t) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { save(m) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = mode == m, onClick = { save(m) })
                Text(t, fontSize = 15.sp)
            }
        }
        if (mode == "include" && selected.isEmpty())
            Text(tr("Выберите хотя бы одно приложение — пока все идут через VPN"), color = Color(0xFFFFC233), fontSize = 13.sp)
        if (changed)
            Text(tr("Переподключите VPN, чтобы изменения заработали"), color = Color(0xFFFFC233), fontSize = 13.sp)
        if (mode != "off") {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(tr("Поиск")) }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }
            )
            Row(
                Modifier.fillMaxWidth().clickable { showSystem = !showSystem }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
                Text(tr("Показывать системные"), color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(tr("выбрано: %s", selected.size), color = Muted, fontSize = 13.sp)
            }
            if (all == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.pkg }) { app ->
                    val on = app.pkg in selected
                    Row(
                        Modifier.fillMaxWidth().clickable { save(set = if (on) selected - app.pkg else selected + app.pkg) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app.pkg)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 15.sp, maxLines = 1)
                            Text(app.pkg, color = Muted, fontSize = 11.sp, maxLines = 1)
                        }
                        Checkbox(checked = on, onCheckedChange = { save(set = if (on) selected - app.pkg else selected + app.pkg) })
                    }
                }
            }
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

@Composable
private fun SettingsTab(model: AppModel) {
    val ctx = LocalContext.current
    var showLogs by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var chooseLang by remember { mutableStateOf(false) }
    val xrayVersion by produceState("…") {
        // не вызывать ядро здесь: в основном процессе уже работает WireGuard (Go), вторая среда Go роняет приложение
        value = withContext(Dispatchers.IO) {
            runCatching { java.io.File(ctx.filesDir, XrayCore.VERSION_FILE).readText().trim() }.getOrNull()?.ifBlank { null } ?: "—"
        }
    }
    if (showLogs) { LogsScreen { showLogs = false }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Header(tr("Настройки"))
        Spacer(Modifier.height(16.dp))
        Section(tr("Аккаунт")) {
            model.config?.user?.name?.let { InfoLine(tr("Аккаунт"), it) }
            model.config?.user?.let { if (it.devicesLimit > 0) InfoLine(tr("Устройства"), tr("%s из %s", it.devices, it.devicesLimit)) }
            TextButton(onClick = { confirmLogout = true }) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = Danger); Spacer(Modifier.width(6.dp))
                Text(tr("Выйти из аккаунта"), color = Danger)
            }
        }
        Section(tr("Язык")) {
            val chosen = I18n.chosen(ctx)
            val current = if (chosen.isBlank()) tr("Как в системе") else I18n.languages.firstOrNull { it.first == chosen }?.second ?: chosen
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { chooseLang = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("Язык приложения"), fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(current, color = Accent, fontSize = 15.sp)
            }
        }
        Section(tr("Основной VPN")) {
            var mode by remember { mutableStateOf(model.prefs.routingMode) }
            var lan by remember { mutableStateOf(model.prefs.lanDirectXray) }
            val smartReady = Routing.profile(model.config, Routing.MODE_SMART) != null
            Text(tr("Маршрутизация"), fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            for ((value, title, hint) in listOf(
                Triple(Routing.MODE_SMART, tr("Умная"), if (smartReady) tr("Российские сайты — напрямую, реклама блокируется, остальное — через VPN") else tr("Не настроено на сервере")),
                Triple(Routing.MODE_OFF, tr("Всё через VPN"), tr("Все сайты и приложения — через VPN")),
            )) {
                val enabled = value == Routing.MODE_OFF || smartReady
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { mode = value; model.prefs.routingMode = value }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = mode == value, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 15.sp, color = if (enabled) Color.Unspecified else Muted)
                        Text(hint, color = Muted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            SwitchLine(tr("Локальная сеть напрямую"), lan) { lan = it; model.prefs.lanDirectXray = it }
            Text(tr("Роутер, принтер и другие устройства дома (192.168.x.x) — мимо VPN"), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(tr("Изменения применятся при следующем подключении."), color = Muted, fontSize = 12.sp)
        }
        Section(tr("Пинг и проверка соединения")) {
            var pingOnOpen by remember { mutableStateOf(model.prefs.pingOnOpen) }
            var testAfter by remember { mutableStateOf(model.prefs.testAfterConnect) }
            var url by remember { mutableStateOf(model.prefs.pingUrl) }
            var chooseUrl by remember { mutableStateOf(false) }
            SwitchLine(tr("Пинговать серверы при открытии"), pingOnOpen) { pingOnOpen = it; model.prefs.pingOnOpen = it }
            SwitchLine(tr("Проверять интернет после подключения"), testAfter) { testAfter = it; model.prefs.testAfterConnect = it }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { chooseUrl = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("Адрес для проверки"), fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(url.removePrefix("https://").substringBefore("/"), color = Accent, fontSize = 13.sp)
            }
            Text(tr("Пинг — время полного запроса через сервер (подключение + ответ сайта), а не ICMP. Поэтому цифры выше, чем у обычного ping."), color = Muted, fontSize = 12.sp)
            if (chooseUrl) AlertDialog(
                onDismissRequest = { chooseUrl = false },
                title = { Text(tr("Адрес для проверки")) },
                text = {
                    Column {
                        Prefs.PING_URLS.forEach { u ->
                            Row(
                                Modifier.fillMaxWidth().clickable { url = u; model.prefs.pingUrl = u; chooseUrl = false }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = u == url, onClick = null, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(u.removePrefix("https://"), fontSize = 14.sp)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { chooseUrl = false }) { Text(tr("Закрыть")) } }
            )
        }
        Section(tr("Работа в фоне")) {
            var unrestricted by remember { mutableStateOf(Battery.isUnrestricted(ctx)) }
            LaunchedEffect(Unit) { while (true) { unrestricted = Battery.isUnrestricted(ctx); delay(1500) } }
            Text(
                if (unrestricted) tr("Ограничение батареи снято — VPN не засыпает при выключенном экране")
                else tr("Android может усыплять VPN при выключенном экране — после разблокировки он переподключается"),
                color = if (unrestricted) Muted else Color(0xFFFFC233), fontSize = 13.sp
            )
            if (!unrestricted) TextButton(onClick = { Battery.request(ctx) }) { Text(tr("Разрешить работу в фоне")) }
        }
        Section(tr("Резервный VPN")) {
            var lan by remember { mutableStateOf(model.prefs.lanDirectWdtt) }
            SwitchLine(tr("Локальная сеть напрямую"), lan) { lan = it; model.prefs.lanDirectWdtt = it }
            Text(tr("Роутер, принтер и другие устройства дома (192.168.x.x) — мимо VPN. Остальное всегда идёт через VPN."), color = Muted, fontSize = 12.sp)
            Text(tr("Изменения применятся при следующем подключении."), color = Muted, fontSize = 12.sp)
            TextButton(onClick = { showLogs = true }) { Text(tr("Журнал резервного VPN")) }
        }
        var crash by remember { mutableStateOf(CrashLog.read(ctx)) }
        crash?.let { text ->
            Section(tr("Последний сбой")) {
                Text(text.lineSequence().take(6).joinToString("\n"), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Row {
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Lite 404 crash", text))
                        model.notify(tr("Отчёт скопирован"), ToastKind.Success)
                    }) { Text(tr("Скопировать")) }
                    TextButton(onClick = { CrashLog.clear(ctx); crash = null }) { Text("Удалить", color = Muted) }
                }
            }
        }
        Section(tr("О приложении")) {
            InfoLine(tr("Версия"), BuildConfig.VERSION_NAME)
            InfoLine(tr("Ядро"), "Xray $xrayVersion")
            InfoLine(tr("Лицензия"), "GPLv3")
            Text(tr("Основано на WDTT (amurcanov/proxy-turn-vk-android) и Xray-core."), color = Muted, fontSize = 13.sp)
            TextButton(onClick = { openUrl(ctx, SOURCE_URL) }) { Text(tr("Исходный код")) }
            TextButton(onClick = { openUrl(ctx, BOT_URL) }) { Text(tr("Бот @lite404bot")) }
        }
    }
    if (chooseLang) {
        val chosen = I18n.chosen(ctx)
        AlertDialog(
            onDismissRequest = { chooseLang = false },
            title = { Text(tr("Язык приложения")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(listOf("" to tr("Как в системе")) + I18n.languages) { (code, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                chooseLang = false
                                if (code != chosen) {
                                    I18n.setChosen(ctx, code)
                                    (ctx as? android.app.Activity)?.recreate()
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = code == chosen, onClick = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(name, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseLang = false }) { Text(tr("Закрыть")) } }
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(tr("Выйти из аккаунта?")) },
            text = { Text(tr("VPN отключится, данные подписки удалятся с телефона. Чтобы войти снова, понадобится токен из бота.")) },
            confirmButton = { TextButton(onClick = { confirmLogout = false; model.logout() }) { Text(tr("Выйти"), color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(tr("Отмена")) } }
        )
    }
}

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp), content = content)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun LogsScreen(onBack: () -> Unit) {
    val logs by TunnelManager.logs.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(tr("Журнал резервного VPN"), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { TunnelManager.clearLogs() }) { Text(tr("Очистить")) }
        }
        if (logs.isEmpty()) Text(tr("Пусто"), color = Muted, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(logs) { e ->
                Text(
                    (if (e.count > 1) "[${e.count}×] " else "") + e.message,
                    color = if (e.isError) Danger else Color.White,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
    }
}

// ───────────────────────── утилиты ─────────────────────────

private fun openUrl(ctx: Context, url: String) {
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
