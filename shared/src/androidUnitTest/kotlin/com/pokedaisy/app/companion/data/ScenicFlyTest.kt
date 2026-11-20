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
        assertEquals(48 + 8, f.words().size)
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

    private val emerald = File(RETAIL_ROM_DIR + "Pokemon - Emerald Version (USA, Europe).gba").takeIf { it.isFile }?.readBytes()

    private fun readEm(a: Long, n: Int): ByteArray {
        val o = (a - 0x08000000L).toInt()
        return if (o >= 0 && o + n <= emerald!!.size) emerald.copyOfRange(o, o + n) else ByteArray(n)
    }

    @Test
    fun emeraldCodeMatchesRetail() {
        assumeTrue(emerald != null)
        assertTrue(flightCodeMatches(FLIGHT_EMERALD, ::readEm))
        assertEquals(48 + 8, FLIGHT_EMERALD.words().size)
    }

    @Test
    fun emeraldRoutesStayOnJoinedMaps() {
        assumeTrue(emerald != null)
        val e = FLIGHT_EMERALD
        // From Oldale Town's landing spot to every town Emerald's FLY knows that a connection reaches.
        val (oldale, ox, oy) = ScenicFly.destination(FLY_EMERALD, e, EM_OLDALE, ::readEm)!!
        val world = ScenicFly.read(e, oldale, ::readEm)!!
        val reached = mutableListOf<Int>()
        for (mapsec in EM_TOWNS) {
            val (to, tx, ty) = ScenicFly.destination(FLY_EMERALD, e, mapsec, ::readEm) ?: continue
            if (world.placed(to) == null) continue
            val dirs = world.route(oldale, ox, oy, to, tx, ty)
            assertNotNull("mapsec $mapsec", dirs)
            assertEquals("mapsec $mapsec", Triple(to, tx, ty), replay(world, oldale, ox, oy, dirs!!))
            assertTrue(dirs.size < ScenicFly.MAX_STEPS)
            reached += mapsec
        }
        println("EMERALD reached $reached")
        // Over land and the routes between: Littleroot, Petalburg, Rustboro, Mauville, Lilycove.
        for (m in listOf(EM_LITTLEROOT, EM_PETALBURG, EM_RUSTBORO, 9, 12)) assertTrue("mapsec $m in $reached", m in reached)
    }

    @Test
    fun emeraldLittlerootFollowsTheGender() {
        assumeTrue(emerald != null)
        // HEAL_LOCATION_LITTLEROOT_TOWN_BRENDANS_HOUSE / _MAYS_HOUSE (12 / 13): outside, not the table's bedroom.
        val (map, x, y) = ScenicFly.destination(FLY_EMERALD, FLIGHT_EMERALD, EM_LITTLEROOT, ::readEm)!!
        assertEquals(ScenicFly.MapId(0, 9), map)
        assertTrue("$x,$y", (x == 5 || x == 14) && y == 9)
    }

    /** The hacks on retail code: Radical Red and Unbound (CFRU, FireRed rev 0), Glazed (Emerald). Each from a spot its save
     * stands on (or a fly spot), to the towns a connection reaches; skipped without the ROM. */
    private fun hack(name: String): ((Long, Int) -> ByteArray)? {
        val b = File(RETAIL_ROM_DIR + name).takeIf { it.isFile }?.readBytes() ?: return null
        return { a, n -> val o = (a - 0x08000000L).toInt(); if (o >= 0 && o + n <= b.size) b.copyOfRange(o, o + n) else ByteArray(n) }
    }

    private fun flies(f: FlightFns, fly: FlyFns, read: (Long, Int) -> ByteArray, from: ScenicFly.MapId, x: Int, y: Int, towns: List<Int>): List<Int> {
        assertTrue(flightCodeMatches(f, read))
        val world = ScenicFly.read(f, from, read)!!
        return towns.filter { mapsec ->
            val (to, tx, ty) = ScenicFly.destination(fly, f, mapsec, read) ?: return@filter false
            val dirs = world.route(from, x, y, to, tx, ty) ?: return@filter false
            assertEquals("mapsec 0x${mapsec.toString(16)}", Triple(to, tx, ty), replay(world, from, x, y, dirs))
            true
        }
    }

    @Test
    fun radicalRedFliesKanto() {
        val read = hack("Pokemon Radical Red (v4.1).gba")
        assumeTrue(read != null)
        val reached = flies(FLIGHT_RADICAL_RED, FIELD_ITEMS_PORTS.getValue("BPRE0").fly!!, read!!, ScenicFly.MapId(3, 0), 6, 8, KANTO_TOWNS)
        for (m in listOf(0x59, 0x5A, 0x5B, 0x5F)) assertTrue("mapsec $m in $reached", m in reached)
    }

    @Test
    fun unboundReadsItsScrambledMapsAndFlySpots() {
        val read = hack("Pokemon Unbound (v2.1.1.1).gba")
        assumeTrue(read != null)
        val fly = flyUnbound(FIELD_ITEMS_PORTS.getValue("BPRE0").fly!!)
        // A fly spot is where its FLY lands: Dehara City (0x59) on map 3.68 at 12,12.
        assertEquals(Triple(ScenicFly.MapId(3, 68), 12, 12), ScenicFly.destination(fly, FLIGHT_UNBOUND, 0x59, read!!))
        // From the save's map (3.80) over Borrius' mainland, joined through the XORed gMapGroups.
        val reached = flies(FLIGHT_UNBOUND, fly, read, ScenicFly.MapId(3, 80), 19, 27, (0x58..0x9E).toList())
        assertTrue("$reached", reached.size >= 15)
    }

    @Test
    fun glazedFliesTunod() {
        val read = hack("Pokemon Glazed (v9.2.0).gba")
        assumeTrue(read != null)
        val reached = flies(FLIGHT_GLAZED, FIELD_ITEMS_GLAZED.fly!!, read!!, ScenicFly.MapId(0, 17), 14, 13, EM_TOWNS)
        for (m in listOf(0, 3, 4, 5, 6, 9, 11)) assertTrue("mapsec $m in $reached", m in reached)
    }

    @Test
    fun theExpansionHacksOfferTakeOff() {
        // Their bag has no USE (bagActions false): the support check returned before it looked at the flight once, and
        // TAKE OFF never showed in the app though the core flew.
        val cases = listOf(
            "Pokemon Emerald Imperium (v1.3.1).gba" to FIELD_ITEMS_IMPERIUM,
            "Pokemon Lazarus (v2.0).gba" to FIELD_ITEMS_LAZARUS,
            "Pokemon Heart and Soul (v2.0.6).gba" to FIELD_ITEMS_HEART_AND_SOUL,
            "Pokemon Emerald Seaglass (v3.0).gba" to FIELD_ITEMS_SEAGLASS,
            "Pokemon Too Many Types 2 (v1.5.2).gba" to FIELD_ITEMS_TMT2,
            "Pokemon R.O.W.E. (v2.1.9.1 Experimental).gba" to FIELD_ITEMS_ROWE,
            "Pokemon SoulGold (v1.1.4).gba" to FIELD_ITEMS_SOULGOLD,
            "Pokemon SoulGold (v1.2).gba" to FIELD_ITEMS_SOULGOLD_V1_2,
            "Pokemon SoulGold (v1.2) (Alt 1).gba" to FIELD_ITEMS_SOULGOLD_V1_2B,
            "Pokemon SoulGold (v1.2) (Alt 2).gba" to FIELD_ITEMS_SOULGOLD_V1_2C,
            "Pokemon SoulGold (v1.2.1).gba" to FIELD_ITEMS_SOULGOLD_V1_2_1,
        )
        var checked = 0
        for ((name, fns) in cases) {
            val read = hack(name) ?: continue
            val s = fieldItemSupport(fns, read)
            assertNotNull(name, s)
            assertTrue(name, s!!.canFly && s.canFlight)
            checked++
        }
        assumeTrue(checked > 0)
    }

    @Test
    fun theExpansionHacksFlyJohto() {
        // Heart and Soul from the save's New Bark (0.0, 15,12); SoulGold v1.1.4 from its save's route (0.16, 24,29): its
        // 11-bit grid (undefined 0x7FF) and gMapGroups' last group stopped where the table starts.
        hack("Pokemon Heart and Soul (v2.0.6).gba")?.let { read ->
            val reached = flies(FLIGHT_HEART_AND_SOUL, FIELD_ITEMS_HEART_AND_SOUL.fly!!, read, ScenicFly.MapId(0, 0), 15, 12, (0..0x7C).toList())
            assertTrue("$reached", reached.size >= 12)
        }
        val read = hack("Pokemon SoulGold (v1.1.4).gba")
        assumeTrue(read != null)
        val reached = flies(FLIGHT_SOULGOLD, FIELD_ITEMS_SOULGOLD.fly!!, read!!, ScenicFly.MapId(0, 16), 24, 29, (0..0x138).toList())
        assertTrue("$reached", reached.size >= 19)
    }

    @Test
    fun everyLanguageCartsFlightMatchesItsRom() {
        // scripts/port_field_items.py's mapped records, each against the cart of its key in the ROM folder: the code where
        // the record says, and the collision and elevation reads two functions (their first 32 bytes are the same, so a
        // match by code alone once put both on one).
        val dir = File(RETAIL_ROM_DIR)
        assumeTrue(dir.isDirectory)
        var checked = 0
        for (f in dir.listFiles().orEmpty().filter { it.name.endsWith(".gba") && it.length() <= 0x1000000 }) {
            val b = f.readBytes()
            val key = String(b, 0xAC, 4, Charsets.US_ASCII) + (b[0xBC].toInt() and 0xFF)
            val fl = FIELD_ITEMS_PORTS[key]?.flight ?: continue
            if (sha1(b) !in ShinyOdds.RETAIL_SHA1S) continue   // retail carts only, not a hack under the same code
            val read = { a: Long, n: Int -> val o = (a - 0x08000000L).toInt(); if (o >= 0 && o + n <= b.size) b.copyOfRange(o, o + n) else ByteArray(n) }
            assertTrue("$key ${f.name}", flightCodeMatches(fl, read))
            assertTrue("$key collisionAt", fl.collisionAt != fl.elevationAt)
            assertNotNull("$key world", ScenicFly.read(fl, ScenicFly.MapId(if (key.startsWith("BP") && !key.startsWith("BPE")) 3 else 0, 0), read))
            checked++
        }
        println("language carts checked: $checked")
    }

    @Test
    fun theHacksRecordsMatchTheirRoms() {
        val cases = listOf(
            "Pokemon Ultra Violet (v1.22).gba" to FLIGHT_ULTRA_VIOLET,
            "Pokemon Orange Islands.gba" to FLIGHT_ORANGE_ISLANDS,
            "Pokemon Ghost Grey (v1.0.4).gba" to FLIGHT_GHOST_GREY,
            "Pokemon Ghost Grey (v1.0.1).gba" to FLIGHT_GHOST_GREY,
            "Pokemon Let's Go Pikachu.gba" to FLIGHT_LETS_GO,
            "Pokemon Let's Go Eevee (Translation Only).gba" to FLIGHT_LETS_GO,
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
            "Super Mariomon (v1.5.2 Anniversary).gba" to FLIGHT_MARIOMON_V1_5_2,
        ) + SOULGOLD_FLIGHTS
        var checked = 0
        for ((name, fl) in cases) {
            val read = hack(name) ?: continue
            assertTrue(name, flightCodeMatches(fl, read))
            checked++
        }
        assumeTrue(checked > 0)
        // A hack running as FIRERED gets in only by a record of its own, never by rev 0's.
        assertTrue(!flightAllowed(GameKind.FIRERED, false, FLIGHT_FIRERED_REV0))
        assertTrue(flightAllowed(GameKind.FIRERED, false, FLIGHT_ROCKET_EDITION))
        assertTrue(flightAllowed(GameKind.FIRERED, false, FLIGHT_ASH_GRAY))
        assertTrue(flightAllowed(GameKind.FIRERED, false, FLIGHT_GHOST_GREY))
        assertTrue(flightAllowed(GameKind.EMERALD, false, FLIGHT_EMERALD_LEGACY))
        assertTrue(flightAllowed(GameKind.EMERALD, false, FLIGHT_EMERALD_KAIZO))
        assertTrue(!flightAllowed(GameKind.EMERALD, false, FLIGHT_EMERALD))
    }

    @Test
    fun rocketEditionAndAshGrayFlyKanto() {
        // Retail FireRed's save booted in each stands on Route 20 (3.38) at 75,5. Ash Gray's map groups are its own (bigger
        // lists), so rev 0's read no world there.
        var checked = 0
        for ((name, f) in listOf("Pokemon FireRed Rocket Edition.gba" to FLIGHT_ROCKET_EDITION, "Pokemon Ash Gray (v4.5.3).gba" to FLIGHT_ASH_GRAY)) {
            val read = hack(name) ?: continue
            val reached = flies(f, FIELD_ITEMS_PORTS.getValue("BPRE0").fly!!, read, ScenicFly.MapId(3, 38), 75, 5, KANTO_TOWNS)
            assertTrue("$name $reached", reached.size >= 8)
            checked++
        }
        assumeTrue(checked > 0)
    }

    @Test
    fun seaglassFliesHoenn() {
        // Its save, walked out of Birch's lab: Littleroot (0.9) at 7,17.
        val read = hack("Pokemon Emerald Seaglass (v3.0).gba")
        assumeTrue(read != null)
        val reached = flies(FLIGHT_SEAGLASS, FIELD_ITEMS_SEAGLASS.fly!!, read!!, ScenicFly.MapId(0, 9), 7, 17, EM_TOWNS)
        assertTrue("$reached", reached.size >= 12)
        assertTrue(pcCodeMatches(FIELD_ITEMS_SEAGLASS.pc!!, read))
    }

    @Test
    fun tooManyTypes2FliesHoenn() {
        // Its save stands in Lilycove (0.5) at 32,10.
        val read = hack("Pokemon Too Many Types 2 (v1.5.2).gba")
        assumeTrue(read != null)
        val reached = flies(FLIGHT_TMT2, FIELD_ITEMS_TMT2.fly!!, read!!, ScenicFly.MapId(0, 5), 32, 10, EM_TOWNS)
        assertTrue("$reached", reached.size >= 12)
    }

    @Test
    fun roweFliesHoennAndLandsKantoTheGamesWay() {
        // Its save stands in Littleroot (0.9) at 20,9. Kanto and Sevii are worlds of their own (no connection to Hoenn): their
        // towns take the game's FLY, through the special heal locations its CB_ExitFlyMap switch gives them.
        val read = hack("Pokemon R.O.W.E. (v2.1.9.1 Experimental).gba")
        assumeTrue(read != null)
        val reached = flies(FLIGHT_ROWE, FIELD_ITEMS_ROWE.fly!!, read!!, ScenicFly.MapId(0, 9), 20, 9, EM_TOWNS)
        assertTrue("$reached", reached.size >= 12)
        val fly = FIELD_ITEMS_ROWE.fly!!
        assertEquals(58, fly.healWarp!!.special[88])   // Pallet Town
        assertTrue(88 in fly.extraMapsecs)
        assertTrue(pcCodeMatches(FIELD_ITEMS_ROWE.pc!!, read))
    }

    @Test
    fun mariomonBuildsPickTheirOwnTables() {
        val builds = listOf("Super Mariomon (v1.5.1 Anniversary).gba" to FIELD_ITEMS_MARIOMON_V1_5, "Super Mariomon (v1.5.2 Anniversary).gba" to FIELD_ITEMS_MARIOMON_V1_5_2)
        var checked = 0
        for ((name, own) in builds) {
            val read = hack(name) ?: continue
            for ((_, other) in builds) assertEquals(name, other === own, fieldItemSupport(other, read)?.canFlight == true)
            checked++
        }
        assumeTrue(checked > 0)
        // The save's area (2.4 at 25,23) joins two towns over the map; the rest take the game's FLY.
        val read = hack("Super Mariomon (v1.5.1 Anniversary).gba") ?: return
        val reached = flies(FLIGHT_MARIOMON_V1_5, FIELD_ITEMS_MARIOMON_V1_5.fly!!, read, ScenicFly.MapId(2, 4), 25, 23, (0 until 0xA8).toList())
        assertEquals(listOf(0, 0x58), reached)
    }

    @Test
    fun quetzalBuildsPickTheirOwnTablesAndFlyHoenn() {
        val builds = listOf(
            "Pokemon Quetzal (Alpha 9.0) (English).gba" to FIELD_ITEMS_QUETZAL,
            "Pokemon Quetzal (Alpha 9.0) (Spanish).gba" to FIELD_ITEMS_QUETZAL_ES,
            "Pokemon Quetzal (Alpha 9v1) (English).gba" to FIELD_ITEMS_QUETZAL_V1,
            "Pokemon Quetzal (Alpha 9v1) (Spanish).gba" to FIELD_ITEMS_QUETZAL_ES_V1,
        )
        var checked = 0
        for ((name, own) in builds) {
            val read = hack(name) ?: continue
            for ((_, other) in builds) assertEquals(name, other === own, fieldItemSupport(other, read)?.canFlight == true)
            checked++
        }
        assumeTrue(checked > 0)
        // From Littleroot (0.9) at 5,9, where its own FLY landed headless: Hoenn's towns over the map.
        val read = hack("Pokemon Quetzal (Alpha 9.0) (English).gba") ?: return
        val reached = flies(FLIGHT_QUETZAL, FIELD_ITEMS_QUETZAL.fly!!, read, ScenicFly.MapId(0, 9), 5, 9, EM_TOWNS)
        assertTrue("$reached", reached.size >= 12)
    }

    @Test
    fun kaizoFliesHoennWithoutTheWarp() {
        // Retail's save booted in Kaizo stands on map 0.29 at 15,48; its FLY never takes the game's warp (classicFly), so
        // every town it offers goes over the map or says there's no way.
        val read = hack("Pokemon Emerald Kaizo (v2.0).gba")
        assumeTrue(read != null)
        val reached = flies(FLIGHT_EMERALD_KAIZO, FLY_EMERALD, read!!, ScenicFly.MapId(0, 29), 15, 48, EM_TOWNS)
        assertTrue("$reached", reached.size >= 10)
    }

    private val SOULGOLD_FLIGHTS = listOf(
        "Pokemon SoulGold (v1.1.4).gba" to FLIGHT_SOULGOLD,
        "Pokemon SoulGold (v1.2).gba" to FLIGHT_SOULGOLD_V1_2,
        "Pokemon SoulGold (v1.2) (Alt 1).gba" to FLIGHT_SOULGOLD_V1_2B,
        "Pokemon SoulGold (v1.2) (Alt 2).gba" to FLIGHT_SOULGOLD_V1_2C,
        "Pokemon SoulGold (v1.2.1).gba" to FLIGHT_SOULGOLD_V1_2_1,
    )

    /** SoulGold's builds share a game code and kind: each one's code CRCs must pick its own record and no other's (the field
     * items' candidates are chosen that way), and every record carries the follower calls and the 11-bit grid's 0x7FF. */
    @Test
    fun soulGoldBuildsPickTheirOwnFlight() {
        var checked = 0
        for ((name, own) in SOULGOLD_FLIGHTS) {
            val read = hack(name) ?: continue
            for ((_, other) in SOULGOLD_FLIGHTS) assertEquals(name, other === own, flightCodeMatches(other, read))
            checked++
        }
        assumeTrue(checked > 0)
        for ((_, f) in SOULGOLD_FLIGHTS) {
            assertEquals(0x7FF, f.mapGridUndefined)
            assertTrue(f.followerRemove != 0L && f.followerUpdate != 0L)
            assertEquals(80, f.healLocationCount)
            assertTrue(flightAllowed(GameKind.SOULGOLD, false, f))
        }
        assertTrue(!flightAllowed(GameKind.SOULGOLD, false, null))
    }

    private fun sha1(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

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

        // Emerald's map sections (sMapHealLocations' order): LITTLEROOT 0, OLDALE 1, ... PETALBURG 7, SLATEPORT 8,
        // MAUVILLE 9, RUSTBORO 10, FORTREE 11, LILYCOVE 12, MOSSDEEP 13, SOOTOPOLIS 14, EVER_GRANDE 15.
        const val EM_LITTLEROOT = 0
        const val EM_OLDALE = 1
        const val EM_PETALBURG = 7
        const val EM_RUSTBORO = 10
        val EM_TOWNS = (0..15).toList()
    }
}
