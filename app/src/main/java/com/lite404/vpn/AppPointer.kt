package com.lite404.vpn

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

private data class AppPointer(val position: Offset = Offset.Zero, val visible: Boolean = false, val press: Int = 0)
internal class PointerGlowTarget internal constructor(
    private val position: () -> Offset,
    private val opacity: State<Float>,
    private val glow: Brush,
    private val radius: Float
) {
    private var origin by mutableStateOf(Offset.Zero)
    val modifier = Modifier.onGloballyPositioned { origin = it.positionInRoot() }

    fun draw(scope: DrawScope, mask: Path) = with(scope) {
        val alpha = opacity.value
        if (alpha <= 0f) return@with
        val center = position() - origin
        if (center.x <= -radius || center.x >= size.width + radius ||
            center.y <= -radius || center.y >= size.height + radius) return@with
        clipPath(mask) {
            translate(center.x, center.y) { drawCircle(glow, radius, center = Offset.Zero, alpha = alpha) }
        }
    }
}

private class PointerLight(
    val pointer: State<AppPointer>,
    val opacity: State<Float>,
    val glow: Brush,
    val radius: Float
)
private val LocalPointerLight = staticCompositionLocalOf<PointerLight?> { null }

@Composable
internal fun AppPointerSurface(modifier: Modifier = Modifier, haptics: Boolean, content: @Composable BoxScope.() -> Unit) {
    val view = LocalView.current
    val feedback = if (haptics) Modifier.pointerInput(view) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (down.type != PointerType.Touch) return@awaitEachGesture
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                    event.changes.any { it.id != down.id && it.pressed }
                ) break
                if (!change.pressed) {
                    if (change.isConsumed) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    break
                }
            }
        }
    } else Modifier
    var observer: Modifier = Modifier
    val light = if (LocalGlass.current) {
        val pointer = remember { mutableStateOf(AppPointer()) }
        val origin = remember { mutableStateOf(Offset.Zero) }
        val opacity = rememberPointerOpacity(pointer)
        val radius = with(LocalDensity.current) { 125.dp.toPx() }
        observer = Modifier.onGloballyPositioned { origin.value = it.positionInRoot() }
                .pointerInput(pointer) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.pressed } ?: event.changes.firstOrNull {
                                it.type == PointerType.Mouse && event.type != PointerEventType.Exit &&
                                    it.position.x >= 0f && it.position.x < size.width &&
                                    it.position.y >= 0f && it.position.y < size.height
                            }
                            val previous = pointer.value
                            if (change != null) {
                                val started = change.pressed && !change.previousPressed
                                pointer.value = AppPointer(
                                    position = change.position + origin.value,
                                    visible = true,
                                    press = previous.press + if (started) 1 else 0
                                )
                            } else if (previous.visible) {
                                pointer.value = previous.copy(visible = false)
                            }
                        }
                    }
                }
        remember(pointer, opacity, radius) {
            val glow = ShaderBrush(RadialGradientShader(
                center = Offset.Zero, radius = radius,
                colors = listOf(.24f, .18f, .10f, .035f, .007f, 0f).map { Color.White.copy(alpha = it) },
                colorStops = listOf(0f, .24f, .46f, .68f, .86f, 1f)
            ))
            PointerLight(pointer, opacity, glow, radius)
        }
    } else null
    CompositionLocalProvider(LocalPointerLight provides light) {
        Box(modifier.fillMaxSize().then(feedback).then(observer), content = content)
    }
}

@Composable
private fun rememberPointerOpacity(pointer: State<AppPointer>): State<Float> {
    val visible by remember(pointer) { derivedStateOf { pointer.value.visible } }
    val press by remember(pointer) { derivedStateOf { pointer.value.press } }
    val opacity = remember { Animatable(0f) }
    val shownPress = remember { mutableIntStateOf(0) }
    LaunchedEffect(visible, press) {
        if (visible) {
            opacity.animateTo(1f, tween(75))
            shownPress.intValue = press
        } else {
            if (press != shownPress.intValue) {
                shownPress.intValue = press
                if (opacity.value < .55f) opacity.animateTo(.55f, tween(55))
            }
            opacity.animateTo(0f, tween(190))
        }
    }
    return opacity.asState()
}

@Composable
internal fun rememberPointerGlowTarget(): PointerGlowTarget? {
    val light = LocalPointerLight.current ?: return null
    return remember(light) { PointerGlowTarget({ light.pointer.value.position }, light.opacity, light.glow, light.radius) }
}
