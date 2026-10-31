package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the NUZLOCKE tracker reads ([NuzlockeSample.of]) on every GBA battle capture: in battle, wild or trainer, the
 * foe, and the party's personalities (each the run's id for a Pokémon).
 */
class NuzlockeFixturesTest {
    private val fixtures = listOf(
        "emerald_de_battle" to NATIVE_EMERALD_DE, "emerald_es_battle" to NATIVE_EMERALD_ES, "emerald_fr_battle" to NATIVE_EMERALD_FR,
        "emerald_it_battle" to NATIVE_EMERALD_IT, "emerald_ja_battle" to NATIVE_EMERALD_JA, "sapphire_rev1_battle" to NATIVE_SAPPHIRE,
        "emerald_rogue_battle" to NATIVE_EMERALD_ROGUE,
        "unbound_battle" to NATIVE_UNBOUND_WITH_DEX, "soulgold_battle" to NATIVE_SOULGOLD, "soulgold_v12_battle" to NATIVE_SOULGOLD_V1_2,
        "soulgold_v12b_battle" to NATIVE_SOULGOLD_V1_2B, "soulgold_v12c_battle" to NATIVE_SOULGOLD_V1_2C,
        "soulgold_v121_battle" to NATIVE_SOULGOLD_V1_2_1,
        "emerald_kaizo_battle" to NATIVE_EMERALD_KAIZO, "emerald_legacy_battle" to NATIVE_EMERALD_LEGACY, "radical_red_battle" to NATIVE_RADICAL_RED_V4_1,
        "gaia_battle" to NATIVE_GAIA_V3_2, "glazed_battle" to NATIVE_GLAZED, "lazarus_battle" to NATIVE_LAZARUS,
        "odyssey_battle" to NATIVE_ODYSSEY, "amethyst_battle" to NATIVE_AMETHYST, "sword_shield_up_battle" to NATIVE_SWORD_SHIELD,
        "sword_shield_up_casual_battle" to NATIVE_SWORD_SHIELD, "sword_shield_up_performance_battle" to NATIVE_SWORD_SHIELD,
        "sword_shield_up_casual_performance_battle" to NATIVE_SWORD_SHIELD,
        "emerald_seaglass_battle" to NATIVE_EMERALD_SEAGLASS, "tmt2_battle" to NATIVE_TMT2, "heart_and_soul_battle" to NATIVE_HEART_AND_SOUL,
        "orange_islands_battle" to NATIVE_ORANGE_ISLANDS, "ultra_violet_battle" to NATIVE_ULTRA_VIOLET, "celia_battle" to NATIVE_CELIA,
        "rocket_edition_battle" to NATIVE_ROCKET_EDITION, "ash_gray_battle" to NATIVE_ASH_GRAY, "ash_gray_fork_battle" to NATIVE_ASH_GRAY,
        "ghost_grey_battle" to NATIVE_GHOST_GREY, "ghost_grey_v101_battle" to NATIVE_GHOST_GREY_V1_0_1,
        "recharged_yellow_v180_battle" to NATIVE_RECHARGED_YELLOW_V1_8_0, "recharged_yellow_v190_battle" to NATIVE_RECHARGED_YELLOW_V1_9_0,
        "recharged_yellow_v194_battle" to NATIVE_RECHARGED_YELLOW_V1_9_4, "recharged_yellow_battle" to NATIVE_RECHARGED_YELLOW_V1_9_7,
        "recharged_yellow_trainer" to NATIVE_RECHARGED_YELLOW_V1_9_7,
        "mariomon_v1_5_1_battle" to NATIVE_MARIOMON_V1_5_1, "mariomon_v1_5_2_battle" to NATIVE_MARIOMON_V1_5_2, "mariomon_v1_3_battle" to NATIVE_MARIOMON_V1_3,
        "mariomon_v1_2_2_battle" to NATIVE_MARIOMON_V1_2_2, "mariomon_v1_2_1_battle" to NATIVE_MARIOMON_V1_2_1,
        "quetzal_battle" to NATIVE_QUETZAL, "quetzal_es_battle" to NATIVE_QUETZAL_ES,
        "quetzal_v1_battle" to NATIVE_QUETZAL_V1, "quetzal_es_v1_battle" to NATIVE_QUETZAL_ES_V1, "imperium_battle" to NATIVE_IMPERIUM, "rowe_battle" to NATIVE_ROWE,
        "quetzal_084_battle" to NATIVE_QUETZAL_V084, "quetzal_es_084_battle" to NATIVE_QUETZAL_V084_ES,
        "quetzal_moemon_084_battle" to NATIVE_QUETZAL_MOEMON_V084, "quetzal_moemon_es_084_battle" to NATIVE_QUETZAL_MOEMON_V084_ES,
        "gs_chronicles_battle" to NATIVE_GS_CHRONICLES,
        "recharged_emerald_v225_battle" to NATIVE_RECHARGED_EMERALD_V2_2_5, "recharged_emerald_battle" to NATIVE_RECHARGED_EMERALD_V2_2_8,
        "recharged_emerald_rebalanced_battle" to NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8,
        "elite_redux_battle" to NATIVE_ELITE_REDUX_V2_65_4_1B, "elite_redux_v2653_battle" to NATIVE_ELITE_REDUX_V2_65_3B,
        "elite_redux_trainer" to NATIVE_ELITE_REDUX_V2_65_4_1B, "elite_redux_v2653_trainer" to NATIVE_ELITE_REDUX_V2_65_3B,
        "team_rocket_battle" to NATIVE_TEAM_ROCKET, "team_rocket_trainer" to NATIVE_TEAM_ROCKET,
    )

