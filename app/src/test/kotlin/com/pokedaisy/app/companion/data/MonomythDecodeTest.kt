package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pokémon Monomyth v1.0 (its own pokeemerald build, game code MONE): vanilla's 100-byte party mons under a key of its own,
 * its 250 flavour-only natures, its eight-pocket bag, its rearranged 0x58-byte battle mons and its battle menus, its own
 * names and EXP curves. The save is the user's (the monomyth fixture's README has what the game's own screens showed).
 */
class MonomythDecodeTest {
    private val cfg = NATIVE_MONOMYTH

    @Before fun setUp() {
        activeGame = GameKind.MONOMYTH
    }

    @After fun tearDown() {
        activeGame = GameKind.FIRERED
    }

    @Test
    fun `the save - party, bag, money and place`() {
        val t = decodeNative("monomyth", cfg)
        assertEquals(listOf(194, 183, 96, 72, 119, 106), t.party.map { it.species })
        assertEquals(listOf("Mishimizu", "Leonte", "Joltik", "Venipede", "Whismur", "Scraggy"), t.party.map { speciesName(it.species) })
        assertEquals(listOf(12, 9, 10, 10, 10, 9), t.party.map { it.level })
        assertEquals(listOf(12, 31, 31, 29, 35, 28), t.party.map { it.hp })
        assertEquals(listOf(37, 31, 31, 29, 35, 28), t.party.map { it.maxHp })
        // As its party menu draws them.
        assertEquals(
            listOf(GENDER_SYMBOL_MALE, GENDER_SYMBOL_FEMALE, GENDER_SYMBOL_MALE, GENDER_SYMBOL_FEMALE, GENDER_SYMBOL_FEMALE, GENDER_SYMBOL_MALE),
            t.party.map { it.genderSymbol },
        )
        assertTrue(t.party.none { it.isEgg })
        // MISHIMIZU, as its summary showed it.
        val lead = t.party[0]
        assertEquals(listOf("Scratch", "Metal Claw", "Assist", "Water Gun"), lead.moves.map { lookupMove(it).name })
        assertEquals(listOf(35, 35, 20, 19), lead.pp.toList())
        assertEquals(0, lead.heldItem)
        assertEquals(995L, lead.exp)
        assertEquals(266L, expProgress(lead.species, lead.level, lead.exp)!!.toNext)
        val stats = lead.stats!!
        assertEquals("Dreamy", natureName(stats.nature))
        assertNull(natureRaises(stats.nature))   // its natures change no stat
        assertEquals(listOf(37, 23, 17, 21, 20, 18), stats.stats)
        assertEquals(SpeciesTypes(11, 11), activeSpeciesTypeData[lead.species])   // WATER
        // The bag: eight pockets, Medicine and held items folded into the app's ITEMS.
        val bag = t.items.associate { itemName(it.itemId) to (it.quantity to it.pocket) }
        assertEquals(1 to POCKET_ITEMS, bag["Repel"])
        assertEquals(3 to POCKET_ITEMS, bag["Potion"])
        assertEquals(2 to POCKET_ITEMS, bag["Antidote"])
        assertEquals(1 to POCKET_ITEMS, bag["Fresh Water"])
        assertEquals(3 to POCKET_POKE_BALLS, bag["PokéBall"])
        assertEquals(1 to POCKET_POKE_BALLS, bag["Blu. Apricorn"])
        assertEquals(1 to POCKET_ITEMS, bag["Sharp Beak"])
        assertEquals(5 to POCKET_BERRIES, bag["Oran Berry"])
        assertEquals(POCKET_TM_HM, t.items.single { it.itemId == 369 }.pocket)
        assertPocketsInRange(t)
        assertEquals(1968L, t.money)
        assertFalse(t.inBattle)
        assertEquals(2 to 5, t.mapGroup to t.mapNum)
        assertEquals(6 to 3, t.x to t.y)
        assertEquals(0x01, t.regionMapSectionId)
        assertEquals("Catsear City", activeMapSecData[0x01]!!.name)
    }

