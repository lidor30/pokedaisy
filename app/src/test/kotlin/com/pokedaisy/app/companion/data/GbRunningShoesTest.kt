package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** RUNNING SHOES ([GbGamePatches.Found.run]): the hook and trampoline in each cart, N/A where the code or the room differs. */
class GbRunningShoesTest {
    private val dir = File(File(RETAIL_ROM_DIR).parentFile, "gbc")
    private fun rom(name: String) = File(dir, name).takeIf { it.isFile }?.readBytes()
    private val tag = GbGamePatches.TAG

    /** (trampoline's cart offset, hook's cart offset), or null. */
    private fun run(name: String): Pair<Long, Long>? {
        val r = rom(name) ?: return null.also { assumeTrue("$name not on this machine", false) }
        val p = GbGamePatches.find(r)?.run ?: return null
        // 4 bytes at most a write (pk_cheats_patch refuses more on a Game Boy cart), each expecting its own bytes.
        assertEquals(true, p.writes.all { it.value.size in 1..4 && it.expect.size == it.value.size })
        // The trampoline's pieces first (the cart's padding), then the hook, a jump to the trampoline's start.
        // (The hook's first byte was the game's `ld a, [nn]`, FA; the trampoline's place held padding.)
        val hook = p.writes.first { (it.expect[0].toInt() and 0xFF) == 0xFA }
        assertEquals(0xC3, hook.value[0].toInt() and 0xFF)
        val pad = p.writes.takeWhile { it !== hook }
        assertEquals(true, pad.all { w -> w.expect.all { it == pad[0].expect[0] && (it.toInt() == 0 || it.toInt() == -1) } })
        return (pad[0].addr - tag) to (hook.addr - tag)
    }

    @Test fun `Red and Blue - moveAhead2's bike check, 21 bytes at bank 0's end`() {
        assertEquals(0x3FEBL to 0x05CCL, run("Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb"))
        assertEquals(0x3FEBL to 0x05CCL, run("Pokemon - Blue Version (USA, Europe) (SGB Enhanced).gb"))
        val p = GbGamePatches.find(rom("Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb")!!)!!.run!!
        // wWalkBikeSurfState D700, hJoyHeld B4, the ledge check at 05D2, .normalPlayerSpriteAdvancement at 05DC.
        val tramp = p.writes.filter { it.addr - tag >= 0x3FEB }.flatMap { it.value.toList() }.toByteArray()
        assertArrayEquals(byteArrayOf(0xFA.toByte(), 0x00, 0xD7.toByte(), 0x3D, 0x28, 9, 0x3C, 0x20, 9, 0xF0.toByte(), 0xB4.toByte(),
            0xE6.toByte(), 0x02, 0x28, 3, 0xC3.toByte(), 0xD2.toByte(), 0x05, 0xC3.toByte(), 0xDC.toByte(), 0x05), tramp)
        val hook = p.writes.filter { it.addr - tag in 0x05CC until 0x05D2 }.flatMap { it.value.toList() }.toByteArray()
        assertArrayEquals(byteArrayOf(0xC3.toByte(), 0xEB.toByte(), 0x3F, 0, 0, 0), hook)
    }

    @Test fun `Yellow - DoBikeSpeedup's own check, 16 bytes of its 17`() {
        assertEquals(0x3FF0L to 0x049DL, run("Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb"))
    }

    @Test fun `Gold, Silver and Crystal - BikeCheck, 18 bytes at its bank's end`() {
        assertEquals(0x13FEEL to 0x103CAL, run("Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc"))
        assertEquals(0x13FEEL to 0x103CAL, run("Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc"))
        for (c in listOf("Pokemon - Crystal Version (USA).gbc", "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc", "Pokemon - Crystal Version (Australia).gbc"))
            assertEquals(c, 0x83FEEL to 0x803CAL, run(c))
    }

    @Test fun `N A where the room or the code differs`() {
        for (c in listOf(
            "Pokemon - Edicion Amarilla - Edicion Especial Pikachu (Spain) (GBC,SGB Enhanced).gb",
            "Pokemon - Gelbe Edition - Special Pikachu Edition (Germany) (GBC,SGB Enhanced).gb",
            "Pokemon - Version Jaune - Edition Speciale Pikachu (France) (GBC,SGB Enhanced).gb",
            "Pokemon Brown (v6.1.2).gb", "Pokemon PureRed (v2.7.6).gb", "Pokemon Yellow Legacy (v1.0.10).gb",
            "Pokemon Crystal Clear (v2.6.0).gbc", "Pokemon Prism (v0.95 build 254 Hotfix 5).gbc", "Pokemon Polished Crystal (v3.2.3).gbc",
        )) {
            val r = rom(c) ?: continue
            assertNull(c, GbGamePatches.find(r)?.run)
        }
    }
}
