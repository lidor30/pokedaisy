package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.RomFileReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import okio.Path.Companion.toOkioPath

/** SAVE EDITOR front pictures read from a ROM file (skipped where the ROM isn't here: CI's ROM runner has them). */
class RomMonSpritesTest {

    private fun sha1(f: File) = MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** The ROM's hash picks its dex tables and they check out against the file; a sprite decodes to 64x64. */
    private fun check(path: String, code: String, rev: Int) {
        val rom = File(path)
        assumeTrue("no ROM at $path", rom.isFile)
        val tables = RomMonSprites.tablesFor(sha1(rom), code, rev)
        assertNotNull(tables)
        val sprites = RomMonSprites.open(rom.toOkioPath(), tables!!)
        assertNotNull(sprites)
        val bulbasaur = sprites!!.get(1)
        assertNotNull("Bulbasaur's front picture", bulbasaur)
        assertEquals(64, bulbasaur!!.width)
        assertEquals(64, bulbasaur.height)
        assertTrue("opaque pixels decoded", bulbasaur.argb.count { (it ushr 24) != 0 } > 100)
        assertEquals("cached after first decode", bulbasaur, sprites.get(1))
    }

    @Test
    fun fireRedRev1() = check(RomFileReader.FIRERED_REV1_PATH, "BPRE", 1)

    @Test
    fun emerald() = check(RomFileReader.EMERALD_PATH, "BPEE", 0)

    @Test
    fun anUnknownRomGetsNone() {
        assertNull(RomMonSprites.tablesFor("0".repeat(40), "BPRE", 1))
        assertNull(RomMonSprites.tablesFor(null, "ZZZZ", 0))
    }
}
