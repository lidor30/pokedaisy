package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SMART fast-forward's shop exception ([ShopWatch], GitHub #41), on headless captures of a Poké Mart clerk. */
class ShopWatchTest {
    private fun iwram(name: String) = javaClass.classLoader!!.getResourceAsStream("fixtures/ff_shop/$name.bin")!!.readBytes()

    /** An IWRAM capture plus the few ROM bytes the watch reads: the clerk's `pokemart` command. */
    private class Capture(private val iwram: ByteArray, private val rom: Map<Long, ByteArray>) : MemoryReader {
        override fun readCoreMemory(addr: Long, size: Int): ByteArray {
            if (addr in 0x03000000L until 0x03008000L) {
                val o = (addr - 0x03000000L).toInt()
                return iwram.copyOfRange(o, o + size)
            }
            val (at, bytes) = rom.entries.first { (at, b) -> addr >= at && addr + size <= at + b.size }
            return bytes.copyOfRange((addr - at).toInt(), (addr - at).toInt() + size)
        }
    }

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Oldale Town's clerk in retail Emerald, Two Island's in FireRed rev 1: `pokemart <items>`.
    private val emeraldRom = mapOf(0x081FC26CL to bytes("867cc21f08"))
    private val fireRedRom = mapOf(0x08164A20L to bytes("86304a1608"))

    private fun open(game: String, state: String, rom: Map<Long, ByteArray>): Boolean {
        ShopWatch.load()
        return ShopWatch.isOpen(Capture(iwram("${game}_$state"), rom))
    }

    @Test fun findsTheGlobalAndImmediateContexts() {
        assertArrayEquals(longArrayOf(0x03000E40L, 0x03000EB8L), ShopWatch.find(iwram("emerald_buy")))
        assertArrayEquals(longArrayOf(0x03000EB0L, 0x03000F28L), ShopWatch.find(iwram("firered_buy")))
    }

    @Test fun theMartsMenuBuyScreenAndSellBagAreAShop() {
        for (state in listOf("menu", "buy", "sell")) {
            assertTrue("emerald $state", open("emerald", state, emeraldRom))
            assertTrue("firered $state", open("firered", state, fireRedRom))
        }
    }

    @Test fun notOnceTheClerkIsDone() {
        assertFalse(open("emerald", "after", emeraldRom))
        assertFalse(open("firered", "after", fireRedRom))
    }
}
