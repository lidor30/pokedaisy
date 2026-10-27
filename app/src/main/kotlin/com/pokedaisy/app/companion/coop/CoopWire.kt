package com.pokedaisy.app.companion.coop

/**
 * App message framing inside a transport frame: a u16 big-endian type, then the payload.
 * Types 0x0001-0x00FF are the link's own ([HELLO], [PAIR_ACCEPT], [PAIR_DENY], [BYE]);
 * 0x0100 up belong to the co-op features.
 */
object CoopWire {
    const val MAX_FRAME = 65_536
    const val MAX_PAYLOAD = 65_534
    const val FIRST_APP_TYPE = 0x0100

    const val HELLO = 1
    const val PAIR_ACCEPT = 2
    const val PAIR_DENY = 3
    const val BYE = 4

    fun encode(type: Int, payload: ByteArray): ByteArray {
        require(type in 1..0xFFFF) { "message type out of range: $type" }
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val out = ByteArray(2 + payload.size)
        out[0] = (type shr 8).toByte()
        out[1] = type.toByte()
        payload.copyInto(out, 2)
        return out
    }

    /** Null for a frame shorter than 2 bytes, longer than [MAX_FRAME], or of type 0. */
    fun decode(frame: ByteArray): CoopMessage? {
        if (frame.size < 2 || frame.size > MAX_FRAME) return null
        val type = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        if (type == 0) return null
        return CoopMessage(type, frame.copyOfRange(2, frame.size))
    }
}

/**
 * The HELLO each side sends first on a connection: who it is (the save), what it runs and
 * the ticket to dial it back. Layout: u16 protocol, u8 appVersion length (1..32, ASCII
 * `[0-9A-Za-z.+-]`), appVersion, 20 bytes ROM SHA-1, u16 trainer ID, u16 secret ID, 8 name
 * bytes, u8 female (0 / 1), u16 ticket length (0..2,048, 0 = none), ticket. Nothing after it.
 */
class CoopHello(
    val protocol: Int,
    val appVersion: String,
    /** 40 lowercase hex characters. */
    val romSha1: String,
    val save: CoopSaveId,
    val female: Boolean,
    /** A [CoopTicket] string ([CoopInvite.isTicket]), or null. */
    val ticket: String?,
) {
    init {
        require(protocol in 0..0xFFFF) { "protocol out of range" }
        require(VERSION.matches(appVersion)) { "bad app version" }
        require(SHA1.matches(romSha1)) { "bad ROM SHA-1" }
        require(ticket == null || CoopInvite.isTicket(ticket)) { "bad ticket" }
    }

    fun encode(): ByteArray {
        val version = appVersion.toByteArray(Charsets.US_ASCII)
        val t = ticket?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0)
        val out = java.io.ByteArrayOutputStream(FIXED + version.size + t.size)
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        u16(protocol)
        out.write(version.size)
        out.write(version)
        for (i in 0 until 20) out.write(romSha1.substring(i * 2, i * 2 + 2).toInt(16))
        u16(save.trainerId)
        u16(save.secretId)
        out.write(save.name)
        out.write(if (female) 1 else 0)
        u16(t.size)
        out.write(t)
        return out.toByteArray()
    }

    companion object {
        private val VERSION = Regex("^[0-9A-Za-z.+-]{1,32}$")
        private val SHA1 = Regex("^[0-9a-f]{40}$")
        private const val MAX_TICKET = 2_048

        /** Every byte but the app version's and the ticket's. */
        private const val FIXED = 2 + 1 + 20 + 2 + 2 + 8 + 1 + 2

        /** Null on any field out of range, a short or overlong buffer, or a byte past the ticket. */
        fun decode(b: ByteArray): CoopHello? {
            var p = 0
            fun has(n: Int) = b.size - p >= n
            fun u8(): Int = b[p++].toInt() and 0xFF
            fun u16(): Int = (u8() shl 8) or u8()
            fun ascii(n: Int): String {
                val s = String(b, p, n, Charsets.ISO_8859_1)
                p += n
                return s
            }

            if (!has(3)) return null
            val protocol = u16()
            val versionLen = u8()
            if (versionLen !in 1..32 || !has(versionLen + 20 + 2 + 2 + 8 + 1 + 2)) return null
            val version = ascii(versionLen)
            if (!VERSION.matches(version)) return null
            val sha1 = toHex(b.copyOfRange(p, p + 20)).also { p += 20 }
            val trainerId = u16()
            val secretId = u16()
            val name = b.copyOfRange(p, p + 8).also { p += 8 }
            val female = when (u8()) {
                0 -> false
                1 -> true
                else -> return null
            }
            val ticketLen = u16()
            if (ticketLen > MAX_TICKET || b.size - p != ticketLen) return null
            val ticket = if (ticketLen == 0) null else ascii(ticketLen)
            if (ticket != null && !CoopInvite.isTicket(ticket)) return null
            return CoopHello(protocol, version, sha1, CoopSaveId(trainerId, secretId, name), female, ticket)
        }
    }
}
