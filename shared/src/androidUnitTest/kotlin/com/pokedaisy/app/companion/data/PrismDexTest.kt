package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/**
 * Prism's POKéDEX: its one list (253 species in its own order, numbered by their place), the save's flags by species
 * id, and the pages read from the player's ROM (skipped without it) through its text engine ([Gen2PackedText]).
 *
 * The pages were checked against the game's own dex screens headless (the prism fixture's save with every seen / caught
 * flag set and the Pokédex flag on, then START > Pokédex): LARVITAR (No.001), FROSLASS (No.021, a page whose text picks
 * metric or imperial by the UNITS option), PHANCERO (No.239), VARANEOUS (No.250), RAIWATO (No.251), FAMBACO (No.252) and
 * LIBABEEL (No.253), both halves of each page as the game prints them, HT / WT, and the picture pixel for pixel in the
 * page's colours. The game's prose isn't kept here: the pins are CRC32s of the decoded text and pixels.
 */
class PrismDexTest {
    private val romFile = File(File(RETAIL_ROM_DIR).parentFile!!, "gbc/Pokemon Prism (v0.95 build 254 Hotfix 5).gbc")

    private fun rom() {
        assumeTrue("Prism ROM not on this machine", romFile.isFile)
        Gen1Dex.rom = romFile.readBytes()
        activeGame = GameKind.PRISM
        activeGen2 = GEN2_PRISM
    }

    @After
    fun noCart() { Gen1Dex.rom = null }

    private fun crc(s: String) = CRC32().apply { update(s.toByteArray()) }.value
    private fun crc(px: IntArray) = CRC32().apply { px.forEach { v -> repeat(4) { update(v ushr (24 - 8 * it)) } } }.value

    @Test
    fun `its own list - every species but EGG once, numbered by its place`() {
        val g = GEN2_DEX_PRISM
        assertEquals(253, g.dexCount)
        assertEquals((1..254).filter { it != 253 }, (1..253).map { g.speciesOf(it) }.sorted())
        assertEquals(246, g.speciesOf(1))     // LARVITAR is No.001
        assertEquals(27, g.speciesOf(16))     // SHINX No.016
        assertEquals(254, g.speciesOf(253))   // LIBABEEL No.253
        assertEquals(0, g.speciesOf(254))
        for (n in 1..253) assertEquals(n, g.dexOf(g.speciesOf(n)))
        assertEquals(0, g.dexOf(253))         // EGG
        val t = POKEDEX_PRISM
        assertEquals(246, PokedexSource.speciesFor(t, 1))
        assertEquals(239, PokedexSource.nationalOf(t, 252))   // PHANCERO
        assertEquals(246, PokedexSource.cachedSpeciesMap(t)!![1])
    }

    @Test
    fun `the save's flags - by species id, shown under the dex's own numbers`() {
        activeGame = GameKind.PRISM
        activeGen2 = GEN2_PRISM
        val dex = readGen2Telemetry(FixtureMemoryReader.load("prism"), GEN2_PRISM).pokedex
        assertNotNull(dex)
        assertEquals(POKEDEX_PRISM, dex!!.tables)
        // LARVITAR (species 246) and SHINX (27), both caught: No.001 and No.016.
        assertEquals(setOf(1, 16), dex.caught)
        assertEquals(setOf(1, 16), dex.seen)
    }

    @Test
    fun `pages match the game's own - text, HT, WT and picture`() {
        rom()
        val t = POKEDEX_PRISM
        // dex No. to (species, category CRC, dm, hg, description CRC, picture CRC)
        val pages = mapOf(
            1 to listOf(246L, 0x29C55190L, 6L, 720L, 0xDBB111ECL, 0xA4EBB790L),
            21 to listOf(34L, 0xEBD9501FL, 13L, 266L, 0xB781C0D8L, 0x6808242EL),
            239 to listOf(252L, 0x54E579DDL, 15L, 468L, 0x591423B9L, 0xA446135BL),
            250 to listOf(138L, 0x2EA8A019L, 19L, 1194L, 0x3D60A4ABL, 0xAB2B5FD1L),
            251 to listOf(251L, 0x375F3686L, 19L, 1948L, 0x61152048L, 0xC61489A6L),
            252 to listOf(238L, 0x449B58A8L, 18L, 652L, 0x68D14E13L, 0x2C033CADL),
            253 to listOf(254L, 0x62F4E733L, 16L, 1240L, 0x204AFE66L, 0x068FF2D9L),
        )
        val got = pages.mapValues { (n, _) ->
            val e = PokedexSource.entry(t, n)!!
            val pic = Gen2Dex.frontPixels(t.gen2!!, e.species)!!
            listOf(e.species.toLong(), crc(e.category), e.heightDm.toLong(), e.weightHg.toLong(), crc(e.description), crc(pic))
        }
        val out = File("build/prism-dex").apply { mkdirs() }
        File(out, "pins.txt").writeText(got.entries.joinToString("\n") { (n, v) -> "$n to " + v.joinToString { "0x%X".format(it) } })
        assertEquals(pages, got)
        assertEquals("0.6m", formatDexHeight(t, 6))
        assertEquals("72.0kg", formatDexWeight(t, 720))
    }

    @Test
    fun `every page and description decodes`() {
        rom()
        val t = POKEDEX_PRISM
        val out = File("build/prism-dex").apply { mkdirs() }
        val lines = (1..253).map { n ->
            val e = PokedexSource.entry(t, n)
            assertNotNull("No.$n", e)
            assertTrue("No.$n's category", e!!.category.isNotBlank())
            assertTrue("No.$n's page", e.description.length > 20)
            assertTrue("No.$n: no leftovers", e.description.none { it == '\n' || it == '|' })
            assertTrue("No.$n's size", e.heightDm in 1..200 && e.weightHg in 1..10000)
            val pic = Gen2Dex.frontPixels(t.gen2!!, e.species)
            assertNotNull("No.$n's picture", pic)
            RomArt.Image(56, 56, pic!!).png().let { File(out, "%03d.png".format(n)).writeBytes(it) }
            "$n ${e.species} ${e.category} | ${e.heightDm} ${e.weightHg} | ${e.description}"
        }
        File(out, "entries.txt").writeText(lines.joinToString("\n"))
        val g = t.gen2!!
        val items = (1..253).map { Gen2Dex.itemDescription(g, it) }
        assertTrue(items.all { it.isNotEmpty() })
        File(out, "items.txt").writeText(items.withIndex().joinToString("\n") { "${it.index + 1} ${it.value}" })
        assertEquals("", Gen2Dex.itemDescription(g, 0xFE))
        // TMs aren't items in Prism: their pocket shows the move's own description.
        assertTrue(gen2TmItemsPrism.all { Gen2Dex.itemDescription(g, it).isNotEmpty() })
    }

    @Test
    fun `item descriptions - the bag's text, a broken word joined back`() {
        rom()
        assertEquals("Restores Pokémon HP by 20.", itemDescription(18))   // POTION
        assertEquals("Repels weak Pokémon for 100 steps.", itemDescription(20))   // REPEL: "Poké-" / "mon" on the bag screen
    }

    @Test
    fun `no cart - no pages`() {
        Gen1Dex.rom = null
        assertNull(Gen2Dex.entry(GEN2_DEX_PRISM, 1))
        assertEquals("", Gen2Dex.itemDescription(GEN2_DEX_PRISM, 18))
    }
}
