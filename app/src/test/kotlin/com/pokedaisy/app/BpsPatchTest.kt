package com.pokedaisy.app

import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** [BpsPatch] and [RomPatcher]: every BPS action, the checks that keep a wrong ROM or a broken patch from writing
 * anything, where the result goes, and a real patch on the player's ROM (skipped without it). */
class BpsPatchTest {

    /** A patch written by hand, one action at a time. */
    private class Writer(sourceSize: Int, targetSize: Int, meta: String = "") {
        val out = ByteArrayOutputStream().apply { write("BPS1".toByteArray()) }
        init {
            number(sourceSize.toLong()); number(targetSize.toLong())
            val m = meta.toByteArray()
            number(m.size.toLong()); out.write(m)
        }
        fun number(v: Long) {
            var data = v
            while (true) {
                val x = (data and 0x7F).toInt()
                data = data shr 7
                if (data == 0L) { out.write(0x80 or x); return }
                out.write(x)
                data--
            }
        }
        private fun action(kind: Int, length: Int) = number(((length - 1).toLong() shl 2) or kind.toLong())
        private fun offset(d: Long) = number((Math.abs(d) shl 1) or (if (d < 0) 1 else 0))
        fun sourceRead(n: Int) = action(0, n)
        fun targetRead(bytes: ByteArray) { action(1, bytes.size); out.write(bytes) }
        fun sourceCopy(n: Int, relative: Long) { action(2, n); offset(relative) }
        fun targetCopy(n: Int, relative: Long) { action(3, n); offset(relative) }
        fun finish(source: ByteArray, target: ByteArray): ByteArray {
            fun le(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            out.write(le(BpsPatch.crc32(source, source.size)))
            out.write(le(BpsPatch.crc32(target, target.size)))
            val body = out.toByteArray()
            return body + le(BpsPatch.crc32(body, body.size))
        }
    }

    private val source = ByteArray(64) { (it * 7).toByte() }
    // 0..7 kept, "HACK", a run of 6 'A's (TARGET_COPY over itself), source 40..47, source 16..19.
    private val target = source.copyOf(8) + "HACK".toByteArray() + ByteArray(6) { 'A'.code.toByte() } +
        source.copyOfRange(40, 48) + source.copyOfRange(16, 20)

    private fun patch(): ByteArray = Writer(source.size, target.size, "by someone").run {
        sourceRead(8)
        targetRead("HACK".toByteArray() + byteArrayOf('A'.code.toByte()))
        targetCopy(5, 12)          // from target 12 (the 'A'), five more: a run
        sourceCopy(8, 40)          // source 40..47
        sourceCopy(4, -(48 - 16))  // back to source 16
        finish(source, target)
    }

    @Test fun everyActionGivesTheTarget() {
        val p = BpsPatch.parse(patch())
        assertEquals(64L, p.sourceSize)
        assertEquals(target.size.toLong(), p.targetSize)
        assertEquals("by someone", p.metadata)
        assertEquals(BpsPatch.crc32(source, source.size), p.sourceCrc)
        var last = 0f
        assertArrayEquals(target, p.apply(source) { last = it })
        assertEquals(1f, last)
    }

    @Test fun anotherRomIsTurnedAway() {
        val other = source.copyOf().also { it[3] = 99 }
        assertEquals(BpsPatch.Reason.WRONG_SOURCE, runCatching { BpsPatch.parse(patch()).apply(other) }.reasonOf())
        assertEquals(BpsPatch.Reason.WRONG_SOURCE, runCatching { BpsPatch.parse(patch()).apply(source.copyOf(63)) }.reasonOf())
    }

    @Test fun aDamagedOrCutPatchIsCaughtBeforePatching() {
        val damaged = patch().also { it[20] = (it[20] + 1).toByte() }
        assertEquals(BpsPatch.Reason.DAMAGED, runCatching { BpsPatch.parse(damaged) }.reasonOf())
        assertEquals(BpsPatch.Reason.DAMAGED, runCatching { BpsPatch.parse(patch().copyOf(30)) }.reasonOf())
        assertEquals(BpsPatch.Reason.NOT_BPS, runCatching { BpsPatch.parse("UPS1 not this".toByteArray()) }.reasonOf())
        assertFalse(BpsPatch.looksLikeBps("PK\u0003\u0004".toByteArray()))
        assertTrue(BpsPatch.looksLikeBps(patch()))
    }

