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

    /** A rebuild that moved its icon tables (a FireRed QoL patch did: retail's place blank) is found by its ROM header. */
    @Test
    fun aRebuildByItsRomHeader() {
        val rom = File(RomFileReader.FIRERED_REV1_PATH)
        assumeTrue("no ROM", rom.isFile)
        val bytes = rom.readBytes()
        val t = com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV1.iconTables
        // The three tables copied into the 0xFF padding near the end, the old ones zeroed, the header pointed at the copies.
        val to = bytes.size - 0x2000
        fun off(a: Long) = (a - 0x08000000L).toInt()
        // (They sit back to back: all copied before any is blanked.)
        val tables = listOf(t.monIconTable to 0x6E0, t.monIconPaletteIndices to 0x1B8, t.monIconPaletteTable to 0x30)
        for ((i, pair) in tables.withIndex()) {
            System.arraycopy(bytes, off(pair.first), bytes, to + i * 0x800, pair.second)
            val p = 0x08000000L + to + i * 0x800
            for (k in 0 until 4) bytes[0x138 + i * 4 + k] = (p ushr (8 * k)).toByte()
        }
        for ((from, len) in tables) java.util.Arrays.fill(bytes, off(from), off(from) + len, 0)
        val copy = kotlin.io.path.createTempFile("firered-moved", ".gba").toFile()
        try {
            copy.writeBytes(bytes)
            assertNull("retail's place is blank", RomMonIcons.open(copy.toOkioPath(), t))
            assertNotNull(RomMonIcons.forRom(copy.toOkioPath(), sha1(copy), "BPRE", 1))
        } finally {
            copy.delete()
        }
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
            assertNotNull(RomMonIcons.forRom(copy.toOkioPath(), sha1(copy), "BPRE", 1))
        } finally {
            copy.delete()
        }
    }
}
