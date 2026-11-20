package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DexHoldingsTest {
    private fun mon(species: Int, egg: Boolean = false) = MonView(species, "", 5, 1, 1, "", emptyList(), null, isEgg = egg)
    private fun box(vararg mons: MonView?) = BoxView("", 0, mons.toList())

    @Test fun `the party wins over a box, and the first box over a later one`() {
        val h = dexHoldings(listOf(mon(25)), BoxesView(0, listOf(box(null, mon(25), mon(1)), box(mon(1), mon(4)))))!!
        assertEquals(DEX_IN_PARTY, h[25])
        assertEquals(1, h[1])
        assertEquals(2, h[4])
        assertNull(h[7])
    }

    @Test fun `eggs don't count`() {
        assertNull(dexHoldings(listOf(mon(25, egg = true)), BoxesView(0, listOf(box(mon(1, egg = true)))))!![25])
    }

    @Test fun `no boxes read means nothing can be said`() {
        assertNull(dexHoldings(listOf(mon(25)), null))
    }
}
