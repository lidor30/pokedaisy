package com.pokedaisy.app.companion.coop

import org.junit.Assert.assertTrue
import org.junit.Test

class CoopBackoffTest {
    @Test fun doublesToCapWithJitter() {
        val b = CoopBackoff(java.util.Random(1))
        val d = List(10) { b.next() }
        assertTrue(d[0] in 800..1_200)
        assertTrue(d[1] in 1_600..2_400)
        assertTrue(d[2] in 3_200..4_800)
        assertTrue(d.last() in 48_000..72_000)
    }

    @Test fun resetStartsOver() {
        val b = CoopBackoff(java.util.Random(2))
        repeat(5) { b.next() }
        b.reset()
        assertTrue(b.next() in 800..1_200)
    }
}
