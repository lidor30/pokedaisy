package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Elite Redux 2.65.4.1b and 2.65.3b: its packed plaintext party mons, its owned-flags bag, its battle struct and
 * menus, its own names (EliteReduxGen.kt). The one save on file loads in both builds; what the game's own screens show
 * is in the fixtures' READMEs.
 */
class EliteReduxDecodeTest {
    private val builds = listOf(
        Triple("elite_redux", NATIVE_ELITE_REDUX_V2_65_4_1B, false),
        Triple("elite_redux_v2653", NATIVE_ELITE_REDUX_V2_65_3B, true),
    )

    private fun use(v2653: Boolean) {
        activeGame = GameKind.ELITE_REDUX
        eliteRedux265_3 = v2653
    }

    @After fun tearDown() {
        activeGame = GameKind.FIRERED
        eliteRedux265_3 = false
    }

    @Test
    fun `the save - party, bag and money in both builds`() {
        for ((key, cfg, v2653) in builds) {
            use(v2653)
            val t = decodeNative(key, cfg)
            assertEquals(key, listOf(644, 380, 1071, 1853, 608, 2669), t.party.map { it.species })
            assertEquals(listOf("Zekrom", "Latias", "Lucineon", "Lampent"), listOf(0, 1, 2, 4).map { speciesName(t.party[it].species) })
            // Its 12-byte nickname at +0x26.
            assertEquals(key, listOf("Zekrom", "Latias", "Lucineon", "Eevee", "Lampent", "Kirlia"), t.party.map { it.nickname })
            assertEquals(List(6) { 20 }, t.party.map { it.level })
            assertEquals(listOf(76, 68, 69, 62, 60, 59), t.party.map { it.maxHp })
            assertEquals(t.party.map { it.maxHp }, t.party.map { it.hp })
            // LUCINEON's summary: its moves and PP, exp, Sassy.
            val luc = t.party[2]
            assertEquals(listOf("Nature Power", "Vine Whip", "Heal Bell", "Mirage Slam"), luc.moves.map { lookupMove(it).name })
            assertEquals(listOf(24, 24, 8, 16), luc.pp.toList())
            assertEquals(10000L, luc.exp)
            assertEquals(22, luc.stats!!.nature)
            // The bag: owned items, no counts; ER's eight pockets in the app's five.
            val byPocket = t.items.groupingBy { it.pocket }.eachCount()
            assertEquals(mapOf(POCKET_ITEMS to 106, POCKET_BERRIES to 47, POCKET_POKE_BALLS to 27, POCKET_KEY_ITEMS to 3), byPocket)
            assertTrue(t.items.all { it.quantity == 1 })
            assertEquals(999_900L, t.money)
            assertFalse(t.inBattle)
            assertEquals(10, t.regionMapSectionId)   // Rustboro City
            assertEquals("Rustboro City", activeMapSecData[10]!!.name)
        }
    }

    @Test
    fun `a scripted wild battle - both builds`() {
        for ((key, cfg, v2653) in listOf(
            Triple("elite_redux_battle", NATIVE_ELITE_REDUX_V2_65_4_1B, false),
            Triple("elite_redux_v2653_battle", NATIVE_ELITE_REDUX_V2_65_3B, true),
        )) {
            use(v2653)
            val ram = FixtureMemoryReader.load(key)
            val t = decodeNative(key, cfg)
            assertTrue(t.inBattle)
            val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
            assertEquals(Triple(644, 20, 76), Triple(me.species, me.level, me.hp))
            assertEquals(76, me.maxHp)
            assertEquals(16 to 13, me.type1 to me.type2)   // DRAGON / ELECTRIC
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            assertEquals("Zigzagoon" to 5, speciesName(foe.species) to foe.level)
            assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
            // The wild foe in gEnemyParty, the same packed struct.
            val wild = decodePartyMon(ram.readCoreMemory(cfg.enemyParty, cfg.monStride), 0, cfg.partyMonLayout)!!
            assertEquals(263 to 5, wild.species to wild.level)
        }
    }

