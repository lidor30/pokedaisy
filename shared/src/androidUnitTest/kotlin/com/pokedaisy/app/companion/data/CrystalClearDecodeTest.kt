package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Crystal Clear (Game Boy Color, a hack of Crystal) on a new game played headless in v2.6.0 - see the
 * crystal_clear fixtures' READMEs: the save booted in v2.6.0 (crystal_clear) and v2.5.11 (crystal_clear_v2_5_11), a
 * wild battle in each (crystal_clear_battle, crystal_clear_v2_5_11_battle, crystal_clear_battle_menu) and MOM's trainer
 * battle from the new game (crystal_clear_trainer).
 */
class CrystalClearDecodeTest {
    private fun cfgFor(fixture: String) = if ("v2_5_11" in fixture) GEN2_CRYSTAL_CLEAR_V2_5_11 else GEN2_CRYSTAL_CLEAR_V2_6_0

    private fun read(fixture: String): Telemetry {
        activeGame = GameKind.CRYSTAL_CLEAR
        romLanguage = 'E'
        romGameCode = ""
        activeGen2 = cfgFor(fixture)
        return readGen2Telemetry(FixtureMemoryReader.load(fixture), cfgFor(fixture))
    }

    @Test
    fun `party - Crystal's struct, its own starter moves`() {
        for (fixture in listOf("crystal_clear", "crystal_clear_v2_5_11")) {
            val t = read(fixture)
            assertFalse(fixture, t.inBattle)
            assertEquals(fixture, listOf("BULBASAUR", "SENTRET"), t.party.map { speciesName(it.species) })
            assertEquals(fixture, listOf("BULBASAUR", "SENTRET"), t.party.map { it.nickname })
            val bulbasaur = t.party[0]
            assertEquals(1 to 5, bulbasaur.species to bulbasaur.level)
            assertEquals(21 to 21, bulbasaur.hp to bulbasaur.maxHp)
            assertEquals(listOf("TACKLE", "GROWL", "PETAL DANCE", "SAFEGUARD"), bulbasaur.moves.map { lookupMove(it).name })
            assertEquals("MIRACLE SEED", itemName(bulbasaur.heldItem))
            assertEquals(GENDER_SYMBOL_MALE, bulbasaur.genderSymbol)   // the battle shows ♂
            val sentret = t.party[1]
            assertEquals(161 to 2, sentret.species to sentret.level)
            assertEquals(14 to 14, sentret.hp to sentret.maxHp)
            assertEquals(GENDER_SYMBOL_FEMALE, sentret.genderSymbol)   // ♀ in the battle it was caught in
        }
    }

    @Test
    fun `money, pockets, its own items and places, the moved map ids and caught flags`() {
        for (fixture in listOf("crystal_clear", "crystal_clear_v2_5_11")) {
            val t = read(fixture)
            assertEquals(1500L, t.money)   // ¥3000, half lost to MOM
            assertEquals(listOf("POTION" to 1, "POKé BALL" to 4, "HOUSE KEY" to 1, "DEX TRACKER" to 1),
                t.items.map { itemName(it.itemId) to it.quantity })
            assertEquals(listOf(POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_KEY_ITEMS, POCKET_KEY_ITEMS), t.items.map { it.pocket })
            // wMapGroup / wMapNumber at DA3A (Crystal's DCB5 holds something else there), the coordinates at Crystal's DCB7.
            assertEquals(24 to 3, t.mapGroup to t.mapNum)
            assertEquals(45 to 11, t.x to t.y)
            assertEquals("ROUTE 29", lookupLocation(t.regionMapSectionId).mapSecName)
            assertEquals(0, t.playerGender)
            assertEquals((3 * 60L + 56) * 60 + 15, t.frameCounter)   // 0:03:56, frame 15
            val dex = t.pokedex!!
            assertEquals(setOf(1, 161), dex.caught)   // DA0E
            assertEquals(setOf(1, 97, 161), dex.seen)   // MOM's HYPNO too
        }
    }

