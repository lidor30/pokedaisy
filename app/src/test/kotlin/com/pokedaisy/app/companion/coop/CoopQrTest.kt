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

    @Test fun decodesBack() {
        val g = CoopQr.encode(link)
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
        assertEquals(link, QRCodeReader().decode(bitmap).text)
    }
}
