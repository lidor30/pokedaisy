package com.pokedaisy.relay

import java.io.ByteArrayOutputStream

/**
 * The lobby's messages (`/v1/lobby`, see README.md): binary, byte 0 the kind, then fields in order. A string is a u8 byte
 * count and that many UTF-8 bytes; numbers are big-endian. The app's copy is `CoopLobbyWire` - change the two together.
 */
object LobbyWire {
    // Client to server.
    const val HOST: Byte = 0x20
    const val UNHOST: Byte = 0x21
    const val FIND: Byte = 0x22
    const val LIST: Byte = 0x23

    // Server to client.
    const val HOSTED: Byte = 0x30
    const val FOUND: Byte = 0x31
    const val ROOMS: Byte = 0x32
    const val ERROR: Byte = 0x3F

    // ERROR's reason byte.
    const val NOT_FOUND = 1
    const val BAD_REQUEST = 2
    const val LIMIT = 3
    const val FULL = 4

    /** HOST's flags: bit 0 = listed in LIST for its game. */
    const val FLAG_LISTED = 1

    sealed interface Request {
        /** Opens this socket's room, or updates it (same code) when it has one. */
        data class Host(val listed: Boolean, val invite: String, val game: String, val name: String) : Request
        data object Unhost : Request
        data class Find(val code: String) : Request
        data class List(val game: String) : Request
    }

    data class Listing(val code: String, val name: String, val waitingMinutes: Int)

    /** The request in [bytes], or null for anything malformed (a wrong kind, a short field, bytes left over). */
    fun decode(bytes: ByteArray): Request? {
        val r = Reader(bytes)
        return try {
            val req = when (r.byte()) {
                HOST -> {
                    val flags = r.u8()
                    if (flags and FLAG_LISTED.inv() != 0) return null
                    Request.Host(flags and FLAG_LISTED != 0, r.string(), r.string(), r.string())
                }
                UNHOST -> Request.Unhost
                FIND -> Request.Find(r.string())
                LIST -> Request.List(r.string())
                else -> return null
            }
            if (!r.done()) null else req
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    fun hosted(code: String): ByteArray = Writer(HOSTED).string(code).bytes()
    fun found(invite: String): ByteArray = Writer(FOUND).string(invite).bytes()
    fun error(request: Byte, reason: Int): ByteArray = Writer(ERROR).u8(request.toInt() and 0xFF).u8(reason).bytes()

    fun rooms(list: kotlin.collections.List<Listing>): ByteArray {
        val w = Writer(ROOMS).u8(list.size)
        for (l in list) w.string(l.code).string(l.name).u16(l.waitingMinutes.coerceIn(0, 0xFFFF))
        return w.bytes()
    }

    private class Reader(private val b: ByteArray) {
        private var i = 0
        fun byte(): Byte = b[i++]
        fun u8(): Int = b[i++].toInt() and 0xFF
        fun string(): String {
            val n = u8()
            if (i + n > b.size) throw IndexOutOfBoundsException()
            return String(b, i, n, Charsets.UTF_8).also { i += n }
        }
        fun done() = i == b.size
    }

    private class Writer(kind: Byte) {
        private val out = ByteArrayOutputStream().apply { write(kind.toInt()) }
        fun u8(v: Int) = apply { out.write(v) }
        fun u16(v: Int) = apply { out.write(v shr 8); out.write(v and 0xFF) }
        fun string(s: String) = apply {
            val raw = s.toByteArray(Charsets.UTF_8)
            require(raw.size <= 255) { "string over 255 bytes" }
            out.write(raw.size)
            out.write(raw)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }
}
