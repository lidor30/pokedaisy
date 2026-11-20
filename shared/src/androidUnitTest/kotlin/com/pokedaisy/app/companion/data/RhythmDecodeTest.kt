package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pokémon Rhythm v1.4.0: Vedeo on CFRU, FireRed rev 0's RAM, its own names (gen_cfru_tables.py rhythm). A new game played
 * headless (the fixtures' READMEs say what the game's own screens show). */
class RhythmDecodeTest {
    @Test
    fun `the save - party, bag, money, place`() {
        activeGame = GameKind.RHYTHM
        val t = decodeNative("rhythm", NATIVE_RHYTHM)
        assertEquals(listOf(Triple("Charmander", 10, 28), Triple("Pikachu", 8, 23), Triple("Magikarp", 5, 17)),
            t.party.map { Triple(speciesName(it.species), it.level, it.maxHp) })
        assertEquals(listOf("Heart Scale" to 1, "Potion" to 5, "Repel" to 2, "Quest Menu" to 1, "Poké Ball" to 5),
            t.items.map { itemName(it.itemId) to it.quantity })
        assertEquals(5000L, t.money)
        assertEquals("Notewood Town", activeMapSecData[t.regionMapSectionId]?.name)
        assertFalse(t.inBattle)
        assertPocketsInRange(t)
    }

    @Test
    fun `a wild battle`() {
        activeGame = GameKind.RHYTHM
        val ram = FixtureMemoryReader.load("rhythm_battle")
        val t = decodeNative("rhythm_battle", NATIVE_RHYTHM)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(listOf("Scratch", "Growl", "Ember", "Smokescreen"), me.moves.map { lookupMove(it).name })
        assertEquals("Pikachu" to 5, speciesName(foe.species) to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_RHYTHM))
    }
}
