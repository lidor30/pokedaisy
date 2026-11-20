package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LazarusDecodeTest {
    @Before fun setGame() { activeGame = GameKind.LAZARUS }
    @After fun resetGame() { activeGame = GameKind.FIRERED }

    @Test
    fun `party, bag and location decode from the real save`() {
        // HEADLESS capture (scripts/capture_fixture_headless.sh lazarus
        // --script native-capture/boot/rhh_splash.txt) of the user's save:
        // Fennekin (National Dex 653) Lv5 19/19 HP, 1 Potion, indoors in
        // Acrisia City. See NATIVE_LAZARUS for how each address was found.
        val t = decodeNative("lazarus", NATIVE_LAZARUS)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals(653, mon.species)
        assertEquals("Fennekin", speciesNamesLazarus[mon.species])
        assertEquals(5, mon.level)
        assertEquals(19, mon.hp)
        assertEquals(19, mon.maxHp)
        assertEquals("Scratch", moveDataLazarus.getValue(mon.moves[0]).name)
        assertEquals("Ember", moveDataLazarus.getValue(mon.moves[2]).name)
        assertEquals(SpeciesTypes(11, 11), speciesTypeDataLazarus[mon.species]) // Fire

        // Quantity only comes out right with the key at SaveBlock2+0xB0.
        assertEquals(1, t.items.size)
        val item = t.items[0]
        assertEquals(28, item.itemId)
        assertEquals("Potion", itemNamesLazarus[item.itemId])
        assertEquals(1, item.quantity)
        assertEquals(POCKET_ITEMS, item.pocket)
        assertPocketsInRange(t)

        assertEquals(0, t.mapGroup)
        assertEquals(58, t.mapNum)
        assertEquals(9, t.x)
        assertEquals(9, t.y)
        assertEquals(0x5A, t.regionMapSectionId)
        assertEquals("Acrisia City", mapSecDataLazarus.getValue(t.regionMapSectionId).name)
        assertEquals(false, t.inBattle)
    }
}
