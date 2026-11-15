package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Crystal's art and dex pages, rebuilt from the player's own ROMs (skipped without them): every release
 * gives the same bytes, and the pixels match headless screenshots of the game (sampled colours, the
 * player's icon where the POKéGEAR draws it). The art lands in app/build/gen2-art to look at.
 */
class Gen2ArtTest {
    private val dir = File(RETAIL_ROM_DIR).parentFile!!.let { File(it, "gbc") }
    private val roms = listOf(
        "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc",
        "Pokemon - Crystal Version (USA).gbc",
        "Pokemon - Crystal Version (Australia).gbc",
    ).map { File(dir, it) }

    private fun rom(i: Int = 0): ByteArray {
        assumeTrue("Crystal ROM not on this machine", roms[i].isFile)
        return roms[i].readBytes()
    }

    @Test
    fun `every release gives the same art`() {
        val out = File("build/gen2-art")
        out.deleteRecursively()
        val wrote = Gen2Art.extractTo(rom(0), out.toOkioPath())
        assertTrue(wrote.size > 250)
        for (i in 1..2) {
            val other = File.createTempFile("gen2", "").also { it.delete() }
            Gen2Art.extractTo(rom(i), other.toOkioPath())
            for (path in wrote) assertTrue(path, File(out, path).readBytes().contentEquals(File(other, path).readBytes()))
            other.deleteRecursively()
        }
    }

    @Test
    fun `party icons in the party menu's colours`() {
        val icons = Gen2Art.icons(rom(), Gen2Art.CRYSTAL)
        assertEquals(252, icons.size)   // 251 + the egg
        val px = icons.getValue(152).argb.filter { it != 0 }.toSet()
        // Sampled from the party menu headless: black, red (31,7,4), orange (31,19,10).
        assertEquals(setOf(0xFF000000.toInt(), 0xFFFF3921.toInt(), 0xFFFF9C52.toInt()), px)
    }

    @Test
    fun `town maps - New Bark at its landmark, the POKéGEAR's colours`() {
        val johto = assertNotNullAndGet(Gen2Art.townMap(rom(), Gen2Art.CRYSTAL, 0))
        assertEquals(160 to 144, johto.width to johto.height)
        val colors = johto.argb.toSet()
        // The POKéGEAR map's earth / sea / land seen headless.
        assertTrue(0xFF00FF00.toInt() in colors && 0xFF0000FF.toInt() in colors && 0xFFE7FFA5.toInt() in colors)
        assertNotNull(Gen2Art.townMap(rom(), Gen2Art.CRYSTAL, 1))
        val chris = Gen2Art.player(rom(), Gen2Art.CRYSTAL, 0).argb.filter { it != 0 }.toSet()
        assertTrue(0xFFFF3908.toInt() in chris)   // the red of CHRIS on the POKéGEAR map, headless
    }

    @Test
    fun `dex pages from the cart`() {
        Gen1Dex.rom = rom()
        try {
            val e = Gen2Dex.entry(GEN2_DEX_CRYSTAL, 152)!!
            assertEquals("LEAF", e.category)
            assertEquals(2 * 12 + 11 to 140, e.heightIn to e.weightLbs10)   // 2'11", 14.0 lbs
            assertEquals(listOf(45, 49, 65, 49, 65, 45), e.baseStats)
            assertTrue(e.description, e.description.startsWith("It loves to bask in the sunlight.") && e.description.endsWith("places."))
            val pic = Gen2Dex.frontPixels(GEN2_DEX_CRYSTAL, 152)!!
            assertTrue(pic.count { it != 0 } > 400)
            assertEquals("Restores POKéMON HP by 20.", Gen2Dex.itemDescription(GEN2_DEX_CRYSTAL, 0x12))   // POTION
            assertTrue(Gen2Dex.itemDescription(GEN2_DEX_CRYSTAL, 0xBF).isNotEmpty())   // TM01: its move's
            for (s in 1..251) {
                assertNotNull("entry $s", Gen2Dex.entry(GEN2_DEX_CRYSTAL, s))
                assertNotNull("pic $s", Gen2Dex.frontPixels(GEN2_DEX_CRYSTAL, s))
            }
            val out = File("build/gen2-art/dex").apply { mkdirs() }
            for (s in listOf(1, 25, 152, 201, 249)) {
                File(out, "$s.png").writeBytes(RomArt.Image(56, 56, Gen2Dex.frontPixels(GEN2_DEX_CRYSTAL, s)!!).png())
            }
        } finally {
            Gen1Dex.rom = null
        }
    }

    @Test
    fun `the font parses`() {
        val ttf = Gen2Art.font(rom(), Gen2Art.CRYSTAL)
        val fontClass = Class.forName("java.awt.Font")
        val font = fontClass.getMethod("createFont", Int::class.java, java.io.InputStream::class.java)
            .invoke(null, 0 /* TRUETYPE_FONT */, ttf.inputStream())
        assertEquals("Gen2 Pokemon", fontClass.getMethod("getFamily").invoke(font))
        val canDisplay = fontClass.getMethod("canDisplay", Char::class.java)
        for (c in "CHIKORITA Lv5 ♂♀ 19/19 ¥3000") assertTrue("glyph $c", canDisplay.invoke(font, c) as Boolean)
    }

    @Test
    fun `another cart gets nothing`() {
        assertTrue(Gen2Art.extractTo(ByteArray(0x8000), File.createTempFile("gen2", "").also { it.delete() }.toOkioPath()).isEmpty())
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
