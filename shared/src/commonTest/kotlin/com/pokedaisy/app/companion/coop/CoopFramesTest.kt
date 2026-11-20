package com.pokedaisy.app.companion.coop

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okio.Buffer
import okio.EOFException
import okio.IOException

class CoopFramesTest {
    @Test fun tcpRoundTrip() {
        val pipe = Buffer()
        val data = ByteArray(300) { it.toByte() }
        CoopFrames.writeTcp(pipe, data)
        CoopFrames.writeTcp(pipe, ByteArray(0))
        CoopFrames.writeTcp(pipe, ByteArray(CoopFrames.MAX_TCP_FRAME) { 7 })
        assertContentEquals(data, CoopFrames.readTcp(pipe))
        assertEquals(0, CoopFrames.readTcp(pipe).size)
        assertEquals(CoopFrames.MAX_TCP_FRAME, CoopFrames.readTcp(pipe).size)
    }

    @Test fun tcpRefusesOversizeLengthWithoutConsumingBody() {
        val malicious = Buffer().writeInt(CoopFrames.MAX_TCP_FRAME + 1).write(ByteArray(100))
        assertFailsWith<IOException> { CoopFrames.readTcp(malicious) }
        assertEquals(100L, malicious.size)
        assertFailsWith<IOException> { CoopFrames.readTcp(Buffer().writeInt(-1)) }
    }

    @Test fun tcpCleanEndIsEof() {
        assertFailsWith<EOFException> { CoopFrames.readTcp(Buffer()) }
    }

    @Test fun tcpTruncatedFrameIsIoException() {
        assertFailsWith<IOException> { CoopFrames.readTcp(Buffer().write(byteArrayOf(0, 0, 0, 5, 1, 2))) }
        assertFailsWith<IOException> { CoopFrames.readTcp(Buffer().write(byteArrayOf(0, 0))) }
    }

    @Test fun writeRefusesOversize() {
        assertFailsWith<IllegalArgumentException> {
            CoopFrames.writeTcp(Buffer(), ByteArray(CoopFrames.MAX_TCP_FRAME + 1))
        }
    }

    @Test fun relayDataPrefixesTheKind() {
        assertContentEquals(byteArrayOf(1, 9, 8), CoopFrames.relayData(byteArrayOf(9, 8)))
    }
}
