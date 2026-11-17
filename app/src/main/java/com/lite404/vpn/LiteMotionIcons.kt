package com.lite404.vpn

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class MotionGlyph(val opticalScale: Float = 1f) {
    Lock,
    Reserve,
    Apps(1.20f),
    Gear(1.10f),
    Refresh
}

internal const val DockIconDuration = 1370
internal const val RefreshIconDuration = 720
internal const val CheckmarkDuration = 490

@Composable
internal fun rememberIconMotion(event: Any?, duration: Int, animateInitially: Boolean = true): State<Float> {
    if (!LocalGlass.current) return remember(duration) { mutableStateOf(duration.toFloat()) }
    val phase = remember { Animatable(duration.toFloat()) }
    val previous = remember { mutableStateOf(if (animateInitially) null else event) }
    LaunchedEffect(event, duration) {
        if (event != null && event != previous.value) {
            previous.value = event
            phase.snapTo(0f)
            phase.animateTo(duration.toFloat(), tween(duration, easing = LinearEasing))
        }
    }
    return phase.asState()
}

@Composable
internal fun MotionIcon(
    glyph: MotionGlyph,
    tint: Color,
    time: () -> Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val elapsed = time()
        val units = size.minDimension / 128f
        withTransform({
            translate((size.width - units * 128f) / 2, (size.height - units * 128f) / 2)
            scale(units, units, Offset.Zero)
        }) {
            scale(glyph.opticalScale * iconScale(glyph, elapsed), pivot = IconCenter) {
                when (glyph) {
                    MotionGlyph.Lock -> drawLock(tint, elapsed)
                    MotionGlyph.Reserve -> drawReserve(tint, elapsed)
                    MotionGlyph.Apps -> drawApps(tint, elapsed)
                    MotionGlyph.Gear -> rotate(keyframes(elapsed, GearTimes, GearAngles), IconCenter) {
                        drawPath(GearOutline, tint)
                    }
                    MotionGlyph.Refresh -> rotate(360f * fraction(elapsed, 180f, 720f, TurnEase), IconCenter) {
                        drawPath(RefreshCurve, tint, style = IconStroke)
                        drawPath(RefreshTip, tint, style = IconStroke)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppSelectionMark(checked: Boolean, modifier: Modifier = Modifier) {
    if (!LocalGlass.current) {
        androidx.compose.material3.Checkbox(checked, onCheckedChange = null, modifier = modifier.size(24.dp))
        return
    }
    val theme = MaterialTheme.colorScheme
    val duration = CheckmarkDuration.toFloat()
    val elapsed = remember { Animatable(if (checked) duration else 0f) }
    LaunchedEffect(checked) {
        if (checked) elapsed.animateTo(duration, tween(CheckmarkDuration, easing = LinearEasing))
        else elapsed.snapTo(0f)
    }
    Canvas(modifier.size(22.dp)) {
        val radius = CornerRadius(5.dp.toPx())
        if (checked) {
            drawRoundRect(theme.secondaryContainer, cornerRadius = radius)
        }
        val strokeWidth = 1.dp.toPx()
        drawRoundRect(
            color = if (checked) theme.primary.copy(alpha = .67f) else theme.onSurfaceVariant,
            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = radius,
            style = Stroke(strokeWidth)
        )
        if (checked) {
            val t = elapsed.value
            val units = (size.minDimension - 2.dp.toPx()) / 128f
            val inset = (size.minDimension - 128f * units) / 2f
            val amount = fraction(t, 0f, 300f, RevealEase)
            withTransform({
                translate(inset, inset)
                scale(units, units, Offset.Zero)
            }) {
                scale(.035f + .965f * springResponse(t, .65f, 240f, 13f, duration), pivot = IconCenter) {
                    drawCheck(theme.primary, amount)
                }
            }
        }
    }
}

private val IconCenter = Offset(64f, 64f)
private val SmoothEase = CubicBezierEasing(.4f, 0f, .2f, 1f)
private val RevealEase = CubicBezierEasing(.22f, 1f, .36f, 1f)
private val TurnEase = CubicBezierEasing(.30f, 0f, .16f, 1f)
private val CollapseTimes = floatArrayOf(0f, 70f, 180f)
private val CollapseScales = floatArrayOf(1f, .92f, .035f)
private val TiltTimes = floatArrayOf(80f, 130f, 180f)
private val TiltAngles = floatArrayOf(0f, -4f, 0f)
private val ReserveTurnTimes = floatArrayOf(250f, 340f, 430f, 520f, 610f, 710f, 860f)
private val ReserveTurns = floatArrayOf(0f, -10f, 8f, -5.5f, 3f, -1.2f, 0f)
private val ReserveBounceTimes = floatArrayOf(280f, 380f, 510f, 640f, 820f)
private val ReserveBounces = floatArrayOf(0f, -5f, 1.6f, -.6f, 0f)
private val GearTimes = floatArrayOf(180f, 280f, 610f, 770f, 980f)
private val GearAngles = floatArrayOf(0f, -17f, 96f, 88f, 90f)
private val AppCenters = listOf(Offset(43f, 43f), Offset(85f, 43f), Offset(85f, 85f), Offset(43f, 85f))

private val Shackle = svgPath("M42 59V41C42 14 86 14 86 41V59")
private val ReserveOutline = svgPath("M55.3 25.5Q64 10.5 72.7 25.5L111 91.5Q120 107 102 107H26Q8 107 17 91.5Z")
private val RefreshCurve = svgPath("M98 47A37 37 0 1 0 99 77")
private val RefreshTip = svgPath("M79 47H99V27")
private val IconStroke = Stroke(width = 10.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val GearOutline = gearPath()

private fun DrawScope.drawLock(tint: Color, t: Float) {
    val open = springResponse(t - 180f, .55f, 270f, 10f, 1190f)
    val close = springResponse(t - 820f, .50f, 320f, 15f, 550f)
    val angle = keyframes(t, TiltTimes, TiltAngles) + 25f * (open - close)
    rotate(angle, Offset(86f, 59f)) {
        drawPath(Shackle, tint, style = Stroke(11f, cap = StrokeCap.Round))
    }
    drawRoundRect(tint, Offset(27f, 55f), Size(74f, 50f), CornerRadius(12f))
}

private fun DrawScope.drawReserve(tint: Color, t: Float) {
    val dy = keyframes(t, ReserveBounceTimes, ReserveBounces)
    val outline = Path().apply {
        fillType = PathFillType.EvenOdd
        addPath(ReserveOutline)
        addRoundRect(RoundRect(Rect(60f, 47f + dy, 68f, 75f + dy), CornerRadius(4f)))
        addOval(Rect(59.5f, 81.5f + dy, 68.5f, 90.5f + dy))
    }
    rotate(keyframes(t, ReserveTurnTimes, ReserveTurns), Offset(64f, 94f)) { drawPath(outline, tint) }
}

private fun DrawScope.drawApps(tint: Color, t: Float) {
    AppCenters.forEachIndexed { index, center ->
        val p = if (t in 180f..<1120f) springResponse(t - 210f - index * 35f, .65f, 240f, 14f, 800f) else 1f
        val angle = -70f * (1f - p) * PI.toFloat() / 180f
        val d = (center - IconCenter) * (1f + (13f / 21f) * (1f - p))
        val position = IconCenter + Offset(d.x * cos(angle) - d.y * sin(angle), d.x * sin(angle) + d.y * cos(angle))
        val side = 32f * (.8f + .2f * p)
        rotate(-12f * (1f - p), position) {
            drawRoundRect(tint, position - Offset(side / 2, side / 2), Size(side, side), CornerRadius(side / 4))
        }
    }
}

private fun DrawScope.drawCheck(tint: Color, fraction: Float) {
    val start = Offset(29f, 65f)
    val elbow = Offset(52f, 88f)
    val end = Offset(100f, 40f)
    val distance = 71f * fraction
    drawCircle(tint, 6.5f, start)
    if (distance > 0f) {
        val path = Path().apply {
            moveTo(start.x, start.y)
            if (distance <= 23f) {
                lineTo(start.x + distance, start.y + distance)
            } else {
                lineTo(elbow.x, elbow.y)
                lineTo((elbow.x + distance - 23f).coerceAtMost(end.x), (elbow.y - distance + 23f).coerceAtLeast(end.y))
            }
        }
        drawPath(path, tint, style = Stroke(13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun iconScale(glyph: MotionGlyph, t: Float): Float = when {
    t < 180f -> keyframes(t, CollapseTimes, CollapseScales)
    glyph == MotionGlyph.Refresh -> .035f + .965f * fraction(t, 180f, 460f, RevealEase)
    else -> .035f + .965f * springResponse(t - 180f, .65f, 240f, if (glyph == MotionGlyph.Lock) 18f else 13f, 800f)
}

private fun springResponse(t: Float, mass: Float, stiffness: Float, damping: Float, settleAt: Float): Float {
    if (t <= 0f) return 0f
    if (t >= settleAt) return 1f
    val seconds = t / 1000f
    val decay = damping / (2f * mass)
    val frequency = sqrt(stiffness / mass - decay * decay)
    return 1f - exp(-decay * seconds) * (cos(frequency * seconds) + decay / frequency * sin(frequency * seconds))
}

private fun fraction(t: Float, start: Float, end: Float, easing: CubicBezierEasing): Float =
    easing.transform(((t - start) / (end - start)).coerceIn(0f, 1f))

private fun keyframes(t: Float, times: FloatArray, values: FloatArray): Float {
    for (index in 1 until times.size) {
        if (t <= times[index]) {
            val progress = fraction(t, times[index - 1], times[index], SmoothEase)
            return values[index - 1] + (values[index] - values[index - 1]) * progress
        }
    }
    return values.last()
}

private fun gearPath(): Path {
    val angles = listOf(-22.5f to 34f, -15f to 34f, -10f to 43f, 10f to 43f, 15f to 34f)
    val points = (0 until 8).flatMap { tooth ->
        angles.map { (angle, radius) ->
            val radians = (tooth * 45 + angle - 90) * PI.toFloat() / 180f
            IconCenter + Offset(radius * cos(radians), radius * sin(radians))
        }
    }
    return Path().apply {
        fillType = PathFillType.EvenOdd
        points.forEachIndexed { index, point ->
            val before = point * .54f + points[(index + points.size - 1) % points.size] * .46f
            val after = point * .54f + points[(index + 1) % points.size] * .46f
            if (index == 0) moveTo(before.x, before.y) else lineTo(before.x, before.y)
            quadraticTo(point.x, point.y, after.x, after.y)
        }
        close()
        addOval(Rect(48f, 48f, 80f, 80f))
    }
}

private fun svgPath(value: String): Path = PathParser().parsePathString(value).toPath()
