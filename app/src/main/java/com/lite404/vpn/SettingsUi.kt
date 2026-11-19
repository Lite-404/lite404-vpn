package com.lite404.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private enum class SettingsPage(val title: String, val summary: String) {
    Primary("Основной VPN", "Маршрутизация и свои списки"),
    Reserve("Резервный VPN", "Потоки, локальная сеть и свои списки"),
    Network("Сеть и пинг", "Способ замера и проверка соединения"),
    Startup("Автоподключение", "Запуск приложения и загрузка телефона"),
    Interface("Интерфейс", "Жесты, виброотклик и облегчённый режим"),
    Language("Язык", "Язык приложения"),
    Themes("Темы", "Цвет, AMOLED и размер текста"),
    Journal("Журнал", "Основной и резервный VPN"),
    Updates("Обновления", "Приложение и ядро"),
    PrimaryLists("Свои списки", "Сайты и адреса"),
    ReserveLists("Свои списки", "Домены, адреса и готовые категории"),
}

@Composable
internal fun SettingsTab(
    model: AppModel, showUpdates: Boolean, dockClearance: Dp, active: Boolean, homeRequest: Int, onAppearanceChanged: () -> Unit
) {
    val ctx = LocalContext.current
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(homeRequest) { page = null }
    LaunchedEffect(showUpdates, active) {
        if (showUpdates && active) page = SettingsPage.Updates
    }
    fun back() {
        page = when (page) {
            SettingsPage.PrimaryLists -> SettingsPage.Primary
            SettingsPage.ReserveLists -> SettingsPage.Reserve
            else -> null
        }
    }
    BackHandler(active && page != null) { back() }
    when (val current = page) {
        SettingsPage.PrimaryLists -> ListsScreen(model, dockClearance, ::back)
        SettingsPage.ReserveLists -> ListsScreen(model, dockClearance, ::back, reserve = true)
        SettingsPage.Journal -> JournalScreen(model, dockClearance, ::back)
        else -> key(current) {
            Column(
                Modifier.fillMaxSize().pageEntrance(enabled = current != null).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                    .padding(top = 20.dp, bottom = dockClearance + 16.dp)
            ) {
                SettingsTitle(current?.title ?: "Настройки", if (current != null) ::back else null)
                when (current) {
                    null -> {
                        SubscriptionCard(model) { confirmLogout = true }
                        SettingsBlock("VPN") {
                            listOf(SettingsPage.Primary, SettingsPage.Reserve).forEach { item ->
                                SettingsEntry(item) { page = item }
                            }
                        }
                        SettingsBlock("Сеть и запуск") {
                            listOf(SettingsPage.Network, SettingsPage.Startup).forEach { item ->
                                SettingsEntry(item) { page = item }
                            }
                        }
                        SettingsBlock("Внешний вид") {
                            listOf(SettingsPage.Themes, SettingsPage.Interface, SettingsPage.Language).forEach { item ->
                                SettingsEntry(item) { page = item }
                            }
                        }
                        SettingsBlock("Обслуживание") {
                            listOf(SettingsPage.Journal, SettingsPage.Updates).forEach { item ->
                                SettingsEntry(item) { page = item }
                            }
                        }
                    }
                    SettingsPage.Primary -> PrimarySettings(model) { page = SettingsPage.PrimaryLists }
                    SettingsPage.Reserve -> ReserveSettings(model) { page = SettingsPage.ReserveLists }
                    SettingsPage.ReserveLists -> Unit
                    SettingsPage.Network -> NetworkSettings(model)
                    SettingsPage.Startup -> StartupSettings(model)
                    SettingsPage.Interface -> {
                        SettingsBlock("Жесты") {
                            PreferenceSwitch("Переключение вкладок свайпом", model.swipeTabsEnabled, model::updateTabSwipe)
                        }
                        SettingsBlock("Отклик") {
                            BooleanPreference("Виброотклик", model.prefs.haptics, { model.prefs.haptics = it; onAppearanceChanged() })
                        }
                        SettingsBlock("Производительность") {
                            BooleanPreference("Облегчённый режим", model.prefs.lightweight, { model.prefs.lightweight = it; onAppearanceChanged() })
                            Text(tr("Убирает Liquid Glass, свечение и анимации иконок."), color = Muted, fontSize = 13.sp)
                        }
                    }
                    SettingsPage.Language -> LanguageSettings()
                    SettingsPage.Themes -> ThemeSettings(model, onAppearanceChanged)
                    SettingsPage.Updates -> UpdatesSettings(model, active)
                    SettingsPage.PrimaryLists, SettingsPage.Journal -> Unit
                }
            }
        }
    }
    if (confirmLogout && active) {
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
private fun SettingsTitle(title: String, onBack: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 13.dp)) {
        onBack?.let {
            IconButton(it, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Назад"), tint = Accent)
            }
        }
        Text(tr(title), fontSize = if (onBack == null) 29.sp else 23.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SubscriptionCard(model: AppModel, onLogout: () -> Unit) {
    val color = Accent
    val shape = RoundedCornerShape(21.dp)
    val user = model.config?.user
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Card)
            .drawWithCache {
                val glow = Brush.radialGradient(listOf(color.copy(alpha = .37f), color.copy(alpha = .12f), Color.Transparent),
                    center = Offset(size.width * .09f, size.height * .05f), radius = size.width * .78f)
                onDrawBehind { drawRect(glow) }
            }.border(1.dp, color.copy(alpha = .23f), shape).padding(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(42.dp).background(color.copy(alpha = .13f), CircleShape), contentAlignment = Alignment.Center) {
                SettingsSymbol("Account", color, Modifier.size(25.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(tr("Подписка Lite 404"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                user?.name?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Spacer(Modifier.height(10.dp))
        user?.let {
            val (label, tint) = subscriptionExpiry(it)
            Text(label, fontSize = 13.sp, color = tint)
            if (it.queuedPeriods > 0) Text(tr("Текущий период до %s",
                java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US).format(java.util.Date(it.expiresAt))),
                fontSize = 12.sp, color = Muted)
            if (it.devicesLimit > 0) Text(tr("Устройства") + " · " + tr("%s из %s", it.devices, it.devicesLimit), fontSize = 12.sp, color = Muted)
        }
        TextButton(onClick = onLogout, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = Danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("Выйти"), color = Danger)
        }
    }
}

@Composable
private fun SettingsGroupLabel(title: String) {
    Text(tr(title), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 17.dp, bottom = 6.dp))
}

@Composable
internal fun SettingsBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    SettingsGroupLabel(title)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun SettingsEntry(page: SettingsPage, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val color = Accent
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(36.dp).background(color.copy(alpha = .10f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            SettingsSymbol(page.name, color)
        }
        Column(Modifier.weight(1f)) {
            Text(tr(page.title), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(tr(page.summary), fontSize = 12.sp, color = Muted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun PreferenceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr(label), fontSize = 15.sp, modifier = Modifier.weight(1f))
        LiquidSwitch(checked, onChange)
    }
}

@Composable
private fun BooleanPreference(label: String, initial: Boolean, write: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    PreferenceSwitch(label, checked) { checked = it; write(it) }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(selected, onClick = null)
        Column {
            Text(label, fontSize = 15.sp)
            if (detail != null) Text(detail, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PrimarySettings(model: AppModel, onLists: () -> Unit) {
    val prefs = model.prefs
    var mode by remember { mutableStateOf(prefs.routingMode) }
    SettingsBlock("Маршрутизация") {
        Column(Modifier.selectableGroup()) {
            if (Routing.profile(model.config, Routing.MODE_SMART) != null) {
                Choice(tr("Умная"), mode == Routing.MODE_SMART, { mode = Routing.MODE_SMART; prefs.routingMode = mode },
                    tr("Российские сайты — напрямую, реклама блокируется, остальное — через VPN"))
            } else Text(tr("Умная маршрутизация не настроена на сервере"), color = Muted, fontSize = 13.sp)
            Choice(tr("Всё через VPN"), mode == Routing.MODE_OFF, { mode = Routing.MODE_OFF; prefs.routingMode = mode })
        }
    }
    SettingsBlock("Правила") { SettingsEntry(SettingsPage.PrimaryLists, onLists) }
    SettingsBlock("Локальная сеть") {
        BooleanPreference("Локальная сеть напрямую", prefs.lanDirectXray) { prefs.lanDirectXray = it }
    }
    ReconnectHint()
}

@Composable
private fun ReserveSettings(model: AppModel, onLists: () -> Unit) {
    val prefs = model.prefs
    var workers by remember { mutableFloatStateOf(prefs.wdttWorkers.toFloat()) }
    var mode by remember { mutableStateOf(prefs.wdttRoutingMode) }
    SettingsBlock("Маршрутизация") {
        Column(Modifier.selectableGroup()) {
            if (Routing.profile(model.config, Routing.MODE_SMART) != null) {
                Choice(tr("Умная"), mode == Routing.MODE_SMART, { mode = Routing.MODE_SMART; prefs.wdttRoutingMode = mode },
                    tr("Российские сайты — напрямую, реклама блокируется, остальное — через VPN"))
            }
            Choice(tr("Всё через VPN"), mode == Routing.MODE_OFF, { mode = Routing.MODE_OFF; prefs.wdttRoutingMode = mode })
        }
    }
    SettingsBlock("Производительность туннеля") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("Количество потоков"), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(workers.roundToInt().toString(), color = Accent)
        }
        GlassSlider(workers, 1f..WdttController.WORKERS.toFloat(), WdttController.WORKERS - 2,
            { workers = it }, onFinished = { prefs.wdttWorkers = workers.roundToInt() }, label = tr("Количество потоков"))
    }
    SettingsBlock("Правила") { SettingsEntry(SettingsPage.ReserveLists, onLists) }
    SettingsBlock("Локальная сеть") {
        BooleanPreference("Локальная сеть напрямую", prefs.lanDirectWdtt) { prefs.lanDirectWdtt = it }
    }
    ReconnectHint()
}

@Composable
private fun ReconnectHint() {
    Text(tr("Изменения применятся при следующем подключении."), color = Muted, fontSize = 12.sp,
        modifier = Modifier.padding(6.dp))
}

@Composable
private fun NetworkSettings(model: AppModel) {
    val prefs = model.prefs
    var method by remember { mutableStateOf(prefs.pingMethod) }
    var url by remember { mutableStateOf(prefs.pingUrl) }
    SettingsBlock("Способ пинга серверов") {
        Column(Modifier.selectableGroup()) {
            Choice(tr("HTTP GET · рекомендуется"), method == PingMethod.HTTP_GET, { method = PingMethod.HTTP_GET; prefs.pingMethod = method },
                tr("Запрос через VPN-сервер с адресом проверки"))
            Choice("ICMP", method == PingMethod.ICMP, { method = PingMethod.ICMP; prefs.pingMethod = method },
                tr("Прямой ICMP-запрос к серверу. Сервер может запрещать ответы."))
            Choice("TCP", method == PingMethod.TCP, { method = PingMethod.TCP; prefs.pingMethod = method },
                tr("Время открытия соединения с портом сервера"))
        }
    }
    SettingsBlock("Адрес для проверки") {
        Column(Modifier.selectableGroup()) {
            Prefs.PING_URLS.forEach { address ->
                Choice(address.substringAfter("://").substringBefore('/'), url == address, { url = address; prefs.pingUrl = address })
            }
        }
    }
    SettingsBlock("Автоматические проверки") {
        BooleanPreference("Пинговать серверы при открытии", prefs.pingOnOpen) { prefs.pingOnOpen = it }
        BooleanPreference("Проверять интернет после подключения", prefs.testAfterConnect) { prefs.testAfterConnect = it }
    }
}

@Composable
private fun StartupSettings(model: AppModel) {
    val ctx = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    fun permissionIfNeeded(enabled: Boolean) {
        if (enabled) VpnService.prepare(ctx)?.let(request::launch)
    }
    SettingsBlock("Автоподключение") {
        BooleanPreference("При открытии приложения", model.prefs.connectOnOpen) {
            model.prefs.connectOnOpen = it
            permissionIfNeeded(it)
        }
        BooleanPreference("При загрузке устройства", model.prefs.connectOnBoot) {
            model.prefs.connectOnBoot = it
            permissionIfNeeded(it)
        }
        Text(tr("Подключается последний использованный VPN. Для запуска при загрузке заранее выдайте системное разрешение VPN."),
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(6.dp))
    }
    SettingsBlock("Работа в фоне") {
        var unlimited by remember { mutableStateOf(Battery.isUnrestricted(ctx)) }
        LaunchedEffect(Unit) { while (true) { unlimited = Battery.isUnrestricted(ctx); delay(1500) } }
        Text(tr(if (unlimited) "Работа в фоне разрешена" else "Android ограничивает работу в фоне"), color = Muted, fontSize = 13.sp)
        if (!unlimited) TextButton(onClick = { Battery.request(ctx) }) { Text(tr("Разрешить работу в фоне")) }
    }
}

@Composable
private fun LanguageSettings() {
    val ctx = LocalContext.current
    val chosen = I18n.chosen(ctx)
    fun select(code: String) {
        if (chosen != code) {
            I18n.setChosen(ctx, code)
            (ctx as? android.app.Activity)?.recreate()
        }
    }
    SettingsBlock("Система") {
        LanguageRow("", tr("Как в системе"), chosen.isEmpty()) { select("") }
    }
    SettingsBlock("Доступные языки") {
        Column(Modifier.selectableGroup()) {
            I18n.languages.forEachIndexed { index, (code, label) ->
                if (index > 0) HorizontalDivider(color = Color.White.copy(alpha = .055f), modifier = Modifier.padding(start = 47.dp))
                LanguageRow(code, label, chosen == code) { select(code) }
            }
        }
    }
}

@Composable
private fun LanguageRow(code: String, title: String, chosen: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (chosen) Accent.copy(alpha = .08f) else Color.Transparent)
        .selectable(chosen, role = Role.RadioButton, onClick = onClick).padding(horizontal = 9.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(35.dp).background(Accent.copy(alpha = .10f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            if (code.isEmpty()) SettingsSymbol("Language", Accent)
            else Text(code.uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
        AppSelectionMark(chosen)
    }
}

@Composable
private fun ThemeSettings(model: AppModel, onChange: () -> Unit) {
    val prefs = model.prefs
    var selected by remember { mutableStateOf(AccentPreset.from(prefs.accentTheme)) }
    var scale by remember { mutableFloatStateOf(prefs.textScale) }
    SettingsBlock("Акцентный цвет") {
    AccentPreset.entries.chunked(3).forEach { row ->
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { preset ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Card)
                    .selectable(selected == preset, role = Role.RadioButton) {
                        selected = preset
                        prefs.accentTheme = preset.id
                        onChange()
                    }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(32.dp).background(preset.color, CircleShape), contentAlignment = Alignment.Center) {
                        if (selected == preset) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(tr(preset.title), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    }
    SettingsBlock("Фон") {
        BooleanPreference("AMOLED-тема", prefs.amoled) { prefs.amoled = it; onChange() }
    }
    SettingsBlock("Текст") {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(tr("Размер текста"), fontWeight = FontWeight.Medium)
        Text("${(scale * 100).roundToInt()}%", color = Accent)
    }
    GlassSlider(scale, .85f..1.25f, 7, { scale = it },
        onFinished = { prefs.textScale = scale; onChange() }, label = tr("Размер текста"))
    }
}

@Composable
private fun JournalScreen(model: AppModel, dockClearance: Dp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by rememberSaveable { mutableIntStateOf(0) }
    val mode = VpnMode.entries[index]
    var enabled by remember(mode) { mutableStateOf(VpnJournal.enabled(ctx, mode)) }
    var content by remember(mode) { mutableStateOf("") }
    var confirmClear by remember(mode) { mutableStateOf(false) }
    LaunchedEffect(mode) {
        while (true) {
            content = withContext(Dispatchers.IO) { VpnJournal.read(ctx, mode) }
            delay(900)
        }
    }
    Column(Modifier.fillMaxSize().pageEntrance().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = dockClearance + 8.dp)) {
        SettingsTitle("Журнал", onBack)
        GlassSegments(listOf(tr("Основной VPN"), tr("Резервный VPN")), index, { index = it })
        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 5.dp)) {
        PreferenceSwitch("Записывать журнал", enabled) {
            scope.launch {
                val state = Connection.state.value
                content = withContext(Dispatchers.IO) {
                    runCatching { VpnJournal.setEnabled(ctx, mode, it) }
                    if (it && VpnJournal.enabled(ctx, mode)) {
                        val status = when {
                            state.mode != mode || state.phase == Phase.OFF -> "VPN отключён"
                            state.phase == Phase.ON -> "VPN подключён"
                            else -> "VPN меняет состояние"
                        }
                        VpnJournal.record(ctx, mode, "Запись включена · $status")
                    }
                    VpnJournal.read(ctx, mode)
                }
                enabled = VpnJournal.enabled(ctx, mode)
            }
        }
        Text(tr(if (enabled) "Идёт запись новых событий" else "Запись отключена"), color = if (enabled) Accent else Muted,
            fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 9.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            JournalAction("Скопировать", "Copy", content.isNotBlank(), Modifier.weight(1f)) {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(tr("Журнал"), content))
                model.notify(tr("Журнал скопирован"), ToastKind.Success)
            }
            JournalAction("Очистить", "Clear", content.isNotBlank(), Modifier.weight(1f)) { confirmClear = true }
            JournalAction("Отправить", "Share", content.isNotBlank(), Modifier.weight(1f)) {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Lite 404 · " + tr("Журнал"))
                    .putExtra(Intent.EXTRA_TEXT, content)
                ctx.startActivity(Intent.createChooser(share, tr("Отправить журнал")))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(17.dp)).background(Card).padding(13.dp)) {
            if (content.isBlank()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsSymbol("Journal", Muted, Modifier.size(34.dp))
                Text(tr(if (enabled) "Ожидание событий" else "Журнал пуст"), fontSize = 17.sp)
                Text(tr(if (enabled) "Новые события появятся при подключении VPN" else "Включите запись, чтобы сохранить события подключения"),
                    color = Muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else SelectionContainer {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(content.lineSequence().filter { it.isNotBlank() }.toList().asReversed()) { line ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(line.substringBefore("  "), fontSize = 10.sp, color = Muted, fontFamily = FontFamily.Monospace)
                            Text(line.substringAfter("  ", line), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            HorizontalDivider(color = Color.White.copy(alpha = .04f), modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text(tr("Очистить журнал?")) },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                scope.launch {
                    withContext(Dispatchers.IO) { VpnJournal.clear(ctx, mode) }
                    if (mode == VpnMode.WDTT) TunnelManager.clearLogs()
                    content = ""
                }
            }) { Text(tr("Очистить"), color = Danger) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Отмена")) } }
    )
}

@Composable
private fun UpdatesSettings(model: AppModel, active: Boolean) {
    val ctx = LocalContext.current
    val core by produceState("—") {
        value = withContext(Dispatchers.IO) {
            runCatching { File(ctx.filesDir, XrayCore.VERSION_FILE).readText().trim() }.getOrDefault("—")
        }
    }
    SettingsBlock("Приложение") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(45.dp).background(Accent.copy(alpha = .12f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center) { SettingsSymbol("Updates", Accent) }
            Column {
                Text("Lite 404", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(tr("Версия") + " " + BuildConfig.VERSION_NAME, color = Muted, fontSize = 13.sp)
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = .07f), modifier = Modifier.padding(top = 9.dp))
        UpdateBlock(model, active)
    }
    SettingsBlock("Ядро VPN") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSymbol("Primary", Accent)
            Column {
                Text(tr("Версия ядра"), fontSize = 15.sp)
                Text(core, color = Muted, fontSize = 13.sp)
            }
        }
    }
    SettingsBlock("Поддержка и исходники") {
        TextButton(onClick = { openUrl(ctx, BOT_URL) }) { SettingsSymbol("Share", Accent); Spacer(Modifier.width(9.dp)); Text(tr("Бот @lite404bot")) }
        TextButton(onClick = { openUrl(ctx, SOURCE_URL) }) { SettingsSymbol("Journal", Accent); Spacer(Modifier.width(9.dp)); Text(tr("Исходный код")) }
    }
}

@Composable
private fun JournalAction(title: String, symbol: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(13.dp),
        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 11.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Accent.copy(alpha = .12f), contentColor = Accent)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsSymbol(symbol, if (enabled) Accent else Muted.copy(alpha = .4f))
            Text(tr(title), fontSize = 11.sp)
        }
    }
}
