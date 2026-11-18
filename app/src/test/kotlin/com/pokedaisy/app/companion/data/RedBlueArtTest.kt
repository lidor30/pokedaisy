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
    // The hack under the ROM folder's naming (CLAUDE.md): retail Red with the Gen II Graphics V2.0 patch.
    private val redGen2Graphics = File(gbc, "Pokemon Red Gen II Graphics (v2.0).gb").takeIf { it.isFile }

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

    @Test
    fun `Red Gen II Graphics bank and pointer resolution configuration`() {
        assertEquals(27, GEN1_DEX_RED_GEN2_GRAPHICS.picBankOffset)
        assertEquals(0x2F to 0x4000, GEN1_DEX_RED_GEN2_GRAPHICS.cgbPalettes)
        assertEquals(0x1C to 0x65C8, GEN1_DEX_RED_GEN2_GRAPHICS.monsterPalettes)
        assertEquals(27, GEN1_DEX_YELLOW_GEN2_GRAPHICS.picBankOffset)
        assertEquals(0x42 to 0x4000, GEN1_DEX_YELLOW_GEN2_GRAPHICS.cgbPalettes)
        assertEquals(0x1C to 0x6995, GEN1_DEX_YELLOW_GEN2_GRAPHICS.monsterPalettes)
    }

    @Test
    fun `Red Gen II Graphics extracts front sprites using byte 27 bank selection`() {
        assumeTrue("Red Gen II Graphics ROM not on this machine", redGen2Graphics != null)
        val rom = redGen2Graphics!!.readBytes()
        Gen1Dex.rom = rom
        val t = GEN1_DEX_RED_GEN2_GRAPHICS
        try {
            // Verify pointers and banks in ROM
            // Bulbasaur (Dex 1): BaseStats at bank 0x0E addr 0x43DE -> 0x383DE
            val bulbaBase = 0x383DE
            val bulbaPtr = (rom[bulbaBase + 11].toInt() and 0xFF) or ((rom[bulbaBase + 12].toInt() and 0xFF) shl 8)
            val bulbaBank = rom[bulbaBase + 27].toInt() and 0xFF
            assertEquals(0x4000, bulbaPtr)
            assertEquals(0x09, bulbaBank)

            // Graveler (Dex 75): 0x383DE + 74 * 28 = 0x38BF2
            val gravBase = bulbaBase + 74 * 28
            val gravPtr = (rom[gravBase + 11].toInt() and 0xFF) or ((rom[gravBase + 12].toInt() and 0xFF) shl 8)
            val gravBank = rom[gravBase + 27].toInt() and 0xFF
            assertEquals(0x4000, gravPtr)
            assertEquals(0x0C, gravBank)

            // Mew (Dex 151): mewBaseStats at 0x01:0x425B -> 0x425B
            val mewBase = 0x425B
            val mewPtr = (rom[mewBase + 11].toInt() and 0xFF) or ((rom[mewBase + 12].toInt() and 0xFF) shl 8)
            val mewBank = rom[mewBase + 27].toInt() and 0xFF
            assertEquals(0x57E3, mewPtr)
            assertEquals(0x2E, mewBank)

            // Verify sprite decompression & dimensions for Bulbasaur, Graveler, Mew
            val bulbaPic = Gen1Dex.frontPixels(t, 1)
            assertNotNull("Bulbasaur front sprite", bulbaPic)
            assertEquals(56 * 56, bulbaPic!!.size)
            assertTrue("Bulbasaur has colored pixels", bulbaPic.any { it != 0 })

            val gravPic = Gen1Dex.frontPixels(t, 75)
            assertNotNull("Graveler front sprite", gravPic)
            assertEquals(56 * 56, gravPic!!.size)
            assertTrue("Graveler has colored pixels", gravPic.any { it != 0 })

            val mewPic = Gen1Dex.frontPixels(t, 151)
            assertNotNull("Mew front sprite", mewPic)
            assertEquals(56 * 56, mewPic!!.size)
            assertTrue("Mew has colored pixels", mewPic.any { it != 0 })

            // Verify all 151 species extract cleanly
            for (s in 1..151) {
                assertNotNull("entry $s", Gen1Dex.entry(t, s))
                assertNotNull("pic $s", Gen1Dex.frontPixels(t, s))
            }

            val out = File("build/gen1-art-red-gen2-graphics-dex").apply { mkdirs() }
            for (s in listOf(1, 4, 7, 25, 75, 150, 151)) {
                File(out, "$s.png").writeBytes(RomArt.Image(56, 56, Gen1Dex.frontPixels(t, s)!!).png())
            }
        } finally {
            Gen1Dex.rom = null
        }
    }
}
