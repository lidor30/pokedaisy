package com.pokedaisy.app.companion.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PerformanceCountersTest {
    @Test fun fastForwardCountsGameFramesAndDisplayedPicturesSeparately() {
        val before = PerformanceCounters(100_000_000L, 30, 15, 40_000_000L, 4, 6, 17)
        val after = PerformanceCounters(600_000_000L, 90, 45, 160_000_000L, 5, 14, 48)
        val reading = after.since(before, 3_500_000L)
        assertEquals(120f, reading.fps)
        assertEquals(60f, reading.shownFps)
        assertEquals(62f, reading.refreshHz)
        assertEquals(2f, reading.frameMs)
        assertEquals(3.5f, reading.frameMaxMs)
        assertEquals(24, reading.emulationLoad)
        assertEquals(2f, reading.snapshotsPerSecond)
        assertEquals(16f, reading.battleChecksPerSecond)
    }

    @Test fun aResetCounterCannotReportNegativeRates() {
        val before = PerformanceCounters(0L, 120, 60, 80_000_000L, 10, 16)
        val after = PerformanceCounters(500_000_000L, 0, 0, 0L, 0, 0)
        assertEquals(PerfReading(), after.since(before, 0))
        assertEquals(PerfReading(), before.since(before, 0))
    }
}
