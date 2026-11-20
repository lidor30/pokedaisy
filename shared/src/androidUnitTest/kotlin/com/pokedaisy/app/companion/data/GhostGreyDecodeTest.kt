package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Ghost Grey (v1.0.1, v1.0.2, v1.0.4): retail FireRed rev 0's RAM, Rocket Edition's repointed tables with
 * its own ghosts laid over FireRed's (ActiveTables' fireRedHack). Its 1.0.3 save loads in every build (the
 * fixtures' READMEs, with what the game's own screens show): its party, bag and money.
 */
class GhostGreyDecodeTest {
    private fun use(cfg: NativeConfig) {
        activeGame = GameKind.FIRERED
        fireRedHack = fireRedHackFor(cfg)
    }

    @After fun tearDown() {
        fireRedHack = null
    }

    private val builds = listOf("ghost_grey" to NATIVE_GHOST_GREY, "ghost_grey_v102" to NATIVE_GHOST_GREY, "ghost_grey_v101" to NATIVE_GHOST_GREY_V1_0_1)

    @Test
    fun `the save - every build, as the game's own screens show it`() {
        for ((key, cfg) in builds) {
            use(cfg)
            val t = decodeNative(key, cfg)
            assertEquals(listOf("Vivillain", "Drifblim", "Lichtoise", "Poltersect", "Viralplume", "Gastly"),
                t.party.map { speciesName(it.species) })
            assertEquals(listOf(36, 32, 38, 37, 36, 13), t.party.map { it.level })
            assertEquals(listOf(85 to 93, 130 to 138, 100 to 103, 72 to 87, 98 to 100, 30 to 30), t.party.map { it.hp to it.maxHp })
            assertPocketsInRange(t)
            // The bag's ITEMS pocket, first rows: RAGECANDYBAR x9, ADDERALL 5MG x1, GHOST-B-GONE x3, VAPE x2.
            val items = t.items.filter { it.pocket == 0 }.take(4).map { itemName(it.itemId) to it.quantity }
            assertEquals(listOf("RageCandyBar" to 9, "Adderall 5mg" to 1, "Ghost-B-Gone" to 3, "Vape" to 2), items)
            assertEquals(163300L, t.money)
            assertFalse(t.inBattle)
        }
    }

    @Test
    fun `a scripted wild battle - v1_0_4 and v1_0_1`() {
        for ((key, cfg) in listOf("ghost_grey_battle" to NATIVE_GHOST_GREY, "ghost_grey_v101_battle" to NATIVE_GHOST_GREY_V1_0_1)) {
            use(cfg)
            val ram = FixtureMemoryReader.load(key)
            val t = decodeNative(key, cfg)
            assertTrue(t.inBattle)
            val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
            assertEquals(12 to 36, me.species to me.level)
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            assertEquals("Greavard" to 30, speciesName(foe.species) to foe.level)
            assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
            assertEquals(listOf("Psybeam", "Mega Drain", "Air Slash", "Bug Buzz"), me.moves.map { lookupMove(it).name })
        }
    }

    @Test
    fun `its own names, types and matchups over FireRed's`() {
        use(NATIVE_GHOST_GREY)
        assertEquals("Rowlet", speciesName(1))
        assertEquals(SpeciesTypes(12, 7), activeSpeciesTypeData[1])   // GRASS / GHOST
        assertEquals("Fairy", activeTypeNames[23])
        assertEquals(120, activeTypeEffectiveness[707])   // GHOST on GHOST: 1.2x
        assertEquals(50, activeTypeEffectiveness[1623])   // DRAGON on FAIRY: half
        assertEquals(MoveInfo("Purify", 17, 10), activeMoveData[451])
        assertEquals("Jaspar Town", activeMapSecData[110]!!.name)
        assertTrue(readsRomRegionMap)
        assertFalse(movesAreVanilla)
        // v1.0.1: the same tables but PURIFY's type.
        use(NATIVE_GHOST_GREY_V1_0_1)
        assertEquals(MoveInfo("Purify", 14, 10), activeMoveData[451])
        assertEquals("Rowlet", speciesName(1))
    }

    @Test
    fun `detected by hash, each build its own table set`() {
        assertTrue(TelemetrySampler.fireRedHackConfig(TelemetrySampler.GHOST_GREY_V1_0_4_SHA1) === NATIVE_GHOST_GREY)
        assertTrue(TelemetrySampler.fireRedHackConfig(TelemetrySampler.GHOST_GREY_V1_0_2_SHA1) === NATIVE_GHOST_GREY)
        assertTrue(TelemetrySampler.fireRedHackConfig(TelemetrySampler.GHOST_GREY_V1_0_1_SHA1) === NATIVE_GHOST_GREY_V1_0_1)
        assertTrue(fireRedHackFor(NATIVE_GHOST_GREY_V1_0_1) === fireRedHackGhostGreyV101)
    }
}
