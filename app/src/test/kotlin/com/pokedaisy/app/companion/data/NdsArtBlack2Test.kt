package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pokémon Black 2's and White 2's art and DEX from their carts (NdsArt): the same files in both, written to Black 2's
 * paths, its names read from its own (renumbered) text banks. Skips without both ROMs (`-DromDir=`'s ../nds/). The
 * pictures land in app/build/nds-art-black2/ too, to look at.
 */
class NdsArtBlack2Test {
    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")
    private val black2 = File(nds, "Pokemon - Black Version 2 (USA, Europe) (NDSi Enhanced).nds")
    private val white2 = File(nds, "Pokemon - White Version 2 (USA, Europe) (NDSi Enhanced).nds")

    @Test
    fun `Black 2's art, DEX and names, White 2's the same`() {
        assumeTrue("no Black 2 / White 2 ROM in $nds", black2.isFile && white2.isFile)
        val bDir = kotlin.io.path.createTempDirectory("nds-art-b2").toFile()
        val wDir = kotlin.io.path.createTempDirectory("nds-art-w2").toFile()
        try {
            assertTrue(NdsArt.extractTo(black2, bDir))
            assertTrue(NdsArt.extractTo(white2, wDir))
            val paths = listOf("${NdsArt.BLACK2_ICONS}/495.png", "${NdsArt.BLACK2_ICONS}/${NdsArt.BLACK_EGG}.png",
                "${NdsArt.BLACK2_ITEMS}/17.png", "${NdsArt.BLACK2_ITEMS}/627.png", "${NdsArt.BLACK2_FRONT}/643.png",
                "regionmap/${NdsArt.BLACK2_MAP}.png", "regionmap/${NdsArt.BLACK2_PLAYER}.png",
                "regionmap/${NdsArt.black2Town(0)}.png", "regionmap/${NdsArt.black2Cursor(0)}.png", NdsArt.BLACK2_DEX)
            for (path in paths) {
                assertTrue(path, File(bDir, path).isFile)
                assertTrue(path, File(wDir, path).readBytes().contentEquals(File(bDir, path).readBytes()))
            }
            // Black's paths stay Black's.
            assertTrue(!File(bDir, NdsArt.BLACK_DEX).exists() && !File(bDir, "regionmap/${NdsArt.BLACK_MAP}.png").exists())
            val pages = File(bDir, NdsArt.BLACK2_DEX).readLines().map { it.split('\t') }
            assertEquals(649, pages.size)
            // SNIVY: a Grass Snake, its Unova Dex place (VICTINI 1), its hidden ability.
            val snivy = pages[494]
            assertEquals("495", snivy[0])
            assertEquals("2", snivy[19])
            // Each page's Unova Dex place is its place in [unovaDexBlack2] (older POKéMON among them), 0 when it's not in it.
            for (species in 1..649) {
                assertEquals("$species", (unovaDexBlack2.indexOf(species) + 1).toString(), pages[species - 1][19])
            }
            val names = NdsArt.readNames(black2)!!
            assertEquals("Snivy", names.species(495))
            assertEquals("Humilau City", names.place(mapPlaceIdsBlack2.getValue(465)))
            assertEquals("Medal Box", names.item(627))
            assertEquals("Medal Box", names.itemsMerged(false)[627])
            assertEquals('E', names.language)
            assertEquals(false, NdsArt.extractTo(black2, bDir))
            val look = File("build/nds-art-black2").apply { deleteRecursively(); mkdirs() }
            File(bDir, "regionmap").copyRecursively(File(look, "regionmap"), overwrite = true)
            for (s in listOf(495, 500, 643, 646)) File(bDir, "${NdsArt.BLACK2_FRONT}/$s.png").copyTo(File(look, "front_$s.png"), true)
            for (s in listOf(495, 500, 646, NdsArt.BLACK_EGG)) File(bDir, "${NdsArt.BLACK2_ICONS}/$s.png").copyTo(File(look, "icon_$s.png"), true)
            for (i in listOf(17, 442, 627, 638)) File(bDir, "${NdsArt.BLACK2_ITEMS}/$i.png").copyTo(File(look, "item_$i.png"), true)
        } finally {
            bDir.deleteRecursively()
            wDir.deleteRecursively()
        }
    }
}
