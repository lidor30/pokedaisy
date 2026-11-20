package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Pokémon Wishes of Tomorrow v1.2.6 (pokeemerald-expansion 1.15.3): its encrypted party mons with expansion's packing, its
 * bag, its 0x88-byte battle mons (a Mega Evolved foe too), the FOE TEAM, its dex and guide, and its randomizer. The saves
 * are new games played headless; what the game's own screens showed is in the fixtures' READMEs.
 */
class WishesDecodeTest {
    private val cfg = NATIVE_WISHES

    @Before fun setUp() {
        activeGame = GameKind.WISHES
    }

    @After fun tearDown() {
        activeGame = GameKind.FIRERED
    }

    private fun rom(): RomFileReader? = RomFileReader.load(RETAIL_ROM_DIR + ROM)

    @Test
    fun `the save - party, bag, money and place`() {
        val t = decodeNative("wishes", cfg)
        assertEquals(listOf(155, 25, 6, 94, 448, 1581), t.party.map { it.species })
        assertEquals(listOf("Cyndaquil", "Pikachu", "Charizard", "Gengar", "Lucario"), t.party.take(5).map { speciesName(it.species) })
        assertEquals(listOf(12, 10, 36, 30, 25, 1), t.party.map { it.level })
        assertEquals(listOf(33, 29, 103, 77, 77, 11), t.party.map { it.maxHp })
        assertEquals(t.party.map { it.maxHp }, t.party.map { it.hp })
        // As its party menu draws them: Pikachu female, the rest male, the egg none.
        assertEquals(
            listOf(GENDER_SYMBOL_MALE, GENDER_SYMBOL_FEMALE, GENDER_SYMBOL_MALE, GENDER_SYMBOL_MALE, GENDER_SYMBOL_MALE, GENDER_SYMBOL_NONE),
            t.party.map { it.genderSymbol },
        )
        assertEquals(listOf(false, false, false, false, false, true), t.party.map { it.isEgg })
        val cyndaquil = t.party[0]
        assertEquals(listOf("Tackle", "Leer", "Smokescreen", "Ember"), cyndaquil.moves.map { lookupMove(it).name })
        assertEquals(listOf(35, 30, 20, 25), cyndaquil.pp.toList())
        assertEquals("Oran Berry", itemName(cyndaquil.heldItem))
        assertEquals(973L, cyndaquil.exp)   // Lv12 medium slow: its EXP bar from the ROM's growth rates
        assertEquals(973L, expProgress(cyndaquil.species, cyndaquil.level, cyndaquil.exp)!!.levelStart)
        val stats = cyndaquil.stats!!
        assertEquals(10, stats.nature)   // PID % 25, no Mint bits
        assertEquals(listOf(14, 31, 17, 5, 18, 27), stats.ivs)
        assertEquals(listOf(33, 18, 17, 23, 21, 20), stats.stats)
        assertEquals("Charizardite X", itemName(t.party[2].heldItem))
        // Its bag, as the bag screen lists each pocket.
        fun pocket(p: Int) = t.items.filter { it.pocket == p }.map { itemName(it.itemId) to it.quantity }
        assertEquals(listOf("Potion" to 5, "Super Potion" to 2, "Repel" to 2, "Rare Candy" to 1), pocket(POCKET_ITEMS))
        assertEquals(listOf("Poké Ball" to 10, "Great Ball" to 3, "Snag Ball" to 2), pocket(POCKET_POKE_BALLS))
        assertEquals(listOf("TM01" to 1), pocket(POCKET_TM_HM))
        assertEquals(listOf("Oran Berry" to 3), pocket(POCKET_BERRIES))
        assertTrue(pocket(POCKET_KEY_ITEMS).map { it.first }.containsAll(listOf("Bike", "Mega Ring", "Town Map")))
        assertPocketsInRange(t)
        assertEquals(3000L, t.money)
        assertFalse(t.inBattle)
        assertEquals(0xD1, t.regionMapSectionId)
        assertEquals("Munen Village", activeMapSecData[0xD1]!!.name)
    }

    @Test
    fun `a scripted wild battle - the battlers, the menus`() {
        val ram = FixtureMemoryReader.load("wishes_battle")
        val t = decodeNative("wishes_battle", cfg)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(Triple(155, 12, 33), Triple(me.species, me.level, me.hp))
        assertEquals(33, me.maxHp)
        assertEquals(11 to 11, me.type1 to me.type2)   // FIRE
        assertEquals(listOf(35, 30, 20, 25), me.pp.toList())
        assertEquals(66, me.ability)   // BLAZE
        assertEquals("Oran Berry", itemName(me.item))
        assertEquals(listOf(18, 17, 23, 21, 20), me.stats!!.toList())
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Zigzagoon" to 5, speciesName(foe.species) to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        val wild = decodePartyMon(ram.readCoreMemory(cfg.enemyParty, cfg.monStride), 0, cfg.partyMonLayout)!!
        assertEquals(263 to 5, (wild.species and cfg.speciesMask) to wild.level)
        assertTrue(t.enemyParty.isEmpty())   // a wild battle: no FOE TEAM
        assertEquals(0, ram.readCoreMemory(cfg.actionCursor, 1)[0].toInt())
        assertEquals(0, ram.readCoreMemory(cfg.moveCursor, 1)[0].toInt())
    }

