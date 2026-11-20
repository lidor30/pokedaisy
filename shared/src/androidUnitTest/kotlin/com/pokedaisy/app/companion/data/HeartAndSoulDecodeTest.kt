package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HeartAndSoulDecodeTest {
    @Before fun setGame() { activeGame = GameKind.HEART_AND_SOUL }
    @After fun resetGame() { activeGame = GameKind.FIRERED }

    @Test
    fun `party, bag and location decode from the real save`() {
        // HEADLESS capture (scripts/capture_fixture_headless.sh heart_and_soul
        // --script native-capture/boot/rhh_splash.txt) of the user's save:
        // CYNDAQUIL (155, stored packed as 22683) Lv5 16/19 HP, Tackle + Leer;
        // 2 Potions + EXP. SHARE + GB PLAYER; New Bark Town.
        val t = decodeNative("heart_and_soul", NATIVE_HEART_AND_SOUL)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals(155, mon.species)
        assertEquals("CYNDAQUIL", speciesNamesHns[mon.species])
        assertEquals(5, mon.level)
        assertEquals(16, mon.hp)
        assertEquals(19, mon.maxHp)
        assertEquals("TACKLE", moveDataHns.getValue(mon.moves[0]).name)
        assertEquals("LEER", moveDataHns.getValue(mon.moves[1]).name)
        assertEquals(33, mon.pp[0])
        assertEquals(SpeciesTypes(11, 11), speciesTypeDataHns[mon.species]) // Fire

        val byId = t.items.associateBy { it.itemId }
        assertEquals(3, t.items.size)
        assertEquals("POTION", itemNamesHns[28])
        assertEquals(2, byId.getValue(28).quantity)
        assertEquals(POCKET_ITEMS, byId.getValue(28).pocket)          // Medicine pocket
        assertEquals(POCKET_KEY_ITEMS, byId.getValue(461).pocket)     // EXP. SHARE
        assertEquals(POCKET_KEY_ITEMS, byId.getValue(874).pocket)     // GB PLAYER
        assertPocketsInRange(t)

        // SaveBlock1.pos is at +4 in this hack (saveBlock1PosOff).
        assertEquals(0, t.mapGroup)
        assertEquals(0, t.mapNum)
        assertEquals(15, t.x)
        assertEquals(12, t.y)
        assertEquals(0x3F, t.regionMapSectionId)
        assertEquals("New Bark Town", mapSecDataHns.getValue(t.regionMapSectionId).name)
        assertEquals(false, t.inBattle)
    }
}

class HeartAndSoulBattleDecodeTest {
    @Test
    fun `wild battle decodes with the expansion BattlePokemon layout`() {
        // Wild HOOTHOOT Lv2 on Route 29 vs the user's CYNDAQUIL; see the
        // fixture's README.txt. BattlePokemon is 0x88 bytes here, not 0x58.
        val t = decodeNative("heart_and_soul_battle", NATIVE_HEART_AND_SOUL)
        assertEquals(true, t.inBattle)
        assertEquals(false, t.isDoubleBattle)

        val player = t.battleMons[0]
        assertEquals(155, player.species)
        assertEquals(5, player.level)
        assertEquals(11, player.type1)        // Fire
        assertEquals(11, player.type2)
        assertEquals(19, player.maxHp)
        assertEquals(listOf(33, 43, 0, 0), player.moves.toList())  // Tackle, Leer
        assertEquals(listOf(33, 30, 0, 0), player.pp.toList())

        val foe = t.battleMons[1]
        assertEquals(163, foe.species)
        assertEquals("HOOTHOOT", speciesNamesHns[foe.species])
        assertEquals(2, foe.level)
        assertEquals(1, foe.type1)            // Normal
        assertEquals(3, foe.type2)            // Flying
        assertEquals(14, foe.hp)
        assertEquals(14, foe.maxHp)
        assertEquals(0L, foe.status1)
        assertEquals("FORESIGHT", moveDataHns.getValue(foe.moves[2]).name)
        assertEquals(listOf(35, 40, 40, 0), foe.pp.toList())
        assertEquals(0, t.battleMons[2].species)
    }
}
