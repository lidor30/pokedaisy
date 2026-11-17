package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.coop.x25519.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The X25519 copied into `companion/coop/x25519/` (Bouncy Castle's, trimmed: the public key by the plain ladder, the
 * inverse by Fermat) against RFC 7748's vectors and against the whole Bouncy Castle library, which stays a test
 * dependency for this: a key made by an older build must still pair with one made by this one.
 */
class X25519Test {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun publicOf(secret: ByteArray) = ByteArray(32).also { X25519.generatePublicKey(secret, 0, it, 0) }

    private fun agree(secret: ByteArray, peer: ByteArray): ByteArray? =
        ByteArray(32).takeIf { X25519.calculateAgreement(secret, 0, peer, 0, it, 0) }

    @Test fun rfc7748ScalarMultVectors() {
        // RFC 7748 section 5.2.
        val out = ByteArray(32)
        X25519.scalarMult(
            hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"), 0,
            hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"), 0, out, 0,
        )
        assertArrayEquals(hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"), out)
        X25519.scalarMult(
            hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"), 0,
            hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"), 0, out, 0,
        )
        assertArrayEquals(hex("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"), out)
    }

    @Test fun rfc7748DiffieHellmanVector() {
        // RFC 7748 section 6.1.
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePub = publicOf(alice)
        val bobPub = publicOf(bob)
        assertArrayEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), alicePub)
        assertArrayEquals(hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), bobPub)
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertArrayEquals(shared, agree(alice, bobPub))
        assertArrayEquals(shared, agree(bob, alicePub))
    }

    @Test fun agreesWithBouncyCastle() {
        val rnd = Random(0x25519)
        repeat(500) {
            val a = ByteArray(32).also(rnd::nextBytes)
            val b = ByteArray(32).also(rnd::nextBytes)
            val bcA = ByteArray(32).also { org.bouncycastle.math.ec.rfc7748.X25519.generatePublicKey(a, 0, it, 0) }
            val bcB = ByteArray(32).also { org.bouncycastle.math.ec.rfc7748.X25519.generatePublicKey(b, 0, it, 0) }
            assertArrayEquals(bcA, publicOf(a))
            val bcShared = ByteArray(32)
            assertTrue(org.bouncycastle.math.ec.rfc7748.X25519.calculateAgreement(a, 0, bcB, 0, bcShared, 0))
            assertArrayEquals(bcShared, agree(a, publicOf(b)))
            assertArrayEquals(bcShared, agree(b, bcA))
        }
    }

    @Test fun lowOrderPeerKeyIsRefused() {
        val secret = ByteArray(32) { 7 }
        assertFalse(X25519.calculateAgreement(secret, 0, ByteArray(32), 0, ByteArray(32), 0))
        // u = 1: a point of small order, so the shared secret comes out all zero.
        val one = ByteArray(32).also { it[0] = 1 }
        assertFalse(X25519.calculateAgreement(secret, 0, one, 0, ByteArray(32), 0))
        assertEquals(32, X25519.POINT_SIZE)
    }
}