    @Test
    fun `a trainer battle - the FOE TEAM, a Mega Evolved foe, the next foe`() {
        val t = decodeNative("wishes_trainer", cfg)
        assertTrue(t.inBattle)
        // GENERAL EDWARDS' six, TYRANITAR out holding its Mega Stone.
        assertEquals(listOf("Tyranitar", "Corviknight", "Blastoise", "Lucario", "Volcarona", "Falinks"), t.enemyParty.map { speciesName(it.species) })
        assertEquals(listOf(41, 36, 38, 37, 39, 35), t.enemyParty.map { it.level })
        assertEquals(0, t.enemyActive)
        assertEquals(-1, t.enemyNext)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(248 to (142 to 142), foe.species to (foe.hp to foe.maxHp))
        assertEquals(45, foe.ability)   // SAND STREAM
        assertEquals("Tyranitarite", itemName(foe.item))
        assertTrue(foe.statStages!!.all { it == 6 })
        // A turn on it Mega Evolved: the battler and its party slot both read MEGA TYRANITAR's own id, named like the game.
        val mega = decodeNative("wishes_mega", cfg)
        val m = mega.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(926 to "Tyranitar", m.species to speciesName(m.species))
        assertEquals(926, mega.enemyParty[0].species)
        assertEquals(0, mega.battleMons[BATTLE_POS_PLAYER_LEFT].hp)   // EARTHQUAKE fainted the lead
        // Kenji's MACHOP fainted; he's about to send out MACHOKE (slot 2).
        val next = decodeNative("wishes_trainer_next", cfg)
        assertEquals(listOf("Machop", "Meditite", "Machoke"), next.enemyParty.map { speciesName(it.species) })
        assertEquals(0, next.enemyParty[0].hp)
        assertEquals(0, next.enemyActive)
        assertEquals(2, next.enemyNext)
    }

    @Test
    fun `the Pokédex - seen and caught, entries, the regional list`() {
        val dex = readPokedexState(FixtureMemoryReader.load("wishes"), cfg, cfg.pokedex!!)!!
        assertEquals(setOf(6, 25, 94, 155, 448), dex.seen)
        assertEquals(setOf(6, 25, 94, 155, 448), dex.caught)
        assertFalse(dex.national)
        val rom = rom()
        assumeTrue(rom != null)
        PokedexSource.reader = rom!!
        val t = POKEDEX_WISHES
        // Its dex screen: SEEN 1 / OWN 1 in the regional list - PIKACHU, No. 164.
        val order = PokedexSource.regionalOrder(t)!!
        assertEquals(214, order.size)
        assertEquals(252, order.first())
        assertEquals(dex.seen.filter { it in order }, listOf(25))
        assertEquals(163, order.indexOf(25))
        val cyndaquil = PokedexSource.entry(t, 155)!!
        assertEquals("Fire Mouse" to (5 to 79), cyndaquil.category to (cyndaquil.heightDm to cyndaquil.weightHg))
        assertEquals(listOf("Blaze"), cyndaquil.abilities)
        assertEquals("Flash Fire", cyndaquil.hiddenAbility)
        assertTrue(cyndaquil.description.startsWith("It flares flames from its back"))
        // EVOLVE and MOVES from gSpeciesInfo.
        val evolutions = DexDetails.evolutions(t)!!
        assertTrue(evolutions[155]!!.any { it.to == 156 && it.reqs == listOf(EvoReq.Level(14)) })
        assertTrue(evolutions.size > 400)
        assertTrue(evolutions.values.flatten().none { EvoReq.Special in it.reqs })
        assertEquals(listOf(LevelMove(1, 33), LevelMove(1, 43), LevelMove(6, 108)), DexDetails.levelUp(t, cfg.guideTables, 155)!!.take(3))
    }

