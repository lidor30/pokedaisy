package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** NATIVE_IMPERIUM on the field (a headless capture of the user's save, rhh_splash.txt). */
class ImperiumDecodeTest {
    @Test
    fun `party, bag and location decode from the real save`() {
        activeGame = GameKind.IMPERIUM
        val t = decodeNative("imperium", NATIVE_IMPERIUM)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals("Charmander", speciesNamesImperium[mon.species])
        assertEquals(5 to 20, mon.level to mon.hp)
        assertEquals(20, mon.maxHp)
        assertEquals(listOf("Scratch", "Growl", "False Swipe"), mon.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals(listOf(35, 40, 40), mon.pp.take(3))
        // 1.10 packs the nickname's 11th character above 21 bits of experience.
        assertEquals(135L, mon.exp)

        assertEquals(listOf("Potion" to 1), t.items.map { itemNamesImperium[it.itemId] to it.quantity })
        assertEquals(POCKET_ITEMS, t.items[0].pocket)
        assertPocketsInRange(t)

        assertEquals(0x10, t.regionMapSectionId)
        assertEquals("Route 101", mapSecDataImperium.getValue(t.regionMapSectionId).name)
        assertFalse(t.inBattle)
    }
}
