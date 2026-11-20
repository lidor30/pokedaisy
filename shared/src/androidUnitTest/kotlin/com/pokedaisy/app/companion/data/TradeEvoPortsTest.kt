package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TRADE EVOLUTIONS' calls by game ([tradeEvoFor]): every retail FireRed / LeafGreen / Emerald, never a hack; and each
 * cart's table matches its own code (skips a ROM that isn't here). */
class TradeEvoPortsTest {
    @Test
    fun `every retail release, no hack`() {
        assertEquals(TRADE_EVO_FIRERED_REV1, tradeEvoFor(GameKind.FIRERED, "BPRE", 1, hack = false))
        assertNotNull(tradeEvoFor(GameKind.FIRERED, "BPRE", 0, hack = false))
        for (key in listOf("BPRD0", "BPRF0", "BPRI0", "BPRS0", "BPRJ0", "BPRJ1", "BPGD0", "BPGF0", "BPGI0", "BPGS0", "BPGJ0", "BPGJ1"))
            assertNotNull(key, tradeEvoFor(GameKind.FIRERED, key.dropLast(1), key.last() - '0', hack = false))
        for (key in listOf("BPED0", "BPEF0", "BPEI0", "BPES0", "BPEJ0"))
            assertNotNull(key, tradeEvoFor(GameKind.EMERALD, key.dropLast(1), 0, hack = false))
        assertNull(tradeEvoFor(GameKind.FIRERED, "BPRE", 0, hack = true))
        // FireRed under an Emerald header (Clover): no Emerald table for it.
        assertNull(tradeEvoFor(GameKind.FIRERED, "BPEE", 0, hack = false))
    }

    @Test
    fun `each table's code is its cart's`() {
        var n = 0
        retailRomFiles().forEach { rom ->
            val bytes = rom.readBytes()
            val code = String(bytes, 0xAC, 4, Charsets.ISO_8859_1)
            val rev = bytes[0xBC].toInt() and 0xFF
            val kind = when { code.startsWith("BPR") || code.startsWith("BPG") -> GameKind.FIRERED; code.startsWith("BPE") -> GameKind.EMERALD; else -> return@forEach }
            val evo = tradeEvoFor(kind, code, rev, hack = false) ?: return@forEach
            assertTrue("${rom.name} ($code$rev)", tradeEvoCodeMatches(evo) { a, len -> bytes.copyOfRange((a - 0x08000000L).toInt(), (a - 0x08000000L).toInt() + len) })
            n++
        }
        if (retailRomFiles().any()) assertTrue("only $n carts", n >= 20)
    }
}