    @Test
    fun `a scripted wild battle - the battlers, the menus`() {
        val ram = FixtureMemoryReader.load("monomyth_battle")
        val t = decodeNative("monomyth_battle", cfg)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(Triple(194, 12, 12), Triple(me.species, me.level, me.hp))
        assertEquals(37, me.maxHp)
        assertEquals(11 to 11, me.type1 to me.type2)
        assertEquals(listOf(35, 35, 20, 19), me.pp.toList())
        assertEquals(listOf(23, 17, 21, 20, 18), me.stats!!.toList())
        assertTrue(me.statStages!!.all { it == 6 })
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Poliwag" to 5, speciesName(foe.species) to foe.level)
        assertEquals(19 to 19, foe.hp to foe.maxHp)   // this capture's (a wild mon's IVs are random)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        assertTrue(t.enemyParty.isEmpty())   // a wild battle: no FOE TEAM
        assertEquals(listOf(21), t.battleFoes.map { it.species })
        assertEquals(0, ram.readCoreMemory(cfg.actionCursor, 1)[0].toInt())
        assertEquals(0, ram.readCoreMemory(cfg.moveCursor, 1)[0].toInt())
    }

    @Test
    fun `a trainer battle - the FOE TEAM`() {
        val t = decodeNative("monomyth_trainer", cfg)
        assertTrue(t.inBattle)
        // BUG CATCHER TIM's two, SPINARAK out.
        assertEquals(listOf("Spinarak", "Joltik"), t.enemyParty.map { speciesName(it.species) })
        assertEquals(listOf(3, 4), t.enemyParty.map { it.level })
        assertEquals(0, t.enemyActive)
        assertEquals(-1, t.enemyNext)   // monToSwitchIntoId isn't mapped
        assertEquals("Spinarak", speciesName(t.battleMons[BATTLE_POS_OPPONENT_LEFT].species))
        // SPINARAK fainted, JOLTIK out.
        val next = decodeNative("monomyth_trainer_next", cfg)
        assertEquals(0, next.enemyParty[0].hp)
        assertEquals(1, next.enemyActive)
        assertEquals("Joltik" to 4, next.battleMons[BATTLE_POS_OPPONENT_LEFT].let { speciesName(it.species) to it.level })
    }

    @Test
    fun `its own types, moves, items, natures and EXP curves`() {
        assertEquals("Fairy", activeTypeNames[18])
        assertEquals(0, activeTypeEffectiveness[1618])     // DRAGON on FAIRY
        assertEquals(200, activeTypeEffectiveness[1816])   // FAIRY on DRAGON
        assertEquals("Süßchen", speciesName(338))
        assertEquals(MoveInfo("Water Gun", 11, 40), activeMoveData[55])
        assertEquals("Master Ball", activeItemNames[1])
        assertEquals("Potion", activeItemNames[32])
        assertEquals(250, natureNamesMonomyth.size)
        assertEquals(190, monomythNature(0xD7F4F1BDL))
        // Medium fast is vanilla's to Lv70, steeper after.
        assertEquals(343000, expTablesMonomyth[3][70])
        assertEquals(1189244, expTablesMonomyth[3][100])
    }

    @Test
    fun `MAP - the ATHANTO MAP's sections and cursor grid`() {
        val map = activeRegionMap()!!
        assertEquals(listOf("monomyth"), map.images.toList())
        // CATSEAR CITY under the player's head (screen tile (4, 12), as its PokeNav draws it), ROUTE 4 just below.
        assertEquals(listOf(MapTiles(4, 12, 1, 1)), map.tilesOf(0x01))
        assertEquals(0x01, map.pick(0, 4, 12)!!.mapsec)
        assertEquals("Route 4", activeMapSecData[map.pick(0, 4, 13)!!.mapsec]!!.name)
        // PEAK OF KINGS has no rect of its own: the grid's corner cell places it.
        assertEquals(listOf(MapTiles(1, 2, 1, 1)), map.tilesOf(78))
    }

    @Test
    fun `its own key decrypts every party mon`() {
        val ram = FixtureMemoryReader.load("monomyth")
        val raw = ram.readCoreMemory(cfg.playerParty, 6 * cfg.monStride)
        // Vanilla's key fails the checksum (the decode falls back to a plaintext read, which isn't these mons).
        assertTrue((0 until 6).all { decodePartyMon(raw, it * 100, MONOMYTH_PARTY_MON) != null })
        assertTrue((0 until 6).none { decodePartyMon(raw, it * 100, VANILLA_PARTY_MON)?.species == decodePartyMon(raw, it * 100, MONOMYTH_PARTY_MON)!!.species })
    }

