package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Blaze Black 2 Redux / Volt White 2 Redux ([Black2Redux]): Black 2's and White 2's reader on their RAM, the player's
 * retail saves loaded in them headless (the blaze_black2_redux* and volt_white2_redux fixtures' READMEs), and their
 * tables read from the cart (skips without the ROMs: `-DromDir=`'s ../nds/).
 */
class Black2ReduxTest {
    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")
    private val blaze = File(nds, "Pokemon Blaze Black 2 Redux (v1.4.1).nds")
    private val volt = File(nds, "Pokemon Volt White 2 Redux (v1.4.1).nds")

    private fun read(fixture: String, kind: GameKind = GameKind.BLACK2): Telemetry {
        activeGame = kind
        black2Redux = true
        romLanguage = 'E'
        romGameCode = ""
        return readGen5Telemetry(FixtureMemoryReader.load(fixture), gen5ConfigFor(kind)!!)
    }

    @After
    fun reset() {
        black2Redux = false
        Black2Redux.data = null
        NdsArt.names = null
        NdsArt.dir = null
    }

    @Test
    fun `the configs - Black 2's and White 2's, their own DEX`() {
        black2Redux = true
        assertEquals(GEN5_BLACK2_REDUX, gen5ConfigFor(GameKind.BLACK2))
        assertEquals(GEN5_WHITE2_REDUX, gen5ConfigFor(GameKind.WHITE2))
        assertEquals(POKEDEX_REDUX, GEN5_BLACK2_REDUX.pokedexTables)
        assertEquals(GEN5_BLACK2.saveControlPtr, GEN5_BLACK2_REDUX.saveControlPtr)
        assertEquals(GEN5_WHITE2.gameSystemPtr, GEN5_WHITE2_REDUX.gameSystemPtr)
        black2Redux = false
        assertEquals(GEN5_BLACK2, gen5ConfigFor(GameKind.BLACK2))
    }

    @Test
    fun `Blaze Black 2 - the retail save on its field`() {
        val t = read("blaze_black2_redux")
        assertFalse(t.inBattle)
        assertEquals(listOf(500, 571, 547, 520, 55, 505), t.party.map { it.species })
        assertTrue(t.party.all { it.level == 100 })
        assertEquals(listOf(394, 372, 457, 535), t.party[0].moves.toList())
        assertEquals(538, t.party[3].heldItem)
        assertEquals(50, t.items.size)
        assertEquals(656_841L, t.money)
        assertEquals(465, t.mapNum)
        assertEquals("Humilau City", t.mapSecName)
        val dex = t.pokedex!!
        assertEquals(POKEDEX_REDUX, dex.tables)
        assertEquals(15, dex.caught.size)
        assertEquals(248, dex.seen.size)
        assertTrue((0 until 8).all { t.progress!!.flag(GEN4_BADGE_FLAG + it) })
    }

    @Test
    fun `Blaze Black 2 - a wild battle, its lead's own typing from the battle`() {
        val t = read("blaze_black2_redux_battle")
        assertTrue(t.inBattle)
        assertEquals(false, t.isTrainerBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(500 to 100, me.species to me.level)
        assertEquals(356 to 356, me.hp to me.maxHp)
        assertEquals(10 to 4, me.type1 to me.type2)                       // EMBOAR: Fire / Ground here (retail: Fighting)
        assertEquals(listOf(394, 372, 457, 535), me.moves.toList())
        assertEquals(listOf(15, 10, 5, 10), me.pp.toList())
        assertEquals(594 to 50, foe.species to foe.level)                 // ALOMOMOLA
        assertEquals(11 to 11, foe.type1 to foe.type2)
        assertEquals(listOf(594), t.battleFoes.map { it.species })
        assertEquals(BATTLE_INPUT_ACTION_SELECT, t.battleInputState)
    }

    @Test
    fun `Volt White 2 - the retail save on its field`() {
        val t = read("volt_white2_redux", GameKind.WHITE2)
        assertFalse(t.inBattle)
        assertEquals(listOf(497, 227, 579, 505, 127, 184), t.party.map { it.species })
        assertEquals(544_628L, t.money)
        assertEquals(448, t.mapNum)
        assertEquals("Virbank City", t.mapSecName)
        assertEquals(230, t.pokedex!!.seen.size)
    }

    @Test
    fun `Fairy is Gen 5's 17 in these carts, the app's 9`() {
        assertEquals(9, gen5Type(17))
        assertEquals(10, gen5Type(9))                                     // Fire, as before
        assertEquals(17, gen5Type(16))                                    // Dark
        assertEquals(8, gen5Type(8))                                      // Steel
    }

    @Test
    fun `the carts - detection and their tables`() {
        assumeTrue("no Blaze Black 2 Redux ROM at $blaze", blaze.isFile)
        assertTrue(Black2Redux.matches(blaze))
        for (retail in listOf("Pokemon - Black Version 2 (USA, Europe) (NDSi Enhanced) [b].nds",
                "Pokemon - White Version 2 (USA, Europe) (NDSi Enhanced) [b].nds")) {
            val f = File(nds, retail)
            if (f.isFile) assertFalse(retail, Black2Redux.matches(f))
        }
        val names = NdsArt.readNames(blaze)!!
        assertEquals('E', names.language)
        val data = NdsArt.readRedux(blaze)!!
        assertEquals(SpeciesTypes(9, 9), data.speciesTypes[35])               // CLEFAIRY: Fairy
        assertEquals(SpeciesTypes(11, 9), data.speciesTypes[184])             // AZUMARILL: Water / Fairy
        assertEquals(SpeciesTypes(12, 16), data.speciesTypes[497])            // SERPERIOR: Grass / Dragon (Complete)
        assertEquals(SpeciesTypes(10, 4), data.speciesTypes[500])             // EMBOAR: Fire / Ground (Complete)
        assertEquals(MoveInfo(names.moves[1], 0, 40), data.moveData[1])       // Pound
        // New moves in old slots, from its text (Comet Punch's is Power-Up Punch, ViceGrip's Dual Wingbeat), Fairy moves.
        assertEquals(Triple("Power-Up Punch", 1, 50), data.moveData.getValue(4).let { Triple(it.name, it.type, it.power) })
        assertEquals(Triple("Dual Wingbeat", 2, 40), data.moveData.getValue(11).let { Triple(it.name, it.type, it.power) })
        assertEquals(Triple("Moonblast", 9, 95), data.moveData.getValue(296).let { Triple(it.name, it.type, it.power) })
        assertEquals(Triple("Play Rough", 9, 90), data.moveData.getValue(66).let { Triple(it.name, it.type, it.power) })
        // Categories in the app's order (0 physical, 1 special, 2 status).
        assertEquals(0, data.moveCategories[66])
        assertEquals(1, data.moveCategories[296])
        assertEquals(2, data.moveCategories[204])                             // Charm
        assertEquals(559, data.moveData.keys.max())
        // Gen 6's chart: Fairy beats Dragon, Dragon can't touch it, Steel no longer resists Ghost or Dark.
        assertEquals(200, data.typeChart[9 * 100 + 16])
        assertEquals(0, data.typeChart[16 * 100 + 9])
        assertEquals(200, data.typeChart[3 * 100 + 9])                        // Poison beats Fairy
        assertEquals(50, data.typeChart[9 * 100 + 10])                        // Fire resists it
        assertNull(data.typeChart[7 * 100 + 8])
        assertNull(data.typeChart[17 * 100 + 8])
        assertEquals(0, data.typeChart[0 * 100 + 7])
        // 15 machines teach other moves: TM19 (202), TM20 (161), TM45 Power-Up Punch (4)...
        assertEquals(listOf(202, 161, 4), listOf(18, 19, 44).map { data.machines[it] })
        assertArrayEquals(tmMovesBlack.copyOfRange(0, 18), data.machines.copyOfRange(0, 18))
        assertEquals(Black2Redux.ICON_PALETTES_SIZE, data.iconPalettes.size)
        assertTrue(data.iconPalettes.all { it in 0..2 })
        assertArrayEquals(iconPalettesBlack2.copyOfRange(1, 4), data.iconPalettes.copyOfRange(1, 4))   // BULBASAUR's line
        assertEquals(itemIconsBlack2.size, data.itemIcons.size)
        assertEquals(itemIconsBlack2[1], data.itemIcons[1])                   // the Master Ball's: unchanged
        assertTrue(data.wildRates.all { it.size > 100 * 3 })
        // The companion's tables over its own, once read.
        Black2Redux.data = data
        NdsArt.names = names
        val t = read("blaze_black2_redux_battle")
        assertEquals("Fairy", typeName(activeSpeciesTypeData.getValue(35).type1))
        assertEquals(listOf("Water", "Fairy"), activeSpeciesTypeData.getValue(184).let { listOf(typeName(it.type1), typeName(it.type2)) })
        assertEquals(listOf("Flare Blitz", "Assurance", "Head Smash", "Heat Crash"), t.battleMons[BATTLE_POS_PLAYER_LEFT].moves.map { lookupMove(it).name })
        assertEquals("Power-Up Punch", lookupMove(4).name)
        assertEquals(200, activeTypeEffectiveness[9 * 100 + 16])
        assertNull(guideId(GameKind.BLACK2, null))
        val dir = kotlin.io.path.createTempDirectory("nds-art").toFile()
        try {
            assertTrue(NdsArt.extractTo(blaze, dir))
            for (id in listOf(1, 35, 184, 649)) assertTrue("$id", File(dir, "${NdsArt.REDUX_ICONS}/$id.png").isFile)
            for (id in listOf(1, 17, 4)) assertTrue("$id", File(dir, "${NdsArt.REDUX_ITEMS}/$id.png").isFile)
            for (id in listOf(1, 35, 649)) assertTrue("$id", File(dir, "${NdsArt.REDUX_FRONT}/$id.png").isFile)
            assertFalse(File(dir, NdsArt.BLACK2_DEX).exists())
            assertFalse(NdsArt.extractTo(blaze, dir))                          // built once
            NdsArt.dir = dir
            val clefairy = Gen4Dex.entry(GEN5_DEX_REDUX, 35)!!
            assertEquals(9 to 9, clefairy.type1 to clefairy.type2)
            // The DEX's machines are the cart's: TM45 teaches Power-Up Punch here.
            val learns = Gen4Dex.machines(GEN5_DEX_REDUX, 66)!!.map { it.label to it.move }
            assertTrue(learns.toString(), "TM45" to 4 in learns)
            val out = File("build/nds-art").apply { mkdirs() }
            for (id in listOf(35, 184, 500)) File(dir, "${NdsArt.REDUX_ICONS}/$id.png").copyTo(File(out, "redux-icon-$id.png"), true)
            File(dir, "${NdsArt.REDUX_FRONT}/184.png").copyTo(File(out, "redux-front-184.png"), true)
            File(dir, "${NdsArt.REDUX_ITEMS}/17.png").copyTo(File(out, "redux-item-17.png"), true)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The other seven builds: the same check and table places, each its own species records (Classic, EVless). */
    @Test
    fun `every build - the same check and tables, its own species`() {
        val builds = listOf(
            "Pokemon Blaze Black 2 Redux (v1.4.1).nds" to Black2Redux.BLAZE_BLACK2_V1_4_1_SHA1,
            "Pokemon Blaze Black 2 Redux (v1.4.1) (Classic).nds" to Black2Redux.BLAZE_BLACK2_V1_4_1_CLASSIC_SHA1,
            "Pokemon Blaze Black 2 Redux (v1.4.1) (EVless).nds" to Black2Redux.BLAZE_BLACK2_V1_4_1_EVLESS_SHA1,
            "Pokemon Blaze Black 2 Redux (v1.4.1) (Classic EVless).nds" to Black2Redux.BLAZE_BLACK2_V1_4_1_CLASSIC_EVLESS_SHA1,
            "Pokemon Volt White 2 Redux (v1.4.1).nds" to Black2Redux.VOLT_WHITE2_V1_4_1_SHA1,
            "Pokemon Volt White 2 Redux (v1.4.1) (Classic).nds" to Black2Redux.VOLT_WHITE2_V1_4_1_CLASSIC_SHA1,
            "Pokemon Volt White 2 Redux (v1.4.1) (EVless).nds" to Black2Redux.VOLT_WHITE2_V1_4_1_EVLESS_SHA1,
            "Pokemon Volt White 2 Redux (v1.4.1) (Classic EVless).nds" to Black2Redux.VOLT_WHITE2_V1_4_1_CLASSIC_EVLESS_SHA1,
        )
        assumeTrue("no Volt White 2 Redux ROM at $volt", volt.isFile)
        for ((name, sha1) in builds) {
            val rom = File(nds, name)
            if (!rom.isFile) continue
            assertEquals(name, sha1, com.pokedaisy.app.RomIdentity.sha1(rom))
            assertTrue(name, Black2Redux.matches(rom))
            assertEquals(name, if (name.contains("Volt")) "Pokémon Volt White 2 Redux" else "Pokémon Blaze Black 2 Redux",
                com.pokedaisy.app.GameTitles.BY_SHA1[sha1])
            val data = NdsArt.readRedux(rom)!!
            val classic = name.contains("Classic")
            assertEquals(name, SpeciesTypes(9, 9), data.speciesTypes[35])
            assertEquals(name, SpeciesTypes(11, 9), data.speciesTypes[184])
            // Classic keeps the official typings: SERPERIOR Grass, EMBOAR Fire / Fighting.
            assertEquals(name, if (classic) SpeciesTypes(12, 12) else SpeciesTypes(12, 16), data.speciesTypes[497])
            assertEquals(name, if (classic) SpeciesTypes(10, 1) else SpeciesTypes(10, 4), data.speciesTypes[500])
            assertEquals(name, "Moonblast" to 9, data.moveData.getValue(296).let { it.name to it.type })
            assertEquals(name, listOf(202, 161, 4), listOf(18, 19, 44).map { data.machines[it] })
            assertEquals(name, 200, data.typeChart[9 * 100 + 16])
            assertTrue(name, data.wildRates.all { it.size > 100 * 3 })
        }
    }
}
