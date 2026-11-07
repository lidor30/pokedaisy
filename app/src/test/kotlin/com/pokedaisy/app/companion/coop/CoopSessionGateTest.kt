package com.pokedaisy.app.companion.coop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [CoopSession.gate]: why co-op stays off, in the order the player should fix things. */
class CoopSessionGateTest {
    private val id = CoopSaveId(1, 2, byteArrayOf(0xBB.toByte(), 0xBC.toByte(), 0xBD.toByte(), 0xFF.toByte(), 0, 0, 0, 0))

    @Test fun gateOrder() {
        assertEquals(CoopOffReason.NOT_EMERALD, CoopSession.gate("00", id, true, false))
        assertEquals(CoopOffReason.NOT_EMERALD, CoopSession.gate(null, null, false, true))
        assertEquals(CoopOffReason.NO_SAVE, CoopSession.gate(EMERALD_COOP_SHA1, null, false, false))
        assertEquals(CoopOffReason.NOT_PAIRED, CoopSession.gate(EMERALD_COOP_SHA1, id, false, false))
        assertEquals(CoopOffReason.CHEATS_ON, CoopSession.gate(EMERALD_COOP_SHA1, id, true, true))
        assertNull(CoopSession.gate(EMERALD_COOP_SHA1, id, true, false))
    }
}

class CoopMayBeEmeraldTest {
    @org.junit.Test fun onlyAnEmeraldCodeAtMostSixteenMegabytesIsHashed() {
        org.junit.Assert.assertTrue(CoopSession.mayBeRetailEmerald("BPEE", 0x1000000L))
        org.junit.Assert.assertTrue(CoopSession.mayBeRetailEmerald("BPEE", 0xFFF000L))
        org.junit.Assert.assertFalse(CoopSession.mayBeRetailEmerald("BPEE", 0x2000000L))
        org.junit.Assert.assertFalse(CoopSession.mayBeRetailEmerald("BPRE", 0x1000000L))
        org.junit.Assert.assertFalse(CoopSession.mayBeRetailEmerald("", 512L shl 20))
    }
}

class CoopCheatsMidSessionTest {
    @org.junit.Test fun cheatsCannotBeSwitchedOnWhileCoopRuns() {
        org.junit.Assert.assertTrue(CoopSession.refusesCheats(turningOn = true, coopOn = true))
        org.junit.Assert.assertFalse("turning them off is always fine", CoopSession.refusesCheats(turningOn = false, coopOn = true))
        org.junit.Assert.assertFalse("no co-op, no rule", CoopSession.refusesCheats(turningOn = true, coopOn = false))
    }
}

class CoopCheatGateTest {
    @org.junit.Test fun whatReachesTheEngine() {
        org.junit.Assert.assertEquals("A", CoopSession.cheatsForEngine(true, false) { "A" })
        org.junit.Assert.assertEquals("", CoopSession.cheatsForEngine(true, true) { "A" })
        org.junit.Assert.assertEquals("", CoopSession.cheatsForEngine(false, false) { "A" })
        org.junit.Assert.assertEquals("", CoopSession.cheatsForEngine(false, true) { error("never loaded") })
        org.junit.Assert.assertEquals("", CoopSession.cheatsForEngine(true, true) { error("never loaded") })
    }
}
