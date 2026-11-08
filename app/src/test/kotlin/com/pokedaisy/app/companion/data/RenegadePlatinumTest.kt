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
 * Renegade Platinum (RenegadePlatinum): Platinum's reader on its RAM, on a new game played headless (the
 * renegade_platinum fixtures' READMEs), and its tables read from the cart (skips without the ROM: `-DromDir=`'s ../nds/).
 */
class RenegadePlatinumTest {
    private val rom = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon Renegade Platinum (v1.3.0).nds")
    private val retail = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon - Platinum Version (USA) (Rev 1).nds")

    private fun read(fixture: String = "renegade_platinum"): Telemetry {
        activeGame = GameKind.PLATINUM
        renegadePlatinum = true
        romLanguage = 'E'
        romGameCode = RenegadePlatinum.CODE
        return readGen4Telemetry(FixtureMemoryReader.load(fixture), gen4ConfigFor(GameKind.PLATINUM)!!)
    }

    @After
    fun reset() {
        renegadePlatinum = false
        RenegadePlatinum.data = null
        NdsArt.names = null
        NdsArt.dir = null
        romGameCode = ""
    }

    @Test
    fun `party - Platinum's struct, the hack's own learnsets`() {
        val t = read()
        assertFalse(t.inBattle)
        assertEquals(listOf(387, 429, 330, 35, 25, 448), t.party.map { it.species })
        assertEquals(listOf(5, 30, 40, 12, 9, 35), t.party.map { it.level })
        assertEquals(listOf(21 to 21, 79 to 79, 131 to 131, 42 to 42, 27 to 27, 95 to 95), t.party.map { it.hp to it.maxHp })
        assertEquals(234, t.party[1].heldItem)
        // TURTWIG's Lv 5 moves are its own here: Tackle, Withdraw, Absorb.
        assertEquals(listOf(33, 110, 71, 0), t.party[0].moves.toList())
        assertEquals(listOf(225, 414, 201, 89), t.party[2].moves.toList())
    }

    @Test
    fun `save - money, the place, the bag and the Pokedex`() {
        val t = read()
        assertEquals(15345L, t.money)
        assertEquals(415, t.mapNum)
        assertEquals("Twinleaf Town", t.mapSecName)
        assertEquals(4 to 6, t.x to t.y)
        assertEquals(0, t.playerGender)
        assertEquals(setOf(79 to 2, 450 to 1, 328 to 1, 17 to 5, 155 to 3, 4 to 10), t.items.map { it.itemId to it.quantity }.toSet())
        val dex = t.pokedex!!
        assertEquals(POKEDEX_RENEGADE, dex.tables)
        assertEquals(setOf(25, 35, 330, 387, 429, 448), dex.caught)
        assertEquals(setOf(25, 35, 330, 387, 429, 448), dex.seen)
        assertFalse(dex.national)
    }

    @Test
    fun `battle - a wild one, the foe's Fairy type from the battle`() {
        val t = read("renegade_platinum_battle")
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(387 to 5, me.species to me.level)
        assertEquals(21 to 21, me.hp to me.maxHp)
        assertEquals(12 to 12, me.type1 to me.type2)                                  // Grass
        assertEquals(listOf(33, 110, 71, 0), me.moves.toList())
        assertEquals(listOf(35, 40, 25, 0), me.pp.toList())
        assertEquals(35 to 5, foe.species to foe.level)
        assertEquals(22 to 22, foe.hp to foe.maxHp)
        assertEquals(9 to 9, foe.type1 to foe.type2)                                  // Fairy, in "???"'s slot
        assertEquals(listOf(35), t.battleFoes.map { it.species })
        assertEquals(emptyList<Mon>(), t.enemyParty)                                  // a wild one: no FOE TEAM
        assertEquals(BATTLE_INPUT_ACTION_SELECT, t.battleInputState)
    }

