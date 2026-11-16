package com.pokedaisy.app.companion.data

import okio.EOFException
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** The okio ROM-file readers give the bytes (and the errors) the RandomAccessFile / mapped-buffer code did. */
class RomFilesTest {
    private val bytes = Random(11).nextBytes(200_001)

    private fun withFile(block: (File) -> Unit) {
        val f = File.createTempFile("romfiles", ".bin")
        try { f.writeBytes(bytes); block(f) } finally { f.delete() }
    }

    @Test fun `the windowed reader reads what the array does, anywhere`() = withFile { f ->
        withRomFile(f.toOkioPath()) { h ->
            val file = FileLittleEndian(h)
            val mem = LittleEndianBytes(bytes)
            assertEquals(mem.limit(), file.limit())
            val r = Random(5)
            // Walks forward like the scanners, with jumps back and far ahead.
            var o = 0
            repeat(20_000) {
                o = when (r.nextInt(10)) { 0 -> r.nextInt(bytes.size - 8); 1 -> maxOf(0, o - r.nextInt(2000)); else -> minOf(bytes.size - 8, o + r.nextInt(64)) }
                assertEquals(mem.get(o), file.get(o))
                assertEquals(mem.getShort(o), file.getShort(o))
                assertEquals(mem.getInt(o), file.getInt(o))
                assertEquals(mem.getLong(o), file.getLong(o))
            }
            assertEquals(mem.getInt(bytes.size - 4), file.getInt(bytes.size - 4))
            assertThrows(IndexOutOfBoundsException::class.java) { file.getInt(bytes.size - 3) }
            assertThrows(IndexOutOfBoundsException::class.java) { file.get(-1) }
        }
    }

    @Test fun `reads at an offset`() = withFile { f ->
        withRomFile(f.toOkioPath()) { h ->
            assertArrayEquals(bytes.copyOfRange(1000, 1100), h.readFully(1000, 100))
            assertThrows(EOFException::class.java) { h.readFully(bytes.size - 10L, 20) }
            assertArrayEquals(bytes.copyOfRange(bytes.size - 10, bytes.size), h.readUpTo(bytes.size - 10L, 20))
            val cart = h.cartReader()
            assertArrayEquals(bytes.copyOfRange(0x0C, 0x10), cart(0x0C, 4))
            assertNull(cart(bytes.size - 2L, 4))
        }
        assertArrayEquals(bytes, readRomFile(f.toOkioPath()))
    }
}
