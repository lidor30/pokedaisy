package com.pokedaisy.app.companion.coop

/** Host cryptography for the common Noise IK state machine. Keys and nonces are raw bytes. */
interface CoopCrypto {
    fun randomBytes(size: Int): ByteArray?
    fun publicKey(secret: ByteArray): ByteArray?
    /** Returns null for an invalid or low-order public key. */
    fun dh(secret: ByteArray, peerPublic: ByteArray): ByteArray?
    /** AES-256-GCM ciphertext followed by its 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, associated: ByteArray, plain: ByteArray): ByteArray?
    /** Null unless the ciphertext and its tag authenticate at this nonce. */
    fun open(key: ByteArray, nonce: ByteArray, associated: ByteArray, sealed: ByteArray): ByteArray?
}
