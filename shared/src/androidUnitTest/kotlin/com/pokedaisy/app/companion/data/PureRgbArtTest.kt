package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * PureRed's and PureBlue's art and dex pages, rebuilt from the player's own ROMs (skipped without them): PureRGB's own
 * party icons (8 tiles each, frame 2 beside frame 1), its fixed-size dex records with the category apart, MEW in the main
 * base data, the pic's bank from its base data. The art lands in app/build/gen1-art-pure to look at.
 */
class PureRgbArtTest {
    private val gbc = File(RETAIL_ROM_DIR).parentFile!!.let { File(it, "gbc") }
    private val pureRed = File(gbc, "Pokemon PureRed (v2.7.6).gb")
    private val pureBlue = File(gbc, "Pokemon PureBlue (v2.7.6).gb")

    private fun rom(f: File): ByteArray {
        assumeTrue("$f not on this machine", f.isFile)
        return f.readBytes()
    }

    @After
    fun reset() {
        Gen1Dex.rom = null
        activeGame = GameKind.FIRERED
    }

    @Test
    fun `the art, in each one's colours`() {
        val out = File("build/gen1-art-pure")
        out.deleteRecursively()
        val red = Gen1Art.extractTo(rom(pureRed), out.toOkioPath())
        assertTrue(red.size > 150)
        assertTrue("regionmap/pure_red.png" in red && "regionmap/player_pure_red.png" in red && Gen1Art.PURE_FONT in red)
        val blue = Gen1Art.extractTo(rom(pureBlue), out.toOkioPath())
        assertTrue("regionmap/pure_blue.png" in blue && "gen1/pure_blue/pokemon/25.png" in blue)
        // The same shapes, other colours.
        val r = Gen1Art.icons(rom(pureRed), Gen1Art.PURE_RED)
        val b = Gen1Art.icons(rom(pureBlue), Gen1Art.PURE_BLUE)
        assertEquals(151, r.size)
        for (dex in 1..151) assertEquals("$dex", r.getValue(dex).argb.map { it != 0 }, b.getValue(dex).argb.map { it != 0 })
        assertEquals(Gen1Art.PURE_RED.iconColors.toSet(), r.getValue(1).argb.filter { it != 0 }.toSet())
        // Two frames that differ (BULBASAUR bobs).
        val bulba = r.getValue(1).argb
        assertNotEquals(bulba.copyOfRange(0, 256).toList(), bulba.copyOfRange(256, 512).toList())
        val map = Gen1Art.townMap(rom(pureRed), Gen1Art.PURE_RED)
        assertEquals(160 to 144, map.width to map.height)
        assertEquals(Gen1Art.PURE_RED.townMapColors.toSet(), map.argb.toSet())
        assertTrue(Gen1Art.player(rom(pureRed), Gen1Art.PURE_RED).argb.any { it != 0 })
    }

    @Test
    fun `dex pages from the cart`() {
        activeGame = GameKind.PURE_RED
        Gen1Dex.rom = rom(pureRed)
        val t = POKEDEX_PURE_RED.gen1!!
        val bulbasaur = Gen1Dex.entry(t, 1)!!
        assertEquals("SEED", bulbasaur.category)
        assertEquals(2 * 12 + 4 to 150, bulbasaur.heightIn to bulbasaur.weightLbs10)
        assertEquals(listOf(45, 49, 49, 45, 65), bulbasaur.baseStats)
        // Its one-byte words expanded ("this #MON" is one byte for POKéMON).
        assertEquals("A strange seed was planted on its back at birth. The plant sprouts and grows with this POKéMON.",
            bulbasaur.description)
        val mew = Gen1Dex.entry(t, 151)!!
        assertEquals(listOf(100, 100, 100, 100, 100), mew.baseStats)
        assertEquals("PSYCHIC", typeName(mew.type1))
        for (s in 1..151) {
            val e = Gen1Dex.entry(t, s)
            assertNotNull("entry $s", e)
            assertTrue("category $s", e!!.category.isNotBlank() && e.description.isNotBlank())
            assertNotNull("pic $s", Gen1Dex.frontPixels(t, s))
        }
        val out = File("build/gen1-art-pure-dex").apply { mkdirs() }
        for (s in listOf(1, 4, 7, 25, 100, 150, 151)) {
            File(out, "$s.png").writeBytes(RomArt.Image(56, 56, Gen1Dex.frontPixels(t, s)!!).png())
        }
    }

    /** STEADY FF music: each listed (bank, id) is a music header in the cart (its first channel one of CHAN1-4), and the park
     * is the `18 FE` the render core spins on. */
    @Test
    fun `the songs STEADY renders are the cart's music`() {
        for ((file, sha1) in listOf(pureRed to TelemetrySampler.PURE_RED_V2_7_6_SHA1, pureBlue to TelemetrySampler.PURE_BLUE_V2_7_6_SHA1)) {
            val rom = rom(file)
            val t = Gen1Music.forSha1(sha1)!!
            assertEquals(0x18 to 0xFE, (rom[t.spin].toInt() and 0xFF) to (rom[t.spin + 1].toInt() and 0xFF))
            for ((bank, id) in t.songs) {
                val header = rom[bank * 0x4000 + 3 * id].toInt() and 0xFF
                assertTrue("$bank:$id", header and 0x0F in 0..3)
            }
            // The ids are the headers' places: each is the start of a header (3 bytes a channel), none falls inside another's.
            val starts = t.songs.groupBy({ it.first }, { it.second })
            for ((bank, ids) in starts) {
                val sorted = ids.sorted()
                for ((a, b) in sorted.zipWithNext()) {
                    val channels = ((rom[bank * 0x4000 + 3 * a].toInt() and 0xFF) shr 6) + 1
                    assertEquals("$bank:$a", b, a + channels)
                }
            }
        }
    }

    @Test
    fun `the font parses`() {
        val ttf = Gen1Art.font(rom(pureRed), Gen1Art.PURE_RED)
        val fontClass = Class.forName("java.awt.Font")
        val font = fontClass.getMethod("createFont", Int::class.java, java.io.InputStream::class.java)
            .invoke(null, 0 /* TRUETYPE_FONT */, ttf.inputStream())
        val canDisplay = fontClass.getMethod("canDisplay", Char::class.java)
        for (c in "CHARMANDER Lv5 ♂♀ 19/19 ¥3000 +%") assertTrue("glyph $c", canDisplay.invoke(font, c) as Boolean)
    }
}
