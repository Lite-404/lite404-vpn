package com.lite404.vpn

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Modifier.pageEntrance(enabled: Boolean = true): Modifier {
    val animate = enabled && LocalGlass.current
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        translationX = size.width * (1f - progress.value)
    }
}

@Composable
internal fun RouteEditor(title: String, value: String, onChange: (String) -> Unit, placeholder: String) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val parsed = remember(value) { runCatching { Lists.parse(value) }.getOrNull() }
    val shape = RoundedCornerShape(17.dp)
    Column(Modifier.fillMaxWidth().background(colors.surface, shape)
        .border(1.dp, if (focused) colors.primary.copy(alpha = .55f) else Color.White.copy(alpha = .06f), shape)
        .pointerSheen(17).padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            SettingsSymbol(if (title == "Напрямую") "Network" else "Primary", colors.primary)
            Text(tr(title), modifier = Modifier.weight(1f), fontSize = 15.sp)
            parsed?.let {
                val count = it.first.size + it.second.size
                if (count > 0) Text(count.toString(), color = colors.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        BasicTextField(
            value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 95.dp).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = tr(title) },
            textStyle = TextStyle(color = colors.onSurface, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 23.sp),
            cursorBrush = SolidColor(colors.primary),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) Text(placeholder, color = colors.onSurfaceVariant.copy(alpha = .65f),
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 23.sp)
                    field()
                }
            }
        )
        if (parsed == null) Text(tr("Проверьте формат адресов"), color = colors.error, fontSize = 12.sp)
    }
}

@Composable
internal fun SettingsSymbol(kind: String, color: Color, modifier: Modifier = Modifier) {
    val glyph = when (kind) {
        "Primary" -> MotionGlyph.Lock
        "Reserve" -> MotionGlyph.Reserve
        "Interface" -> MotionGlyph.Gear
        "Updates" -> MotionGlyph.Refresh
        else -> null
    }
    if (glyph != null) {
        MotionIcon(glyph, color, { DockIconDuration.toFloat() }, modifier.size(23.dp))
        return
    }
    Canvas(modifier.size(23.dp)) {
        val u = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * u, y * u)
        val stroke = Stroke(1.9f * u, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, xx: Float, yy: Float) =
            drawLine(color, point(x, y), point(xx, yy), stroke.width, StrokeCap.Round)
        when (kind) {
            "Copy" -> {
                drawRoundRect(color, point(8f, 7f), Size(12f * u, 14f * u), androidx.compose.ui.geometry.CornerRadius(2f * u), style = stroke)
                line(4f, 16f, 4f, 3f)
                line(4f, 3f, 15f, 3f)
            }
            "Clear" -> {
                line(4f, 6f, 20f, 6f)
                line(9f, 3f, 15f, 3f)
                drawRoundRect(color, point(6f, 6f), Size(12f * u, 15f * u), androidx.compose.ui.geometry.CornerRadius(2f * u), style = stroke)
                line(10f, 10f, 10f, 17f)
                line(14f, 10f, 14f, 17f)
            }
            "Share" -> {
                line(6f, 12f, 18f, 5f)
                line(6f, 12f, 18f, 19f)
                listOf(6f to 12f, 18f to 5f, 18f to 19f).forEach { (x, y) ->
                    drawCircle(color, 2.5f * u, point(x, y))
                }
            }
            "Network", "Language" -> {
                drawCircle(color, 9f * u, style = stroke)
                drawOval(color, topLeft = point(8f, 3f), size = Size(8f * u, 18f * u), style = stroke)
                line(3f, 12f, 21f, 12f)
            }
            "Startup" -> {
                drawArc(color, -45f, 270f, false, topLeft = point(4f, 4f), size = Size(16f * u, 16f * u), style = stroke)
                line(12f, 2f, 12f, 11f)
            }
            "Themes" -> {
                drawCircle(color, 9f * u, style = stroke)
                listOf(8f to 8f, 15f to 7f, 17f to 13f).forEach { (x, y) -> drawCircle(color, 1.6f * u, point(x, y)) }
                drawCircle(color, 2.2f * u, point(9f, 17f))
            }
            "Account" -> {
                drawCircle(color, 4f * u, point(12f, 7f), style = stroke)
                drawArc(color, 180f, 180f, false, topLeft = point(4f, 14f), size = Size(16f * u, 12f * u), style = stroke)
            }
            else -> {
                for (y in listOf(6f, 12f, 18f)) {
                    drawCircle(color, 1.2f * u, point(4f, y))
                    line(9f, y, 21f, y)
                }
            }
        }
    }
}
