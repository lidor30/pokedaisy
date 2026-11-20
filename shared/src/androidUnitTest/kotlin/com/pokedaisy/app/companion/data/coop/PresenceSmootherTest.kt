package com.pokedaisy.app.companion.data.coop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The partner's movement ([PresenceSmoother]): buffered ~150 ms behind the sender's clock, lerped, never backwards. */
class PresenceSmootherTest {
    private fun upd(
        t: Long, x: Int, y: Int = 10 * 16, group: Int = 0, num: Int = 16,
        facing: Int = 4, mode: Int = PresenceMode.WALK, step: Int = 0, valid: Boolean = true,
    ) = PresenceUpdate(t, valid, false, group, num, x, y, facing, mode, step, 0)

    private fun PresenceSmoother.x(now: Long): Int = (at(now, 0, 16) ?: throw AssertionError("hidden at $now")).fineX

    @Test fun interpolatesBetweenUpdates() {
        val s = PresenceSmoother()
        s.push(upd(0, 160), 40)
        s.push(upd(100, 176), 140)
        // offset 40, so now 240 renders the sender's t = 240 - 40 - 150 = 50: half way.
        assertEquals(168, s.at(40 + 150 + 50, 0, 16)!!.fineX)
    }

    @Test fun senderAtFourTimesSpeedKeepsWallClockPace() {
        // At 4x the sender steps a tile every 66 ms of its own wall clock (16 frames / 4); updates arrive 40-80 ms late.
        val updates = (0..30).map { i -> upd(66L * i, 160 + 16 * i) to (66L * i + 40 + (i % 3) * 20) }
        val s = PresenceSmoother()
        var next = 0
        var last = -1
        var now = 300L
        while (now <= 1300) {
            while (next < updates.size && updates[next].second <= now) {
                s.push(updates[next].first, updates[next].second); next++
            }
            val x = s.x(now)
            val t = now - 40 - 150
            val expected = 160 + 16 * t / 66
            assertTrue("now $now: x $x vs wall-clock $expected", abs(x - expected) <= 32)
            if (last >= 0) assertTrue("now $now: $last -> $x in 16 ms", x - last in 0..4)
            last = x
            now += 16
        }
    }

    @Test fun lateAndDuplicateUpdatesNeverStepBack() {
        val s = PresenceSmoother()
        s.push(upd(0, 160), 40)
        s.push(upd(200, 192), 240)
        s.push(upd(100, 100), 250)   // late: older than the newest, dropped
        s.push(upd(200, 150), 260)   // duplicate time, dropped
        var last = -1
        var now = 190L
        while (now <= 1000) {
            val x = s.x(now)
            assertTrue("now $now: $last -> $x", x >= last)
            last = x
            now += 10
        }
        assertEquals(192, last)
    }

    @Test fun burstAfterStallCatchesUpWithoutAnyJump() {
        val s = PresenceSmoother()
        // A tile per 100 ms, steady until t = 1000 (arrived 1040)...
        for (i in 0..10) s.push(upd(100L * i, 160 + 16 * i), 100L * i + 40)
        val final = 160 + 16 * 30
        var last = -1
        var reachedAt = -1L
        var now = 1040L
        while (now <= 5000) {
            // ...then nothing for 2 s, and t = 1100..3000 all arrive in the same ms.
            if (now == 3040L) for (i in 11..30) s.push(upd(100L * i, 160 + 16 * i), 3040)
            val x = s.x(now)
            if (last >= 0) {
                val d = x - last
                assertTrue("now $now: stepped back $last -> $x", d >= 0)
                assertTrue("now $now: a jump of $d px in one frame", d <= 16)
            }
            if (x == final && reachedAt < 0) reachedAt = now
            last = x
            now += 16
        }
        assertEquals(final, last)
        assertTrue("reached the newest place at $reachedAt", reachedAt in 0..4100)
    }

    @Test fun senderAtQuarterSpeedKeepsWallClockPace() {
        // 0.25x: a tile per 1067 ms of the sender's wall clock; an update every 100 ms.
        val s = PresenceSmoother()
        var next = 0
        var last = -1
        var now = 300L
        val xs = HashMap<Long, Int>()
        while (now <= 4000) {
            while (next * 100L + 40 <= now) {
                val t = 100L * next
                s.push(upd(t, 160 + (16 * t / 1067).toInt()), t + 40); next++
            }
            val x = s.x(now)
            val expected = 160 + 16 * (now - 40 - 150) / 1067.0
            assertTrue("now $now: x $x vs wall-clock $expected", abs(x - expected) <= 2.5)
            assertTrue("now $now: $last -> $x", x >= last)
            xs[now] = x
            last = x
            now += 16
        }
        // a tile (16 px) per 1067 ms
        assertEquals(16.0, (xs.getValue(3036) - xs.getValue(1964)).toDouble(), 3.0)
    }

