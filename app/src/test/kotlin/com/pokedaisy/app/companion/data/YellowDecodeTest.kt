package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pokémon Yellow (Game Boy) on the user's save - see the yellow / yellow_battle fixtures' READMEs. */
class YellowDecodeTest {
    @Test
    fun `party - Gen 1's big-endian struct, internal species to Dex numbers`() {
        activeGame = GameKind.YELLOW
        val t = readGen1Telemetry(FixtureMemoryReader.load("yellow"), GEN1_YELLOW)
        assertFalse(t.inBattle)
        assertEquals(6, t.party.size)
        assertEquals(listOf("PIKACHU", "SANDSLASH", "SNORLAX", "LAPRAS", "PIDGEOT", "RATICATE"), t.party.map { speciesName(it.species) })
        // wPartyMonNicks: this save's are the player's own, not the species names.
        assertEquals(listOf("Joseph", "Liam", "Fred", "Henry", "Seth", "Richard"), t.party.map { it.nickname })
        val pikachu = t.party[0]
        assertEquals(25 to 100, pikachu.species to pikachu.level)
        assertEquals(182 to 182, pikachu.hp to pikachu.maxHp)
        assertEquals(listOf("THUNDERBOLT", "THUNDER", "SLAM", "LIGHT SCREEN"), pikachu.moves.map { lookupMove(it).name })
        assertEquals(listOf(15, 10, 20, 30), pikachu.pp.toList())
        assertEquals(GENDER_SYMBOL_NONE, pikachu.genderSymbol)
        assertEquals(450 to 450, t.party[2].hp to t.party[2].maxHp) // SNORLAX
    }

    @Test
    fun `money, bag and location`() {
        activeGame = GameKind.YELLOW
        val t = readGen1Telemetry(FixtureMemoryReader.load("yellow"), GEN1_YELLOW)
        assertEquals(999999L, t.money) // BCD 99 99 99
        assertEquals(17, t.items.size)
        assertEquals("S.S.TICKET", itemName(t.items[1].itemId))
        assertEquals(89, t.regionMapSectionId)
        assertEquals("VERMILION CITY", lookupLocation(t.regionMapSectionId).mapSecName)
    }

    @Test
    fun `battle - both battlers, Gen 1 types`() {
        activeGame = GameKind.YELLOW
        val t = readGen1Telemetry(FixtureMemoryReader.load("yellow_battle"), GEN1_YELLOW)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(25 to 100, me.species to me.level)
        assertEquals("ELECTRIC", typeName(me.type1))
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("CHARMANDER", speciesName(foe.species))
        assertEquals(12, foe.level)
        assertEquals("FIRE", typeName(foe.type1))
        assertTrue(t.enemyParty.isEmpty()) // a wild battle has no FOE TEAM
    }

    @Test
    fun `battle menus - which one waits, and its cursor`() {
        // On the move list (the yellow_battle fixture), the first move.
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 0, readGen1BattleInput(FixtureMemoryReader.load("yellow_battle"), GEN1_YELLOW))
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, readGen1BattleInput(FixtureMemoryReader.load("yellow_battle_move2"), GEN1_YELLOW))
        // The battle menu with the cursor on RUN: column 1, row 1.
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen1BattleInput(FixtureMemoryReader.load("yellow_battle_menu"), GEN1_YELLOW))
        assertEquals(BATTLE_INPUT_ACTION_SELECT, readGen1Telemetry(FixtureMemoryReader.load("yellow_battle_menu"), GEN1_YELLOW).battleInputState)
        // On the field: no battle.
        assertEquals(BATTLE_INPUT_NONE to -1, readGen1BattleInput(FixtureMemoryReader.load("yellow"), GEN1_YELLOW))
    }

    @Test
    fun `Gen 1 type chart - its own quirks`() {
        activeGame = GameKind.YELLOW
        val ghost = activeTypeNames.entries.first { it.value == "GHOST" }.key
        val psychic = activeTypeNames.entries.first { it.value == "PSYCHIC" }.key
        assertEquals(0, activeTypeEffectiveness[ghost * 100 + psychic]) // the famous bug
        assertEquals("NORMAL", typeName(lookupMove(44).type)) // BITE
    }
}
