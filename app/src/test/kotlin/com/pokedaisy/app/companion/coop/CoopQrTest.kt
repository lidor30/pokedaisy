package com.pokedaisy.app.companion.coop

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopQrTest {
    private val link = CoopInvite.link("pdco" + "c".repeat(200))

    @Test fun encodesAnInviteLink() {
        val g = CoopQr.encode(link)
        assertTrue(g.size in 41..73)
        assertTrue(g.all { it.size == g.size })
        assertTrue(g[0][0] && g[0][6] && g[6][0]) // finder pattern corners
    }

    @Test fun decodesBack() = assertEquals(link, decode(CoopQr.encode(link)))

    @Test fun everySizeDecodesBack() {
        // Lengths at the edges of versions 1, 2, 10, 11 and up to 40 (level M's byte capacities: 14, 26, 213, 251, 2331).
        for (len in listOf(1, 14, 15, 26, 27, 213, 214, 251, 252, 600, 1200, 2331)) {
            val text = (0 until len).map { "abcdefghijklmnopqrstuvwxyz234567:/.?#"[(it * 7 + len) % 37] }.joinToString("")
            val g = CoopQr.encode(text)
            assertEquals("length $len", text, decode(g))
        }
    }

    private fun decode(g: Array<BooleanArray>): String {
        val scale = 4
        val quiet = 4
        val side = (g.size + quiet * 2) * scale
        val px = IntArray(side * side) { 0xFFFFFF }
        for (y in g.indices) for (x in g.indices) if (g[y][x]) {
            for (dy in 0 until scale) for (dx in 0 until scale) {
                px[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0x000000
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, px)))
        return QRCodeReader().decode(bitmap).text
    }
}
