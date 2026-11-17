package com.lite404.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSwipeTest {
    private fun gesture() = TabSwipeGesture(slop = 8f, lockDistance = 22f, commitDistance = 72f, verticalLimit = 50f)

    @Test
    fun aDeliberateSwipeSelectsExactlyOneNeighborOnRelease() {
        val left = gesture()
        assertEquals(SwipeIntent.Undecided, left.move(-12f, 3f))
        assertEquals(SwipeIntent.Horizontal, left.move(-27f, 4f))
        assertEquals(1, left.stepOnRelease(-230f, 13f))
        assertEquals(-1, gesture().stepOnRelease(95f, -10f))
    }

    @Test
    fun aVerticalOrDiagonalStartCannotTurnIntoNavigation() {
        val scroll = gesture()
        assertEquals(SwipeIntent.Rejected, scroll.move(6f, 13f))
        assertEquals(0, scroll.stepOnRelease(140f, 24f))
        assertEquals(0, gesture().stepOnRelease(-92f, 49f))
    }

    @Test
    fun aShortDragOrLargeVerticalDetourDoesNotNavigate() {
        assertEquals(0, gesture().stepOnRelease(-55f, 5f))
        val turn = gesture()
        assertEquals(SwipeIntent.Horizontal, turn.move(35f, 5f))
        assertEquals(SwipeIntent.Rejected, turn.move(64f, 51f))
        assertEquals(0, turn.stepOnRelease(110f, 8f))
    }

    @Test
    fun previewStartsAtTheLockAndTracksTheFingerInBothDirections() {
        val swipe = gesture()
        assertEquals(TabSwipeProgress(1, 0f), swipe.progress(-22f, 372f))
        assertEquals(TabSwipeProgress(1, .5f), swipe.progress(-197f, 372f))
        assertEquals(TabSwipeProgress(-1, .25f), swipe.progress(109.5f, 372f))
        assertEquals(TabSwipeProgress(-1, 1f), swipe.progress(410f, 372f))
    }

    @Test
    fun aSwipeStartsSettlingAtTheLastVisiblePositionAndCarriesReleaseSpeed() {
        val drag = gesture()
        assertEquals(2f, drag.progressVelocity(-700f, 372f, 1), .0001f)
        assertEquals(2f, drag.progressVelocity(700f, 372f, -1), .0001f)
        val moving = TabSwipeSettle(.42f, 1f, 2f)
        val still = TabSwipeSettle(.42f, 1f, 0f)
        assertEquals(.42f, moving.positionAt(0f), 0f)
        assertTrue(moving.positionAt(.1f) > still.positionAt(.1f))
        assertEquals(1f, moving.positionAt(1f), .0001f)
    }

    @Test
    fun fastReleasesAndCanceledSwipesReachTheirTargetWithoutOvershoot() {
        val forward = TabSwipeSettle(.86f, 1f, 8f)
        val canceled = TabSwipeSettle(.32f, 0f, -6f)
        val frames = (0..60).map { it / 60f }
        assertTrue(frames.map(forward::positionAt).zipWithNext().all { (a, b) -> b >= a && b <= 1f })
        assertTrue(frames.map(canceled::positionAt).zipWithNext().all { (a, b) -> b <= a && b >= 0f })
        assertEquals(0f, canceled.positionAt(1f), .0001f)
    }
}
