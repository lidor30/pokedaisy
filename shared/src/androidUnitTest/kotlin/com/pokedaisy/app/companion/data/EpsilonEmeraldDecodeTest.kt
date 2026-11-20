package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NATIVE_EPSILON_EMERALD (Pokémon Epsilon Emerald v1.0.0): retail Emerald's save, which it loads as it is (the
 * `epsilon_emerald` fixtures' READMEs). What each check expects is what the game's own party, bag and battle screens showed.
 */
class EpsilonEmeraldDecodeTest {
    private val cfg = NATIVE_EPSILON_EMERALD

    private fun use() {
        activeGame = GameKind.EMERALD
        emeraldHack = emeraldHackFor(cfg)
    }

    @After fun tearDown() {
        emeraldHack = null
    }

    @Test
    fun `the save's party, bag, money and place`() {
        use()
        val t = decodeNative("epsilon_emerald", cfg)
        // Its party screen: KADABRA Lv36, MANECTRIC Lv36, TRAPINCH Lv27, BLAZIKEN Lv44, PELIPPER Lv43, DOTS (a SEEDOT) Lv11.
        assertEquals(
            listOf("Kadabra" to 36, "Manectric" to 36, "Trapinch" to 27, "Blaziken" to 44, "Pelipper" to 43, "Seedot" to 11),
            t.party.map { speciesName(it.species) to it.level },
        )
        assertEquals(listOf(81, 108, 65, 145, 120, 30), t.party.map { it.maxHp })
        val bag = t.items.associate { itemName(it.itemId) to (it.quantity to it.pocket) }
        // Its bag screen, pocket by pocket: the Items pocket ends at SILK SCARF (27), the old key items past it left out.
        assertEquals(27, t.items.count { it.pocket == POCKET_ITEMS })
        assertEquals(2 to POCKET_ITEMS, bag["Potion"])
        assertEquals(1 to POCKET_ITEMS, bag["Silk Scarf"])
        assertEquals(listOf("Great Ball" to 6, "Poké Ball" to 2, "Premier Ball" to 1, "Nest Ball" to 3, "Ultra Ball" to 6),
            t.items.filter { it.pocket == POCKET_POKE_BALLS }.map { itemName(it.itemId) to it.quantity })
        // TMs & HMs: TM05 .. TM50 (18) and HM01 .. HM06, no counts.
        val tms = t.items.filter { it.pocket == POCKET_TM_HM }.map { itemName(it.itemId) }
        assertEquals(24, tms.size)
        assertEquals(listOf("TM05", "TM08", "TM09"), tms.take(3))
        assertEquals(listOf("TM50", "HM01", "HM02", "HM03", "HM04", "HM05", "HM06"), tms.takeLast(7))
        assertEquals(4 to POCKET_BERRIES, bag["Cheri Berry"])
        assertEquals(listOf("Wailmer Pail", "Dowsing Mchn", "Soot Sack", "Good Rod", "Devon Scope", "Magma Emblem", "Fame Checker",
            "Mega Bracelet"), t.items.filter { it.pocket == POCKET_KEY_ITEMS }.map { itemName(it.itemId) })
        assertPocketsInRange(t)
        assertEquals(185092L, t.money)
        assertEquals(0, t.playerGender)
        assertFalse(t.inBattle)
        // ROUTE 114: Emerald's map ids and section.
        assertEquals(0 to 29, t.mapGroup to t.mapNum)
        assertEquals(0x1D, t.regionMapSectionId)
    }

    @Test
    fun `a scripted wild battle`() {
        use()
        val ram = FixtureMemoryReader.load("epsilon_emerald_battle")
        val t = decodeNative("epsilon_emerald_battle", cfg)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("Kadabra" to 36, speciesName(me.species) to me.level)
        assertEquals(81 to 81, me.hp to me.maxHp)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Zigzagoon" to 5, speciesName(foe.species) to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        assertTrue(t.enemyParty.isEmpty())
        assertEquals(listOf(288 to 5), t.battleFoes.map { it.species to it.level })
    }

    @Test
    fun `its names, types and chart`() {
        use()
        assertEquals("Obstagoon", speciesName(445))
        assertEquals("Roserade", speciesName(412))
        assertEquals("Roost", lookupMove(355).name)
        assertEquals("Hydro Steam", lookupMove(828).name)
        assertEquals("Mega Bracelet", itemName(348))
        assertEquals("Fairy", typeName(18))
        assertEquals("Ice Oasis", activeMapSecData[75]?.name)
        assertFalse(movesAreVanilla)
        fun types(species: Int) = activeSpeciesTypeData.getValue(species).let { typeName(it.type1) to typeName(it.type2) }
        assertEquals("Fairy" to "Fairy", types(35))     // CLEFAIRY
        assertEquals("Grass" to "Poison", types(1))
        // Its chart: Fairy beats Dragon, Dragon doesn't touch it.
        assertEquals(200, activeTypeEffectiveness[18 * 100 + 16])
        assertEquals(0, activeTypeEffectiveness[16 * 100 + 18])
    }