    @Test fun theBaseIsFoundByChecksumAndTheResultNamedAfterThePatch() {
        val dir = Files.createTempDirectory("bps").toFile()
        try {
            val gba = File(dir, "ROMs/gba").apply { mkdirs() }
            File(gba, "other.gba").writeBytes(source.copyOf().also { it[0] = 1 })   // same size, another CRC
            val base = File(gba, "Base.gba").apply { writeBytes(source) }
            File(gba, "small.gba").writeBytes(ByteArray(10))
            val p = BpsPatch.parse(patch())
            val cache = RomPatcher.CrcCache(File(dir, "crcs.tsv"))
            val found = RomPatcher.findBase(gba.listFiles()!!.sortedBy { it.name }.reversed(), p.sourceSize, p.sourceCrc, cache)
            assertEquals(base, found)
            assertEquals(p.sourceCrc, cache.get(base))

            val imported = File(dir, "files/roms")
            // A folder named for the kind, inside a linked one.
            val out = RomPatcher.outputDir(RomPatcher.Platform.GBA, base, listOf(File(dir, "ROMs").path), imported)
            assertEquals(gba, out)
            assertEquals("My Hack (v1.2).gba", RomPatcher.outputName("My Hack (v1.2).bps", "gba"))

            val first = RomPatcher.apply(p, base, out, "My Hack.gba")
            assertFalse(first.existed)
            assertArrayEquals(target, first.file.readBytes())
            assertTrue(base.readBytes().contentEquals(source))   // the base ROM is never touched
            // Again: the same ROM is already there, kept.
            assertTrue(RomPatcher.apply(p, base, out, "My Hack.gba").existed)
            // Another file under that name stays; the result gets " (2)".
            File(out, "Other.gba").writeBytes(ByteArray(3))
            assertEquals("Other (2).gba", RomPatcher.apply(p, base, out, "Other.gba").file.name)
            assertTrue(out.listFiles()!!.none { it.name.endsWith(".part") })

            // No linked folder: the app's own roms/.
            assertEquals(imported, RomPatcher.outputDir(RomPatcher.Platform.GBA, base, emptyList(), imported))
            // The base's own folder when nothing is named for the kind.
            val games = File(dir, "games/firered").apply { mkdirs() }
            val elsewhere = File(games, "Base.gba").apply { writeBytes(source) }
            assertEquals(games, RomPatcher.outputDir(RomPatcher.Platform.GBA, elsewhere, listOf(File(dir, "games").path), imported))
            // A Game Boy hack goes to gbc/ when that's where the player keeps them.
            val gbc = File(dir, "games/gbc").apply { mkdirs() }
            assertEquals(gbc, RomPatcher.outputDir(RomPatcher.Platform.GB, elsewhere, listOf(File(dir, "games").path), imported))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun platformByHeader() {
        val gb = ByteArray(0x8000).also {
            byteArrayOf(0xCE.toByte(), 0xED.toByte(), 0x66, 0x66).copyInto(it, 0x104)
        }
        assertEquals(RomPatcher.Platform.GB, RomPatcher.platformOf(gb))
        assertEquals(RomPatcher.Platform.GBC, RomPatcher.platformOf(gb.copyOf().also { it[0x143] = 0xC0.toByte() }))
        assertEquals(RomPatcher.Platform.GBA, RomPatcher.platformOf(ByteArray(0x200)))
    }

    /** The player's FireRed QoL patch on retail FireRed rev 1 gives the QoL build beside them, byte for byte. */
    @Test fun aRealPatchOnTheRealRom() {
        val roms = File(RETAIL_ROM_DIR).parentFile
        val bps = File(roms, "FireRed QoL.bps")
        val base = File(RETAIL_ROM_DIR, "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba")
        assumeTrue(bps.isFile && base.isFile)
        val p = BpsPatch.parse(bps.readBytes())
        assertEquals(0x84EE4776L, p.sourceCrc)
        assertEquals("Pokémon FireRed (USA, Europe) (Rev 1)", RomPatcher.KNOWN_BASES[p.sourceCrc])
        val found = RomPatcher.findBase(File(RETAIL_ROM_DIR).listFiles()!!.toList(), p.sourceSize, p.sourceCrc)
        assertEquals(base.canonicalFile, found?.canonicalFile)
        val out = p.apply(base.readBytes())
        assertEquals(p.targetCrc, BpsPatch.crc32(out, out.size))
    }

    private fun Result<*>.reasonOf(): BpsPatch.Reason? = (exceptionOrNull() as? BpsPatch.Error)?.reason
}
