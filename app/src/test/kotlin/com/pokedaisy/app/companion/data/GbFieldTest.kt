package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * PC anywhere, FLY and WILD ENCOUNTERS on the Game Boy games (GbField.kt): the code they lean on is found in the
 * player's own carts (skipped without them) - every Crystal release, each Yellow and Red its own config's and no
 * other's (the European Yellows' RAM sits 5 bytes later, Red's 1) - and what they write, on a memory stand-in.
 */
class GbFieldTest {
    private val dir = File(File(RETAIL_ROM_DIR).parentFile, "gbc")

    private fun romOf(name: String): ByteArray {
        val f = File(dir, name)
        assumeTrue("$name not on this machine", f.isFile)
        return f.readBytes()
    }

    private fun GbField.matches(rom: ByteArray) =
        codeMatches { off, len -> if (off + len <= rom.size) rom.copyOfRange(off.toInt(), (off + len).toInt()) else null }

    @Test
    fun `Crystal's code is in every release`() {
        for (name in listOf(
            "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc",
            "Pokemon - Crystal Version (USA).gbc",
            "Pokemon - Crystal Version (Australia).gbc",
        )) assertTrue(name, GbField.Crystal.matches(romOf(name)))
    }

    @Test
    fun `each Gen 1 cart has its own config's code, and no other's`() {
        val carts = listOf(
            "Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb" to GB_FIELD_YELLOW,
            "Pokemon - Gelbe Edition - Special Pikachu Edition (Germany) (GBC,SGB Enhanced).gb" to GB_FIELD_YELLOW_DE,
            "Pokemon - Version Jaune - Edition Speciale Pikachu (France) (GBC,SGB Enhanced).gb" to GB_FIELD_YELLOW_FR,
            "Pokemon - Versione Gialla - Speciale Edizione Pikachu (Italy) (GBC,SGB Enhanced).gb" to GB_FIELD_YELLOW_IT,
            "Pokemon - Edicion Amarilla - Edicion Especial Pikachu (Spain) (GBC,SGB Enhanced).gb" to GB_FIELD_YELLOW_ES,
            "Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb" to GB_FIELD_RED_BLUE,
            "Pokemon - Rote Edition (Germany) (SGB Enhanced).gb" to GB_FIELD_RED_BLUE_DE,
            "Pokemon - Blaue Edition (Germany) (SGB Enhanced).gb" to GB_FIELD_RED_BLUE_DE,
            "Pokemon PureRed (v2.7.6).gb" to GB_FIELD_PURE_RGB,
        )
        // The carts on this machine: one that's missing (German Red / Blue here) mustn't skip the others' checks.
        var checked = 0
        for ((name, own) in carts) {
            val rom = File(dir, name).takeIf { it.isFile }?.readBytes() ?: continue
            for ((_, cfg) in carts) assertEquals(name, cfg === own, cfg.matches(rom))
            assertFalse(name, GbField.Crystal.matches(rom))
            checked++
        }
        assumeTrue("no Gen 1 cart on this machine", checked > 0)
    }

    /** 64 KB of bus, plus Color WRAM bank 1 at (1 << 16) | D000-DFFF, and a cart. */
    private class Memory(val rom: ByteArray = ByteArray(0x200000)) : GameCalls {
        val bus = ByteArray(0x10000)
        val bank1 = ByteArray(0x1000)
        var calledAt: List<Int>? = null
        private fun at(addr: Long): Pair<ByteArray, Int> =
            if (addr >= 0x10000) bank1 to (addr and 0xFFFF).toInt() - 0xD000 else bus to addr.toInt()
        operator fun get(addr: Long) = at(addr).let { (m, i) -> m[i].toInt() and 0xFF }
        operator fun set(addr: Long, v: Int) = at(addr).let { (m, i) -> m[i] = v.toByte() }
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = -1L
        override fun read(addr: Long, len: Int) = ByteArray(len) { get(addr + it).toByte() }
        override fun write(addr: Long, bytes: ByteArray): Boolean { bytes.forEachIndexed { i, b -> set(addr + i, b.toInt()) }; return true }
        override fun rom(off: Long, len: Int) = rom.copyOfRange(off.toInt(), off.toInt() + len)
        override fun gbCallAt(at: Int, fn: Int, a: Int, b: Int, hl: Int): Boolean { calledAt = listOf(at, fn, a, b, hl); return true }
    }

