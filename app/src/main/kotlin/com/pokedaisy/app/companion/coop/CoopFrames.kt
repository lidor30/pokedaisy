package com.pokedaisy.app.companion.coop

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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

    /** TCP: a u32 BE length, then the body. */
    fun writeTcp(out: OutputStream, bytes: ByteArray) {
        require(bytes.size <= MAX_TCP_FRAME) { "frame too large" }
        val n = bytes.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(bytes)
        out.flush()
    }

    /** One frame; [EOFException] at a clean end, [IOException] for a length over [MAX_TCP_FRAME] (the body is never read). */
    fun readTcp(input: InputStream): ByteArray {
        val first = input.read()
        if (first < 0) throw EOFException()
        val din = DataInputStream(input)
        val n = (first shl 24) or (din.readUnsignedByte() shl 16) or (din.readUnsignedByte() shl 8) or din.readUnsignedByte()
        if (n < 0 || n > MAX_TCP_FRAME) throw IOException("frame length $n")
        val body = ByteArray(n)
        din.readFully(body)
        return body
    }

    fun relayData(bytes: ByteArray): ByteArray = byteArrayOf(RELAY_DATA.toByte()) + bytes
}
