package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** TextFormat.kt's helpers print what the String.format calls they replaced printed (in a '.' locale). */
class TextFormatTest {
    private fun f(fmt: String, vararg a: Any?) = String.format(Locale.ROOT, fmt, *a)

    @Test fun hexMatchesPercentX() {
        for (v in listOf(0, 1, 0x7F, 0xFF, 0x1234, 0x08000000, Int.MAX_VALUE, -1, Int.MIN_VALUE)) {
            assertEquals(f("%x", v), v.hex())
            assertEquals(f("%02x", v), v.hex(2))
            assertEquals(f("%08X", v), v.hex(8, upper = true))
        }
        for (v in listOf(0L, 5L, 0x08ABCDEFL, 0xFFFFFFFFL, Long.MAX_VALUE, -1L))
            assertEquals(f("%07x", v), v.hex(7))
        val b = byteArrayOf(0, 1, 0x7F, -128, -1, 0x2A)
        assertEquals(b.joinToString("") { f("%02x", it) }, b.hex())
        assertEquals(b.joinToString("") { f("%02X", it.toInt() and 0xFF) }, b.hex(upper = true))
    }

    @Test fun padMatchesPercentD() {
        for (v in listOf(0, 3, 9, 10, 42, 99, 100, 12345, -3, -42)) assertEquals(f("%02d", v), v.pad(2))
        for (v in listOf(0L, 7L, 123L)) assertEquals(f("%02d", v), v.pad(2))
    }

    @Test fun fixedMatchesPercentF() {
        for (v in listOf(0.0, 0.5, 0.125, 0.994, 0.995, 0.9951, 1.0, 12.3456, 0.333333, 2.0 / 3)) assertEquals("$v", f("%.2f", v), v.fixed(2))
    }

    @Test fun multiplierMatchesPercentG() {
        for (pct in listOf(6, 12, 33, 66, 75, 125, 150, 300, 600, 800, 1600, 1250)) assertEquals("$pct", f("%.2gx", pct / 100.0), formatMultiplier(pct))
    }
}
