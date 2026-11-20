package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NATIVE_GLAZED: retail Emerald's RAM with Glazed's own tables (gen_glazed_tables.py). */
class GlazedDecodeTest {
    @Test
    fun `party, bag and location decode from the real save`() {
        // A headless capture of the user's save (rhh_splash.txt): CHIMCHAR (species 298, retail's
        // SEEDOT slot) Lv6, 20/22 HP, SCRATCH / LEER / EMBER, in Forest Pass's tall grass.
        activeGame = GameKind.GLAZED
        val t = decodeNative("glazed", NATIVE_GLAZED)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals("CHIMCHAR", speciesNamesGlazed[mon.species])
        assertEquals(6 to 20, mon.level to mon.hp)
        assertEquals(22, mon.maxHp)
        assertEquals(listOf("SCRATCH", "LEER", "EMBER"), mon.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals("Fire", typeName(lookupMove(52).type))

        // Its Items pocket: an EXP. SHARE and a POTION.
        assertEquals(listOf("EXP. SHARE" to 1, "POTION" to 1), t.items.map { itemNamesGlazed[it.itemId] to it.quantity })
        assertEquals(POCKET_ITEMS, t.items[0].pocket)
        assertPocketsInRange(t)

        assertEquals(0x11, t.regionMapSectionId)
        assertEquals("Forest Pass", mapSecDataGlazed.getValue(t.regionMapSectionId).name)
        assertFalse(t.inBattle)
    }

    @Test
    fun `Fairy is type 9`() {
        activeGame = GameKind.GLAZED
        assertEquals(SpeciesTypes(9, 9), activeSpeciesTypeData[35]) // CLEFAIRY
        assertEquals("Fairy", typeName(9))
        assertEquals(0, singleTypeMultiplierPct(16, 9)) // Dragon vs Fairy
        assertEquals(200, singleTypeMultiplierPct(9, 16))
    }
}
