package com.lite404.vpn

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class DockItem(val label: String, val glyph: MotionGlyph)

internal class DockBackdrop(val content: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Unspecified)
}

internal fun Modifier.captureDockBackdrop(backdrop: DockBackdrop?): Modifier {
    if (backdrop == null) return this
    return onGloballyPositioned { backdrop.origin = it.positionInRoot() }.drawWithContent {
        backdrop.content.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.content)
    }
}

private const val DockWidthFraction = .87f

internal data class DockTouch(val position: Float, val dragging: Boolean = false)

internal fun DockPose.bounds(size: Size, count: Int, fillSlot: Boolean = false): Rect {
    val lensHeight = size.height * height.coerceAtMost(.968f + .54f * lift)
    val outerGutter = (size.width / DockWidthFraction - size.width) / 2
    val span = horizontalSpan(size.width, size.height, count, outerGutter)
    val rect = Rect(
        span.center - span.width / 2, (size.height - lensHeight) / 2,
        span.center + span.width / 2, (size.height + lensHeight) / 2
    )
    if (!fillSlot) return rect
    val slot = size.width / count
    return androidx.compose.ui.geometry.lerp(
        Rect(position * slot, 0f, (position + 1f) * slot, size.height), rect, glass
    )
}

private val InactiveDockContent = Color(0xFFDDDFE0)
private val DockTrack = Brush.verticalGradient(listOf(Color(0x28EBF6EE), Color(0x14C8D2C6)))

private val DockPose.opticalStrength: Float
    get() = glass * (.25f + .75f * lift)

@Composable
internal fun rememberDockPose(initial: Float, input: () -> DockInput): State<DockPose> {
    val latest by rememberUpdatedState(input)
    val dynamics = remember { DockDynamics(initial) }
    val pose = remember { mutableStateOf(dynamics.pose) }
    LaunchedEffect(dynamics) {
        var previousFrame = 0L
        while (true) {
            if (!dynamics.needsFrame(latest())) {
                snapshotFlow { latest() }.first(dynamics::needsFrame)
                previousFrame = 0L
            }
            withFrameNanos { frame ->
                val since = if (previousFrame == 0L) 1f / 60f else (frame - previousFrame) / 1_000_000_000f
                val durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                val frameSeconds = since.coerceAtMost(.064f)
                val seconds = if (durationScale > 0f) frameSeconds / durationScale else 0f
                pose.value = dynamics.advance(latest(), seconds, contactSeconds = frameSeconds)
                previousFrame = frame
            }
        }
    }
    return pose
}

