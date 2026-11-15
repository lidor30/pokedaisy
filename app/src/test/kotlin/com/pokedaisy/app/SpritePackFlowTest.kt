package com.pokedaisy.app

import com.pokedaisy.app.companion.data.LookArt
import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.companion.data.SpritePack
import com.pokedaisy.app.companion.data.TextArt
import com.pokedaisy.app.companion.data.TextBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okio.Path.Companion.toOkioPath

/** TOOLS > SPRITE PACK's zip reader ([SpritePackFlow.read]): what it keeps, what it skips, and a real pack loaded through it. */
class SpritePackFlowTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    @Test
    fun keepsTheManifestAndBinsOnly() {
        val files = SpritePackFlow.read(ByteArrayInputStream(zip(
            SpritePack.MANIFEST to "x".toByteArray(), "README.txt" to "hi".toByteArray(), "red/fr_normal.bin" to ByteArray(10),
            "big.bin" to ByteArray(SpritePack.MAX_FILE_BYTES + 1),
        )))
        assertEquals(setOf(SpritePack.MANIFEST, "red/fr_normal.bin"), files.keys)
    }

    @Test
    fun notAZipIsNoPack() {
        try {
            SpritePackFlow.read(ByteArrayInputStream("not a zip at all".toByteArray()))
            fail("not a zip")
        } catch (_: SpritePack.NotAPack) {
        }
    }

    /** FireRed's heroes and text look, packed as the tool does and loaded through the reader. */
    @Test
    fun aRealPackLoads() {
        val rom = File(System.getProperty("romDir") ?: "${System.getProperty("user.home")}/Downloads/Game ROMs & Emulation/gba",
            "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba")
        assumeTrue(rom.isFile)
        val from = Files.createTempDirectory("pack-from").toFile().toOkioPath()
        val scan = PlayerLook.scan(rom.readBytes(), TextBlob.entries)
        LookArt.keep(from, scan)
        TextArt.keep(from, scan)
        val files = SpritePack.collect(from)
        val bytes = zip(SpritePack.MANIFEST to SpritePack.manifest(files, "2026-11-15").toByteArray(), *files.toList().toTypedArray(),
            "../escape.bin" to ByteArray(4))
        val to = Files.createTempDirectory("pack-to").toFile()
        val r = SpritePack.install(to.toOkioPath(), SpritePackFlow.read(ByteArrayInputStream(bytes)))
        assertEquals(setOf(LookChar.RED, LookChar.LEAF), r.heroes.toSet())
        assertEquals(1, r.refused)
        assertEquals("2026-11-15", r.made)
        assertFalse(File(to, "escape.bin").exists() || File(to.parentFile, "escape.bin").exists())
        assertTrue(LookArt.load(to.toOkioPath(), LookChar.RED) != null)
    }
}
