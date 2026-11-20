package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pokémon Team Rocket Edition: Dragonsden Version: vanilla-shaped encrypted party mons with the flags folded into +0x12,
 * its eight-pocket bag, its 0x5C-byte battle mons, its battle menus and its own names (TeamRocketGen.kt). The one save on
 * file; what the game's own screens show is in the fixtures' READMEs.
 */
class TeamRocketDecodeTest {
    private val cfg = NATIVE_TEAM_ROCKET

    @Before fun setUp() {
        activeGame = GameKind.TEAM_ROCKET
    }

    @After fun tearDown() {
        activeGame = GameKind.FIRERED
    }

    @Test
    fun `the save - party, bag, money and place`() {
        val t = decodeNative("team_rocket", cfg)
        assertEquals(listOf(169, 229, 105, 142, 3, 137), t.party.map { it.species })
        assertEquals(listOf("Crobat", "Houndoom", "Marowak", "Aerodactyl", "Venusaur", "Porygon"), t.party.map { speciesName(it.species) })
        assertEquals(listOf(79, 77, 77, 77, 77, 77), t.party.map { it.level })
        assertEquals(listOf(247, 241, 251, 234, 297, 259), t.party.map { it.maxHp })
        assertEquals(t.party.map { it.maxHp }, t.party.map { it.hp })
        // As the party menu draws them: Porygon genderless.
        assertEquals(
            listOf(GENDER_SYMBOL_FEMALE, GENDER_SYMBOL_FEMALE, GENDER_SYMBOL_MALE, GENDER_SYMBOL_MALE, GENDER_SYMBOL_MALE, GENDER_SYMBOL_NONE),
            t.party.map { it.genderSymbol },
        )
        assertTrue(t.party.none { it.isEgg })
        // CROBAT: its moves, PP, held item, experience; Adamant (PID % 25 - no Mint bits here), its stats recomputed.
        val crobat = t.party[0]
        assertEquals(listOf("Curse", "Tailwind", "Poison Fang", "Fly"), crobat.moves.map { lookupMove(it).name })
        assertEquals(listOf(10, 15, 15, 15), crobat.pp.toList())
        assertEquals("Black Sludge", itemName(crobat.heldItem))
        assertEquals(493_039L, crobat.exp)   // Lv79 exactly (medium fast)
        assertEquals(493_039L, expProgress(crobat.species, crobat.level, crobat.exp)!!.levelStart)
        val stats = crobat.stats!!
        assertEquals(3, stats.nature)
        assertEquals(List(6) { 31 }, stats.ivs)
        assertEquals(listOf(0, 252, 6, 252, 0, 0), stats.evs)
        assertEquals(listOf(247, 243, 156, 284, 126, 155), stats.stats)
        // The bag's ITEMS pocket, as its bag screen lists it; eight pockets folded into the app's five.
        val items = t.items.filter { it.pocket == POCKET_ITEMS }.associate { itemName(it.itemId) to it.quantity }
        assertEquals(15, items["Escape Rope"])
        assertEquals(80, items["Max Repel"])
        assertEquals(15, items["Rare Candy"])
        assertEquals(4, items["AbilityCapsle"])
        assertEquals(listOf(4, 3, 2, 1), t.items.filter { it.pocket == POCKET_POKE_BALLS }.map { it.itemId })
        assertTrue(t.items.any { it.pocket == POCKET_TM_HM && itemName(it.itemId) == "TM01" })
        assertTrue(t.items.any { it.pocket == POCKET_KEY_ITEMS && itemName(it.itemId) == "Mach Bike" })
        assertTrue(t.items.any { it.pocket == POCKET_BERRIES && itemName(it.itemId) == "Pomeg Berry" })
        assertPocketsInRange(t)
        assertEquals(3_000_000L, t.money)
        assertFalse(t.inBattle)
        assertEquals(0x5E, t.regionMapSectionId)
        assertEquals("CELADON CITY", activeMapSecData[0x5E]!!.name)
    }

    @Test
    fun `a scripted wild battle - the battlers, the menus`() {
        val ram = FixtureMemoryReader.load("team_rocket_battle")
        val t = decodeNative("team_rocket_battle", cfg)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(Triple(169, 79, 247), Triple(me.species, me.level, me.hp))
        assertEquals(247, me.maxHp)
        assertEquals(3 to 2, me.type1 to me.type2)   // POISON / FLYING
        assertEquals(listOf(10, 15, 15, 15), me.pp.toList())
        assertEquals(39, me.ability)   // INNER FOCUS
        assertEquals(497, me.item)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Rattata" to 5, speciesName(foe.species) to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        val wild = decodePartyMon(ram.readCoreMemory(cfg.enemyParty, cfg.monStride), 0, cfg.partyMonLayout)!!
        assertEquals(19 to 5, wild.species to wild.level)
        assertTrue(t.enemyParty.isEmpty())   // a wild battle: no FOE TEAM
        // The menus' cursors, as BattleInputController reads and writes them.
        assertEquals(0, ram.readCoreMemory(cfg.actionCursor, 1)[0].toInt())
        assertEquals(0, ram.readCoreMemory(cfg.moveCursor, 1)[0].toInt())
    }