@Composable
internal fun LiquidDock(
    items: List<DockItem>,
    selection: () -> Int,
    backdrop: DockBackdrop?,
    swipeOffset: () -> Float?,
    onSelect: (Int) -> Unit
) {
    val last = items.lastIndex
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun visualIndex(index: Int) = if (rtl) last - index else index
    val selected = selection()
    val selectedPosition = visualIndex(selected).toFloat()
    val selectedOnTouch by rememberUpdatedState(selectedPosition)
    val select by rememberUpdatedState(onSelect)
    var touch by remember { mutableStateOf<DockTouch?>(null) }
    var activation by remember { mutableIntStateOf(0) }
    var sameTab by remember { mutableStateOf(false) }
    val pressed = touch != null
    val animated = LocalGlass.current
    val pose = if (!animated) rememberUpdatedState(DockPose(selectedPosition)) else rememberDockPose(selectedPosition) {
        val swipe = if (touch == null) swipeOffset() else null
        DockInput(touch?.position ?: (visualIndex(selection()) + (swipe ?: 0f)), pressed, activation,
            dragging = touch?.dragging == true, sameTab = sameTab, swiping = swipe != null)
    }
    val keepActiveTint by remember(pressed, sameTab, selectedPosition, pose) {
        derivedStateOf {
            val current = pose.value
            abs(current.position - selectedPosition) < .005f && (sameTab || !pressed && current.glass < .005f)
        }
    }
    val phase = rememberIconMotion(selected, DockIconDuration, animateInitially = false)
    val lens = { pose.value }
    val colors = MaterialTheme.colorScheme
    val refraction = remember(animated) {
        if (animated && Build.VERSION.SDK_INT >= 33) DockRefraction() else null
    }

    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 13.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            Modifier.widthIn(max = 485.dp).fillMaxWidth(DockWidthFraction).height(54.dp)
                .selectableGroup()
                .pointerInput(items.size, rtl) {
                    awaitEachGesture {
                        val pass = PointerEventPass.Initial
                        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
                        if (down.isConsumed) return@awaitEachGesture
                        val width = size.width.toFloat() / items.size
                        val start = (down.position.x / width).toInt().coerceIn(0, last)
                        var dragPosition = start.toFloat()
                        var dragging = false
                        sameTab = dragPosition == selectedOnTouch
                        touch = DockTouch(dragPosition)
                        activation++
                        down.consume()
                        try {
                            while (true) {
                                val event = awaitPointerEvent(pass)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                                val deltaX = change.position.x - down.position.x
                                val slop = viewConfiguration.touchSlop
                                if (dragging || abs(deltaX) > slop) {
                                    dragging = true
                                    dragPosition = start + deltaX / width
                                }
                                if (!change.pressed) {
                                    change.consume()
                                    select(visualIndex(dragPosition.roundToInt().coerceIn(0, last)))
                                    break
                                }
                                if (dragging) touch = DockTouch(resistDockEdge(dragPosition, last), dragging = true)
                                change.consume()
                            }
                        } finally {
                            touch = null
                        }
                    }
                }
        ) {
            val slot = maxWidth / items.size
            val labelWidth = with(LocalDensity.current) { (slot - 13.dp).toPx() }
            val settingsWidth = with(LocalDensity.current) { (slot - 18.dp).toPx() }
            val measurer = rememberTextMeasurer()
            val baseLabel = LocalTextStyle.current.copy(
                fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                letterSpacing = (-.3).sp, textAlign = TextAlign.Center
            )
            val labels = remember(items, labelWidth, settingsWidth, measurer, baseLabel) {
                items.map { item ->
                    val settings = item.glyph == MotionGlyph.Gear
                    val style = if (settings) baseLabel.copy(fontSize = 12.sp) else baseLabel
                    val available = if (settings) settingsWidth else labelWidth
                    val width = measurer.measure(AnnotatedString(item.label), style, softWrap = false, maxLines = 1).size.width
                    val scale = (available / width.coerceAtLeast(1)).coerceIn(.72f, 1f)
                    style.copy(fontSize = style.fontSize * scale)
                }
            }
            DockRail(items.size, lens, backdrop)
            DockGlass(items.size, lens)
            DockRow(items, labels, InactiveDockContent, selected, phase = { phase.value }, lensClip = lens,
                outside = true, onActivate = {
                    sameTab = it == selected
                    activation++
                    select(it)
                }, settledTint = colors.primary.takeIf { keepActiveTint })
            val lensLayer = Modifier.drawWithCache {
                val clip = Path()
                onDrawWithContent {
                    val rect = lens().bounds(size, items.size)
                    clip.reset()
                    clip.addRoundRect(RoundRect(rect, CornerRadius(rect.height / 2)))
                    clipPath(clip) { this@onDrawWithContent.drawContent() }
                }
            }
            val lensEffect = if (refraction == null) Modifier else Modifier.graphicsLayer {
                val pose = lens()
                renderEffect = if (pose.glass > .015f) refraction.effect(pose, size, items.size) else null
            }
            Box(lensLayer.then(lensEffect).fillMaxSize().clearAndSetSemantics {}) {
                DockRow(items, labels, colors.primary, selected, phase = { phase.value }, lensClip = lens)
            }
        }
    }
}

