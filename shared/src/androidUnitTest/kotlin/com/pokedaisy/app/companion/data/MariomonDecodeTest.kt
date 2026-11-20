package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NATIVE_MARIOMON_*: every release, each from headless captures of the user's v1.5.1 save (cut
 * to v1.3's SaveBlock1 for v1.3 / v1.2.2 / v1.2.1, see the fixtures' READMEs): the field in
 * WORLD 1-1 and a scripted wild GOOMBA at the action menu.
 */
class MariomonDecodeTest {
    private class Release(val key: String, val cfg: NativeConfig, val v13: Boolean)

    private val releases = listOf(
        Release("mariomon_v1_5_2", NATIVE_MARIOMON_V1_5_2, false),
        Release("mariomon_v1_5_1", NATIVE_MARIOMON_V1_5_1, false),
        Release("mariomon_v1_3", NATIVE_MARIOMON_V1_3, true),
        Release("mariomon_v1_2_2", NATIVE_MARIOMON_V1_2_2, true),
        Release("mariomon_v1_2_1", NATIVE_MARIOMON_V1_2_1, true),
    )

    private fun use(r: Release) {
        activeGame = GameKind.MARIOMON
        mariomonV13 = r.v13
    }

    @Test
    fun `party, money and location in every release`() {
        for (r in releases) {
            use(r)
            val t = decodeNative(r.key, r.cfg)
            assertEquals(r.key, 1, t.party.size)
            val mon = t.party[0]
            assertEquals(r.key, "BOB-OMB", activeSpeciesNames[mon.species])
            assertEquals(912, mon.species)
            assertEquals(6 to 30, mon.level to mon.hp)
            assertEquals(30, mon.maxHp)
            assertEquals(listOf("Tackle", "Charge", "Self-Destruct", "Ember"), mon.moves.filter { it != 0 }.map { lookupMove(it).name })
            assertEquals(listOf(35, 20, 5, 25), mon.pp.take(4))
            assertEquals(198L, mon.exp)
            assertEquals(GENDER_SYMBOL_FEMALE, mon.genderSymbol) // personality 0x1C < its ratio 31: the ♀ the party menu shows
            val stats = mon.stats!!
            assertEquals(1, stats.nature) // PID % 25: Lonely
            assertEquals(listOf(30, 15, 8, 9, 12, 9), stats.stats)
            assertEquals(SpeciesTypes(10, 10), activeSpeciesTypeData[912]) // Fire

            assertEquals(3100L, t.money)
            assertEquals(0x65, t.regionMapSectionId)
            assertEquals("World 1-1", activeMapSecData.getValue(t.regionMapSectionId).name)
            assertEquals(2 to 4, t.mapGroup to t.mapNum)
            assertEquals(25 to 23, t.x to t.y)
            assertFalse(t.inBattle)
        }
    }

    @Test
    fun `the bag - v1_5's Medicine pocket and the older five`() {
        for (r in releases) {
            use(r)
            val t = decodeNative(r.key, r.cfg)
            val bag = t.items.map { activeItemNames[it.itemId] to it.quantity }
            // The MUSHROOMS (item 28) live in v1.5's Medicine pocket, which the older saves don't have.
            val medicine = if (r.v13) emptyList() else listOf("Mushroom" to 2)
            assertEquals(r.key, medicine + listOf("Plumber Cap" to 10, "Hammer" to 1, "Escape Rope" to 1, "Exp. Share" to 1), bag)
            assertEquals(POCKET_POKE_BALLS, t.items.first { it.itemId == 1 }.pocket)
            assertEquals(3, t.items.count { it.pocket == POCKET_KEY_ITEMS })
            assertPocketsInRange(t)
        }
    }

    @Test
    fun `a scripted wild GOOMBA in every release`() {
        for (r in releases) {
            use(r)
            val ram = FixtureMemoryReader.load("${r.key}_battle")
            resetNativeBagCache()
            val t = readNativeTelemetry(ram, r.cfg)
            assertTrue(r.key, t.inBattle)
            assertFalse(t.isDoubleBattle)
            val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
            assertEquals(912 to 6, me.species to me.level)
            assertEquals(30 to 30, me.hp to me.maxHp)
            assertEquals(10 to 10, me.type1 to me.type2)
            assertEquals(listOf(35, 20, 5, 25), me.pp.take(4))
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            assertEquals("GOOMBA", activeSpeciesNames[foe.species])
            assertEquals(5 to 19, foe.level to foe.hp)
            assertEquals(0 to 0, foe.type1 to foe.type2)
            assertEquals(r.key, 0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, r.cfg))
            assertEquals(listOf(915), t.battleFoes.map { it.species })
            assertEquals(0, u16le(ram.readCoreMemory(r.cfg.battlerPartyIndexes, 2), 0))
            // gBattleStruct->monToSwitchIntoId: no switch pending.
            val bs = u32le(ram.readCoreMemory(r.cfg.battleStructPtr, 4), 0)
            assertEquals(6, ram.readCoreMemory(bs + r.cfg.monToSwitchIntoOff, 1)[0].toInt())
        }
    }

    @Test
    fun `dex flags - TATTLEDEX SEEN 2, OWN 1`() {
        for (r in releases) {
            val dex = readPokedexState(FixtureMemoryReader.load(r.key), r.cfg, r.cfg.pokedex!!)!!
            assertEquals(r.key, setOf(1, 7), dex.seen) // PIRANHA P., BOB-OMB
            assertEquals(setOf(7), dex.caught)
            assertFalse(dex.national)
        }
    }

    @Test
    fun `EXP to the next level uses its own growth rates`() {
        use(releases[1])
        val p = expProgress(912, 6, 198)!! // MEDIUM_SLOW
        assertEquals(179L to 236L, p.levelStart to p.nextLevel)
        assertEquals(125L, expProgress(915, 5, 125)!!.levelStart) // GOOMBA, MEDIUM_FAST
    }

    @Test
    fun `types - vanilla ids with Fairy 18`() {
        use(releases[1])
        assertEquals("Fairy", typeName(18))
        assertEquals(0, singleTypeMultiplierPct(16, 18)) // Dragon vs Fairy
        assertEquals(200, singleTypeMultiplierPct(10, 12)) // Fire vs Grass
        assertEquals(SpeciesTypes(12, 12), activeSpeciesTypeData[906]) // PIRANHA P.
    }
}
