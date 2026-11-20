package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EmeraldSeaglassDecodeTest {
    @Test
    fun `party, bag and location decode from the real save`() {
        // Fixture is a HEADLESS capture (scripts/capture_fixture_headless.sh
        // emerald_seaglass) of the user's real save: 1 Torchic (National Dex
        // #255, not the internal Gen3 index 280), level 5, full HP, knowing
        // Scratch/Growl; a Shiny Charm in the key-items pocket; standing in
        // Birch's Lab. See NativeReader.kt's NATIVE_EMERALD_SEAGLASS comment
        // for how each address was found and cross-checked.
        val t = decodeNative("emerald_seaglass", NATIVE_EMERALD_SEAGLASS)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals(255, mon.species)
        assertEquals(5, mon.level)
        assertEquals(19, mon.hp)
        assertEquals(19, mon.maxHp)
        assertEquals(10, mon.moves[0]) // Scratch
        assertEquals(45, mon.moves[1]) // Growl

        assertEquals(1, t.items.size)
        val item = t.items[0]
        assertEquals(691, item.itemId)
        assertEquals("Shiny Charm", itemNamesSeaglass[item.itemId])
        assertEquals(1, item.quantity)
        assertEquals(POCKET_KEY_ITEMS, item.pocket)
        assertPocketsInRange(t)

        assertEquals(1, t.mapGroup)
        assertEquals(4, t.mapNum)
        assertEquals(6, t.x)
        assertEquals(5, t.y)
        assertEquals(0x00, t.regionMapSectionId) // MAPSEC_LITTLEROOT_TOWN
        assertEquals(false, t.inBattle)
    }
}