@Composable
private fun DockRail(count: Int, lens: () -> DockPose, backdrop: DockBackdrop?) {
    val sample = if (backdrop != null) rememberGraphicsLayer() else null
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    val mask = remember { Path() }
    val blurRadius = with(LocalDensity.current) { 13.dp.toPx() }
    val blur = remember(blurRadius) { BlurEffect(blurRadius, blurRadius, TileMode.Clamp) }
    val background = MaterialTheme.colorScheme.background
    val railColor = MaterialTheme.colorScheme.surface
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val rect = Rect(Offset.Zero, size)
        val radius = CornerRadius(size.height / 2)
        val pose = lens()
        drawRoundRect(railColor, cornerRadius = radius)
        if (pose.glass <= .001f) return@Canvas
        if (backdrop != null && sample != null && origin != Offset.Unspecified && backdrop.origin != Offset.Unspecified) {
            // Blur a strip around the dock instead of rasterizing the whole screen.
            val bleed = 34.dp.toPx().roundToInt()
            val source = origin - backdrop.origin
            sample.alpha = pose.glass
            sample.renderEffect = blur
            sample.record(size = IntSize(size.width.roundToInt() + 2 * bleed, size.height.roundToInt() + 2 * bleed)) {
                drawRect(background)
                translate(bleed - source.x, bleed - source.y) { drawLayer(backdrop.content) }
            }
            mask.reset()
            mask.addRoundRect(RoundRect(rect, radius))
            val capsule = pose.bounds(size, count)
            mask.addRoundRect(RoundRect(capsule, CornerRadius(capsule.height / 2)))
            clipPath(mask) { translate(-bleed.toFloat(), -bleed.toFloat()) { drawLayer(sample) } }
        }
        drawRoundRect(DockTrack, cornerRadius = radius, alpha = pose.glass)
        val stroke = 1.dp.toPx()
        drawRoundRect(
            Color.White.copy(alpha = .085f * pose.glass), Offset(stroke / 2, stroke / 2),
            Size(size.width - stroke, size.height - stroke), radius, style = Stroke(stroke)
        )
    }
}

@Composable
private fun DockRow(
    items: List<DockItem>,
    labels: List<TextStyle>,
    tint: Color,
    selected: Int,
    phase: () -> Float,
    lensClip: () -> DockPose,
    outside: Boolean = false,
    settledTint: Color? = null,
    onActivate: (Int) -> Unit = {}
) {
    val layer = if (!outside) Modifier else Modifier.drawWithCache {
        val clip = Path()
        onDrawWithContent {
            val rect = lensClip().bounds(size, items.size)
            clip.reset()
            clip.addRoundRect(RoundRect(rect, CornerRadius(rect.height / 2)))
            clipPath(clip, ClipOp.Difference) { this@onDrawWithContent.drawContent() }
        }
    }
    Row(layer.fillMaxSize()) {
        items.forEachIndexed { index, item ->
            val itemTint = settledTint?.takeIf { selected == index } ?: tint
            val action = if (outside) {
                val interactions = remember { MutableInteractionSource() }
                val focused = interactions.collectIsFocusedAsState()
                Modifier.selectable(
                    selected = selected == index,
                    interactionSource = interactions,
                    indication = null,
                    role = Role.Tab,
                    onClick = { onActivate(index) }
                ).drawWithContent {
                    if (focused.value) {
                        val inset = 3.dp.toPx()
                        drawRoundRect(
                            Color.White.copy(alpha = .36f),
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - inset * 2, size.height - inset * 2),
                            cornerRadius = CornerRadius(18.dp.toPx()),
                            style = Stroke(1.dp.toPx())
                        )
                    }
                    drawContent()
                }
            } else Modifier
            Column(
                Modifier.weight(1f).fillMaxSize().then(action),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((-3).dp, Alignment.CenterVertically)
            ) {
                val magnifyIcon = if (outside) Modifier else Modifier.graphicsLayer {
                    val pose = lensClip()
                    scaleX = 1f + .15f * pose.opticalStrength
                    scaleY = scaleX
                    translationX = 2.dp.toPx() * pose.glass * pose.shear
                }
                MotionIcon(item.glyph, itemTint, time = { if (selected == index) phase() else DockIconDuration.toFloat() },
                    modifier = Modifier.size(30.dp).then(magnifyIcon))
                Text(
                    item.label, color = itemTint, style = labels[index],
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = if (outside) Modifier else Modifier.graphicsLayer {
                        val pose = lensClip()
                        scaleX = 1f + .035f * pose.opticalStrength
                        scaleY = scaleX
                        translationX = pose.glass * pose.shear * 1.dp.toPx()
                    }
                )
            }
        }
    }
}

