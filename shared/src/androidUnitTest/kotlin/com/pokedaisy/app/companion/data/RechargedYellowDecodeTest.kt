package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NATIVE_RECHARGED_YELLOW_*: every release decodes the same save (the user's v1.8.0 one, which the later
 * releases load as is) from its own addresses - the fixtures' READMEs say how each was captured. What each
 * expects is what the game's own party, bag, trainer card and battle screens showed headless.
 */
class RechargedYellowDecodeTest {
    private val releases = listOf(
        "recharged_yellow_v180" to NATIVE_RECHARGED_YELLOW_V1_8_0,
        "recharged_yellow_v190" to NATIVE_RECHARGED_YELLOW_V1_9_0,
        "recharged_yellow_v194" to NATIVE_RECHARGED_YELLOW_V1_9_4,
        "recharged_yellow" to NATIVE_RECHARGED_YELLOW_V1_9_7,
    )

    @Test
    fun `party, bag, money and location in every release`() {
        activeGame = GameKind.RECHARGED_YELLOW
        for ((key, cfg) in releases) {
            val t = decodeNative(key, cfg)
            assertEquals(
                key,
                listOf("WARTORTLE" to 16, "CHARMELEON" to 24, "VULPIX" to 12, "PIKACHU" to 24, "SANDSHREW" to 13, "BULBASAUR" to 10),
                t.party.map { speciesName(it.species) to it.level },
            )
            assertEquals(key, listOf(48, 65, 34, 54, 36, 32), t.party.map { it.maxHp })
            assertTrue(key, t.party.all { it.hp == it.maxHp && it.status == 0L })
            val wartortle = t.party[0]
            assertEquals(3008L, wartortle.exp)
            // Its title-cased move names, FireRed's ids.
            assertEquals(listOf("Tail Whip", "Bubble", "Withdraw", "Water Gun"), wartortle.moves.map { lookupMove(it).name })
            assertEquals(GENDER_SYMBOL_MALE, wartortle.genderSymbol)
            assertEquals(GENDER_SYMBOL_FEMALE, t.party[2].genderSymbol)
            // Vanilla IVs / EVs / natures: the stored stats recompute from its (rebalanced) base stats.
            val s = wartortle.stats!!
            assertEquals(listOf(21, 1, 17, 5, 27, 30), s.ivs)
            assertEquals(listOf(3, 1, 16, 6, 1, 0), s.evs)
            assertEquals(8, s.nature) // Impish
            assertEquals(listOf(48, 25, 36, 24, 27, 35), s.stats)
            // FireRed's growth rates and EXP tables: medium slow, Lv16 starts at 2535, Lv17 at 3120.
            assertEquals(ExpProgress(3008L, 2535L, 3120L), expProgress(wartortle.species, wartortle.level, wartortle.exp))

            val bag = t.items.associate { itemName(it.itemId) to (it.quantity to it.pocket) }
            assertEquals(49, t.items.size) // 18 items and medicine, 2 balls, 12 TMs / HMs, 9 berries, 8 key items
            assertEquals(1 to POCKET_ITEMS, bag["Escape Rope"])
            assertEquals(6 to POCKET_ITEMS, bag["Lure"])
            // Its Medicine pocket folds into ITEMS.
            assertEquals(13 to POCKET_ITEMS, bag["Super Potion"])
            assertEquals(26 to POCKET_POKE_BALLS, bag["Poké Ball"])
            assertEquals(1 to POCKET_TM_HM, bag["HM01"])
            assertEquals(6 to POCKET_BERRIES, bag["Leppa Berry"])
            assertEquals(1 to POCKET_KEY_ITEMS, bag["Town Map"])
            assertEquals(1 to POCKET_KEY_ITEMS, bag["Autograph"])
            assertPocketsInRange(t)

            assertEquals(3 to 1, t.mapGroup to t.mapNum)
            assertEquals(0x59, t.regionMapSectionId)
            assertEquals("Viridian City", activeMapSecData[t.regionMapSectionId]!!.name)
            // No encryption key: the money is plain, the trainer card's.
            assertEquals(4804L, t.money)
            assertEquals(1, t.playerGender)
            assertFalse(t.inBattle)
        }
    }

    @Test
    fun `a scripted wild battle in every release`() {
        activeGame = GameKind.RECHARGED_YELLOW
        for ((key, cfg) in releases) {
            val ram = FixtureMemoryReader.load(key + "_battle")
            val t = decodeNative(key + "_battle", cfg)
            assertTrue(key, t.inBattle)
            assertFalse(t.isDoubleBattle)
            val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
            assertEquals(key, "WARTORTLE" to 16, speciesName(me.species) to me.level)
            assertEquals(48 to 48, me.hp to me.maxHp)
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            assertEquals(key, "RATTATA" to 3, speciesName(foe.species) to foe.level)
            assertEquals(listOf("Normal", "Normal"), listOf(typeName(foe.type1), typeName(foe.type2)))
            // The action menu waits (each release's own handler addresses).
            assertEquals(key, 0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
            // A wild battle: no FOE TEAM, but the foe's own struct for its stats.
            assertTrue(t.enemyParty.isEmpty())
            assertEquals(listOf(19 to 3), t.battleFoes.map { it.species to it.level })
        }
    }

    @Test
    fun `a trainer battle - Brock's NORMAL team`() {
        activeGame = GameKind.RECHARGED_YELLOW
        val t = decodeNative("recharged_yellow_trainer", NATIVE_RECHARGED_YELLOW_V1_9_7)
        assertTrue(t.inBattle)
        assertEquals(listOf("GEODUDE" to 12, "ONIX" to 14), t.enemyParty.map { speciesName(it.species) to it.level })
        assertEquals(0, t.enemyActive)
        assertEquals("GEODUDE", speciesName(t.battleMons[BATTLE_POS_OPPONENT_LEFT].species))
        // Its own moveset: Rock Tomb, FireRed's id 317.
        assertEquals("Rock Tomb", lookupMove(t.enemyParty[0].moves[2]).name)
    }

    @Test
    fun `its names come from its own tables, FireRed's ids`() {
        activeGame = GameKind.RECHARGED_YELLOW
        assertEquals("PIKACHU", speciesName(25))
        assertEquals("ThunderShock", lookupMove(84).name)
        assertEquals("Linking Cord", itemName(61))
        assertEquals("DexNav", itemName(407))
        assertEquals("Adamant Mint", itemName(378))
        assertEquals("Undergound Path", activeMapSecData[0x81]!!.name) // sic, the game's
        // FireRed's species types and chart: no Fairy type.
        assertEquals(listOf("Normal", "Normal"), activeSpeciesTypeData.getValue(35).let { listOf(typeName(it.type1), typeName(it.type2)) })
        assertNull(activeTypeNames.values.firstOrNull { it == "Fairy" })
    }
}
