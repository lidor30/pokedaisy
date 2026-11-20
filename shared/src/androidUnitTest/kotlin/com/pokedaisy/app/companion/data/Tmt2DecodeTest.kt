package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class Tmt2DecodeTest {
    @Test
    fun `party, bag and location decode from the real save`() {
        // Fixture is a HEADLESS capture (scripts/capture_fixture_headless.sh
        // tmt2) of the user's real save: Chimchar (390 - stored packed with
        // teraType as 22918, see NATIVE_TMT2's speciesMask), Lv5, 20/20 HP,
        // Scratch + Leer; 7 bag items across 4 pockets; Lilycove City.
        val t = decodeNative("tmt2", NATIVE_TMT2)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals(390, mon.species)
        assertEquals("Chimchar", speciesNamesTmt2[mon.species])
        assertEquals(5, mon.level)
        assertEquals(20, mon.hp)
        assertEquals(20, mon.maxHp)
        assertEquals("Scratch", moveDataTmt2.getValue(mon.moves[0]).name)
        assertEquals("Leer", moveDataTmt2.getValue(mon.moves[1]).name)

        // Quantities only come out right with the relocated encryptionKey
        // (SaveBlock2+0x44); pockets from TMT2_BAG_POCKET_ORDER.
        val byId = t.items.associateBy { it.itemId }
        assertEquals(7, t.items.size)
        assertEquals(15, byId.getValue(1).quantity)                 // Poké Ball
        assertEquals(POCKET_POKE_BALLS, byId.getValue(1).pocket)
        assertEquals(POCKET_ITEMS, byId.getValue(116).pocket)       // Max Repel
        assertEquals(POCKET_BERRIES, byId.getValue(517).pocket)     // Rawst Berry
        assertEquals(POCKET_KEY_ITEMS, byId.getValue(844).pocket)   // Dexnav
        assertEquals("Dexnav", itemNamesTmt2[844])
        assertEquals(1, byId.getValue(844).quantity)
        assertPocketsInRange(t)

        assertEquals(0, t.mapGroup)
        assertEquals(5, t.mapNum)
        assertEquals(32, t.x)
        assertEquals(10, t.y)
        assertEquals(0x0C, t.regionMapSectionId) // MAPSEC_LILYCOVE_CITY
        assertEquals(false, t.inBattle)
    }
}
