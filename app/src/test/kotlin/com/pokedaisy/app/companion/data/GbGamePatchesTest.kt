package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** INSTANT TEXT, REUSABLE TMS and LOW HP BEEP on the Game Boy carts ([GbGamePatches]): where pret's symbols put the code. */
class GbGamePatchesTest {
    private val dir = File(File(RETAIL_ROM_DIR).parentFile, "gbc")
    private fun rom(name: String) = File(dir, name).takeIf { it.isFile }?.readBytes()
    private val tag = GbGamePatches.TAG

    @Test
    fun `Yellow - PrintLetterDelay, ItemUseTMHM's ret c, the alarm's set`() {
        val f = GbGamePatches.find(rom("Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb") ?: return assumeTrue(false))!!
        assertEquals(tag + 0x38C8, f.text!!.writes.single().addr)                       // 00:38C8 PrintLetterDelay
        assertEquals(tag + 0xE47F, f.tms.single().branch)                               // 03:6374 ItemUseTMHM's end
        assertEquals(0xC9.toByte(), f.tms.single().value.single())
        assertEquals(tag + 0x3CEAE, f.beep!!.writes.single().addr)                      // 0F:4EAB .setLowHealthAlarm + 3
        assertEquals(tag + 0xC407, f.poison!!.writes.single().addr)                     // its jp nz, in ApplyOutOfBattlePoisonDamage
    }

    @Test
    fun `Crystal and Gold - their own TM and alarm code`() {
        rom("Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc")?.let { r ->
            val f = GbGamePatches.find(r)!!
            assertEquals(tag + 0x313D, f.text!!.writes.single().addr)                   // 00:313D PrintLetterDelay
            assertEquals(tag + 0x2C8B1, f.tms.single().branch)                          // call ConsumeTM
            assertEquals(3, f.tms.single().value.size)
            assertEquals(tag + 0x3DFBC, f.beep!!.writes.single().addr)                  // 0F:5FB9 CheckDanger.danger + 3
        }
        rom("Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc")?.let { r ->
            val f = GbGamePatches.find(r)!!
            assertEquals(tag + 0x31E2, f.text!!.writes.single().addr)                   // 00:31E2 PrintLetterDelay
            assertEquals(tag + 0x3DE1D, f.beep!!.writes.single().addr)                  // 0F:5E1A CheckDanger.danger + 3
        }
    }

    @Test
    fun `every Game Boy cart here - all three or the ones it has, never a guess`() {
        assumeTrue(dir.isDirectory)
        var n = 0
        dir.listFiles()!!.filter { it.extension == "gb" || it.extension == "gbc" }.forEach { file ->
            val f = GbGamePatches.find(file.readBytes()) ?: return@forEach
            val polished = "Polished" in file.name || "Prism" in file.name
            if (polished) assertTrue(file.name, f.tms.isEmpty() && f.text == null && f.beep == null)
            if ("Pokemon - " in file.name) assertNotNull(file.name, f.poison)
            if ("Pokemon - " in file.name) {
                assertNotNull(file.name, f.text); assertNotNull(file.name, f.beep); assertEquals(file.name, 1, f.tms.size)
                n++
            }
            if ("PureRed" in file.name || "PureBlue" in file.name) assertNull(file.name, f.text)   // three look-alikes
        }
        assertTrue("checked $n", n >= 12)
    }
}
