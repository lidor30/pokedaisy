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
}
