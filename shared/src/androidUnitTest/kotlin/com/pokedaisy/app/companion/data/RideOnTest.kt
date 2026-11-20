package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * RIDE ON (RideOn.kt) over the player's own retail FireRed rev 1 and Emerald (skipped without them): each hero's bike
 * sheets made the rider alone (their upper body from their standing frames, no pedalling), the vehicle a sprite of its own.
 */
class RideOnTest {
    private fun scanOf(name: String) = File(RETAIL_ROM_DIR + name).takeIf { it.isFile }?.let { PlayerLook.scan(it.readBytes()) }

    @Test
    fun fireRedBikeSheetTakesTheMotorcycle() {
        val scan = scanOf("Pokemon - FireRed Version (USA, Europe) (Rev 1).gba")
        assumeTrue(scan != null)
        val pal = scan!!.data(LookChar.RED, LookPart.OW_PAL)!!
        val w = RideOn.writes(scan, LookChar.RED, emptyList(), pal, RideVehicle.MOTORCYCLE)
        assertEquals(scan.hitsOf(LookChar.RED, LookPart.FR_BIKE).size, w.size)
        val (at, made) = w.first()
        assertEquals(9 * 512, made.size)
        val own = scan.rom.copyOfRange(at, at + made.size)
        val art = RideVehiclesGen.MOTORCYCLE
        for (view in 0..2) {
            val before = RideOn.decode(own, view)
            val after = RideOn.decode(made, view)
            // The rider's upper body as the hero's frame has it, wherever the vehicle has nothing in front.
            for (i in 0 until art.cut[view] * 32) if ((art.views[view][i].toInt() and 0xC0) == 0 && before[i] != 0) assertEquals(before[i], after[i])
            // Something of the vehicle below the cut.
            assertTrue((art.cut[view] * 32 + 32 until 1024).any { after[it] != before[it] })
        }
        // No pedalling: the moving frames are the standing ones.
        for ((k, view) in listOf(3 to 0, 4 to 0, 5 to 1, 6 to 1, 7 to 2, 8 to 2)) assertArrayEquals(RideOn.decode(made, view), RideOn.decode(made, k))
        assertTrue(RideOn.writes(scan, LookChar.RED, emptyList(), pal, RideVehicle.BIKE).isEmpty())
    }

    @Test
    fun theVehicleIsASpriteOfItsOwn() {
        for (v in listOf(RideVehicle.MOTORCYCLE, RideVehicle.SCOOTER)) {
            val sp = RideOn.sprite(v)!!
            assertEquals(32, sp.width)
            assertEquals(RideOn.BYTES, sp.frames.size)
            // Smoke behind it from the side and from behind, none from the front (the vehicle hides it).
            val ex = sp.frames.copyOfRange(3 * 1024 + RideOn.SMOKE_FRAMES * 64, RideOn.BYTES)
            assertEquals(-128, ex[0].toInt())
            assertTrue(ex[2].toInt() != -128 && ex[4].toInt() != -128)
            assertTrue((3 * 1024 until 3 * 1024 + RideOn.SMOKE_FRAMES * 64).any { sp.frames[it].toInt() != 0 })
            assertEquals(32, sp.pal.size)
            // Its own colours, at most 15: every pixel's index is one of the art's.
            val used = (0 until (3 * 1024 + RideOn.SMOKE_FRAMES * 64) * 2).map { i -> (sp.frames[i / 2].toInt() shr (4 * (i % 2))) and 15 }.toSet() - 0
            assertTrue(used.isNotEmpty() && used.all { it < v.art!!.palette.size } && v.art!!.palette.size <= 16)
        }
        assertEquals(null, RideOn.sprite(RideVehicle.BIKE))
    }

    @Test
    fun emeraldMachAndAcroBikesTakeTheScooter() {
        val scan = scanOf("Pokemon - Emerald Version (USA, Europe).gba")
        assumeTrue(scan != null)
        val pal = scan!!.data(LookChar.MAY, LookPart.OW_PAL)!!
        val w = RideOn.writes(scan, LookChar.MAY, emptyList(), pal, RideVehicle.SCOOTER)
        val sizes = w.map { it.second.size }.toSet()
        assertEquals(setOf(9 * 512, 27 * 512), sizes)
    }

    @Test
    fun coloursGoToTheNearestOfThePalette() {
        val pal = ByteArray(32)
        fun set(i: Int, r: Int, g: Int, b: Int) { val c = (r shr 3) or ((g shr 3) shl 5) or ((b shr 3) shl 10); pal[i * 2] = c.toByte(); pal[i * 2 + 1] = (c shr 8).toByte() }
        set(1, 255, 0, 0); set(2, 0, 0, 255); set(3, 0, 0, 0)
        for (i in 4..15) set(i, 255, 255, 255)
        assertEquals(2, RideOn.nearest(pal, 0x1A42A8))
        assertEquals(1, RideOn.nearest(pal, 0xD03040))
        assertEquals(0, RideOn.nearest(pal, 0))
    }
}
