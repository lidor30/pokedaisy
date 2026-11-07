package com.pokedaisy.app.companion.coop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CoopWire] frames and the [CoopHello]: everything received is checked field by field. */
class CoopWireTest {
    private val name = byteArrayOf(0xBB.toByte(), 0xC6.toByte(), 0xC9.toByte(), 0xC8.toByte(), 0xFF.toByte(), 0, 0, 0)
    private val save = CoopSaveId(0x1234, 0xABCD, name)
    private val ticket = ticketOf(0xC2)

    private fun hello(ticket: String? = this.ticket) =
        CoopHello(protocol = 1, appVersion = "1.3.1", romSha1 = EMERALD_COOP_SHA1, save = save, female = true, ticket = ticket)

    @Test fun roundTrip() {
        val m = CoopWire.decode(CoopWire.encode(0x0123, byteArrayOf(1, 2)))!!
        assertEquals(0x0123, m.type)
        assertArrayEquals(byteArrayOf(1, 2), m.payload)
        assertArrayEquals(byteArrayOf(0x01, 0x23, 1, 2), CoopWire.encode(0x0123, byteArrayOf(1, 2)))
    }

    @Test fun theLargestFramesPass() {
        val m = CoopWire.decode(CoopWire.encode(0xFFFF, ByteArray(CoopWire.MAX_PAYLOAD)))!!
        assertEquals(0xFFFF, m.type)
        assertEquals(CoopWire.MAX_PAYLOAD, m.payload.size)
        assertNotNull(CoopWire.decode(byteArrayOf(0, 1)))
    }

    @Test fun refusesOversizeAndBadTypes() {
        assertThrows(IllegalArgumentException::class.java) { CoopWire.encode(5, ByteArray(65_535)) }
        assertThrows(IllegalArgumentException::class.java) { CoopWire.encode(0x10000, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { CoopWire.encode(0, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { CoopWire.encode(-1, ByteArray(0)) }
        assertNull(CoopWire.decode(byteArrayOf(0)))
        assertNull(CoopWire.decode(byteArrayOf()))
        assertNull(CoopWire.decode(byteArrayOf(0, 0)))
        assertNull(CoopWire.decode(ByteArray(CoopWire.MAX_FRAME + 1).also { it[1] = 5 }))
    }

    @Test fun helloRoundTrip() {
        val h = CoopHello.decode(hello().encode())!!
        assertEquals(1, h.protocol)
        assertEquals("1.3.1", h.appVersion)
        assertEquals(EMERALD_COOP_SHA1, h.romSha1)
        assertEquals(save, h.save)
        assertEquals(0x1234, h.save.trainerId)
        assertEquals(0xABCD, h.save.secretId)
        assertArrayEquals(name, h.save.name)
        assertTrue(h.female)
        assertEquals(ticket, h.ticket)
    }

    @Test fun helloLayout() {
        val b = hello(ticket = null).encode()
        // u16 protocol, u8 length + "1.3.1", 20 SHA-1 bytes, u16 + u16, 8 name bytes, u8 female, u16 ticket length.
        assertEquals(2 + 1 + 5 + 20 + 2 + 2 + 8 + 1 + 2, b.size)
        assertArrayEquals(byteArrayOf(0, 1, 5, '1'.code.toByte()), b.copyOfRange(0, 4))
        assertEquals(0xF3.toByte(), b[8])
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0xAB.toByte(), 0xCD.toByte()), b.copyOfRange(28, 32))
        assertArrayEquals(name, b.copyOfRange(32, 40))
        assertArrayEquals(byteArrayOf(1, 0, 0), b.copyOfRange(40, 43))
        assertNull(CoopHello.decode(b)!!.ticket)
    }

    @Test fun helloRejects() {
        val good = hello().encode()
        assertNotNull(CoopHello.decode(good))
        assertNotNull(CoopHello.decode(rebuild()))
        assertNull(CoopHello.decode(good.copyOf(good.size - 1)))
        assertNull(CoopHello.decode(good + byteArrayOf(0)))
        assertNull(CoopHello.decode(byteArrayOf()))
        // appVersion length 33 (the bytes are there, the length is out of range).
        assertNull(CoopHello.decode(rebuild(version = "1".repeat(33))))
        assertNull(CoopHello.decode(rebuild(version = "")))
        assertNull(CoopHello.decode(rebuild(version = "1.3.1\n")))
        assertNull(CoopHello.decode(rebuild(version = "1.3 1")))
        // Tickets outside our form (length 206, uppercase, non-ASCII).
        assertNull(CoopHello.decode(rebuild(ticket = "pdco" + "a".repeat(206))))
        assertNull(CoopHello.decode(rebuild(ticket = "pdco" + "A".repeat(60))))
        assertNull(CoopHello.decode(rebuild(ticket = "pdco" + "é".repeat(60))))
        assertNull(CoopHello.decode(rebuild(female = 2)))
        assertNotNull(CoopHello.decode(rebuild(female = 0)))
        assertNotNull(CoopHello.decode(rebuild(ticket = "pdco" + "a".repeat(205))))
    }

    @Test fun helloRefusesToEncodeBadFields() {
        assertThrows(IllegalArgumentException::class.java) { CoopHello(0x10000, "1.3.1", EMERALD_COOP_SHA1, save, false, null) }
        assertThrows(IllegalArgumentException::class.java) { CoopHello(1, "1.3.1", "F3AE", save, false, null) }
        assertThrows(IllegalArgumentException::class.java) { CoopHello(1, "1.3.1", EMERALD_COOP_SHA1.uppercase(), save, false, null) }
        assertThrows(IllegalArgumentException::class.java) { CoopHello(1, "1.3.1!", EMERALD_COOP_SHA1, save, false, null) }
        assertThrows(IllegalArgumentException::class.java) { CoopHello(1, "1.3.1", EMERALD_COOP_SHA1, save, false, "nope") }
        assertThrows(IllegalArgumentException::class.java) { CoopSaveId(0x10000, 0, name) }
        assertThrows(IllegalArgumentException::class.java) { CoopSaveId(0, 0, ByteArray(7)) }
    }

    @Test fun saveIdsCompareByKey() {
        val a = CoopSaveId(0x1234, 0xABCD, name)
        val b = CoopSaveId(0x1234, 0xABCD, name.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals("1234abcd-bbc6c9c8ff000000", a.key)
        assertTrue(a != CoopSaveId(0x1234, 0xABCE, name))
    }

    @Test fun peerIds() {
        val bytes = ByteArray(32) { it.toByte() }
        val p = CoopPeerId.of(bytes)
        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", p.hex)
        assertArrayEquals(bytes, p.bytes())
        assertThrows(IllegalArgumentException::class.java) { CoopPeerId.of(ByteArray(31)) }
    }

    /** A HELLO laid out by hand, so the decoder sees values the encoder would refuse. */
    private fun rebuild(version: String = "1.3.1", ticket: String = this.ticket, female: Int = 1): ByteArray {
        val v = version.toByteArray(Charsets.UTF_8)
        val t = ticket.toByteArray(Charsets.UTF_8)
        val sha = ByteArray(20) { EMERALD_COOP_SHA1.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return byteArrayOf(0, 1, v.size.toByte()) + v + sha +
            byteArrayOf(0x12, 0x34, 0xAB.toByte(), 0xCD.toByte()) + name + byteArrayOf(female.toByte()) +
            byteArrayOf((t.size shr 8).toByte(), t.size.toByte()) + t
    }
}
