package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Ruby / Sapphire's GUIDE: their own guide and area data (not Emerald's,
 * though they run as GameKind.EMERALD), the live tables read from the user's
 * ROMs (skipped when they aren't on this machine), checked against
 * pret/pokeruby's data (wild_encounters.json, trainer_parties.h), and NEXT
 * BOSS from the real saves.
 */
class RubySapphireGuideTest {
    private fun rom(file: String): RomFileReader? = RomFileReader.load(RETAIL_ROM_DIR + file)?.also {
        activeGame = GameKind.EMERALD
        PokedexSource.reader = it
    }

    @Test fun `each version gets its own guide`() {
        assertEquals(GuideId.RUBY, guideId(GameKind.EMERALD, GUIDE_TABLES_RUBY))
        assertEquals(GuideId.SAPPHIRE, guideId(GameKind.EMERALD, NATIVE_SAPPHIRE.guideTables))
        assertEquals(GuideId.EMERALD, guideId(GameKind.EMERALD, null)) // no live tables
        assertEquals(GuideId.LEAFGREEN, guideId(GameKind.FIRERED, GUIDE_TABLES_LEAFGREEN_REV1))
        val ruby = gameGuide(GuideId.RUBY)!!
        val sapphire = gameGuide(GuideId.SAPPHIRE)!!
        assertEquals("CHAMPION STEVEN", ruby.bosses.last().title)
        assertEquals("LEADER WALLACE", ruby.bosses[7].title)
        fun titles(g: GameGuide) = g.pages.flatMap { p -> p.sections.flatMap { s -> s.entries.map { it.title } } }
        assertTrue("GROUDON" in titles(ruby) && "KYOGRE" !in titles(ruby))
        assertTrue("KYOGRE" in titles(sapphire) && "LATIAS" in titles(sapphire))
        assertFalse(titles(ruby).any { "CHIKORITA" in it }) // Emerald-only gift
    }

    @Test fun `LeafGreen lists its own GAME CORNER prizes`() {
        fun prizes(id: GuideId) = gameGuide(id)!!.pages.flatMap { p -> p.sections.flatMap { it.entries } }
            .single { it.title == "GAME CORNER prizes" }.answer
        assertTrue("SCYTHER" in prizes(GuideId.FIRERED) && "PINSIR" !in prizes(GuideId.FIRERED))
        assertTrue("PINSIR Lv18 (2500)" in prizes(GuideId.LEAFGREEN) && "SCYTHER" !in prizes(GuideId.LEAFGREEN))
    }

    @Test fun `tables are where the configs say`() {
        for ((file, t) in listOf(RUBY_ROM to GUIDE_TABLES_RUBY, SAPPHIRE_ROM to GUIDE_TABLES_SAPPHIRE)) {
            val rom = rom(file)
            assumeTrue("$file not on this machine", rom != null)
            assertTrue(guideTablesMatchRom(rom!!, t))
            assertFalse("Emerald's tables aren't in $file", guideTablesMatchRom(rom, GUIDE_TABLES_EMERALD))
        }
    }

    /** Route101_Ruby: WURMPLE, ZIGZAGOON and POOCHYENA. */
    @Test fun route101() {
        assumeTrue(rom(RUBY_ROM) != null)
        val e = GuideRomSource.encounters(GUIDE_TABLES_RUBY, 0, 16)!!
        assertEquals(setOf(290, 288, 286), e.grass.map { it.species }.toSet())
        assertEquals(100, e.grass.sumOf { it.percent })
    }

    /** gTrainerParty_Steven: six custom-move Pokémon, METAGROSS Lv58 last with a SITRUS BERRY. */
    @Test fun steven() {
        for ((file, t) in listOf(RUBY_ROM to GUIDE_TABLES_RUBY, SAPPHIRE_ROM to GUIDE_TABLES_SAPPHIRE)) {
            assumeTrue(rom(file) != null)
            val team = GuideRomSource.party(t, 335)!!
            assertEquals(listOf(57, 55, 56, 56, 56, 58), team.map { it.level })
            assertEquals(227, team[0].species) // SKARMORY
            assertEquals(TrainerMon(400, 58, 142, team[5].moves), team[5])
            assertEquals(4, team[5].moves.size)
        }
    }

    /** Both saves are post-game: every badge, and the HALL OF FAME. */
    @Test fun `next boss from the real saves`() {
        for ((fixture, cfg) in listOf("ruby_rev1" to NATIVE_RUBY, "sapphire_rev1" to NATIVE_SAPPHIRE)) {
            val progress = readSaveProgress(FixtureMemoryReader.load(fixture), cfg, cfg.guideTables!!)
            assertNotNull(progress)
            val guide = gameGuide(cfg.guideTables!!.guide)!!
            assertTrue(guide.bosses.take(8).all { progress!!.flag(it.done) })
            assertTrue(progress!!.flag(guide.bosses.last().done))
        }
    }

    /** pokeruby's own maps: the CUTTER's HM01 in RUSTBORO CITY, and its MAKUHITA-for-SLAKOTH trade. */
    @Test fun areas() {
        val rustboro = areaThings(GuideId.RUBY, 10)
        assertEquals("CUTTERS HOUSE", rustboro.single { it.kind == AreaKind.GIFT && it.id == 339 }.where)
        assertTrue(rustboro.any { it.kind == AreaKind.TRADE && it.id == 335 && it.wants == 364 })
        assertEquals(rustboro, areaThings(GuideId.SAPPHIRE, 10))
    }

    private companion object {
        const val RUBY_ROM = "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba"
        const val SAPPHIRE_ROM = "Pokemon - Sapphire Version (USA, Europe) (Rev 1).gba"
    }
}
