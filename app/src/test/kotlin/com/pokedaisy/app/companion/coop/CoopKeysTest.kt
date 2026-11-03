package com.pokedaisy.app.companion.coop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopKeysTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")

    @Test fun publicKeyMatchesRfc7748() {
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(CoopKeys.publicKey(alice)))
    }

    @Test fun dhAgreesBothWays() {
        val a = CoopKeys.dh(alice, CoopKeys.publicKey(bob))
        val b = CoopKeys.dh(bob, CoopKeys.publicKey(alice))
        assertNotNull(a)
        assertArrayEquals(a, b)
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", hex(a!!))
    }

    @Test fun dhRefusesLowOrderPoint() {
        assertNull(CoopKeys.dh(alice, ByteArray(32)))
    }

    @Test fun inviteRoomIs32Hex() {
        val room = CoopKeys.inviteRoom(ByteArray(16) { it.toByte() })
        assertTrue(Regex("^[0-9a-f]{32}$").matches(room))
        assertNotEquals(room, CoopKeys.inviteRoom(ByteArray(16) { (it + 1).toByte() }))
    }

    private fun id(secret: ByteArray) = CoopPeerId.of(CoopKeys.publicKey(secret))

    @Test fun pairRoomSameFromBothSides() {
        val r1 = CoopKeys.pairRoom(alice, id(alice), id(bob))
        val r2 = CoopKeys.pairRoom(bob, id(bob), id(alice))
        assertNotNull(r1)
        assertEquals(r1, r2)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(r1!!))
    }

    @Test fun pairRoomDiffersPerPair() {
        val carol = ByteArray(32) { (it + 9).toByte() }
        assertNotEquals(CoopKeys.pairRoom(alice, id(alice), id(bob)), CoopKeys.pairRoom(alice, id(alice), id(carol)))
    }

    @Test fun pairRoomNeverContainsKeyHex() {
        val room = CoopKeys.pairRoom(alice, id(alice), id(bob))!!
        for (k in listOf(alice, bob, CoopKeys.publicKey(alice), CoopKeys.publicKey(bob))) {
            assertFalse(room.contains(hex(k).take(16)))
        }
    }
}