    @Test
    fun `GUIDE - HERE from the ROM, trainers' default moves`() {
        val rom = rom()
        assumeTrue(rom != null)
        PokedexSource.reader = rom!!
        val t = cfg.guideTables!!
        assertTrue(guideTablesMatchRom(rom, t))
        assertEquals(GuideId.WISHES, guideId(GameKind.WISHES, t))
        val here = decodeNative("wishes_route", cfg)
        assertEquals(0 to 59, here.mapGroup to here.mapNum)
        assertEquals("Melting Mile", activeMapSecData[here.regionMapSectionId]!!.name)
        val route = GuideRomSource.encounters(t, 0, 59)!!
        assertEquals(
            setOf("Shinx", "Fletchling", "Sewaddle", "Sentret", "Igglybuff", "Ralts", "Caterpie", "Riolu"),
            route.grass.map { speciesName(it.species) }.toSet(),
        )
        assertEquals(100, route.grass.sumOf { it.percent })
        assertTrue(GuideRomSource.wildMapKeys(t).size > 100)
        // GENERAL EDWARDS' six; KENJI's MACHOP knows no moves of its own, so it gets its level-up ones.
        assertEquals(listOf("Tyranitar", "Corviknight", "Blastoise", "Lucario", "Volcarona", "Falinks"), GuideRomSource.party(t, 885)!!.map { speciesName(it.species) })
        assertTrue(GuideRomSource.party(t, 864)!![0].moves.isNotEmpty())
    }

    @Test
    fun `the randomizer - HERE swaps the wild Pokémon like the game`() {
        val rom = rom()
        assumeTrue(rom != null)
        PokedexSource.reader = rom!!
        val t = cfg.guideTables!!
        // The randomizer-off save meets the ROM's own; the randomizer-on one swaps them by its trainer ID.
        val off = readSaveProgress(FixtureMemoryReader.load("wishes_route"), cfg, t)
        assertNull(WishesRandomizer.wildSeed(off))
        val p = readSaveProgress(FixtureMemoryReader.load("wishes_random"), cfg, t)!!
        assertTrue(p.flag(WishesRandomizer.FLAG_RANDOMIZER) && p.flag(WishesRandomizer.FLAG_WILD) && p.flag(WishesRandomizer.FLAG_TRAINERS))
        assertEquals(0x0E68C089L, WishesRandomizer.wildSeed(p))
        // What the game's own swap function (0x0823ECF4) returned for this save, called headless.
        val info = SpeciesInfoView.read(POKEDEX_WISHES.speciesInfo, POKEDEX_WISHES.speciesCount, 0x104) { a, n -> rom.readCoreMemory(a, n) }
        val seed = 0x0E68C089L
        for ((from, to) in listOf(1 to 0xE1, 4 to 0x579, 7 to 0x1CA, 25 to 0x131, 155 to 0x36D, 263 to 0x9A, 261 to 0x25D,
            300 to 0x308, 448 to 0x2E9, 1000 to 0xA1, 1025 to 0x259, 1300 to 0x22A, 1580 to 0x518, 1581 to 0x62D, 0 to 0)) {
            assertEquals("species $from", to, WishesRandomizer.species(from, seed, info))
        }
        // HERE on that save: MELTING MILE's grass swapped (and same picks merged).
        decodeNative("wishes_random", cfg)   // the sampler sets the save's swaps
        assertEquals(seed, GuideRandomizer.wildSeed)
        val route = GuideRomSource.encounters(t, 0, 59)!!
        assertEquals(
            setOf("Wailord", "Celebi", "Yanmega", "Stantler", "Porygon2", "Quilladin", "Golem", "Parasect"),
            route.grass.map { speciesName(it.species) }.toSet(),
        )
        assertEquals(100, route.grass.sumOf { it.percent })
        // The DEX's AREA follows: WAILORD is out on MELTING MILE for this save.
        assertTrue(DexDetails.catchSpots(t, 321)!!.any { it.mapsec == 0xD3 })
        // Back on the randomizer-off save: the ROM's own again.
        decodeNative("wishes_route", cfg)
        assertNull(GuideRandomizer.wildSeed)
        assertTrue(GuideRomSource.encounters(t, 0, 59)!!.grass.any { speciesName(it.species) == "Shinx" })
    }

    @Test
    fun `its own types and matchups`() {
        assertEquals("Fairy", activeTypeNames[19])
        assertEquals(SpeciesTypes(11, 3), activeSpeciesTypeData[6])   // CHARIZARD: FIRE / FLYING
        assertEquals(0, activeTypeEffectiveness[1719])     // DRAGON on FAIRY
        assertEquals(200, activeTypeEffectiveness[1917])   // FAIRY on DRAGON
        assertEquals("Snag Ball", activeItemNames[918])
        assertEquals("Browt", speciesName(1574))
        assertEquals(1581, cfg.eggSpecies)
    }

    @Test
    fun `detected by hash`() {
        assertTrue(TelemetrySampler.WISHES_OF_TOMORROW_V1_2_6_SHA1 in TelemetrySampler.SUPPORTED_HACK_SHA1S)
    }

    private companion object {
        const val ROM = "Pokemon Wishes of Tomorrow (v1.2.6).gba"
    }
}
