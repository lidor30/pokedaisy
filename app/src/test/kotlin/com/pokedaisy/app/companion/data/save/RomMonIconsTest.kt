package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.RomFileReader
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import okio.Path.Companion.toOkioPath

/** PC BOXES' icons read from a ROM file (skipped where the ROM isn't on this machine: CI's ROM runner has them). */
class RomMonIconsTest {

    private fun sha1(f: File) = MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** The ROM's hash picks its tables and they check out against the file (decoding itself is the companion's,
     * [com.pokedaisy.app.companion.data.decodeMonIcon]; a Bitmap can't be made in these JVM tests). */
    private fun check(path: String, code: String, rev: Int) {
        val rom = File(path)
        assumeTrue("no ROM at $path", rom.isFile)
        val tables = RomMonIcons.tablesFor(sha1(rom), code, rev)
        assertNotNull(tables)
        assertNotNull(RomMonIcons.open(rom.toOkioPath(), tables!!))
    }

    @Test
    fun fireRedRev1() = check(RomFileReader.FIRERED_REV1_PATH, "BPRE", 1)

    @Test
    fun emerald() = check(RomFileReader.EMERALD_PATH, "BPEE", 0)

    @Test
    fun anUnknownRomGetsNone() {
        assertNull(RomMonIcons.tablesFor("0".repeat(40), "BPRE", 1))
        assertNull(RomMonIcons.tablesFor(null, "ZZZZ", 0))
    }
}
