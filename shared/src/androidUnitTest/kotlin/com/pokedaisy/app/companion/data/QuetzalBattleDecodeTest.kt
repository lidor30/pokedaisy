package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NATIVE_QUETZAL at a scripted wild battle's action menu (see the fixture's README). */
class QuetzalBattleDecodeTest {
    private val ram = FixtureMemoryReader.load("quetzal_battle")
    private val t = readNativeTelemetry(ram, NATIVE_QUETZAL)

    @Test
    fun `both battlers decode`() {
        activeGame = GameKind.QUETZAL
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("Charmander", speciesNamesQuetzal[me.species])
        assertEquals(5, me.level)
        assertEquals(10 to 19, me.hp to me.maxHp)
        assertEquals("Fire", typeName(me.type1))
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Pidgey", speciesNamesQuetzal[foe.species])
        assertEquals(3, foe.level)
        assertEquals(listOf("Normal", "Flying"), listOf(typeName(foe.type1), typeName(foe.type2)))
    }

    @Test
    fun `touch input and the foe's party`() {
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_QUETZAL))
        val foe = decodePartyMon(ram.readCoreMemory(NATIVE_QUETZAL.enemyParty, 0x68), 0, QUETZAL_PARTY_MON)!!
        assertEquals("Pidgey" to 3, speciesNamesQuetzal[foe.species] to foe.level)
        assertEquals(0, u16le(ram.readCoreMemory(NATIVE_QUETZAL.battlerPartyIndexes, 2), 0))
    }
}
