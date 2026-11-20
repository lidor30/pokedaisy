package com.pokedaisy.app.companion.data.coop

import com.pokedaisy.app.companion.coop.COOP_EMERALD_CODES
import com.pokedaisy.app.companion.coop.COOP_EMERALD_SHA1S
import com.pokedaisy.app.companion.coop.CoopSession
import com.pokedaisy.app.companion.coop.EMERALD_DE_SHA1
import com.pokedaisy.app.companion.data.LookArt
import com.pokedaisy.app.companion.data.LookBlob
import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import okio.Path.Companion.toOkioPath

/**
 * Co-op on the player's own German Emerald (Pokémon Smaragd-Edition, BPED rev 0): the parts that read the real ROM.
 * The ROM is found in romDir by its SHA-1 ([EMERALD_DE_SHA1]), whatever the file is called; skipped without it. Writes
 * app/build/partner/de/brendan.bin and may.bin for the headless tests on a German save.
 */
class SmaragdCoopTest {
    private val rom: ByteArray? by lazy {
        val dir = File(RETAIL_ROM_DIR).takeIf { it.isDirectory } ?: return@lazy null
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gba", ignoreCase = true) && f.length() == 16L shl 20 }
            .orEmpty().sortedBy { it.name }
            .firstNotNullOfOrNull { f -> f.readBytes().takeIf { sha1(it) == EMERALD_DE_SHA1 } }
    }

    private fun smaragd(): ByteArray = rom.also { assumeTrue("no German Emerald (BPED) in romDir", it != null) }!!

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun isTheGermanCoopRom() {
        val rom = smaragd()
        val code = String(rom, 0xAC, 4)
        assertEquals("BPED", code)
        assertEquals("rev 0", 0, rom[0xBC].toInt())
        assertTrue(code in COOP_EMERALD_CODES)
        assertTrue(EMERALD_DE_SHA1 in COOP_EMERALD_SHA1S)
        // What the activity checks before it hashes a ROM at all.
        assertTrue(CoopSession.mayBeRetailEmerald(code, rom.size.toLong()))
    }

    @Test fun surfBlobIsFoundAtTheGermanOffset() {
        val rom = smaragd()
        val german = CoopEmeraldGen.SURF_BLOB_PIC + 0x11D30
        val blob = PartnerArt.surfBlob(rom)
        assertNotNull("the surf blob in Smaragd", blob)
        assertArrayEquals(rom.copyOfRange(german, german + 3 * 512), blob)
        // It really is the German place: the English offset holds something else in this ROM.
        val english = rom.copyOfRange(CoopEmeraldGen.SURF_BLOB_PIC, CoopEmeraldGen.SURF_BLOB_PIC + 3 * 512)
        assertNotEquals(CoopEmeraldGen.SURF_BLOB_CRC, CRC32().apply { update(english) }.value)
    }

    @Test fun buildsBothHeroesAndWritesHeadlessArt() {
        val rom = smaragd()
        val dir = Files.createTempDirectory("partner-de").toFile()
        try {
            val out = File("build/partner/de").apply { mkdirs() }
            for ((female, name) in listOf(false to "brendan", true to "may")) {
                val art = PartnerArt.build(dir.toOkioPath(), rom, female, look = null)
                assertNotNull(name, art)
                assertEquals(PartnerArt.ART_BYTES, art!!.size)
                // The hero's own overworld palette, the same colours as in the English game.
                val pal = LookBlob.valueOf("${name.uppercase()}_OW_PAL")
                assertEquals(name, pal.crc, CRC32().apply { update(art, 42 * 512, 32) }.value)
                // The last three frames are Smaragd's own surf blob.
                assertArrayEquals(name, PartnerArt.surfBlob(rom), art.copyOfRange(39 * 512, 42 * 512))
                File(out, "$name.bin").writeBytes(art)
            }
            // The scan kept Smaragd's heroes for next time.
            assertNotNull(LookArt.load(dir.toOkioPath(), LookChar.BRENDAN))
            assertNotNull(LookArt.load(dir.toOkioPath(), LookChar.MAY))
        } finally {
            dir.deleteRecursively()
        }
    }
}
