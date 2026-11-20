package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FireRed Rocket Edition and Ash Gray (v4.5.3, Fork Bug Fix v1.0): retail FireRed rev 0's RAM, FireRed's
 * tables with their own names laid over them (ActiveTables' fireRedHack). Retail FireRed's save boots in
 * each (see the fixtures' READMEs): its party, bag and money, on the shore Rocket Edition's map header calls
 * ROUTE 20 and Ash Gray's (which moved gMapGroups) ROUTE 12, as its quest log says.
 */
class FireRedHackDecodeTest {
    private fun use(cfg: NativeConfig) {
        activeGame = GameKind.FIRERED
        fireRedHack = fireRedHackFor(cfg)
    }

    @After fun tearDown() {
        fireRedHack = null
    }

    private fun checkSave(key: String, cfg: NativeConfig, mapsec: Int): Telemetry {
        use(cfg)
        val t = decodeNative(key, cfg)
        assertEquals(6, t.party.size)
        assertEquals(listOf(135, 64, 131, 34, 74, 145), t.party.map { it.species })
        assertEquals(listOf(50, 50, 50, 49, 7, 50), t.party.map { it.level })
        assertEquals(127 to 127, t.party[0].hp to t.party[0].maxHp)
        assertPocketsInRange(t)
        assertTrue(t.items.size > 10)
        assertEquals(mapsec, t.regionMapSectionId)
        assertEquals(246468L, t.money)
        assertFalse(t.inBattle)
        return t
    }

    private fun checkBattle(key: String, cfg: NativeConfig) {
        use(cfg)
        val ram = FixtureMemoryReader.load(key)
        val t = decodeNative(key, cfg)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(135 to 50, me.species to me.level)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(143 to 100, foe.species to foe.level)
        // Retail rev 0's battle handlers: the action menu waits.
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        val wild = decodePartyMon(ram.readCoreMemory(cfg.enemyParty, MON_STRUCT_SIZE), 0)!!
        assertEquals(143 to 100, wild.species to wild.level)
    }

    @Test
    fun `Rocket Edition - the save, in its own mixed-case names`() {
        val t = checkSave("rocket_edition", NATIVE_ROCKET_EDITION, mapsec = 0x78)
        assertEquals("Jolteon", speciesName(t.party[0].species))
        assertEquals(listOf("Double Kick", "Thunder Wave", "Thunderbolt", "Quick Attack"), t.party[0].moves.map { lookupMove(it).name })
        assertEquals("Route 20", activeMapSecData[t.regionMapSectionId]!!.name)
    }

    @Test
    fun `Rocket Edition - a scripted wild battle`() = checkBattle("rocket_edition_battle", NATIVE_ROCKET_EDITION)

    @Test
    fun `Rocket Edition - its FAIRY type, added moves and items`() {
        use(NATIVE_ROCKET_EDITION)
        assertEquals("Fairy", activeTypeNames[23])
        assertEquals(SpeciesTypes(23, 23), activeSpeciesTypeData[35])
        assertEquals(MoveInfo("Play Rough", 23, 90), activeMoveData[463])
        assertEquals(200, activeTypeEffectiveness[2316])   // FAIRY on DRAGON
        assertEquals(0, activeTypeEffectiveness[1623])     // DRAGON on FAIRY
        assertEquals("Choice Specs", itemName(226))
        // Its moves aren't FireRed's: no move advice from FireRed's numbers.
        assertFalse(movesAreVanilla)
    }

    @Test
    fun `Ash Gray - the save, both builds`() {
        for (key in listOf("ash_gray", "ash_gray_fork")) {
            val t = checkSave(key, NATIVE_ASH_GRAY, mapsec = 0x70)
            assertEquals("JOLTEON", speciesName(t.party[0].species))
        }
    }

    @Test
    fun `Ash Gray - a scripted wild battle, both builds`() {
        checkBattle("ash_gray_battle", NATIVE_ASH_GRAY)
        checkBattle("ash_gray_fork_battle", NATIVE_ASH_GRAY)
    }

    @Test
    fun `Ash Gray - its own names, matchup and region map over FireRed's`() {
        use(NATIVE_ASH_GRAY)
        assertEquals("GS BALL", itemName(349))
        assertEquals("Valencia Island", activeMapSecData[143]!!.name)
        assertEquals(50, activeTypeEffectiveness[1304])    // ELECTRIC on GROUND: half, not none
        assertEquals(MoveInfo("Tackle", 0, 50), activeMoveData[33])
        assertTrue(readsRomRegionMap)
        assertEquals(0, activeRegionMapImages.size)
        // ... and only here: plain FireRed keeps its own.
        fireRedHack = null
        assertEquals(0, activeTypeEffectiveness[1304])
        assertFalse(readsRomRegionMap)
        assertTrue(movesAreVanilla)
    }
}
