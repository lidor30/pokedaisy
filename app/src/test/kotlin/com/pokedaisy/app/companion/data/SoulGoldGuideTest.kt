package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SoulGoldGuideTest {
    private val t = GUIDE_TABLES_SOULGOLD_V1_2

    private fun rom(): RomFileReader? {
        val candidates = listOfNotNull(
            System.getProperty("soulgoldRom"),
            RETAIL_ROM_DIR + "Soulgold (v1.2).gba",
            RETAIL_ROM_DIR + "Pokemon-SoulGold-v1.2.gba",
        )
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
        return file?.let {
            RomFileReader.load(it.absolutePath)?.also { reader ->
                activeGame = GameKind.SOULGOLD
                PokedexSource.reader = reader
            }
        }
    }

    @Test fun `guide exists and has bosses and pages`() {
        val guide = gameGuide(GuideId.SOULGOLD)
        assertNotNull(guide)
        assertEquals(22, guide!!.bosses.size)
        assertEquals("LEADER FALKNER", guide.bosses[0].title)
        assertEquals("GOLD / CRYSTAL", guide.bosses.last().title)

        val pageTitles = guide.pages.map { it.title }
        assertTrue("TIPS" in pageTitles)
        assertTrue("LEGENDARIES" in pageTitles)
        assertTrue("GROTTOS" in pageTitles)
        assertTrue("MEGA EVOLUTIONS" in pageTitles)
        assertTrue("ACHIEVEMENTS" in pageTitles)
        assertTrue("GIFTS & TRADES" in pageTitles)
    }

    @Test fun `tables match rom and probe succeeds`() {
        val r = rom()
        assumeTrue("SoulGold ROM not found", r != null)
        assertTrue(guideTablesMatchRom(r!!, t))
    }

    @Test fun `boss parties have correct levels, species, items and moves`() {
        val r = rom()
        assumeTrue("SoulGold ROM not found", r != null)

        // Falkner (trainer 19): Pidgey Lv11, Gligar Lv11, Pidgeotto Lv12 (holding Oran Berry 520)
        val falkner = GuideRomSource.party(t, 19)
        assertNotNull(falkner)
        assertEquals(3, falkner!!.size)
        assertEquals(listOf(11, 11, 12), falkner.map { it.level })
        assertEquals(listOf(16, 207, 17), falkner.map { it.species })
        assertEquals(listOf("Pidgey", "Gligar", "Pidgeotto"), falkner.map { speciesName(it.species) })
        assertEquals(520, falkner[2].heldItem)
        assertEquals(listOf(33, 16, 98), falkner[0].moves) // Tackle, Gust, Quick Attack

        // Bugsy (trainer 596): Scyther Lv20, Whirlipede Lv19, Ribombee Lv19
        val bugsy = GuideRomSource.party(t, 596)
        assertNotNull(bugsy)
        assertEquals(3, bugsy!!.size)
        assertEquals(listOf(20, 19, 19), bugsy.map { it.level })
        assertEquals(listOf(123, 544, 742), bugsy.map { it.species })

        // Whitney (trainer 604): Audino Lv25, Girafarig Lv26, Miltank Lv27
        val whitney = GuideRomSource.party(t, 604)
        assertNotNull(whitney)
        assertEquals(3, whitney!!.size)
        assertEquals(listOf(25, 26, 27), whitney.map { it.level })
        assertEquals(listOf(531, 203, 241), whitney.map { it.species })
        assertEquals(listOf("Audino", "Girafarig", "Miltank"), whitney.map { speciesName(it.species) })
    }

    @Test fun `next boss advances to Whitney when Falkner and Bugsy are defeated`() {
        val guide = gameGuide(GuideId.SOULGOLD)!!
        val badge1 = 0x98C + 0x7 // FLAG_BADGE01_GET = 2451
        val badge2 = badge1 + 1 // FLAG_BADGE02_GET = 2452

        fun saveWithFlags(vararg flags: Int) = SaveProgress(
            ByteArray(t.flagBytes).also { b ->
                flags.forEach { b[it / 8] = (b[it / 8].toInt() or (1 shl (it % 8))).toByte() }
            },
            ByteArray(t.varCount * 2)
        )

        // When no badges are beaten, next boss is Falkner
        val newSave = saveWithFlags()
        assertEquals("LEADER FALKNER", guide.bosses.first { !it.isDone(newSave) }.title)

        // When Falkner and Bugsy are beaten (Badges 1 and 2), next boss is Whitney
        val midSave = saveWithFlags(badge1, badge2)
        assertTrue(guide.bosses[0].isDone(midSave)) // Falkner is done
        assertTrue(guide.bosses[1].isDone(midSave)) // Bugsy is done
        assertFalse(guide.bosses[2].isDone(midSave)) // Whitney is not done
        assertEquals("LEADER WHITNEY", guide.bosses.first { !it.isDone(midSave) }.title)

        // Verify that with the old truncated 0x130 (304 bytes) flag array, badge 1 (byte 306) would be out-of-bounds and read false
        val truncatedSave = SaveProgress(ByteArray(0x130), ByteArray(0x200))
        assertFalse("Truncated flag array causes badge 1 to be missed", truncatedSave.flag(badge1))
        // But with t.flagBytes (0x2AA = 682 bytes), it is well within range:
        assertTrue("Full flag array holds badge 1", midSave.flag(badge1))
        assertTrue("Full flag array holds badge 2", midSave.flag(badge2))
    }

    @Test fun `middle gyms scale with other defeated flags`() {
        val chuck = GUIDE_SOULGOLD.bosses.single { it.title == "LEADER CHUCK" }
        fun save(vararg flags: Int) = SaveProgress(
            ByteArray(t.flagBytes).also { b -> flags.forEach { b[it / 8] = (b[it / 8].toInt() or (1 shl (it % 8))).toByte() } },
            ByteArray(t.varCount * 2)
        )
        // middleGym(510, 442, 538, FLAG_DEFEATED_OLIVINE_CITY_GYM (0x4F5), FLAG_DEFEATED_MAHOGANY_TOWN_GYM (0x4F6))
        assertEquals(510, chuck.trainer(save()))
        assertEquals(442, chuck.trainer(save(0x4F5)))
        assertEquals(538, chuck.trainer(save(0x4F5, 0x4F6)))
    }

    @Test fun `generated WHERE IS from SoulGold area data`() {
        activeGame = GameKind.SOULGOLD
        val page = generatedWhereIs(GuideId.SOULGOLD, { "AREA $it" }) { false }
        assertNotNull(page)
        val hms = page!!.sections.single { it.heading == "HMs" }.entries.map { it.title }
        assertTrue(hms.any { it.startsWith("HM01") })
        val keys = page.sections.single { it.heading == "KEY ITEMS" }.entries
        assertTrue(keys.isNotEmpty())
        assertTrue(keys.all { !it.title.startsWith("Item#") })
    }

    @Test fun `area things contains Johto locations`() {
        // Mapsec 0xE8 = New Bark Town, 0xCD = Cherrygrove City
        val newBarkThings = areaThings(GuideId.SOULGOLD, 0xE8)
        assertTrue(newBarkThings.isNotEmpty())
    }

    @Test fun `readSaveProgress from fixture loads flags and starter var correctly`() {
        val ram = FixtureMemoryReader.load("soulgold_v12")
        val progress = readSaveProgress(ram, NATIVE_SOULGOLD_V1_2, t)
        assertNotNull(progress)
        // In the fixture, Cyndaquil was chosen as starter (VAR_STARTER_SPECIES = 0x4051)
        assertEquals(155, progress!!.variable(0x4051))
        // System flags set at start of game
        val sysFlags = 0x98C
        assertTrue("Has starter", progress.flag(sysFlags)) // FLAG_SYS_POKEMON_GET
        assertTrue("Has running shoes", progress.flag(sysFlags + 0x60)) // FLAG_SYS_B_DASH
        assertTrue("Has Pokégear", progress.flag(sysFlags + 2)) // FLAG_SYS_POKENAV_GET
        assertFalse("No Falkner badge yet in Cherrygrove", progress.flag(sysFlags + 7))

        val guide = gameGuide(GuideId.SOULGOLD)!!
        // In Cherrygrove, Falkner is indeed the next boss
        assertEquals("LEADER FALKNER", guide.bosses.first { !it.isDone(progress) }.title)
    }
}
