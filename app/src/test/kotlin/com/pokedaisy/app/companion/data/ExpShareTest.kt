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
 * EXP SHARE's places, found by agbcc's Cmd_getexp ([ExpShare.find]): gSentPokesToOpponent and the two divisions FULL EXP
 * turns into `nop`s. FireRed rev 1 pinned at what was played headless; every FireRed / LeafGreen / Emerald cart must have
 * them. Skips a ROM that isn't here.
 */
class ExpShareTest {
    private fun hex(v: Long) = "%08X".format(v)

    @Test
    fun retailFireRedRev1AndEmerald() {
        File(RETAIL_ROM_DIR + "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba").takeIf { it.isFile }?.let { rom ->
            val f = ExpShare.find(rom.toOkioPath())!!
            assertEquals("02023F4E", hex(f.sentPokes))
            assertEquals(listOf("08021C10", "08021C50"), f.full.map { hex(it.addr) })
            // Each a `bl` (the division), turned into two `nop`s.
            f.full.forEach { assertEquals(0xF0, it.expect[1].toInt() and 0xF8); assertEquals(0xF8, it.expect[3].toInt() and 0xF8) }
        }
        File(RETAIL_ROM_DIR + "Pokemon - Emerald Version (USA, Europe).gba").takeIf { it.isFile }?.let { rom ->
            val f = ExpShare.find(rom.toOkioPath())!!
            assertEquals("020243FE", hex(f.sentPokes))
            assertEquals(listOf("0804A4C0", "0804A500"), f.full.map { hex(it.addr) })
        }
    }

    @Test
    fun hacks() {
        File(RETAIL_ROM_DIR + "Pokemon Radical Red (v4.1).gba").takeIf { it.isFile }?.let { assertNotNull(ExpShare.find(it.toOkioPath())) }
        // Rewrote getexp's divisions / built with gcc: none.
        File(RETAIL_ROM_DIR + "Pokemon Unbound (v2.1.1.1).gba").takeIf { it.isFile }?.let { assertNull(ExpShare.find(it.toOkioPath())) }
        File(RETAIL_ROM_DIR + "Pokemon Lazarus (v2.0).gba").takeIf { it.isFile }?.let { assertNull(ExpShare.find(it.toOkioPath())) }
    }

    /** Every FireRed / LeafGreen / Emerald cart in the retail ROM dir (-DmultiRomDir=...). */
    @Test
    fun everyRetailBuild() {
        val dir = File(System.getProperty("multiRomDir") ?: RETAIL_ROM_DIR)
        assumeTrue(dir.isDirectory)
        var checked = 0
        for (f in retailRomFiles(dir).filter { listOf("Pirate", "Debug").none { x -> x in it.name } }) {
            val code = f.inputStream().use { ByteArray(0xB0).also { b -> it.read(b) } }.let { String(it, 0xAC, 4) }
            if (code.startsWith("AXV") || code.startsWith("AXP")) continue   // Ruby / Sapphire build getexp another way
            assertNotNull(f.name, ExpShare.find(f.toOkioPath()))
            checked++
        }
        assertTrue("checked $checked", checked >= 15)
    }

    @Test
    fun maskLeavesEggsOut() {
        val mon = MonView(species = 25, name = "A", level = 5, hp = 10, maxHp = 10, status = "", types = emptyList(), iconAsset = null)
        assertEquals(0b101101, ExpShare.maskOf(listOf(mon, mon.copy(isEgg = true), mon, mon, mon.copy(isEgg = true), mon)))
        assertNull(ExpShare.find(LittleEndianBytes(ByteArray(0x1000))))
    }
}
