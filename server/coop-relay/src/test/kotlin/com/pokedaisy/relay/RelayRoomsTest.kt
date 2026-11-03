package com.pokedaisy.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRoomsTest {
    private var now = 1_000_000L
    private val room = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"

    private fun rooms(config: RelayConfig = RelayConfig()) = RelayRooms(config) { now }

    @Test
    fun secondJoinPairsAndThirdIsRefused() {
        val r = rooms()
        assertEquals(RelayRooms.Join.Ok(null), r.join(1, "1.1.1.1", room))
        assertEquals(RelayRooms.Join.Ok(1), r.join(2, "2.2.2.2", room))
        assertEquals(RelayRooms.Join.Refused(4409), r.join(3, "3.3.3.3", room))
        assertEquals(2L, r.peerOf(1))
        assertEquals(1L, r.peerOf(2))
        assertNull(r.peerOf(3))
    }

    @Test
    fun leaveTellsTheOther() {
        val r = rooms()
        r.join(1, "1.1.1.1", room)
        r.join(2, "2.2.2.2", room)
        assertEquals(1L, r.leave(2))
        assertNull(r.peerOf(1))
        assertNull(r.leave(1))
        assertEquals(0, r.roomCount)
        assertEquals(0, r.socketCount)
        assertNull(r.leave(99))
    }

    @Test
    fun maxRoomsRefusesNewRooms() {
        val r = rooms(RelayConfig(maxRooms = 1))
        r.join(1, "1.1.1.1", room)
        assertEquals(RelayRooms.Join.Refused(4429), r.join(2, "2.2.2.2", other))
        // joining the existing room is still fine
        assertEquals(RelayRooms.Join.Ok(1), r.join(3, "3.3.3.3", room))
    }

    @Test
    fun perIpSocketLimit() {
        val r = rooms(RelayConfig(maxSocketsPerIp = 2))
        r.join(1, "1.1.1.1", room)
        r.join(2, "1.1.1.1", other)
        assertFalse(r.canAdmit("1.1.1.1"))
        assertTrue(r.canAdmit("9.9.9.9"))
        assertEquals(RelayRooms.Join.Refused(4429), r.join(3, "1.1.1.1", "a".repeat(32)))
        r.leave(1)
        assertTrue(r.canAdmit("1.1.1.1"))
    }

    @Test
    fun perIpNewSocketRate() {
        val r = rooms(RelayConfig(maxSocketsPerIp = 1000))
        for (i in 1L..30L) {
            assertTrue(r.join(i, "1.1.1.1", "%032x".format(i)) is RelayRooms.Join.Ok)
            r.leave(i)
        }
        assertFalse(r.canAdmit("1.1.1.1"))
        assertEquals(RelayRooms.Join.Refused(4429), r.join(31, "1.1.1.1", room))
        assertTrue(r.canAdmit("2.2.2.2"))
        now += 60_001
        assertTrue(r.canAdmit("1.1.1.1"))
        assertTrue(r.join(32, "1.1.1.1", room) is RelayRooms.Join.Ok)
    }

    @Test
    fun tokenBucketsMessagesAndBytes() {
        val r = rooms(RelayConfig(maxMessagesPerSec = 5, maxBytesPerSec = 1000))
        r.join(1, "1.1.1.1", room)
        repeat(5) { assertTrue(r.allow(1, 10)) }
        assertFalse(r.allow(1, 10))
        now += 1000
        assertTrue(r.allow(1, 10))
        // bytes: 1000 a second, one second of burst
        now += 1000
        assertTrue(r.allow(1, 600))
        assertFalse(r.allow(1, 600))
        now += 500
        assertTrue(r.allow(1, 600)) // 400 left + 500 refilled
        // buckets are per socket
        r.join(2, "2.2.2.2", room)
        assertTrue(r.allow(2, 1000))
    }

    @Test
    fun loneAndSilentExpire() {
        val r = rooms(RelayConfig(loneTimeoutSec = 10, silentTimeoutSec = 4))
        r.join(1, "1.1.1.1", room)
        r.join(2, "2.2.2.2", other)
        r.join(3, "3.3.3.3", other)
        now += 3_000
        assertTrue(r.expired().isEmpty())
        r.touch(2)
        now += 3_000 // pair silent 3 s since the touch
        assertTrue(r.expired().isEmpty())
        now += 2_000 // 5 s
        assertEquals(setOf(2L, 3L), r.expired().toSet())
        now += 5_000 // the lone peer is at 10 s
        assertEquals(setOf(1L, 2L, 3L), r.expired().toSet())
    }

    @Test
    fun roomIdMustBe32LowerHex() {
        assertTrue(RelayRooms.isRoomId(room))
        assertFalse(RelayRooms.isRoomId("XYZ"))
        assertFalse(RelayRooms.isRoomId("ABCDEF0123456789ABCDEF0123456789"))
        assertFalse(RelayRooms.isRoomId(room + "0"))
        assertFalse(RelayRooms.isRoomId(room.dropLast(1)))
        assertFalse(RelayRooms.isRoomId("g" + room.drop(1)))
    }

    @Test
    fun ipIsMaskedForLogs() {
        assertEquals("203.0.113.0/24", RelayRooms.maskIp("203.0.113.77"))
        assertEquals("2001:db8:1::/48", RelayRooms.maskIp("2001:0db8:0001:abcd:1:2:3:4"))
        assertEquals("2001:db8:1::/48", RelayRooms.maskIp("2001:db8:1:abcd::7"))
        assertEquals("unknown", RelayRooms.maskIp("not an ip"))
    }
}

class OverBufferTest {
    private fun q(vararg sizes: Int) = sizes.map { java.nio.ByteBuffer.allocate(it) }

    @Test
    fun aStalledReaderIsOverTheCap() {
        assertFalse(overBuffer(q(), 100))
        assertFalse(overBuffer(q(50, 50), 100))
        assertTrue(overBuffer(q(50, 51), 100))
        assertTrue(overBuffer(q(1000), 100))
    }
}
