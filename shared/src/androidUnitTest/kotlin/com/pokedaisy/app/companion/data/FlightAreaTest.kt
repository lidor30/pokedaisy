package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * FLIGHT AREA (FlightArea.kt) over the player's own retail FireRed rev 1 and Emerald (skipped without them): the maps and
 * trainers read from the ROM, the seen maps a save proves (its towns, the routes between them, maps with a beaten trainer
 * from the save fixtures' flags), and the scenic FLY kept to them.
 */
class FlightAreaTest {
    private val fr = File(RETAIL_ROM_DIR + "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba").takeIf { it.isFile }?.readBytes()
    private val em = File(RETAIL_ROM_DIR + "Pokemon - Emerald Version (USA, Europe).gba").takeIf { it.isFile }?.readBytes()

    private fun reader(rom: ByteArray): (Long, Int) -> ByteArray = { a, n ->
        val o = (a - 0x08000000L).toInt()
        if (o >= 0 && o + n <= rom.size) rom.copyOfRange(o, o + n) else ByteArray(n)
    }

    private fun town(fly: FlyFns, f: FlightFns, read: (Long, Int) -> ByteArray, mapsec: Int) =
        FlightArea.id(ScenicFly.destination(fly, f, mapsec, read)!!.first)

    private fun towns(fly: FlyFns, f: FlightFns, read: (Long, Int) -> ByteArray): Set<Int> =
        (0 until fly.count).mapNotNull { ScenicFly.destination(fly, f, fly.firstMapsec + it, read)?.first?.let(FlightArea::id) }.toSet()

    private fun map(g: Int, n: Int) = (g shl 8) or n

    /** FlagGet over a save fixture: SaveBlock1's flags at [flagsOff]. */
    private fun flags(fixture: String, sb1Ptr: Long, flagsOff: Int): (Int) -> Boolean {
        val ram = FixtureMemoryReader.load(fixture)
        val p = ram.readCoreMemory(sb1Ptr, 4).let { b -> (0..3).fold(0L) { v, i -> v or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
        val bytes = ram.readCoreMemory(p + flagsOff, 0x120)
        return { n -> n / 8 < bytes.size && (bytes[n / 8].toInt() shr (n % 8)) and 1 != 0 }
    }

    @Test
    fun fireRedMapsAndTrainers() {
        assumeTrue(fr != null)
        val maps = FlightArea.readMaps(FLIGHT_FIRERED_REV1, reader(fr!!))
        assertTrue(maps.size > 400)
        // Route 3 joins Pewter (east) and Route 4 (north); its trainers are battled from their objects.
        assertTrue(map(3, 2) in maps.getValue(map(3, 21)).connections)
        assertTrue(maps.getValue(map(3, 21)).trainers.size >= 8)
        assertTrue(maps.values.sumOf { it.trainers.size } > 300)
    }

    @Test
    fun fireRedSeedsTheRoutesBetweenVisitedTowns() {
        assumeTrue(fr != null)
        val read = reader(fr!!)
        val f = FLIGHT_FIRERED_REV1
        val fly = FLY_FIRERED_REV1
        val maps = FlightArea.readMaps(f, read)
        val all = towns(fly, f, read)
        val pallet = town(fly, f, read, 0x58)
        val viridian = town(fly, f, read, 0x59)
        val pewter = town(fly, f, read, 0x5A)
        val cerulean = town(fly, f, read, 0x5B)
        val noFly = f.noFly.toSet()
        val early = FlightArea.seed(maps, all, setOf(pallet, viridian, pewter), { false }, noFly)
        assertTrue(map(3, 19) in early)   // Route 1
        assertTrue(map(3, 20) in early)   // Route 2
        assertFalse(map(3, 21) in early)  // Route 3: Cerulean not reached yet
        assertFalse(map(3, 40) in early)  // Route 22: only leads on to the League
        // Route 4's Pokemon Center is on the Fly map too: a town of its own, visited on the way.
        val route4 = town(fly, f, read, 0x63)
        assertEquals(map(3, 22), route4)
        val more = FlightArea.seed(maps, all, setOf(pallet, viridian, pewter, route4, cerulean), { false }, noFly)
        assertTrue(map(3, 21) in more && map(3, 22) in more)   // Routes 3 and 4
        // The scenic FLY from Pallet: Cerulean is reached over the seen maps, nothing past them.
        val world = ScenicFly.read(f, ScenicFly.MapId(3, 0), read) { FlightArea.id(it) in more }!!
        assertTrue(world.maps.all { FlightArea.id(it.id) in more })
        val dest = ScenicFly.destination(fly, f, 0x5B, read)!!
        assertTrue(world.route(ScenicFly.MapId(3, 0), 6, 8, dest.first, dest.second, dest.third) != null)
        val lavender = ScenicFly.destination(fly, f, 0x5C, read)!!
        assertNull(world.placed(lavender.first))
    }

    @Test
    fun fireRedSaveProvesItsTrainersMaps() {
        assumeTrue(fr != null)
        val read = reader(fr!!)
        val maps = FlightArea.readMaps(FLIGHT_FIRERED_REV1, read)
        val beaten = flags("firered_vanilla", FLIGHT_FIRERED_REV1.saveBlock1Ptr, 0xEE0)
        val pewter = town(FLY_FIRERED_REV1, FLIGHT_FIRERED_REV1, read, 0x5A)
        val seen = FlightArea.seed(maps, towns(FLY_FIRERED_REV1, FLIGHT_FIRERED_REV1, read), setOf(pewter), { beaten(FlightArea.TRAINER_FLAGS_START + it) })
        // Route 3's trainers are beaten on that save, and Route 3 touches Pewter; Route 4's chain on from it.
        assertTrue(map(3, 21) in seen && map(3, 22) in seen)
        // Route 22's are beaten too, but nothing seen touches it (Viridian isn't in this set).
        assertFalse(map(3, 40) in seen)
        assertFalse(map(3, 20) in seen)   // Route 2: no trainers, no visited town past it
    }

    @Test
    fun emeraldSeedsAndTrainers() {
        assumeTrue(em != null)
        val read = reader(em!!)
        val f = FLIGHT_EMERALD
        val fly = FLY_EMERALD
        val maps = FlightArea.readMaps(f, read)
        assertTrue(maps.values.sumOf { it.trainers.size } > 300)
        val littleroot = ScenicFly.MapId(0, 9)
        val oldale = ScenicFly.MapId(0, 10)
        val seen = FlightArea.seed(maps, towns(fly, f, read) + FlightArea.id(littleroot), setOf(FlightArea.id(littleroot), FlightArea.id(oldale)), { false })
        assertTrue(map(0, 16) in seen)   // Route 101
        assertFalse(map(0, 17) in seen)  // Route 102
        val beaten = flags("emerald_vanilla", f.saveBlock1Ptr, 0x1270)
        val trainers = FlightArea.seed(maps, emptySet(), setOf(FlightArea.id(oldale)), { beaten(FlightArea.TRAINER_FLAGS_START + it) })
        assertTrue(map(0, 17) in trainers)   // Route 102: its trainers beaten, next to Oldale
    }

    /** Every hack FREE FLIGHT flies: its maps and trainers read (the trainerbattle layout voted right: a wrong one reads
     * trainer ids from text pointers, and next to none pass). */
    @Test
    fun theHacksMapsAndTrainersRead() {
        val cases = listOf(
            "Pokemon Radical Red (v4.1).gba" to FLIGHT_RADICAL_RED,
            "Pokemon Unbound (v2.1.1.1).gba" to FLIGHT_UNBOUND,
            "Pokemon Glazed (v9.2.0).gba" to FLIGHT_GLAZED,
            "Pokemon Ultra Violet (v1.22).gba" to FLIGHT_ULTRA_VIOLET,
            "Pokemon Orange Islands.gba" to FLIGHT_ORANGE_ISLANDS,
            "Pokemon Ghost Grey (v1.0.4).gba" to FLIGHT_GHOST_GREY,
            "Pokemon Let's Go Pikachu.gba" to FLIGHT_LETS_GO,
            "Pokemon Odyssey (v4.1.1).gba" to FLIGHT_ODYSSEY,
            "Pokemon Gaia (v3.2).gba" to FLIGHT_GAIA,
            "Pokemon Amethyst (v1.3.0).gba" to FLIGHT_AMETHYST,
            "Pokemon Amethyst (v1.4.1).gba" to FLIGHT_AMETHYST_V141,
            "Pokemon Sword and Shield Ultimate Plus (v1.2.1.2) (Casual Patch).gba" to FLIGHT_SWORD_SHIELD,
            "Pokemon GS Chronicles (v2.7.6).gba" to FLIGHT_GS_CHRONICLES,
            "Pokemon Emerald Legacy (v1.1.4).gba" to FLIGHT_EMERALD_LEGACY,
            "Pokemon Emerald Kaizo (v2.0).gba" to FLIGHT_EMERALD_KAIZO,
            "Pokemon FireRed Rocket Edition.gba" to FLIGHT_ROCKET_EDITION,
            "Pokemon Ash Gray (v4.5.3).gba" to FLIGHT_ASH_GRAY,
            "Pokemon Emerald Imperium (v1.3.1).gba" to FLIGHT_IMPERIUM,
            "Pokemon Lazarus (v2.0).gba" to FLIGHT_LAZARUS,
            "Pokemon Heart and Soul (v2.0.6).gba" to FLIGHT_HEART_AND_SOUL,
            "Pokemon Emerald Seaglass (v3.0).gba" to FLIGHT_SEAGLASS,
            "Pokemon Too Many Types 2 (v1.5.2).gba" to FLIGHT_TMT2,
            "Pokemon R.O.W.E. (v2.1.9.1 Experimental).gba" to FLIGHT_ROWE,
            "Super Mariomon (v1.5.1 Anniversary).gba" to FLIGHT_MARIOMON_V1_5,
            "Pokemon Quetzal (Alpha 9.0) (English).gba" to FLIGHT_QUETZAL,
            "Pokemon SoulGold (v1.1.4).gba" to FLIGHT_SOULGOLD,
        )
        var checked = 0
        for ((name, f) in cases) {
            val rom = File(RETAIL_ROM_DIR + name).takeIf { it.isFile }?.readBytes() ?: continue
            val maps = FlightArea.readMaps(f, reader(rom))
            val trainers = maps.values.sumOf { it.trainers.size }
            println("$name: ${maps.size} maps, ${maps.values.count { it.trainers.isNotEmpty() }} with trainers, $trainers trainers")
            assertTrue(name, maps.size > 100 && trainers > 100)
            checked++
        }
        assumeTrue(checked > 0)
    }

    @Test
    fun bitsAndSaveKey() {
        val b = FlightArea.bits(listOf(map(3, 19), 0))
        assertEquals(1, b[0].toInt() and 1)
        assertEquals(1 shl (19 and 7), b[map(3, 19) shr 3].toInt() and 0xFF)
        val ram = FixtureMemoryReader.load("firered_vanilla")
        val key = FlightArea.saveKey(FLIGHT_FIRERED_REV1) { a, n -> ram.readCoreMemory(a, n) }
        assertTrue(key != null && key.length == 8)
    }
}
