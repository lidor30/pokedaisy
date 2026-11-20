package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Heart and Soul's GUIDE: the expansion trainer / wild layouts read from the
 * release ROM (skipped when it isn't on this machine), checked against its
 * source (trainers.party, wild_encounters.json), NEXT BOSS from the real
 * save, and the WHERE IS page generated from its area data.
 */
class HeartAndSoulGuideTest {
    private val t = GUIDE_TABLES_HEART_AND_SOUL

    private fun rom(): RomFileReader? = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Heart and Soul (v2.0.6).gba")?.also {
        activeGame = GameKind.HEART_AND_SOUL
        PokedexSource.reader = it
    }

    @Test fun `tables are where the config says`() {
        val rom = rom()
        assumeTrue("Heart and Soul ROM not on this machine", rom != null)
        assertTrue(guideTablesMatchRom(rom!!, t))
        assertFalse(guideTablesMatchRom(rom, GUIDE_TABLES_EMERALD))
    }

    /** TRAINER_FALKNER_1_HNS: PIDGEY Lv8 (TACKLE, SAND ATTACK, QUICK ATTACK), NOCTOWL Lv11. */
    @Test fun falkner() {
        assumeTrue(rom() != null)
        val team = GuideRomSource.party(t, 402)!!
        assertEquals(listOf(16 to 8, 164 to 11), team.map { it.species to it.level })
        assertEquals(listOf(33, 28, 98), team[0].moves)
    }

    /** gRoute29_hns_Day: PIDGEY, SENTRET, HOPPIP, RATTATA, HOOTHOOT (the day table, not the night one). */
    @Test fun route29() {
        assumeTrue(rom() != null)
        val e = GuideRomSource.encounters(t, 0, 11)!!
        assertEquals(setOf(16, 161, 187, 19, 163), e.grass.map { it.species }.toSet())
        assertEquals(16, e.grass.first().species) // PIDGEY is the most common by day
        assertEquals(100, e.grass.sumOf { it.percent })
    }

    /** The save (CYNDAQUIL, no badges yet) is up to FALKNER. */
    @Test fun `next boss from the real save`() {
        val progress = readSaveProgress(FixtureMemoryReader.load("heart_and_soul"), NATIVE_HEART_AND_SOUL, t)
        assertNotNull(progress)
        val guide = gameGuide(guideId(GameKind.HEART_AND_SOUL, t))!!
        assertEquals("LEADER FALKNER", guide.bosses.first { !it.isDone(progress!!) }.title)
        assertEquals(1, progress!!.variable(0x4023)) // VAR_STARTER_MON: CYNDAQUIL
    }

    /** CHUCK / JASMINE / PRYCE: _1, _1_2, _1_3 by how many of the other two are beaten. */
    @Test fun `middle gyms scale with the others beaten`() {
        val chuck = GUIDE_HEART_AND_SOUL.bosses.single { it.title == "LEADER CHUCK" }
        fun save(vararg flags: Int) = SaveProgress(ByteArray(0x130).also { b -> flags.forEach { b[it / 8] = (b[it / 8].toInt() or (1 shl (it % 8))).toByte() } }, ByteArray(0x200))
        assertEquals(418, chuck.trainer(save()))
        assertEquals(420, chuck.trainer(save(0x22C)))
        assertEquals(421, chuck.trainer(save(0x22C, 0x22D)))
        val will = GUIDE_HEART_AND_SOUL.bosses.single { it.title == "ELITE FOUR WILL" }
        assertFalse(will.isDone(save()))
        assertTrue(will.isDone(save(0x860 + 0x3F))) // champion
    }

    /** WHERE IS from the area data: the HMs and the BICYCLE's shop. */
    @Test fun `generated WHERE IS`() {
        activeGame = GameKind.HEART_AND_SOUL
        val page = generatedWhereIs(GuideId.HEART_AND_SOUL, { "AREA $it" }) { false }!!
        val hms = page.sections.single { it.heading == "HMs" }.entries.map { it.title }
        assertTrue("HM01" in hms)
        val keys = page.sections.single { it.heading == "KEY ITEMS" }.entries
        assertTrue(keys.size >= 10)
        assertTrue(keys.all { !it.title.startsWith("Item#") })
    }
}
