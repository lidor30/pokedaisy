package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Radical Red's GUIDE: its tables in the user's ROM (skipped when it isn't on
 * this machine), the seven fixed gym leaders' teams, NEXT BOSS from the real
 * save, and the WHERE IS generated from the ROM-read area data.
 */
class RadicalRedGuideTest {
    private val t = GUIDE_TABLES_RADICAL_RED

    private fun rom(): RomFileReader? = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Radical Red (v4.1).gba")?.also {
        activeGame = GameKind.RADICAL_RED
        PokedexSource.reader = it
    }

    @Test fun `tables are where the config says`() {
        val rom = rom()
        assumeTrue("Radical Red ROM not on this machine", rom != null)
        assertTrue(guideTablesMatchRom(rom!!, t))
    }

    @Test fun `gym leaders' teams`() {
        assumeTrue(rom() != null)
        for (boss in GUIDE_RADICAL_RED.bosses) {
            val id = boss.trainers(SaveProgress(ByteArray(0x120), ByteArray(0x200))).single()
            val team = GuideRomSource.party(t, id)!!
            assertTrue(boss.title, team.size in 2..6 && team.all { it.species > 0 && it.level in 1..100 && it.moves.isNotEmpty() })
        }
    }

    /** ROUTE 1 (map 3.19): a full grass table. */
    @Test fun route1() {
        assumeTrue(rom() != null)
        val e = GuideRomSource.encounters(t, 3, 19)!!
        assertEquals(100, e.grass.sumOf { it.percent })
    }

    /** Its wild Pokemon follow the clock (GitHub #941): ROUTE 1 has POOCHYENA (286) and HOOTHOOT (163) from 17:00 to 3:59,
     * MEOWTH (52) from 4:00 to 16:59, in header arrays of their own; a map without them (2.27) never switches. */
    @Test fun `route 1 by day and by night`() {
        assumeTrue(rom() != null)
        fun at(hour: Int) = SaveProgress(ByteArray(0x120), ByteArray(0x200), varBanks = listOf(SaveBank(GBA_VAR_HOUR, 0, 1) to byteArrayOf(hour.toByte(), 0)))
        // The clock's hour in the captures: 9:55 (the dex fixture) and 19:15 (the battle one).
        assertEquals(9, readSaveProgress(FixtureMemoryReader.load("radical_red_dex"), NATIVE_RADICAL_RED_V4_1, t)!!.variable(GBA_VAR_HOUR))
        assertEquals(19, readSaveProgress(FixtureMemoryReader.load("radical_red_battle"), NATIVE_RADICAL_RED_V4_1, t)!!.variable(GBA_VAR_HOUR))
        for (h in listOf(17, 20, 23, 0, 3)) assertEquals("$h:00", 1, t.wildSetOf(3, 19, at(h)))
        for (h in listOf(4, 9, 16)) assertEquals("$h:00", 0, t.wildSetOf(3, 19, at(h)))
        val day = GuideRomSource.encounters(t, 3, 19, 0)!!.grass.map { it.species }
        val night = GuideRomSource.encounters(t, 3, 19, 1)!!.grass.map { it.species }
        assertTrue(286 in night && 286 !in day)
        assertTrue(163 in night && 163 !in day)
        assertTrue(52 in day && 52 !in night)
        assertEquals(100, GuideRomSource.encounters(t, 3, 19, 1)!!.grass.sumOf { it.percent })
        assertEquals(2, GuideRomSource.encounterSets(t, 3, 19).size) // the DEX's AREA sees both
        assertEquals(1, GuideRomSource.encounterSets(t, 2, 27).size)
        assertEquals(GuideRomSource.encounters(t, 2, 27, 0), GuideRomSource.encounters(t, 2, 27, 1))
    }

    @Test fun `next boss from the real save`() {
        val progress = readSaveProgress(FixtureMemoryReader.load("radical_red"), NATIVE_RADICAL_RED_V4_1, t)
        assertNotNull(progress)
        val next = GUIDE_RADICAL_RED.bosses.firstOrNull { !it.isDone(progress!!) }
        assertTrue(next == null || next.title.startsWith("LEADER"))
    }

    @Test fun `generated WHERE IS`() {
        activeGame = GameKind.RADICAL_RED
        val page = generatedWhereIs(GuideId.RADICAL_RED, { "AREA $it" }) { false }!!
        val hms = page.sections.single { it.heading == "HMs" }.entries.map { it.title }
        assertTrue(hms.size >= 5)
        assertTrue(page.sections.single { it.heading == "KEY ITEMS" }.entries.size >= 10)
    }
}
