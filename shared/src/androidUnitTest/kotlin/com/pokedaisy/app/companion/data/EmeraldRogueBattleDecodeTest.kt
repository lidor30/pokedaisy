package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NATIVE_EMERALD_ROGUE mid trainer battle (a live dump from the Thor, see the fixture's README). */
class EmeraldRogueBattleDecodeTest {
    private val t = decodeNative("emerald_rogue_battle", NATIVE_EMERALD_ROGUE)

    @Test
    fun `battle is on and both battlers decode`() {
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]!!
        assertEquals(744, me.species) // Rockruff
        assertEquals(10, me.level)
        assertEquals(31 to 31, me.hp to me.maxHp)
        assertEquals(5 to 5, me.type1 to me.type2) // Rock
        assertEquals(listOf(33, 43, 28, 104), me.moves.toList()) // Tackle, Leer, Sand Attack, Double Team
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]!!
        assertEquals(316, foe.species) // Gulpin
        assertEquals(15, foe.level)
        assertEquals(46 to 46, foe.hp to foe.maxHp)
        assertEquals(3 to 3, foe.type1 to foe.type2) // Poison
    }

    @Test
    fun `the trainer's team is read`() {
        assertEquals(316, t.enemyParty.first().species)
        assertEquals(0, t.enemyActive)
    }

    @Test
    fun `Rogue's own move and type tables`() {
        activeGame = GameKind.EMERALD_ROGUE
        assertEquals(MoveInfo("Sludge", 3, 65), activeMoveData[124])
        assertEquals("Fairy", activeTypeNames[18])
        // Gen 6+ chart: Steel no longer resists Ghost; Fairy is immune to Dragon.
        assertEquals(100, typeMultiplierPct(7, 8, 8))
        assertEquals(0, typeMultiplierPct(16, 18, 18))
    }
}
