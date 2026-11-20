package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** The plain-Kotlin byte helpers read and write exactly what the java.nio / Charsets calls they replaced did. */
class LittleEndianBytesTest {
    private val bytes = Random(7).nextBytes(64)

    @Test fun `reads match a little-endian ByteBuffer`() {
        val nio = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val le = LittleEndianBytes(bytes)
        for (i in 0..bytes.size - 8) {
            assertEquals(nio.get(i), le.get(i))
            assertEquals(nio.getShort(i), le.getShort(i))
            assertEquals(nio.getInt(i), le.getInt(i))
            assertEquals(nio.getLong(i), le.getLong(i))
            assertEquals(nio.getInt(i), s32le(bytes, i))
        }
        assertEquals(64, le.limit())
    }

    @Test fun `reads past the limit throw like a ByteBuffer`() {
        val le = LittleEndianBytes(bytes, 10)
        assertEquals(10, le.limit())
        assertEquals(bytes[9], le.get(9))
        assertThrows(IndexOutOfBoundsException::class.java) { le.get(10) }
        assertThrows(IndexOutOfBoundsException::class.java) { le.getInt(7) }
        assertThrows(IndexOutOfBoundsException::class.java) { le.getShort(-1) }
        assertThrows(IndexOutOfBoundsException::class.java) { ByteBuffer.wrap(bytes, 0, 10).getInt(7) }
    }

    @Test fun `words match ByteBuffer's putInt`() {
        for (v in intArrayOf(0, 1, -1, 0x08000000, 0x12345678, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertArrayEquals(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array(), le32(v))
            assertArrayEquals(ByteBuffer.allocate(4).putInt(v).array(), be32(v))
        }
    }

    @Test fun `text matches the JDK's charsets`() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(String(all, Charsets.US_ASCII), asciiString(all))
        assertEquals(String(all, 0x41, 4, Charsets.US_ASCII), asciiString(all, 0x41, 4))
        assertEquals(String(all, 0x7E, 4, Charsets.US_ASCII), asciiString(all, 0x7E, 4))
        assertThrows(IndexOutOfBoundsException::class.java) { asciiString(all, 250, 10) }
        for (s in listOf("BPRE", "NARC", "IHDR", "café", "a😀b", "", "\u0000x")) {
            assertArrayEquals(s, s.toByteArray(Charsets.US_ASCII), asciiBytes(s))
            assertArrayEquals(s, s.toByteArray(Charsets.UTF_16BE), utf16beBytes(s))
        }
    }
}
