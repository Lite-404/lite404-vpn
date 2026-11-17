package com.lite404.vpn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal enum class AccentPreset(val id: String, val title: String, val color: Color) {
    Lime("lime", "Лайм", Color(0xFFB4FF39)),
    Mint("mint", "Мята", Color(0xFF67F7C7)),
    Ice("ice", "Лёд", Color(0xFF81D5FF)),
    Iris("iris", "Ирис", Color(0xFFBFA3FF)),
    Peach("peach", "Персик", Color(0xFFFFAE81)),
    Rose("rose", "Роза", Color(0xFFFF91C3));

    companion object {
        fun from(id: String) = entries.firstOrNull { it.id == id } ?: Lime
    }
}

internal data class Appearance(
    val lightweight: Boolean,
    val haptics: Boolean,
    val amoled: Boolean,
    val preset: AccentPreset,
    val textScale: Float
) {
    constructor(prefs: Prefs) : this(
        prefs.lightweight, prefs.haptics, prefs.amoled, AccentPreset.from(prefs.accentTheme), prefs.textScale
    )
}

internal val LocalGlass = compositionLocalOf { true }

internal fun liteColors(appearance: Appearance) = darkColorScheme(
    primary = appearance.preset.color, onPrimary = Color.Black,
    background = if (appearance.amoled) Color.Black else Color(0xFF0B0B0C),
    onBackground = Color.White, surface = if (appearance.amoled) Color(0xFF111113) else Color(0xFF17171A),
    onSurface = Color.White, surfaceVariant = if (appearance.amoled) Color(0xFF111113) else Color(0xFF17171A),
    onSurfaceVariant = Color(0xFFB0B0B5), secondaryContainer = appearance.preset.color.copy(alpha = .15f),
    onSecondaryContainer = appearance.preset.color, error = Color(0xFFFF5A5A)
)

@Composable
internal fun PlainDock(items: List<DockItem>, selected: Int, onSelect: (Int) -> Unit) {
    LiquidDock(items, { selected }, null, { null }, onSelect)
}

@Composable
internal fun Modifier.pointerSheen(radiusDp: Int = 13): Modifier {
    if (!LocalGlass.current) return this
    val light = rememberPointerGlowTarget() ?: return this
    return then(light.modifier).drawWithCache {
        val mask = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radiusDp.dp.toPx()))) }
        onDrawWithContent {
            drawContent()
            light.draw(this, mask)
        }
    }
}


@Composable
internal fun GlassSegments(
    labels: List<String>, selected: Int, onSelect: (Int) -> Unit,
    icon: (@Composable (Int, Color) -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalGlass.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val last = labels.lastIndex
    fun visual(index: Int) = if (rtl) last - index else index
    val current by rememberUpdatedState(visual(selected).toFloat())
    val choose by rememberUpdatedState(onSelect)
    var touch by remember { mutableStateOf<DockTouch?>(null) }
    var activation by remember { mutableIntStateOf(0) }
    var same by remember { mutableStateOf(false) }
    val pose = if (!glass) rememberUpdatedState(DockPose(current)) else rememberDockPose(visual(selected).toFloat()) {
        DockInput(touch?.position ?: current, touch != null, activation,
            dragging = touch?.dragging == true, sameTab = same)
    }
    val keepTint by remember(glass, pose) {
        derivedStateOf {
            !glass || kotlin.math.abs(pose.value.position - current) < .005f &&
                (same || touch == null && pose.value.glass < .005f)
        }
    }
    fun activate(index: Int) {
        same = index == selected
        activation++
        onSelect(index)
    }
    val gestures = if (!glass) Modifier else Modifier.pointerInput(labels.size, rtl) {
        awaitEachGesture {
            val pass = PointerEventPass.Initial
            val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
            if (down.isConsumed) return@awaitEachGesture
            val width = size.width.toFloat() / labels.size
            val start = (down.position.x / width).toInt().coerceIn(0, last)
            var position = start.toFloat()
            var dragging = false
            same = position == current
            touch = DockTouch(position)
            activation++
            down.consume()
            try {
                while (true) {
                    val event = awaitPointerEvent(pass)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                    val delta = change.position - down.position
                    if (!dragging && kotlin.math.abs(delta.y) > viewConfiguration.touchSlop &&
                        kotlin.math.abs(delta.y) > kotlin.math.abs(delta.x)) break
                    if (dragging || kotlin.math.abs(delta.x) > viewConfiguration.touchSlop) {
                        dragging = true
                        position = start + delta.x / width
                    }
                    if (!change.pressed) {
                        change.consume()
                        choose(visual(position.roundToInt().coerceIn(0, last)))
                        break
                    }
                    if (dragging) touch = DockTouch(resistDockEdge(position, last), dragging = true)
                    change.consume()
                }
            } finally {
                touch = null
            }
        }
    }
    val radius = 13.dp
    Box(Modifier.fillMaxWidth().padding(vertical = if (icon == null) 9.dp else 15.dp)
        .then(if (icon == null) Modifier.clip(RoundedCornerShape(radius)) else Modifier)
        .background(colors.surface, RoundedCornerShape(radius))
        .border(1.dp, Color.White.copy(alpha = .085f), RoundedCornerShape(radius))
        .selectableGroup().then(gestures)) {
        if (glass) DockGlass(labels.size, { pose.value }, cornerFraction = .16f, modifier = Modifier.matchParentSize(), fillSlot = true)
        else Box(Modifier.matchParentSize().padding(4.dp)) {
            Row(Modifier.matchParentSize()) {
                labels.indices.forEach { i ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(
                        if (i == selected) colors.primary.copy(alpha = .17f) else Color.Transparent, RoundedCornerShape(9.dp)))
                }
            }
        }
        val refraction = if (glass && android.os.Build.VERSION.SDK_INT >= 33) remember { DockRefraction() } else null
        fun clipping(outside: Boolean) = if (!glass) Modifier else Modifier.drawWithCache {
            val mask = Path()
            onDrawWithContent {
                val rect = pose.value.bounds(size, labels.size, fillSlot = true)
                mask.reset()
                mask.addRoundRect(RoundRect(rect, CornerRadius(rect.height * .16f)))
                clipPath(mask, if (outside) ClipOp.Difference else ClipOp.Intersect) {
                    this@onDrawWithContent.drawContent()
                }
            }
        }
        SegmentLabels(labels, selected, icon, { pose.value }, outside = true,
            modifier = clipping(true), keepTint = keepTint, onActivate = ::activate)
        if (glass) {
            Box(Modifier.matchParentSize().then(clipping(false)).clearAndSetSemantics {}) {
                val effect = if (refraction == null) Modifier else Modifier.graphicsLayer {
                    val lens = pose.value
                    renderEffect = if (lens.glass > .015f) refraction.effect(lens, size, labels.size) else null
                }
                SegmentLabels(labels, selected, icon, { pose.value }, outside = false, modifier = effect)
            }
        }
    }
}