    @Test
    fun `a trainer battle - the FOE TEAM, the foe's details, the next foe`() {
        for ((key, cfg, v2653) in listOf(
            Triple("elite_redux_trainer", NATIVE_ELITE_REDUX_V2_65_4_1B, false),
            Triple("elite_redux_v2653_trainer", NATIVE_ELITE_REDUX_V2_65_3B, true),
        )) {
            use(v2653)
            val t = decodeNative(key, cfg)
            assertTrue(key, t.inBattle)
            // The randomizer save's ROXANNE: five Lv20s, ANORITH out.
            assertEquals(key, listOf(347, 345, 222, 2503, 299), t.enemyParty.map { it.species })
            assertTrue(t.enemyParty.all { it.level == 20 })
            assertEquals(0, t.enemyActive)
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            assertEquals(347 to (54 to 54), foe.species to (foe.hp to foe.maxHp))
            assertEquals(52, foe.ability)   // HYPER CUTTER (its innates follow it)
            assertEquals(287, foe.item)     // FOCUS SASH
            assertEquals(listOf(49, 37, 45, 24, 31), foe.stats!!.toList())
            assertTrue(foe.statStages!!.all { it == 6 })
            assertEquals(13, t.enemyParty[0].stats!!.nature)   // Jolly
        }
        // ANORITH fainted, NOSEPASS (slot 4) out; Zekrom's BEAST BOOST raised its Attack one stage.
        use(false)
        val next = decodeNative("elite_redux_trainer_next", NATIVE_ELITE_REDUX_V2_65_4_1B)
        assertEquals(4, next.enemyActive)
        assertEquals(0, next.enemyParty[0].hp)
        assertEquals(310, next.battleMons[BATTLE_POS_OPPONENT_LEFT].item)   // EVIOLITE
        assertEquals(7, next.battleMons[BATTLE_POS_PLAYER_LEFT].statStages!![0])
    }

    @Test
    fun `the dex flags - the title screen's POKéDEX 29`() {
        for ((key, cfg, v2653) in builds) {
            use(v2653)
            val dex = readPokedexState(FixtureMemoryReader.load(key), cfg, cfg.pokedex!!)!!
            assertEquals(29, dex.caught.size)
            assertTrue(1072 in dex.caught)   // LUCINEON, by its dex number
            assertTrue(dex.seen.containsAll(dex.caught))
        }
    }

    @Test
    fun `GUIDE - HERE's wild tables from the ROM`() {
        for ((file, cfg, v2653) in listOf(
            Triple("Pokemon Elite Redux (2.65.4.1b).gba", NATIVE_ELITE_REDUX_V2_65_4_1B, false),
            Triple("Pokemon Elite Redux (2.65.3b).gba", NATIVE_ELITE_REDUX_V2_65_3B, true),
        )) {
            val rom = RomFileReader.load(RETAIL_ROM_DIR + file) ?: continue
            use(v2653)
            PokedexSource.reader = rom
            val t = cfg.guideTables!!
            assertTrue(file, guideTablesMatchRom(rom, t))
            // Route 101 (map 0.16): ZIGZAGOON in its grass, as the headless battle's.
            val route101 = GuideRomSource.encounters(t, 0, 16)!!
            assertTrue(file, route101.grass.any { it.species == 263 })
            assertTrue(file, route101.grass.all { it.species in speciesNamesEliteRedux && it.minLevel in 1..100 })
            assertTrue(GuideRomSource.wildMapKeys(t).size > 100)
        }
    }

    @Test
    fun `its own types and matchups`() {
        use(false)
        assertEquals("Fairy", activeTypeNames[18])
        assertEquals(SpeciesTypes(11, 18), activeSpeciesTypeData[1071])   // LUCINEON: WATER / FAIRY
        assertEquals(0, activeTypeEffectiveness[1618])     // DRAGON on FAIRY
        assertEquals(200, activeTypeEffectiveness[1816])   // FAIRY on DRAGON
        assertFalse(battleHasBag)
        assertFalse(bagHasCounts)
    }

    @Test
    fun `detected by hash, each build its own config`() {
        assertTrue(TelemetrySampler.ELITE_REDUX_V2_65_4_1B_SHA1 in TelemetrySampler.SUPPORTED_HACK_SHA1S)
        assertTrue(TelemetrySampler.ELITE_REDUX_V2_65_3B_SHA1 in TelemetrySampler.SUPPORTED_HACK_SHA1S)
    }
}
