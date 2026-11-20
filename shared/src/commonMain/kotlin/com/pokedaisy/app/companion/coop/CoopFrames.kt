package com.pokedaisy.app.companion.coop

import okio.BufferedSink
import okio.BufferedSource
import okio.IOException

/** Framing and codes shared by the relay and TCP paths of the co-op transport. */
object CoopFrames {
    const val MAX_APP_FRAME = 65_536
    const val MAX_TCP_FRAME = 70_000

    /** Session plaintext kinds (the first byte after the handshake). */
    const val DATA = 0
    const val PING = 1
    const val CLOSE = 2

    /** The relay's message kinds (byte 0). */
    const val RELAY_DATA = 0x01
    const val PEER_JOINED = 0x10
    const val PEER_LEFT = 0x11

    /** Reason codes. */
    const val CLOSED = 0
    const val TIMEOUT = 1
    const val DENIED = 2
    const val VERSION = 3
    const val NOT_ALLOWED = 4
    const val OVERFLOW = 5
    const val ERROR = 6
    const val PENDING_EXPIRED = 7
    const val DUPLICATE = 8

    /** TCP uses a four-byte big-endian length followed by the body. */
    fun writeTcp(out: BufferedSink, bytes: ByteArray) {
        require(bytes.size <= MAX_TCP_FRAME) { "frame too large" }
        out.writeInt(bytes.size).write(bytes).flush()
    }

    /** Refuse an invalid length before consuming or allocating the payload. */
    fun readTcp(input: BufferedSource): ByteArray {
        val size = input.readInt()
        if (size !in 0..MAX_TCP_FRAME) throw IOException("frame length $size")
        return input.readByteArray(size.toLong())
    }

    fun relayData(bytes: ByteArray): ByteArray = byteArrayOf(RELAY_DATA.toByte()) + bytes
}
