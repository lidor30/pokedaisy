package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The scenic FLY's world and routes (ScenicFly.kt) over retail FireRed rev 1's own maps (the player's ROM, skipped
 * without it), and FLIGHT_FIRERED_REV1's code check. Each route is replayed over the world: every step stays on a map
 * and only crosses where the game joins two maps, and it ends on the town's landing spot.
 */
class ScenicFlyTest {
    private val rom = File(RETAIL_ROM_DIR + "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba").takeIf { it.isFile }?.readBytes()

    private fun read(a: Long, n: Int): ByteArray {
        val o = (a - 0x08000000L).toInt()
        return if (o >= 0 && o + n <= rom!!.size) rom.copyOfRange(o, o + n) else ByteArray(n)
    }

    private val f = FLIGHT_FIRERED_REV1
    private val fly = FLY_FIRERED_REV1
    private val route20 = ScenicFly.MapId(3, 38)

    @Test
    fun codeMatchesRetail() {
        assumeTrue(rom != null)
        assertTrue(flightCodeMatches(f, ::read))
        assertEquals(33 + 8, f.words().size)
    }

    @Test
    fun kantoIsOneWorld() {
        assumeTrue(rom != null)
        val world = ScenicFly.read(f, route20, ::read)!!
        val names = world.maps.map { it.id }.toSet()
        // Pallet Town (3.0), Cinnabar (3.8), Indigo Plateau (3.9), Fuchsia (3.7), Route 1 (3.19): all joined to Route 20.
        for (id in listOf(3 to 0, 3 to 8, 3 to 9, 3 to 7, 3 to 19)) assertTrue("$id", ScenicFly.MapId(id.first, id.second) in names)
        // One Island isn't joined to Kanto.
        val one = ScenicFly.destination(fly, f, ONE_ISLAND, ::read)
        assertNotNull(one)
        assertNull(world.placed(one!!.first))
    }

    @Test
    fun routesStayOnJoinedMaps() {
        assumeTrue(rom != null)
        val world = ScenicFly.read(f, route20, ::read)!!
        // From the user's save's spot on Route 20 to every Kanto town FLY knows.
        for (mapsec in KANTO_TOWNS - SAFFRON_CITY) {
            val (to, tx, ty) = ScenicFly.destination(fly, f, mapsec, ::read)!!
            val dirs = world.route(route20, 82 - 7, 12 - 7, to, tx, ty)
            assertNotNull("mapsec 0x${mapsec.toString(16)}", dirs)
            val end = replay(world, route20, 82 - 7, 12 - 7, dirs!!)
            assertEquals("mapsec 0x${mapsec.toString(16)}", Triple(to, tx, ty), end)
            assertTrue(dirs.size < ScenicFly.MAX_STEPS)
            println("ROUTE ${mapsec.toString(16)} ${dirs.joinToString("") { "?DULR"[it.toInt()].toString() }}")
        }
    }

    @Test
    fun saffronsStandInIsLeftOut() {
        assumeTrue(rom != null)
        // SaffronCity_Connection (3.11): the stand-in the game only shows past the gatehouses, its tiles scrambled up close.
        val world = ScenicFly.read(f, route20, ::read)!!
        assertNull(world.placed(ScenicFly.MapId(3, 11)))
    }

    @Test
    fun saffronHasNoRoute() {
        assumeTrue(rom != null)
        // Saffron City is reached through its gatehouses' warps, never a map connection: the classic FLY goes there.
        val world = ScenicFly.read(f, route20, ::read)!!
        val (to, _, _) = ScenicFly.destination(fly, f, SAFFRON_CITY, ::read)!!
        assertNull(world.placed(to))
    }

    @Test
    fun aDiagonalTripIsAStaircase() {
        assumeTrue(rom != null)
        val world = ScenicFly.read(f, route20, ::read)!!
        val (to, tx, ty) = ScenicFly.destination(fly, f, PALLET_TOWN, ::read)!!
        val dirs = world.route(route20, 75, 5, to, tx, ty)!!
        // Straightened: the longest run one way is well under the whole trip (a shortest path alone goes all across, then all up).
        var run = 0
        var longest = 0
        for (k in dirs.indices) { run = if (k > 0 && dirs[k] == dirs[k - 1]) run + 1 else 1; longest = maxOf(longest, run) }
        assertTrue("longest run $longest of ${dirs.size}", longest < dirs.size * 3 / 4)
        println("Route 20 -> Pallet: ${dirs.size} steps, longest run $longest: ${dirs.joinToString("") { "?DULR"[it.toInt()].toString() }}")
    }

    /** Walks [dirs] over the world, map by map, as the game would; fails on a step off the joined maps. */
    private fun replay(world: ScenicFly.World, start: ScenicFly.MapId, x0: Int, y0: Int, dirs: ByteArray): Triple<ScenicFly.MapId, Int, Int> {
        var m = world.placed(start)!!
        var x = m.x + x0
        var y = m.y + y0
        for (d in dirs) {
            when (d.toInt()) { 1 -> y++; 2 -> y--; 3 -> x--; else -> x++ }
            if (x !in m.x until m.x + m.w || y !in m.y until m.y + m.h) {
                val next = m.conns.firstNotNullOfOrNull { k ->
                    world.placed(k.to)?.takeIf { p -> k.dir == d.toInt() && x in p.x until p.x + p.w && y in p.y until p.y + p.h }
                }
                assertNotNull("stepped off ${m.id} at $x,$y", next)
                m = next!!
            }
            // Never over a square the game leaves undefined (Route 20's west edge: the flight can't cross those).
            assertTrue("undefined square of ${m.id} at ${x - m.x},${y - m.y}", !m.hole(x - m.x, y - m.y))
        }
        return Triple(m.id, x - m.x, y - m.y)
    }

    private companion object {
        // FireRed's map sections (sMapFlyDestinations from 0x58): PALLET_TOWN .. SAFFRON_CITY (0x62), the Route 4 and
        // Route 10 Pokemon Centers (0x63, 0x64); ONE_ISLAND is 0x8F (SEVII_MAPSEC_START).
        const val PALLET_TOWN = 0x58
        val KANTO_TOWNS = (0x58..0x64).toList()
        const val ONE_ISLAND = 0x8F
        const val SAFFRON_CITY = 0x62
    }
}
