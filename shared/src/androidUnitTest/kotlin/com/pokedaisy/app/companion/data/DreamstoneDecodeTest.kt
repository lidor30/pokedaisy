package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pokémon Dreamstone Mysteries (pokeemerald-expansion 1.11.2): 1.10's packed party mons, its seven-pocket bag, expansion's
 * 0x60-byte battle mons, its battle menus, its own names, and its GUIDE / DEX tables. The save is a new game played
 * headless (the dreamstone fixture's README); what the game's own screens showed is in the fixtures' READMEs.
 */
class DreamstoneDecodeTest {
    private val cfg = NATIVE_DREAMSTONE

    @Before fun setUp() {
        activeGame = GameKind.DREAMSTONE
    }

    @After fun tearDown() {
        activeGame = GameKind.FIRERED
    }

    @Test
    fun `the save - party, bag, money and place`() {
        val t = decodeNative("dreamstone", cfg)
        assertEquals(listOf(255, 25, 501, 152, 1289), t.party.map { it.species })
        assertEquals(listOf("Torchic", "Pikachu", "Oshawott", "Chikorita", "Sprigatito"), t.party.map { speciesName(it.species) })
        assertEquals(listOf(12, 9, 8, 10, 7), t.party.map { it.level })
        assertEquals(listOf(35, 25, 26, 30, 24), t.party.map { it.maxHp })
        assertEquals(t.party.map { it.maxHp }, t.party.map { it.hp })
        // As its party menu draws them: CHIKORITA female (its genderRatio is 127 here, not Gen 2's 31).
        assertEquals(
            listOf(GENDER_SYMBOL_MALE, GENDER_SYMBOL_MALE, GENDER_SYMBOL_MALE, GENDER_SYMBOL_FEMALE, GENDER_SYMBOL_MALE),
            t.party.map { it.genderSymbol },
        )
        assertTrue(t.party.none { it.isEgg })
        // OSHAWOTT, as its summary showed it: Quiet, 26 / 16 / 13 / 16 / 13 / 10, 314 EXP with 105 to Lv9; no EVs in this game.
        val oshawott = t.party[2]
        assertEquals(listOf("Tackle", "Tail Whip", "Water Gun"), oshawott.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals(listOf(35, 30, 25, 0), oshawott.pp.toList())
        assertEquals(0, oshawott.heldItem)
        assertEquals(314L, oshawott.exp)
        assertEquals(105L, expProgress(oshawott.species, oshawott.level, oshawott.exp)!!.toNext)
        val stats = oshawott.stats!!
        assertEquals(17, stats.nature)   // QUIET
        assertEquals(List(6) { 0 }, stats.evs)
        assertEquals(listOf(26, 16, 13, 10, 16, 13), stats.stats)
        assertEquals(listOf("Ember", "Quick Attack", "Flame Charge", "Detect"), t.party[0].moves.map { lookupMove(it).name })
        // The bag: seven pockets, Items / Medicine / Battle Items folded into the app's ITEMS.
        val bag = t.items.associate { itemName(it.itemId) to (it.quantity to it.pocket) }
        assertEquals(3 to POCKET_ITEMS, bag["Repel"])
        assertEquals(5 to POCKET_ITEMS, bag["Potion"])
        assertEquals(2 to POCKET_ITEMS, bag["Antidote"])
        assertEquals(10 to POCKET_POKE_BALLS, bag["Poké Ball"])
        assertEquals(2 to POCKET_BERRIES, bag["Oran Berry"])
        assertEquals(1 to POCKET_KEY_ITEMS, bag["Lab Package"])
        assertPocketsInRange(t)
        assertEquals(3000L, t.money)
        assertFalse(t.inBattle)
        assertEquals(0x00 to 0x00, t.mapGroup to t.mapNum)
        assertEquals(0xD5, t.regionMapSectionId)
        assertEquals("Carabrue Town", activeMapSecData[0xD5]!!.name)
    }

    @Test
    fun `a scripted wild battle - the battlers, the menus`() {
        val ram = FixtureMemoryReader.load("dreamstone_battle")
        val t = decodeNative("dreamstone_battle", cfg)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(Triple(255, 12, 35), Triple(me.species, me.level, me.hp))
        assertEquals(35, me.maxHp)
        assertEquals(11 to 11, me.type1 to me.type2)   // FIRE
        assertEquals(listOf(25, 30, 20, 5), me.pp.toList())
        assertEquals(66, me.ability)   // BLAZE
        assertEquals(listOf(20, 15, 19, 23, 19), me.stats!!.toList())
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Zigzagoon" to 5, speciesName(foe.species) to foe.level)
        assertEquals(53, foe.ability)   // PICKUP
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        val wild = decodePartyMon(ram.readCoreMemory(cfg.enemyParty, cfg.monStride), 0, cfg.partyMonLayout)!!
        assertEquals(263 to 5, (wild.species and cfg.speciesMask) to wild.level)
        assertTrue(t.enemyParty.isEmpty())   // a wild battle: no FOE TEAM
        assertEquals(0, ram.readCoreMemory(cfg.actionCursor, 1)[0].toInt())
        assertEquals(0, ram.readCoreMemory(cfg.moveCursor, 1)[0].toInt())
    }

    @Test
    fun `a trainer battle - the FOE TEAM and the next foe`() {
        val t = decodeNative("dreamstone_trainer", cfg)
        assertTrue(t.inBattle)
        // BACKPACKER RONNIE's two, CUBONE out.
        assertEquals(listOf("Cubone", "Sentret"), t.enemyParty.map { speciesName(it.species) })
        assertEquals(listOf(6, 8), t.enemyParty.map { it.level })
        assertEquals(0, t.enemyActive)
        assertEquals(-1, t.enemyNext)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(104 to (22 to 22), foe.species to (foe.hp to foe.maxHp))
        assertEquals(69, foe.ability)   // ROCK HEAD
        assertTrue(foe.statStages!!.all { it == 6 })
        // CUBONE fainted; RONNIE is about to send out SENTRET (slot 1).
        val next = decodeNative("dreamstone_trainer_next", cfg)
        assertEquals(0, next.enemyParty[0].hp)
        assertEquals(0, next.enemyActive)
        assertEquals(1, next.enemyNext)
    }

    @Test
    fun `GUIDE - HERE's wild Pokémon and NEXT BOSS from the ROM`() {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Dreamstone Mysteries.gba") ?: return
        PokedexSource.reader = rom
        val t = cfg.guideTables!!
        assertTrue(guideTablesMatchRom(rom, t))
        assertEquals(GuideId.DREAMSTONE, guideId(GameKind.DREAMSTONE, t))
        val here = decodeNative("dreamstone_route", cfg)
        assertEquals(0 to 4, here.mapGroup to here.mapNum)
        assertEquals("Route 1", activeMapSecData[here.regionMapSectionId]!!.name)
        val route = GuideRomSource.encounters(t, 0, 4)!!
        assertEquals(setOf("Rattata", "Pidove", "Cubone", "Rockruff"), route.grass.map { speciesName(it.species) }.toSet())
        assertTrue(route.grass.all { it.minLevel in 2..5 })
        assertEquals(155, GuideRomSource.wildMapKeys(t).size)   // 165 headers, some maps listed twice
        // RONNIE's two, as the headless trainer battle met them; LEADER INGER's three.
        assertEquals(listOf("Cubone", "Sentret"), GuideRomSource.party(t, 7)!!.map { speciesName(it.species) })
        assertEquals(listOf("Rockruff", "Geodude", "Minior"), GuideRomSource.party(t, 12)!!.map { speciesName(it.species) })
        assertEquals(listOf(12, 12, 13), GuideRomSource.party(t, 12)!!.map { it.level })
        // Difficulty 0 reads the first table (its own team where it has one).
        assertEquals(listOf("Rockruff", "Geodude", "Minior"), GuideRomSource.party(t, 12 or (1 shl TRAINER_TABLE_SHIFT))!!.map { speciesName(it.species) })
        val save = readSaveProgress(FixtureMemoryReader.load("dreamstone"), cfg, t)!!
        val next = GUIDE_DREAMSTONE.bosses.first { !it.isDone(save) }
        assertEquals("LEADER INGER", next.title)
        assertEquals(listOf(12 or (1 shl TRAINER_TABLE_SHIFT)), next.trainers(save))   // difficulty 0 on a new game
        assertTrue(save.flag(0x860) && save.flag(0x861))   // POKéMON / POKéDEX menus
    }

    @Test
    fun `DEX - the save's flags and its own regional order`() {
        val dex = readPokedexState(FixtureMemoryReader.load("dreamstone"), cfg, POKEDEX_DREAMSTONE)!!
        assertEquals(setOf(25, 152, 255, 501, 906), dex.seen)
        assertEquals(dex.seen, dex.caught)
        assertFalse(dex.national)
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Dreamstone Mysteries.gba") ?: return
        PokedexSource.reader = rom
        val order = PokedexSource.regionalOrder(POKEDEX_DREAMSTONE)!!
        assertEquals(315, order.size)
        assertEquals(574, order.first())          // GOTHITA
        assertEquals(152, order[53])               // No. 054 CHIKORITA, as its dex lists it
        assertEquals(501, order[223])              // No. 224 OSHAWOTT, as its summary shows it
        // Of the five, its dex counts only these two (SEEN 2 / OWN 2 on its screen).
        assertEquals(2, order.count { it in dex.caught })
        val chikorita = PokedexSource.entry(POKEDEX_DREAMSTONE, 152)!!
        assertEquals("Leaf" to (9 to 64), chikorita.category to (chikorita.heightDm to chikorita.weightHg))
        assertTrue(chikorita.description.startsWith("It waves its leaf around"))
        assertEquals(listOf("Overgrow"), chikorita.abilities.take(1))
    }

    @Test
    fun `its own types, moves, items and map`() {
        assertEquals("Fairy", activeTypeNames[19])
        assertEquals(SpeciesTypes(11, 11), activeSpeciesTypeData[255])
        assertEquals(0, activeTypeEffectiveness[1719])     // DRAGON on FAIRY
        assertEquals(200, activeTypeEffectiveness[1917])   // FAIRY on DRAGON
        assertEquals("Sprigatito", speciesName(1289))
        assertEquals(MoveInfo("Tackle", 1, 40), moveDataDreamstone[33])
        assertEquals("Lab Package", activeItemNames[829])
        assertNotNull(activeItemNames[855])   // Archaeolens 2.0, its table's last
        assertEquals(null, activeItemNames[856])
        assertEquals(arrayOf("dreamstone").toList(), activeRegionMapImages.toList())
        // CARABRUE TOWN sits on its grid's (0x12, 8), its entry's tile, + (1, 2) on the screen.
        assertEquals(MapSecInfo("Carabrue Town", 0, 19, 10, 1, 1), activeMapSecData[0xD5])
        // Emerald's leftover sections are off its map.
        assertEquals(-1, activeMapSecData[0x00]!!.region)
        assertTrue(battleHasBag)
        assertTrue(bagHasCounts)
    }

    @Test
    fun `detected by hash`() {
        assertTrue(TelemetrySampler.DREAMSTONE_MYSTERIES_UNVERSIONED_SHA1 in TelemetrySampler.SUPPORTED_HACK_SHA1S)
        assertEquals(1524, cfg.eggSpecies)
    }
}
