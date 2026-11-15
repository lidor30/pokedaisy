package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Prism's art, rebuilt from the player's own ROM (skipped without it): its party icons in the colours the party menu
 * showed headless, its six town maps (the Naljo one matched against the map screen's own pixels, pixel for pixel past
 * the sprites), the held-item marks and the font. The art lands in app/build/gen2-art-prism to look at.
 */
class PrismArtTest {
    private val rom = File(File(RETAIL_ROM_DIR).parentFile!!, "gbc/Pokemon Prism (v0.95 build 254 Hotfix 5).gbc")

    private fun rom(): ByteArray {
        assumeTrue("Prism ROM not on this machine", rom.isFile)
        return rom.readBytes()
    }

    private fun argb(rgb: Int) = (0xFF000000 or rgb.toLong()).toInt()

    @Test
    fun `everything lands on its own paths`() {
        val out = File("build/gen2-art-prism")
        out.deleteRecursively()
        val wrote = Gen2Art.extractTo(rom(), out.toOkioPath())
        assertEquals(254 + 1 + 1 + 6, wrote.size)   // 254 icons (EGG's is 253), the font, the held-item marks, six maps
        assertTrue(wrote.all { it.startsWith("gen2/prism") || it.startsWith("regionmap/prism_") })
        assertTrue("the same build again: nothing", Gen2Art.extractTo(rom(), out.toOkioPath()).isEmpty())
    }

    @Test
    fun `party icons - by species id, in the OBJ palette each one's byte picks`() {
        val icons = Gen2Art.icons(rom(), Gen2Art.PRISM)
        assertEquals(254, icons.size)
        // Sampled from the party menu headless: an orange shade, the species' colour, black outlines.
        val orange = argb(0xFFAD4A)
        val black = argb(0x000000)
        assertEquals(setOf(orange, argb(0x39BD18), black), icons.getValue(246).argb.filter { it != 0 }.toSet())   // LARVITAR green
        assertEquals(setOf(orange, argb(0x4A21FF), black), icons.getValue(27).argb.filter { it != 0 }.toSet())    // SHINX blue
        assertTrue(icons.getValue(Gen2Art.EGG).argb.count { it != 0 } > 50)
    }

    @Test
    fun `town maps - six regions, Naljo as the map screen draws it`() {
        for (i in 0 until 6) assertNotNull("map $i", Gen2Art.townMap(rom(), Gen2Art.PRISM, i))
        val naljo = Gen2Art.townMap(rom(), Gen2Art.PRISM, 0)!!
        // Pixels of the map screen, headless (it matched it everywhere but under its sprites and the blinking dots):
        // the sea, two town boxes, a mountain.
        for ((xy, rgb) in listOf((4 to 12) to 0x184AEF, (60 to 60) to 0xCE634A, (44 to 44) to 0xCE634A,
            (100 to 40) to 0xC69439, (150 to 100) to 0x184AEF)) {
            assertEquals("$xy", argb(rgb), naljo.argb[xy.second * 160 + xy.first])
        }
        assertEquals(12, naljo.argb.toSet().size)
    }

    @Test
    fun `the font parses`() {
        val ttf = Gen2Art.font(rom(), Gen2Art.PRISM)
        val fontClass = Class.forName("java.awt.Font")
        val font = fontClass.getMethod("createFont", Int::class.java, java.io.InputStream::class.java)
            .invoke(null, 0 /* TRUETYPE_FONT */, ttf.inputStream())
        val canDisplay = fontClass.getMethod("canDisplay", Char::class.java)
        for (c in "Larvitar Lv5 ♂♀ 19/21 Poké ¥3000") assertTrue("glyph $c", canDisplay.invoke(font, c) as Boolean)
    }
}
