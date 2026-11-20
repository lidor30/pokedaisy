package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Unbound's GUIDE: its tables in the user's ROM (skipped when it isn't on this
 * machine), every gym leader's difficulty teams, NEXT BOSS from the real save,
 * and the WHERE IS generated from the ROM-read area data.
 */
class UnboundGuideTest {
    private val t = GUIDE_TABLES_UNBOUND

    private fun rom(): RomFileReader? = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Unbound (v2.1.1.1).gba")?.also {
        activeGame = GameKind.UNBOUND
        PokedexSource.reader = it
    }

    @Test fun `tables are where the config says`() {
        val rom = rom()
        assumeTrue("Unbound ROM not on this machine", rom != null)
        assertTrue(guideTablesMatchRom(rom!!, t))
    }

    /** Every variant is that leader's own team: 2-6 Pokémon with moves (VANILLA teams carry
     * none of their own: theirs come from CFRU's learnsets), bigger on harder difficulties. */
    @Test fun `gym leaders' teams`() {
        assumeTrue(rom() != null)
        for (boss in GUIDE_UNBOUND.bosses) {
            val sizes = boss.variants.map { (label, id) ->
                val team = GuideRomSource.party(t, id)!!
                assertTrue("${boss.title} $label", team.size in 2..6 && team.all { it.species > 0 && it.level in 1..100 && it.moves.isNotEmpty() })
                team.size
            }
            assertTrue("${boss.title}: VANILLA no bigger than INSANE", sizes.first() <= sizes.last())
        }
        assertEquals(listOf(19, 18, 16), GuideRomSource.party(t, 6)!!.map { it.level }) // MIRSKLE, DIFFICULT
        assertEquals(4, GuideRomSource.party(t, 6)!![0].moves.size)
    }

    @Test fun `next boss from the real save`() {
        val progress = readSaveProgress(FixtureMemoryReader.load("unbound"), NATIVE_UNBOUND_WITH_DEX, t)
        assertNotNull(progress)
        val next = GUIDE_UNBOUND.bosses.first { !it.isDone(progress!!) }
        assertTrue(next.title.startsWith("LEADER"))
    }

    @Test fun `generated WHERE IS`() {
        activeGame = GameKind.UNBOUND
        val page = generatedWhereIs(GuideId.UNBOUND, { "AREA $it" }) { false }!!
        val keys = page.sections.single { it.heading == "KEY ITEMS" }.entries.map { it.title }
        assertTrue("Old Rod" in keys && "Go-Goggles" in keys)
        assertTrue(page.sections.any { it.heading == "GIFT POKéMON" && it.entries.size >= 10 })
    }
}
