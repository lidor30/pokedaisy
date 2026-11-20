package com.pokedaisy.app.companion.data

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** okio's writeModifiedUtf8 / readModifiedUtf8 are DataOutputStream.writeUTF / DataInputStream.readUTF byte for byte,
 * so the companion caches written before still read. */
class ModifiedUtf8Test {
    private val samples = listOf("", "PIKACHU", "Pokémon", "\u0000", "ポケモン", "a😀b", "߿ࠀ￿", "x".repeat(1000))

    @Test fun `bytes match writeUTF`() {
        for (s in samples) {
            val jdk = ByteArrayOutputStream().also { DataOutputStream(it).writeUTF(s) }.toByteArray()
            assertArrayEquals(s, jdk, Buffer().writeModifiedUtf8(s).readByteArray())
            assertEquals(s, Buffer().write(jdk).readModifiedUtf8())
            assertEquals(s, DataInputStream(Buffer().writeModifiedUtf8(s).inputStream()).readUTF())
        }
    }

    @Test fun `too long or broken input throws`() {
        assertThrows(okio.IOException::class.java) { Buffer().writeModifiedUtf8("é".repeat(40000)) }
        assertThrows(okio.IOException::class.java) { Buffer().writeShort(2).writeByte(0xC3).writeByte(0x41).readModifiedUtf8() }
        assertThrows(okio.IOException::class.java) { Buffer().writeShort(4).writeByte(0x41).readModifiedUtf8() }
    }
}
