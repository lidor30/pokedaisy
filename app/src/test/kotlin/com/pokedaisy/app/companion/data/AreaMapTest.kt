package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.data.AreaMap.MapId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/**
 * AREA maps ([AreaMap]) on the player's ROMs (skips without them): each game's tables match its own ROM only, the areas
 * gather the right floors in the right order, and every map renders (PNGs in build/area-maps/ to look at).
 */
class AreaMapTest {
    private fun rom(file: String) = RomFileReader.load(RETAIL_ROM_DIR + file)

    private val read: RomFileReader.() -> (Long, Int) -> ByteArray = { { a, n -> readCoreMemory(a, n) } }

    private val fireRed = "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba"
    private val emerald = "Pokemon - Emerald Version (USA, Europe).gba"
    private val unbound = "Pokemon Unbound (v2.1.1.1).gba"

    private val AREA_MAP_FIRERED_REV1 get() = AREA_MAP_TABLES.getValue(0x84ee4776L)
    private val AREA_MAP_EMERALD get() = AREA_MAP_TABLES.getValue(0x1f1c08fbL)
    private val AREA_MAP_UNBOUND get() = AREA_MAP_TABLES.getValue(0x4b3d4957L)

    @Test fun `each game's tables match its own ROM only`() {
        val tables = listOf(fireRed to AREA_MAP_FIRERED_REV1, emerald to AREA_MAP_EMERALD, unbound to AREA_MAP_UNBOUND)
        for ((file, _) in tables) {
            val r = rom(file)?.read() ?: continue
            for ((other, t) in tables) assertEquals("$other's tables on $file", file == other, AreaMap.matches(t, r))
        }
    }

    /** Every ROM gamedata/gen3/area_maps.toml has a row for (found by name in the ROM folder): its tables match it, and
     * an indoor area of several floors draws. */
    @Test fun `every row reads its own ROM`() {
        val rows = File("../gamedata/gen3/area_maps.toml").takeIf { it.isFile } ?: File("gamedata/gen3/area_maps.toml")
        val names = Regex("^name = \"(.*)\"$", RegexOption.MULTILINE).findAll(rows.readText()).map { it.groupValues[1] }.toList()
        val dir = File(RETAIL_ROM_DIR)
        val files = dir.walkTopDown().filter { it.isFile }.associateBy { it.name }
        var checked = 0
        for (name in names) {
            val f = files[name] ?: continue
            val bytes = f.readBytes()
            val t = AREA_MAP_TABLES[CRC32().apply { update(bytes) }.value] ?: error("$name: no row by its CRC")
            val r = RomFileReader(bytes)
            val read: (Long, Int) -> ByteArray = { a, n -> r.readCoreMemory(a, n) }
            assertTrue("$name: tables don't match", AreaMap.matches(t, read))
            // An area with more than one floor whose floors draw in more than a few colours (a hack's blank leftover maps
            // don't count): the tiles, palettes and metatiles came out.
            val drawn = (0 until t.mapGroupSizes.size).asSequence()
                .flatMap { g -> (0 until t.mapGroupSizes[g]).asSequence().map { MapId(g, it) } }
                .mapNotNull { AreaMap.area(t, it, read) }.filter { it.floors.size > 1 }.take(40)
                .firstOrNull { a -> a.floors.all { f -> AreaMap.render(t, f.id, read)?.argb?.distinct()?.size ?: 0 >= 8 } }
            assertNotNull("$name: no area with floors draws", drawn)
            // To look at: build/area-maps/all/<ROM>.png, the area's first floor.
            File("build/area-maps/all").apply { mkdirs() }.let { dir ->
                AreaMap.render(t, drawn!!.floors.first().id, read)?.let { File(dir, name.substringBeforeLast('.') + ".png").writeBytes(it.png()) }
            }
            checked++
        }
        println("AREA maps: $checked ROMs checked")
    }

    private fun labels(t: AreaMapTables, r: (Long, Int) -> ByteArray, g: Int, n: Int) =
        AreaMap.area(t, MapId(g, n), r)?.floors?.map { it.label }

    @Test fun `FireRed's areas`() {
        val r = rom(fireRed)?.read() ?: return
        val t = AREA_MAP_FIRERED_REV1
        assertEquals(listOf("1", "2", "3"), labels(t, r, 1, 1))                         // Mt. Moon: 1F, B1F, B2F by map order
        assertEquals((1..11).map { "${it}F" }, labels(t, r, 1, 47))                       // Silph Co., its elevator left out
        assertEquals(listOf("B4F", "B3F", "B2F", "B1F"), labels(t, r, 1, 44))             // the Rocket Hideout, from B3F
        assertEquals(listOf("1F", "2F", "3F", "4F", "5F", "RF"), labels(t, r, 10, 0))     // Celadon's department store
        assertEquals(listOf("1F", "2F", "3F", "4F", "5F", "RF", "1"), labels(t, r, 10, 6)) // ... from inside its elevator
        assertNull(AreaMap.area(t, MapId(3, 0), r))                                       // a town: the region map
        assertTrue(AreaMap.area(t, MapId(1, 1), r)!!.worthShowing)
    }

