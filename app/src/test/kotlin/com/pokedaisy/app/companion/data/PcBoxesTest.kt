package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** The BOXES tab's read ([readPcBoxes]) on the retail FireRed capture: 58 Pokémon over boxes 1-3, every checksum good. */
class PcBoxesTest {
    private val cfg = NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4)

    @Before fun setUp() {
        activeGame = GameKind.FIRERED
        romLanguage = 'E'
        resetNativeBagCache()
    }

    @After fun tearDown() {
        PokedexSource.reader = InProcessReader
        resetNativeBagCache()
    }

    @Test fun readsTheBoxes() {
        val boxes = readPcBoxes(FixtureMemoryReader.load("firered_vanilla"), cfg)
        assertNotNull(boxes)
        boxes!!
        assertEquals(PC_BOXES, boxes.boxes.size)
        assertEquals(0, boxes.currentBox)
        assertEquals(58, boxes.boxes.sumOf { b -> b.mons.count { it != null } })
        assertEquals("BOX1", boxes.boxes[0].name)
        assertEquals("BOX14", boxes.boxes[13].name)
        // The default wallpapers: FOREST, CITY, DESERT, SAVANNA, round again.
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1), boxes.boxes.map { it.wallpaper })
        // Box 1's first slots (PARAS, VENONAT, SPEAROW, WEEDLE, ...), slot 6 empty.
        assertEquals(listOf(46, 10, 21, 13, 105, 143), boxes.boxes[0].mons.take(6).map { it?.species })
        assertNull(boxes.boxes[0].mons[6])
        boxes.boxes.flatMap { it.mons }.filterNotNull().forEach { assertTrue(it.level in 1..100) }
        // Unchanged storage hands back the same object (the tab has nothing to redraw).
        assertTrue(boxes === readPcBoxes(FixtureMemoryReader.load("firered_vanilla"), cfg))
    }

    /** Retail Emerald: the same storage right after gSaveBlock2Ptr (0x03005D94). */
    @Test fun readsEmeraldBoxes() {
        activeGame = GameKind.EMERALD
        val em = NATIVE_EMERALD_RETAIL.copy(pokemonStoragePtr = NATIVE_EMERALD_RETAIL.saveBlock2Ptr + 4)
        val boxes = readPcBoxes(FixtureMemoryReader.load("emerald_vanilla"), em)!!
        assertEquals(16, boxes.boxes.sumOf { b -> b.mons.count { it != null } })
        assertEquals("BOX1", boxes.boxes[0].name)
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1), boxes.boxes.map { it.wallpaper })
        // Species ids (Gen 3's own numbering past 251), slot by slot.
        assertEquals(listOf(364, 286, 335, 290, 370, 227, 183, 301), boxes.boxes[0].mons.take(8).map { it?.species })
        boxes.boxes.flatMap { it.mons }.filterNotNull().forEach { assertTrue(it.level in 1..100) }
    }

    /** The stats a box record doesn't keep: worked out from the ROM's base stats, as the game does, they match the party's own. */
    @Test fun statsMatchTheParty() {
        val rom = RomFileReader.load(RomFileReader.FIRERED_REV1_PATH)
        assumeTrue("FireRed rev 1 ROM not found", rom != null)
        PokedexSource.reader = rom!!
        val ram = FixtureMemoryReader.load("firered_vanilla")
        val count = ram.readCoreMemory(cfg.playerPartyCount, 1)[0].toInt()
        val raw = ram.readCoreMemory(cfg.playerParty, count * MON_STRUCT_SIZE)
        val base = { s: Int -> PokedexSource.baseStats(cfg.pokedex!!, s) }
        for (i in 0 until count) {
            // The party's egg (TOGEPI's): no stats until it hatches.
            if (raw[i * MON_STRUCT_SIZE + 0x13].toInt() and 0x04 != 0) continue
            val party = decodePartyMon(raw, i * MON_STRUCT_SIZE)!!
            val box = decodeBoxMon(raw, i * MON_STRUCT_SIZE, baseStats = base)!!
            assertEquals(party.level, box.level)
            // Equal, or a point under where EVs came in after the last level-up (the party keeps its stats until then).
            party.stats!!.stats.zip(box.stats!!.stats).forEach { (stored, worked) -> assertTrue("$stored vs $worked", worked - stored in 0..1) }
        }
        val boxes = readPcBoxes(ram, cfg)!!
        boxes.boxes.flatMap { it.mons }.filterNotNull().filter { it.species != SPECIES_EGG_VANILLA }
            .forEach { assertNotNull(it.stats); assertEquals(it.hp, it.maxHp) }
    }

    /** English LeafGreen, both revisions: FireRed's storage pointer (the Poller sets it on retail LeafGreen). */
    @Test fun readsLeafGreenBoxes() {
        for ((fixture, base) in listOf("leafgreen_rev0" to NATIVE_LEAFGREEN_REV0, "leafgreen_rev1" to NATIVE_LEAFGREEN_REV1)) {
            val lg = base.copy(pokemonStoragePtr = base.saveBlock2Ptr + 4)
            val boxes = readPcBoxes(FixtureMemoryReader.load(fixture), lg)!!
            assertEquals(fixture, 74, boxes.boxes.sumOf { b -> b.mons.count { it != null } })
            // This save's names have a space ("BOX 1").
            assertEquals(fixture, "BOX 1", boxes.boxes[0].name)
            boxes.boxes.flatMap { it.mons }.filterNotNull().forEach { assertTrue(it.level in 1..100) }
        }
    }

    /** SoulGold: 15 boxes of 76-byte plaintext records behind gSaveBlock2Ptr + 8, the same in every build. */
    @Test fun readsSoulGoldBoxes() {
        activeGame = GameKind.SOULGOLD
        val boxes = readPcBoxes(FixtureMemoryReader.load("soulgold_party"), NATIVE_SOULGOLD)!!
        assertEquals(15, boxes.boxes.size)
        assertEquals((1..15).map { "Box$it" }, boxes.boxes.map { it.name })
        assertEquals((0 until 15).toList(), boxes.boxes.map { it.wallpaper })
        assertEquals(31, boxes.boxes.sumOf { b -> b.mons.count { it != null } })
        // BUIZEL, ZIGZAGOON, YAMASK, ... (expansion's species ids); ABRA alone in box 2, its moves' tracker bits masked off.
        assertEquals(listOf(418, 263, 562), boxes.boxes[0].mons.take(3).map { it?.species })
        assertEquals(listOf(100, 0, 0, 0), boxes.boxes[1].mons[0]!!.moves.toList())
        for ((fixture, cfg) in listOf("soulgold" to NATIVE_SOULGOLD, "soulgold_v12" to NATIVE_SOULGOLD_V1_2,
                "soulgold_v12b" to NATIVE_SOULGOLD_V1_2B, "soulgold_v12c" to NATIVE_SOULGOLD_V1_2C)) {
            assertEquals(fixture, "Box1", readPcBoxes(FixtureMemoryReader.load(fixture), cfg)?.boxes?.first()?.name)
        }
    }

    /** SoulGold's party copies decoded as box records: the level from the ROM's growth rate and the stats the game's
     * way equal what the party keeps (the plain nature, IVs and EVs where the box puts them). */
    @Test fun soulGoldStatsMatchTheParty() {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon SoulGold (v1.1.4).gba")
        assumeTrue("SoulGold v1.1.4 ROM not found", rom != null)
        PokedexSource.reader = rom!!
        activeGame = GameKind.SOULGOLD
        val cfg = NATIVE_SOULGOLD
        val raw = FixtureMemoryReader.load("soulgold_party").readCoreMemory(cfg.playerParty, 6 * cfg.monStride)
        val base = { s: Int -> PokedexSource.baseStats(cfg.pokedex!!, s) }
        val growth = { s: Int -> PokedexSource.growthRate(cfg.pokedex!!, s) }
        // The five that aren't the egg.
        for (i in 0 until 5) {
            val o = i * cfg.monStride
            val box = decodeBoxMon(raw, o, cfg.partyMonLayout, base, BoxRecordFormat.PLAIN, growth)!!
            assertEquals(raw[o + cfg.partyMonLayout.level].toInt(), box.level)
            val stored = List(6) { (raw[o + 0x54 + 2 * it].toInt() and 0xFF) or ((raw[o + 0x55 + 2 * it].toInt() and 0xFF) shl 8) }
            assertEquals(stored, box.stats!!.stats)
        }
        val boxes = readPcBoxes(FixtureMemoryReader.load("soulgold_party"), cfg)!!
        boxes.boxes.flatMap { it.mons }.filterNotNull().forEach { assertTrue(it.level in 1..100); assertNotNull(it.stats) }
    }

    /** Unbound (CFRU): 25 boxes, the game's own SendMonToPC having put its lead into boxes 1, 20, 23 and 25. */
    @Test fun readsUnboundBoxes() {
        activeGame = GameKind.UNBOUND
        val cfg = NATIVE_UNBOUND_WITH_DEX
        val boxes = readPcBoxes(FixtureMemoryReader.load("unbound_boxes"), cfg)!!
        assertEquals(CFRU_PC_BOXES, boxes.boxes.size)
        // Box 25 is CFRU's preset-teams box.
        assertEquals((1..24).map { "Box$it" } + "Preset", boxes.boxes.map { it.name })
        assertEquals(listOf(0, 19, 22, 24), boxes.boxes.indices.filter { boxes.boxes[it].mons.any { m -> m != null } })
        for (b in listOf(0, 19, 22, 24)) {
            val gible = boxes.boxes[b].mons[0]!!
            assertEquals(496, gible.species)
            assertEquals(0x42EA0623L, gible.personality)
            assertEquals(listOf(33, 28, 232, 0), gible.moves.toList())
        }
        // The save as it was: nothing boxed.
        assertEquals(0, readPcBoxes(FixtureMemoryReader.load("unbound"), cfg)!!.boxes.sumOf { b -> b.mons.count { it != null } })
    }

    /** Unbound's boxed GIBLE against itself in the party: its level (its growth rate from the ROM), stats and PP (the
     * moves' base PP from the ROM's gBattleMoves). */
    @Test fun unboundBoxMonMatchesTheParty() {
        val rom = RomFileReader.load(RomFileReader.UNBOUND_PATH)
        assumeTrue("Unbound ROM not found", rom != null)
        PokedexSource.reader = rom!!
        activeGame = GameKind.UNBOUND
        val cfg = NATIVE_UNBOUND_WITH_DEX
        val ram = rom.withRam(FixtureMemoryReader.load("unbound_boxes"))
        val party = decodePartyMon(ram.readCoreMemory(cfg.playerParty, MON_STRUCT_SIZE), 0, cfg.partyMonLayout)!!
        val gible = readPcBoxes(ram, cfg)!!.boxes[0].mons[0]!!
        assertEquals(11, gible.level)
        assertEquals(party.level, gible.level)
        assertEquals(party.stats!!.stats, gible.stats!!.stats)
        assertEquals(listOf(35, 15, 35, 0), gible.pp.toList())
        assertEquals(gible.hp, gible.maxHp)
    }
}
