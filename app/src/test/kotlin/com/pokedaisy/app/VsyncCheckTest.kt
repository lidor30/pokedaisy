package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** GitHub #350: the RG DS's GL thread ticks ~400 times a second on a 60 Hz screen; the Thor / RP6's follow the refresh. */
class VsyncCheckTest {
    /** Ticks [seconds] long, each [interval] after the last (a lambda, so a sequence can jitter / skip / change rate). */
    private fun run(check: VsyncCheck, displayHz: Float, seconds: Double, start: Long = 1L, interval: (Long) -> Long): Long {
        var t = start
        val end = start + (seconds * 1e9).toLong()
        while (t < end) {
            check.tick(t, { displayHz })
            t += interval(t).coerceAtLeast(1L)
        }
        return t
    }

    /** The Thor / RP6: ticks up to 2 ms either side of a 60 Hz refresh, one in 50 a skipped refresh, two close
     * together now and then (a free buffer: the swap returned at once). Minutes of it never strike. */
    @Test fun steadySixtyWithJitterAndSkipsIsReliable() {
        val rnd = Random(1)
        val check = VsyncCheck()
        val period = 1e9 / 60.0
        run(check, 60f, 300.0) {
            when (rnd.nextInt(50)) {
                0 -> (2 * period).toLong()
                1 -> 2_000_000L
                else -> (period + rnd.nextLong(-2_000_000L, 2_000_000L)).toLong()
            }
        }
        assertFalse(check.reason, check.unreliable)
        assertEquals(60.0, check.lastRate, 3.0)
    }

    @Test fun steadyOneTwentyIsReliable() {
        val rnd = Random(2)
        val check = VsyncCheck()
        run(check, 120f, 120.0) { (1e9 / 120.0 + rnd.nextLong(-1_000_000L, 1_000_000L)).toLong() }
        assertFalse(check.reason, check.unreliable)
    }

    /** setFrameRate(60) on a 120 Hz screen: 120 for a while, then 60, then back - a rate change, not a bogus clock. */
    @Test fun aScreenSwitchingBetweenSixtyAndOneTwentyIsReliable() {
        val check = VsyncCheck()
        var t = 1L
        repeat(5) {
            t = run(check, 120f, 7.3, t) { (1e9 / 120.0).toLong() }
            t = run(check, 120f, 9.1, t) { (1e9 / 60.0).toLong() }
        }
        assertFalse(check.reason, check.unreliable)
    }

    /** A DS game at a high resolution: the GL thread can't keep up, 35-60 ticks a second, never above the screen. */
    @Test fun aGlThreadTooBusyToKeepUpIsReliable() {
        val rnd = Random(3)
        val check = VsyncCheck()
        val period = 1e9 / 60.0
        run(check, 60f, 120.0) { (period * (1 + rnd.nextInt(3)) * (0.9 + rnd.nextDouble() * 0.2)).toLong() }
        assertFalse(check.reason, check.unreliable)
    }

    /** A pause (the view gone, the screen off) isn't a window, and the ticks after it don't jump from the ones before. */
    @Test fun aPauseIsNotCounted() {
        val check = VsyncCheck()
        var t = run(check, 60f, 5.0) { (1e9 / 60.0).toLong() }
        t += 30_000_000_000L
        run(check, 60f, 5.0, t) { (1e9 / 60.0).toLong() }
        assertFalse(check.reason, check.unreliable)
    }

    /**
     * The RG DS (the reporter's fps lines on 1.4.0: repeats ~690 per 120 frames, so ~400 draws a second; the 4-34 ms
     * intervals the refresh estimate kept made it 70-205 Hz): intervals of 0.5-14 ms on a 60 Hz screen. Caught within
     * a few seconds.
     */
    @Test fun rgDsJitteryTicksAreUnreliable() {
        val rnd = Random(4)
        val check = VsyncCheck()
        var caughtAt = -1L
        var t = 1L
        while (t < 10_000_000_000L && caughtAt < 0) {
            if (check.tick(t, { 60f })) caughtAt = t
            t += if (rnd.nextInt(10) == 0) rnd.nextLong(4_000_000L, 14_000_000L) else rnd.nextLong(500_000L, 3_000_000L)
        }
        assertTrue("never caught", caughtAt > 0)
        assertTrue("caught at $caughtAt ns", caughtAt < 3_500_000_000L)
        assertTrue(check.reason!!, check.reason!!.contains("60 Hz"))
    }

