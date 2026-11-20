package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Emerald's GUIDE: the live tables read from the retail ROM (skipped when it
 * isn't on this machine), checked against the pokeemerald decomp's own data
 * (wild_encounters.json, trainer_parties.h), and NEXT BOSS from a real save.
 */
class EmeraldGuideRomTest {
    private val t = GUIDE_TABLES_EMERALD

    private fun rom(): RomFileReader? = RomFileReader.load(RomFileReader.EMERALD_PATH)?.also {
        activeGame = GameKind.EMERALD
        PokedexSource.reader = it
    }

    @Test fun `tables are where the config says`() {
        val rom = rom()
        assumeTrue("retail Emerald ROM not on this machine", rom != null)
        assertTrue(guideTablesMatchRom(rom!!, t))
        assertFalse("FireRed's tables aren't in Emerald", guideTablesMatchRom(rom, GUIDE_TABLES_FIRERED_REV1))
    }

    /** gRoute101: WURMPLE and POOCHYENA in four slots each (20+10+10+5), ZIGZAGOON in the rare four. */
    @Test fun route101() {
        assumeTrue("retail Emerald ROM not on this machine", rom() != null)
        val e = GuideRomSource.encounters(t, 0, 16)!!
        assertEquals(
            listOf(EncounterSlot(290, 2, 3, 45), EncounterSlot(286, 2, 3, 45), EncounterSlot(288, 2, 3, 10)),
            e.grass,
        )
        assertTrue(e.water.isEmpty())
    }

    /** sParty_TateAndLiza1: custom moves and SITRUS BERRIES on the last two. */
    @Test fun tateAndLiza() {
        assumeTrue("retail Emerald ROM not on this machine", rom() != null)
        assertEquals(
            listOf(
                TrainerMon(319, 41, 0, listOf(89, 246, 94, 113)),
                TrainerMon(178, 41, 0, listOf(94, 241, 109, 347)),
                TrainerMon(348, 42, 142, listOf(113, 94, 95, 347)),
                TrainerMon(349, 42, 142, listOf(241, 76, 94, 53)),
            ),
            GuideRomSource.party(t, 271),
        )
    }

    /** The captured save (six badges, see EmeraldPokedexTest) is up to the MOSSDEEP CITY gym. */
    @Test fun `next boss from a real save`() {
        val progress = readSaveProgress(FixtureMemoryReader.load("emerald_vanilla"), NATIVE_EMERALD_RETAIL, t)
        assertNotNull(progress)
        assertEquals("LEADERS TATE AND LIZA", GUIDE_EMERALD.bosses.first { !progress!!.flag(it.done) }.title)
    }
}