    @Test
    fun `a trainer battle - the FOE TEAM, the foe's details, the next foe`() {
        val t = decodeNative("team_rocket_trainer", cfg)
        assertTrue(t.inBattle)
        // KANTO LEADER BROCK's four, ONIX out.
        assertEquals(listOf("Onix", "Kabutops", "Omastar", "Golem"), t.enemyParty.map { speciesName(it.species) })
        assertEquals(listOf(45, 46, 46, 47), t.enemyParty.map { it.level })
        assertEquals(0, t.enemyActive)
        assertEquals(-1, t.enemyNext)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(95 to (100 to 100), foe.species to (foe.hp to foe.maxHp))
        assertEquals(5, foe.ability)   // STURDY
        assertEquals("Choice Band", itemName(foe.item))
        assertEquals(listOf(59, 162, 81, 45, 59), foe.stats!!.toList())
        assertTrue(foe.statStages!!.all { it == 6 })
        assertEquals("Rock Tomb", lookupMove(t.enemyParty[0].moves[0]).name)
        // ONIX fainted; BROCK is about to send OMASTAR (slot 2).
        val next = decodeNative("team_rocket_trainer_next", cfg)
        assertEquals(0, next.enemyParty[0].hp)
        assertEquals(0, next.enemyActive)
        assertEquals(2, next.enemyNext)
    }

    @Test
    fun `GUIDE - HERE's wild Pokémon from the ROM`() {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Team Rocket Dragonsden Version.gba") ?: return
        PokedexSource.reader = rom
        val t = cfg.guideTables!!
        assertTrue(guideTablesMatchRom(rom, t))
        assertEquals(GuideId.TEAM_ROCKET, guideId(GameKind.TEAM_ROCKET, t))
        // The warp's ROUTE 101 (map 0.16): its land set as the ROM has it.
        val here = decodeNative("team_rocket_route", cfg)
        assertEquals(0 to 16, here.mapGroup to here.mapNum)
        assertEquals("ROUTE 101", activeMapSecData[here.regionMapSectionId]!!.name)
        val route = GuideRomSource.encounters(t, 0, 16)!!
        assertEquals(setOf("Cascoon", "Silcoon", "Mightyena", "Beautifly", "Dustox", "Linoone"), route.grass.map { speciesName(it.species) }.toSet())
        assertTrue(route.grass.all { it.minLevel in 40..85 })
        assertTrue(GuideRomSource.wildMapKeys(t).size > 300)
        // BROCK's four, as the headless trainer battle met them.
        assertEquals(listOf("Onix", "Kabutops", "Omastar", "Golem"), GuideRomSource.party(t, 415)!!.map { speciesName(it.species) })
    }

    @Test
    fun `an egg is flagged by +0x12's bit 5`() {
        // The egg given headless (giveegg PIKACHU, into the PC): its header bytes.
        val raw = ByteArray(100)
        hex("98d93e78a9890000606f8bff39050506070831c4d5e7e3e2ffff0000df930000").copyInto(raw)
        assertEquals(0x20, raw[TEAM_ROCKET_PARTY_MON.flags].toInt() and TEAM_ROCKET_PARTY_MON.eggMask)
        assertEquals(0, 0x12 and TEAM_ROCKET_PARTY_MON.eggMask)   // every other mon's byte
        assertEquals(1395, cfg.eggSpecies)
    }

    @Test
    fun `its own types and matchups`() {
        assertEquals("Fairy", activeTypeNames[18])
        assertEquals(SpeciesTypes(3, 2), activeSpeciesTypeData[169])
        assertEquals(0, activeTypeEffectiveness[1618])     // DRAGON on FAIRY
        assertEquals(200, activeTypeEffectiveness[1816])   // FAIRY on DRAGON
        assertEquals(50, activeTypeEffectiveness[1010])    // FIRE on FIRE
        assertEquals("Terapagos", speciesName(1379))
        assertEquals(MoveInfo("Curse", 7, 0), moveDataTeamRocket[174])
        assertNotNull(activeItemNames[910])   // Rainbow Pass
        assertTrue(battleHasBag)
        assertTrue(bagHasCounts)
    }

    @Test
    fun `detected by hash`() {
        assertTrue(TelemetrySampler.TEAM_ROCKET_DRAGONSDEN_V1_5_SHA1 in TelemetrySampler.SUPPORTED_HACK_SHA1S)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