@Composable
internal fun DockGlass(count: Int, lens: () -> DockPose, cornerFraction: Float = .5f, modifier: Modifier = Modifier, fillSlot: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val solid = androidx.compose.ui.graphics.lerp(surface, accent, .17f)
    val light = rememberPointerGlowTarget()
    val mask = remember { Path() }
    Canvas(modifier.fillMaxSize().then(light?.modifier ?: Modifier)) {
        val pose = lens()
        val rect = pose.bounds(size, count, fillSlot)
        val radius = CornerRadius(rect.height * cornerFraction)
        drawRoundRect(solid, rect.topLeft, rect.size, radius, alpha = 1f - pose.glass)
        val glass = pose.glass
        if (glass > .001f) {
            drawRoundRect(androidx.compose.ui.graphics.lerp(surface, accent, .08f), rect.topLeft, rect.size, radius, alpha = .46f * glass)
            val shine = Brush.linearGradient(
                0f to Color(0xFFDAF2FF).copy(alpha = .31f * glass),
                .32f to accent.copy(alpha = .20f * glass),
                .61f to accent.copy(alpha = .08f * glass),
                1f to androidx.compose.ui.graphics.lerp(accent, Color.White, .65f).copy(alpha = .28f * glass),
                start = Offset(rect.center.x - rect.width * (.36f - pose.shear * .17f), rect.top),
                end = Offset(rect.center.x + rect.width * .38f, rect.bottom)
            )
            drawRoundRect(shine, rect.topLeft, rect.size, radius)
            val edge = (abs(pose.position - pose.position.coerceIn(0f, count - 1f)) * 4f).coerceIn(0f, 1f)
            val rim = Brush.sweepGradient(
                0f to Color(0xFFE1F9FD),
                .15f to Color(0x3AF4FFF5),
                .30f to Color(0xFFC9E2FE),
                .49f to Color(0xFFFAEDBC),
                .66f to Color(0x48E8FFEC),
                .84f to Color(0xFFF0FFF0),
                1f to Color(0xFFE1F9FD),
                center = rect.center + Offset(rect.width * .21f * pose.shear, 0f)
            )
            drawRoundRect(rim, rect.topLeft, rect.size, radius, alpha = glass * (.74f + .26f * edge),
                style = Stroke((1.1f + .28f * edge).dp.toPx()))
        }
        if (light != null) {
            mask.reset()
            mask.addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(size.height / 2f)))
            if (pose.lift > .001f) mask.addRoundRect(RoundRect(rect, radius))
            light.draw(this, mask)
        }
    }
}

@RequiresApi(33)
internal class DockRefraction {
    private val shader = RuntimeShader(
        """
        uniform shader content;
        uniform float2 center;
        uniform float2 radius;
        uniform float strength;
        uniform float shear;

        half4 main(float2 p) {
            float2 delta = p - center;
            float2 local = delta / radius;
            float edge = smoothstep(0.44, 1.08, length(local));
            float2 offset = delta * ((0.018 + 0.048 * edge) * strength);
            offset.x -= shear * radius.x * 0.029 * edge;
            return content.eval(p - offset);
        }
        """.trimIndent()
    )

    fun effect(lens: DockPose, size: Size, count: Int): androidx.compose.ui.graphics.RenderEffect {
        val rect = lens.bounds(size, count)
        shader.setFloatUniform("center", rect.center.x, rect.center.y)
        shader.setFloatUniform("radius", rect.width / 2, rect.height / 2)
        shader.setFloatUniform("strength", lens.glass * (.55f + .45f * lens.lift))
        shader.setFloatUniform("shear", lens.shear)
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}