    @Test fun renderTimeNeverGoesBackwardsWhenTheLowOffsetLeavesTheWindow() {
        val s = PresenceSmoother()
        s.push(upd(0, 160), 0)   // offset 0, the least delayed; every later one is 100
        var next = 1
        var last = -1
        var now = 0L
        while (now <= 8000) {
            // 8 px per 100 ms; once 64 later samples are in, the offset 0 one has left the window and T would drop 100 ms.
            while (next <= 75 && next * 100L + 100 <= now) {
                s.push(upd(100L * next, 160 + 8 * next), 100L * next + 100); next++
            }
            val p = s.at(now, 0, 16)
            if (p != null) {
                assertTrue("now $now: $last -> ${p.fineX}", p.fineX >= last)
                last = p.fineX
            }
            now += 16
        }
        assertEquals(160 + 8 * 75, last)
    }

    @Test fun hiddenWhenStale() {
        val s = PresenceSmoother()
        s.push(upd(0, 160), 1000)
        assertNull(s.at(1000 + 3001, 0, 16))
        assertNotNull(s.at(1000 + 2999, 0, 16))
    }

    @Test fun hiddenOnOtherMapAndSnapsOnReturn() {
        val s = PresenceSmoother()
        s.push(upd(0, 160), 40)
        s.push(upd(100, 400, num = 17), 140)
        assertNull(s.at(290, 0, 16))                 // T 100: on (0,17)
        s.push(upd(200, 480), 240)                   // back on (0,16), somewhere else
        assertNull(s.at(340, 0, 16))                 // T 150: still the (0,17) one shown
        assertNull(s.at(370, 0, 16))                 // T 180: no lerp from the old map
        assertEquals(480, s.at(390, 0, 16)!!.fineX)  // T 200: there at once
        assertEquals(480, s.at(500, 0, 16)!!.fineX)
    }

    @Test fun hiddenWhenNotValid() {
        val s = PresenceSmoother()
        s.push(upd(0, 160, valid = false), 40)
        assertNull(s.at(300, 0, 16))
    }

    @Test fun aSenderWhoseClockRestartedIsShownAgain() {
        val s = PresenceSmoother()
        s.push(upd(1_000_000, 160), 1000)
        // 9 s later its clock starts over: an "older" time, accepted because the old one went stale.
        s.push(upd(500, 300), 10_000)
        assertEquals(300, s.at(10_200, 0, 16)!!.fineX)
    }

    @Test fun placementFramesFromTheUpdate() {
        val s = PresenceSmoother()
        s.push(upd(0, 160, facing = 4, step = 3), 40)
        s.push(upd(100, 176, facing = 4, step = 3), 140)
        // T 50: fineX 168, 8 into its tile going east = first half -> stepping, foot 1.
        val p = s.at(240, 0, 16)!!
        assertEquals(3 + 2 * 2 + 1, p.frame)
        assertTrue(p.hflip)
        assertEquals(-1, p.blobFrame)
        assertEquals(0, p.bob)
        // T 60: fineX 169 -> second half, standing frame.
        assertEquals(2, s.at(250, 0, 16)!!.frame)
    }

    @Test fun surfingBobsAndShowsTheBlob() {
        val s = PresenceSmoother()
        s.push(upd(0, 160, facing = 1, mode = PresenceMode.SURF), 40)
        val p = s.at(266, 0, 16)!!
        assertEquals(36, p.frame)
        assertEquals(39, p.blobFrame)
        assertEquals(1, p.bob)
        assertEquals(2, s.at(532, 0, 16)!!.bob)
    }

    @Test fun frameIndices() {
        assertEquals(2, PartnerFrames.index(PresenceMode.WALK, 4, false, 0))
        assertEquals(9 + 3 + 2 * 2 + 1, PartnerFrames.index(PresenceMode.RUN, 3, true, 1))
        assertEquals(37, PartnerFrames.index(PresenceMode.SURF, 2, true, 0))
        assertEquals(41, PartnerFrames.blob(PresenceMode.SURF, 4))
        assertEquals(-1, PartnerFrames.blob(PresenceMode.WALK, 1))
    }
}
