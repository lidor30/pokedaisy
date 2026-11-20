package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Inflater
import okio.Path.Companion.toOkioPath

/**
 * Pokémon Black's art and DEX rebuilt from the player's own cart (NdsArt): party and bag icons, the DEX's pages, the
 * Unova order, Gen 5's evolutions, level-up moves, TMs / HMs and front pictures, and its names (Gen5Text) - a few
 * written to app/build/nds-art/ to look at. Pixels are pinned by CRC (never the games' prose). Skips without the ROM
 * (`-DromDir=`'s ../nds/).
 */
class NdsArtBlackTest {
    private val rom = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon - Black Version (USA, Europe) (NDSi Enhanced).nds")

    /** A PNG RomArt wrote (RGBA, filter 0 rows): its size and the CRC32 of its ARGB pixels. */
    private fun pixels(f: File): Triple<Int, Int, Long> {
        val b = f.readBytes()
        fun int(o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        val w = int(16)
        val h = int(20)
        val z = java.io.ByteArrayOutputStream()
        var o = 8
        while (o + 8 <= b.size) {
            val len = int(o)
            if (String(b, o + 4, 4, Charsets.US_ASCII) == "IDAT") z.write(b, o + 8, len)
            o += 12 + len
        }
        val raw = ByteArray(h * (1 + w * 4))
        Inflater().apply { setInput(z.toByteArray()); inflate(raw); end() }
        val crc = CRC32()
        for (y in 0 until h) {
            assertEquals(0, raw[y * (1 + w * 4)].toInt())
            for (x in 0 until w) {
                val p = y * (1 + w * 4) + 1 + x * 4
                // ARGB, as RomArtTest pins them
                crc.update(byteArrayOf(raw[p + 3], raw[p], raw[p + 1], raw[p + 2]))
            }
        }
        return Triple(w, h, crc.value)
    }

    @Test
    fun `icons, the DEX's pages, Unova order and pictures`() {
        assumeTrue("no Black ROM at $rom", rom.isFile)
        val dir = kotlin.io.path.createTempDirectory("nds-art").toFile()
        val out = File("build/nds-art").apply { mkdirs() }
        try {
            assertTrue(NdsArt.extractTo(rom.toOkioPath(), dir.toOkioPath()))
            // name -> (width, height, CRC32 of the ARGB pixels)
            val golden = mapOf(
                "${NdsArt.BLACK_ICONS}/1.png" to Triple(32, 64, 2950989826L),         // BULBASAUR, palette 1
                "${NdsArt.BLACK_ICONS}/495.png" to Triple(32, 64, 834731437L),        // SNIVY
                "${NdsArt.BLACK_ICONS}/${NdsArt.BLACK_EGG}.png" to Triple(32, 64, 480870671L),
                "${NdsArt.BLACK_ITEMS}/17.png" to Triple(32, 32, 3212865179L),        // Potion
                "${NdsArt.BLACK_ITEMS}/328.png" to Triple(32, 32, 3849909080L),       // TM01, the DARK disc
                "${NdsArt.BLACK_FRONT}/1.png" to Triple(96, 96, 3228605874L),
                "${NdsArt.BLACK_FRONT}/495.png" to Triple(96, 96, 3499488936L),
                "${NdsArt.BLACK_FRONT}/643.png" to Triple(96, 96, 31452441L),         // RESHIRAM
            )
            for ((path, want) in golden) {
                val f = File(dir, path)
                assertTrue("$f", f.isFile)
                f.copyTo(File(out, "bw-" + path.substringAfter("nds/black/").replace('/', '-')), true)
                assertEquals(path, want, pixels(f))
            }
            assertEquals(649 + 1, File(dir, NdsArt.BLACK_ICONS).list()!!.size)
            assertEquals(649, File(dir, NdsArt.BLACK_FRONT).list()!!.size)
            assertTrue(File(dir, "${NdsArt.BLACK_ITEMS}/626.png").isFile)
            // the DEX: a page a species, from the cart's own text and data
            val dex = File(dir, NdsArt.BLACK_DEX).readLines()
            File(out, "bw-dex.tsv").writeText(dex.take(3).joinToString("\n"))
            assertEquals(649, dex.size)
            val bulbasaur = dex.first().split('\t')
            assertEquals(listOf("1", "Seed Pokémon"), bulbasaur.take(2))
            assertTrue(bulbasaur[2].isNotBlank())
            assertEquals(listOf("7", "69"), bulbasaur.subList(3, 5))                     // 0.7 m, 6.9 kg
            assertEquals(listOf("45", "49", "49", "65", "65", "45"), bulbasaur.subList(5, 11))
            assertEquals(listOf("11", "3"), bulbasaur.subList(11, 13))                  // GRASS (Gen 5's 11), POISON
            assertEquals(listOf("Overgrow", ""), bulbasaur.subList(17, 19))
            assertEquals("Chlorophyll", bulbasaur[23])
            // ... its Unova order, evolutions, level-up moves and TMs, as the companion reads them
            val t = GEN5_DEX_BLACK
            val pages = Gen4Dex.parse(dex, t.lastSpecies, t.gen5)
            assertEquals("Chlorophyll", pages.entries[1]?.hiddenAbility)
            assertEquals(listOf("Overgrow"), pages.entries[1]?.abilities)
            assertEquals(156, pages.regional.size)
            assertEquals(listOf(494, 495, 496, 497), pages.regional.take(4))           // VICTINI (No. 000), SNIVY
            assertEquals(649, pages.regional.last())                                    // GENESECT
            assertEquals(listOf(EvoLink(1, 2, listOf(EvoReq.Level(16)))), pages.evolutions[1])
            assertEquals(LevelMove(1, 33), pages.levelUp[1]?.first())                  // TACKLE
            // Gen 5's method 7: KARRABLAST and SHELMET evolve traded for each other
            assertEquals(listOf(EvoLink(588, 589, listOf(EvoReq.Trade, EvoReq.TradeFor(616)))), pages.evolutions[588])
            assertEquals(listOf(EvoLink(616, 617, listOf(EvoReq.Trade, EvoReq.TradeFor(588)))), pages.evolutions[616])
            // ... and the later methods one up from Gen 4's: MAGNETON's magnetic field, GLIGAR's held item at night
            assertEquals(listOf(EvoLink(82, 462, listOf(EvoReq.LevelUp, EvoReq.SomePlace))), pages.evolutions[82])
            assertEquals(listOf(EvoLink(207, 472, listOf(EvoReq.LevelUp, EvoReq.Hold(327), EvoReq.Time(EvoTime.NIGHT)))),
                pages.evolutions[207])
            val eevee = pages.evolutions[133].orEmpty().map { it.to }.toSet()
            assertEquals(setOf(134, 135, 136, 196, 197, 470, 471), eevee)
            // TM / HM bits: TM01 .. TM95 then HM01 .. HM06 - BULBASAUR has TM06 (TOXIC), HM01 (CUT), HM04 (STRENGTH)
            // and TM94 (ROCK SMASH), not TM93 (WILD CHARGE)
            val bits = pages.machines[1]!!
            fun has(i: Int) = (bits[i / 8].toInt() shr (i % 8)) and 1 != 0
            assertEquals(listOf(true, true, true, true, false), listOf(5, 95, 98, 93, 92).map(::has))
            assertEquals(listOf(15, 70, 249, 528), listOf(95, 98, 93, 92).map { tmMovesBlack[it] })
        } finally {
            dir.deleteRecursively()
        }
    }

    /** [len] bytes of the cart's unpacked ARM9 at RAM address [addr]. */
    private fun arm9(addr: Long, len: Int): ByteArray = withRomFile(rom.toOkioPath()) { f ->
        Gen5Lz.arm9(f, f.readFully(0, 0x200))!!.at(addr, len)!!
    }

    private fun u16s(b: ByteArray) = IntArray(b.size / 2) { (b[it * 2].toInt() and 0xFF) or ((b[it * 2 + 1].toInt() and 0xFF) shl 8) }

    @Test
    fun `the icon tables are the cart's`() {
        assumeTrue("no Black ROM at $rom", rom.isFile)
        // a byte an icon (712): low nibble its palette, high nibble its female icon's
        val pals = arm9(0x0209E474L, 712)
        assertEquals(pals.map { it.toInt() and 0xF }, iconPalettesBlack.take(712).map { it.toInt() })
        // (NCGR, NCLR) by item id, 0..626; the table leaves out the unused ids (the "?" icon, 1001 / 1002, and 622)
        val items = u16s(arm9(0x0209EB6CL, 627 * 4))
        val cart = (1..626).associateWith { (items[it * 2] shl 16) or items[it * 2 + 1] }
        assertEquals(cart.filterKeys { it in itemIconsBlack }, itemIconsBlack)
        assertEquals(setOf(622), cart.filter { (id, v) -> id !in itemIconsBlack && v != 0x03E903EA }.keys)
    }

    @Test
    fun `the machines' moves are the cart's`() {
        assumeTrue("no Black ROM at $rom", rom.isFile)
        val moves = u16s(arm9(0x0209EAA0L, 101 * 2))
        // the cart lists TM01..TM92, HM01..HM06, TM93..TM95; the personal bits TM01..TM95, HM01..HM06
        val bitsOrder = moves.copyOfRange(0, 92) + moves.copyOfRange(98, 101) + moves.copyOfRange(92, 98)
        assertEquals(tmMovesBlack.toList(), bitsOrder.toList())
    }

    @Test
    fun `its names, read from its own text`() {
        assumeTrue("no Black ROM at $rom", rom.isFile)
        val n = NdsArt.readNames(rom.toOkioPath())!!
        assertEquals('E', n.language)
        assertEquals(listOf("Bulbasaur", "Victini", "Genesect"), listOf(1, 494, 649).map { n.species(it) })
        assertEquals("Tackle", n.move(33))
        assertEquals("Potion", n.item(17))
        assertEquals(649 + 3, n.species.size)
    }

    @Test
    fun `the Town Map - the map and its sprites, as the game draws them`() {
        assumeTrue("no Black ROM at $rom", rom.isFile)
        val dir = kotlin.io.path.createTempDirectory("nds-art").toFile()
        val out = File("build/nds-art").apply { mkdirs() }
        try {
            assertTrue(NdsArt.extractTo(rom.toOkioPath(), dir.toOkioPath()))
            // Checked against the game's own Town Map headless (nds_dump, the user's save): the picture pixel for pixel
            // where no sprite is, each sprite where the game puts it.
            val golden = mapOf(
                NdsArt.BLACK_MAP to Triple(256, 168, 465150285L),
                NdsArt.blackTown(0) to Triple(16, 16, 60101346L),        // a town arrived at: its blue middle
                NdsArt.blackTown(3) to Triple(16, 16, 1394109110L),      // Victory Road's small one
                NdsArt.blackTown(4) to Triple(16, 16, 2891058625L),      // Liberty Garden's island
                NdsArt.blackTown(5) to Triple(16, 16, 1386196619L),      // Unity Tower: its ring and middle
                NdsArt.BLACK_PLAYER to Triple(48, 48, 1927940204L),      // the figure and the red arrow
                NdsArt.blackCursor(0) to Triple(32, 32, 3423530032L),
                NdsArt.blackCursor(1) to Triple(32, 32, 851329966L),
            )
            for ((name, want) in golden) {
                val f = File(dir, "regionmap/$name.png")
                assertTrue("$f", f.isFile)
                f.copyTo(File(out, "bw-map-$name.png"), true)
                assertEquals(name, want, pixels(f))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
