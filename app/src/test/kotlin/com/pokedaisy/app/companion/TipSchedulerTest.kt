package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.TipScheduler.Companion.AGAIN_AFTER
import com.pokedaisy.app.companion.TipScheduler.Companion.CALM_MS
import com.pokedaisy.app.companion.TipScheduler.Companion.EARLY_SETTLE
import com.pokedaisy.app.companion.TipScheduler.Companion.FIRST_TIP_AFTER
import com.pokedaisy.app.companion.TipScheduler.Companion.GAP
import com.pokedaisy.app.companion.TipScheduler.Companion.MINUTE
import com.pokedaisy.app.companion.TipScheduler.Companion.MOMENT_TTL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TipSchedulerTest {
    private class Store : TipStore {
        override var fieldMillis = 0L
        override var lastClosedAt = -1L
        override var records: Map<TipId, TipRecord> = emptyMap()
    }

    private fun scheduler(store: Store = Store(), available: (TipId) -> Boolean = { true }) = TipScheduler(store, available)

    /** [ms] of calm field play, a second at a time. */
    private fun TipScheduler.play(ms: Long, calm: Boolean = true) {
        var left = ms
        while (left > 0) {
            val dt = minOf(1000L, left)
            tick(dt, onField = true, calm = calm)
            left -= dt
        }
    }

    @Test fun nothingBeforeHalfAnHour() {
        val s = scheduler()
        s.play(FIRST_TIP_AFTER - 1000)
        assertNull(s.current)
        s.play(2000)
        assertEquals(TipId.ZOOM, s.current)
    }

    @Test fun onlyFieldTimeCounts() {
        val s = scheduler()
        repeat(40 * 60) { s.tick(1000, onField = false, calm = false) }
        assertEquals(0L, s.playedMillis)
        assertNull(s.current)
    }

    @Test fun waitsForACalmStretch() {
        val s = scheduler()
        s.play(FIRST_TIP_AFTER + MINUTE, calm = false)
        assertNull(s.current)
        s.play(CALM_MS - 1000)
        assertNull(s.current)
        s.play(1000)
        assertEquals(TipId.ZOOM, s.current)
    }

    @Test fun fiveMinutesBetweenTips() {
        val s = scheduler()
        s.play(FIRST_TIP_AFTER + CALM_MS)
        assertEquals(TipId.ZOOM, s.current)
        s.answered(TipId.ZOOM)
        s.play(GAP - 1000)
        assertNull(s.current)
        s.play(CALM_MS)
        assertEquals(TipId.EXPAND, s.current)
    }

    @Test fun theGapIsKeptAcrossLaunches() {
        val store = Store()
        val s = scheduler(store)
        s.play(FIRST_TIP_AFTER + CALM_MS)
        s.answered(s.current!!)
        // A new launch a minute of play later: its settle minute passes, the gap still holds.
        val again = scheduler(store)
        again.play(2 * MINUTE)
        assertNull(again.current)
        again.play(GAP)
        assertEquals(TipId.EXPAND, again.current)
    }

    @Test fun aMomentGoesFirst() {
        val s = scheduler()
        s.play(FIRST_TIP_AFTER - MINUTE)
        s.moment(TipId.WILD)
        s.play(MINUTE + CALM_MS)
        assertEquals(TipId.WILD, s.current)
    }

    @Test fun anEarlyTipNeedsNoHalfHour() {
        val s = scheduler(available = { it == TipId.DS_TRIGGERS })
        s.moment(TipId.DS_TRIGGERS)
        s.play(EARLY_SETTLE - 1000)
        assertNull(s.current)
        s.play(CALM_MS)
        assertEquals(TipId.DS_TRIGGERS, s.current)
        // Answered: never again, its moment or not.
        s.answered(TipId.DS_TRIGGERS)
        s.moment(TipId.DS_TRIGGERS)
        s.play(MINUTE)
        assertNull(s.current)
    }

    @Test fun anEarlyTipWaitsForTheField() {
        val s = scheduler(available = { it == TipId.DS_TRIGGERS })
        s.moment(TipId.DS_TRIGGERS)
        s.play(MINUTE, calm = false)
        assertNull(s.current)
        s.play(CALM_MS)
        assertEquals(TipId.DS_TRIGGERS, s.current)
    }

    @Test fun momentsGoStale() {
        val s = scheduler()
        s.play(FIRST_TIP_AFTER, calm = false)
        s.moment(TipId.AUTO_RUN)
        s.play(MOMENT_TTL + MINUTE, calm = false)
        s.play(CALM_MS)
        assertEquals(TipId.ZOOM, s.current)
    }

    @Test fun momentOnlyTipsNeedTheirMoment() {
        val s = scheduler(available = { !it.generic })
        s.play(FIRST_TIP_AFTER + 10 * MINUTE)
        assertNull(s.current)
        s.moment(TipId.FLY)
        s.play(CALM_MS)
        assertEquals(TipId.FLY, s.current)
    }

    @Test fun unavailableTipsAreSkipped() {
        val s = scheduler(available = { it != TipId.ZOOM && it != TipId.EXPAND })
        s.play(FIRST_TIP_AFTER + CALM_MS)
        assertEquals(TipId.QUICK_MENU, s.current)
    }

    @Test fun usedFeaturesNeverGetATip() {
        val s = scheduler()
        s.used(TipId.ZOOM)
        s.play(FIRST_TIP_AFTER + CALM_MS)
        assertEquals(TipId.EXPAND, s.current)
        // Found while its card is up: the card goes.
        s.used(TipId.EXPAND)
        assertNull(s.current)
    }

    @Test fun anIgnoredTipComesBackOnceMuchLater() {
        val s = scheduler(available = { it == TipId.ZOOM })
        s.play(FIRST_TIP_AFTER + CALM_MS)
        s.timedOut(TipId.ZOOM)
        s.play(AGAIN_AFTER - MINUTE)
        assertNull(s.current)
        s.play(2 * MINUTE)
        assertEquals(TipId.ZOOM, s.current)
        s.timedOut(TipId.ZOOM)
        s.play(3 * AGAIN_AFTER)
        assertNull(s.current)
    }

    @Test fun switchedOff() {
        val s = scheduler()
        s.play(FIRST_TIP_AFTER + CALM_MS)
        assertEquals(TipId.ZOOM, s.current)
        s.enabled = false
        assertNull(s.current)
        s.play(10 * GAP)
        assertNull(s.current)
    }
}
