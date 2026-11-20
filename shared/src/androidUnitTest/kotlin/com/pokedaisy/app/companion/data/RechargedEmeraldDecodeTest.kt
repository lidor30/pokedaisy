package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NATIVE_RECHARGED_EMERALD_*: every build decodes the user's retail Emerald save, rewritten into its save layout by
 * scripts/convert_save_recharged_emerald.py (the fixtures' READMEs), from its own addresses. What each expects is
 * what the game's own party, bag and battle screens showed headless.
 */
class RechargedEmeraldDecodeTest {
    private val builds = listOf(
        "recharged_emerald_v225" to NATIVE_RECHARGED_EMERALD_V2_2_5,
        "recharged_emerald" to NATIVE_RECHARGED_EMERALD_V2_2_8,
        "recharged_emerald_rebalanced" to NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8,
    )

    private fun use(cfg: NativeConfig) {
        activeGame = GameKind.EMERALD
        emeraldHack = emeraldHackFor(cfg)
    }

    @After fun tearDown() {
        emeraldHack = null
    }

    @Test
    fun `party, bag, money and place in every build`() {
        for ((key, cfg) in builds) {
            use(cfg)
            val t = decodeNative(key, cfg)
            assertEquals(
                key,
                listOf("KADABRA" to 36, "MANECTRIC" to 36, "TRAPINCH" to 27, "BLAZIKEN" to 44, "PELIPPER" to 43),
                t.party.take(5).map { speciesName(it.species) to it.level },
            )
            assertEquals(key, listOf(81, 108, 65, 145, 120, 30), t.party.map { it.maxHp })
            assertEquals(key, 11, t.party[5].level)
            // Its mixed-case move names over Emerald's ids.
            assertEquals(key, listOf("Psychic", "Confusion", "Disable", "Psybeam"), t.party[0].moves.map { lookupMove(it).name })

            val bag = t.items.associate { itemName(it.itemId) to (it.quantity to it.pocket) }
            assertEquals(key, 1 to POCKET_ITEMS, bag["Escape Rope"])
            assertEquals(key, 2 to POCKET_ITEMS, bag["X Special"])
            // Its Medicine pocket (where the converted save put retail's medicine) folds into ITEMS.
            assertEquals(key, 2 to POCKET_ITEMS, bag["Potion"])
            assertEquals(key, 4 to POCKET_ITEMS, bag["Revive"])
            assertEquals(key, 6 to POCKET_POKE_BALLS, bag["Great Ball"])
            assertEquals(key, 3 to POCKET_POKE_BALLS, bag["Nest Ball"])
            assertTrue(key, t.items.any { it.pocket == POCKET_KEY_ITEMS })
            assertPocketsInRange(t)

            assertEquals(key, 0 to 29, t.mapGroup to t.mapNum)
            assertEquals(key, 0x1D, t.regionMapSectionId)
            assertEquals(key, 185092L, t.money)
            assertEquals(key, 0, t.playerGender) // BRENDAN (Rebalanced: SaveBlock2 4 bytes later)
            assertFalse(key, t.inBattle)
        }
    }

    @Test
    fun `a scripted wild battle in every build`() {
        for ((key, cfg) in builds) {
            use(cfg)
            val ram = FixtureMemoryReader.load(key + "_battle")
            val t = decodeNative(key + "_battle", cfg)
            assertTrue(key, t.inBattle)
            assertFalse(t.isDoubleBattle)
            val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
            assertEquals(key, "KADABRA" to 36, speciesName(me.species) to me.level)
            assertEquals(81 to 81, me.hp to me.maxHp)
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            assertEquals(key, "ZIGZAGOON" to 5, speciesName(foe.species) to foe.level)
            assertEquals(key, 0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
            assertTrue(t.enemyParty.isEmpty())
            assertEquals(listOf(288 to 5), t.battleFoes.map { it.species to it.level })
        }
    }

    @Test
    fun `its names come from its own tables, Emerald's ids`() {
        use(NATIVE_RECHARGED_EMERALD_V2_2_8)
        assertEquals("PIKACHU", speciesName(25))
        assertEquals("Thunderbolt", lookupMove(85).name)
        assertEquals("Linking Cord", itemName(61))
        assertEquals("Shiny Charm", itemName(226))
        // Retail's moves, types and chart: no Fairy type, CLEFAIRY stays Normal.
        assertEquals(listOf("Normal", "Normal"), activeSpeciesTypeData.getValue(35).let { listOf(typeName(it.type1), typeName(it.type2)) })
        assertNull(activeTypeNames.values.firstOrNull { it == "Fairy" })
        assertFalse(movesAreVanilla)
        // Rebalanced re-types nine species (NINETALES 38 is FIRE / GHOST there).
        use(NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8)
        assertEquals(SpeciesTypes(10, 7), activeSpeciesTypeData.getValue(38))
        use(NATIVE_RECHARGED_EMERALD_V2_2_8)
        assertEquals(SpeciesTypes(10, 10), activeSpeciesTypeData.getValue(38))
    }
}
