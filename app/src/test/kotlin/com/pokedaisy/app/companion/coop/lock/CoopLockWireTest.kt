package com.pokedaisy.app.companion.coop.lock

import org.junit.Assert.*
import org.junit.Test

class CoopLockWireTest {
    @Test fun roundTripsEach() { listOf(LockMsg.Propose(0), LockMsg.Answer(true, 0), LockMsg.Off, LockMsg.Status(true), LockMsg.Hold(7))
        .forEach { m -> val (t, b) = CoopLockWire.encode(m); assertEquals(m, CoopLockWire.decode(t, b) { true }) } }
    @Test fun refusesWrongVersionLengthOrType() {
        val (t, b) = CoopLockWire.encode(LockMsg.Answer(true, 2))
        assertNull(CoopLockWire.decode(t, byteArrayOf(2, b[1], b[2])) { true })
        assertNull(CoopLockWire.decode(t, b.copyOf(b.size - 1)) { true })
        assertNull(CoopLockWire.decode(t, b + 0) { true })
        assertNull(CoopLockWire.decode(0x0305, b) { true })
    }
    @Test fun refusesUnknownId() { val (t, b) = CoopLockWire.encode(LockMsg.Hold(9)); assertNull(CoopLockWire.decode(t, b) { it == 7 })
        assertEquals(LockMsg.Hold(0), CoopLockWire.decode(t, CoopLockWire.encode(LockMsg.Hold(0)).second) { false }) }
    @Test fun refusesFlagsOutOfRange() {
        assertNull(CoopLockWire.decode(CoopLockWire.ANSWER, byteArrayOf(1, 2, 0)) { true })
        assertNull(CoopLockWire.decode(CoopLockWire.STATUS, byteArrayOf(1, 2)) { true })
    }
}
