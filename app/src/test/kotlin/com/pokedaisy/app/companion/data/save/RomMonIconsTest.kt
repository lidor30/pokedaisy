package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.RomFileReader
import org.junit.Assert.assertEquals
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

    /** A FireRed / Emerald build nothing names (a trimmed dump, a randomized one, a hack edited in place) gets its base
     * game's tables, which [RomMonIcons.open] then checks against the file; another game's code gets none. */
    @Test
    fun anUnknownBuildGetsItsBaseGamesTables() {
        assertEquals(com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV1.iconTables, RomMonIcons.tablesFor("0".repeat(40), "BPRE", 1))
        assertEquals(com.pokedaisy.app.companion.data.NATIVE_EMERALD_RETAIL.iconTables, RomMonIcons.tablesFor(null, "BPEE", 0))
        assertNull(RomMonIcons.tablesFor(null, "ZZZZ", 0))
        assertNull(RomMonIcons.tablesFor(null, null, null))
    }

    /** A retail FireRed whose bytes changed (here: a padding byte) still gets its icons: the tables check out. */
    @Test
    fun aChangedRetailDump() {
        val rom = File(RomFileReader.FIRERED_REV1_PATH)
        assumeTrue("no ROM", rom.isFile)
        val copy = kotlin.io.path.createTempFile("firered", ".gba").toFile()
        try {
            val bytes = rom.readBytes()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
            copy.writeBytes(bytes)
            val tables = RomMonIcons.tablesFor(sha1(copy), "BPRE", 1)
            assertNotNull(tables)
            assertNotNull(RomMonIcons.open(copy.toOkioPath(), tables!!))
        } finally {
            copy.delete()
        }
    }
}
