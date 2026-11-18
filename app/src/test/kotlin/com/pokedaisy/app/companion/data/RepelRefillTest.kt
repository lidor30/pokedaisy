package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AUTO REFILL's choices ([RepelRefill]): which kind was working, and which goes on next. The calls themselves were played
 * headless on FireRed rev 1 and Emerald (3 steps left, one more on top: 100 left three steps later, no "wore off"). */
class RepelRefillTest {
    private val kinds = listOf(86 to 100, 83 to 200, 84 to 250)

    @Test
    fun kindFromTheFirstCount() {
        assertEquals(86, RepelRefill.kindOf(kinds, 100))
        assertEquals(86, RepelRefill.kindOf(kinds, 97))
        assertEquals(83, RepelRefill.kindOf(kinds, 197))
        assertEquals(84, RepelRefill.kindOf(kinds, 250))
        assertNull(RepelRefill.kindOf(kinds, 300))
    }

    @Test
    fun theSameKindFirstElseTheSmallest() {
        val bag = setOf(86, 84)
        assertEquals(84 to 250, RepelRefill.pick(kinds, { it in bag }, running = 84))
        // The working kind's gone: the smallest the bag has, never a bigger one first.
        assertEquals(86 to 100, RepelRefill.pick(kinds, { it in bag }, running = 83))
        assertEquals(86 to 100, RepelRefill.pick(kinds, { it in bag }, running = null))
        assertEquals(84 to 250, RepelRefill.pick(kinds, { it == 84 }, running = 86))
        assertNull(RepelRefill.pick(kinds, { false }, running = 86))
    }
}
