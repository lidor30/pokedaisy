package com.pokedaisy.app.companion.coop

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One direction of a finished handshake: AES-256-GCM with the Noise nonce (4 zero bytes + an 8-byte
 * big-endian counter) and no associated data. [decrypt] counts the nonce only on success, so a
 * forged or replayed message neither advances nor breaks the stream; both refuse after 2^63 messages.
 */
class CoopCipher internal constructor(private val key: ByteArray) {
    private var n = 0L

    @Synchronized
    fun encrypt(plain: ByteArray): ByteArray {
        check(n >= 0) { "cipher exhausted" }
        val out = CoopNoise.aead(true, key, n, ByteArray(0), plain)!!
        n++
        return out
    }

    /** The plaintext, or null when [sealed] doesn't authenticate at the current nonce. */
    @Synchronized
    fun decrypt(sealed: ByteArray): ByteArray? {
        if (n < 0) return null
        val out = CoopNoise.aead(false, key, n, ByteArray(0), sealed) ?: return null
        n++
        return out
    }
}

/**
 * Noise_IK_25519_AESGCM_SHA256 (Noise spec revision 34, section 5), the IK pattern only:
 * `<- s`, then `-> e, es, s, ss` and `<- e, ee, se`. The initiator knows the responder's static
 * key; the responder learns the initiator's from message 1 ([remoteStatic]).
 */
