package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.coop.x25519.X25519

/**
 * The co-op link's X25519 key operations and the relay room ids derived from them. Pure functions
 * over raw 32-byte keys; nothing here logs or keeps a key.
 */
object CoopKeys {
    private const val KEY = 32
    private val rooms = CoopRooms(JvmCoopCrypto)

    /** The X25519 public key of [secret] (32 bytes to 32 bytes). */
    fun publicKey(secret: ByteArray): ByteArray {
        require(secret.size == KEY) { "x25519 secret: 32 bytes" }
        return ByteArray(KEY).also { X25519.generatePublicKey(secret, 0, it, 0) }
    }

    /** The X25519 shared secret, or null when it is all zero (a low-order public key). */
    fun dh(secret: ByteArray, peerPublic: ByteArray): ByteArray? {
        if (secret.size != KEY || peerPublic.size != KEY) return null
        val out = ByteArray(KEY)
        return if (X25519.calculateAgreement(secret, 0, peerPublic, 0, out, 0)) out else null
    }

    fun inviteRoom(inviteSecret: ByteArray): String = rooms.invite(inviteSecret)

    fun pairRoom(ownSecret: ByteArray, own: CoopPeerId, partner: CoopPeerId): String? =
        rooms.pair(ownSecret, own, partner)
}
