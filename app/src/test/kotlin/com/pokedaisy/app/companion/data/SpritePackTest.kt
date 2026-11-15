package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import okio.Path.Companion.toOkioPath

/**
 * SPRITE PACK ([SpritePack]): only paths and bytes of graphics the app knows get in; a pack made from what a ROM scan
 * kept loads back into the same files (and the heroes' map heads), and a second load adds nothing. The round trips skip
 * a ROM that isn't here.
 */
class SpritePackTest {
    private fun tmp() = Files.createTempDirectory("sprite-pack-test").toFile().apply { deleteOnExit() }

    private fun rom(name: String) = File(RETAIL_ROM_DIR, name).takeIf { it.isFile }

    private fun manifest(files: Map<String, ByteArray>) = SpritePack.MANIFEST to SpritePack.manifest(files, "2026-11-15").encodeToByteArray()

    @Test
    fun pathsAreOnlyKnownGraphics() {
        assertTrue(SpritePack.itemOf("red/fr_normal.bin") is SpritePack.Item.Hero)
        assertTrue(SpritePack.itemOf("text/emerald/font.bin") is SpritePack.Item.Text)
        assertTrue(SpritePack.itemOf("mon/HNS/25.bin") is SpritePack.Item.Mon)
        for (bad in listOf(
            "../red/fr_normal.bin", "red/../red/fr_normal.bin", "red/fr_normal.png", "ash/fr_normal.bin", "red/em_walking.bin",
            "text/ruby/box.bin", "mon/HNS/0.bin", "mon/HNS/387.bin", "mon/HNS/025.bin", "mon/XYZ/25.bin", "look/red/fr_normal.bin", "",
        )) assertNull(bad, SpritePack.itemOf(bad))
    }

    @Test
    fun aFileWithoutTheManifestIsNoPack() {
        try {
            SpritePack.install(tmp().toOkioPath(), mapOf("red/fr_normal.bin" to ByteArray(2304)))
            fail("no manifest")
        } catch (_: SpritePack.NotAPack) {
        }
    }

    @Test
    fun wrongBytesAreRefused() {
        val files = mapOf("red/fr_normal.bin" to ByteArray(2304), "mon/HNS/25.bin" to ByteArray(MonArt.SIZE), "text/firered/box.bin" to ByteArray(3))
        val dir = tmp()
        val r = SpritePack.install(dir.toOkioPath(), files + manifest(files))
        assertEquals(3, r.refused)
        assertEquals(0, r.added)
        assertTrue(LookArt.dir(dir.toOkioPath()).listFiles().isEmpty())
    }

    @Test
    fun aNewerFormatIsTurnedAway() {
        try {
            SpritePack.install(tmp().toOkioPath(), mapOf(SpritePack.MANIFEST to "${SpritePack.MAGIC} ${SpritePack.FORMAT + 1}\n".encodeToByteArray()))
            fail("newer format")
        } catch (e: SpritePack.NotAPack) {
            assertTrue(e.message!!.contains("newer"))
        }
    }

    /** What a scan of [roms] keeps, packed, loads back the same. */
    private fun roundTrip(vararg roms: File): SpritePack.Result {
        val from = tmp().toOkioPath()
        for (f in roms) {
            val bytes = f.readBytes()
            val scan = PlayerLook.scan(bytes, TextBlob.entries)
            LookArt.keep(from, scan)
            TextArt.keep(from, scan)
            MonArt.keep(from, bytes)
        }
        val files = SpritePack.collect(from)
        assertTrue("nothing packed", files.isNotEmpty())
        val to = tmp().toOkioPath()
        val r = SpritePack.install(to, files + manifest(files))
        assertEquals(0, r.refused)
        assertEquals(files.size, r.added)
        assertEquals(LookArt.available(from), LookArt.available(to))
        for (c in LookArt.available(to)) assertTrue("$c head", (RomArt.dir(to) / "regionmap/${c.headArt}.png").isFile)
        for (s in TextLook.STYLES) assertEquals(TextArt.load(from, s) != null, TextArt.load(to, s) != null)
        MonArt.forget()
        assertEquals(MonArt.available(from), MonArt.available(to))
        for ((p, b) in files) assertTrue(p, (LookArt.dir(to) / p).readBytes().contentEquals(b))
        val again = SpritePack.install(to, files + manifest(files))
        assertTrue(again.nothingNew)
        assertEquals(files.size, again.kept)
        return r
    }

    @Test
    fun fireRedAndEmeraldRoundTrip() {
        val fr = rom("Pokemon - FireRed Version (USA, Europe) (Rev 1).gba")
        val em = rom("Pokemon - Emerald Version (USA, Europe).gba")
        assumeTrue(fr != null && em != null)
        val r = roundTrip(fr!!, em!!)
        assertEquals(setOf(LookChar.RED, LookChar.LEAF, LookChar.BRENDAN, LookChar.MAY), r.heroes.toSet())
        assertEquals(TextLook.STYLES.toSet(), r.textStyles.toSet())
    }

    /** Every follower Pokémon the table knows packs and loads (it was made from these ROMs: `make sprite-pack-sigs`). */
    @Test
    fun followersRoundTrip() {
        val roms = listOf(
            "Pokemon Heart and Soul (v2.0.6).gba", "Pokemon R.O.W.E. (v2.1.9.1 Experimental).gba",
            "Pokemon Recharged Yellow (v1.9.7).gba", "Pokemon Emerald Seaglass (v3.0).gba",
        ).mapNotNull(::rom)
        assumeTrue(roms.isNotEmpty())
        val r = roundTrip(*roms.toTypedArray())
        assertTrue("followers: ${r.mons}", r.mons > 300)
    }
}
