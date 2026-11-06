package com.pokedaisy.app.companion.data

import com.pokedaisy.app.CompanionSupport
import com.pokedaisy.app.GameTitles
import com.pokedaisy.app.WideScreen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The French DS carts (Platinum rev 0, HeartGold / SoulSilver, Black, White): the US carts' code and save layouts at their
 * own globals, checked headless on the real carts (docs/DEVELOPMENT.md's "French carts"). The reads here go through the US
 * fixtures with those globals moved; names and art are the player's cart's, so they skip without the ROMs.
 */
class FrenchDsTest {
    private fun supported(code: String, rev: Int): Boolean {
        val f = File.createTempFile("nds-header-", ".nds")
        try {
            val header = ByteArray(0x200)
            code.toByteArray(Charsets.US_ASCII).copyInto(header, 0x0C)
            header[0x1E] = rev.toByte()
            header[0x15C] = 0x56
            header[0x15D] = 0xCF.toByte()
            f.writeBytes(header)
            return CompanionSupport.isSupported(f)
        } finally { f.delete() }
    }

    @Test
    fun `French rev 0 is supported, EXPAND included, and other revisions aren't`() {
        for (code in listOf("CPUF", "IPKF", "IPGF", "IRBF", "IRAF")) {
            assertTrue(code, supported(code, 0))
            assertTrue(code, WideScreen.ndsKnows(code, 0))
            assertFalse(code, supported(code, 1))
            assertFalse(code, WideScreen.ndsKnows(code, 1))
        }
    }

    @Test
    fun `each cart has its name`() {
        assertEquals("Pokémon Version Platine", GameTitles.BY_SHA1["62f9ec7a412d06bd518f7dc065e4612812e327b9"])
        assertEquals("Pokémon Version Or HeartGold", GameTitles.BY_SHA1["dba64620a959d5f69ed1cf3e43fcfcf2786d417e"])
        assertEquals("Pokémon Version Argent SoulSilver", GameTitles.BY_SHA1["6ed98d446eb65861c71fc5f94bdc35691667f665"])
        assertEquals("Pokémon Version Noire", GameTitles.BY_SHA1["a559da54839663d8f8f3f03382e5665a61873d22"])
        assertEquals("Pokémon Version Blanche", GameTitles.BY_SHA1["28f075915dd445cbfd902f1e1a94d5048b03a095"])
    }

    @Test
    fun `the game code picks the French configs, and the US ones stay as they were`() {
        assertSame(GEN4_PLATINUM_FR, gen4ConfigFor(GameKind.PLATINUM, "CPUF"))
        assertSame(GEN4_PLATINUM_US, gen4ConfigFor(GameKind.PLATINUM, "CPUE"))
        assertSame(GEN4_HEARTGOLD_FR, gen4ConfigFor(GameKind.HEARTGOLD, "IPKF"))
        assertSame(GEN4_SOULSILVER_FR, gen4ConfigFor(GameKind.SOULSILVER, "IPGF"))
        assertSame(GEN5_BLACK_FR, gen5ConfigFor(GameKind.BLACK, "IRBF"))
        assertSame(GEN5_BLACK, gen5ConfigFor(GameKind.BLACK, "IRBO"))
        assertSame(GEN5_WHITE_FR, gen5ConfigFor(GameKind.WHITE, "IRAF"))
        assertSame(GEN5_WHITE, gen5ConfigFor(GameKind.WHITE, "IRAO"))
    }

    @Test
    fun `every read of a French cart follows its own globals`() {
        assertEquals(0x021C09BCL, GEN4_PLATINUM_FR.battle!!.fieldSystemPtr)
        assertEquals(0x020EA3E0L, GEN4_PLATINUM_FR.battle!!.battleTemplate)
        assertEquals(0x021C09BCL, GEN4_PLATINUM_FR.townMap!!.fieldSystemPtr)
        for (cfg in listOf(GEN4_HEARTGOLD_FR, GEN4_SOULSILVER_FR)) {
            assertEquals(0x021D4178L, cfg.battle!!.fieldSystemPtr)
            assertEquals(0x020FA468L, cfg.battle!!.battleTemplate)
            assertEquals(0x021D4178L, cfg.pokegear!!.fieldSystemPtr)
        }
        assertEquals(GEN5_BLACK_FR.gameSystemPtr, GEN5_BLACK_FR.battle!!.gameSystemPtr)
        assertEquals(GEN5_WHITE_FR.gameSystemPtr, GEN5_WHITE_FR.battle!!.gameSystemPtr)
    }

    /** [us]'s memory with the pointers [moved] holds (French address to US address) read at the French addresses. */
    private fun moved(us: MemoryReader, moved: Map<Long, Long>) = object : MemoryReader {
        override fun readCoreMemory(addr: Long, size: Int): ByteArray = when {
            addr in moved -> us.readCoreMemory(moved.getValue(addr), size)
            addr in moved.values -> ByteArray(size)
            else -> us.readCoreMemory(addr, size)
        }
    }

    private fun assertSameSave(fixture: String, a: Telemetry, b: Telemetry) {
        assertEquals(fixture, a.party.size, b.party.size)
        for ((x, y) in a.party.zip(b.party)) {
            assertArrayEquals(fixture, x.moves, y.moves)
            assertArrayEquals(fixture, x.pp, y.pp)
            assertEquals(fixture, x.copy(moves = y.moves, pp = y.pp), y)
        }
        assertEquals(fixture, a.items, b.items)
        assertEquals(fixture, a.money, b.money)
        assertEquals(fixture, a.mapNum, b.mapNum)
        assertEquals(fixture, a.pokedex, b.pokedex)
    }

    private fun withGame(kind: GameKind, code: String, block: () -> Unit) {
        val oldGame = activeGame
        val oldNames = NdsArt.names
        val oldCode = romGameCode
        val oldLanguage = romLanguage
        try {
            NdsArt.names = null
            activeGame = kind
            romGameCode = code
            romLanguage = 'E'
            block()
        } finally {
            activeGame = oldGame; NdsArt.names = oldNames; romGameCode = oldCode; romLanguage = oldLanguage
        }
    }

    @Test
    fun `the Gen 4 saves read through the French globals`() {
        data class Case(val kind: GameKind, val code: String, val fixture: String, val us: Gen4Config, val fr: Gen4Config, val fieldPtr: Pair<Long, Long>)
        for (c in listOf(
            Case(GameKind.PLATINUM, "CPUF", "platinum", GEN4_PLATINUM_US, GEN4_PLATINUM_FR, 0x021C09BCL to 0x021C07DCL),
            Case(GameKind.HEARTGOLD, "IPKF", "heartgold", GEN4_HEARTGOLD_US, GEN4_HEARTGOLD_FR, 0x021D4178L to 0x021D4158L),
            Case(GameKind.SOULSILVER, "IPGF", "soulsilver", GEN4_SOULSILVER_US, GEN4_SOULSILVER_FR, 0x021D4178L to 0x021D4158L),
        )) withGame(c.kind, c.code) {
            val us = FixtureMemoryReader.load(c.fixture)
            val french = moved(us, mapOf(c.fr.saveDataPtr to c.us.saveDataPtr, c.fieldPtr))
            assertSameSave(c.fixture, readGen4Telemetry(us, c.us), readGen4Telemetry(french, c.fr))
        }
    }

    @Test
    fun `the Gen 5 saves read through the French statics`() {
        data class Case(val kind: GameKind, val code: String, val fixture: String, val us: Gen5Config, val fr: Gen5Config)
        for (c in listOf(
            Case(GameKind.BLACK, "IRBF", "black", GEN5_BLACK, GEN5_BLACK_FR),
            Case(GameKind.WHITE, "IRAF", "white", GEN5_WHITE, GEN5_WHITE_FR),
        )) withGame(c.kind, c.code) {
            val us = FixtureMemoryReader.load(c.fixture)
            val french = moved(us, mapOf(c.fr.saveControlPtr to c.us.saveControlPtr, c.fr.gameSystemPtr to c.us.gameSystemPtr))
            assertSameSave(c.fixture, readGen5Telemetry(us, c.us), readGen5Telemetry(french, c.fr))
        }
    }

    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")

    private fun rom(name: String) = File(nds, name).also { assumeTrue("no $it", it.isFile) }

    @Test
    fun `Platinum's names and art come from its cart`() {
        val rom = rom("Pokemon - Version Platine (France).nds")
        assertTrue(CompanionSupport.isSupported(rom))
        val n = NdsArt.readNames(rom)!!
        assertEquals('F', n.language)
        assertEquals("BULBIZARRE", n.species(1))
        assertEquals("Bonaugure", n.place(GEN4_PLATINUM_FR.mapPlaceIds.getValue(415)))
        val dir = kotlin.io.path.createTempDirectory("nds-fr").toFile()
        try {
            assertTrue(NdsArt.extractTo(rom, dir))
            assertTrue(File(dir, "${NdsArt.PLATINUM_ICONS}/387.png").isFile)
            val page = File(dir, NdsArt.PLATINUM_DEX).readLines().first().split('\t')
            assertTrue(page[1], page[1].contains("Graine"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `HeartGold's and SoulSilver's names and pages are their own`() {
        val dir = kotlin.io.path.createTempDirectory("nds-fr").toFile()
        try {
            val pages = listOf("Pokemon - Version Or HeartGold (France).nds", "Pokemon - Version Argent SoulSilver (France).nds").map { name ->
                val rom = rom(name)
                val n = NdsArt.readNames(rom)!!
                assertEquals('F', n.language)
                assertEquals("BULBIZARRE", n.species(1))
                assertEquals("Doublonville", n.place(GEN4_HEARTGOLD_FR.mapPlaceIds.getValue(76)))
                assertTrue(NdsArt.extractTo(rom, dir))
                File(dir, NdsArt.HEARTGOLD_DEX).readLines()
            }
            assertEquals(493, pages[0].size)
            assertTrue(pages[0] != pages[1])
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `Black's and White's names and art come from their carts`() {
        val dir = kotlin.io.path.createTempDirectory("nds-fr").toFile()
        try {
            for ((name, dex) in listOf(
                "Pokemon - Version Noire (France) (NDSi Enhanced).nds" to NdsArt.BLACK_DEX,
                "Pokemon - Version Blanche (France) (NDSi Enhanced).nds" to NdsArt.WHITE_DEX,
            )) {
                val rom = rom(name)
                val n = NdsArt.readNames(rom)!!
                assertEquals('F', n.language)
                assertEquals("Bulbizarre", n.species(1))
                assertTrue(NdsArt.extractTo(rom, dir))
                assertTrue(File(dir, dex).readLines().first().split('\t')[1].contains("Graine"))
            }
        } finally { dir.deleteRecursively() }
    }
}