    @Test
    fun `DEX - the save's Hoenn counts and its pages`() {
        use()
        val t = POKEDEX_EPSILON_EMERALD
        assertEquals(t, cfg.pokedex)
        val dex = readPokedexState(FixtureMemoryReader.load("epsilon_emerald"), cfg, t)!!
        assertFalse(dex.national)   // retail Emerald's save never got the National Dex
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Epsilon Emerald (v1.0.0).gba") ?: return
        PokedexSource.reader = rom
        assertTrue(pokedexMatchesRom(rom, t))
        // Its Hoenn Pokédex on this save: Seen 083, Own 028 (the game's own screen, headless).
        val hoenn = PokedexSource.regionalOrder(t)!!.toList()
        assertEquals(241, hoenn.size)
        assertEquals(252 to 386, hoenn.first() to hoenn.last())   // TREECKO .. DEOXYS
        assertEquals(83 to 28, hoenn.count { it in dex.seen } to hoenn.count { it in dex.caught })
        // Its own national numbers: No. 412 is GALLADE (species 430), No. 440 its EVOLOTTO; 423-439 unused.
        assertEquals(430, PokedexSource.speciesFor(t, 412))
        assertEquals(276, PokedexSource.speciesFor(t, 440))
        assertEquals(0, PokedexSource.speciesFor(t, 430))
        val gallade = PokedexSource.entry(t, 412)!!
        assertEquals("Blade" to (16 to 520), gallade.category to (gallade.heightDm to gallade.weightHg))
        assertTrue(gallade.description.startsWith("A master of courtesy and swordsmanship"))
        val bulbasaur = PokedexSource.entry(t, 1)!!
        assertEquals("Seed" to (7 to 69), bulbasaur.category to (bulbasaur.heightDm to bulbasaur.weightHg))
        assertEquals(listOf("Overgrow") to "Chlorophyll", bulbasaur.abilities to bulbasaur.hiddenAbility)
        assertEquals(12 to 3, bulbasaur.type1 to bulbasaur.type2)
        // EVOLVE: BULBASAUR at 16; KIRLIA -> GALLADE with a DAWN STONE, male; its LINOONE form -> OBSTAGOON at 35 by night;
        // its FARFETCH'D form after three critical hits; KOFFING -> its WEEZING form holding a SWEET TEA.
        assertTrue(EvoLink(1, 2, listOf(EvoReq.Level(16))) in DexDetails.family(t, 1)!!)
        assertTrue(EvoLink(393, 430, listOf(EvoReq.UseItem(99), EvoReq.Gender(female = false))) in DexDetails.family(t, 430)!!)
        assertTrue(EvoLink(506, 445, listOf(EvoReq.Level(35), EvoReq.Time(EvoTime.NIGHT))) in DexDetails.family(t, 445)!!)
        assertTrue(EvoLink(497, 441, listOf(EvoReq.Crits(3))) in DexDetails.family(t, 441)!!)
        assertTrue(EvoLink(109, 498, listOf(EvoReq.LevelUp, EvoReq.Hold(88))) in DexDetails.family(t, 109)!!)
        // MOVES: BULBASAUR's level-up list (TACKLE 1, GROWL 3, LEECH SEED 7 ...); no TMs (not located).
        val moves = DexDetails.levelUp(t, cfg.guideTables, 1)!!
        assertEquals(listOf(LevelMove(1, 33), LevelMove(3, 45), LevelMove(7, 73)), moves.take(3))
        assertFalse(DexDetails.hasTeachable(t))
    }

    @Test
    fun `GUIDE - HERE's wild Pokemon, no NEXT BOSS`() {
        use()
        val g = cfg.guideTables!!
        assertEquals(GuideId.EPSILON_EMERALD, g.guide)
        assertTrue(gameGuide(GuideId.EPSILON_EMERALD)!!.bosses.isEmpty())
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Epsilon Emerald (v1.0.0).gba") ?: return
        PokedexSource.reader = rom
        assertTrue(guideTablesMatchRom(rom, g))
        // ROUTE 101 (0.16): WURMPLE, POOCHYENA and ZIGZAGOON at Lv2-3 in the grass, no water.
        val r101 = GuideRomSource.encounters(g, 0, 16)!!
        assertEquals(setOf(290, 286, 288), r101.grass.map { it.species }.toSet())
        assertEquals(2 to 3, r101.grass.minOf { it.minLevel } to r101.grass.maxOf { it.maxLevel })
        assertTrue(r101.water.isEmpty())
        // ROUTE 102 (0.17) has water and fishing; the save's ROUTE 114 (0.29) has grass.
        val r102 = GuideRomSource.encounters(g, 0, 17)!!
        assertTrue(r102.water.isNotEmpty() && r102.oldRod.isNotEmpty())
        assertTrue(GuideRomSource.encounters(g, 0, 29)!!.grass.isNotEmpty())
        // AREA: ZIGZAGOON in ROUTE 101's grass (map section 0x10).
        assertTrue(MapSections.known(g))
        assertTrue(DexDetails.catchSpots(g, 288)!!.any { it.mapsec == 0x10 && it.method == WildMethod.GRASS })
    }

    @Test
    fun `PC anywhere and FLY - its table matches the ROM, no bag USE`() {
        assertEquals(FIELD_ITEMS_EPSILON_EMERALD, cfg.fieldItems)
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Epsilon Emerald (v1.0.0).gba") ?: return
        val read = { a: Long, n: Int -> rom.readCoreMemory(a, n) }
        val support = fieldItemSupport(FIELD_ITEMS_EPSILON_EMERALD, read)!!
        assertTrue(support.canPc)
        assertTrue(support.canFly)
        assertFalse(support.canUse(86))   // a Repel: not from the bag
        // Its sMapHealLocations: Littleroot {0, 9, heal 1}, Rustboro heal 6, Route 101 {0, 16, heal 23}, its last row 0xEE.
        val rows = read(FIELD_ITEMS_EPSILON_EMERALD.fly!!.destinations, 0xEF * 3)
        fun row(i: Int) = (0..2).map { rows[i * 3 + it].toInt() and 0xFF }
        assertEquals(listOf(0, 9, 1), row(0))
        assertEquals(6, row(0x0A)[2])
        assertEquals(listOf(0, 16, 23), row(0x10))
        assertEquals(listOf(0, 62, 62), row(0xEE))
    }
}
