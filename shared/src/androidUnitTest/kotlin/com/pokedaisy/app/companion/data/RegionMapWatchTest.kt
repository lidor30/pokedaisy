package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** [RegionMapWatch] finds each ROM's map pointers; skips without the ROMs on this machine. */
class RegionMapWatchTest {
    private fun find(path: String): List<RegionMapWatch.Watch> {
        val f = File(path)
        assumeTrue("no ROM at $f", f.isFile)
        return RegionMapWatch.find(f.inputStream().use { it.readNBytes(4 shl 20) })
    }

    private fun rom(name: String) = File(File(RomFileReader.FIRERED_REV1_PATH).parentFile, name).path

    @Test fun fireRedFamilyWatchesSRegionMap() {
        for (name in listOf(
            "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba",
            "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba",
            "Pokemon Unbound (v2.1.1.1).gba", "Pokemon Odyssey (v4.1.1).gba",
            "Pokemon Gaia (v3.2).gba", "Pokemon Radical Red (v4.1).gba", "Pokemon Amethyst (v1.3.0).gba",
            // FireRed 1.0 under Emerald's game code.
            "Pokemon Clover (v1.3.3).gba",
        )) {
            assertEquals(name, listOf(RegionMapWatch.Watch(0x020399D4L)), find(rom(name)))
        }
    }

    /** Recharged Yellow's gcc pools: sRegionMap two words before the offset. Headless, with the Town Map used from
     * the bag: 0 on the field, a pointer while it's open, 0 again back in the bag - the word right before the offset
     * (0x0203779C in v1.8.0) went 0 -> 1 and stayed 1. */
    @Test fun rechargedYellowWatchesItsOwnSRegionMap() {
        assertEquals(listOf(RegionMapWatch.Watch(0x02039ABCL)), find(rom("Pokemon Recharged Yellow (v1.8.0).gba")))
        for (v in listOf("1.9.0", "1.9.4", "1.9.7")) {
            assertEquals(v, listOf(RegionMapWatch.Watch(0x02039AC4L)), find(rom("Pokemon Recharged Yellow (v$v).gba")))
        }
    }

    @Test fun emeraldWatchesTownFlyAndPokenavMaps() {
        for (path in listOf(RomFileReader.EMERALD_PATH)) {
            assertEquals(
                path,
                listOf(
                    RegionMapWatch.Watch(0x0203BCD0L), RegionMapWatch.Watch(0x0203A148L),
                    // gPokenavResources->substructPtrs[POKENAV_SUBSTRUCT_REGION_MAP_STATE]
                    RegionMapWatch.Watch(0x0203CF40L, 0x1C),
                ),
                find(path),
            )
        }
    }

    @Test fun emeraldHacksAreLeftAlone() {
        assertEquals(emptyList<RegionMapWatch.Watch>(), find(rom("Pokemon Emerald Seaglass (v3.0).gba")))
        assertEquals(emptyList<RegionMapWatch.Watch>(), find(rom("Pokemon Heart and Soul (v2.0.6).gba")))
    }
}
