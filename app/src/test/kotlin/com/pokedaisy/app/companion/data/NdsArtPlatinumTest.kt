package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Platinum's DEX rebuilt from the player's own cart (NdsArt): a page a species, the Sinnoh order, evolutions, level-up
 * moves, TMs / HMs and front pictures - a few written to app/build/nds-art/ to look at. Skips without the ROM
 * (`-DromDir=`'s ../nds/).
 */
class NdsArtPlatinumTest {
    private val rom = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon - Platinum Version (USA) (Rev 1).nds")

    @Test
    fun `the DEX's pages, Sinnoh order and pictures`() {
        assumeTrue("no Platinum ROM at $rom", rom.isFile)
        val dir = kotlin.io.path.createTempDirectory("nds-art").toFile()
        try {
            assertTrue(NdsArt.extractTo(rom.toOkioPath(), dir))
            val dex = File(dir, NdsArt.PLATINUM_DEX).readLines()
            assertEquals(493, dex.size)
            val bulbasaur = dex.first().split('\t')
            assertEquals("1", bulbasaur[0])
            assertEquals(listOf("7", "69"), bulbasaur.subList(3, 5))   // 0.7 m, 6.9 kg
            assertEquals(listOf("45", "49", "49", "65", "65", "45"), bulbasaur.subList(5, 11))
            assertEquals("Overgrow", bulbasaur[17])
            assertTrue(bulbasaur[2].isNotBlank())
            val pages = Gen4Dex.parse(dex)
            assertEquals("Seed", pages.entries[1]?.category)
            assertEquals(210, pages.regional.size)
            assertEquals(listOf(387, 388, 389, 390), pages.regional.take(4))   // TURTWIG first
            assertEquals(listOf(EvoLink(387, 388, listOf(EvoReq.Level(18)))), pages.evolutions[387])
            assertEquals(LevelMove(1, 33), pages.levelUp[387]?.first())          // TACKLE
            // MAGNETON: a level-up in the magnetic field (Platinum's Mt. Coronet) to MAGNEZONE
            assertEquals(listOf(EvoLink(82, 462, listOf(EvoReq.LevelUp, EvoReq.SomePlace))), pages.evolutions[82])
            // Platinum's HM05 is DEFOG (HG / SS's is WHIRLPOOL): STARLY learns it
            val starly = pages.machines[396]!!
            assertTrue((starly[(92 + 4) / 8].toInt() shr ((92 + 4) % 8)) and 1 != 0)
            assertEquals(432, tmMovesPlatinum[96])                                 // DEFOG
            val out = File("build/nds-art").apply { mkdirs() }
            for (species in listOf(387, 487)) {
                val f = File(dir, "${NdsArt.PLATINUM_FRONT}/$species.png")
                assertTrue("$f", f.isFile)
                f.copyTo(File(out, "pt-front-$species.png"), true)
            }
            File(out, "pt-dex.tsv").writeText(dex.take(3).joinToString("\n"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
