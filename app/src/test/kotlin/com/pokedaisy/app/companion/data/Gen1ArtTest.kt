package com.pokedaisy.app.companion.data

import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The Gen 1 font cut from the user's Yellow ROM is a TrueType file Java's own parser accepts. */
class Gen1ArtTest {
    private val rom = File(File(RETAIL_ROM_DIR).parentFile, "gbc/Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb")

    @Test
    fun `Yellow's font parses, with its glyphs`() {
        assumeTrue("Yellow ROM not on this machine", rom.isFile)
        val ttf = Gen1Art.font(rom.readBytes(), Gen1Art.YELLOW)
        // java.awt is on the test JVM, not on the Android compile classpath: reflection.
        val fontClass = Class.forName("java.awt.Font")
        val font = fontClass.getMethod("createFont", Int::class.java, java.io.InputStream::class.java)
            .invoke(null, 0 /* TRUETYPE_FONT */, ttf.inputStream())
        assertEquals("Gen1 Pokemon", fontClass.getMethod("getFamily").invoke(font))
        val canDisplay = fontClass.getMethod("canDisplay", Char::class.java)
        for (c in "PIKACHU Lv100 ♂♀ 182/182 ¥999,999") assertTrue("glyph $c", canDisplay.invoke(font, c) as Boolean)
        val sized = fontClass.getMethod("deriveFont", Float::class.java).invoke(font, 16f)
        val img = Class.forName("java.awt.image.BufferedImage").getConstructor(Int::class.java, Int::class.java, Int::class.java).newInstance(1, 1, 2)
        val g = img.javaClass.getMethod("createGraphics").invoke(img)
        val fm = Class.forName("java.awt.Graphics").getMethod("getFontMetrics", fontClass).invoke(g, sized)
        val charWidth = { c: Char -> Class.forName("java.awt.FontMetrics").getMethod("charWidth", Char::class.java).invoke(fm, c) as Int }
        assertEquals(8, charWidth('A'))   // 8 px cells at 16 px per em (100 units = 1 px)
    }

    @Test
    fun `another cart gets nothing`() {
        assertTrue(Gen1Art.extractTo(ByteArray(0x8000), File.createTempFile("gen1", "").also { it.delete() }.toOkioPath()).isEmpty())
    }
}