    @Test
    fun `Yellow - the PC tile's routine through Bankswitch, from the overworld loop`() {
        val m = Memory()
        assertEquals(ItemUseResult.USED, GB_FIELD_YELLOW.openPc(m))
        assertEquals(listOf(0x0248, 0x3E84, 0, 0x18, 0x66F4), m.calledAt)
        m.calledAt = null
        m[0xCFC4] = 4   // mid-step
        assertEquals(ItemUseResult.NOT_NOW, GB_FIELD_YELLOW.openPc(m))
        assertEquals(null, m.calledAt)
    }

    @Test
    fun `Yellow - FLY lists the visited towns and sets the fly warp, withdrawn if the loop never takes it`() {
        val m = Memory()
        assertEquals(FlyResult.NO_BADGE, GB_FIELD_YELLOW.flyTargets(m).result)
        m[0xD355] = 0x04                                   // the Thunder Badge
        assertEquals(FlyResult.NO_FLYER, GB_FIELD_YELLOW.flyTargets(m).result)
        m[0xD162] = 2
        m[0xD172 + 44 + 1] = 19                            // the second Pokémon's second move: FLY
        m[0xD366] = 3                                      // a building's tileset
        assertEquals(FlyResult.CANT_FLY_HERE, GB_FIELD_YELLOW.flyTargets(m).result)
        m[0xD366] = 0
        m[0xD70A] = 0b0010_0011                            // Pallet, Viridian, Vermilion
        m[0xD70B] = 0b100                                  // Saffron (10)
        val t = GB_FIELD_YELLOW.flyTargets(m)
        assertEquals(FlyTargets(FlyResult.OK, listOf(0, 1, 5, 10), 1), t)
        assertEquals(FlyResult.FAILED, GB_FIELD_YELLOW.fly(3, t, m))
        m[0xD731] = 0x01                                   // the game timer bit stays
        m[0xC1F2] = 0x12
        assertEquals(FlyResult.OK, GB_FIELD_YELLOW.fly(5, t, m))
        assertEquals(5, m[0xD719])
        assertEquals(0x09, m[0xD731])
        assertEquals(0x80, m[0xD732])
        assertEquals(0x80, m[0xD42F])
        assertEquals(0xFF, m[0xC1F2])
        assertFalse(GB_FIELD_YELLOW.taken(m))
        assertEquals(listOf(0x01, 0x00, 0x00, 0x12), listOf(m[0xD731], m[0xD732], m[0xD42F], m[0xC1F2]))
        GB_FIELD_YELLOW.fly(5, t, m)
        m[0xD731] = m[0xD731] or 0x04                      // HandleFlyWarpOrDungeonWarp on its way
        assertTrue(GB_FIELD_YELLOW.taken(m))
    }

    @Test
    fun `Red and Blue - FLY with no Pikachu to hide`() {
        val m = Memory()
        m[0xD356] = 0x04
        m[0xD163] = 1
        m[0xD173 + 2] = 19
        m[0xD70B] = 0b11
        m[0xD42F] = 0x55                                   // not Red's: left alone
        val t = GB_FIELD_RED_BLUE.flyTargets(m)
        assertEquals(FlyTargets(FlyResult.OK, listOf(0, 1), 0), t)
        assertEquals(FlyResult.OK, GB_FIELD_RED_BLUE.fly(1, t, m))
        assertEquals(listOf(1, 0x08, 0x80, 0x55), listOf(m[0xD71A], m[0xD732], m[0xD733], m[0xD42F]))
    }

    @Test
    fun `Yellow - encounter rates held at 0, put back from the map's wild data`() {
        val rom = ByteArray(0x200000)
        val ptrs = 0x03 * 0x4000 + (0x4B95 - 0x4000)
        rom[ptrs + 2 * 12] = 0x00; rom[ptrs + 2 * 12 + 1] = 0x60   // map 12's data at 03:6000
        val data = 0x03 * 0x4000 + 0x2000
        rom[data] = 25; rom[data + 21] = 7                         // grass rate, 20 bytes of mons, water rate
        val m = Memory(rom)
        m[0xD35D] = 12
        m[0xD886] = 25
        GB_FIELD_YELLOW.holdWild(m)
        assertEquals(0 to 0, m[0xD886] to m[0xD8A3])
        assertTrue(GB_FIELD_YELLOW.releaseWild(m))
        assertEquals(25 to 7, m[0xD886] to m[0xD8A3])
        m[0xD056] = 1                                              // a battle: its bytes are the enemy's then
        GB_FIELD_YELLOW.holdWild(m)
        assertEquals(25, m[0xD886])
    }

