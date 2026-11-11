package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DsFrameSkipTest {
    private val budget = 14_000_000L

    /** Runs [frames] frames: a drawn one costs [drawn] ns, a skipped one [skipped]; the draw pattern. */
    private fun DsFrameSkip.run(frames: Int, drawn: Long, skipped: Long): List<Boolean> {
        val seen = mutableListOf<Boolean>()
        var promised: Boolean? = null
        repeat(frames) {
            val (draw, next) = plan()
            promised?.let { assertEquals("a frame is drawn as the one before promised", it, draw) }
            promised = next
            seen += draw
            frameDone(if (draw) drawn else skipped, draw)
        }
        return seen
    }

    @Test fun aDeviceThatKeepsUpDrawsEveryFrame() {
        val s = DsFrameSkip(budget)
        val drawn = s.run(600, drawn = 9_000_000L, skipped = 6_000_000L)
        assertTrue(drawn.all { it })
        assertEquals(0, s.skip)
    }

    @Test fun aSlowDeviceDrawsOneFrameInTwo() {
        // 16 ms drawn, 11 ms skipped: one in two averages 13.5 ms, under the 14 ms budget.
        val s = DsFrameSkip(budget)
        val drawn = s.run(600, drawn = 16_000_000L, skipped = 11_000_000L)
        assertEquals(1, s.skip)
        val tail = drawn.takeLast(100)
        assertEquals(50, tail.count { it })
    }

    @Test fun aSlowerDeviceSkipsThree() {
        // 22 ms drawn, 12.5 skipped: one in three averages 15.7 ms (over the 15.4 ms reach), one in four 14.9.
        val s = DsFrameSkip(budget)
        val drawn = s.run(600, drawn = 22_000_000L, skipped = 12_500_000L)
        assertEquals(DsFrameSkip.MAX_SKIP, s.skip)
        assertEquals(25, drawn.takeLast(100).count { it })
    }

    @Test fun aDeviceSkippingCantSaveDrawsEveryFrame() {
        // The Galaxy Tab A (32-bit, interpreted): 52 ms drawn, ~40 skipped - three in four skipped would still be
        // 43 ms, far from the DS's speed, for a picture at a quarter of the frames. Better smooth and slow.
        val s = DsFrameSkip(budget)
        val drawn = s.run(600, drawn = 52_000_000L, skipped = 40_000_000L)
        assertEquals(0, s.skip)
        assertTrue(drawn.takeLast(100).all { it })
    }

    @Test fun fewerSkipsOnlyOnceItHolds() {
        val s = DsFrameSkip(budget)
        s.run(300, drawn = 16_000_000L, skipped = 11_000_000L)
        assertEquals(1, s.skip)
        // The scene gets lighter: a few frames aren't enough to stop skipping...
        s.run(10, drawn = 9_000_000L, skipped = 6_000_000L)
        assertEquals(1, s.skip)
        // ...half a second of them is.
        s.run(200, drawn = 9_000_000L, skipped = 6_000_000L)
        assertEquals(0, s.skip)
    }

    @Test fun resetDrawsTheNextFrame() {
        val s = DsFrameSkip(budget)
        s.run(300, drawn = 20_000_000L, skipped = 12_000_000L)
        s.reset()
        assertEquals(0, s.skip)
        assertTrue(s.plan().first)
    }
}
