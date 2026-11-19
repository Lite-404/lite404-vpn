package com.lite404.vpn

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private val SecondaryText = Color(0xFFB0B0B5)
private val ServerPanelColor = Color(0xFF181819)
private val ServerAnchorHeight = 58.dp
private val ServerOptionHeight = 46.dp
private val PingFooterHeight = 51.dp

internal enum class GlobeState { Off, Busy, On }

internal class ServerPicker(expandedState: MutableState<Boolean>) {
    var expanded by expandedState
    var panelCoordinates: LayoutCoordinates? = null
    private var viewportCoordinates: LayoutCoordinates? = null

    val dismissOnOutsideTap = Modifier
        .onGloballyPositioned { viewportCoordinates = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val pass = PointerEventPass.Initial
                val down = awaitFirstDown(pass = pass)
                if (!expanded) return@awaitEachGesture
                val panel = panelCoordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val viewport = viewportCoordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                if (panel.boundsInRoot().contains(viewport.localToRoot(down.position))) return@awaitEachGesture

                // Prevent an outside tap from also pressing the control behind the picker.
                down.consume()
                while (true) {
                    val event = awaitPointerEvent(pass)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.any { it.id != down.id && it.pressed } ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    ) break
                    if (!change.pressed) {
                        change.consume()
                        expanded = false
                        break
                    }
                }
            }
        }
}

@Composable
internal fun VpnDashboard(
    time: String,
    upload: String,
    download: String,
    traffic: String?,
    expiry: Pair<String, Color>?,
    dockClearance: Dp,
    globe: GlobeState,
    powerLabel: String,
    onPower: () -> Unit,
    refresh: @Composable () -> Unit,
    names: List<String>,
    selected: Int,
    canSelect: Boolean,
    active: Boolean,
    serverPicker: ServerPicker,
    pings: Map<Int, Long>,
    pinging: Boolean,
    onPing: () -> Unit,
    onSelect: (Int) -> Unit,
    connectionPing: Long?,
    testing: Boolean,
    onTest: () -> Unit,
    status: @Composable () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val expanded = active && serverPicker.expanded
    LaunchedEffect(canSelect, names.isEmpty()) {
        if (!canSelect || names.isEmpty()) serverPicker.expanded = false
    }
    BackHandler(enabled = expanded) { serverPicker.expanded = false }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val compact = maxHeight - dockClearance < 630.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(top = 19.dp, bottom = dockClearance + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Lite", color = accent, fontSize = 24.sp, fontWeight = FontWeight.Light)
                        Spacer(Modifier.width(5.dp))
                        Text("404", color = Color.White, fontSize = 24.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text(BuildConfig.VERSION_NAME, color = SecondaryText, fontSize = 11.sp)
                }
                Spacer(Modifier.width(20.dp))
                expiry?.let { (label, color) ->
                    Text(label, modifier = Modifier.weight(1f).padding(top = 6.dp), color = color,
                        fontSize = 12.sp, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(if (compact) 24.dp else 36.dp))
            RollingTimer(time, running = globe == GlobeState.On)
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("↓  $download", color = SecondaryText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("↑  $upload", color = SecondaryText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            if (traffic != null) {
                Spacer(Modifier.height(8.dp))
                Text(traffic, color = SecondaryText, fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = tr("Трафик") })
            }
            Spacer(Modifier.height(if (compact) 30.dp else 46.dp))
            BoxWithConstraints(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                val globeSize = (maxWidth * .72f).coerceAtMost(if (compact) 196.dp else 218.dp)
                val gap = if (compact) 40.dp else 52.dp
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(globeSize)) {
                        GlobePower(globe, powerLabel, onPower, Modifier.align(Alignment.Center).size(globeSize))
                        Box(Modifier.align(Alignment.BottomCenter).width(globeSize + 48.dp)) {
                            Box(Modifier.align(Alignment.CenterEnd)) { refresh() }
                        }
                    }
                    Spacer(Modifier.height(gap + ServerAnchorHeight + 8.dp))
                    VpnTestButton(enabled = globe == GlobeState.On, testing = testing, covered = expanded, onClick = onTest)
                    Box(Modifier.heightIn(min = 22.dp), contentAlignment = Alignment.Center) {
                        connectionPing?.takeUnless { expanded }?.let {
                            Text(tr("Пинг") + " · " + tr("%s мс", it), color = SecondaryText, fontSize = 12.sp)
                        }
                    }
                    status()
                }
                VpnServerPanel(
                    names, selected, canSelect && active, expanded, { serverPicker.expanded = it },
                    pings, pinging, onPing, onSelect,
                    anchorY = globeSize + gap,
                    maxReveal = globeSize / 2 + gap,
                    onPositioned = { if (active) serverPicker.panelCoordinates = it }
                )
            }
        }
    }
}

