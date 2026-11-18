package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pokémon Ceruleum v1.1.1: pokeemerald-expansion 1.13.2, its own RAM and names (gen_expansion_tables.py ceruleum). A new
 * game played headless (the fixtures' READMEs say what the game's own screens show). */
class CeruleumDecodeTest {
    @Test
    fun `the save - party, bag, money, place`() {
        activeGame = GameKind.CERULEUM
        val t = decodeNative("ceruleum", NATIVE_CERULEUM)
        assertEquals(listOf(Triple("Xatu", 70, 171), Triple("Charizard", 10, 37), Triple("Pikachu", 8, 24),
            Triple("Cyndaquil", 5, 19)), t.party.map { Triple(speciesName(it.species), it.level, it.maxHp) })
        assertEquals(listOf("Potion" to 5, "Repel" to 2, "Poké Ball" to 10), t.items.map { itemName(it.itemId) to it.quantity })
        assertEquals(258984L, t.money)
        assertEquals("Council HQ", activeMapSecData[t.regionMapSectionId]?.name)
        assertFalse(t.inBattle)
        assertPocketsInRange(t)
    }

    @Test
    fun `a wild battle`() {
        activeGame = GameKind.CERULEUM
        val ram = FixtureMemoryReader.load("ceruleum_battle")
        val t = decodeNative("ceruleum_battle", NATIVE_CERULEUM)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(listOf("Psychic", "Aeroblast", "Shadow Ball", "Toxic"), me.moves.map { lookupMove(it).name })
        assertEquals("Xatu" to 70, speciesName(me.species) to me.level)
        assertEquals("Rattata" to 5, speciesName(foe.species) to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_CERULEUM))
    }
}
