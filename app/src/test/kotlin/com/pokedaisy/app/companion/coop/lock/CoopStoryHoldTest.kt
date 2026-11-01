package com.pokedaisy.app.companion.coop.lock

import org.junit.Assert.*
import org.junit.Test

class CoopStoryHoldTest {
    private var now = 10_000L
    private val h = CoopStoryHold { now }
    @Test fun goOnlyWhenBothHoldTheSame() { h.onPartner(5); assertNull(h.step(4, true).go); assertEquals(5, h.step(5, true).go); assertNull(h.step(5, true).go) }
    @Test fun noGoWhileDisconnected() { h.onPartner(5); assertNull(h.step(5, false).go) }
    @Test fun staleHoldIsForgotten() { h.onPartner(5); now += 3001; assertNull(h.step(5, true).go) }
    @Test fun sendsOnChangeAndEverySecond() { assertEquals(5, h.step(5, true).send); assertNull(h.step(5, true).send); now += 1000
        assertEquals(5, h.step(5, true).send); assertEquals(0, h.step(0, true).send); now += 5000; assertNull(h.step(0, true).send) }
    @Test fun goAgainAfterTheHoldClearedAndCameBack() {
        h.onPartner(5); assertEquals(5, h.step(5, true).go); h.step(0, true); h.onPartner(5); assertEquals(5, h.step(5, true).go)
    }
}