@Composable
private fun VpnTestButton(enabled: Boolean, testing: Boolean, covered: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.height(43.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(CircleShape)
                .background(ServerPanelColor)
                .border(1.dp, accent.copy(alpha = if (enabled) .83f else .40f), CircleShape)
                .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
                .clickable(enabled = enabled && !testing && !covered, role = Role.Button, onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            if (testing) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp, color = accent)
            else Speedometer(if (enabled) Color.White else SecondaryText, 16.dp)
            Text(tr("Проверить"), color = if (enabled) Color.White else SecondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun VpnServerPanel(
    names: List<String>,
    selected: Int,
    enabled: Boolean,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    pings: Map<Int, Long>,
    pinging: Boolean,
    onPing: () -> Unit,
    onSelect: (Int) -> Unit,
    anchorY: Dp,
    maxReveal: Dp,
    onPositioned: (LayoutCoordinates) -> Unit,
    showPing: Boolean = true
) {
    val accent = MaterialTheme.colorScheme.primary
    val alternate = remember(names, selected) { names.indices.filter { it != selected } }
    val visibleRows = (maxReveal / ServerOptionHeight).toInt().coerceAtLeast(1)
    val rowHeight = if (alternate.size > visibleRows) maxReveal / visibleRows else ServerOptionHeight
    val listHeight = rowHeight * minOf(alternate.size, visibleRows)
    val above by animateDpAsState(if (expanded) listHeight else 0.dp, tween(340), label = "Server list")
    val below by animateDpAsState(if (expanded && showPing) PingFooterHeight else 0.dp, tween(300), label = "Ping button")
    val openAngle = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -90f else 90f
    val arrowAngle by animateFloatAsState(if (expanded) openAngle else 0f, tween(290), label = "Server arrow")
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth()
            .absoluteOffset(y = anchorY - above)
            .onGloballyPositioned(onPositioned)
            .clip(shape).background(ServerPanelColor)
            .border(2.dp, accent.copy(alpha = if (names.isEmpty()) .4f else 1f), shape)
    ) {
        if (above > 0.dp) {
            Box(Modifier.fillMaxWidth().height(above).clipToBounds(), contentAlignment = Alignment.BottomCenter) {
                LazyColumn(
                    Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true)
                        .height(listHeight).padding(horizontal = 8.dp)
                ) {
                    items(alternate) { i ->
                        ServerOption(names[i], pings[i], pinging && i !in pings, expanded && enabled, rowHeight) {
                            onExpanded(false)
                            onSelect(i)
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(ServerAnchorHeight)
                .clickable(enabled = enabled && names.isNotEmpty(), role = Role.Button) { onExpanded(!expanded) }
                .padding(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            val name = names.getOrNull(selected)
            ServerFlag(name.orEmpty(), 34.dp)
            Text(name?.let(::serverLabel) ?: tr("Нет серверов"), color = Color.White, fontSize = 18.sp,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (name != null) Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null, Modifier.size(25.dp).rotate(arrowAngle), tint = Color.White
            )
        }
        if (below > 0.dp) {
            Box(Modifier.fillMaxWidth().height(below).clipToBounds(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true).height(PingFooterHeight),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = Color.White.copy(alpha = .12f))
                    Row(
                        Modifier.height(PingFooterHeight - 1.dp)
                            .clickable(enabled = expanded && !pinging, role = Role.Button, onClick = onPing)
                            .padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (pinging) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp, color = accent)
                        else Speedometer(accent, 17.dp)
                        Text(tr(if (pinging) "Измеряем…" else "Пинг"), color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (alternate.isEmpty() && !pinging) PingValue(pings[selected], loading = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerOption(name: String, ping: Long?, loading: Boolean, enabled: Boolean, height: Dp, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(height)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        ServerFlag(name, 25.dp)
        Text(serverLabel(name), modifier = Modifier.weight(1f), color = Color.White,
            fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        PingValue(ping, loading)
    }
}

@Composable
private fun PingValue(ping: Long?, loading: Boolean) {
    val colors = MaterialTheme.colorScheme
    when {
        loading -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = SecondaryText)
        ping == null -> Unit
        ping < 0 -> Text(tr("нет ответа"), color = colors.error, fontSize = 11.sp)
        else -> Text(tr("%s мс", ping), color = when {
            ping < 250 -> colors.primary
            ping < 600 -> Color(0xFFFFC238)
            else -> colors.error
        }, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
internal fun Speedometer(color: Color, diameter: Dp) {
    Canvas(Modifier.size(diameter)) {
        val stroke = 1.45.dp.toPx()
        val pad = stroke / 2f
        drawArc(color, 151f, 238f, false, topLeft = Offset(pad, pad),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round))
        drawLine(color, center, Offset(size.width * .73f, size.height * .27f), stroke, StrokeCap.Round)
        drawCircle(color, radius = stroke * .72f)
    }
}

@Composable
private fun GlobePower(state: GlobeState, label: String, onClick: () -> Unit, modifier: Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val pressScale = remember { Animatable(1f) }
    LaunchedEffect(interactions) {
        var movement: Job? = null
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    movement?.cancel()
                    movement = launch { pressScale.animateTo(.962f, tween(95)) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    movement?.cancel()
                    movement = launch {
                        if (interaction is PressInteraction.Release && pressScale.value > .984f) {
                            pressScale.animateTo(.975f, tween(45))
                        }
                        pressScale.animateTo(1f, spring(dampingRatio = .83f, stiffness = 420f))
                    }
                }
                else -> Unit
            }
        }
    }
    val tint by animateColorAsState(when (state) {
        GlobeState.Off -> Color(0xFFFF3030)
        GlobeState.Busy -> Color(0xFFFFC238)
        GlobeState.On -> MaterialTheme.colorScheme.primary
    }, tween(220), label = "VPN globe color")
    val angle = if (state == GlobeState.Busy) {
        val rotation = rememberInfiniteTransition(label = "VPN connecting")
        rotation.animateFloat(0f, 360f, infiniteRepeatable(tween(920, easing = LinearEasing), RepeatMode.Restart),
            label = "VPN progress angle")
    } else null
    Canvas(modifier.clip(CircleShape)
        .semantics { contentDescription = label }
        .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
        .graphicsLayer {
            scaleX = pressScale.value
            scaleY = pressScale.value
        }
    ) {
        scale(size.minDimension / 200f, size.minDimension / 200f, pivot = Offset.Zero) {
            val mid = Offset(100f, 100f)
            clipPath(GlobeOutline) {
                drawPath(Coastlines, tint, style = Stroke(3.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawCircle(tint, radius = 97f, center = mid, style = Stroke(3.5f))
            drawCircle(tint, radius = 51f, center = mid)
            if (angle != null) {
                drawCircle(Color.Black.copy(alpha = .23f), radius = 24f, center = mid, style = Stroke(4.5f))
                drawArc(Color.Black, angle.value - 90f, 112f, useCenter = false, topLeft = Offset(76f, 76f),
                    size = Size(48f, 48f), style = Stroke(4.5f, cap = StrokeCap.Round))
            } else {
                drawArc(Color.Black, -48f, 277f, useCenter = false, topLeft = Offset(75f, 80f),
                    size = Size(50f, 50f), style = Stroke(5.5f, cap = StrokeCap.Round))
                drawLine(Color.Black, Offset(100f, 74f), Offset(100f, 101f), strokeWidth = 5.5f, cap = StrokeCap.Round)
            }
        }
    }
}

private val GlobeOutline = Path().apply { addOval(Rect(3f, 3f, 197f, 197f)) }

private val Coastlines = Path().apply {
    moveTo(15f, 55f)
    cubicTo(23f, 50f, 30f, 51f, 35f, 44f)
    cubicTo(39f, 36f, 43f, 33f, 52f, 32f)
    cubicTo(64f, 32f, 59f, 18f, 70f, 14f)
    quadraticTo(78f, 12f, 79f, 6f)
    moveTo(146f, 15f)
    cubicTo(153f, 24f, 150f, 30f, 162f, 32f)
    cubicTo(174f, 35f, 164f, 48f, 178f, 48f)
    cubicTo(187f, 49f, 180f, 62f, 192f, 65f)
    moveTo(5f, 117f)
    cubicTo(22f, 117f, 19f, 131f, 33f, 133f)
    cubicTo(47f, 134f, 39f, 144f, 52f, 149f)
    cubicTo(73f, 159f, 50f, 170f, 49f, 181f)
    moveTo(195f, 127f)
    cubicTo(175f, 128f, 185f, 143f, 167f, 146f)
    cubicTo(152f, 148f, 163f, 161f, 153f, 166f)
    cubicTo(142f, 170f, 140f, 177f, 148f, 184f)
}
