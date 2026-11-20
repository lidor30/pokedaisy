package com.pokedaisy.app.companion.data

import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** LOW HP BEEP / FIELD POISON's places ([QuietPatches]): pinned on FireRed rev 1 and Emerald at what was played headless,
 * and found in every FireRed / LeafGreen / Emerald / Ruby / Sapphire cart. Skips a ROM that isn't here. */
class QuietPatchesTest {
    private fun hex(v: Long) = "%08X".format(v)

    @Test
    fun retailFireRedRev1AndEmerald() {
        File(RETAIL_ROM_DIR + "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba").takeIf { it.isFile }?.let { rom ->
            val f = QuietPatches.find(rom.toOkioPath())!!
            assertEquals("080352BA", hex(f.beep!!.writes.single().addr))
            assertEquals("0806D7D8", hex(f.poison!!.writes.single().addr))
        }
        File(RETAIL_ROM_DIR + "Pokemon - Emerald Version (USA, Europe).gba").takeIf { it.isFile }?.let { rom ->
            val f = QuietPatches.find(rom.toOkioPath())!!
            assertEquals("0805E9F2", hex(f.beep!!.writes.single().addr))
            assertEquals("0809CBB6", hex(f.poison!!.writes.single().addr))
        }
    }

    @Test
    fun everyRetailBuild() {
        val dir = File(System.getProperty("multiRomDir") ?: RETAIL_ROM_DIR)
        assumeTrue(dir.isDirectory)
        var checked = 0
        for (f in retailRomFiles(dir).filter { listOf("Pirate", "Debug").none { x -> x in it.name } }) {
            val found = QuietPatches.find(f.toOkioPath())
            assertNotNull(f.name, found?.beep)
            assertNotNull(f.name, found?.poison)
            checked++
        }
        assertTrue("checked $checked", checked >= 15)
    }

    @Test
    fun nothingInAnEmptyRom() {
        assertNull(QuietPatches.find(LittleEndianBytes(ByteArray(0x1000))))
    }
}
