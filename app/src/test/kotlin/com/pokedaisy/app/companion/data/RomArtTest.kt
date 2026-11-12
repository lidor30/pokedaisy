package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/**
 * [RomArt] rebuilds the FireRed / Emerald art from the ROM. The ROM tests
 * skip without the retail ROMs on this machine (see [RomFileReader]).
 */
class RomArtTest {
    private fun pixels(img: RomArt.Image): String {
        val crc = CRC32()
        img.argb.forEach { crc.update(byteArrayOf((it ushr 24).toByte(), (it ushr 16).toByte(), (it ushr 8).toByte(), it.toByte())) }
        return "%08x".format(crc.value)
    }

    private fun composed(path: String): Map<String, RomArt.Image> {
        val rom = File(path).takeIf { it.isFile }?.readBytes()
        assumeTrue("no ROM at $path", rom != null)
        return RomArt.compose(RomArt.find(rom!!))
    }

    private fun assertGolden(images: Map<String, RomArt.Image>, golden: Map<String, String>) {
        assertEquals(golden.keys, images.keys)
        golden.forEach { (path, crc) -> assertEquals(path, crc, pixels(images.getValue(path))) }
    }

    @Test fun fireRedSuppliesItsArt() = assertGolden(composed(RomFileReader.FIRERED_REV1_PATH), FIRERED)

    @Test fun emeraldSuppliesItsArt() = assertGolden(composed(RomFileReader.EMERALD_PATH), EMERALD)

