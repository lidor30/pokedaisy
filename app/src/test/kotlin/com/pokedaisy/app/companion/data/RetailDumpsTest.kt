package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Retail FireRed / Emerald as collections dump them (RetailDumps): the end's padding trimmed and the save code
 * patched still read as retail; a change anywhere else doesn't. Built from the retail ROMs (skipped without them).
 */
class RetailDumpsTest {

    private fun rom(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    private fun matches(b: ByteArray): String? {
        val code = String(b, 0xAC, 4, Charsets.US_ASCII)
        val rev = b[0xBC].toInt() and 0xFF
        val fromFile = RetailDumps.matchStream(code, rev, b.size.toLong()) { okio.Buffer().write(b) }
        // The game's bus gives the same answer as the file.
        val fromBus = RetailDumps.match(code, rev, b.size.toLong()) { off, len ->
            if (off + len > b.size) null else b.copyOfRange(off.toInt(), off.toInt() + len)
        }
        assertEquals(fromFile, fromBus)
        return fromFile
    }

    private fun check(path: String, code: String) {
        val retail = rom(path)
        assumeTrue("no ROM at $path", retail != null)
        retail!!
        val dump = RETAIL_DUMPS.first { it.code == code && it.rev == (retail[0xBC].toInt() and 0xFF) }
        assertEquals(dump.retailSha1, matches(retail))

        // What a collection ships: the padding cut off; the save code, its warnings, the flash chip's ID and the wait
        // states rewritten; its own leftovers in the empty space.
        val trimmed = retail.copyOf(dump.end + 3)
        dump.ranges.drop(1).forEach { r -> for (i in r.first..minOf(r.last, r.first + 40)) trimmed[i] = (trimmed[i].toInt() xor 0x5A).toByte() }
        assertEquals(dump.retailSha1, matches(trimmed))

        // Cut into its data, or changed outside those ranges (a mod): not retail.
        assertNull(matches(retail.copyOf(dump.end - 1)))
        val modded = trimmed.copyOf()
        modded[0x1000] = (modded[0x1000].toInt() xor 1).toByte()
        assertNull(matches(modded))
    }

    @Test
    fun emerald() = check(RomFileReader.EMERALD_PATH, "BPEE")

    @Test
    fun fireRedRev1() = check(RomFileReader.FIRERED_REV1_PATH, "BPRE")
}
