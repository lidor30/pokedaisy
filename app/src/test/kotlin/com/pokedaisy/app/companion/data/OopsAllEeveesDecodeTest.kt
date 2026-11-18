package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pokémon Oops All Eevees! v1.3.2: Kanto on CFRU, FireRed rev 0's RAM, its own names (gen_cfru_tables.py oops_all_eevees).
 * A new game played headless (the fixtures' READMEs say what the game's own screens show). */
class OopsAllEeveesDecodeTest {
    @Test
    fun `the save - party, bag, money, place`() {
        activeGame = GameKind.OOPS_ALL_EEVEES
        val t = decodeNative("oops_all_eevees", NATIVE_OOPS_ALL_EEVEES)
        assertEquals(listOf(Triple("Dunduneon", 10, 42), Triple("Vireon", 8, 39), Triple("Dunneuteon", 5, 31)),
            t.party.map { Triple(speciesName(it.species), it.level, it.maxHp) })
        assertEquals(listOf("Potion" to 5, "Repel" to 2, "Poké Ball" to 5), t.items.map { itemName(it.itemId) to it.quantity })
        assertEquals(3000L, t.money)
        assertEquals("Pallet Town", activeMapSecData[t.regionMapSectionId]?.name)
        assertFalse(t.inBattle)
        assertPocketsInRange(t)
    }

    @Test
    fun `a wild battle`() {
        activeGame = GameKind.OOPS_ALL_EEVEES
        val ram = FixtureMemoryReader.load("oops_all_eevees_battle")
        val t = decodeNative("oops_all_eevees_battle", NATIVE_OOPS_ALL_EEVEES)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(listOf("Tackle", "Tail Whip", "Sand Attack", "Doll Eyes"), me.moves.map { lookupMove(it).name })
        assertEquals("Dunduneon" to 5, speciesName(foe.species) to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_OOPS_ALL_EEVEES))
    }
}
