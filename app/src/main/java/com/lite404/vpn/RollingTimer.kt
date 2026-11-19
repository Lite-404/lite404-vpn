package com.lite404.vpn

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

// The reference's upward roll takes roughly 300 ms. One finite animation drives
// every changed digit, so carries stay synchronized and finish before the next tick.
internal const val TimerRollMillis = 320

@Composable
internal fun RollingTimer(value: String, running: Boolean, fontSize: TextUnit = 36.sp,
                          lineHeight: TextUnit = 44.sp, letterSpacing: TextUnit = 2.sp) {
    val animated = LocalGlass.current && running
    var previous by remember { mutableStateOf(value) }
    var target by remember { mutableStateOf(value) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(value, animated) {
        previous = target
        target = value
        if (animated && previous != value && previous.length == value.length) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(TimerRollMillis, easing = CubicBezierEasing(.22f, .7f, .18f, 1f)))
        } else progress.snapTo(1f)
    }
    val style = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = fontSize,
        lineHeight = lineHeight, fontFeatureSettings = "zero")
    val measurer = rememberTextMeasurer()
    val measure = remember(style, measurer) { measurer.measure("0", style) }
    val density = LocalDensity.current
    val width = with(density) { measure.size.width.toDp() + letterSpacing.toDp() }
    val height = with(density) { lineHeight.toDp() }
    Row(Modifier.height(height).clipToBounds().clearAndSetSemantics {
        contentDescription = tr("время подключения") + ": " + value
    }, verticalAlignment = Alignment.CenterVertically) {
        target.forEachIndexed { index, digit ->
            val old = previous.getOrNull(index) ?: digit
            Box(Modifier.width(width).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (old == digit || !animated) Text(digit.toString(), style = style, maxLines = 1)
                else {
                    fun Modifier.roll(incoming: Boolean) = graphicsLayer {
                        val p = progress.value
                        translationY = size.height * .72f * if (incoming) 1f - p else -p
                        alpha = if (incoming) p else 1f - p
                        if (Build.VERSION.SDK_INT >= 31) {
                            val blur = sin(p * Math.PI).toFloat() * 2.5.dp.toPx()
                            renderEffect = if (blur > .1f) BlurEffect(blur, blur, TileMode.Decal) else null
                        }
                    }
                    Text(old.toString(), Modifier.roll(false), style = style, maxLines = 1)
                    Text(digit.toString(), Modifier.roll(true), style = style, maxLines = 1)
                }
            }
        }
    }
}
