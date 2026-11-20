package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pokémon Team Rocket Edition 2026 v3.1 (Spanish): v3.0.9's RAM and tables, its ROM moved ([NATIVE_TEAM_ROCKET_V3_1]). A new
 * game played headless in it, then a scripted wild battle; what the game's own screens show is in the fixtures' READMEs.
 */
class TeamRocketV3DecodeTest {
    private val cfg = NATIVE_TEAM_ROCKET_V3_1

    @Before fun setUp() {
        activeGame = GameKind.TEAM_ROCKET
        teamRocketV3 = true
    }

    @After fun tearDown() {
        activeGame = GameKind.FIRERED
        teamRocketV3 = false
    }

    @Test
    fun `the save - its ZUBAT, money, place and Pokedex`() {
        val t = decodeNative("team_rocket_v3_1", cfg)
        assertFalse(t.inBattle)
        // The game's own ZUBAT (its species id; the summary reads No063, Veneno / Volador).
        assertEquals(listOf(1669), t.party.map { it.species })
        assertEquals("Zubat", speciesName(1669))
        val zubat = t.party.single()
        assertEquals(Triple(5, 20, 20), Triple(zubat.level, zubat.hp, zubat.maxHp))
        assertEquals(listOf("Mordisco", "Absorber", "Supersónico"), zubat.moves.take(3).map { lookupMove(it).name })
        assertEquals(listOf(25, 25, 20, 0), zubat.pp.toList())
        assertEquals(GENDER_SYMBOL_MALE, zubat.genderSymbol)
        assertEquals(SpeciesTypes(4, 3), activeSpeciesTypeData[1669])          // Poison / Flying, expansion's ids
        assertEquals(3000L, t.money)
        assertTrue(t.items.isEmpty())
        assertEquals(0x10A, t.regionMapSectionId)                               // ALMACÉN ROCKET
        assertEquals("Almacén Rocket", mapSecDataTeamRocketV3[0x10A]?.name)
        val dex = t.pokedex!!
        assertEquals(POKEDEX_TEAM_ROCKET_V3_1, dex.tables)
        assertEquals(setOf(41), dex.seen)
        assertEquals(setOf(41), dex.caught)
    }

    @Test
    fun `a scripted wild battle - the battlers, the menus`() {
        val ram = FixtureMemoryReader.load("team_rocket_v3_1_battle")
        val t = decodeNative("team_rocket_v3_1_battle", cfg)
        assertTrue(t.inBattle)
        assertEquals(false, t.isTrainerBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(Triple(1669, 5, 20), Triple(me.species, me.level, me.hp))
        assertEquals(4 to 3, me.type1 to me.type2)
        assertEquals(listOf(25, 25, 20, 0), me.pp.toList())
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(Triple("Ekans", 4, 16), Triple(speciesName(foe.species), foe.level, foe.maxHp))
        assertEquals(listOf(23), t.battleFoes.map { it.species })
        assertTrue(t.enemyParty.isEmpty())
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        assertEquals(0, ram.readCoreMemory(cfg.actionCursor, 1)[0].toInt())
        assertEquals(0, ram.readCoreMemory(cfg.moveCursor, 1)[0].toInt())
    }

    @Test
    fun `the config - v3_0_9's RAM, its own ROM places`() {
        val old = NATIVE_TEAM_ROCKET_V3
        assertEquals(old.playerParty, cfg.playerParty)
        assertEquals(old.saveBlock1Ptr, cfg.saveBlock1Ptr)
        assertEquals(old.battleMons, cfg.battleMons)
        assertEquals(old.partyMenu, cfg.partyMenu)
        // Every battle handler v3.0.9's + 0x68.
        for ((a, b) in listOf(old.handleInputChooseAction to cfg.handleInputChooseAction, old.handleInputChooseMove to cfg.handleInputChooseMove,
                old.completeWhenChoseItem to cfg.completeWhenChoseItem, old.waitForMonSelection to cfg.waitForMonSelection,
                old.handleInputChooseTarget to cfg.handleInputChooseTarget)) assertEquals(0x68L, b - a)
    }
}
