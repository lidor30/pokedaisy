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
    fun `balls - the thrown ball in each kind's colours, the same in Red, Blue and Yellow`() {
        val dir = rom.parentFile
        val carts = listOf(
            "Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb",
            "Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb",
            "Pokemon - Blue Version (USA, Europe) (SGB Enhanced).gb",
            "Pokemon - Gelbe Edition - Special Pikachu Edition (Germany) (CGB+SGB Enhanced).gb",
            "Pokemon - Rote Edition (Germany) (SGB Enhanced).gb",
            "Pokemon - Blaue Edition (Germany) (SGB Enhanced).gb",
        ).map { File(dir, it) }.filter { it.isFile }
        assumeTrue("no Gen 1 ROM on this machine", carts.isNotEmpty())
        val first = Gen1Art.balls(carts[0].readBytes())
        // MASTER, ULTRA, GREAT, POKé, SAFARI BALL.
        assertEquals(setOf(0x01, 0x02, 0x03, 0x04, 0x08), first.keys)
        // POKé BALL: black, red, pink - Crystal's.
        assertEquals(setOf(0xFF000000.toInt(), 0xFFF75231.toInt(), 0xFFFF9CC6.toInt()), first.getValue(0x04).argb.filter { it != 0 }.toSet())
        assertTrue(first.getValue(0x08).argb.filter { it != 0 }.toSet().size >= 4)   // the camouflage
        for (f in carts.drop(1)) {
            val balls = Gen1Art.balls(f.readBytes())
            for ((id, img) in first) assertTrue("${f.name} ball $id", img.argb.contentEquals(balls.getValue(id).argb))
        }
        val out = File("build/gen1-art/balls").also { it.mkdirs() }
        for ((id, img) in first) File(out, "ball-$id.png").writeBytes(img.png())
    }

    @Test
    fun `another cart gets nothing`() {
        assertTrue(Gen1Art.extractTo(ByteArray(0x8000), File.createTempFile("gen1", "").also { it.delete() }.toOkioPath()).isEmpty())
    }
}