    @Test
    fun `its own landmarks - places past FAST SHIP on the region their stand-in names`() {
        activeGame = GameKind.CRYSTAL_CLEAR
        assertEquals(117, activeMapSecData.size)
        assertEquals("VIRIDIAN FOREST" to 1, activeMapSecData.getValue(0x60).let { it.name to it.region })
        assertEquals("CHERRYGROVE BAY" to 0, activeMapSecData.getValue(0x65).let { it.name to it.region })
        assertEquals(-1, activeMapSecData.getValue(0x74).region)   // ORIGIN PLAINS: no stand-in, on no map
        // Its houses: NEW BARK's maps keep Crystal's numbers, the player's room is one of the maps it added (24.14).
        assertEquals("NEW BARK TOWN", lookupLocation(gen2Landmark(GEN2_CRYSTAL_CLEAR_V2_6_0, 24, 4, 0, 0)).mapSecName)
        assertEquals("NEW BARK TOWN", lookupLocation(gen2Landmark(GEN2_CRYSTAL_CLEAR_V2_6_0, 24, 14, 0, 0)).mapSecName)
        // Species, moves and types are Crystal's (gen_gen2_tables.py checks them byte for byte).
        assertEquals("CELEBI", speciesName(251))
        assertEquals("DARK", typeName(lookupMove(44).type))   // BITE
    }

    @Test
    fun `battle - both battlers, in both versions`() {
        for (fixture in listOf("crystal_clear_battle", "crystal_clear_v2_5_11_battle")) {
            val t = read(fixture)
            assertTrue(fixture, t.inBattle)
            val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
            assertEquals(1 to 5, me.species to me.level)
            assertEquals("GRASS", typeName(me.type1))
            assertEquals("POISON", typeName(me.type2))
            assertEquals("MIRACLE SEED", itemName(me.item))
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            assertEquals("PIDGEY" to 2, speciesName(foe.species) to foe.level)
            assertEquals("FLYING", typeName(foe.type2))
            assertTrue(t.enemyParty.isEmpty())   // a wild battle has no FOE TEAM
        }
    }

    @Test
    fun `trainer battle - MOM's team of six`() {
        val t = read("crystal_clear_trainer")
        assertTrue(t.inBattle)
        assertEquals(listOf("HYPNO", "SUNFLORA", "SANDSLASH", "BLISSEY", "MANTINE", "TOGETIC"), t.enemyParty.map { speciesName(it.species) })
        assertEquals(0, t.enemyActive)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("HYPNO" to 81, speciesName(foe.species) to foe.level)
        assertEquals(listOf("PSYCHIC", "FIRE PUNCH", "PURSUIT", "THUNDERBOLT"), foe.moves.map { lookupMove(it).name })
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 0, readGen2BattleInput(FixtureMemoryReader.load("crystal_clear_trainer"), GEN2_CRYSTAL_CLEAR_V2_6_0))
    }

    @Test
    fun `battle menus - Crystal's, where Crystal has them`() {
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, readGen2BattleInput(FixtureMemoryReader.load("crystal_clear_battle"), GEN2_CRYSTAL_CLEAR_V2_6_0))
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1,
            readGen2BattleInput(FixtureMemoryReader.load("crystal_clear_v2_5_11_battle"), GEN2_CRYSTAL_CLEAR_V2_5_11))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen2BattleInput(FixtureMemoryReader.load("crystal_clear_battle_menu"), GEN2_CRYSTAL_CLEAR_V2_6_0))
        assertEquals(BATTLE_INPUT_NONE to -1, readGen2BattleInput(FixtureMemoryReader.load("crystal_clear"), GEN2_CRYSTAL_CLEAR_V2_6_0))
    }

    @Test
    fun `growth rates and curves - Crystal's`() {
        val t = read("crystal_clear")
        assertEquals(135L, expProgress(1, 5, t.party[0].exp)!!.levelStart)   // given at Lv5 with Medium Slow's total
        assertEquals(t.party[1].exp, expProgress(161, 2, t.party[1].exp)!!.levelStart)   // caught at Lv2
    }

    @Test
    fun `detected by SHA-1 - each version's own tables`() {
        assertEquals(GEN2_CRYSTAL_CLEAR_V2_6_0, TelemetrySampler.crystalClearConfigFor(TelemetrySampler.CRYSTAL_CLEAR_V2_6_0_COLOR_FILTER_SHA1))
        assertEquals(GEN2_CRYSTAL_CLEAR_V2_5_11, TelemetrySampler.crystalClearConfigFor(TelemetrySampler.CRYSTAL_CLEAR_V2_5_11_SHA1))
        assertTrue(TelemetrySampler.CRYSTAL_CLEAR_SHA1S.all { it in TelemetrySampler.SUPPORTED_GB_SHA1S })
        assertEquals(null, TelemetrySampler.crystalClearConfigFor(TelemetrySampler.CRYSTAL_REV1_SHA1))
    }
}
