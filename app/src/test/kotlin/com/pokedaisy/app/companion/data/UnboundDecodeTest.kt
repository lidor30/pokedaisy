package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnboundDecodeTest {
    @Test
    fun `party populated, items span multiple pockets`() {
        val t = decodeNative("unbound", NATIVE_UNBOUND)
        assertTrue(t.party.isNotEmpty())
        t.party.forEach { assertTrue(it.species > 0) }
        assertPocketsInRange(t)
        val pockets = t.items.map { it.pocket }.toSet()
        assertTrue("expected items in >1 pocket, got $pockets", pockets.size > 1)
    }

    @Test
    fun `native gender matches the game's own party screen`() {
        // PID + genderRatiosUnbound (the ROM's live gBaseStats); the game's
        // party menu for this save shows the Gible as female (headless
        // mgba_dump `shot`, 2026-09-27).
        activeGame = GameKind.UNBOUND
        val t = decodeNative("unbound", NATIVE_UNBOUND)
        assertEquals(GENDER_SYMBOL_FEMALE, t.party[0].genderSymbol)
    }

    @Test
    fun `native read shows an egg as an egg`() {
        // Same save with party[0]'s BoxPokemon +0x13 isEgg bit set - the headless check of the game drew such a mon as
        // the "Egg" box with the ROM's icon 412; the hatch species stays in the reading for types and IVs.
        activeGame = GameKind.UNBOUND
        val fixture = FixtureMemoryReader.load("unbound")
        val flag = NATIVE_UNBOUND.playerParty + 0x13
        val reader = object : MemoryReader by fixture {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray {
                val b = fixture.readCoreMemory(addr, size)
                if (flag >= addr && flag < addr + size) {
                    val i = (flag - addr).toInt()
                    b[i] = (b[i].toInt() or 0x04).toByte()
                }
                return b
            }
        }
        val t = readNativeTelemetry(reader, NATIVE_UNBOUND)
        assertTrue(t.party[0].isEgg)
        assertEquals(GENDER_SYMBOL_NONE, t.party[0].genderSymbol)
    }
}
