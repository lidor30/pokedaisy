package com.pokedaisy.app.companion.coop

import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CoopTicket]: the encoded form, and decoding everything else to null. */
class CoopTicketTest {
    private val peer = CoopPeerId.of(ByteArray(32) { (it * 3 + 1).toByte() })
    private val secret = ByteArray(16) { (0xF0 + it).toByte() }
    private val v4 = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)), 1)
    private val v6 = InetSocketAddress(InetAddress.getByAddress(ByteArray(16) { (it + 1).toByte() }), 65_535)

    private val alphabet = "abcdefghijklmnopqrstuvwxyz234567"

    private fun b32(bytes: ByteArray): String {
        val out = StringBuilder()
        var bits = 0
        var acc = 0
        for (b in bytes) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) { out.append(alphabet[(acc shr (bits - 5)) and 31]); bits -= 5 }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(alphabet[(acc shl (5 - bits)) and 31])
        return out.toString()
    }

    /** A ticket from raw bytes, for the malformed cases. */
    private fun raw(vararg parts: ByteArray): String = "pdco" + b32(parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val key get() = peer.bytes()

    @Test fun roundTripsKeySecretAndAddresses() {
        val d = CoopTicket.decode(CoopTicket(peer, secret, listOf(v4, v6)).encode())!!
        assertEquals(peer, d.peer)
        assertArrayEquals(secret, d.inviteSecret)
        assertEquals(listOf(v4, v6), d.addresses)
        assertEquals(1, d.addresses[0].port)
        assertEquals(65_535, d.addresses[1].port)
        val bare = CoopTicket.decode(CoopTicket(peer, null, listOf(v6)).encode())!!
        assertNull(bare.inviteSecret)
        assertEquals(listOf(v6), bare.addresses)
    }

    @Test fun peerOnlyHasNoSecretOrAddresses() {
        val d = CoopTicket.decode(CoopTicket.peerOnly(peer))!!
        assertEquals(peer, d.peer)
        assertNull(d.inviteSecret)
        assertTrue(d.addresses.isEmpty())
    }

    @Test fun encodedMatchesInviteRegex() {
        val min = CoopTicket.peerOnly(peer)
        assertEquals(4 + 56, min.length)
        assertTrue(CoopInvite.isTicket(min))
        // The largest ticket is 127 bytes: 204 characters, one inside the regex's 205.
        val max = CoopTicket(peer, secret, listOf(v6, v6, v6, v6)).encode()
        assertEquals(4 + 204, max.length)
        assertTrue(CoopInvite.isTicket(max))
    }

    @Test fun refusesBadInput() {
        val good = CoopTicket(peer, secret, listOf(v4)).encode()
        assertNotNullTicket(good)
        assertNull(CoopTicket.decode("endpoint" + good.substring(4)))
        assertNull(CoopTicket.decode(good.uppercase()))
        assertNull(CoopTicket.decode(""))
        for (ch in "189") assertNull(CoopTicket.decode(good.dropLast(1) + ch))
        assertNull(CoopTicket.decode(raw(b(2), key, b(0), b(0))))                       // wrong version
        assertNull(CoopTicket.decode(raw(b(1), key, b(2), b(0))))                       // flag bit 1
        assertNull(CoopTicket.decode(raw(b(1), key, b(0), b(5))))                       // five addresses
        assertNull(CoopTicket.decode(raw(b(1), key, b(0), b(1, 5, 1, 2, 3, 4, 0, 80)))) // family 5
        assertNull(CoopTicket.decode(raw(b(1), key, b(0), b(1, 4, 1, 2, 3, 4, 0, 0))))  // port 0
        assertNull(CoopTicket.decode(raw(b(1), key, b(0), b(0), b(0))))                 // a byte past the end
        assertNull(CoopTicket.decode(raw(b(1), key, b(1), secret.copyOf(15))))          // truncated secret
        assertNull(CoopTicket.decode(raw(b(1), key.copyOf(20))))                        // truncated key
        // 58 bytes = 464 bits: 93 characters, the last carrying one pad bit. Set it.
        assertEquals(0, alphabet.indexOf(good.last()) and 1)
        assertNull(CoopTicket.decode(good.dropLast(1) + alphabet[alphabet.indexOf(good.last()) or 1]))
    }

    private fun assertNotNullTicket(s: String) = assertTrue(CoopTicket.decode(s) != null)

    @Test fun addressesAreNeverLookedUp() {
        // decode builds addresses from bytes (InetAddress.getByAddress): no host name is resolved.
        val d = CoopTicket.decode(CoopTicket(peer, null, listOf(v4)).encode())!!
        assertEquals("192.168.1.20", d.addresses[0].address.hostAddress)
        assertFalse(d.addresses[0].isUnresolved)
        assertEquals(4, d.addresses[0].address.address.size)
    }

    @Test fun encodeRequiresAtMostFourAddresses() {
        assertThrows(IllegalArgumentException::class.java) { CoopTicket(peer, null, List(5) { v4 }).encode() }
    }
}
