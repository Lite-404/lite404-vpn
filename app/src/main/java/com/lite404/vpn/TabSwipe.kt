package com.lite404.vpn

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class SwipeIntent { Undecided, Horizontal, Rejected }

internal data class TabSwipeProgress(val step: Int, val fraction: Float)

internal data class TabSwipeRelease(val step: Int, val velocity: Float)

internal class TabSwipeSettle(private val start: Float, private val end: Float, velocity: Float) {
    private val displacement = end - start
    private val distance = abs(displacement)
    private val speed = (if (displacement < 0f) -velocity else velocity).coerceAtLeast(0f)
    val durationMillis = ((200f + 145f * distance) / (1f + .2f * speed)).roundToInt().coerceIn(145, 345)
    private val slope = if (distance <= .001f) 0f else (speed * durationMillis / (1000f * distance)).coerceAtMost(2.8f)

    // Cubic Hermite interpolation carries release speed into a stationary landing.
    fun positionAt(elapsed: Float): Float {
        val t = elapsed.coerceIn(0f, 1f)
        val remaining = 1f - t
        val traveled = t * t * (3f - 2f * t) + slope * t * remaining * remaining
        return (start + displacement * traveled).coerceIn(minOf(start, end), maxOf(start, end))
    }
}

internal class TabSwipeGesture(
    private val slop: Float,
    private val lockDistance: Float,
    private val commitDistance: Float,
    private val verticalLimit: Float
) {
    private var intent = SwipeIntent.Undecided

    fun move(x: Float, y: Float): SwipeIntent {
        if (intent == SwipeIntent.Rejected) return intent
        val horizontal = abs(x)
        val vertical = abs(y)
        val mostlyHorizontal = horizontal > 1.75f * vertical
        if (vertical > verticalLimit || (intent == SwipeIntent.Undecided && vertical >= slop && !mostlyHorizontal)) {
            intent = SwipeIntent.Rejected
        } else if (horizontal >= lockDistance && mostlyHorizontal) {
            intent = SwipeIntent.Horizontal
        }
        return intent
    }

    fun stepOnRelease(x: Float, y: Float): Int {
        if (move(x, y) != SwipeIntent.Horizontal || abs(x) < commitDistance || abs(x) <= abs(y) * 2f) return 0
        return if (x < 0f) 1 else -1
    }

    fun progress(x: Float, width: Float): TabSwipeProgress {
        val fraction = ((abs(x) - lockDistance) / dragWidth(width)).coerceIn(0f, 1f)
        return TabSwipeProgress(if (x < 0f) 1 else -1, fraction)
    }

    fun progressVelocity(xVelocity: Float, width: Float, step: Int): Float =
        (-xVelocity * step / dragWidth(width)).coerceIn(-8f, 8f)

    private fun dragWidth(width: Float) = (width - lockDistance).coerceAtLeast(1f)
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.tabSwipe(
    enabled: Boolean,
    dockClearance: Dp,
    hasNeighbor: (Int) -> Boolean,
    onProgress: (TabSwipeProgress) -> Unit,
    onFinish: (TabSwipeRelease) -> Unit
): Modifier {
    val canMove by rememberUpdatedState(hasNeighbor)
    val update by rememberUpdatedState(onProgress)
    val finish by rememberUpdatedState(onFinish)
    if (!enabled) return this
    return pointerInput(dockClearance) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val edge = 24.dp.toPx()
            if (down.type != PointerType.Touch || down.position.x < edge || down.position.x > size.width - edge ||
                down.position.y >= size.height - dockClearance.toPx()) return@awaitEachGesture

            val slop = viewConfiguration.touchSlop
            val gesture = TabSwipeGesture(
                slop = slop,
                lockDistance = max(slop * 1.5f, 22.dp.toPx()),
                commitDistance = (size.width * .19f).coerceIn(64.dp.toPx(), 112.dp.toPx()),
                verticalLimit = 50.dp.toPx()
            )
            var preview: TabSwipeProgress? = null
            var release = TabSwipeRelease(0, 0f)
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            var lastMove = down.uptimeMillis
            try {
                while (true) {
                    // Descendant scrollables and switches see the movement first.
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                    val delta = change.position - down.position
                    val intent = gesture.move(delta.x, delta.y)
                    if (intent == SwipeIntent.Rejected) break
                    if (!change.pressed) {
                        preview?.let {
                            val speed = if (change.uptimeMillis - lastMove <= 70L) velocity.calculateVelocity().x else 0f
                            release = TabSwipeRelease(
                                gesture.stepOnRelease(delta.x, delta.y),
                                gesture.progressVelocity(speed, size.width.toFloat(), it.step)
                            )
                            change.consume()
                        }
                        break
                    }

                    change.historical.forEach { velocity.addPosition(it.uptimeMillis, it.position) }
                    velocity.addPosition(change.uptimeMillis, change.position)
                    if (change.position.x != change.previousPosition.x) lastMove = change.uptimeMillis
                    if (intent != SwipeIntent.Horizontal) {
                        continue
                    }

                    val progress = gesture.progress(delta.x, size.width.toFloat())
                    if (canMove(progress.step)) {
                        preview = progress
                        update(progress)
                    } else {
                        preview = preview?.copy(fraction = 0f)
                        preview?.let(update)
                    }
                    if (preview != null) change.consume()
                }
            } finally {
                if (preview != null) finish(release)
            }
        }
    }
}
