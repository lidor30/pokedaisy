package com.pokedaisy.app.companion.data

import com.pokedaisy.app.CompanionSupport
import com.pokedaisy.app.GameTitles
import com.pokedaisy.app.WideScreen
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class HeartGoldGermanTest {
    @Test
    fun `German rev 0 is supported and other revisions and unmapped regions stay unsupported`() {
        fun supported(code: String, rev: Int): Boolean {
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
        assertTrue(supported("IPKD", 0))
        assertTrue(WideScreen.ndsKnows("IPKD", 0))
        assertTrue(supported("IPGD", 0))
        assertTrue(WideScreen.ndsKnows("IPGD", 0))
        for ((code, rev) in listOf("IPKD" to 1, "IPKD" to 255, "IPKF" to 0, "CPUD" to 0, "CPUF" to 0, "IPGD" to 1, "IPGD" to 255)) {
            assertFalse("$code rev $rev", supported(code, rev))
            assertFalse("$code rev $rev", WideScreen.ndsKnows(code, rev))
        }
        assertEquals("Pokémon Goldene Edition HeartGold", GameTitles.BY_SHA1["13b6853aa93121069df1d17446d3ecb36872aecf"])
        assertEquals("Pokémon Silberne Edition SoulSilver", GameTitles.BY_SHA1["576cdb7eec728c49aed85b9dd7e1e084ba1f08f9"])
    }

    @Test
    fun `field actions resolve the same regional config as the sampler`() {
        val oldCode = romGameCode
        try {
            romGameCode = "IPKD"
            assertSame(GEN4_HEARTGOLD_DE, gen4ConfigFor(GameKind.HEARTGOLD))
            assertSame(GEN4_HEARTGOLD_US, gen4ConfigFor(GameKind.HEARTGOLD, "IPKE"))
            assertSame(GEN4_SOULSILVER_DE, gen4ConfigFor(GameKind.SOULSILVER, "IPGD"))
            assertSame(GEN4_SOULSILVER_US, gen4ConfigFor(GameKind.SOULSILVER, "IPGE"))
        } finally { romGameCode = oldCode }
    }

    // Before SaveData exists (boot/title), and for corrupt/out-of-range globals, reads must
    // stop at the global itself. Include the first address without enough room for SaveData.
    private val invalidSavePointers = listOf(0L, 0x01FFFFFFL, 0x023E0000L, 0x02400000L, 0xFFFFFFFFL)
    private val germanConfigs = listOf(GEN4_HEARTGOLD_DE, GEN4_SOULSILVER_DE)

    private fun pointerReader(cfg: Gen4Config, ptr: Long): (Long, Int) -> ByteArray = { addr, size ->
        assertEquals("must not dereference invalid SaveData 0x${ptr.toString(16)}", cfg.saveDataPtr, addr)
        assertEquals(4, size)
        java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(ptr.toInt()).array()
    }

    @Test
    fun `German missing or invalid SaveData has no telemetry or map`() {
        for (cfg in germanConfigs) for (ptr in invalidSavePointers) {
            val readPointer = pointerReader(cfg, ptr)
            val memory = object : MemoryReader {
                override fun readCoreMemory(addr: Long, size: Int) = readPointer(addr, size)
            }
            val t = readGen4Telemetry(memory, cfg)
            assertEquals(0, t.partyCount)
            assertTrue(t.party.isEmpty())
            assertEquals(0, t.itemCount)
            assertTrue(t.items.isEmpty())
            assertEquals(-1, t.regionMapSectionId)
            assertNull(t.regionMapTile)
            assertNull(t.pokedex)
            assertNull(gen4PokegearBlock(readPointer, cfg))
            assertTrue(gen4OpenFlySpots(readPointer, cfg).isEmpty())
        }
    }

    @Test
    fun `German Repels refuse missing or invalid SaveData without writes`() {
        for (cfg in germanConfigs) for (ptr in invalidSavePointers) {
            val readPointer = pointerReader(cfg, ptr)
            var writes = 0
            val calls = object : GameCalls {
                override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long =
                    error("invalid SaveData must not call game code")
                override fun read(addr: Long, len: Int) = readPointer(addr, len)
                override fun write(addr: Long, bytes: ByteArray): Boolean { writes++; return true }
            }
            for ((item, duration) in GEN4_REPEL_KINDS) {
                assertEquals(ItemUseResult.FAILED, useGen4Repel(cfg, calls, item, duration, fieldFree = true).result)
                assertEquals("invalid SaveData must not write RAM", 0, writes)
            }
        }
    }

    @Test
    fun `the shared save layout reads through the German globals`() {
        // Relocate only the two globals in the existing US capture. This checks layout compatibility,
        // including the Pokegear's pointer chase; it is not a capture from the German game.
        val oldGame = activeGame
        val oldNames = NdsArt.names
        val oldCode = romGameCode
        val oldLanguage = romLanguage
        try {
            NdsArt.names = null
            romLanguage = 'E'
            data class Case(val kind: GameKind, val fixture: String, val source: Gen4Config, val target: Gen4Config)
            for ((kind, fixture, source, target) in listOf(
                Case(GameKind.HEARTGOLD, "heartgold", GEN4_HEARTGOLD_US, GEN4_HEARTGOLD_DE),
                Case(GameKind.SOULSILVER, "soulsilver", GEN4_SOULSILVER_US, GEN4_SOULSILVER_DE),
            )) {
                val us = FixtureMemoryReader.load(fixture)
                activeGame = kind
                romGameCode = if (kind == GameKind.HEARTGOLD) "IPKD" else "IPGD"
                val german = object : MemoryReader {
                    override fun readCoreMemory(addr: Long, size: Int): ByteArray = when (addr) {
                        target.saveDataPtr -> us.readCoreMemory(source.saveDataPtr, size)
                        target.pokegear!!.fieldSystemPtr -> us.readCoreMemory(source.pokegear!!.fieldSystemPtr, size)
                        source.saveDataPtr, source.pokegear!!.fieldSystemPtr -> ByteArray(size)
                        else -> us.readCoreMemory(addr, size)
                    }
                }
                val expected = readGen4Telemetry(us, source)
                val actual = readGen4Telemetry(german, target)
                assertEquals(fixture, expected.party.size, actual.party.size)
                for ((before, after) in expected.party.zip(actual.party)) {
                    assertArrayEquals(fixture, before.moves, after.moves)
                    assertArrayEquals(fixture, before.pp, after.pp)
                    // Mon contains IntArrays, whose data-class equality compares references.
                    assertEquals(fixture, before.copy(moves = after.moves, pp = after.pp), after)
                }
                assertEquals(fixture, expected.items, actual.items)
                assertEquals(expected.money, actual.money)
                assertEquals(expected.mapNum, actual.mapNum)
                assertEquals(expected.regionMapTile, actual.regionMapTile)
                assertEquals(expected.pokegearState, actual.pokegearState)
                assertEquals(expected.pokedex, actual.pokedex)
                assertEquals(expected.repelSteps, actual.repelSteps)
            }
        } finally {
            activeGame = oldGame; NdsArt.names = oldNames
            romGameCode = oldCode; romLanguage = oldLanguage
        }
    }

    @Test
    fun `Repels write only the German save and refuse a busy field or an active Repel`() {
        for ((fixture, cfg) in listOf("heartgold" to GEN4_HEARTGOLD_DE, "soulsilver" to GEN4_SOULSILVER_DE)) {
            for ((item, duration) in GEN4_REPEL_KINDS) {
                val main = javaClass.classLoader!!.getResourceAsStream("fixtures/$fixture/mainram.bin")!!.readBytes()
                fun offset(addr: Long) = (addr - 0x02000000L).toInt()
                val usCfg = if (fixture == "heartgold") GEN4_HEARTGOLD_US else GEN4_SOULSILVER_US
                val ptrBytes = main.copyOfRange(offset(usCfg.saveDataPtr), offset(usCfg.saveDataPtr) + 4)
                ptrBytes.copyInto(main, offset(cfg.saveDataPtr))
                main.fill(0, offset(usCfg.saveDataPtr), offset(usCfg.saveDataPtr) + 4)
                val calls = object : GameCalls {
                    override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long = error("Repels don't call code")
                    override fun read(addr: Long, len: Int) = main.copyOfRange(offset(addr), offset(addr) + len)
                    override fun write(addr: Long, bytes: ByteArray): Boolean {
                        bytes.copyInto(main, offset(addr)); return true
                    }
                }
                val body = java.nio.ByteBuffer.wrap(ptrBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() + cfg.bodyOff
                val pocket = body + cfg.bagOff
                val steps = body + cfg.repelStepsOff!!
                calls.write(pocket, ByteArray(cfg.pocketSizes[0] * 4))
                calls.write(pocket, byteArrayOf(item.toByte(), 0, 1, 0, 17, 0, 2, 0))
                calls.write(steps, byteArrayOf(0))
                val before = main.copyOf()
                assertEquals(ItemUseResult.NOT_NOW, useGen4Repel(cfg, calls, item, duration, false).result)
                assertArrayEquals(before, main)
                assertEquals(ItemUseResult.USED, useGen4Repel(cfg, calls, item, duration, true).result)
                val expected = before.copyOf()
                byteArrayOf(17, 0, 2, 0, 0, 0, 0, 0).copyInto(expected, offset(pocket))
                expected[offset(steps)] = duration.toByte()
                assertArrayEquals("$fixture item $item: no unrelated RAM changed", expected, main)
                assertEquals(ItemUseResult.STILL_ACTIVE, useGen4Repel(cfg, calls, item, duration, true).result)
                assertArrayEquals(expected, main)
                calls.write(steps, byteArrayOf(0))
                val empty = main.copyOf()
                assertEquals(ItemUseResult.NONE_LEFT, useGen4Repel(cfg, calls, item, duration, true).result)
                assertArrayEquals(empty, main)
            }
        }
    }

    @Test
    fun `German cart names and art come from the player's ROM`() {
        val roms = listOf(
            "Pokemon - Goldene Edition HeartGold (Germany).nds",
            "Pokemon - Silberne Edition SoulSilver (Germany).nds",
        ).map { File(File(RETAIL_ROM_DIR).parentFile, "nds/$it") }
        assumeTrue("German HG/SS ROMs unavailable", roms.all { it.isFile })
        val dir = kotlin.io.path.createTempDirectory("nds-german-art").toFile()
        val pages = mutableListOf<List<String>>()
        try {
            for (rom in roms) {
                assertTrue(CompanionSupport.isSupported(rom))
                val names = NdsArt.readNames(rom)!!
                assertEquals('D', names.language)
                assertEquals("BISASAM", names.species(1))
                assertEquals("Tackle", names.move(33))
                assertEquals("Trank", names.item(17))
                assertEquals("Dukatia City", names.place(GEN4_HEARTGOLD_DE.mapPlaceIds.getValue(76)))
                assertTrue(NdsArt.extractTo(rom, dir))
                assertFalse(NdsArt.extractTo(rom, dir))
                assertTrue(File(dir, "${NdsArt.HEARTGOLD_ICONS}/152.png").isFile)
                assertTrue(File(dir, "${NdsArt.HEARTGOLD_ITEMS}/17.png").isFile)
                assertTrue(File(dir, PokegearMap.TILES).isFile)
                assertTrue(File(dir, "${NdsArt.HEARTGOLD_FRONT}/1.png").isFile)
                val lines = File(dir, NdsArt.HEARTGOLD_DEX).readLines()
                pages += lines
                assertEquals(493, lines.size)
                val page = lines.first().split('\t')
                assertTrue(page[1].startsWith("Samen"))
                assertTrue(page[2].isNotBlank())
                assertEquals(256, Gen4Dex.parse(lines).regional.size)
            }
            assertNotEquals("SoulSilver must use its own Pokédex pages after HeartGold", pages[0], pages[1])
            assertTrue("switching back to HeartGold must refresh the shared cache", NdsArt.extractTo(roms[0], dir))
            assertEquals("HeartGold must recover its original Pokédex pages", pages[0], File(dir, NdsArt.HEARTGOLD_DEX).readLines())
            assertFalse("the next HeartGold extraction must hit the cache", NdsArt.extractTo(roms[0], dir))
        } finally { dir.deleteRecursively() }
    }
}