class CoopNoise private constructor(
    private val initiator: Boolean,
    private val s: ByteArray,
    private val sPub: ByteArray,
    private var rs: ByteArray?,
    prologue: ByteArray,
    private val fixedEphemeral: ByteArray?,
) {
    private var ck = ByteArray(HASHLEN)
    private var h = ByteArray(HASHLEN)
    private var k: ByteArray? = null
    private var nonce = 0L
    private var e: ByteArray? = null
    private var ePub: ByteArray? = null
    private var re: ByteArray? = null
    private var step = 0
    private var failed = false
    private var splitDone = false

    init {
        val name = PROTOCOL.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= HASHLEN) name.copyOf(HASHLEN) else sha256(name)
        ck = h.copyOf()
        mixHash(prologue)
        // The pre-message `<- s`: both sides know the responder's static key.
        mixHash(if (initiator) rs!! else sPub)
    }

    /** The peer's static key: known up front to the initiator, to the responder after message 1. */
    val remoteStatic: ByteArray? get() = rs?.copyOf()

    val isDone: Boolean get() = step == 2 && !failed

    /** The handshake hash, once [isDone]. */
    val handshakeHash: ByteArray
        get() {
            check(isDone) { "handshake not finished" }
            return h.copyOf()
        }

    /** Writes the next handshake message carrying [payload]; only valid on this side's turn. */
    fun writeMessage(payload: ByteArray): ByteArray {
        check(!failed) { "handshake failed" }
        check(if (initiator) step == 0 else step == 1) { "not this side's turn" }
        val out = java.io.ByteArrayOutputStream()
        genEphemeral()
        out.write(ePub!!)
        mixHash(ePub!!)
        if (initiator) {
            mixKey(dhOrThrow(e!!, rs!!)) // es
            out.write(encryptAndHash(sPub))
            mixKey(dhOrThrow(s, rs!!)) // ss
        } else {
            mixKey(dhOrThrow(e!!, re!!)) // ee
            mixKey(dhOrThrow(e!!, rs!!)) // se
        }
        out.write(encryptAndHash(payload))
        step++
        return out.toByteArray()
    }

    /** Reads the peer's handshake message; null when it is too short or doesn't authenticate (the handshake is then dead). */
    fun readMessage(message: ByteArray): ByteArray? {
        check(if (initiator) step == 1 else step == 0) { "not this side's turn" }
        if (failed) return null
        val result = try {
            read(message)
        } catch (_: GeneralSecurityException) {
            null
        }
        if (result == null) failed = true else step++
        return result
    }

    private fun read(m: ByteArray): ByteArray? {
        val min = if (initiator) 32 + TAG else 32 + 32 + TAG + TAG
        if (m.size < min) return null
        val remoteE = m.copyOfRange(0, 32)
        re = remoteE
        mixHash(remoteE)
        var off = 32
        if (initiator) {
            mixKey(CoopKeys.dh(e!!, remoteE) ?: return null) // ee
            mixKey(CoopKeys.dh(s, remoteE) ?: return null) // se
        } else {
            mixKey(CoopKeys.dh(s, remoteE) ?: return null) // es
            val staticPub = decryptAndHash(m.copyOfRange(off, off + 32 + TAG)) ?: return null
            off += 32 + TAG
            rs = staticPub
            mixKey(CoopKeys.dh(s, staticPub) ?: return null) // ss
        }
        return decryptAndHash(m.copyOfRange(off, m.size))
    }

    /** The (send, receive) ciphers for this side. Valid once, after [isDone]. */
    fun split(): Pair<CoopCipher, CoopCipher> {
        check(isDone && !splitDone) { "split needs a finished handshake, once" }
        splitDone = true
        val (k1, k2) = hkdf(ck, ByteArray(0))
        return if (initiator) CoopCipher(k1) to CoopCipher(k2) else CoopCipher(k2) to CoopCipher(k1)
    }

    private fun genEphemeral() {
        val sk = fixedEphemeral?.copyOf() ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        e = sk
        ePub = CoopKeys.publicKey(sk)
    }

    private fun dhOrThrow(secret: ByteArray, pub: ByteArray): ByteArray =
        CoopKeys.dh(secret, pub) ?: throw IllegalStateException("low-order public key")

    private fun mixHash(data: ByteArray) {
        h = sha256(h + data)
    }

    private fun mixKey(input: ByteArray) {
        val (nck, tk) = hkdf(ck, input)
        ck = nck
        k = tk
        nonce = 0
    }

    private fun encryptAndHash(plain: ByteArray): ByteArray {
        val key = k
        val out = if (key == null) plain else aead(true, key, nonce++, h, plain)!!
        mixHash(out)
        return out
    }

    private fun decryptAndHash(sealed: ByteArray): ByteArray? {
        val key = k
        val out = if (key == null) sealed else aead(false, key, nonce, h, sealed) ?: return null
        if (key != null) nonce++
        mixHash(sealed)
        return out
    }

    companion object {
        const val PROTOCOL = "Noise_IK_25519_AESGCM_SHA256"
        private const val HASHLEN = 32
        private const val TAG = 16

        fun initiator(staticSecret: ByteArray, remoteStatic: ByteArray, prologue: ByteArray, ephemeral: ByteArray? = null): CoopNoise {
            require(remoteStatic.size == 32) { "remote static: 32 bytes" }
            return CoopNoise(true, staticSecret, CoopKeys.publicKey(staticSecret), remoteStatic.copyOf(), prologue, ephemeral)
        }

        fun responder(staticSecret: ByteArray, prologue: ByteArray, ephemeral: ByteArray? = null): CoopNoise =
            CoopNoise(false, staticSecret, CoopKeys.publicKey(staticSecret), null, prologue, ephemeral)

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        /** The spec's HKDF with two outputs. */
        private fun hkdf(chainingKey: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
            val temp = hmac(chainingKey, input)
            val o1 = hmac(temp, byteArrayOf(1))
            val o2 = hmac(temp, o1 + byteArrayOf(2))
            return o1 to o2
        }

        /** AES-256-GCM with the Noise nonce; null on a failed authentication (decrypt) or a spent counter. */
        internal fun aead(encrypt: Boolean, key: ByteArray, counter: Long, ad: ByteArray, input: ByteArray): ByteArray? {
            if (counter < 0) return null
            val iv = ByteArray(12)
            for (i in 0 until 8) iv[4 + i] = (counter ushr (56 - 8 * i)).toByte()
            return try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                c.updateAAD(ad)
                c.doFinal(input)
            } catch (e: GeneralSecurityException) {
                if (encrypt) throw e
                null
            }
        }
    }
}
