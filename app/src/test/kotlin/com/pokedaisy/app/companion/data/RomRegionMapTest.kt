package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [RomRegionMap] reads a FireRed-engine hack's own region map from its ROM.
 * The hack tests skip without the ROMs on this machine. Highlights were
 * checked by eye against each map (every town's box on its town).
 */
class RomRegionMapTest {
    private fun read(name: String): RomRegionMap.Parsed {
        val f = File(ROMS, name)
        assumeTrue("no ROM at $f", f.isFile)
        return RomRegionMap.read(RomRegionMap.BytesRom(f.readBytes()))!!
    }

    /** Clover: FireRed 1.0's region map code under an Emerald header, let through by its GF header ([isFireRedUnderEmeraldHeader]). */
    @Test fun cloverHasItsOwnRegion() {
        val f = File(ROMS, "Pokemon Clover (v1.3.3).gba")
        assumeTrue("no ROM at $f", f.isFile)
        assertNull(RomRegionMap.read(RomRegionMap.BytesRom(f.readBytes()))) // BPEE: not read unless told it's FireRed's
        assertTrue(isFireRedUnderEmeraldHeader(f.readBytes().copyOf(0x200)))
        val m = RomRegionMap.read(RomRegionMap.BytesRom(f.readBytes()), fireRed = true)!!
        assertEquals("Veepier Town", m.sections[0x58]!!.name)
    }

    /** Elysium (both parts): its own regions on FireRed 1.0's region map code. */
    @Test fun elysiumHasItsOwnRegions() {
        for (part in listOf("A", "B")) {
            val f = File(ROMS, "Pokemon Elysium (v2.5.0) (Part $part).gba")
            assumeTrue("no ROM at $f", f.isFile)
            val m = RomRegionMap.read(RomRegionMap.BytesRom(f.readBytes()))!!
            assertEquals(part, "Astrantia City", m.sections[0x58 + 6]!!.name)
        }
    }

    @Test fun unboundHasBorrius() {
        val m = read("Pokemon Unbound (v2.1.1.1).gba")
        assertEquals(1, m.images.size) // every region slot is Borrius
        // Its 2-tile backdrop border and the missing last row are cut off, so
        // the grid's (4, 4) offset is (2, 2) here.
        assertEquals(26 * 8 to 17 * 8, m.images[0].width to m.images[0].height)
        assertEquals(MapSecInfo("Bellin Town", 0, 12, 4, 1, 1), m.sections[89])
        // Its cursor grid, from the ROM too: a tap on Bellin Town names it.
        val model = RegionMapModel(arrayOf("borrius"), m.sections, m.layouts)
        assertEquals(89, model.pick(0, 12, 4)?.mapsec)
        assertEquals(MapSecInfo("Dehara City", 0, 10, 7, 1, 3), m.sections[98])
        // Past the Sevii start FireRed had, still on Borrius.
        assertEquals(MapSecInfo("Magnolia Town", 0, 20, 4, 1, 1), m.sections[145])
        // Kept from FireRed in capitals; shown in the hack's own case.
        assertEquals("Indigo Plateau", m.sections[97]!!.name)
        // Not on the map: the map, nothing highlighted.
        assertEquals(MapSecInfo("KBT Expressway", 0, 0, 0, 0, 0), m.sections[134])
    }

    @Test fun odysseyHasItsOwnRegion() {
        val m = read("Pokemon Odyssey (v4.1.1).gba")
        // Its own map; FireRed's three Sevii screens, drawn in its tiles (junk, never shown), are dropped.
        assertEquals(1, m.images.size)
        assertEquals(MapSecInfo("Fibernia", 0, 6, 13, 1, 1), m.sections[89])
        assertEquals(MapSecInfo("Charon", 0, 18, 10, 1, 1), m.sections[144])
    }

    /** Sword and Shield Ultimate Plus: Galar on the first screen; FireRed's Sevii screens drawn in its tiles are junk. */
    @Test fun swordShieldHasGalar() {
        val m = read("Pokemon Sword and Shield Ultimate Plus (v1.2.1.2).gba")
        assertEquals(208 to 144, m.images[0].width to m.images[0].height)
        assertEquals(MapSecInfo("Postwick", 0, 10, 15, 1, 1), m.sections[0x58])
        assertEquals(MapSecInfo("Wyndon", 0, 4, 5, 1, 1), m.sections[0x61])
        // On a junk Sevii screen in the ROM: shown on Galar, nothing highlighted.
        assertEquals(MapSecInfo("Isle Of Armor", 0, 0, 0, 0, 0), m.sections[0x8F])
        assertEquals(1, m.images.size) // no MAP 2: the junk screens are gone
        val model = RegionMapModel(arrayOf("galar"), m.sections, m.layouts)
        assertEquals(0x58, model.pick(0, 10, 15)?.mapsec)
    }

    @Test fun amethystKeepsSeviiScreens() {
        val m = read("Pokemon Amethyst (v1.3.0).gba")
        assertEquals(0, m.sections[0x98]!!.region)
        assertEquals(1, m.sections[0x99]!!.region)
    }

    @Test fun amethystV141KeepsSeviiScreens() {
        val m = read("Pokemon Amethyst (v1.4.1).gba")
        assertEquals(0, m.sections[0x98]!!.region)
        assertEquals(1, m.sections[0x99]!!.region)
    }

    /** Orange Islands: its archipelago over FireRed's four screens (VALENCIA on the second, PUMMELO the third). */
    @Test fun orangeIslandsHasItsArchipelago() {
        val m = read("Pokemon Orange Islands.gba")
        assertEquals(4, m.images.size)
        assertEquals(MapSecInfo("Valencia Island", 1, 6, 13, 1, 1), m.sections[0x65])
        assertEquals(MapSecInfo("Mikan Island", 1, 3, 9, 1, 1), m.sections[91])
        assertEquals(2, m.sections[147]!!.region) // PUMMELO ISLAND
    }

    /** Ash Gray (both builds): its own Kanto, then the Orange Islands on the second screen (FireRed's Sevii ids). */
    @Test fun ashGrayHasTheOrangeIslands() {
        for (rom in listOf("Pokemon Ash Gray (v4.5.3).gba", "Pokemon Ash Gray Fork Bug Fix (v1.0).gba")) {
            val m = read(rom)
            assertEquals(2, m.images.size)
            assertEquals(MapSecInfo("Pallet Town", 0, 6, 13, 1, 1), m.sections[88])
            assertEquals(MapSecInfo("Valencia Island", 1, 7, 14, 1, 1), m.sections[143])
            assertEquals(MapSecInfo("Shamouti Island", 1, 13, 8, 1, 3), m.sections[171])
        }
    }

    /** Ghost Grey (v1.0.1 and v1.0.4): its own grey Kanto on FireRed's four screens, its renamed towns. */
    @Test fun ghostGreyHasItsKanto() {
        for (rom in listOf("Pokemon Ghost Grey (v1.0.1).gba", "Pokemon Ghost Grey (v1.0.4).gba")) {
            val m = read(rom)
            assertEquals(4, m.images.size)
            assertEquals(MapSecInfo("Celidon City", 0, 13, 8, 1, 1), m.sections[94])
            assertEquals(MapSecInfo("Jaspar Town", 0, 20, 5, 3, 1), m.sections[110])
        }
    }

    /** Unbound's French translation: English's map, French names (its revision byte is 0x9E, not 0). */
    @Test fun unboundFrenchHasBorriusInFrench() {
        val m = read("Pokemon Unbound (v2.1.1.1) (French).gba")
        assertEquals(1, m.images.size)
        assertEquals(MapSecInfo("Bélenbourg", 0, 12, 4, 1, 1), m.sections[89])
    }

    @Test fun gaiaIsOneMap() {
        val m = read("Pokemon Gaia (v3.2).gba")
        assertEquals(setOf(0), m.sections.values.map { it.region }.toSet())
        assertEquals(1, m.images.size) // FireRed's three Sevii screens in its tiles, none of its sections on them: dropped
    }

    /**
     * GS Chronicles: Johto on the first screen, Kanto on the second (FireRed's Sevii 1-3 slot). The other two are
     * FireRed's Sevii tilemaps in its tiles, holding only its Kanto routes 12-27 (three of 21 with a box): dropped.
     */
    @Test fun gsChroniclesHasJohtoAndKanto() {
        val m = read("Pokemon GS Chronicles (v2.7.6).gba")
        assertEquals(2, m.images.size)
        assertEquals(MapSecInfo("New Bark Town", 0, 21, 12, 1, 1), m.sections[0x58])
        assertEquals(MapSecInfo("Route 29", 0, 18, 12, 3, 1), m.sections[0x65])
        assertEquals(1, m.sections[0x94]!!.region) // Pewter City
        assertEquals(MapSecInfo("Route 12", 0, 0, 0, 0, 0), m.sections[0xB0])
        val model = RegionMapModel(arrayOf("johto", "kanto"), m.sections, m.layouts)
        assertEquals(0x58, model.pick(0, 21, 12)?.mapsec)
    }

    @Test fun otherRomsGiveNothing() {
        assertNull(RomRegionMap.read(RomRegionMap.BytesRom(ByteArray(1 shl 20))))
        // FireRed rev 1 has its code elsewhere (and its own tables anyway).
        val rev1 = File(RomFileReader.FIRERED_REV1_PATH)
        assumeTrue(rev1.isFile)
        assertNull(RomRegionMap.read(RomRegionMap.BytesRom(rev1.readBytes())))
    }

    private companion object {
        val ROMS = File(RomFileReader.FIRERED_REV1_PATH).parentFile
    }
}
