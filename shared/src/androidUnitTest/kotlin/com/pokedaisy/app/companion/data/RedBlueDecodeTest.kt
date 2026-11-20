package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Red and Blue (Game Boy) on the user's Red save, booted in both - see the red* / blue* fixtures' READMEs.
 * Yellow's reader and tables at Red's own addresses ([GEN1_RED]).
 */
class RedBlueDecodeTest {
    private fun red(fixture: String) = FixtureMemoryReader.load(fixture)

    @Test
    fun `party, money, bag and location - in Red and in Blue`() {
        for ((game, cfg, fixture) in listOf(Triple(GameKind.RED, GEN1_RED, "red"), Triple(GameKind.BLUE, GEN1_BLUE, "blue"))) {
            activeGame = game
            val t = readGen1Telemetry(red(fixture), cfg)
            assertFalse(t.inBattle)
            assertEquals(listOf("PIKACHU", "SANDSLASH", "SNORLAX", "LAPRAS", "PIDGEOT", "RATICATE"), t.party.map { speciesName(it.species) })
            assertEquals(listOf("Joseph", "Liam", "Fred", "Henry", "Seth", "Richard"), t.party.map { it.nickname })
            assertEquals(listOf(182, 274, 450, 382, 300, 236), t.party.map { it.hp })
            assertTrue(t.party.all { it.level == 100 && it.hp == it.maxHp })
            assertEquals(listOf("THUNDERBOLT", "THUNDER", "SLAM", "LIGHT SCREEN"), t.party[0].moves.map { lookupMove(it).name })
            assertEquals(999999L, t.money)
            assertEquals(17, t.items.size)
            assertEquals(0x59, t.regionMapSectionId)
            assertEquals("VERMILION CITY", lookupLocation(t.regionMapSectionId).mapSecName)
        }
    }

    @Test
    fun `the dex flags`() {
        activeGame = GameKind.RED
        val dex = readGen1Telemetry(red("red"), GEN1_RED).pokedex!!
        assertEquals(10 to 106, dex.caught.size to dex.seen.size)
    }

    @Test
    fun `battle - both battlers`() {
        activeGame = GameKind.RED
        val t = readGen1Telemetry(red("red_battle"), GEN1_RED)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(25 to 100, me.species to me.level)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("CHARMANDER", speciesName(foe.species))
        assertEquals(12 to 34, foe.level to foe.hp)
        assertEquals("FIRE", typeName(foe.type1))
        assertTrue(t.enemyParty.isEmpty())
        activeGame = GameKind.BLUE
        assertEquals(32, readGen1Telemetry(red("blue_battle"), GEN1_BLUE).battleMons[BATTLE_POS_OPPONENT_LEFT].hp)
    }

    @Test
    fun `battle menus - which one waits, and its cursor`() {
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 0, readGen1BattleInput(red("red_battle"), GEN1_RED))
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, readGen1BattleInput(red("red_battle_move2"), GEN1_RED))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen1BattleInput(red("red_battle_menu"), GEN1_RED))
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 0, readGen1BattleInput(red("blue_battle"), GEN1_BLUE))
        assertEquals(BATTLE_INPUT_NONE to -1, readGen1BattleInput(red("red"), GEN1_RED))
    }

    @Test
    fun `the POKéMON switch - its menus, the party by key and the one out`() {
        assertEquals(GbPartyMenu(submenu = false, cursor = 2), readGen1PartyMenu(red("red_battle_party"), GEN1_RED))
        assertEquals(GbPartyMenu(submenu = true, cursor = 1), readGen1PartyMenu(red("red_battle_switch_menu"), GEN1_RED))
        assertEquals(GbPartyMenu(submenu = false, cursor = 1, forced = true), readGen1PartyMenu(red("red_battle_faint"), GEN1_RED))
        for (f in listOf("red_battle_menu", "red_battle", "red")) assertNull(f, readGen1PartyMenu(red(f), GEN1_RED))
        val s = Gen1PartySwitch(GEN1_RED)
        val r = red("red_battle_party")
        assertEquals(readGen1Telemetry(r, GEN1_RED).party.map { it.personality }, s.party(r).map { it.key })
        assertEquals(6, s.party(r).map { it.key }.toSet().size)
        assertEquals(0, s.outSlot(r))
        assertEquals(0, s.party(red("red_battle_faint"))[0].hp)
    }
}
