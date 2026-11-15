package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Red's and Blue's art and dex pages, rebuilt from the player's own ROMs (skipped without them; Blue is also looked for
 * as pret/pokered's build under $DECOMPS, byte for byte the cart): the two carts give the same art, every species has
 * a page and a picture - MEW's from its own bank - and the colours are the Super Game Boy ones the game shows. The art
 * lands in app/build/gen1-art-red to look at.
 */
class RedBlueArtTest {
    private val gbc = File(RETAIL_ROM_DIR).parentFile!!.let { File(it, "gbc") }
    private val red = File(gbc, "Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb")
    private val blue = listOfNotNull(
        File(gbc, "Pokemon - Blue Version (USA, Europe) (SGB Enhanced).gb"),
        System.getenv("DECOMPS")?.let { File(it, "pokered/pokeblue.gbc") },
    ).firstOrNull { it.isFile }

    private fun red(): ByteArray {
        assumeTrue("Red ROM not on this machine", red.isFile)
        return red.readBytes()
    }

    @Test
    fun `Red and Blue give the same art`() {
        val out = File("build/gen1-art-red")
        out.deleteRecursively()
        val wrote = Gen1Art.extractTo(red(), out.toOkioPath())
        assertTrue(wrote.size > 150)
        assertTrue(Gen1Art.RED_BLUE_TOWN_MAP in wrote && Gen1Art.RED_BLUE_PLAYER in wrote && Gen1Art.RED_BLUE_FONT in wrote)
        assumeTrue("Blue ROM not on this machine", blue != null)
        val other = File.createTempFile("gen1", "").also { it.delete() }
        assertEquals(wrote.toSet(), Gen1Art.extractTo(blue!!.readBytes(), other.toOkioPath()).toSet())
        for (path in wrote) assertTrue(path, File(out, path).readBytes().contentEquals(File(other, path).readBytes()))
        other.deleteRecursively()
    }

    @Test
    fun `party icons and the town map in the game's colours`() {
        val icons = Gen1Art.icons(red(), Gen1Art.RED)
        assertEquals(151, icons.size)
        // BULBASAUR's shape: every colour of the party menu's palette.
        assertEquals(Gen1Art.RED.iconColors.toSet(), icons.getValue(1).argb.filter { it != 0 }.toSet())
        val map = Gen1Art.townMap(red(), Gen1Art.RED)
        assertEquals(160 to 144, map.width to map.height)
        assertEquals(Gen1Art.RED.townMapColors.toSet(), map.argb.toSet())
        assertTrue(Gen1Art.player(red(), Gen1Art.RED).argb.any { it != 0 })
    }

    @Test
    fun `dex pages from the cart, MEW's from bank 1`() {
        activeGame = GameKind.RED
        Gen1Dex.rom = red()
        val t = POKEDEX_RED.gen1!!
        try {
            val bulbasaur = Gen1Dex.entry(t, 1)!!
            assertEquals("SEED", bulbasaur.category)
            assertEquals(2 * 12 + 4 to 150, bulbasaur.heightIn to bulbasaur.weightLbs10)   // 2'04", 15.0 lb
            assertEquals(listOf(45, 49, 49, 45, 65), bulbasaur.baseStats)
            // Red's own text (Yellow rewrote its pages).
            assertTrue(bulbasaur.description, bulbasaur.description.startsWith("A strange seed was"))
            val mew = Gen1Dex.entry(t, 151)!!
            assertEquals(listOf(100, 100, 100, 100, 100), mew.baseStats)
            assertEquals("PSYCHIC", typeName(mew.type1))
            for (s in 1..151) {
                assertNotNull("entry $s", Gen1Dex.entry(t, s))
                assertNotNull("pic $s", Gen1Dex.frontPixels(t, s))
            }
            val out = File("build/gen1-art-red-dex").apply { mkdirs() }
            for (s in listOf(1, 4, 7, 25, 150, 151)) {
                File(out, "$s.png").writeBytes(RomArt.Image(56, 56, Gen1Dex.frontPixels(t, s)!!).png())
            }
        } finally {
            Gen1Dex.rom = null
        }
    }

    @Test
    fun `the font parses`() {
        val ttf = Gen1Art.font(red(), Gen1Art.RED)
        val fontClass = Class.forName("java.awt.Font")
        val font = fontClass.getMethod("createFont", Int::class.java, java.io.InputStream::class.java)
            .invoke(null, 0 /* TRUETYPE_FONT */, ttf.inputStream())
        assertEquals("Gen1 Pokemon", fontClass.getMethod("getFamily").invoke(font))
        val canDisplay = fontClass.getMethod("canDisplay", Char::class.java)
        for (c in "CHARMANDER Lv5 ♂♀ 19/19 ¥3000") assertTrue("glyph $c", canDisplay.invoke(font, c) as Boolean)
    }
}
