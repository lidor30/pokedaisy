package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Polished Crystal's art, rebuilt from the player's own 3.2.3 ROM (skipped without it): the party minis (every species,
 * variant form and the egg, each in its own colours), the held-item marks, its Johto / Kanto / Orange Islands town maps and
 * its font. The art lands in app/build/gen2-art-polished to look at.
 */
class PolishedCrystalArtTest {
    private val rom = File(File(RETAIL_ROM_DIR).parentFile, "gbc/Pokemon Polished Crystal (v3.2.3).gbc")

    private fun rom(): ByteArray {
        assumeTrue("Polished Crystal 3.2.3 not on this machine", rom.isFile)
        return rom.readBytes()
    }

    @Test
    fun `its art, written once per build`() {
        val out = File("build/gen2-art-polished")
        out.deleteRecursively()
        val wrote = Gen2Art.extractTo(rom(), out.toOkioPath())
        assertTrue(wrote.size > 300)
        for (map in Gen2Art.POLISHED_TOWN_MAPS) assertTrue(map, "regionmap/$map.png" in wrote)
        assertTrue(Gen2Art.extractTo(rom(), out.toOkioPath()).isEmpty())   // the same build: nothing to redo
    }

    @Test
    fun `party minis in each species' colours`() {
        val t = Gen2Art::class.java.getDeclaredField("POLISHED_CRYSTAL").apply { isAccessible = true }.get(Gen2Art)
            .let { @Suppress("UNCHECKED_CAST") (it as List<Gen2Art.Gen2ArtTables>) }.first { "6930b48af5844d373e3c9130f26d6dd1084cf4ed" in it.sha1s }
        val icons = Gen2Art.icons(rom(), t)
        assertEquals(speciesNamesPolishedCrystal.keys, icons.keys)
        // BULBASAUR and an ALOLAN form differ from their plain selves; every mini has its four shades at most.
        assertTrue(icons.values.all { img -> img.argb.filter { it != 0 }.toSet().size in 2..3 })
        val rattata = 0x13
        val alolanRattata = POLISHED_NUM_SPECIES + 1 + polishedVariantForms.indexOfFirst { it shr 8 == rattata }
        assertTrue(!icons.getValue(rattata).argb.contentEquals(icons.getValue(alolanRattata).argb))
        assertNotNull(icons[0xFF])   // the egg
    }

    @Test
    fun `dex pages and pictures, in its National order`() {
        activeGame = GameKind.POLISHED_CRYSTAL
        Gen1Dex.rom = rom()
        try {
            val t = polishedCrystalPokedex("6930b48af5844d373e3c9130f26d6dd1084cf4ed")!!.gen2!!
            assertEquals(289, t.dexCount)
            val bulbasaur = Gen2Dex.entry(t, 1)!!
            assertEquals("Seed" to (7 to 69), bulbasaur.category to (bulbasaur.heightDm to bulbasaur.weightHg))
            assertEquals(listOf(45, 49, 49, 65, 65, 45), bulbasaur.baseStats)
            assertTrue(bulbasaur.description, bulbasaur.description.startsWith("While it is young, it uses the nutrients"))
            assertEquals("Fire Mouse", Gen2Dex.entry(t, 155)!!.category)
            // No. 255 is the first species past the 9th bit's line: MISMAGIUS, species 0x101.
            assertEquals(0x101, t.speciesOf(255))
            assertEquals("Magical", Gen2Dex.entry(t, 255)!!.category)
            for (n in 1..289) {
                assertNotNull("entry $n", Gen2Dex.entry(t, n))
                assertNotNull("pic $n", Gen2Dex.frontPixels(t, t.speciesOf(n)))
            }
            val out = File("build/gen2-art-polished-dex").apply { mkdirs() }
            for (n in listOf(1, 6, 25, 155, 249, 251, 255, 289)) {
                File(out, "$n.png").writeBytes(RomArt.Image(56, 56, Gen2Dex.frontPixels(t, t.speciesOf(n))!!).png())
            }
        } finally {
            Gen1Dex.rom = null
        }
    }

    @Test
    fun `bag descriptions - items, key items, a TM's move`() {
        activeGame = GameKind.POLISHED_CRYSTAL
        Gen1Dex.rom = rom()
        try {
            val t = polishedCrystalPokedex(TelemetrySampler.POLISHED_CRYSTAL_V3_2_3_SHA1)!!.gen2!!
            assertEquals("Restores Pokémon HP by 20.", Gen2Dex.itemDescription(t, 25))            // POTION
            assertEquals("A collapsible bike for fast movement.", Gen2Dex.itemDescription(t, 0x201))   // BICYCLE
            assertTrue(Gen2Dex.itemDescription(t, 0x101).isNotEmpty())                              // TM01: its move's
            assertEquals("", Gen2Dex.itemDescription(t, 0xFF))
            // Every item the bag can list has its text (no stray control codes).
            val dump = StringBuilder()
            for (id in itemNamesPolishedCrystal.keys) {
                val d = Gen2Dex.itemDescription(t, id)
                dump.append("${itemNamesPolishedCrystal[id]}: $d\n")
                assertTrue("item $id", d.isNotEmpty() && d.none { it == '@' } && d.length < 80)
            }
            File("build/gen2-art-polished-dex").apply { mkdirs() }.resolve("item-descriptions.txt").writeText(dump.toString())
        } finally {
            Gen1Dex.rom = null
        }
    }

