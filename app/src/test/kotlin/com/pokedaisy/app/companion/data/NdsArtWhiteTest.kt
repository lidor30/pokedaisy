package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pokémon White's art and DEX from its cart (NdsArt): the same art as Black's, written to Black's paths, and its own
 * DEX pages ([NdsArt.WHITE_DEX]: White's descriptions, the rest of each page Black's). Skips without both ROMs
 * (`-DromDir=`'s ../nds/).
 */
class NdsArtWhiteTest {
    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")
    private val black = File(nds, "Pokemon - Black Version (USA, Europe) (NDSi Enhanced).nds")
    private val white = File(nds, "Pokemon - White Version (USA, Europe) (NDSi Enhanced).nds")

    @Test
    fun `White's art is Black's, its DEX descriptions its own`() {
        assumeTrue("no Black / White ROM in $nds", black.isFile && white.isFile)
        val bDir = kotlin.io.path.createTempDirectory("nds-art-b").toFile()
        val wDir = kotlin.io.path.createTempDirectory("nds-art-w").toFile()
        try {
            assertTrue(NdsArt.extractTo(black, bDir))
            assertTrue(NdsArt.extractTo(white, wDir))
            // the art: file for file
            for (path in listOf("${NdsArt.BLACK_ICONS}/495.png", "${NdsArt.BLACK_ITEMS}/17.png", "${NdsArt.BLACK_FRONT}/643.png",
                "regionmap/${NdsArt.BLACK_MAP}.png", "regionmap/${NdsArt.BLACK_PLAYER}.png")) {
                assertTrue(path, File(wDir, path).readBytes().contentEquals(File(bDir, path).readBytes()))
            }
            assertTrue(File(wDir, NdsArt.BLACK_DEX).exists().not())
            // the pages: White's descriptions, everything else Black's
            val b = File(bDir, NdsArt.BLACK_DEX).readLines().map { it.split('\t') }
            val w = File(wDir, NdsArt.WHITE_DEX).readLines().map { it.split('\t') }
            assertEquals(649, w.size)
            fun noText(page: List<String>) = page.filterIndexed { i, _ -> i != 2 }
            assertEquals(b.map(::noText), w.map(::noText))
            assertNotEquals(b[494][2], w[494][2])   // SNIVY's entry
            // the names: White's cart reads as Black's
            assertEquals("Snivy", NdsArt.readNames(white)?.species(495))
            // a second run on the same cart writes nothing
            assertEquals(false, NdsArt.extractTo(white, wDir))
        } finally {
            bDir.deleteRecursively()
            wDir.deleteRecursively()
        }
    }
}
