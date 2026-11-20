package com.pokedaisy.app.companion.coop

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Buffer
import okio.ByteString.Companion.toByteString

/**
 * One direction of a finished handshake: AES-256-GCM with the Noise nonce (4 zero bytes + an 8-byte
 * big-endian counter) and no associated data. [decrypt] counts the nonce only on success, so a
 * forged or replayed message neither advances nor breaks the stream; both refuse after 2^63 messages.
 */
class CoopCipher internal constructor(private val crypto: CoopCrypto, private val key: ByteArray) {
    private val lock = SynchronizedObject()
    private var n = 0L

    fun encrypt(plain: ByteArray): ByteArray = synchronized(lock) {
        check(n >= 0) { "cipher exhausted" }
        checkNotNull(crypto.seal(key, noiseNonce(n), EMPTY, plain)) { "co-op encryption failed" }.also { n++ }
    }

    /** The plaintext, or null when [sealed] doesn't authenticate at the current nonce. */
    fun decrypt(sealed: ByteArray): ByteArray? = synchronized(lock) {
        if (n < 0) return@synchronized null
        crypto.open(key, noiseNonce(n), EMPTY, sealed)?.also { n++ }
    }
}

/**
 * Noise_IK_25519_AESGCM_SHA256 (Noise spec revision 34, section 5), the IK pattern only:
 * `<- s`, then `-> e, es, s, ss` and `<- e, ee, se`. The initiator knows the responder's static
 * key; the responder learns the initiator's from message 1 ([remoteStatic]).
 */
class CoopNoise private constructor(
    private val crypto: CoopCrypto,
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
        val name = PROTOCOL.encodeToByteArray()
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
        val out = Buffer()
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
        return out.readByteArray()
    }

    /** Reads the peer's handshake message; null when it is too short or doesn't authenticate (the handshake is then dead). */
    fun readMessage(message: ByteArray): ByteArray? {
        check(if (initiator) step == 1 else step == 0) { "not this side's turn" }
        if (failed) return null
        val result = read(message)
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
            mixKey(crypto.dh(e!!, remoteE) ?: return null) // ee
            mixKey(crypto.dh(s, remoteE) ?: return null) // se
        } else {
            mixKey(crypto.dh(s, remoteE) ?: return null) // es
            val staticPub = decryptAndHash(m.copyOfRange(off, off + 32 + TAG)) ?: return null
            off += 32 + TAG
            rs = staticPub
            mixKey(crypto.dh(s, staticPub) ?: return null) // ss
        }
        return decryptAndHash(m.copyOfRange(off, m.size))
    }

    /** The (send, receive) ciphers for this side. Valid once, after [isDone]. */
    fun split(): Pair<CoopCipher, CoopCipher> {
        check(isDone && !splitDone) { "split needs a finished handshake, once" }
        splitDone = true
        val (k1, k2) = hkdf(ck, ByteArray(0))
        return if (initiator) CoopCipher(crypto, k1) to CoopCipher(crypto, k2) else CoopCipher(crypto, k2) to CoopCipher(crypto, k1)
    }

    private fun genEphemeral() {
        val sk = fixedEphemeral?.copyOf() ?: checkNotNull(crypto.randomBytes(32)) { "co-op randomness unavailable" }
        e = sk
        ePub = checkNotNull(crypto.publicKey(sk)) { "invalid ephemeral key" }
    }

    private fun dhOrThrow(secret: ByteArray, pub: ByteArray): ByteArray =
        crypto.dh(secret, pub) ?: throw IllegalStateException("low-order public key")

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
        val out = if (key == null) plain else {
            checkNotNull(crypto.seal(key, noiseNonce(nonce), h, plain)) { "co-op encryption failed" }.also { nonce++ }
        }
        mixHash(out)
        return out
    }

    private fun decryptAndHash(sealed: ByteArray): ByteArray? {
        val key = k
        val out = if (key == null) sealed else crypto.open(key, noiseNonce(nonce), h, sealed) ?: return null
        if (key != null) nonce++
        mixHash(sealed)
        return out
    }

    companion object {
        const val PROTOCOL = "Noise_IK_25519_AESGCM_SHA256"
        private const val HASHLEN = 32
        private const val TAG = 16

        fun initiator(
            crypto: CoopCrypto, staticSecret: ByteArray, remoteStatic: ByteArray, prologue: ByteArray, ephemeral: ByteArray? = null,
        ): CoopNoise {
            require(remoteStatic.size == 32) { "remote static: 32 bytes" }
            return CoopNoise(crypto, true, staticSecret, checkNotNull(crypto.publicKey(staticSecret)), remoteStatic.copyOf(), prologue, ephemeral)
        }

        fun responder(crypto: CoopCrypto, staticSecret: ByteArray, prologue: ByteArray, ephemeral: ByteArray? = null): CoopNoise =
            CoopNoise(crypto, false, staticSecret, checkNotNull(crypto.publicKey(staticSecret)), null, prologue, ephemeral)

        private fun sha256(data: ByteArray): ByteArray = data.toByteString().sha256().toByteArray()

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            data.toByteString().hmacSha256(key.toByteString()).toByteArray()

        /** The spec's HKDF with two outputs. */
        private fun hkdf(chainingKey: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
            val temp = hmac(chainingKey, input)
            val o1 = hmac(temp, byteArrayOf(1))
            val o2 = hmac(temp, o1 + byteArrayOf(2))
            return o1 to o2
        }
    }
}

private val EMPTY = ByteArray(0)

private fun noiseNonce(counter: Long): ByteArray {
    check(counter >= 0) { "cipher exhausted" }
    val iv = ByteArray(12)
    for (i in 0 until 8) iv[4 + i] = (counter ushr (56 - 8 * i)).toByte()
    return iv
}
