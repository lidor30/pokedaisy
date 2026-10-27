package com.pokedaisy.app.companion.coop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoopFramesTest {
    @Test fun tcpRoundTrip() {
        val out = ByteArrayOutputStream()
        val a = ByteArray(300) { it.toByte() }
        CoopFrames.writeTcp(out, a)
        CoopFrames.writeTcp(out, ByteArray(0))
        CoopFrames.writeTcp(out, ByteArray(CoopFrames.MAX_TCP_FRAME) { 7 })
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals(a, CoopFrames.readTcp(input))
        assertEquals(0, CoopFrames.readTcp(input).size)
        assertEquals(CoopFrames.MAX_TCP_FRAME, CoopFrames.readTcp(input).size)
    }

    @Test fun tcpRefusesOversizeLengthWithoutReadingBody() {
        val n = 70_001
        val head = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        val input = CountingStream(ByteArrayInputStream(head + ByteArray(100)))
        assertThrows(IOException::class.java) { CoopFrames.readTcp(input) }
        assertEquals(4, input.count)
        assertThrows(IOException::class.java) { CoopFrames.readTcp(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1))) }
    }

    @Test fun tcpCleanEndIsEof() {
        assertThrows(EOFException::class.java) { CoopFrames.readTcp(ByteArrayInputStream(ByteArray(0))) }
    }

    @Test fun tcpTruncatedBodyIsIoException() {
        assertThrows(IOException::class.java) { CoopFrames.readTcp(ByteArrayInputStream(byteArrayOf(0, 0, 0, 5, 1, 2))) }
        assertThrows(IOException::class.java) { CoopFrames.readTcp(ByteArrayInputStream(byteArrayOf(0, 0))) }
    }

    @Test fun writeRefusesOversize() {
        assertThrows(IllegalArgumentException::class.java) { CoopFrames.writeTcp(ByteArrayOutputStream(), ByteArray(70_001)) }
    }

    @Test fun relayDataPrefixesTheKind() {
        assertArrayEquals(byteArrayOf(1, 9, 8), CoopFrames.relayData(byteArrayOf(9, 8)))
    }

    private class CountingStream(private val inner: InputStream) : InputStream() {
        var count = 0
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
    }
}