@Composable
private fun SegmentLabels(
    labels: List<String>, selected: Int, icon: (@Composable (Int, Color) -> Unit)?,
    pose: () -> DockPose, outside: Boolean, modifier: Modifier = Modifier,
    keepTint: Boolean = true, onActivate: (Int) -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalGlass.current
    Row(modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            val tint = if (!outside || selected == index && keepTint) colors.primary else colors.onSurface
            val action = if (outside) Modifier.selectable(selected == index, role = Role.Tab) { onActivate(index) } else Modifier
            Column(Modifier.weight(1f).heightIn(min = if (icon == null) 48.dp else 80.dp)
                .then(action).padding(horizontal = 5.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                val magnification = if (outside || !glass) Modifier else Modifier.graphicsLayer {
                    val lens = pose()
                    scaleX = 1f + .15f * lens.glass * (.25f + .75f * lens.lift)
                    scaleY = scaleX
                    translationX = 2.dp.toPx() * lens.glass * lens.shear
                }
                if (icon != null) {
                    Box(magnification) { icon(index, tint) }
                    Spacer(Modifier.height(5.dp))
                }
                Text(label, color = tint, fontSize = 11.sp, lineHeight = 13.sp,
                    modifier = magnification,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun GlassSlider(
    value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit,
    onFinished: () -> Unit = {}, label: String
) {
    val glass = LocalGlass.current
    val colors = MaterialTheme.colorScheme
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    var grip by remember { mutableStateOf<Float?>(null) }
    var activation by remember { mutableIntStateOf(0) }
    val target = grip ?: ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val pose = if (!glass) rememberUpdatedState(DockPose(target)) else rememberDockPose(target) {
        DockInput(target, glass && grip != null, activation, dragging = grip != null)
    }
    fun quantize(fraction: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        val snapped = if (steps > 0) (clamped * (steps + 1)).roundToInt().toFloat() / (steps + 1) else clamped
        return range.start + (range.endInclusive - range.start) * snapped
    }
    val light = if (glass) rememberPointerGlowTarget() else null
    Canvas(Modifier.fillMaxWidth().height(60.dp)
        .then(light?.modifier ?: Modifier)
        .focusable()
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else {
                val intervals = if (steps > 0) steps + 1 else 100
                val step = (range.endInclusive - range.start) / intervals
                val next = when (event.key) {
                    Key.DirectionRight -> current + direction * step
                    Key.DirectionLeft -> current - direction * step
                    Key.MoveHome -> range.start
                    Key.MoveEnd -> range.endInclusive
                    else -> return@onKeyEvent false
                }
                change(quantize((next - range.start) / (range.endInclusive - range.start)))
                finished()
                true
            }
        }
        .semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range, steps)
            setProgress { requested ->
                change(quantize((requested - range.start) / (range.endInclusive - range.start)))
                finished()
                true
            }
        }
        .pointerInput(range, steps, direction) {
            awaitEachGesture {
                val pass = PointerEventPass.Initial
                val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
                if (down.isConsumed) return@awaitEachGesture
                val inset = 25.dp.toPx()
                val travel = (size.width - 2 * inset).coerceAtLeast(1f)
                val initial = ((current - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                val center = inset + (if (direction > 0) initial else 1f - initial) * travel
                val onThumb = kotlin.math.abs(down.position.x - center) <= 26.dp.toPx()
                var dragging = onThumb
                val start = if (onThumb) initial else {
                    val physical = (down.position.x - inset) / travel
                    if (direction > 0) physical else 1f - physical
                }
                grip = resistDockEdge(start, 1)
                activation++
                down.consume()
                var commit = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(pass)
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                        val delta = pointer.position - down.position
                        if (!dragging && kotlin.math.abs(delta.y) > viewConfiguration.touchSlop &&
                            kotlin.math.abs(delta.y) > kotlin.math.abs(delta.x)) break
                        if (kotlin.math.abs(delta.x) > viewConfiguration.touchSlop) dragging = true
                        val fraction = start + direction * delta.x / travel
                        grip = resistDockEdge(fraction, 1)
                        change(quantize(fraction))
                        pointer.consume()
                        if (!pointer.pressed) {
                            commit = true
                            break
                        }
                    }
                } finally {
                    grip = null
                    // Persist the final value even when Android cancels a completed drag.
                    if (commit || current != range.start + initial * (range.endInclusive - range.start)) finished()
                }
            }
        }) {
        val p = if (glass) pose.value else DockPose(target)
        val fraction = p.position.coerceIn(-.10f, 1.10f)
        val x0 = 25.dp.toPx()
        val width = size.width - 2 * x0
        val x = x0 + (if (direction > 0) fraction else 1f - fraction) * width
        val y = center.y
        val track = Rect(x0, y - 2.dp.toPx(), size.width - x0, y + 2.dp.toPx())
        val trackCorner = CornerRadius(2.dp.toPx())
        drawRoundRect(colors.onSurface.copy(alpha = .16f), track.topLeft, track.size, trackCorner)
        val fillStart = if (direction > 0) x0 else x.coerceIn(x0, size.width - x0)
        val fillEnd = if (direction > 0) x.coerceIn(x0, size.width - x0) else size.width - x0
        drawRoundRect(colors.primary, Offset(fillStart, track.top),
            Size((fillEnd - fillStart).coerceAtLeast(0f), track.height), trackCorner)
        val pressure = if (glass) p.glass else 0f
        val lift = if (glass) p.lift else 0f
        val thumbWidth = (36f + 10f * pressure + 8f * lift).dp.toPx()
        val thumbHeight = (24f + 8f * pressure + 6f * lift).dp.toPx()
        val thumb = Rect(x - thumbWidth / 2, y - thumbHeight / 2, x + thumbWidth / 2, y + thumbHeight / 2)
        val corner = CornerRadius(thumb.height / 2)
        drawRoundRect(Color.Black.copy(alpha = .18f), thumb.topLeft + Offset(0f, 1.dp.toPx()), thumb.size, corner)
        drawRoundRect(Color(0xFFFCFCFC), thumb.topLeft, thumb.size, corner, alpha = 1f - pressure)
        if (pressure > .001f) {
            drawRoundRect(colors.primary.copy(alpha = .20f * pressure), thumb.topLeft, thumb.size, corner)
            val sheen = Brush.linearGradient(
                listOf(Color.White.copy(alpha = .65f), Color.White.copy(alpha = .12f), Color.White.copy(alpha = .40f)),
                start = thumb.topLeft, end = thumb.bottomRight
            )
            drawRoundRect(sheen, thumb.topLeft, thumb.size, corner, alpha = pressure)
            val rim = Brush.sweepGradient(listOf(Color.White, Color.White.copy(alpha = .15f),
                colors.primary.copy(alpha = .7f), Color.White), center = thumb.center)
            drawRoundRect(rim, thumb.topLeft, thumb.size, corner, alpha = .8f * pressure, style = Stroke(1.dp.toPx()))
        }
        light?.let {
            val mask = Path().apply {
                addRoundRect(RoundRect(track, trackCorner))
                addRoundRect(RoundRect(thumb, corner))
            }
            it.draw(this, mask)
        }
    }
}
