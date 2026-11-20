package com.pokedaisy.app.companion.coop

import kotlin.text.CharacterCodingException

/** The DNS-SD service and TXT records understood by both platforms. */
object CoopLanRecord {
    const val SERVICE_TYPE = "_pdcoop._tcp"
    const val NAME_PREFIX = "pdco-"
    private const val MAX_USER_BYTES = 200

    fun nameFor(peer: CoopPeerId) = NAME_PREFIX + peer.hex.substring(0, 12)

    fun encode(peer: CoopPeerId, label: String?): Map<String, String> {
        val out = linkedMapOf("k" to peer.hex)
        val bytes = label.orEmpty().encodeToByteArray()
        var end = minOf(bytes.size, MAX_USER_BYTES)
        // If the next byte is a continuation, skip the incomplete last character.
        while (end < bytes.size && (bytes[end].toInt() and 0xC0) == 0x80) end--
        if (end != 0) out["u"] = bytes.decodeToString(endIndex = end)
        return out
    }

    fun decode(attributes: Map<String, ByteArray?>): Pair<CoopPeerId, String>? {
        val key = attributes["k"] ?: return null
        if (key.size != 64 || key.any { val c = it.toInt().toChar(); c !in '0'..'9' && c !in 'a'..'f' }) return null
        val data = attributes["u"]
        if (data != null && data.size > MAX_USER_BYTES) return null
        val label = try {
            data?.decodeToString(throwOnInvalidSequence = true).orEmpty()
        } catch (_: CharacterCodingException) {
            return null
        }
        return CoopPeerId(key.decodeToString()) to label
    }
}
