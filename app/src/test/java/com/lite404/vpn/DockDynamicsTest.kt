package com.lite404.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockDynamicsTest {
    @Test
    fun heldLensExpandsAndAStationaryTargetLetsTheShapeRebound() {
        val model = DockDynamics(0f)
        val held = DockInput(0f, held = true, activation = 1)
        repeat(120) { model.advance(held, Frame) }
        val expanded = model.pose
        assertEquals(1f, expanded.glass, 0f)
        assertTrue(expanded.height > 1.25f)

        val compressed = (1..36).map { frame ->
            model.advance(held.copy(target = frame / 18f, dragging = true), Frame)
        }.last()
        assertTrue(compressed.height < expanded.height - .12f)
        assertTrue(compressed.width < expanded.width - .06f)

        val stopped = held.copy(target = 2f)
        val recovery = (1..120).map { model.advance(stopped, Frame) }
        assertTrue(recovery.maxOf { it.height } > expanded.height + .008f)
        assertEquals(expanded.height, recovery.last().height, .001f)
        assertFalse(model.needsFrame(stopped))
    }

    @Test
    fun tappingTheCurrentSlotOnlyGivesAQuietPressAndSettlesBackToSolid() {
        val model = DockDynamics(2f)
        val tap = DockInput(2f, held = false, activation = 1, sameTab = true)
        val frames = (1..120).map { model.advance(tap, Frame) }
        assertTrue(frames.maxOf { it.glass } in 0.32f..0.55f)
        assertTrue(frames.minOf { it.height } < .94f)
        assertTrue(frames.all { it.position == 2f && it.lift == 0f })
        assertEquals(DockPose(2f), frames.last())
        assertFalse(model.needsFrame(tap))
    }

    @Test
    fun aQuickTabSwitchStaysInTheRailForTheWholeTransition() {
        val model = DockDynamics(0f)
        val press = DockInput(3f, held = true, activation = 1)
        val frames = (1..150).map { frame ->
            model.advance(press.copy(held = frame < 9), Frame)
        }
        assertTrue(frames.maxOf { it.glass } > .6f)
        assertTrue(frames.all { it.lift == 0f && it.height < 1f })
        assertEquals(DockPose(3f), frames.last())
    }

    @Test
    fun longerSwitchesTakeMoreTimeAndKeepTheGlassThroughArrival() {
        val arrivalFrames = (1..3).map { distance ->
            val model = DockDynamics(0f)
            val input = DockInput(distance.toFloat(), held = false, activation = 0)
            val poses = (1..150).map { model.advance(input, Frame) }
            val arrival = poses.indexOfFirst { it.position >= distance * .9f }
            assertTrue("The glass should still be visible at arrival", poses[arrival].glass > .45f)
            assertTrue(poses.all { it.lift == 0f && it.height < 1f })
            assertEquals(DockPose(distance.toFloat()), poses.last())
            arrival + 1
        }
        assertTrue(arrivalFrames[0] >= 16)
        assertTrue(arrivalFrames[1] > arrivalFrames[0] * 1.2f)
        assertTrue(arrivalFrames[2] > arrivalFrames[0] * 1.5f)
    }

    @Test
    fun holdAndHorizontalDragCanBothLiftTheCapsuleOutOfTheRail() {
        val press = DockInput(0f, held = true, activation = 1)
        val held = DockDynamics(0f)
        repeat(15) { held.advance(press, Frame) }
        assertEquals(0f, held.pose.lift, 0f)
        repeat(55) { held.advance(press, Frame) }
        assertTrue(held.pose.lift > .96f && held.pose.height > 1.2f)

        val dragged = DockDynamics(0f)
        val earlyDrag = press.copy(target = .6f, dragging = true)
        repeat(14) { dragged.advance(earlyDrag, Frame) }
        assertTrue(dragged.pose.lift > .5f)
    }

    @Test
    fun releasedLensCarriesPositionMomentumIntoTheSnap() {
        val model = DockDynamics(0f)
        val held = DockInput(0f, held = true, activation = 1)
        repeat(60) { model.advance(held, Frame) }
        repeat(24) { frame -> model.advance(held.copy(target = frame / 18f, dragging = true), Frame) }
        val before = model.pose
        val release = DockInput(1f, held = false, activation = 1)
        val after = model.advance(release, Frame)
        assertTrue(after.position > before.position)
        repeat(120) { model.advance(release, Frame) }
        assertEquals(DockPose(1f), model.pose)
    }

    @Test
    fun anEndTabShowsTheOvershootBothDuringATapAndWithTheLensLifted() {
        for (lifted in listOf(false, true)) {
            val model = DockDynamics(3f)
            val initial = DockInput(3f, held = lifted, activation = 1)
            repeat(100) { model.advance(initial, Frame) }
            val destination = initial.copy(target = 0f, dragging = lifted, activation = 2)
            val spans = (1..140).map {
                model.advance(destination, Frame).horizontalSpan(400f, 72f, 4, 30f)
            }
            val rebound = spans.minBy { it.center }
            val resting = spans.last()
            assertTrue(resting.center - rebound.center > 4f)
            assertTrue(resting.width - rebound.width > 3f)
            val outsideLimit = if (lifted) -30f else 0f
            assertTrue(spans.all { it.center - it.width / 2f >= outsideLimit })
        }
    }

    @Test
    fun edgeResistanceBuildsUpGraduallyPastTheLastSlot() {
        assertEquals(3.01f, resistDockEdge(3.01f, 3), .00005f)
        assertTrue(resistDockEdge(3.25f, 3) < 3.25f)
        assertTrue(resistDockEdge(3.5f, 3) > resistDockEdge(3.25f, 3))
    }

    @Test
    fun pageSwipeFollowsProgressInBothDirectionsWithoutLiftingTheLens() {
        val model = DockDynamics(1f)
        val swipe = DockInput(1f, held = false, activation = 0, swiping = true)
        for (position in listOf(1.1f, 1.55f, 1.3f, 1f, .65f, 0f)) {
            val pose = model.advance(swipe.copy(target = position), Frame)
            assertEquals(position, pose.position, 0f)
            assertEquals(0f, pose.lift, 0f)
        }
        val settled = swipe.copy(target = 0f)
        repeat(200) { model.advance(settled, Frame) }
        assertTrue(model.pose.glass > .7f)
        val committed = settled.copy(swiping = false)
        repeat(200) { model.advance(committed, Frame) }
        assertEquals(DockPose(0f), model.pose)
        assertFalse(model.needsFrame(committed))
    }

    @Test
    fun swipeHandoffPinsTheCenterAndDissolvesTheGlassWithoutAnotherBounce() {
        for (destination in listOf(0f, 2f)) {
            val model = DockDynamics(1f)
            val swipe = DockInput(1f, held = false, activation = 0, swiping = true)
            repeat(55) { frame ->
                model.advance(swipe.copy(target = 1f + (destination - 1f) * frame / 55f), Frame)
            }
            val after = swipe.copy(target = destination, swiping = false)
            val settled = (1..150).map { model.advance(after, Frame) }
            assertTrue(settled.all { it.position == destination && it.lift == 0f })
            assertTrue(settled.zipWithNext().all { (a, b) -> b.glass <= a.glass })
            assertEquals(DockPose(destination), settled.last())
        }
    }

    private companion object {
        const val Frame = 1f / 120f
    }

    @Test
    fun pressingTheActiveTabStartsLiftingOnTheFirstFrame() {
        val model = DockDynamics(1f)
        val press = DockInput(1f, held = true, activation = 1, sameTab = true)
        assertTrue(model.advance(press, Frame).lift > 0f)
    }
}
