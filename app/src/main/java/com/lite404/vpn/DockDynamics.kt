package com.lite404.vpn

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

internal data class DockInput(
    val target: Float,
    val held: Boolean,
    val activation: Int,
    val dragging: Boolean = false,
    val sameTab: Boolean = false,
    val swiping: Boolean = false
)

internal data class DockPose(
    val position: Float,
    val width: Float = .98f,
    val height: Float = .95f,
    val glass: Float = 0f,
    val shear: Float = 0f,
    val lift: Float = 0f
) {
    fun horizontalSpan(railWidth: Float, railHeight: Float, slots: Int, outerGutter: Float): DockSpan {
        val slot = railWidth / slots
        val edge = railHeight * .02f - outerGutter * lift
        val minimum = edge + slot * .39f
        val maximum = railWidth - minimum
        val cushion = slot * .11f
        val center = ((position + .5f) * slot).easeAbove(minimum, cushion).easeBelow(maximum, cushion)
        val availableWidth = 2f * min(center - edge, railWidth - edge - center)
        val lensWidth = (slot * width).easeBelow(availableWidth, slot * .09f)
        return DockSpan(center, lensWidth)
    }
}

internal data class DockSpan(val center: Float, val width: Float)

// The response and its slope both match at the start of the cushion. Pressure
// keeps moving the center and squeezing the lens as it approaches the boundary.
private fun Float.easeAbove(limit: Float, cushion: Float): Float {
    val start = limit + cushion
    return if (this >= start) this else limit + cushion * exp((this - start) / cushion)
}

private fun Float.easeBelow(limit: Float, cushion: Float): Float = -(-this).easeAbove(-limit, cushion)

internal fun resistDockEdge(position: Float, last: Int): Float {
    val inner = position.coerceIn(0f, last.toFloat())
    val overflow = position - inner
    val reach = .24f
    return inner + reach * tanh(overflow / reach)
}

internal class DockDynamics(start: Float) {
    private val rest = DockPose(start)
    private val center = DampedSpring(start, frequency = DragFrequency, damping = .70f)
    private val width = DampedSpring(rest.width, frequency = 24f, damping = .57f)
    private val height = DampedSpring(rest.height, frequency = 22f, damping = .53f)
    private val material = DampedSpring(0f, frequency = 26f, damping = .78f)
    private val shear = DampedSpring(0f, frequency = 25f, damping = .70f)
    private val lift = DampedSpring(0f, frequency = 25f, damping = .76f)
    private var activation = 0
    private var tap = 0f
    private var holdTime = 0f
    private var destination = start
    private var frequency = SwitchFrequency
    private var dragging = false
    private var swiping = false
    private var finishingSwipe = false
    private var repeatTap = false

    val pose: DockPose
        get() = DockPose(
            center.value, width.value, height.value,
            material.value.coerceIn(0f, 1f), shear.value.coerceIn(-1f, 1f),
            lift.value.coerceIn(0f, 1f)
        )

    fun needsFrame(input: DockInput): Boolean =
        input.activation != activation || tap > 0f || input.swiping != swiping ||
            (input.held && holdTime < HoldThreshold) ||
            !center.isAt(input.target) || !material.isAt(contact(input)) || !lift.isAt(liftTarget(input)) ||
            !width.isAt(widthTarget(swipeStrain(input))) || !height.isAt(heightTarget(swipeStrain(input))) ||
            !shear.isAt(0f)

