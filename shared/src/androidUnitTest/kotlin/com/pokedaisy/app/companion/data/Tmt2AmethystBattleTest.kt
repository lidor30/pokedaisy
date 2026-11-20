package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The battle panes for Too Many Types 2 and Amethyst (see each fixture's boot script). */
class Tmt2AmethystBattleTest {
    @Test
    fun `TMT2 - a scripted wild Pikachu`() {
        val ram = FixtureMemoryReader.load("tmt2_battle")
        val t = readNativeTelemetry(ram, NATIVE_TMT2)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(390 to 5, me.species to me.level) // Chimchar
        assertEquals(20 to 20, me.hp to me.maxHp)
        assertEquals(11 to 61, me.type1 to me.type2) // its own type ids
        assertEquals(listOf(35, 30), me.pp.take(2)) // PP at +0x26
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(25 to 5, foe.species to foe.level)
        assertEquals(19 to 19, foe.hp to foe.maxHp)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_TMT2))
        assertEquals(listOf(25), t.battleFoes.map { it.species })
    }

    @Test
    fun `Amethyst - a wild Flabebe on Route 17`() {
        val ram = FixtureMemoryReader.load("amethyst_battle")
        val t = readNativeTelemetry(ram, NATIVE_AMETHYST)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(551 to 6, me.species to me.level) // Tepig
        assertEquals(25 to 25, me.hp to me.maxHp)
        assertEquals(10 to 10, me.type1 to me.type2)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(840 to 4, foe.species to foe.level) // Flabebe
        assertEquals(17 to 17, foe.hp to foe.maxHp)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_AMETHYST))
        assertEquals(listOf(840), t.battleFoes.map { it.species })
    }
}
