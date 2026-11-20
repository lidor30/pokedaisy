package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The QUICK MENU's BIKE / ROD on the DS games: the Bag's register after its pockets ([gen4RegisterAddr]). */
class DsKeyItemsTest {
    @Test
    fun `HeartGold - the player's registered BICYCLE, right after the pockets`() {
        val r = FixtureMemoryReader.load("heartgold")
        val addr = gen4RegisterAddr(GEN4_HEARTGOLD_US) { a, n -> r.readCoreMemory(a, n) }!!
        val b = r.readCoreMemory(addr, 4)
        assertEquals(450, (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8))
    }
}
