package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * LEVEL CAP's two places in getexp ([LevelCap.find]): FireRed rev 1 and Emerald pinned at what was played headless, every
 * FireRed / LeafGreen / Emerald cart must have them, and the CFRU hacks (which run their own getexp) none. Skips a ROM that
 * isn't here.
 */
class LevelCapTest {
    private fun hex(v: Long) = "%08X".format(v)

    @Test
    fun retailFireRedRev1AndEmerald() {
        File(RETAIL_ROM_DIR + "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba").takeIf { it.isFile }?.let { rom ->
            val f = LevelCap.find(rom.toOkioPath())!!
            assertEquals(listOf("08021D0E", "08021FCA"), f.sites.map { hex(it.addr) })
            val w = LevelCap.writes(f, 14)
            assertEquals("642812D1", w[0].expect.hex())
            assertEquals("0E2812D3", w[0].value.hex())
            assertEquals("64284BD0", w[1].expect.hex())
            assertEquals("0E284BD2", w[1].value.hex())
        }
        File(RETAIL_ROM_DIR + "Pokemon - Emerald Version (USA, Europe).gba").takeIf { it.isFile }?.let { rom ->
            assertEquals(listOf("0804A5BE", "0804A88E"), LevelCap.find(rom.toOkioPath())!!.sites.map { hex(it.addr) })
        }
    }

    @Test
    fun hacks() {
        val cases = listOf(
            "Pokemon Emerald Legacy (v1.1.4).gba" to true,   // its case 2 is a `beq`
            "Pokemon Emerald Kaizo (v2.0).gba" to true,
            "Pokemon Glazed (v9.2.0).gba" to true,
            "Pokemon Ultra Violet (v1.22).gba" to true,
            "Pokemon Let's Go Pikachu.gba" to true,
            // CFRU: the old getexp is there, but the game runs its own.
            "Pokemon Radical Red (v4.1).gba" to false,
            "Pokemon Unbound (v2.1.1.1).gba" to false,
            "Pokemon Amethyst (v1.4.1).gba" to false,
            "Pokemon GS Chronicles (v2.7.6).gba" to false,
            // Rewrote the first check.
            "Pokemon Gaia (v3.2).gba" to false,
            "Pokemon Lazarus (v2.0).gba" to false,
        )
        for ((name, want) in cases) {
            val rom = File(RETAIL_ROM_DIR + name)
            if (!rom.isFile) continue
            assertEquals(name, want, LevelCap.find(rom.toOkioPath()) != null)
        }
    }

    /** Every FireRed / LeafGreen / Emerald / Ruby / Sapphire cart in the retail ROM dir (-DmultiRomDir=...). */
    @Test
    fun everyRetailBuild() {
        val dir = File(System.getProperty("multiRomDir") ?: RETAIL_ROM_DIR)
        assumeTrue(dir.isDirectory)
        var checked = 0
        for (f in retailRomFiles(dir).filter { listOf("Pirate", "Debug").none { x -> x in it.name } }) {
            val code = f.inputStream().use { ByteArray(0xB0).also { b -> it.read(b) } }.let { String(it, 0xAC, 4) }
            assertNotNull(f.name, LevelCap.find(f.toOkioPath()))
            checked++
        }
        assertTrue("checked $checked", checked >= 40)
    }

    /** The cap for the real saves: the top level of the first boss their guide has ahead (FireRed's and Emerald's). */
    @Test
    fun capFromTheSaves() {
        for ((fixture, rom, cfg, game) in listOf(
            Quad("firered_vanilla", "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba", NATIVE_FIRERED_REV1, GameKind.FIRERED),
            Quad("emerald_vanilla", "Pokemon - Emerald Version (USA, Europe).gba", NATIVE_EMERALD_RETAIL, GameKind.EMERALD),
        )) {
            val reader = RomFileReader.load(RETAIL_ROM_DIR + rom) ?: continue
            activeGame = game
            PokedexSource.reader = reader
            val t = cfg.guideTables!!
            val progress = readSaveProgress(FixtureMemoryReader.load(fixture), cfg, t)!!
            val snap = SnapshotView(connected = true, game = game, guideTables = t, progress = progress)
            assertEquals(fixture, true, LevelCap.hasBosses(snap))
            val guide = gameGuide(guideId(game, t))!!
            val boss = guide.bosses.firstOrNull { !it.isDone(progress) }
            val cap = LevelCap.next(snap)
            if (boss == null) { assertNull(fixture, cap); continue }
            val top = boss.trainers(progress).maxOf { GuideRomSource.party(t, it)!!.maxOf { m -> m.level } }
            assertEquals(fixture, LevelCap.Cap(top, boss.title), cap)
            println("$fixture: cap ${cap!!.level} (${cap.boss})")
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    @Test
    fun nothingInAnEmptyRom() {
        assertNull(LevelCap.find(LittleEndianBytes(ByteArray(0x1000))))
    }

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }
}
