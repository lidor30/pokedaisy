package com.pokedaisy.app.companion.data

import com.pokedaisy.app.CompanionSupport
import com.pokedaisy.app.GameTitles
import com.pokedaisy.app.WideScreen
import com.pokedaisy.app.companion.DsLayout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The DS language ports ([NDS_PORTS], gamedata/nds/ports.toml): the US carts' code and save layouts at their own globals,
 * each checked headless on the real cart next to its US one (docs/DEVELOPMENT.md's "Other languages"). The reads here go
 * through the US fixtures with those globals moved; names and art are the player's carts', so they skip without the ROMs.
 */
class DsPortsTest {
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
    fun `every port is supported at its revision only, EXPAND included, with its own name`() {
        assertTrue(NDS_PORTS.isNotEmpty())
        for (p in NDS_PORTS) {
            assertTrue(p.code, supported(p.code, p.revision))
            assertTrue(p.code, WideScreen.ndsKnows(p.code, p.revision))
            assertFalse(p.code, supported(p.code, p.revision + 1))
            assertEquals(p.code, p.title, GameTitles.BY_SHA1[p.sha1])
            assertTrue(p.code, p.sha1 in GameTitles.NDS_SHA1S)
        }
    }

    @Test
    fun `the menus are followed where the cart's menu functions are known`() {
        for (p in NDS_PORTS) assertEquals(p.code, p.followed, DsLayout.followed(p.code))
        // German HeartGold / SoulSilver got their menu functions with the ports (pk_nds_field.cpp's TOUCH_RULES).
        assertTrue(DsLayout.followed("IPKD"))
        assertTrue(DsLayout.followed("IPGD"))
    }

    @Test
    fun `a port's config is its game's US one with its own globals`() {
        for (p in NDS_PORTS) {
            if (p.kind == GameKind.PLATINUM) {
                val cfg = gen4ConfigFor(p.kind, p.code)!!
                val us = GEN4_PLATINUM_US
                assertEquals(p.code, p.saveData, cfg.saveDataPtr)
                assertEquals(p.code, p.system, cfg.battle!!.fieldSystemPtr)
                assertEquals(p.code, p.battleTemplate, cfg.battle!!.battleTemplate)
                assertEquals(p.code, p.system, cfg.townMap!!.fieldSystemPtr)
                assertEquals(p.code, us.copy(saveDataPtr = cfg.saveDataPtr, battle = cfg.battle, townMap = cfg.townMap).toString(), cfg.toString())
            } else if (p.kind.isHgss) {
                val cfg = gen4ConfigFor(p.kind, p.code)!!
                val us = if (p.kind == GameKind.HEARTGOLD) GEN4_HEARTGOLD_US else GEN4_SOULSILVER_US
                assertEquals(p.code, p.saveData, cfg.saveDataPtr)
                assertEquals(p.code, p.system, cfg.battle!!.fieldSystemPtr)
                assertEquals(p.code, p.battleTemplate, cfg.battle!!.battleTemplate)
                assertEquals(p.code, p.system, cfg.pokegear!!.fieldSystemPtr)
                // As text: a data class holding arrays compares them by identity.
                assertEquals(p.code, us.copy(saveDataPtr = cfg.saveDataPtr, battle = cfg.battle, pokegear = cfg.pokegear).toString(), cfg.toString())
            } else {
                val cfg = gen5ConfigFor(p.kind, p.code)!!
                val us = gen5ConfigFor(p.kind, "")!!
                assertEquals(p.code, p.saveData, cfg.saveControlPtr)
                assertEquals(p.code, p.system, cfg.gameSystemPtr)
                assertEquals(p.code, p.system, cfg.battle!!.gameSystemPtr)
                assertEquals(p.code, us.copy(saveControlPtr = cfg.saveControlPtr, gameSystemPtr = cfg.gameSystemPtr, battle = cfg.battle).toString(), cfg.toString())
            }
        }
    }

    @Test
    fun `the saves read through each port's globals`() {
        for (p in NDS_PORTS) withGame(p.kind, p.code) {
            val fixture = p.kind.name.lowercase()
            val us = FixtureMemoryReader.load(fixture)
            if (p.kind == GameKind.PLATINUM || p.kind.isHgss) {
                val usCfg = gen4ConfigFor(p.kind, when (p.kind) { GameKind.PLATINUM -> "CPUE"; GameKind.HEARTGOLD -> "IPKE"; else -> "IPGE" })!!
                val cfg = gen4ConfigFor(p.kind, p.code)!!
                val moved = moved(us, mapOf(cfg.saveDataPtr to usCfg.saveDataPtr, p.system to usCfg.battle!!.fieldSystemPtr))
                assertSameSave(p.code, readGen4Telemetry(us, usCfg), readGen4Telemetry(moved, cfg))
            } else {
                val usCfg = gen5ConfigFor(p.kind, "")!!
                val cfg = gen5ConfigFor(p.kind, p.code)!!
                val moved = moved(us, mapOf(cfg.saveControlPtr to usCfg.saveControlPtr, cfg.gameSystemPtr to usCfg.gameSystemPtr))
                assertSameSave(p.code, readGen5Telemetry(us, usCfg), readGen5Telemetry(moved, cfg))
            }
        }
    }

    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")

    /** The ports' carts in the ROMs folder, by their No-Intro names (gamedata/nds/ports.toml's `cart`). */
    private val carts: Map<String, String> by lazy {
        val toml = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "gamedata/nds/ports.toml") }.first { it.isFile }.readText()
        Regex("""code = "(\w{4})"\s+rev = (\d+)[\s\S]*?cart = "([^"]+)"""").findAll(toml)
            .associate { "${it.groupValues[1]}:${it.groupValues[2]}" to it.groupValues[3] }
    }

    @Test
    fun `every port's names and pages come from its own cart`() {
        val present = NDS_PORTS.mapNotNull { p -> File(nds, carts.getValue("${p.code}:${p.revision}")).takeIf { it.isFile }?.let { p to it } }
        assumeTrue("no DS carts in $nds", present.isNotEmpty())
        val dir = kotlin.io.path.createTempDirectory("nds-ports").toFile()
        try {
            for ((p, rom) in present) {
                assertTrue(p.code, CompanionSupport.isSupported(rom))
                val n = NdsArt.readNames(rom)
                assertNotNull(p.code, n)
                // Europe rev 10 HeartGold / SoulSilver are English (the header's E is the US build's code).
                assertEquals(p.code, if (p.code[3] == 'E') 'E' else p.code[3], n!!.language)
                val bulbasaur = n.species(1)
                val expected = mapOf('D' to "bisasam", 'F' to "bulbizarre", 'J' to "フシギダネ", 'K' to "이상해씨").getOrDefault(p.code[3], "bulbasaur")
                assertEquals(p.code, expected, bulbasaur?.lowercase())
                assertEquals(p.code, mapOf('D' to "Meisterball", 'J' to "マスターボール", 'K' to "마스터볼").getOrDefault(p.code[3], "Master Ball"), n.item(1))
                // Each cart's art goes to its own folder here, so one cart's pages never stand for another's.
                val out = File(dir, "${p.code}-${p.revision}").apply { mkdirs() }
                assertTrue(p.code, NdsArt.extractTo(rom, out))
                val dex = when (p.kind) {
                    GameKind.PLATINUM -> NdsArt.PLATINUM_DEX
                    GameKind.HEARTGOLD, GameKind.SOULSILVER -> NdsArt.HEARTGOLD_DEX
                    GameKind.BLACK -> NdsArt.BLACK_DEX
                    GameKind.WHITE -> NdsArt.WHITE_DEX
                    else -> NdsArt.BLACK2_DEX
                }
                // BULBASAUR's category, in the cart's language: the DEX's text banks are the cart's.
                val seed = mapOf('E' to "Seed", 'S' to "Semilla", 'I' to "Seme", 'D' to "Samen", 'F' to "Graine", 'J' to "たね", 'K' to "씨앗")
                val bulbasaurPage = File(out, dex).readLines().first { it.startsWith("1\t") }.split('\t')
                assertTrue("${p.code}: ${bulbasaurPage[1]}", bulbasaurPage[1].contains(seed.getValue(p.code[3])))
                assertTrue("${p.code}: no description", bulbasaurPage[2].isNotBlank())
            }
        } finally { dir.deleteRecursively() }
    }

    /** [us]'s memory with the pointers [moved] holds (a port's address to the US one) read at the port's addresses. */
    private fun moved(us: MemoryReader, moved: Map<Long, Long>) = object : MemoryReader {
        override fun readCoreMemory(addr: Long, size: Int): ByteArray = when {
            addr in moved -> us.readCoreMemory(moved.getValue(addr), size)
            addr in moved.values -> ByteArray(size)
            else -> us.readCoreMemory(addr, size)
        }
    }

    private fun assertSameSave(what: String, a: Telemetry, b: Telemetry) {
        assertTrue(what, a.party.isNotEmpty())
        assertEquals(what, a.party.size, b.party.size)
        for ((x, y) in a.party.zip(b.party)) {
            assertArrayEquals(what, x.moves, y.moves)
            assertArrayEquals(what, x.pp, y.pp)
            assertEquals(what, x.copy(moves = y.moves, pp = y.pp), y)
        }
        assertEquals(what, a.items, b.items)
        assertEquals(what, a.money, b.money)
        assertEquals(what, a.mapNum, b.mapNum)
        assertEquals(what, a.pokedex, b.pokedex)
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
}
