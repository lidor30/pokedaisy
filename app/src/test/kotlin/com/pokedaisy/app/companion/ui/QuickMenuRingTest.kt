package com.pokedaisy.app.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ring's tiles come out of the middle one by one, so closing, the same run backwards, takes the last one in first. */
class QuickMenuRingTest {
    @Test fun `every tile starts in the middle and ends in its place`() {
        for (n in listOf(1, 2, 5, 10)) for (i in 0 until n) {
            assertEquals(0f, tileOut(0f, i, n), 0f)
            assertEquals(1f, tileOut(1f, i, n), 0.0001f)
        }
    }

    @Test fun `each tile is behind the one before it, part way`() {
        for (i in 1 until 10) assertTrue("tile $i", tileOut(0.4f, i, 10) <= tileOut(0.4f, i - 1, 10))
        assertTrue(tileOut(0.3f, 0, 10) > 0f)
        assertEquals(0f, tileOut(0.3f, 9, 10), 0f)
    }

    @Test fun `no tile runs backwards`() {
        var last = 0f
        for (s in 0..100) {
            val v = tileOut(s / 100f, 4, 10)
            assertTrue(v >= last)
            last = v
        }
    }
}