    fun advance(input: DockInput, seconds: Float, contactSeconds: Float = seconds): DockPose {
        val newPress = input.activation != activation
        if (newPress) {
            activation = input.activation
            repeatTap = input.sameTab
            tap = if (repeatTap) .105f else .15f
            holdTime = 0f
        }
        val swipePull = if (input.swiping) tanh((input.target - destination) * 12f) else 0f
        if (input.swiping) {
            tap = 0f
            repeatTap = false
            lift.snap(0f)
        } else if (swiping && !input.held && !newPress) {
            finishingSwipe = true
            frequency = SwitchFrequency
            center.snap(input.target)
            material.snap(material.value)
            width.snap(width.value)
            height.snap(height.value)
            shear.snap(shear.value)
        } else if (input.dragging || dragging) {
            frequency = DragFrequency
        } else if (input.target != destination) {
            // A longer jump has more time to travel and keeps its glass through arrival.
            val extraSlots = (abs(input.target - center.value) - 1f).coerceIn(0f, 2f)
            frequency = SwitchFrequency / (1f + .34f * extraSlots)
            tap = max(tap, .16f + .042f * extraSlots)
            repeatTap = false
        }
        if (input.swiping || newPress || input.held || (!swiping && input.target != destination)) {
            finishingSwipe = false
        }
        destination = input.target
        dragging = input.dragging
        swiping = input.swiping
        holdTime = if (input.held) holdTime + contactSeconds else 0f
        if (seconds <= 0f) {
            tap = 0f
            center.snap(input.target)
            material.snap(contact(input))
            lift.snap(liftTarget(input))
            width.snap(widthTarget(swipeStrain(input)))
            height.snap(heightTarget(swipeStrain(input)))
            shear.snap(0f)
        } else {
            material.advance(contact(input), seconds, critical = finishingSwipe)
            tap = (tap - seconds).coerceAtLeast(0f)
            lift.advance(liftTarget(input), seconds)
            if (input.swiping) center.snap(input.target)
            else center.advance(input.target, seconds, frequency)

            // The finger pulls ahead of the lens. That displacement loads the shape
            // springs, which retain their own momentum when the finger stops or reverses.
            val pull = if (input.swiping) swipePull else tanh((input.target - center.value) * 4.8f)
            val strain = if (input.swiping) swipeStrain(input) else pull * pull
            width.advance(widthTarget(strain), seconds, critical = finishingSwipe)
            height.advance(heightTarget(strain), seconds, critical = finishingSwipe)
            shear.advance(pull * material.value.coerceIn(0f, 1f), seconds, critical = finishingSwipe)
        }
        return pose
    }

    private fun liftTarget(input: DockInput): Float =
        if (input.held && (input.sameTab || input.dragging || holdTime >= HoldThreshold)) 1f else 0f

    private fun contact(input: DockInput): Float = when {
        input.swiping -> .74f
        liftTarget(input) > 0f -> 1f
        input.held || tap > 0f -> if (repeatTap) .48f else .74f
        else -> 0f
    }

    private fun swipeStrain(input: DockInput): Float {
        if (!input.swiping) return 0f
        val fraction = input.target % 1f
        return 4f * fraction * (1f - fraction)
    }

    private fun widthTarget(strain: Float): Float =
        rest.width + shapeChange(if (repeatTap) -.068f else .13f - .06f * strain, .49f - .34f * strain)

    private fun heightTarget(strain: Float): Float =
        rest.height + shapeChange(if (repeatTap) -.075f else .015f - .18f * strain, .43f - .58f * strain)

    private fun shapeChange(inside: Float, lifted: Float): Float =
        material.value.coerceIn(0f, 1f) * (inside + (lifted - inside) * lift.value.coerceIn(0f, 1f))

    private companion object {
        const val HoldThreshold = .17f
        const val SwitchFrequency = 19f
        const val DragFrequency = 27f
    }
}

private class DampedSpring(initial: Float, private val frequency: Float, private val damping: Float) {
    private val dampedFrequencyRatio = sqrt(1f - damping * damping)
    private var velocity = 0f
    var value = initial
        private set

    fun isAt(target: Float): Boolean = abs(value - target) < .001f && abs(velocity) < .015f

    fun snap(target: Float) {
        value = target
        velocity = 0f
    }

    fun advance(target: Float, seconds: Float, frequency: Float = this.frequency, critical: Boolean = false) {
        val displacement = value - target
        if (critical) {
            val phase = velocity + frequency * displacement
            val envelope = exp(-frequency * seconds)
            value = target + envelope * (displacement + phase * seconds)
            velocity = envelope * (velocity - frequency * phase * seconds)
            if (isAt(target)) snap(target)
            return
        }
        val decay = frequency * damping
        val angular = frequency * dampedFrequencyRatio
        val phase = (velocity + decay * displacement) / angular
        val cosine = cos(angular * seconds)
        val sine = sin(angular * seconds)
        val envelope = exp(-decay * seconds)
        val oscillation = displacement * cosine + phase * sine
        value = target + envelope * oscillation
        velocity = envelope * (angular * (phase * cosine - displacement * sine) - decay * oscillation)
        if (isAt(target)) snap(target)
    }
}
