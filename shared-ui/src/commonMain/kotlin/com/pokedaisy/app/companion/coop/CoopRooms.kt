package com.pokedaisy.app.companion.coop

import okio.ByteString.Companion.toByteString

/** Keep the same room IDs on Android and iOS. No room identifier contains a key. */
class CoopRooms(private val crypto: CoopCrypto) {
    fun invite(secret: ByteArray): String = roomHex(secret, INVITE_DATA)

    fun pair(secret: ByteArray, own: CoopPeerId, partner: CoopPeerId): String? {
        val shared = crypto.dh(secret, partner.bytes()) ?: return null
        val (first, second) = if (own.hex <= partner.hex) own to partner else partner to own
        return roomHex(shared, PAIR_DATA + first.bytes() + second.bytes())
    }

    private fun roomHex(key: ByteArray, data: ByteArray): String =
        data.toByteString().hmacSha256(key.toByteString()).substring(0, 16).hex()

    private companion object {
        val INVITE_DATA = "pokedaisy coop invite".encodeToByteArray()
        val PAIR_DATA = "pokedaisy coop pair".encodeToByteArray()
    }
}
