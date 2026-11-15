package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * HeartGold's art rebuilt from the player's own cart (NdsArt): the party and bag icons, the Pokégear map's pieces and
 * heads, and the map put together for two states ([PokegearMap.compose]) - written to app/build/nds-art/ to look at.
 * Skips without the ROM (`-DromDir=`'s ../nds/).
 */
class NdsArtHeartGoldTest {
    private val rom = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon - HeartGold Version (USA).nds")

    @Test
    fun `icons, the Pokegear map's pieces and heads, and the map for a state`() {
        assumeTrue("no HeartGold ROM at $rom", rom.isFile)
        val dir = kotlin.io.path.createTempDirectory("nds-art").toFile()
        try {
            assertTrue(NdsArt.extractTo(rom.toOkioPath(), dir.toOkioPath()))
            assertTrue(File(dir, "${NdsArt.HEARTGOLD_ICONS}/152.png").isFile)          // CHIKORITA
            assertTrue(File(dir, "${NdsArt.HEARTGOLD_ITEMS}/17.png").isFile)           // Potion
            assertTrue(File(dir, "${NdsArt.HEARTGOLD_ITEMS}/468.png").isFile)          // Apricorn Box (HG/SS's own)
            for (head in listOf(PokegearMap.HEAD_M, PokegearMap.HEAD_F)) assertTrue(File(dir, "regionmap/$head.png").isFile)
            val tiles = File(dir, PokegearMap.TILES).readBytes()
            val tilemap = File(dir, PokegearMap.TILEMAP).readBytes()
            val pal = File(dir, PokegearMap.palette(0)).readBytes()
            assertEquals(64 * 64 * 2, tilemap.size)
            val everything = PokegearMap.compose(tiles, tilemap, pal, unlock = 2) { true }
            val start = PokegearMap.compose(tiles, tilemap, pal, unlock = 0) { false }
            assertEquals(PokegearMap.W * 8 to PokegearMap.H * 8, everything.width to everything.height)
            assertNotEquals(everything.argb.toList(), start.argb.toList())
            val out = File("build/nds-art").apply { mkdirs() }
            File(out, "pokegear-all.png").writeBytes(everything.png())
            File(out, "pokegear-start.png").writeBytes(start.png())
            // the bag's eight pocket buttons, open and not, one size
            val sizes = (0 until 8).flatMap { p -> listOf(true, false).map { open ->
                val f = File(dir, "${NdsArt.heartGoldPocket(p, open)}.png")
                assertTrue("$f", f.isFile)
                f.copyTo(File(out, "pocket_${p}_${if (open) "on" else "off"}.png"), true)
                // the PNG's IHDR: width, height
                java.nio.ByteBuffer.wrap(f.readBytes(), 16, 8).let { it.int to it.int }
            } }.toSet()
            assertEquals(1, sizes.size)
            for (frame in 0..1) assertTrue(File(dir, "regionmap/${PokegearMap.cursor(frame)}.png").isFile)
            // the DEX: a page a species, from the cart's own text and data
            val dex = File(dir, NdsArt.HEARTGOLD_DEX).readLines()
            assertEquals(493, dex.size)
            val bulbasaur = dex.first().split('\t')
            assertEquals(listOf("1", "Seed Pokémon"), bulbasaur.take(2))
            assertEquals(listOf("7", "69"), bulbasaur.subList(3, 5))   // 0.7 m, 6.9 kg
            assertEquals(listOf("45", "49", "49", "65", "65", "45"), bulbasaur.subList(5, 11))
            assertEquals("Overgrow", bulbasaur[17])
            assertTrue(bulbasaur[2].isNotBlank())
            // ... its Johto order, evolutions, level-up moves and TMs, as the companion reads them
            val pages = Gen4Dex.parse(dex)
            assertEquals(256, pages.regional.size)
            assertEquals(listOf(152, 153, 154, 155), pages.regional.take(4))       // CHIKORITA first
            assertEquals(listOf(EvoLink(1, 2, listOf(EvoReq.Level(16)))), pages.evolutions[1])
            assertEquals(LevelMove(1, 33), pages.levelUp[1]?.first())           // TACKLE
            val eevee = pages.evolutions[133].orEmpty().map { it.to }.toSet()
            assertEquals(setOf(134, 135, 136, 196, 197, 470, 471), eevee)
            val tms = pages.machines[1]!!
            assertTrue((tms[0].toInt() shr 5) and 1 != 0)                       // TM06 (TOXIC)
            File(dir, "${NdsArt.HEARTGOLD_FRONT}/1.png").copyTo(File(out, "front-1.png"), true)
            File(dir, "${NdsArt.HEARTGOLD_FRONT}/154.png").copyTo(File(out, "front-154.png"), true)
            File(out, "dex.tsv").writeText(dex.take(3).joinToString("\n"))
            File(dir, "${NdsArt.HEARTGOLD_ITEMS}/17.png").copyTo(File(out, "item-potion.png"), true)
            File(dir, "regionmap/${PokegearMap.HEAD_F}.png").copyTo(File(out, "head-lyra.png"), true)
        } finally {
            dir.deleteRecursively()
        }
    }
}