    @Test
    fun `dex pages - abilities, and the faithful builds' own`() {
        activeGame = GameKind.POLISHED_CRYSTAL
        Gen1Dex.rom = rom()
        try {
            val t = polishedCrystalPokedex(TelemetrySampler.POLISHED_CRYSTAL_V3_2_3_SHA1)!!.gen2!!
            val bulbasaur = Gen2Dex.entry(t, 1)!!
            assertEquals(listOf("Overgrow", "Chlorophyll") to "Effect Spore", bulbasaur.abilities to bulbasaur.hiddenAbility)
            val cyndaquil = Gen2Dex.entry(t, 155)!!
            assertEquals(listOf("Blaze", "Flame Body") to "Flash Fire", cyndaquil.abilities to cyndaquil.hiddenAbility)
            for (n in 1..289) assertTrue("abilities $n", Gen2Dex.entry(t, n)!!.abilities.isNotEmpty())
        } finally {
            Gen1Dex.rom = null
        }
        val faithful = File(File(RETAIL_ROM_DIR).parentFile, "gbc/Pokemon Polished Crystal (v3.2.3) (Faithful).gbc")
        assumeTrue("faithful 3.2.3 not on this machine", faithful.isFile)
        Gen1Dex.rom = faithful.readBytes()
        try {
            val t = polishedCrystalPokedex(TelemetrySampler.POLISHED_CRYSTAL_V3_2_3_Faithful_SHA1)!!.gen2!!
            val cyndaquil = Gen2Dex.entry(t, 155)!!
            assertEquals(listOf("Blaze") to "Flash Fire", cyndaquil.abilities to cyndaquil.hiddenAbility)
        } finally {
            Gen1Dex.rom = null
        }
    }

    @Test
    fun `the player on the town map, by the look picked`() {
        val t = Gen2Art::class.java.getDeclaredField("POLISHED_CRYSTAL").apply { isAccessible = true }.get(Gen2Art)
            .let { @Suppress("UNCHECKED_CAST") (it as List<Gen2Art.Gen2ArtTables>) }.first { "6930b48af5844d373e3c9130f26d6dd1084cf4ed" in it.sha1s }
        val out = File("build/gen2-art-polished-dex").apply { mkdirs() }
        val heads = (0..2).map { Gen2Art.player(rom(), t, it) }
        heads.forEachIndexed { g, img -> File(out, "${Gen2Art.POLISHED_PLAYERS[g]}.png").writeBytes(img.png()) }
        // CHRIS red, KRIS blue, CRYS green (PokegearOBPals' third colour): each drawn, each in its own.
        val third = listOf(0xFFFF3908.toInt(), 0xFF524AFF.toInt(), 0xFF39BD18.toInt())
        heads.forEachIndexed { g, img ->
            assertTrue("head $g", img.argb.count { it != 0 } > 60)
            assertTrue("head $g colour", third[g] in img.argb.toSet())
        }
    }

    @Test
    fun `town maps and the font`() {
        val t = Gen2Art::class.java.getDeclaredField("POLISHED_CRYSTAL").apply { isAccessible = true }.get(Gen2Art)
            .let { @Suppress("UNCHECKED_CAST") (it as List<Gen2Art.Gen2ArtTables>) }.first { "6930b48af5844d373e3c9130f26d6dd1084cf4ed" in it.sha1s }
        for (i in 0..2) {
            val map = Gen2Art.townMap(rom(), t, i)
            assertNotNull("map $i", map)
            assertEquals(160 to 144, map!!.width to map.height)
            assertTrue(map.argb.toSet().size > 6)
        }
        val ttf = Gen2Art.font(rom(), t)
        val fontClass = Class.forName("java.awt.Font")
        val font = fontClass.getMethod("createFont", Int::class.java, java.io.InputStream::class.java)
            .invoke(null, 0 /* TRUETYPE_FONT */, ttf.inputStream())
        val canDisplay = fontClass.getMethod("canDisplay", Char::class.java)
        for (c in "Cyndaquil Lv5 ♂♀ 20/20 ¥3000 Poké Ball") assertTrue("glyph $c", canDisplay.invoke(font, c) as Boolean)
    }
}