    @Test
    fun `DEX - the save's flags, Athanto's numbers, the pages`() {
        val dex = readPokedexState(FixtureMemoryReader.load("monomyth"), cfg, POKEDEX_MONOMYTH)!!
        // Its dex screen: SEEN 22 / OWN 11; the party's six (No.7, 15, 19, 33, 47, 50) owned.
        assertEquals(22 to 11, dex.seen.size to dex.caught.size)
        assertTrue(dex.caught.containsAll(listOf(7, 15, 19, 33, 47, 50)))
        assertTrue(1 in dex.seen && 1 !in dex.caught)   // No.001 MARMROOT, only seen
        assertTrue(dex.national)   // its one dex: nothing to unlock (no nationalMagic)
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Monomyth (v1.0).gba") ?: return
        PokedexSource.reader = rom
        assertTrue(pokedexMatchesRom(rom, POKEDEX_MONOMYTH))
        assertEquals(194, PokedexSource.speciesFor(POKEDEX_MONOMYTH, 7))
        assertEquals(176, PokedexSource.speciesFor(POKEDEX_MONOMYTH, 1))
        // No.007 MISHIMIZU's page, as the game shows it.
        val e = PokedexSource.entry(POKEDEX_MONOMYTH, 7)!!
        assertEquals("Rookie" to (5 to 91), e.category to (e.heightDm to e.weightHg))
        assertTrue(e.description.startsWith("Mishimizu love shiny copper objects"))
        assertEquals(listOf("Torrent"), e.abilities)
        assertEquals("Swift Swim", e.hiddenAbility)
        assertEquals(11 to 11, e.type1 to e.type2)
        val venipede = PokedexSource.entry(POKEDEX_MONOMYTH, 33)!!
        assertEquals("Centipede" to (4 to 53), venipede.category to (venipede.heightDm to venipede.weightHg))
        // EVOLVE: MISHIMIZU -> COPPESHU at Lv20; SNORUNT -> FROSLASS with a DAWN STONE, female.
        assertTrue(EvoLink(194, 195, listOf(EvoReq.Level(20))) in DexDetails.family(POKEDEX_MONOMYTH, 194)!!)
        assertTrue(DexDetails.family(POKEDEX_MONOMYTH, 48)!!.any { it.to == 50 && EvoReq.Gender(female = true) in it.reqs })
        // MOVES: its learnset, as the save's MISHIMIZU learned it (WATER GUN at 8, METAL CLAW at 11).
        val moves = DexDetails.levelUp(POKEDEX_MONOMYTH, cfg.guideTables, 194)!!
        assertTrue(LevelMove(8, 55) in moves && LevelMove(11, 229) in moves)
        // AREA: LEONTE in ROUTE 1's grass (map 0.12, its wild header's land set, Lv3-5).
        assertTrue(MapSections.known(cfg.guideTables!!))
        assertTrue(DexDetails.catchSpots(cfg.guideTables, 183)!!.any { it.mapsec == 0x10 && it.method == WildMethod.GRASS })
    }

    @Test
    fun `its packed party icons unpack`() {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Monomyth (v1.0).gba") ?: return
        assertTrue(cfg.monIconPacked)
        val rd = { a: Long, n: Int -> rom.readCoreMemory(a, n) }
        // MISHIMIZU, KOTORA and SÜSSCHEN, both frames: the bytes its own decoder writes (checked by drawing them).
        listOf(194 to 0xB8E0F95FL, 1 to 0x63B82C72L, 338 to 0x4CC7429EL).forEach { (species, crc) ->
            val tiles = MonomythIcons.unpack(rd, u32le(rd(cfg.monIconTable + species * 4L, 4), 0))!!
            assertEquals(species.toString(), crc, java.util.zip.CRC32().apply { update(tiles) }.value)
        }
    }

    @Test
    fun `PC anywhere - its table matches the ROM, no FLY or bag USE`() {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Monomyth (v1.0).gba") ?: return
        assertEquals(FIELD_ITEMS_MONOMYTH, cfg.fieldItems)
        val support = fieldItemSupport(FIELD_ITEMS_MONOMYTH) { a, n -> rom.readCoreMemory(a, n) }!!
        assertTrue(support.canPc)
        assertFalse(support.canFly)
        assertFalse(support.canUse(93))   // its Repel: not from the bag
    }

    @Test
    fun `detected by hash, under its own game code`() {
        assertTrue(TelemetrySampler.MONOMYTH_V1_0_SHA1 in TelemetrySampler.SUPPORTED_HACK_SHA1S)
        assertTrue(TelemetrySampler.MONOMYTH_CODE in TelemetrySampler.HACK_GAME_CODES)
    }

    // v1.1: the same game and RAM with v1.0's save, its ROM moved and its header's game code BPEE.
    private val v11 = NATIVE_MONOMYTH_V1_1

