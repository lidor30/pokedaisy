package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Crystal Clear's art and dex pages, rebuilt from the player's own ROMs (skipped without them), at each version's
 * addresses: the two versions give the same art, the Color Filter builds other colours on the same paths, and the
 * pixels match headless screenshots (the party menu's icon colours, the POKéGEAR map's). The art lands in
 * app/build/gen2-art-crystal-clear to look at.
 */
class CrystalClearArtTest {
    private val dir = File(RETAIL_ROM_DIR).parentFile!!.let { File(it, "gbc") }
    private val v260 = File(dir, "Pokemon Crystal Clear (v2.6.0).gbc")
    private val v260cf = File(dir, "Pokemon Crystal Clear (v2.6.0) (Color Filter).gbc")
    private val v2511 = File(dir, "Pokemon Crystal Clear (v2.5.11).gbc")
    private val v2511cf = File(dir, "Pokemon Crystal Clear (v2.5.11) (Color Filter).gbc")

    private fun rom(f: File): ByteArray {
        assumeTrue("Crystal Clear ROM not on this machine", f.isFile)
        return f.readBytes()
    }

    @Test
    fun `both versions give the same art, the colour builds the same paths`() {
        val out = File("build/gen2-art-crystal-clear")
        out.deleteRecursively()
        val wrote = Gen2Art.extractTo(rom(v260), out.toOkioPath())
        assertEquals(252 + 1 + 1 + 2, wrote.size)   // 251 icons + the egg, the font, the held-item marks, two maps
        assertTrue(wrote.all { it.startsWith("gen2/crystal_clear/") || it.startsWith("gen2/crystal-clear") || it.startsWith("regionmap/crystal_clear_") })
        assertTrue("the same build again: nothing", Gen2Art.extractTo(rom(v260), out.toOkioPath()).isEmpty())
        val other = File.createTempFile("gen2", "").also { it.delete() }
        try {
            assertEquals(wrote, Gen2Art.extractTo(rom(v2511), other.toOkioPath()))
            for (path in wrote) assertTrue(path, File(out, path).readBytes().contentEquals(File(other, path).readBytes()))
            // Another build of the game on the same paths: rebuilt, its own colours.
            assertEquals(wrote, Gen2Art.extractTo(rom(v2511cf), other.toOkioPath()))
            assertEquals(wrote, Gen2Art.extractTo(rom(v260cf), other.toOkioPath()))
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun `party icons - one per species, in its own colours`() {
        for ((f, t) in listOf(v260 to Gen2Art.CRYSTAL_CLEAR_V2_6_0, v2511 to Gen2Art.CRYSTAL_CLEAR_V2_5_11)) {
            val icons = Gen2Art.icons(rom(f), t)
            assertEquals(252, icons.size)
            // Sampled from the party menu headless: BULBASAUR teal with red eyes, SENTRET's browns, black outlines.
            assertEquals(setOf(0xFF000000.toInt(), 0xFF63DEBD.toInt(), 0xFFFF5231.toInt()), icons.getValue(1).argb.filter { it != 0 }.toSet())
            assertEquals(setOf(0xFF000000.toInt(), 0xFFB57B52.toInt(), 0xFF7B3921.toInt()), icons.getValue(161).argb.filter { it != 0 }.toSet())
            assertTrue(icons.getValue(Gen2Art.EGG).argb.count { it != 0 } > 50)
            assertTrue(icons.getValue(201).argb.count { it != 0 } > 30)   // UNOWN: A's
        }
    }

    @Test
    fun `town maps - its JOHTO and KANTO in the default RED POKéGEAR's colours`() {
        for ((f, t) in listOf(v260 to Gen2Art.CRYSTAL_CLEAR_V2_6_0, v2511 to Gen2Art.CRYSTAL_CLEAR_V2_5_11)) {
            for (i in 0..1) {
                val map = Gen2Art.townMap(rom(f), t, i)
                assertNotNull(map)
                // Every colour is one the POKéGEAR map showed headless (land, sea, earth, roads, its red towns, the frame).
                val seen = setOf(0xE7FFA5, 0x00FF00, 0x000000, 0x0000FF, 0x7B3900, 0xADADAD, 0x6B6B6B, 0xFF0000, 0xA50000)
                    .map { (0xFF000000 or it.toLong()).toInt() }.toSet()
                assertTrue(map!!.argb.toSet().all { it in seen })
            }
        }
    }

    @Test
    fun `dex pages and descriptions - its packed text, each version's tables`() {
        for ((f, t) in listOf(v260 to GEN2_DEX_CRYSTAL_CLEAR_V2_6_0, v2511 to GEN2_DEX_CRYSTAL_CLEAR_V2_5_11)) {
            Gen1Dex.rom = rom(f)
            try {
                val e = Gen2Dex.entry(t, 1)!!
                assertEquals("SEED", e.category)
                // "the", "it ", "are", "on", "in", "der"... come from its dictionary.
                assertEquals("While it is young, it uses the nutrients that are stored in the seeds on its back in order to grow.", e.description)
                assertEquals(2 * 12 + 4 to 150, e.heightIn to e.weightLbs10)
                assertEquals("A map of the current region.", Gen2Dex.itemDescription(t, 6))   // REGION MAP, its own item
                assertEquals("Restores POKéMON HP by 20.", Gen2Dex.itemDescription(t, 0x12))   // POTION
                assertEquals("An attack that always confuses.", Gen2Dex.itemDescription(t, 0xBF))   // TM01: DYNAMICPUNCH's
                for (s in 1..251) {
                    assertTrue("entry $s", Gen2Dex.entry(t, s)!!.description.isNotEmpty())
                    assertNotNull("pic $s", Gen2Dex.frontPixels(t, s))
                }
                val out = File("build/gen2-art-crystal-clear/dex").apply { mkdirs() }
                for (s in listOf(1, 152, 161, 201, 249)) {
                    File(out, "$s.png").writeBytes(RomArt.Image(56, 56, Gen2Dex.frontPixels(t, s)!!).png())
                }
            } finally {
                Gen1Dex.rom = null
            }
        }
    }

    @Test
    fun `the font parses`() {
        val ttf = Gen2Art.font(rom(v260), Gen2Art.CRYSTAL_CLEAR_V2_6_0)
        val fontClass = Class.forName("java.awt.Font")
        val font = fontClass.getMethod("createFont", Int::class.java, java.io.InputStream::class.java)
            .invoke(null, 0 /* TRUETYPE_FONT */, ttf.inputStream())
        val canDisplay = fontClass.getMethod("canDisplay", Char::class.java)
        for (c in "BULBASAUR Lv5 ♂♀ 21/21 ¥1500") assertTrue("glyph $c", canDisplay.invoke(font, c) as Boolean)
    }
}
