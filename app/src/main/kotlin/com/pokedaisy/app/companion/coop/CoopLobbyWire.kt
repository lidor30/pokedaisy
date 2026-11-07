package com.pokedaisy.app.companion.coop

import java.io.ByteArrayOutputStream

/**
 * The relay's lobby messages (`/v1/lobby`), the app's side: the server's `LobbyWire` (server/coop-relay) with the
 * directions swapped - change the two together. Binary, byte 0 the kind; a string is a u8 byte count and that many UTF-8
 * bytes; numbers are big-endian. Also the room code's rules, which the server's `RelayLobby` checks the same way.
 */
object CoopLobbyWire {
    const val HOST: Byte = 0x20
    const val UNHOST: Byte = 0x21
    const val FIND: Byte = 0x22
    const val LIST: Byte = 0x23

    const val HOSTED: Byte = 0x30
    const val FOUND: Byte = 0x31
    const val ROOMS: Byte = 0x32
    const val ERROR: Byte = 0x3F

    const val NOT_FOUND = 1
    const val BAD_REQUEST = 2
    const val LIMIT = 3
    const val FULL = 4

    private const val FLAG_LISTED = 1

    /** The server's code alphabet: no 0 / O, 1 / I / L or U. */
    const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CODE_LENGTH = 6
    private const val MAX_NAME = 16
    private val NAME_CHARS = Regex("[\\p{L}\\p{N} .'!?&♂♀-]")

    data class Listing(val code: String, val name: String, val waitingMinutes: Int)

    sealed interface Answer {
        data class Hosted(val code: String) : Answer
        data class Found(val invite: String) : Answer
        data class Rooms(val rooms: List<Listing>) : Answer
        data class Error(val request: Byte, val reason: Int) : Answer
    }

    fun host(listed: Boolean, invite: String, game: String, name: String): ByteArray =
        Writer(HOST).u8(if (listed) FLAG_LISTED else 0).string(invite).string(game).string(name).bytes()

    fun unhost(): ByteArray = byteArrayOf(UNHOST)
    fun find(code: String): ByteArray = Writer(FIND).string(code).bytes()
    fun list(game: String): ByteArray = Writer(LIST).string(game).bytes()

    /** The answer in [bytes], or null for anything malformed. */
    fun decode(bytes: ByteArray): Answer? {
        var i = 0
        fun u8(): Int = bytes[i++].toInt() and 0xFF
        fun string(): String {
            val n = u8()
            if (i + n > bytes.size) throw IndexOutOfBoundsException()
            return String(bytes, i, n, Charsets.UTF_8).also { i += n }
        }
        return try {
            val a = when (bytes[i++]) {
                HOSTED -> Answer.Hosted(string())
                FOUND -> Answer.Found(string())
                ROOMS -> Answer.Rooms(List(u8()) { Listing(string(), string(), (u8() shl 8) or u8()) })
                ERROR -> Answer.Error(bytes[i++], u8())
                else -> return null
            }
            if (i != bytes.size) null else a
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    /** What a player typed ("k7q 4mx", "K7Q-4MX") as a code, or null when it can't be one. */
    fun normalizeCode(text: String): String? {
        if (text.length > 32) return null
        val t = text.uppercase().filter { it != ' ' && it != '-' }
        if (t.length != CODE_LENGTH || t.any { it !in CODE_ALPHABET }) return null
        return t
    }

    /** A code as shown: "K7Q-4MX". */
    fun showCode(code: String): String = if (code.length == CODE_LENGTH) code.substring(0, 3) + "-" + code.substring(3) else code

    /** A listed room's name the server takes: the trainer's name with anything else dropped, or [fallback]. */
    fun cleanName(name: String, fallback: String): String {
        val kept = name.map { it.toString() }.filter { NAME_CHARS.matches(it) }.joinToString("")
            .trim().replace(Regex("\\s+"), " ")
        return kept.take(MAX_NAME).trim().ifEmpty { fallback }
    }

    /** The lobby's game key: the ROM's SHA-1, its first 16 hex digits. Rooms are listed per key, so only the same ROM shows. */
    fun gameKey(romSha1: String): String = romSha1.lowercase().take(16)

    /**
     * The invite the lobby hands out: [ticket] without its LAN addresses (they'd reach strangers), only when it has the
     * invite secret that a relay join needs; else null.
     */
    fun lobbyInvite(ticket: String): String? {
        val t = CoopTicket.decode(ticket) ?: return null
        val secret = t.inviteSecret ?: return null
        return CoopTicket(t.peer, secret, emptyList()).encode()
    }

    private class Writer(kind: Byte) {
        private val out = ByteArrayOutputStream().apply { write(kind.toInt()) }
        fun u8(v: Int) = apply { out.write(v) }
        fun string(s: String) = apply {
            val raw = s.toByteArray(Charsets.UTF_8)
            require(raw.size <= 255) { "string over 255 bytes" }
            out.write(raw.size)
            out.write(raw)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }
}
