package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Red Gen II Graphics V2.0 and Yellow Gen II Graphics V1.1 (#802) on retail Red's / Yellow's saves, booted in the
 * patched carts headless (the red_gen2_graphics / yellow_gen2_graphics fixtures' READMEs): the patches leave the RAM
 * alone, so each reads exactly as the retail fixture of the same save does.
 */
class Gen2GraphicsDecodeTest {
    private val gbc = File(RETAIL_ROM_DIR).parentFile?.let { File(it, "gbc") }

    @Test
    fun `the carts are recognised by their hashes`() {
        // (Red's hash goes to GEN1_RED_GEN2_GRAPHICS in detectGameBoy itself, beside retail Red's.)
        assertSame(GEN1_YELLOW_GEN2_GRAPHICS, TelemetrySampler.gen1ConfigFor(TelemetrySampler.YELLOW_GEN2_GRAPHICS_V1_1_SHA1))
        assertTrue(TelemetrySampler.RED_GEN2_GRAPHICS_V2_0_SHA1 in TelemetrySampler.SUPPORTED_GB_SHA1S)
        assertTrue(TelemetrySampler.YELLOW_GEN2_GRAPHICS_V1_1_SHA1 in TelemetrySampler.SUPPORTED_GB_SHA1S)
    }

    private fun sameAsRetail(game: GameKind, retail: Pair<String, Gen1Config>, hack: Pair<String, Gen1Config>) {
        activeGame = game
        val a = readGen1Telemetry(FixtureMemoryReader.load(retail.first), retail.second)
        val b = readGen1Telemetry(FixtureMemoryReader.load(hack.first), hack.second)
        assertEquals(6, b.party.size)
        assertEquals(a.party.map { it.species }, b.party.map { it.species })
        assertEquals(a.party.map { it.nickname }, b.party.map { it.nickname })
        assertEquals(a.party.map { it.level to it.hp }, b.party.map { it.level to it.hp })
        assertEquals(a.party.map { it.moves.toList() }, b.party.map { it.moves.toList() })
        assertEquals(a.money, b.money)
        assertEquals(a.items, b.items)
        assertEquals(a.regionMapSectionId, b.regionMapSectionId)
        assertEquals(a.pokedex!!.caught to a.pokedex!!.seen, b.pokedex!!.caught to b.pokedex!!.seen)
    }

    @Test
    fun `Red Gen II Graphics reads as retail Red`() =
        sameAsRetail(GameKind.RED, "red" to GEN1_RED, "red_gen2_graphics" to GEN1_RED_GEN2_GRAPHICS)

    @Test
    fun `Yellow Gen II Graphics reads as retail Yellow`() =
        sameAsRetail(GameKind.YELLOW, "yellow" to GEN1_YELLOW, "yellow_gen2_graphics" to GEN1_YELLOW_GEN2_GRAPHICS)

    /** STEADY music: Yellow's patch moved bank 0's code from 0x1400 on 0x12 earlier, so PlayMusic and the park did too. */
    @Test
    fun `the music addresses are the carts' own`() {
        val dir = gbc
        val yellow = dir?.let { File(it, "Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb") }
        val yellowG2 = dir?.let { File(it, "Pokemon Yellow Gen II Graphics (v1.1).gb") }
        val red = dir?.let { File(it, "Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb") }
        val redG2 = dir?.let { File(it, "Pokemon Red Gen II Graphics (v2.0).gb") }
        assumeTrue("the ROMs aren't on this machine", listOf(yellow, yellowG2, red, redG2).all { it?.isFile == true })
        val y = yellow!!.readBytes(); val yg = yellowG2!!.readBytes(); val r = red!!.readBytes(); val rg = redG2!!.readBytes()
        fun at(rom: ByteArray, addr: Int, n: Int) = rom.copyOfRange(addr, addr + n)
        // PlayMusic: the same code as retail's, where the config says.
        assertArrayEquals(at(y, GEN1_MUSIC_YELLOW.playMusic, 48), at(yg, GEN1_MUSIC_YELLOW_GEN2_GRAPHICS.playMusic, 48))
        assertArrayEquals(at(r, GEN1_MUSIC_RED.playMusic, 48), at(rg, GEN1_MUSIC_RED_GEN2_GRAPHICS.playMusic, 48))
        // The park: `18 FE` (jr @) in the same `jp z` operand as retail's.
        assertArrayEquals(byteArrayOf(0x18, 0xFE.toByte()), at(yg, GEN1_MUSIC_YELLOW_GEN2_GRAPHICS.spin, 2))
        assertArrayEquals(at(y, GEN1_MUSIC_YELLOW.spin - 3, 3).copyOfRange(0, 1), at(yg, GEN1_MUSIC_YELLOW_GEN2_GRAPHICS.spin - 3, 3).copyOfRange(0, 1))
    }
}
