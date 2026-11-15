package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * The POKéMON CENTER's specials, found in each ROM's gSpecials by their code ([PokemonCenter.find]). FireRed rev 1 and
 * Emerald pinned at what was played headless (heal, rename, forget, the relearner from right after a loaded save); every
 * retail cart must have all four. Skips a ROM that isn't here.
 */
class PokemonCenterTest {
    private fun hex(v: Long?) = v?.let { "%08X".format(it) }

    @Test
    fun retailFireRedRev1() {
        val rom = File(RETAIL_ROM_DIR + "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba")
        if (!rom.isFile) return
        val c = PokemonCenter.find(rom.toOkioPath())!!
        assertEquals("0815FDD8", hex(c.specials))
        assertEquals(0, c.heal)
        assertEquals(158, c.nickname)
        assertEquals(221, c.forget)
        assertEquals(224, c.relearn?.index)
        assertEquals("080E47A1", hex(c.relearn?.init))
        assertEquals("03005020", hex(c.relearn?.fieldCallback))
        assertEquals("0807DD39", hex(c.relearn?.continueScript))
        assertEquals("020370C0", hex(c.var8004))
        assertEquals("020370D0", hex(c.varResult))
        assertEquals("080CC1B4", hex(c.otIdNotPlayers))
        assertEquals("080F6F14", hex(c.windowFrame))
        assertEquals(256 to 270, c.healSong to c.forgetSong)
        // The PC's table is the same gSpecials.
        assertEquals(hex(PC_FIRERED_REV1.specials), hex(c.specials))
    }

    @Test
    fun retailEmerald() {
        val rom = File(RETAIL_ROM_DIR + "Pokemon - Emerald Version (USA, Europe).gba")
        if (!rom.isFile) return
        val c = PokemonCenter.find(rom.toOkioPath())!!
        assertEquals(hex(PC_EMERALD.specials), hex(c.specials))
        assertEquals(161, c.nickname)
        assertEquals(224, c.forget)
        assertEquals(227, c.relearn?.index)
        assertEquals("081606A1", hex(c.relearn?.init))
        assertEquals("03005DAC", hex(c.relearn?.fieldCallback))
        assertEquals("080AF169", hex(c.relearn?.continueScript))
        assertEquals("020375E0", hex(c.var8004))
        assertNull(c.windowFrame)   // no recap in Emerald: the relearner's palette is always there
        assertEquals(368 to 378, c.healSong to c.forgetSong)
    }

    @Test
    fun hacks() {
        // CFRU rewrote ChangePokemonNickname: the rest stays.
        File(RETAIL_ROM_DIR + "Pokemon Unbound (v2.1.1.1).gba").takeIf { it.isFile }?.let { rom ->
            val c = PokemonCenter.find(rom.toOkioPath())
            assertNotNull(c)
            assertNull(c!!.nickname)
            assertNotNull(c.forget)
        }
        // gcc builds: another shape, nothing.
        File(RETAIL_ROM_DIR + "Pokemon Lazarus (v2.0).gba").takeIf { it.isFile }?.let { assertNull(PokemonCenter.find(it.toOkioPath())) }
    }

    /** Every retail cart in the retail ROM dir (-DmultiRomDir=...): all four, in the PC's own gSpecials. */
    @Test
    fun everyRetailBuild() {
        val dir = File(System.getProperty("multiRomDir") ?: RETAIL_ROM_DIR)
        assumeTrue(dir.isDirectory)
        var checked = 0
        for (f in retailRomFiles(dir).filter { listOf("Pirate", "Debug").none { x -> x in it.name } }) {
            val c = PokemonCenter.find(f.toOkioPath())
            assertNotNull(f.name, c)
            c!!
            assertNotNull(f.name, c.nickname)
            assertNotNull(f.name, c.forget)
            assertNotNull(f.name, c.relearn)
            val h = f.inputStream().use { ByteArray(0xC0).also { b -> it.read(b) } }
            fieldItemsFor(String(h, 0xAC, 4), h[0xBC].toInt())?.pc?.let { assertEquals(f.name, hex(it.specials), hex(c.specials)) }
            checked++
        }
        assertTrue("checked $checked", checked >= 30)
    }

    @Test
    fun nothingInAnEmptyRom() {
        assertNull(PokemonCenter.find(LittleEndianBytes(ByteArray(0x10000))))
    }
}
