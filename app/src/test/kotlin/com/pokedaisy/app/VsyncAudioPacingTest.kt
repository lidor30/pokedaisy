package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Vsync pacing's audio guard (GitHub #80: music hiccups at 1x in 1.2.0). */
class VsyncAudioPacingTest {
    private val buffer = 4096

    @Test fun lowRunsAtOnceHighWaitsElseTheTick() {
        assertEquals(VsyncPace.CATCH_UP, vsyncPace(0, buffer))
        assertEquals(VsyncPace.CATCH_UP, vsyncPace(buffer * 3 / 8 - 1, buffer))
        assertEquals(VsyncPace.ON_TICK, vsyncPace(buffer / 2, buffer))
        assertEquals(VsyncPace.HOLD, vsyncPace(buffer * 7 / 10 + 1, buffer))
        assertEquals(VsyncPace.ON_TICK, vsyncPace(0, 0))   // no audio track: plain ticks
    }

    /**
     * The audio's refresh period (#80's "tonal shifts"): GL-thread ticks up to 2 ms either side of a 60.03 Hz refresh,
     * some skipped. The quick average the audio used to follow swings; total time over refreshes lands on the screen's
     * period, and starts over when the screen goes to 120 Hz.
     */
    @Test fun refreshAverageCancelsTickJitterAndFollowsARateChange() {
        val rnd = Random(7)
        val period = 1e9 / 60.03
        val avg = RefreshAverage()
        var fast = 0.0
        var fastLow = Double.MAX_VALUE
        var fastHigh = 0.0
        var audio = 0.0
        var refresh = 0L
        var last = 0L
        fun tickAt(p: Double, skip: Boolean) {
            refresh += if (skip) 2 else 1
            val at = (refresh * p).toLong() + rnd.nextLong(-2_000_000L, 2_000_000L)
            val d = at - last
            last = at
            fast = if (fast == 0.0) d.toDouble() else fast + (d - fast) * 0.05
            audio = avg.tick(d, fast)
        }
        repeat(1800) { i ->
            tickAt(period, skip = i % 97 == 0)
            if (i > 300) { fastLow = minOf(fastLow, fast); fastHigh = maxOf(fastHigh, fast) }
        }
        assertTrue("quick average swings: ${fastHigh / fastLow}", fastHigh / fastLow > 1.005)
        assertEquals(1.0, audio / period, 0.0005)

        // The screen goes to 120 Hz: the average starts over and finds the new period.
        val half = period / 2
        val base = last
        refresh = 0
        val from = base
        repeat(1800) { i ->
            refresh += 1
            val at = from + (refresh * half).toLong() + rnd.nextLong(-1_000_000L, 1_000_000L)
            val d = at - last
            last = at
            fast = fast + (d - fast) * 0.05
            audio = avg.tick(d, fast)
        }
        assertEquals(1.0, audio / half, 0.0005)
    }

    @Test fun troubleNeedsSeveralUnderrunsCloseTogether() {
        val w = AudioTroubleWatch(max = 4, windowNanos = 10_000_000_000L)
        val s = 1_000_000_000L
        // Spread out: never trips.
        assertFalse(w.record(0))
        assertFalse(w.record(11 * s))
        assertFalse(w.record(22 * s))
        assertFalse(w.record(33 * s))
        // Four within ten seconds: trips.
        assertFalse(w.record(40 * s))
        assertFalse(w.record(42 * s))
        assertFalse(w.record(44 * s))
        assertTrue(w.record(46 * s))
    }

    /**
     * A 60 Hz screen whose refresh ticks sometimes go missing (the loop busy, or two refreshes close together
     * against a bank of one): the device plays a frame's worth of audio every refresh either way. With the guard,
     * a low buffer runs the next frame at once and the device never runs dry; ticks alone (1.2.0) drain it.
     */
    @Test fun lostRefreshesNoLongerRunTheBufferDry() {
        assertEquals(0, underruns(guard = true))
        assertTrue(underruns(guard = false) > 0)
    }

    private fun underruns(guard: Boolean): Int {
        val perFrame = 800        // audio frames a game frame makes (48 kHz / 60)
        val rng = Random(80)
        var queued = buffer / 2
        var dry = 0
        var catchUpRun = 0
        repeat(20_000) {
            // This refresh's frame, unless its tick went missing.
            if (rng.nextInt(20) != 0) queued = minOf(buffer, queued + perFrame)
            // The guard: run more frames at once while the buffer is low (at most two in a row).
            if (guard) {
                while (vsyncPace(queued, buffer) == VsyncPace.CATCH_UP && catchUpRun < 2) {
                    queued = minOf(buffer, queued + perFrame)
                    catchUpRun++
                }
                catchUpRun = 0
            }
            // The device plays a refresh's worth.
            queued -= perFrame
            if (queued < 0) {
                dry++
                queued = 0
            }
        }
        return dry
    }
}
