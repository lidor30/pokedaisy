package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PartySwapTest {
    private class Ram(val bytes: ByteArray, val failWrites: Boolean = false) : GameCalls {
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
        override fun read(addr: Long, len: Int) = bytes.copyOfRange(addr.toInt(), addr.toInt() + len)
        override fun write(addr: Long, bytes: ByteArray): Boolean {
            if (failWrites && addr != 0L) return false
            bytes.copyInto(this.bytes, addr.toInt())
            return true
        }
    }

    private fun party() = ByteArray(12) { it.toByte() }

    @Test fun tradesTwoRecordsWhole() {
        val ram = Ram(party())
        assertEquals(PartySwapResult.SWAPPED, swapPartySlots(ram, 0, 4, 3, 0, 2))
        assertArrayEquals(byteArrayOf(8, 9, 10, 11, 4, 5, 6, 7, 0, 1, 2, 3), ram.bytes)
    }

    @Test fun refusesASlotOutsideTheParty() {
        val ram = Ram(party())
        assertEquals(PartySwapResult.FAILED, swapPartySlots(ram, 0, 4, 2, 0, 2))
        assertArrayEquals(party(), ram.bytes)
    }

    @Test fun putsThePartyBackWhenAWriteFails() {
        val ram = Ram(party(), failWrites = true)
        assertEquals(PartySwapResult.FAILED, swapPartySlots(ram, 0, 4, 3, 0, 2))
        assertArrayEquals(party(), ram.bytes)
    }
}