    /** Where the AREA map's line falls ([AreaMap.Floor.large]): only places with a floor two screens across or down, or
     * over three screens of room, get one; inside such a place a room of a screen or less opens on the region map. */
    @Test fun `only large places get an AREA map`() {
        val r = rom(fireRed)?.read() ?: return
        val t = AREA_MAP_FIRERED_REV1
        fun worth(g: Int, n: Int) = AreaMap.area(t, MapId(g, n), r)?.worthShowing == true
        assertFalse(worth(5, 4))      // Viridian's Pokémon Center: two floors of a screen each
        assertFalse(worth(13, 0))     // Indigo Plateau's (25 x 18)
        assertFalse(worth(10, 0))     // Celadon's department store: six floors, none over 19 x 16
        assertFalse(worth(6, 0))      // Pewter's museum (28 x 11)
        assertTrue(worth(11, 3))      // Fuchsia's gym, its invisible walls (15 x 23)
        val silph = AreaMap.area(t, MapId(1, 56), r)!!
        assertTrue(silph.worthShowing)
        assertTrue(silph.opensOn(MapId(1, 56)))   // 10F: under the line itself (17 x 19), but bigger than the screen
        assertFalse(silph.opensOn(MapId(1, 58)))  // its elevator: the region map
        assertTrue(silph.opensOn(MapId(1, 51)))   // 5F
        val ship = AreaMap.area(t, MapId(1, 5), r)!!
        assertTrue(ship.opensOn(MapId(1, 5)))     // the S.S. Anne's corridor
        assertFalse(ship.opensOn(MapId(1, 12)))   // ... a cabin: the region map
        val e = rom(emerald)?.read() ?: return
        assertFalse(AreaMap.area(AREA_MAP_EMERALD, MapId(8, 4), e)?.worthShowing == true)  // Petalburg's Pokémon Center
        assertTrue(AreaMap.area(AREA_MAP_EMERALD, MapId(15, 0), e)!!.worthShowing)         // Sootopolis's ice gym (17 x 26)
    }

    @Test fun `the minimap's picture is a quarter of each side`() {
        val r = rom(fireRed)?.read() ?: return
        val full = AreaMap.render(AREA_MAP_FIRERED_REV1, MapId(1, 1), r)!!
        val mini = AreaMap.shrink(full, 4)
        assertEquals(full.width / 4, mini.width)
        assertEquals(full.height / 4, mini.height)
        assertTrue(mini.argb.distinct().size >= 8)
        File("build/area-maps").apply { mkdirs() }.let { File(it, "mini_mt_moon_1f.png").writeBytes(mini.png()) }
    }

    @Test fun `Emerald's areas`() {
        val r = rom(emerald)?.read() ?: return
        val t = AREA_MAP_EMERALD
        // Mt. Pyre 1F - 6F, whichever floor the player is on.
        assertEquals((1..6).map { "$it" }, labels(t, r, 24, 15))
        assertEquals((1..6).map { "$it" }, labels(t, r, 24, 20))
        assertEquals(MapId(24, 20), AreaMap.area(t, MapId(24, 20), r)!!.floors.last().id)
        assertNull(AreaMap.area(t, MapId(0, 0), r))
    }

    @Test fun `Unbound's areas`() {
        val r = rom(unbound)?.read() ?: return
        val area = AreaMap.area(AREA_MAP_UNBOUND, MapId(53, 1), r)!!
        // Thundercap Mt.: four floors, several maps on some of them (its unnumbered side room has no chip).
        assertEquals(listOf("1F", "2F", "2F-2", "3F", "3F-2", "3F-3", "3F-4", "4F", "4F-2", "4F-3"), area.floors.map { it.label })
    }

    /** Heart and Soul mixes two kinds of layout (+0x18: 0 Emerald's numbers, 2 FireRed's: [AreaMapTables.alt]); one of
     * each draws (build/area-maps/hns_*.png), with the palettes split each kind's way. */
    @Test fun `Heart and Soul's two kinds of layout`() {
        val r = rom("Pokemon Heart and Soul (v2.0.6).gba")?.read() ?: return
        val t = AREA_MAP_TABLES.getValue(0x01713508L)
        val out = File("build/area-maps").apply { mkdirs() }
        for ((id, kind) in listOf(MapId(22, 13) to 0, MapId(24, 0) to 2)) {
            val layout = AreaMap.info(t, id, r)!!.layout
            assertEquals("$id's kind", kind, r(layout + 0x18, 1)[0].toInt())
            for (pals in listOf(6, 7)) {
                val tt = if (kind == 0) t.copy(primaryPalettes = pals) else t.copy(alt = t.alt!!.copy(palettes = pals))
                val img = AreaMap.render(tt, id, r)!!
                assertFalse("$id is flat", img.argb.distinct().size < 8)
                File(out, "hns_${id.group}_${id.num}_kind${kind}_pals$pals.png").writeBytes(img.png())
            }
        }
    }

    @Test fun `every map of these areas renders`() {
        val out = File("build/area-maps").apply { mkdirs() }
        for ((file, t, maps) in listOf(
            Triple(fireRed, AREA_MAP_FIRERED_REV1, listOf(MapId(1, 1), MapId(1, 47), MapId(10, 0))),
            Triple(emerald, AREA_MAP_EMERALD, listOf(MapId(24, 15), MapId(24, 0))),
            Triple(unbound, AREA_MAP_UNBOUND, listOf(MapId(53, 1))),
        )) {
            val r = rom(file)?.read() ?: continue
            for (start in maps) for (f in AreaMap.area(t, start, r)!!.floors) {
                val img = AreaMap.render(t, f.id, r)
                assertNotNull("${file.take(20)} ${f.id}", img)
                assertEquals(f.width * 16, img!!.width)
                // Not one colour: the tiles and palettes came out.
                assertFalse("${f.id} is flat", img.argb.distinct().size < 4)
                File(out, "${file.substringBefore(" (").replace(' ', '_')}_${f.id.group}_${f.id.num}_${f.label}.png").writeBytes(img.png())
            }
        }
    }
}
