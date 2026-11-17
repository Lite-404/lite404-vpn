package com.lite404.vpn

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.tanh

private val SwitchTrackWidth = 68.dp
private val SwitchTrackHeight = 28.dp
private val SwitchThumbWidth = 40.dp
private val SwitchInset = 2.dp
private val SwitchTravel = SwitchTrackWidth - SwitchThumbWidth - SwitchInset * 2
private val SwitchOff = Color(0xFF6B7075)

@Composable
internal fun LiquidSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    if (!LocalGlass.current) {
        androidx.compose.material3.Switch(checked, onCheckedChange = null)
        return
    }
    val current by rememberUpdatedState(checked)
    val change by rememberUpdatedState(onChange)
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    var grip by remember { mutableStateOf<Float?>(null) }
    val held = grip != null
    val target = grip ?: if (checked) 1f else 0f
    val drawnTarget by rememberUpdatedState(target)
    val position = animateFloatAsState(target, spring(.83f, if (held) 760f else 350f, .001f), label = "Switch position")
    val lift = remember { Animatable(0f) }
    // Changing the value from the row or accessibility action also animates the glass.
    var lastChecked by remember { mutableStateOf(checked) }
    LaunchedEffect(held, checked) {
        val toggled = lastChecked != checked
        lastChecked = checked
        if (held) {
            lift.animateTo(1f, tween(100))
        } else {
            if (toggled && lift.value < .95f) lift.animateTo(.95f, tween(95))
            if (lift.value > 0f) lift.animateTo(0f, tween(290))
        }
    }

    val onColor = MaterialTheme.colorScheme.primary
    val light = rememberPointerGlowTarget()
    Spacer(
        Modifier.size(96.dp, 44.dp)
            .then(light?.modifier ?: Modifier)
            .pointerInput(direction) {
                awaitEachGesture {
                    val pass = PointerEventPass.Initial
                    val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
                    if (down.isConsumed) return@awaitEachGesture
                    val start = if (current) 1f else 0f
                    val travel = SwitchTravel.toPx()
                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    grip = start
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass)
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (pointer.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                            val delta = pointer.position - down.position
                            if (!dragging && abs(delta.y) > slop && abs(delta.y) > abs(delta.x)) break
                            if (abs(delta.x) > slop && abs(delta.x) > abs(delta.y)) dragging = true
                            val value = start + direction * delta.x / travel
                            if (!pointer.pressed) {
                                if (dragging) change(value >= .5f)
                                else if (delta.getDistance() <= slop) change(!current)
                                pointer.consume()
                                break
                            }
                            if (dragging) {
                                val edge = value.coerceIn(0f, 1f)
                                grip = edge + .09f * tanh((value - edge) / .09f)
                                pointer.consume()
                            }
                        }
                    } finally {
                        grip = null
                    }
                }
            }
            .drawWithCache {
                val center = Offset(size.width / 2f, size.height / 2f)
                val halfWidth = SwitchTrackWidth.toPx() / 2f
                val halfHeight = SwitchTrackHeight.toPx() / 2f
                val track = Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
                val trackRadius = CornerRadius(track.height / 2f)
                val trackPath = Path().apply { addRoundRect(RoundRect(track, trackRadius)) }
                val thumbPath = Path()
                val lightPath = Path()
                val rim = Brush.sweepGradient(
                    0f to Color(0xFFF7FFF8), .26f to Color(0xFFDDEFFF), .47f to Color(0x59D6E8FF),
                    .65f to Color(0xFFFBEBD2), .82f to Color(0x69E3FDD1), 1f to Color(0xFFF7FFF8),
                    center = Offset.Zero
                )
                val sheen = Brush.linearGradient(
                    0f to Color(0x9EFFFFFF), .38f to Color(0x25FFFFFF),
                    .65f to Color(0x0AFFFFFF), 1f to Color(0x79E1F6FF),
                    start = Offset.Zero, end = Offset(1f, 1f)
                )
                val rimWidth = Stroke(.8.dp.toPx())
                val restingWidth = SwitchThumbWidth.toPx()
                val restingHeight = track.height - 2f * SwitchInset.toPx()
                val travel = SwitchTravel.toPx()
                val heightLift = 14.dp.toPx()
                val widthLift = 7.5.dp.toPx()
                val edgeExtension = 9.dp.toPx()
                val pullWidth = 2.5.dp.toPx()
                val shadowOffset = Offset(0f, 1.dp.toPx())
                onDrawBehind {
                    val p = position.value
                    val pressure = lift.value
                    val trackColor = lerp(SwitchOff, onColor, p.coerceIn(0f, 1f))
                    drawRoundRect(trackColor, track.topLeft, track.size, trackRadius)

                    val x = center.x + direction * (p - .5f) * travel
                    val pull = tanh((drawnTarget - p) * 6f) * pressure
                    val width = restingWidth + widthLift * pressure + pullWidth * abs(pull)
                    val height = restingHeight + heightLift * pressure - pullWidth * abs(pull)
                    val edge = (direction * (2f * p - 1f)).coerceIn(-1f, 1f)
                    val stretch = edgeExtension * edge * abs(edge) * pressure
                    val thumb = Rect(
                        x - width / 2f + stretch.coerceAtMost(0f), center.y - height / 2f,
                        x + width / 2f + stretch.coerceAtLeast(0f), center.y + height / 2f
                    )
                    val corner = CornerRadius(height / 2f)
                    if (pressure < .002f) {
                        drawRoundRect(Color(0xFFFCFCFC), thumb.topLeft, thumb.size, corner)
                        light?.draw(this, trackPath)
                    } else {
                        drawRoundRect(Color.Black, thumb.topLeft + shadowOffset, thumb.size, corner, alpha = .13f * pressure)
                        thumbPath.reset()
                        thumbPath.addRoundRect(RoundRect(thumb, corner))
                        clipPath(thumbPath) {
                            withTransform({
                                scale(1f + .17f * pressure, 1f + .12f * pressure, pivot = thumb.center)
                                translate(direction * pull * pullWidth, shadowOffset.y * pressure)
                            }) {
                                drawRoundRect(trackColor, track.topLeft, track.size, trackRadius, alpha = .55f * pressure)
                            }
                            drawRoundRect(Color(0xFFF7FAFC), thumb.topLeft, thumb.size, corner, alpha = 1f - .70f * pressure)
                            withTransform({
                                translate(thumb.left, thumb.top)
                                scale(thumb.width, thumb.height, pivot = Offset.Zero)
                            }) { drawRect(sheen, size = Size(1f, 1f), alpha = pressure) }
                        }
                        if (light != null) {
                            lightPath.reset()
                            lightPath.addPath(trackPath)
                            lightPath.addPath(thumbPath)
                            light.draw(this, lightPath)
                        }
                        translate(thumb.center.x, thumb.center.y) {
                            drawRoundRect(rim, thumb.topLeft - thumb.center, thumb.size, corner, alpha = .84f * pressure, style = rimWidth)
                        }
                    }
                }
            }
    )
}