    @Test
    fun `v1_1 - v1_0's save reads the same`() {
        assertEquals(cfg.playerParty, v11.playerParty)
        val old = decodeNative("monomyth", cfg)
        val t = decodeNative("monomyth_v1_1", v11)
        assertEquals(old.party.map { it.species }, t.party.map { it.species })
        assertEquals(old.party.map { it.level to it.hp }, t.party.map { it.level to it.hp })
        assertEquals(old.party.map { it.moves.toList() }, t.party.map { it.moves.toList() })
    }

    @Test
    fun `v1_1 - its battle handlers are read live`() {
        val ram = FixtureMemoryReader.load("monomyth_v1_1_battle")
        val t = decodeNative("monomyth_v1_1_battle", v11)
        assertTrue(t.inBattle)
        assertEquals("Poliwag" to 5, t.battleMons[BATTLE_POS_OPPONENT_LEFT].let { speciesName(it.species) to it.level })
        assertEquals(194, t.battleMons[BATTLE_POS_PLAYER_LEFT].species)
        assertEquals(v11.handleInputChooseAction, u32le(ram.readCoreMemory(v11.battlerControllerFuncs, 4), 0))
        val trainer = decodeNative("monomyth_v1_1_trainer", v11)
        assertEquals(listOf("Spinarak", "Joltik"), trainer.enemyParty.map { speciesName(it.species) })
        assertEquals(listOf(3, 4), trainer.enemyParty.map { it.level })
    }

    @Test
    fun `v1_1 - its tables match the ROM`() {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Monomyth (v1.1).gba") ?: return
        val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(rom.readCoreMemory(0x08000000L, 0x2000000)).joinToString("") { "%02x".format(it) }
        assertEquals(TelemetrySampler.MONOMYTH_V1_1_SHA1, sha1)
        assertTrue(TelemetrySampler.MONOMYTH_V1_1_SHA1 in TelemetrySampler.SUPPORTED_HACK_SHA1S)
        assertEquals("BPEE", String(rom.readCoreMemory(0x080000ACL, 4), Charsets.US_ASCII))
        PokedexSource.reader = rom
        assertTrue(pokedexMatchesRom(rom, POKEDEX_MONOMYTH_V1_1))
        assertFalse(pokedexMatchesRom(rom, POKEDEX_MONOMYTH))
        assertTrue(guideTablesMatchRom(rom, GUIDE_TABLES_MONOMYTH_V1_1))
        assertFalse(guideTablesMatchRom(rom, GUIDE_TABLES_MONOMYTH))
        assertEquals(194, PokedexSource.speciesFor(POKEDEX_MONOMYTH_V1_1, 7))
        val e = PokedexSource.entry(POKEDEX_MONOMYTH_V1_1, 7)!!
        assertEquals("Rookie" to (5 to 91), e.category to (e.heightDm to e.weightHg))
        assertTrue(e.description.startsWith("Mishimizu love shiny copper objects"))
        assertEquals(listOf("Torrent"), e.abilities)
        assertTrue(EvoLink(194, 195, listOf(EvoReq.Level(20))) in DexDetails.family(POKEDEX_MONOMYTH_V1_1, 194)!!)
        val moves = DexDetails.levelUp(POKEDEX_MONOMYTH_V1_1, v11.guideTables, 194)!!
        assertTrue(LevelMove(8, 55) in moves && LevelMove(11, 229) in moves)
        assertTrue(DexDetails.catchSpots(v11.guideTables, 183)!!.any { it.mapsec == 0x10 && it.method == WildMethod.GRASS })
        // The packed icons, as v1.0's: MISHIMIZU's tiles.
        val rd = { a: Long, n: Int -> rom.readCoreMemory(a, n) }
        val tiles = MonomythIcons.unpack(rd, u32le(rd(v11.monIconTable + 194 * 4L, 4), 0))!!
        assertEquals(0xB8E0F95FL, java.util.zip.CRC32().apply { update(tiles) }.value)
    }

    @Test
    fun `v1_1 - PC anywhere matches the ROM, v1_0's table doesn't`() {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Monomyth (v1.1).gba") ?: return
        assertEquals(FIELD_ITEMS_MONOMYTH_V1_1, v11.fieldItems)
        val support = fieldItemSupport(FIELD_ITEMS_MONOMYTH_V1_1) { a, n -> rom.readCoreMemory(a, n) }!!
        assertTrue(support.canPc)
        assertFalse(support.canFly)
        assertNull(fieldItemSupport(FIELD_ITEMS_MONOMYTH) { a, n -> rom.readCoreMemory(a, n) })
    }
}
