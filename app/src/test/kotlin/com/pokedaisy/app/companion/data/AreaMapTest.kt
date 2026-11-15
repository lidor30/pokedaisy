package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.data.AreaMap.MapId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test fun `each game's tables match its own ROM only`() {
        val tables = listOf(fireRed to AREA_MAP_FIRERED_REV1, emerald to AREA_MAP_EMERALD, unbound to AREA_MAP_UNBOUND)
        for ((file, _) in tables) {
            val r = rom(file)?.read() ?: continue
            for ((other, t) in tables) assertEquals("$other's tables on $file", file == other, AreaMap.matches(t, r))
        }
    }

    @Test fun `the configs carry them`() {
        assertEquals(AREA_MAP_FIRERED_REV1, NATIVE_FIRERED_REV1.areaMap)
        assertEquals(AREA_MAP_EMERALD, NATIVE_EMERALD_RETAIL.areaMap)
        assertEquals(AREA_MAP_UNBOUND, NATIVE_UNBOUND_WITH_DEX.areaMap)
        assertNull(NATIVE_FIRERED_REV0.areaMap) // the CFRU hacks' base
        assertNull(NATIVE_EMERALD.areaMap)       // the Emerald hacks' base
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
