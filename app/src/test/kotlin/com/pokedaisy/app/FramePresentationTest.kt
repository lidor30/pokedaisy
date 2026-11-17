package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePresentationTest {
    @Test fun replacingAnUnshownFrameKeepsItsFirstRefresh() {
        assertEquals(2L, pendingFrameShowAt(true, 2L, 4L))
        assertEquals(2L, pendingFrameShowAt(true, 2L, 6L))
    }

    @Test fun aConsumedFrameDoesNotPassItsDeadlineToTheNextFrame() {
        assertEquals(4L, pendingFrameShowAt(false, 2L, 4L))
        assertEquals(2L, pendingFrameShowAt(false, 0L, 2L))
    }

    @Test fun fastForwardAndTimerFramesRemainImmediate() {
        assertEquals(0L, pendingFrameShowAt(true, 2L, 0L))
        assertEquals(0L, pendingFrameShowAt(true, 0L, 2L))
        assertEquals(0L, pendingFrameShowAt(false, 2L, 0L))
    }

    @Test fun audioCatchUpCannotKeepMovingThePendingDeadline() {
        var showAt = 2L
        for (next in 4L..20L step 2) showAt = pendingFrameShowAt(true, showAt, next)
        assertEquals(2L, showAt)
    }

    @Test fun aProducerThatWinsTheRefreshRaceCannotStarveTheDisplay() {
        val shown = refreshesShown(producerFirst = true)
        assertTrue("No frames shown: $shown", shown.isNotEmpty())
        assertEquals(2L, shown.first())
        assertTrue("Display stopped progressing: $shown", shown.last() >= 10L)
    }

    @Test fun ordinary120HzPresentationStillShowsEachFrameForTwoRefreshes() {
        assertEquals(listOf(2L, 4L, 6L, 8L, 10L, 12L), refreshesShown(producerFirst = false))
    }

    private fun refreshesShown(producerFirst: Boolean): List<Long> {
        var fresh = true
        var showAt = 2L
        val shown = mutableListOf<Long>()
        for (tick in 1L..12L) {
            fun publish() {
                showAt = pendingFrameShowAt(fresh, showAt, tick + 2)
                fresh = true
            }
            // A 60 fps game on a 120 Hz display wakes its producer every second refresh.
            if (producerFirst && tick % 2 == 0L) publish()
            if (fresh && tick >= showAt) {
                shown += tick
                fresh = false
            }
            if (!producerFirst && tick % 2 == 0L) publish()
        }
        return shown
    }
}
