package com.pokedaisy.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import kotlin.random.Random

/**
 * RetroArch's compressed saves (SaveRAM Compression): unpacked before the emulator opens them, the compressed file
 * backed up first, never handed to mGBA when they can't be read. The container is built here the way RetroArch 1.22.2
 * writes it (checked on a real one: its mGBA core's Unbound save, 131072 bytes -> 3413, one zlib chunk).
 */
class RzipSaveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A 128 KB flash save: mostly erased, a few sectors of data, like a Gen 3 game's. */
    private val flash: ByteArray = ByteArray(131072) { 0xFF.toByte() }.also { b ->
        val r = Random(7)
        for (i in 0 until 3 * 4096) b[i] = r.nextInt(256).toByte()
        for (i in 57344 until 57344 + 4096) b[i] = r.nextInt(16).toByte()
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xFF).toByte() }

    private fun rzip(data: ByteArray, chunk: Int = 131072, version: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("#RZIPv".toByteArray()); out.write(version); out.write('#'.code)
        out.write(le(chunk.toLong(), 4)); out.write(le(data.size.toLong(), 8))
        var p = 0
        while (p < data.size) {
            val n = minOf(chunk, data.size - p)
            val d = Deflater(6)
            d.setInput(data, p, n); d.finish()
            val buf = ByteArray(n + 1024)
            val len = d.deflate(buf); d.end()
            out.write(le(len.toLong(), 4)); out.write(buf, 0, len)
            p += n
        }
        return out.toByteArray()
    }

    private fun unpacked(r: RzipSave.Result) = (r as RzipSave.Result.Unpacked).bytes

    @Test fun aPlainSaveIsLeftAlone() {
        assertEquals(RzipSave.Result.NotRzip, RzipSave.decode(flash))
        val save = File(tmp.root, "Unbound.srm").apply { writeBytes(flash) }
        assertNull(RzipSave.unpackForPlay(save))
        assertArrayEquals(flash, save.readBytes())
        assertTrue(SaveBackups.list(save).isEmpty())
    }

    @Test fun retroArchsCompressedSaveUnpacks() {
        val packed = rzip(flash)
        assertTrue("much smaller than the chip", packed.size < 16384)
        assertArrayEquals(flash, unpacked(RzipSave.decode(packed)))
        // several chunks, as a bigger save or another chunk size gives
        assertArrayEquals(flash, unpacked(RzipSave.decode(rzip(flash, chunk = 32768))))
    }

    @Test fun aFileTheOldAppGrewStillUnpacks() {
        // 1.4.0's mGBA grew the file to the chip's 128 KB and filled the rest with 0xFF, the chunks untouched.
        val packed = rzip(flash)
        val grown = packed + ByteArray(131072 - packed.size) { 0xFF.toByte() }
        assertArrayEquals(flash, unpacked(RzipSave.decode(grown)))
    }

    @Test fun whatCantBeReadIsRefused() {
        val packed = rzip(flash)
        assertTrue(RzipSave.decode(packed.copyOf(packed.size / 2)) is RzipSave.Result.Unreadable)
        val zstd = rzip(flash, version = 2)
        assertTrue((RzipSave.decode(zstd) as RzipSave.Result.Unreadable).zstd)
        val broken = packed.copyOf().also { it[30] = (it[30].toInt() xor 0x5A).toByte() }
        assertTrue(RzipSave.decode(broken) is RzipSave.Result.Unreadable)
    }

    @Test fun unpackForPlayKeepsTheCompressedFileAndWritesThePlainOne() {
        val packed = rzip(flash)
        val save = File(tmp.root, "Pokemon - Unbound (v2.1.1.1).srm").apply { writeBytes(packed) }
        assertNull(RzipSave.unpackForPlay(save))
        assertArrayEquals(flash, save.readBytes())
        assertTrue("the compressed file is backed up", SaveBackups.list(save).any { it.readBytes().contentEquals(packed) })
        // played again: nothing to do
        assertNull(RzipSave.unpackForPlay(save))
        assertArrayEquals(flash, save.readBytes())
    }

    @Test fun anUnreadableOneIsNeverTouched() {
        val zstd = rzip(flash, version = 2)
        val save = File(tmp.root, "Unbound.srm").apply { writeBytes(zstd) }
        assertNotNull(RzipSave.unpackForPlay(save))
        assertArrayEquals(zstd, save.readBytes())
    }
}
