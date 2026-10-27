package com.pokedaisy.app.companion.data.coop

import com.pokedaisy.app.companion.data.LookChar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The co-op presence message ([PresenceCodec]): 20 bytes, and anything outside the documented ranges is dropped whole. */
class PresenceCodecTest {
    private fun sample() = PresenceUpdate(123456L, true, false, 0, 16, 7 * 16 + 5, 12 * 16, 1, PresenceMode.WALK, 3, 0)

    @Test fun roundTrips() {
        val u = PresenceUpdate(123456L, true, true, 0, 16, 7 * 16 + 5, 12 * 16, 4, PresenceMode.MACH, 9, 4)
        assertEquals(u, PresenceCodec.decode(PresenceCodec.encode(u)))
        assertEquals(20, PresenceCodec.encode(u).size)
    }

    @Test fun roundTripsTheEdges() {
        val u = PresenceUpdate(0xFFFFFFFFL, false, false, 255, 255, 32767, 0, 2, PresenceMode.SURF, 255, 8)
        assertEquals(u, PresenceCodec.decode(PresenceCodec.encode(u)))
    }

    @Test fun refusesWrongVersionOrSize() {
        val b = PresenceCodec.encode(sample())
        b[0] = 2
        assertNull(PresenceCodec.decode(b))
        assertNull(PresenceCodec.decode(ByteArray(19)))
        assertNull(PresenceCodec.decode(ByteArray(21)))
    }

    @Test fun refusesOutOfRange() {
        assertNotNull(PresenceCodec.decode(PresenceCodec.encode(sample())))
        fun changed(at: Int, value: Int) = PresenceCodec.encode(sample()).also { it[at] = value.toByte() }
        assertNull("facing 0", PresenceCodec.decode(changed(8, 0)))
        assertNull("facing 5", PresenceCodec.decode(changed(8, 5)))
        assertNull("mode 5", PresenceCodec.decode(changed(9, 5)))
        assertNull("flags 0x04", PresenceCodec.decode(changed(1, 0x04)))
        assertNull("reserved byte 16", PresenceCodec.decode(changed(16, 1)))
        assertNull("lookId 9", PresenceCodec.decode(changed(11, 9)))
        assertNull("fineX 0x8000", PresenceCodec.decode(changed(4, 0x00).also { it[5] = 0x80.toByte() }))
    }

    @Test fun unknownLookIsOwnHero() {
        assertNull(PresenceLook.charOf(9))
        assertNull(PresenceLook.charOf(0))
        assertEquals(LookChar.MAY, PresenceLook.charOf(4))
    }

    @Test fun lookIdsArePinned() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8),
            listOf(LookChar.RED, LookChar.LEAF, LookChar.BRENDAN, LookChar.MAY, LookChar.RS_BRENDAN, LookChar.RS_MAY,
                LookChar.GOLD, LookChar.KRIS).map(PresenceLook::idOf))
        assertEquals(0, PresenceLook.idOf(null))
    }
}
