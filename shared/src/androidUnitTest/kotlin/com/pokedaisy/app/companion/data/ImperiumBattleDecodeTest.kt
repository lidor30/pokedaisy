package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NATIVE_IMPERIUM at a wild battle's action menu (see the fixture's README). */
class ImperiumBattleDecodeTest {
    private val ram = FixtureMemoryReader.load("imperium_battle")
    private val t = readNativeTelemetry(ram, NATIVE_IMPERIUM)

    @Test
    fun `both battlers decode`() {
        activeGame = GameKind.IMPERIUM
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("Charmander", speciesNamesImperium[me.species])
        assertEquals(5, me.level)
        assertEquals(20 to 20, me.hp to me.maxHp)
        assertEquals(11 to 11, me.type1 to me.type2) // Fire (expansion ids)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Shinx", speciesNamesImperium[foe.species])
        assertEquals(4, foe.level)
        assertEquals("Electric", typeName(foe.type1))
    }

    @Test
    fun `touch input and the foe's party`() {
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_IMPERIUM))
        val foe = decodePartyMon(ram.readCoreMemory(NATIVE_IMPERIUM.enemyParty, MON_STRUCT_SIZE), 0)!!
        assertEquals("Shinx" to 4, speciesNamesImperium[foe.species and 0x07FF] to foe.level)
        assertEquals(0, u16le(ram.readCoreMemory(NATIVE_IMPERIUM.battlerPartyIndexes, 2), 0))
    }
}