    // Battles whose foe isn't read (the tracker counts no encounter there): Unbound's capture is a scripted battle
    // against a Lv0 foe; Celia's battlers don't decode (its BATTLE tab shows the same).
    private val noFoe = setOf("unbound_battle", "celia_battle")

    @Test
    fun everyCaptureGivesTheTrackerWhatItReads() {
        for ((name, cfg) in fixtures) {
            val t = readNativeTelemetry(FixtureMemoryReader.load(name), cfg)
            assertTrue(name, t.inBattle)
            // Emerald Rogue's capture is a trainer's.
            assertEquals(name, name.endsWith("_trainer") || name == "emerald_rogue_battle", t.isTrainerBattle)
            assertTrue(name, t.party.isNotEmpty() && t.party.all { it.personality != 0L })
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            if (name !in noFoe) assertTrue("$name: ${foe.species} Lv${foe.level}", foe.species != 0 && foe.level in 1..100)
        }
    }

    @Test
    fun aFoeTheGameCantNameIsNoFoe() {
        activeGame = GameKind.FIRERED
        fun foe(species: Int, level: Int) = MonView(species, "", level, 10, 10, "", emptyList(), null)
        val v = SnapshotView(connected = true, inBattle = true, isTrainerBattle = false)
        assertEquals(25, NuzlockeSample.of(v.copy(battleOpponent = listOf(foe(25, 5)))).foeSpecies)
        assertEquals(0, NuzlockeSample.of(v.copy(battleOpponent = listOf(foe(61676, 3)))).foeSpecies)
        assertEquals(0, NuzlockeSample.of(v.copy(battleOpponent = listOf(foe(25, 0)))).foeSpecies)
    }

    @Test
    fun theGamesItFollows() {
        val n = com.pokedaisy.app.companion.Nuzlocke
        assertTrue(n.supports(GameKind.FIRERED) && n.supports(GameKind.EMERALD) && n.supports(GameKind.UNBOUND))
        // A roguelike walks the same areas every run; the Game Boy and DS readers don't tell wild from trainer yet.
        assertFalse(n.supports(GameKind.EMERALD_ROGUE))
        assertFalse(n.supports(GameKind.YELLOW) || n.supports(GameKind.CRYSTAL) || n.supports(GameKind.PLATINUM) || n.supports(null))
    }
}
