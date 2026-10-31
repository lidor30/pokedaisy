package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The battle menus' cursors (NativeConfig.actionCursor / moveCursor) on a real capture: the German
 * Emerald battle (English Emerald's RAM) stopped at the action menu, FIGHT highlighted.
 */
class BattleCursorTest {
    @Test
    fun `the action cursor sits on FIGHT at the action menu`() {
        val ram = FixtureMemoryReader.load("emerald_de_battle")
        val cfg = NATIVE_EMERALD_DE
        assertEquals(0, ram.readCoreMemory(cfg.actionCursor, 1)[0].toInt())
        assertTrue((ram.readCoreMemory(cfg.moveCursor, 1)[0].toInt() and 0xFF) in 0..3)
    }

    @Test
    fun `the hacks checked headless sit on FIGHT at their action menus`() {
        // Unbound keeps FireRed's cursors (CFRU's EWRAM is FireRed's); SoulGold's are its own, the same in every build.
        listOf("unbound_battle" to NATIVE_UNBOUND, "soulgold_battle" to NATIVE_SOULGOLD, "soulgold_v12_battle" to NATIVE_SOULGOLD_V1_2,
            "soulgold_v12b_battle" to NATIVE_SOULGOLD_V1_2B, "soulgold_v12c_battle" to NATIVE_SOULGOLD_V1_2C,
            "soulgold_v121_battle" to NATIVE_SOULGOLD_V1_2_1,
            "emerald_kaizo_battle" to NATIVE_EMERALD_KAIZO, "emerald_legacy_battle" to NATIVE_EMERALD_LEGACY, "radical_red_battle" to NATIVE_RADICAL_RED_V4_1,
            "gaia_battle" to NATIVE_GAIA_V3_2, "glazed_battle" to NATIVE_GLAZED, "lazarus_battle" to NATIVE_LAZARUS,
            "odyssey_battle" to NATIVE_ODYSSEY, "amethyst_battle" to NATIVE_AMETHYST, "sword_shield_up_battle" to NATIVE_SWORD_SHIELD,
            "emerald_seaglass_battle" to NATIVE_EMERALD_SEAGLASS, "tmt2_battle" to NATIVE_TMT2, "heart_and_soul_battle" to NATIVE_HEART_AND_SOUL,
            "orange_islands_battle" to NATIVE_ORANGE_ISLANDS, "ultra_violet_battle" to NATIVE_ULTRA_VIOLET, "celia_battle" to NATIVE_CELIA,
            "rocket_edition_battle" to NATIVE_ROCKET_EDITION, "ash_gray_battle" to NATIVE_ASH_GRAY, "ash_gray_fork_battle" to NATIVE_ASH_GRAY,
            "ghost_grey_battle" to NATIVE_GHOST_GREY, "ghost_grey_v101_battle" to NATIVE_GHOST_GREY_V1_0_1,
            "recharged_yellow_v180_battle" to NATIVE_RECHARGED_YELLOW_V1_8_0, "recharged_yellow_v190_battle" to NATIVE_RECHARGED_YELLOW_V1_9_0,
            "recharged_yellow_v194_battle" to NATIVE_RECHARGED_YELLOW_V1_9_4, "recharged_yellow_battle" to NATIVE_RECHARGED_YELLOW_V1_9_7,
            "mariomon_v1_5_1_battle" to NATIVE_MARIOMON_V1_5_1, "mariomon_v1_5_2_battle" to NATIVE_MARIOMON_V1_5_2, "mariomon_v1_3_battle" to NATIVE_MARIOMON_V1_3,
            "mariomon_v1_2_2_battle" to NATIVE_MARIOMON_V1_2_2, "mariomon_v1_2_1_battle" to NATIVE_MARIOMON_V1_2_1,
            "quetzal_battle" to NATIVE_QUETZAL, "quetzal_es_battle" to NATIVE_QUETZAL_ES,
            "quetzal_v1_battle" to NATIVE_QUETZAL_V1, "quetzal_es_v1_battle" to NATIVE_QUETZAL_ES_V1, "imperium_battle" to NATIVE_IMPERIUM, "rowe_battle" to NATIVE_ROWE,
            "quetzal_084_battle" to NATIVE_QUETZAL_V084, "quetzal_es_084_battle" to NATIVE_QUETZAL_V084_ES,
            "quetzal_moemon_084_battle" to NATIVE_QUETZAL_MOEMON_V084, "quetzal_moemon_es_084_battle" to NATIVE_QUETZAL_MOEMON_V084_ES,
            "gs_chronicles_battle" to NATIVE_GS_CHRONICLES,
            "recharged_emerald_v225_battle" to NATIVE_RECHARGED_EMERALD_V2_2_5, "recharged_emerald_battle" to NATIVE_RECHARGED_EMERALD_V2_2_8,
            "recharged_emerald_rebalanced_battle" to NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8,
            "sapphire_rev1_battle" to NATIVE_SAPPHIRE,
            // Elite Redux's own menu: 0 is FIGHT there too.
            "elite_redux_battle" to NATIVE_ELITE_REDUX_V2_65_4_1B, "elite_redux_v2653_battle" to NATIVE_ELITE_REDUX_V2_65_3B,
            "team_rocket_battle" to NATIVE_TEAM_ROCKET, "team_rocket_trainer" to NATIVE_TEAM_ROCKET,
            "wishes_battle" to NATIVE_WISHES, "wishes_trainer" to NATIVE_WISHES,
            "dreamstone_battle" to NATIVE_DREAMSTONE, "dreamstone_trainer" to NATIVE_DREAMSTONE,
        ).forEach { (fixture, cfg) ->
            val ram = FixtureMemoryReader.load(fixture)
            assertEquals(fixture, 0, ram.readCoreMemory(cfg.actionCursor, 1)[0].toInt())
            assertTrue(fixture, (ram.readCoreMemory(cfg.moveCursor, 1)[0].toInt() and 0xFF) in 0..3)
        }
    }

    @Test
    fun `only the configs checked headless have them`() {
        assertEquals(0x020244ACL to 0x020244B0L, NATIVE_EMERALD_RETAIL.actionCursor to NATIVE_EMERALD_RETAIL.moveCursor)
        assertEquals(0x02023FF8L to 0x02023FFCL, NATIVE_FIRERED_REV1.actionCursor to NATIVE_FIRERED_REV1.moveCursor)
        assertEquals(0x02023FF8L to 0x02023FFCL, NATIVE_UNBOUND.actionCursor to NATIVE_UNBOUND.moveCursor)
        assertEquals(0x02023FF8L to 0x02023FFCL, NATIVE_UNBOUND_FR.actionCursor to NATIVE_UNBOUND_FR.moveCursor)
        assertEquals(0x02001E50L to 0x02001E54L, NATIVE_SOULGOLD.actionCursor to NATIVE_SOULGOLD.moveCursor)
        assertEquals(0x02001E50L to 0x02001E54L, NATIVE_SOULGOLD_V1_2C.actionCursor to NATIVE_SOULGOLD_V1_2C.moveCursor)
        assertEquals(0x02001E50L to 0x02001E54L, NATIVE_SOULGOLD_V1_2_1.actionCursor to NATIVE_SOULGOLD_V1_2_1.moveCursor)
        assertEquals(0x02024E60L to 0x02024E64L, NATIVE_RUBY.actionCursor to NATIVE_RUBY.moveCursor)
        // Copies whose RAM differs (or isn't checked) are zeroed; the hacks' base configs never had them.
        listOf(NATIVE_EMERALD_JA, NATIVE_LEAFGREEN_REV1, NATIVE_EMERALD, NATIVE_FIRERED_REV0, NATIVE_EMERALD_ROGUE).forEach {
            assertEquals(0L to 0L, it.actionCursor to it.moveCursor)
        }
    }

    @Test
    fun `Ruby and Sapphire's battle controls - the controller sits in HandleInputChooseAction`() {
        val ram = FixtureMemoryReader.load("sapphire_rev1_battle")
        val cfg = NATIVE_SAPPHIRE
        assertTrue(cfg.hasBattleInputAddrs)
        assertEquals(cfg.handleInputChooseAction or 1, u32le(ram.readCoreMemory(cfg.battlerControllerFuncs, 4), 0))
    }
}