    @Test
    fun `the cart - detection and its tables`() {
        assumeTrue("no Renegade Platinum ROM at $rom", rom.isFile)
        assertTrue(RenegadePlatinum.matches(rom))
        if (retail.isFile) assertFalse(RenegadePlatinum.matches(retail))
        val names = NdsArt.readNames(rom)!!
        assertEquals('E', names.language)
        val data = NdsArt.readRenegade(rom)!!
        assertEquals(SpeciesTypes(9, 9), data.speciesTypes[35])               // CLEFAIRY: Fairy
        assertEquals(SpeciesTypes(7, 9), data.speciesTypes[429])              // MISMAGIUS: Ghost / Fairy
        assertEquals(SpeciesTypes(6, 16), data.speciesTypes[330])             // FLYGON: Bug / Dragon
        assertEquals(SpeciesTypes(12, 12), data.speciesTypes[387])
        assertEquals(MoveInfo(names.moves[33], 0, 40), data.moveData[33])     // Tackle
        assertEquals(9 to 95, data.moveData.getValue(461).let { it.type to it.power })
        assertEquals(1, data.moveCategories[461])                             // special here, status in retail
        assertEquals(7, data.moveData.getValue(174).type)                     // Curse: Ghost, not "???"
        assertEquals(467, data.moveData.keys.max())
        // Gen 6's chart: Fairy beats Dragon, Dragon can't touch it, Steel no longer resists Ghost or Dark.
        assertEquals(200, data.typeChart[9 * 100 + 16])
        assertEquals(0, data.typeChart[16 * 100 + 9])
        assertEquals(200, data.typeChart[8 * 100 + 9])
        assertEquals(50, data.typeChart[9 * 100 + 8])
        assertNull(data.typeChart[7 * 100 + 8])
        assertNull(data.typeChart[17 * 100 + 8])
        assertEquals(0, data.typeChart[0 * 100 + 7])                          // the foresight pair, after 0xFE
        // Five machines teach other moves: TM57, TM62, TM83, TM85, TM88.
        assertEquals(listOf(344, 405, 304, 295, 239), listOf(56, 61, 82, 84, 87).map { data.machines[it] })
        assertArrayEquals(tmMovesPlatinum.copyOfRange(0, 56), data.machines.copyOfRange(0, 56))
        assertTrue(data.wildRates.size > 100 * 3)
        // The companion's tables over its own, once read.
        RenegadePlatinum.data = data
        NdsArt.names = names
        val t = read()
        assertEquals("Fairy", typeName(activeSpeciesTypeData.getValue(35).type1))
        assertEquals(listOf("Ghost", "Fairy"), activeSpeciesTypeData.getValue(429).let { listOf(typeName(it.type1), typeName(it.type2)) })
        assertEquals(listOf("Tackle", "Withdraw", "Absorb"), t.party[0].moves.take(3).map { lookupMove(it).name })
        // It put new moves in old ones' slots (12 of them) and renamed items: the names come from its text, not ours.
        assertEquals("Drill Run", lookupMove(32).name)                       // Horn Drill's slot
        assertEquals("Play Rough" to 9, lookupMove(66).let { it.name to it.type })  // Submission's, now Fairy
        assertEquals("Prism Scale", activeItemNames[429])
        assertEquals(200, activeTypeEffectiveness[9 * 100 + 16])
        val dir = kotlin.io.path.createTempDirectory("nds-art").toFile()
        try {
            assertTrue(NdsArt.extractTo(rom, dir))
            for (id in listOf(1, 35, 387, 493, 494)) assertTrue("$id", File(dir, "${NdsArt.RENEGADE_ICONS}/$id.png").isFile)
            for (id in listOf(1, 17, 79)) assertTrue("$id", File(dir, "${NdsArt.RENEGADE_ITEMS}/$id.png").isFile)
            for (id in listOf(1, 35, 493)) assertTrue("$id", File(dir, "${NdsArt.RENEGADE_FRONT}/$id.png").isFile)
            assertFalse(File(dir, NdsArt.PLATINUM_DEX).exists())
            NdsArt.dir = dir
            val clefairy = Gen4Dex.entry(GEN4_DEX_RENEGADE, 35)!!
            assertEquals(9 to 9, clefairy.type1 to clefairy.type2)
            // The DEX's machines are the cart's: TM57's move is its own.
            val learns = Gen4Dex.machines(GEN4_DEX_RENEGADE, 151)!!.map { it.label to it.move }
            assertTrue(learns.toString(), "TM57" to 344 in learns)
            val out = File("build/nds-art").apply { mkdirs() }
            for (id in listOf(35, 429)) File(dir, "${NdsArt.RENEGADE_ICONS}/$id.png").copyTo(File(out, "rp-icon-$id.png"), true)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** v1.2.0 and v1.3.0's Classic patch: v1.3.0's code and table places, their own species / move files. */
    @Test
    fun `the other builds - the same check and tables, their own data`() {
        for ((name, sha1) in listOf("Pokemon Renegade Platinum (v1.2.0).nds" to RenegadePlatinum.V1_2_0_SHA1,
                "Pokemon Renegade Platinum (v1.3.0) (Classic).nds" to RenegadePlatinum.V1_3_0_CLASSIC_SHA1)) {
            val other = File(rom.parentFile, name)
            if (!other.isFile) continue
            assertEquals(name, sha1, com.pokedaisy.app.RomIdentity.sha1(other))
            assertTrue(name, RenegadePlatinum.matches(other))
            val names = NdsArt.readNames(other)!!
            assertEquals(name, 'E', names.language)
            val data = NdsArt.readRenegade(other)!!
            assertEquals(name, SpeciesTypes(9, 9), data.speciesTypes[35])            // CLEFAIRY: Fairy here too
            assertEquals(name, SpeciesTypes(7, 7), data.speciesTypes[429])           // MISMAGIUS: Ghost alone (v1.3.0: + Fairy)
            assertEquals(name, SpeciesTypes(4, 16), data.speciesTypes[330])          // FLYGON: Ground / Dragon (v1.3.0: Bug)
            assertEquals(name, 9 to 95, data.moveData.getValue(461).let { it.type to it.power })
            assertEquals(name, "Play Rough" to 9, data.moveData.getValue(66).let { it.name to it.type })
            assertEquals(name, 467, data.moveData.keys.max())
            assertEquals(name, listOf(344, 405, 304, 295, 239), listOf(56, 61, 82, 84, 87).map { data.machines[it] })
            assertEquals(name, 200, data.typeChart[9 * 100 + 16])
        }
    }

    @Test
    fun `the cart reader on retail Platinum - its rates, machines and palettes are the decomp's`() {
        assumeTrue("no Platinum ROM at $retail", retail.isFile)
        val data = NdsArt.readRenegade(retail)!!
        assertArrayEquals(tmMovesPlatinum, data.machines)
        assertArrayEquals(iconPalettesPlatinum, data.iconPalettes.copyOfRange(0, iconPalettesPlatinum.size))
        fun byMap(t: IntArray) = (t.indices step 3).associate { t[it] to (t[it + 1] to t[it + 2]) }
        val mine = byMap(data.wildRates)
        val world = byMap(PLATINUM_WORLD.wildRates())
        assertEquals(world, mine.filterKeys { it in world })
        assertTrue(mine.filterKeys { it !in world }.values.all { it == 0 to 0 })
    }
}
