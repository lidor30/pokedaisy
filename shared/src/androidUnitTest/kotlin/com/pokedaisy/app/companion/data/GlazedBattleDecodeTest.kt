package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NATIVE_GLAZED at a wild battle's action menu (see the fixture's README). */
class GlazedBattleDecodeTest {
    private val ram = FixtureMemoryReader.load("glazed_battle")
    private val t = readNativeTelemetry(ram, NATIVE_GLAZED)

    @Test
    fun `both battlers decode`() {
        activeGame = GameKind.GLAZED
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("CHIMCHAR", speciesNamesGlazed[me.species])
        assertEquals(6, me.level)
        assertEquals(20 to 22, me.hp to me.maxHp)
        assertEquals(10 to 10, me.type1 to me.type2) // Fire
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("SENTRET", speciesNamesGlazed[foe.species])
        assertEquals(2, foe.level)
    }

    @Test
    fun `touch input and the foe's party`() {
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_GLAZED))
        val foe = decodePartyMon(ram.readCoreMemory(NATIVE_GLAZED.enemyParty, MON_STRUCT_SIZE), 0)!!
        assertEquals("SENTRET" to 2, speciesNamesGlazed[foe.species] to foe.level)
        assertEquals(0, u16le(ram.readCoreMemory(NATIVE_GLAZED.battlerPartyIndexes, 2), 0))
    }
}
