package com.pokedaisy.app.companion.coop

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Android's existing JVM primitives, with the same X25519 implementation on older releases. */
internal object JvmCoopCrypto : CoopCrypto {
    private val random = SecureRandom()

    override fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
    override fun publicKey(secret: ByteArray) = CoopKeys.publicKey(secret)
    override fun dh(secret: ByteArray, peerPublic: ByteArray) = CoopKeys.dh(secret, peerPublic)

    override fun seal(key: ByteArray, nonce: ByteArray, associated: ByteArray, plain: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, associated).doFinal(plain)

    override fun open(key: ByteArray, nonce: ByteArray, associated: ByteArray, sealed: ByteArray): ByteArray? = try {
        cipher(Cipher.DECRYPT_MODE, key, nonce, associated).doFinal(sealed)
    } catch (_: GeneralSecurityException) {
        null
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, associated: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(associated)
        }
}
