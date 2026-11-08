package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The PC's whole menu, copied from each retail ROM ([PcMenuScript]). */
class PcMenuScriptTest {
    private val dir = File(System.getProperty("romDir") ?: (System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba"))

    private fun rom(name: String): ByteArray {
        val f = File(dir, name)
        assumeTrue(f.isFile)
        return f.readBytes().copyOf(0x400000)
    }

    private fun script(name: String, special: Int): ByteArray {
        val rom = rom(name)
        val menu = PcMenuScript.find(rom, special)
        assertTrue(name, menu > 0)
        val s = PcMenuScript.build(rom, menu)
        assertNotNull(name, s)
        return s!!
    }

    private fun words(s: ByteArray) = (0 until s.size - 3).map { i -> (0..3).fold(0L) { v, k -> v or ((s[i + k].toLong() and 0xFF) shl (8 * k)) } }

    @Test fun everyRetailGameHasItsMenu() {
        for ((name, special) in listOf(
            "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba" to 60, "Pokemon - FireRed Version (USA).gba" to 60,
            "Pokemon - Emerald Version (USA, Europe).gba" to 63, "Pokemon - LeafGreen Version (USA).gba" to 60,
            "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba" to 60, "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba" to 60,
            "Pokemon - Sapphire Version (USA, Europe) (Rev 1).gba" to 60,
        )) {
            val s = script(name, special)
            assertEquals(0x69, s[0].toInt())                 // lockall, straight into the menu
            assertEquals(0x67, s[1].toInt())                 // message: which PC?
            assertTrue(name, s.size <= 0x2F0)
        }
    }

    @Test fun itsJumpsStayInTheCopy() {
        val s = script("Pokemon - FireRed Version (USA, Europe) (Rev 1).gba", 60)
        val ram = PcMenuScript.RAM until PcMenuScript.RAM + s.size
        assertTrue(words(s).any { it in ram })
        // The menu's own address: a jump left behind would run the ROM's exit, and its tile.
        assertFalse(words(s).any { it == 0x081A6A10L })
    }

    @Test fun leavingNeverRunsTheTurnOffSpecial() {
        val name = "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba"
        val rom = rom(name)
        val s = script(name, 60)
        val off = 0x1A6ABE   // EventScript_TurnOffPC: setvar, playse, special AnimatePcTurnOff, special ..., releaseall, end
        val turnOff = rom.copyOfRange(off + 8, off + 11)
        assertEquals(0x25, turnOff[0].toInt())
        assertFalse((0..s.size - 3).any { i -> s[i] == turnOff[0] && s[i + 1] == turnOff[1] && s[i + 2] == turnOff[2] })
        assertEquals(0x02, s.last().toInt())                 // ... releaseall; end
        assertEquals(0x6B, s[s.size - 2].toInt())
    }

    @Test fun aRomWithoutTheMenuGetsNone() {
        assertEquals(-1, PcMenuScript.find(ByteArray(0x1000), 60))
        assertNull(PcMenuScript.build(ByteArray(0x1000) { 0xEE.toByte() }, 0x10))
    }
}
