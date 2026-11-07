package com.pokedaisy.app.companion.coop

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.math.ec.rfc7748.X25519

/**
 * The co-op link's X25519 key operations and the relay room ids derived from them. Pure functions
 * over raw 32-byte keys; nothing here logs or keeps a key.
 */
object CoopKeys {
    private const val KEY = 32
    private val INVITE_DATA = "pokedaisy coop invite".toByteArray(Charsets.US_ASCII)
    private val PAIR_DATA = "pokedaisy coop pair".toByteArray(Charsets.US_ASCII)

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

    /** The invite room: the first 16 bytes of HMAC-SHA256(invite secret, "pokedaisy coop invite"), as hex. */
    fun inviteRoom(inviteSecret: ByteArray): String = roomHex(inviteSecret, INVITE_DATA)

    /**
     * The room one pair meets in, the same from both sides and unguessable without a private key:
     * HMAC-SHA256 keyed by the pair's DH secret over both public keys (smaller first). Null when the
     * DH result is all zero.
     */
    fun pairRoom(ownSecret: ByteArray, own: CoopPeerId, partner: CoopPeerId): String? {
        val shared = dh(ownSecret, partner.bytes()) ?: return null
        val a = own.bytes()
        val b = partner.bytes()
        val (lo, hi) = if (compareUnsigned(a, b) <= 0) a to b else b to a
        return roomHex(shared, PAIR_DATA + lo + hi)
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }

    private fun roomHex(key: ByteArray, data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val tag = mac.doFinal(data)
        return buildString(32) { for (i in 0 until 16) append("%02x".format(tag[i].toInt() and 0xFF)) }
    }
}