    @Test
    fun `PureRed and PureBlue - the same code, their own banks, WRAM bank 1`() {
        assertTrue(GB_FIELD_PURE_RGB.matches(romOf("Pokemon PureBlue (v2.7.6).gb")))
        val rom = romOf("Pokemon PureRed (v2.7.6).gb")
        val m = Memory(rom.copyOf(0x200000))
        assertEquals(ItemUseResult.USED, GB_FIELD_PURE_RGB.openPc(m))
        assertEquals(listOf(0x03D7, 0x3297, 0, 0x33, 0x46B5), m.calledAt)
        m[wramx(1, 0xD35E)] = 0x04
        m[wramx(1, 0xD16B)] = 1
        m[wramx(1, 0xD17B + 3)] = 19
        m[wramx(1, 0xD713)] = 0b1000_0011                  // Pallet, Viridian, Saffron (7: before Fuchsia in PureRGB)
        m[wramx(1, 0xD73A)] = 0x01
        val t = GB_FIELD_PURE_RGB.flyTargets(m)
        assertEquals(FlyTargets(FlyResult.OK, listOf(0, 1, 7), 0), t)
        assertEquals(FlyResult.OK, GB_FIELD_PURE_RGB.fly(7, t, m))
        assertEquals(listOf(7, 0x09, 0x80), listOf(m[wramx(1, 0xD722)], m[wramx(1, 0xD73A)], m[wramx(1, 0xD73B)]))
        // ROUTE 21 (0x20): grass 25, water 7 in its wild data (bank 0x2C, data/wild/maps/Route21.asm).
        m[wramx(1, 0xD73A)] = 0x01
        m[wramx(1, 0xD366)] = 0x20
        GB_FIELD_PURE_RGB.holdWild(m)
        assertTrue(GB_FIELD_PURE_RGB.releaseWild(m))
        assertEquals(25 to 7, m[wramx(1, 0xD88F)] to m[wramx(1, 0xD8AC)])
    }

    @Test
    fun `Crystal - the PC and FLY queued as map scripts, the cooldown held`() {
        val rom = ByteArray(0x200000)
        val flypoints = 0x24 * 0x4000 + (0x5C5E - 0x4000)
        // NEW BARK (landmark 1, spawn 14), CHERRYGROVE (3, 15), ... then Kanto from the 13th: PALLET (47, 2).
        val points = (0 until 12).flatMap { listOf(1 + 2 * it, 14 + it) } + listOf(47, 2, 85, 13, 0xFF)
        points.forEachIndexed { i, v -> rom[flypoints + i] = v.toByte() }
        val m = Memory(rom)
        assertEquals(ItemUseResult.USED, GbField.Crystal.openPc(m))
        assertEquals(listOf(1, 0x2F, 0xA9, 0x41), (0..3).map { m[wramx(1, 0xD45C + it)] })
        assertEquals(ItemUseResult.NOT_NOW, GbField.Crystal.openPc(m))   // one at a time
        assertFalse(GbField.Crystal.taken(m))
        assertEquals(0, m[wramx(1, 0xD45C)])
        m[wramx(1, 0xD45C)] = 0
        assertTrue(GbField.Crystal.taken(m))

        m[wramx(1, 0xD857)] = 0x20                         // the Storm Badge
        m[wramx(1, 0xD19A)] = 2                            // a route
        m[wramx(1, 0xDCD7)] = 1
        m[wramx(1, 0xDCE1)] = 19
        m[wramx(1, 0xDCA5 + 1)] = 0b1100_0000              // spawns 14 and 15 visited
        val t = GbField.Crystal.flyTargets(m)
        assertEquals(FlyTargets(FlyResult.OK, listOf(1, 3), 0), t)
        assertEquals(FlyResult.OK, GbField.Crystal.fly(3, t, m))
        assertEquals(15, m[wramx(1, 0xD001)])
        assertEquals(listOf(1, 0x03, 0xA3, 0x4A), (0..3).map { m[wramx(1, 0xD45C + it)] })

        GbField.Crystal.holdWild(m)
        assertEquals(5, m[wramx(1, 0xD452)])
    }
}
