package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CeliaDecodeTest {
    @Test
    fun `party, bag and location decode from the real save`() {
        // Fixture is a HEADLESS capture (scripts/capture_fixture_headless.sh
        // celia) of the user's real save: species 3 at level 4, 18/18 HP,
        // moves SCRATCH (10) + GROWL (359 - this hack renumbers moves); 1
        // ETHER in the bag; Pallet Town. Species 3 is CHARMANDER in this
        // hack's custom dex - confirmed against the game's own party screen
        // (mgba_dump `shot`), NOT vanilla's Venusaur. See NATIVE_CELIA.
        val t = decodeNative("celia", NATIVE_CELIA)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals(3, mon.species)
        assertEquals("CHARMANDER", speciesNamesCelia[mon.species])
        assertEquals(4, mon.level)
        assertEquals(18, mon.hp)
        assertEquals(18, mon.maxHp)
        assertEquals(359, mon.moves[0])
        assertEquals(10, mon.moves[1])
        assertEquals("GROWL", moveDataCelia.getValue(359).name)
        assertEquals("SCRATCH", moveDataCelia.getValue(10).name)

        // Quantity only decodes to 1 with the relocated encryptionKey
        // (SaveBlock2+0xB18) - vanilla's +0xF20 reads 0 here and would give
        // the raw XORed value.
        assertEquals(1, t.items.size)
        val item = t.items[0]
        assertEquals(34, item.itemId)
        assertEquals("ETHER", itemNamesCelia[item.itemId])
        assertEquals(1, item.quantity)
        assertEquals(POCKET_ITEMS, item.pocket)
        assertPocketsInRange(t)

        assertEquals(3, t.mapGroup)
        assertEquals(0, t.mapNum)
        assertEquals(11, t.x)
        assertEquals(14, t.y)
        assertEquals(0x58, t.regionMapSectionId) // MAPSEC_PALLET_TOWN (vanilla Kanto id)
        assertEquals(false, t.inBattle)
    }
}