    /** Recharged Emerald's own party menu art, Hoenn map and map heads (recompressed and partly redrawn: none of
     * Emerald's fingerprints match it), the same bytes in all three builds. */
    @Test fun rechargedEmeraldSuppliesItsArt() {
        for (f in listOf("Pokemon Recharged Emerald (v2.2.5).gba", "Pokemon Recharged Emerald (v2.2.8).gba", "Pokemon Recharged Emerald Rebalanced (v2.2.8).gba")) {
            val images = composed(System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/" + f)
            val mine = images.filterKeys { it.startsWith(RomArt.RE_PARTY_DIR) || it == RomArt.RE_BACKDROP || it.contains("recharged_emerald") || it.endsWith("_re.png") }
            assertEquals(f, RECHARGED_EMERALD, mine.mapValues { pixels(it.value) })
        }
    }

    /** Glazed wrote its own region map (Tunod + Johto) over retail Emerald's blobs; the rest of its art is retail's. */
    @Test fun glazedSuppliesItsRegionMap() {
        val images = composed(System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/Pokemon Glazed (v9.2.0).gba")
        assertEquals("38897c17", pixels(images.getValue("regionmap/glazed.png")))
        assertTrue("regionmap/hoenn.png" !in images)
    }

    /** Imperium's region map: Emerald's with markers for its new places, on Emerald's palette. */
    @Test fun imperiumSuppliesItsRegionMap() {
        val images = composed(System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/Pokemon Emerald Imperium (v1.3.1).gba")
        assertEquals("a9c4cfb6", pixels(images.getValue("regionmap/imperium.png")))
    }

    /** Team Rocket Edition 2026 v3.1: its seven region maps are v3.0.9's blobs (TR3_*), found by the same fingerprints. */
    @Test fun teamRocketV31SuppliesItsRegionMaps() {
        val images = composed(System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/Pokemon Team Rocket Edition 2026 (v3.1) (Spanish).gba")
        val maps = listOf("tr3_kanto", "tr3_sevii123", "tr3_sevii45", "tr3_sevii67", "tr3_johto", "tr3_hoenn", "tr3_sinnoh")
        for (m in maps) assertTrue(m, "regionmap/$m.png" in images)
        val out = File("build/rom-art").apply { mkdirs() }
        images.getValue("regionmap/tr3_kanto.png").let { File(out, "tr3_1_kanto.png").writeBytes(it.png()) }
    }

    /** Dreamstone Mysteries' Cormoria TOWN MAP (DSM_REGION_*): its own 4bpp tiles, tilemap and palette. */
    @Test fun dreamstoneSuppliesItsRegionMap() {
        val images = composed(System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/Pokemon Dreamstone Mysteries.gba")
        assertEquals("6a43f441", pixels(images.getValue("regionmap/dreamstone.png")))
    }

    /** Wishes of Tomorrow's JOKAIM TOWN MAP (WSH_REGION_*): its own 8bpp tiles and tilemap (smol) and palette; the
     * player's head on it is Emerald's own Brendan / May, which its ROM keeps. */
    @Test fun wishesSuppliesItsRegionMap() {
        val images = composed(System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/Pokemon Wishes of Tomorrow (v1.2.6).gba")
        assertEquals("f4215285", pixels(images.getValue("regionmap/wishes.png")))
        assertTrue("regionmap/player_brendan.png" in images && "regionmap/player_may.png" in images)
    }

    /** Monomyth's ATHANTO MAP (MMY_REGION_*: its PokeNav's, a text screen of its own) and the one head its map code
     * loads: the map matched the game's own screen headless, pixel for pixel, but for its place-name window. */
    @Test fun monomythSuppliesItsRegionMap() {
        for (v in listOf("v1.0", "v1.1")) {
            val images = composed(System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/Pokemon Monomyth ($v).gba")
            assertEquals("bed46b86", pixels(images.getValue("regionmap/monomyth.png")))
            assertEquals("867f8756", pixels(images.getValue("regionmap/player_monomyth.png")))
        }
    }

    /** Super Mariomon's world map (MM_REGION_*, the same bytes in every release) and MARIO's head for it. */
    @Test fun mariomonSuppliesItsRegionMap() {
        for (v in listOf("v1.2.1", "v1.2.2", "v1.3", "v1.5.1 Anniversary", "v1.5.2 Anniversary")) {
            val path = System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/Super Mariomon ($v).gba"
            if (!File(path).isFile) continue
            val images = RomArt.compose(RomArt.find(File(path).readBytes()))
            assertEquals(v, "4031a74a", pixels(images.getValue("regionmap/mariomon.png")))
            assertEquals(v, "c29e9f3a", pixels(images.getValue("regionmap/player_mario.png")))
            assertTrue("regionmap/hoenn.png" !in images)
        }
    }

    /** Let's Go Pikachu / Eevee's party menu: CFRU's slot tilemaps over its own tiles / palette / grid / status icons (LGO_*),
     * the same in both versions; its slots the game's own colours (a selected one's fill 16,115,165, sampled headless). */
    @Test fun letsGoSuppliesItsPartyMenu() {
        for (v in listOf("Pikachu", "Eevee")) {
            val path = System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/Pokemon Let's Go $v.gba"
            if (!File(path).isFile) continue
            val images = RomArt.compose(RomArt.find(File(path).readBytes()))
            val slots = listOf("normal", "selected", "fainted", "selected_fainted", "nohp_normal", "nohp_selected", "empty")
            for (k in slots) assertTrue("$v $k", "${RomArt.LETS_GO_PARTY_DIR}/slot_$k.png" in images)
            assertTrue(v, RomArt.LETS_GO_BACKDROP in images && "${RomArt.LETS_GO_PARTY_DIR}/status_icons.png" in images)
            val selected = images.getValue("${RomArt.LETS_GO_PARTY_DIR}/slot_selected.png")
            assertEquals(v, 0xFF1073A5.toInt(), selected.argb.groupBy { it }.maxBy { it.value.size }.key)
            // CFRU's own palette isn't in it: its partycfru/ slots come from another CFRU ROM, not this one.
            assertTrue(v, "partycfru/slot_normal.png" !in images)
        }
    }

    /** Quetzal's Kanto / Sevii maps: FireRed's art redrawn for Emerald's region_map.c (QTZ_*). */
    @Test fun quetzalSuppliesItsRegionMaps() {
        val images = composed(System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/Pokemon Quetzal (Alpha 9.0) (English).gba")
        assertEquals(
            listOf("eee6e9af", "307eba25", "f356dacb", "508b250a"),
            listOf("kanto", "sevii123", "sevii45", "sevii67").map { pixels(images.getValue("regionmap/quetzal_$it.png")) },
        )
        // Its Johto is its own, on Emerald's palette; its Hoenn is Emerald's map (the same bytes, so the same image).
        assertEquals("e649e781", pixels(images.getValue("regionmap/quetzal_johto.png")))
        // extractTo keeps only what OUTPUTS lists: built but not listed, a player never gets it.
        assertTrue("regionmap/quetzal_johto.png" in RomArt.OUTPUTS)
        assertEquals(pixels(composed(RomFileReader.EMERALD_PATH).getValue("regionmap/${regionMapImagesEmerald[0]}.png")),
                     pixels(images.getValue("regionmap/${regionMapImagesEmerald[0]}.png")))
    }

    /** Heart and Soul's Johto + Kanto Pokegear map (smol, HNS_REGION_*), on Emerald's palette. */
    @Test fun heartAndSoulSuppliesItsRegionMap() {
        val images = composed(System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/Pokemon Heart and Soul (v2.0.6).gba")
        assertEquals("54343313", pixels(images.getValue("regionmap/hns.png")))
    }

    /**
     * Heart and Soul's party menu (smol HNS_PARTY_* / _BALL_* / _STATUS_* / _FONT_SMALL, Emerald's slot
     * tilemaps). Its slot frames come out pixel for pixel Emerald's; all of it matched the PNGs the app
     * bundled before.
     */
    @Test fun heartAndSoulSuppliesItsPartyArt() {
        val images = composed(GBA + "Pokemon Heart and Soul (v2.0.6).gba")
        assertGolden(images.filterKeys { it.startsWith("partyhns/") || it == "partybg/hns.png" }, HEART_AND_SOUL)
    }

    /**
     * The CFRU hacks' shared party menu (CFRU_*: 112x40 slots + the grid backdrop's cell) and each
     * one's status icons; Unbound's Poke Ball is FireRed's (partyfr/pokeball.png). All of it matched
     * the PNGs the app bundled before. Some ROMs also carry another hack's status sheet (the same
     * bytes, so the same image).
     */
    @Test fun cfruHacksSupplyTheirPartyArt() {
        val roms = mapOf(
            "Pokemon Unbound (v2.1.1.1).gba" to "ub", "Pokemon Radical Red (v4.1).gba" to "rr",
            "Pokemon Odyssey (v4.1.1).gba" to "od", "Pokemon Amethyst (v1.3.0).gba" to "am",
            "Pokemon Amethyst (v1.4.1).gba" to "am",
        ).filterKeys { File(GBA + it).isFile }
        assumeTrue("no CFRU ROM here", roms.isNotEmpty())
        for ((rom, sub) in roms) {
            val images = composed(GBA + rom)
            val status = "partycfru/$sub/status_icons.png"
            assertGolden(images.filterKeys { (it.startsWith("partycfru/slot_") || it == RomArt.CFRU_BACKDROP) || it == status },
                CFRU + (status to CFRU_STATUS.getValue(sub)))
            images.filterKeys { it.startsWith("partycfru/") && it.endsWith("/status_icons.png") }.forEach { (path, img) ->
                assertEquals("$rom $path", CFRU_STATUS.getValue(path.split('/')[1]), pixels(img))
            }
        }
        if ("Pokemon Unbound (v2.1.1.1).gba" in roms) {
            assertEquals("53f5f1ea", pixels(composed(GBA + "Pokemon Unbound (v2.1.1.1).gba").getValue("partyfr/pokeball.png")))
        }
    }

    /**
     * Unbound FR (GitHub #227): its own slot tiles ("PV"), status icons and FONT_SMALL (its level glyph "N.") under
     * partycfru_fr/ - never English Unbound's into partycfru/, which it showed until an English scan supplied them. Its
     * slots differ from CFRU's only where "HP" was; the rest of the frame is the same pixels.
     */
    @Test fun unboundFrSuppliesItsPartyArt() {
        val images = composed(GBA + "Pokemon Unbound (v2.1.1.1) (French).gba")
        val dir = RomArt.UNBOUND_FR_PARTY_DIR
        assertEquals((listOf("normal", "selected", "fainted", "selected_fainted", "nohp_normal", "nohp_selected", "empty")
            .map { "$dir/slot_$it.png" } + "$dir/status_icons.png" + "$dir/font_small.png").toSet(),
            images.keys.filter { it.startsWith("$dir/") }.toSet())
        assertEquals(emptySet<String>(), images.keys.filter { it.startsWith("partycfru/slot_") || it == "partycfru/ub/status_icons.png" }.toSet())
        // The empty slot has no HP bar: the same as English Unbound's.
        assertEquals(CFRU.getValue("partycfru/slot_empty.png"), pixels(images.getValue("$dir/slot_empty.png")))
        assertTrue(CFRU.getValue("partycfru/slot_normal.png") != pixels(images.getValue("$dir/slot_normal.png")))
    }

    /**
     * Sword and Shield Ultimate Plus: CFRU's tiles and slot tilemaps in its own palette - its own
     * frames (the striped empty slot too) and grid cell, never CFRU's shared ones; its status icons
     * are Odyssey's. Pinned to scripts/gen_cfru_party_assets.py's decode of the same ROM.
     */
    @Test fun swordShieldSuppliesItsPartyArt() {
        val images = composed(GBA + "Pokemon Sword and Shield Ultimate Plus (v1.2.1.2).gba")
        assertGolden(images.filterKeys { it.startsWith("partyswsh/") || it == RomArt.SWSH_BACKDROP }, SWSH)
        assertEquals(CFRU_STATUS.getValue("od"), pixels(images.getValue("partycfru/od/status_icons.png")))
        assertEquals(emptySet<String>(), images.keys.filter { it.startsWith("partycfru/slot_") || it == RomArt.CFRU_BACKDROP }.toSet())
    }

    @Test fun romWithoutTheArtGivesNothing() {
        val rom = ByteArray(1 shl 20) { (it * 31 + (it ushr 7)).toByte() }
        assertTrue(RomArt.compose(RomArt.find(rom)).isEmpty())
    }

    @Test fun cachedPngsAreWrittenOnce() {
        val dir = File("build/rom-art-test").apply { deleteRecursively() }
        val rom = File(RomFileReader.FIRERED_REV1_PATH).takeIf { it.isFile }?.readBytes()
        assumeTrue(rom != null)
        val first = RomArt.extractTo(rom!!, dir)
        val raw = RomArt.raw(RomArt.find(rom))
        assertEquals(FIRERED.keys + raw.keys, first.toSet())
        assertTrue(first.filter { it.endsWith(".png") }.all { File(dir, it).readBytes().copyOfRange(1, 4).contentEquals("PNG".toByteArray()) })
        // The trainer card's blobs are kept as they decode.
        raw.forEach { (path, bytes) -> assertTrue(path, File(dir, path).readBytes().contentEquals(bytes)) }
        assertEquals(emptyList<String>(), RomArt.extractTo(rom, dir))
    }

    private companion object {
        val GBA = System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/gba/"
        val HEART_AND_SOUL = mapOf(
            "partybg/hns.png" to "aeda6295",
            "partyhns/font_small.png" to "10b2cfee",
            "partyhns/pokeball.png" to "53f5f1ea", // FireRed's / Emerald's ball
            // Emerald's slot frames, pixel for pixel.
            "partyhns/slot_fainted.png" to "988df89d",
            "partyhns/slot_nohp_normal.png" to "0402fa82",
            "partyhns/slot_nohp_selected.png" to "ed6e77fa",
            "partyhns/slot_normal.png" to "b868b751",
            "partyhns/slot_selected.png" to "06077c6c",
            "partyhns/slot_selected_fainted.png" to "b0393f12",
            "partyhns/status_icons.png" to "b27d09eb",
        )
        val CFRU = mapOf(
            "partybg/cfru_tile.png" to "734c1189",
            "partycfru/slot_empty.png" to "c2ed34d7",
            "partycfru/slot_fainted.png" to "2dcf7519",
            "partycfru/slot_nohp_normal.png" to "99193b63",
            "partycfru/slot_nohp_selected.png" to "6234b842",
            "partycfru/slot_normal.png" to "e9603ce2",
            "partycfru/slot_selected.png" to "ec246dad",
            "partycfru/slot_selected_fainted.png" to "f83c49af",
        )
        val SWSH = mapOf(
            "partybg/swsh_tile.png" to "10269a9f",
            "partyswsh/slot_empty.png" to "559e285b",
            "partyswsh/slot_fainted.png" to "9e1b3924",
            "partyswsh/slot_nohp_normal.png" to "5800fc35",
            "partyswsh/slot_nohp_selected.png" to "fdc1d5db",
            "partyswsh/slot_normal.png" to "12fcecf5",
            "partyswsh/slot_selected.png" to "5452336b",
            "partyswsh/slot_selected_fainted.png" to "c3dee479",
        )
        val CFRU_STATUS = mapOf("ub" to "2e442add", "rr" to "dddaa119", "od" to "a20e9bf7", "am" to "bc0660eb")
        // CRC32 of each image's ARGB pixels; the images matched the PNGs the app
        // used to bundle (colour rounding aside) when these were recorded.
        val FIRERED = mapOf(
            "partybg/firered.png" to "e9fcbfdc",
            "partyem/pokeball.png" to "53f5f1ea", // the same ball
            "partyfr/font_small.png" to "74bc5054",
            "partyfr/hold_icons.png" to "3921cfb7",
            "typetags/firered.png" to "72f17597",
            "partyfr/pokeball.png" to "53f5f1ea",
            "partyfr/slot_fainted.png" to "988df89d",
            "partyfr/slot_nohp_normal.png" to "0402fa82",
            "partyfr/slot_nohp_selected.png" to "ed6e77fa",
            "partyfr/slot_normal.png" to "b868b751",
            "partyfr/slot_selected.png" to "06077c6c",
            "partyfr/slot_selected_fainted.png" to "b0393f12",
            "partyfr/status_icons.png" to "bbd9145e",
            "regionmap/kanto.png" to "2ff27d4d",
            "regionmap/player_leaf.png" to "6d6f7465",
            "regionmap/player_red.png" to "547baad2",
            "regionmap/sevii123.png" to "d5d0d9cd",
            "regionmap/sevii45.png" to "05ec1562",
            "regionmap/sevii67.png" to "f94ac4e7",
        )
        val EMERALD = mapOf(
            "partybg/emerald.png" to "4eb5fbd3",
            "partyem/font_small.png" to "f8fb2855",
            "partyem/pokeball.png" to "53f5f1ea",
            "partyem/slot_fainted.png" to "988df89d",
            "partyem/slot_nohp_normal.png" to "0402fa82",
            "partyem/slot_nohp_selected.png" to "ed6e77fa",
            "partyem/slot_normal.png" to "b868b751",
            "partyem/slot_selected.png" to "06077c6c",
            "partyem/slot_selected_fainted.png" to "b0393f12",
            "partyem/status_icons.png" to "5c0fddd3",
            "partyfr/hold_icons.png" to "3921cfb7", // the same marker
            "typetags/emerald.png" to "d7d9220e",
            "partyfr/pokeball.png" to "53f5f1ea", // the same ball
            "regionmap/hoenn.png" to "27d88e99",
            "regionmap/player_brendan.png" to "ffb2b151",
            "regionmap/player_may.png" to "e7e272c8",
        )
    }

    private val RECHARGED_EMERALD = mapOf(
            "partybg/recharged_emerald.png" to "e9fcbfdc",
            "partyre/font_small.png" to "a1f3b06a",
            "partyre/slot_fainted.png" to "988df89d",
            "partyre/slot_nohp_normal.png" to "0402fa82",
            "partyre/slot_nohp_selected.png" to "ed6e77fa",
            "partyre/slot_normal.png" to "b868b751",
            "partyre/slot_selected.png" to "06077c6c",
            "partyre/slot_selected_fainted.png" to "b0393f12",
            "partyre/status_icons.png" to "5c0fddd3",
            "regionmap/player_brendan_re.png" to "c1356739",
            "regionmap/player_may_re.png" to "7b114caf",
            "regionmap/recharged_emerald.png" to "e4f9110b",
    )
}