    /** The same device reporting no display modes: still far past anything a handheld's screen does. */
    @Test fun rgDsWithAnUnknownScreenIsUnreliable() {
        val rnd = Random(5)
        val check = VsyncCheck()
        run(check, 0f, 5.0) { rnd.nextLong(500_000L, 4_500_000L) }
        assertTrue(check.unreliable)
    }

    /** Ticks that sometimes wait and sometimes don't: 95 Hz one second, 172 the next (the reporter's two sessions'
     * medians), never steady - over the 60 Hz screen and jumping. */
    @Test fun aRateThatNeverSettlesIsUnreliable() {
        val check = VsyncCheck()
        var t = 1L
        repeat(4) {
            t = run(check, 0f, 1.05, t) { (1e9 / 95.0).toLong() }
            t = run(check, 0f, 1.05, t) { (1e9 / 172.0).toLong() }
        }
        assertTrue(check.unreliable)
        val known = VsyncCheck()
        t = 1L
        repeat(4) {
            t = run(known, 60f, 1.05, t) { (1e9 / 70.0).toLong() }
            t = run(known, 60f, 1.05, t) { (1e9 / 95.0).toLong() }
        }
        assertTrue(known.unreliable)   // 70 / 95 on a 60 Hz screen: neither is 1.25x over, but it jumps past 1.1x
    }

    /** A screen that runs steadily faster than the modes it reports (120 Hz on a "60 Hz" display): its ticks still come
     * with its refresh, which vsync pacing measures, so it never strikes. */
    @Test fun aSteadyScreenFasterThanItsModesIsReliable() {
        val rnd = Random(6)
        val check = VsyncCheck()
        run(check, 60f, 120.0) { (1e9 / 120.0 + rnd.nextLong(-1_000_000L, 1_000_000L)).toLong() }
        assertFalse(check.reason, check.unreliable)
    }

    /** A false strike isn't for good (1.5.0 kept it for the process: a smooth device stayed on the timer, choppy at 60
     * fps): ten clean seconds and it's reliable again. */
    @Test fun itRecoversOnceTheTicksFollowTheRefresh() {
        val check = VsyncCheck()
        var t = run(check, 60f, 4.0) { 2_500_000L }
        assertTrue(check.unreliable)
        t = run(check, 60f, 5.0, t) { (1e9 / 60.0).toLong() }
        assertTrue("not before ten clean seconds", check.unreliable)
        run(check, 60f, 9.0, t) { (1e9 / 60.0).toLong() }   // the first windows after the change still strike
        assertFalse(check.unreliable)
        assertEquals(null, check.reason)
        assertEquals(VsyncCheck.RECOVER_WINDOWS * 2, check.recoverWindows)
    }

    /** A device that strikes now and then waits twice as long each time before vsync paces again. */
    @Test fun eachRecoveryTakesTwiceAsLong() {
        val check = VsyncCheck()
        var t = 1L
        var waits = VsyncCheck.RECOVER_WINDOWS
        repeat(3) {
            t = run(check, 60f, 4.0, t) { 2_500_000L }
            assertTrue(check.unreliable)
            t = run(check, 60f, waits - 4.0, t) { (1e9 / 60.0).toLong() }
            assertTrue(check.unreliable)
            t = run(check, 60f, 8.0, t) { (1e9 / 60.0).toLong() }
            assertFalse(check.unreliable)
            waits *= 2
            assertEquals(waits, check.recoverWindows)
        }
    }

    /** The RG DS never recovers: its ticks keep outrunning the screen. */
    @Test fun theRgDsStaysUnreliable() {
        val rnd = Random(7)
        val check = VsyncCheck()
        run(check, 60f, 120.0) { if (rnd.nextInt(10) == 0) rnd.nextLong(4_000_000L, 14_000_000L) else rnd.nextLong(500_000L, 3_000_000L) }
        assertTrue(check.unreliable)
    }
}
