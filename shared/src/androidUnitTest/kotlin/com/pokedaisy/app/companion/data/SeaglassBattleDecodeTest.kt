package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NATIVE_EMERALD_SEAGLASS mid wild battle (a headless scripted battle, see the fixture's README). */
class SeaglassBattleDecodeTest {
    private val ram = FixtureMemoryReader.load("emerald_seaglass_battle")
    private val t = readNativeTelemetry(ram, NATIVE_EMERALD_SEAGLASS)

    @Test
    fun `both battlers decode`() {
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(255 to 5, me.species to me.level) // Torchic
        assertEquals(13 to 19, me.hp to me.maxHp) // the copy, hit as it came in
        assertEquals(11 to 11, me.type1 to me.type2) // Fire (expansion ids)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(25 to 5, foe.species to foe.level) // Pikachu
        assertEquals(14 to 14, foe.type1 to foe.type2) // Electric
    }

    @Test
    fun `switch and touch input addresses`() {
        assertEquals(1, u16le(ram.readCoreMemory(NATIVE_EMERALD_SEAGLASS.battlerPartyIndexes, 2), 0)) // slot 2 is out
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_EMERALD_SEAGLASS))
        val foe = decodePartyMon(ram.readCoreMemory(NATIVE_EMERALD_SEAGLASS.enemyParty, MON_STRUCT_SIZE), 0)!!
        assertEquals(25 to 5, foe.species to foe.level) // the wild Pikachu in gEnemyParty
    }

    @Test
    fun `its own move and species tables`() {
        // Moves used to come from vanilla's table (vanilla type ids under expansion's names: "TYPE#0").
        activeGame = GameKind.EMERALD_SEAGLASS
        assertEquals("Scratch", lookupMove(10).name)
        assertEquals("Normal", typeName(lookupMove(10).type))
        assertEquals("Fire", typeName(lookupMove(52).type)) // Ember
        assertEquals(SpeciesTypes(11, 11), activeSpeciesTypeData[255]) // Torchic, by National Dex number
    }
}
