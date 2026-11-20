package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PureRed and PureBlue v2.7.6 on the user's retail Red save, converted by the game's own updater - see the purered* /
 * pureblue* fixtures' READMEs. Gen 1's reader at PureRGB's addresses ([GEN1_PURE_RED], WRAM bank 1 read by bank) over
 * its own tables (gen_gen1_tables.py purered).
 */
class PureRgbDecodeTest {
    private fun pure(fixture: String) = FixtureMemoryReader.load(fixture)

    @After
    fun reset() {
        activeGame = GameKind.FIRERED
    }

    @Test
    fun `party, money, bag and location - in PureRed and in PureBlue`() {
        for ((game, cfg, fixture) in listOf(Triple(GameKind.PURE_RED, GEN1_PURE_RED, "purered"), Triple(GameKind.PURE_BLUE, GEN1_PURE_BLUE, "pureblue"))) {
            activeGame = game
            val t = readGen1Telemetry(pure(fixture), cfg)
            assertFalse(t.inBattle)
            assertEquals(listOf("PIKACHU", "SANDSLASH", "SNORLAX", "LAPRAS", "PIDGEOT", "RATICATE"), t.party.map { speciesName(it.species) })
            assertEquals(listOf(182, 274, 450, 382, 300, 236), t.party.map { it.hp })
            assertTrue(t.party.all { it.level == 100 && it.hp == it.maxHp })
            // PureRGB renamed SLAM.
            assertEquals(listOf("THUNDERBOLT", "THUNDER", "FILTHY SLAM", "LIGHT SCREEN"), t.party[0].moves.map { lookupMove(it).name })
            assertEquals(999999L, t.money)
            // The updater moved the bag to PureRGB's 30 slots (and took the COIN CASE, which is an event there).
            assertEquals(16, t.items.size)
            assertEquals("HELIX FOSSIL", itemName(t.items[0].itemId))
            assertEquals(0x59, t.regionMapSectionId)
            assertEquals("VERMILION CITY", lookupLocation(t.regionMapSectionId).mapSecName)
            assertTrue(t.frameCounter > 0)
        }
    }

    @Test
    fun `its own tables - types, moves, items, places`() {
        activeGame = GameKind.PURE_RED
        assertEquals("NONE", typeName(lookupMove(165).type))      // STRUGGLE is typeless
        assertEquals("TRI", typeName(lookupMove(161).type))       // TRI ATTACK
        assertEquals("HYPER BALL", itemName(5))                    // where Red has the TOWN MAP
        assertEquals("SAFFRON CITY", lookupLocation(7).mapSecName) // Saffron moved before Fuchsia
        assertEquals("", itemDescription(5))                       // not Yellow's TOWN MAP text
        assertTrue(itemDescription(0xC9).startsWith("Teaches "))
        assertEquals(0, singleTypeMultiplierPct(lookupMove(85).type, 4))      // THUNDERBOLT into GROUND
        assertEquals(0, singleTypeMultiplierPct(4, 18))                       // GROUND into FLOATING
        assertEquals(200, singleTypeMultiplierPct(10, 20))                    // BONEMERANG's GROUND into FIRE
    }

    @Test
    fun `the dex flags`() {
        activeGame = GameKind.PURE_RED
        val dex = readGen1Telemetry(pure("purered"), GEN1_PURE_RED).pokedex!!
        assertEquals(10 to 106, dex.caught.size to dex.seen.size)
    }

    @Test
    fun `battle - both battlers`() {
        activeGame = GameKind.PURE_RED
        val t = readGen1Telemetry(pure("purered_battle"), GEN1_PURE_RED)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(25 to 100, me.species to me.level)
        assertEquals(182, me.hp)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("CHARMANDER", speciesName(foe.species))
        assertEquals(12 to 31, foe.level to foe.hp)
        assertEquals("FIRE", typeName(foe.type1))
        assertTrue(t.enemyParty.isEmpty())
        activeGame = GameKind.PURE_BLUE
        assertEquals(32, readGen1Telemetry(pure("pureblue_battle"), GEN1_PURE_BLUE).battleMons[BATTLE_POS_OPPONENT_LEFT].hp)
    }

    @Test
    fun `battle menus - which one waits, and its cursor`() {
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 0, readGen1BattleInput(pure("purered_battle"), GEN1_PURE_RED))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen1BattleInput(pure("purered_battle_menu"), GEN1_PURE_RED))
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 0, readGen1BattleInput(pure("pureblue_battle"), GEN1_PURE_BLUE))
        assertEquals(BATTLE_INPUT_NONE to -1, readGen1BattleInput(pure("purered"), GEN1_PURE_RED))
    }

    @Test
    fun `the POKéMON switch - its menus, the party by key and the one out`() {
        assertEquals(GbPartyMenu(submenu = false, cursor = 2), readGen1PartyMenu(pure("purered_battle_party"), GEN1_PURE_RED))
        assertEquals(GbPartyMenu(submenu = true, cursor = 1), readGen1PartyMenu(pure("purered_battle_switch_menu"), GEN1_PURE_RED))
        // PureRGB's BATTLE_PARTY_MENU is 1 (Red's 2).
        assertEquals(GbPartyMenu(submenu = false, cursor = 1, forced = true), readGen1PartyMenu(pure("purered_battle_faint"), GEN1_PURE_RED))
        for (f in listOf("purered_battle_menu", "purered_battle", "purered")) assertNull(f, readGen1PartyMenu(pure(f), GEN1_PURE_RED))
        val s = Gen1PartySwitch(GEN1_PURE_RED)
        val r = pure("purered_battle_party")
        assertEquals(readGen1Telemetry(r, GEN1_PURE_RED).party.map { it.personality }, s.party(r).map { it.key })
        assertEquals(6, s.party(r).map { it.key }.toSet().size)
        assertEquals(0, s.outSlot(r))
        assertEquals(0, s.party(pure("purered_battle_faint"))[0].hp)
    }

    @Test
    fun `experience follows PureRGB's own growth rates`() {
        activeGame = GameKind.PURE_RED
        // LAPRAS (131): SLOW in Red, MEDIUM SLOW in PureRGB - Lv100 is 1,059,860 there.
        assertEquals(1_059_860L, expProgress(131, 100, 1_059_860L)!!.levelStart)
    }
}
