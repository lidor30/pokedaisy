package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The art cache reads back the PNGs it writes (Gen4Dex's front pictures), pixel for pixel. */
class RomArtPngTest {
    @Test fun `a written PNG reads back the same`() {
        val img = RomArt.Image(5, 3, IntArray(15) { i -> if (i % 4 == 0) 0 else (0xFF shl 24) or (i * 0x0B1D27) })
        val back = RomArt.Image.decodePng(img.png())!!
        assertEquals(5, back.width)
        assertEquals(3, back.height)
        assertArrayEquals(img.argb, back.argb)
    }

    @Test fun `anything else reads as nothing`() {
        assertNull(RomArt.Image.decodePng(ByteArray(0)))
        assertNull(RomArt.Image.decodePng("not a png at all".encodeToByteArray()))
    }
}
