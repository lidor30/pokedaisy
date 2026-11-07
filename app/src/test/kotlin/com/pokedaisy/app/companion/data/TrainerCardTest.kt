package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/** The TRAINER CARD: its data per fixture save, and the card drawn from the retail ROMs. */
class TrainerCardTest {
    private fun card(key: String, cfg: NativeConfig) = readTrainerCard(FixtureMemoryReader.load(key), cfg)!!

    @Test fun `FireRed - the save's card`() {
        val c = card("firered_vanilla", NATIVE_FIRERED_REV1)
        assertEquals("LIDOR", c.nameText)
        assertEquals(49151, c.trainerId)
        assertEquals(224300L, c.money)
        assertEquals(0xFF, c.badges)
        assertEquals(1, c.stars) // the Hall of Fame
        assertEquals((45 shl 16) or (20 shl 8) or 53, c.hofDebut)
        assertEquals(69, c.dexCaught) // Kanto, before the National Dex
        assertEquals(CardStyle.KANTO, c.style)
    }

    @Test fun `Emerald - the save's card`() {
        val c = card("emerald_vanilla", NATIVE_EMERALD_RETAIL)
        assertEquals("LIDOR", c.nameText)
        assertEquals(7450, c.trainerId)
        assertEquals(185092L, c.money)
        assertEquals(0x3F, c.badges)
        assertEquals(0, c.stars)
        assertEquals(0, c.hofDebut)
        assertEquals(28, c.dexCaught) // Hoenn
        assertEquals(CardStyle.HOENN, c.style)
    }

    @Test fun `LeafGreen - FireRed's card`() {
        val c = card("leafgreen_rev1", NATIVE_LEAFGREEN_REV1)
        assertEquals(6098L, c.money)
        assertEquals(CardStyle.KANTO, c.style)
    }

    @Test fun `Unbound - its save, read like FireRed's`() {
        // What Unbound's own card shows for this save: Lidor, IDNo.06174, ₽3848, Pokédex 1, 0:25.
        val c = card("unbound", NATIVE_UNBOUND_WITH_DEX)
        assertEquals("Lidor", c.nameText)
        assertEquals(6174, c.trainerId)
        assertEquals(3848L, c.money)
        assertEquals(1, c.dexCaught)
        assertEquals(0 to 25, c.hours to c.minutes)
        assertEquals(CardStyle.UNBOUND, c.style)
    }

    @Test fun `Glazed - retail Emerald's card, its own art`() {
        // What its card shows for this save (headless): LIDOR, IDNo.03800, ₽3000, 0:15; no POKéDEX yet.
        val c = card("glazed", NATIVE_GLAZED)
        assertEquals("LIDOR", c.nameText)
        assertEquals(3800, c.trainerId)
        assertEquals(3000L, c.money)
        assertEquals(0 to 15, c.hours to c.minutes)
        assertNull(c.dexCaught)
        assertEquals(CardStyle.GLAZED, c.style)
    }

    @Test fun `Imperium - its own save offsets`() {
        // Its card for this save (headless): Lidor, IDNo.48238, ₽3000, 0:22.
        val c = card("imperium", NATIVE_IMPERIUM)
        assertEquals("Lidor", c.nameText)
        assertEquals(48238, c.trainerId)
        assertEquals(3000L, c.money)
        assertEquals(0 to 22, c.hours to c.minutes)
        assertEquals(CardStyle.IMPERIUM, c.style)
    }

    @Test fun `other hacks get no card`() {
        assertNull(readTrainerCard(FixtureMemoryReader.load("unbound"), NATIVE_UNBOUND))
        assertNull(readTrainerCard(FixtureMemoryReader.load("emerald_rogue"), NATIVE_EMERALD_ROGUE))
    }

    private fun art(path: String, style: CardStyle): TrainerCardArt.Art {
        val rom = File(path).takeIf { it.isFile }?.readBytes()
        assumeTrue("no ROM at $path", rom != null)
        val found = RomArt.find(rom!!)
        return TrainerCardArt.art(style) { found[it] }!!
    }

    private fun crc(img: RomArt.Image): String {
        val crc = CRC32()
        img.argb.forEach { crc.update(byteArrayOf((it ushr 24).toByte(), (it ushr 16).toByte(), (it ushr 8).toByte(), it.toByte())) }
        return "%08x".format(crc.value)
    }

    /** Also writes each card to build/trainer-card/ to look at. */
    private fun rendered(art: TrainerCardArt.Art, c: TrainerCardInfo, back: Boolean, name: String, colon: Boolean = true): String {
        val img = TrainerCardArt.render(art, c, back, colon)
        File("build/trainer-card").apply { mkdirs() }.resolve("$name.png").writeBytes(img.png())
        return crc(img)
    }

    @Test fun `FireRed card - drawn like the game`() {
        val art = art(RomFileReader.FIRERED_REV1_PATH, CardStyle.KANTO)
        val c = card("firered_vanilla", NATIVE_FIRERED_REV1)
        val more = c.copy(female = true, stars = 4, trades = 12, linkWins = 3, linkLosses = 1, unionRoom = 7, berryCrush = 99)
        rendered(art, more, false, "firered_female_front")
        rendered(art, more, true, "firered_female_back")
        val front = rendered(art, c, false, "firered_front")
        val back = rendered(art, c, true, "firered_back")
        assertEquals(FR_FRONT to FR_BACK, front to back)
    }

    @Test fun `Emerald card - drawn like the game`() {
        val art = art(RomFileReader.EMERALD_PATH, CardStyle.HOENN)
        val c = card("emerald_vanilla", NATIVE_EMERALD_RETAIL)
        val more = c.copy(
            female = true, stars = 4, hofDebut = (12 shl 16) or (3 shl 8) or 4, trades = 12, linkWins = 3, linkLosses = 1,
            linkContests = 2, linkPokeblocks = 5, battlePoints = 140,
        )
        rendered(art, more, false, "emerald_female_front")
        rendered(art, more, true, "emerald_female_back")
        // The screenshot was a minute later than this capture (31:26).
        val front = rendered(art, c.copy(minutes = 27), false, "emerald_front")
        val back = rendered(art, c, true, "emerald_back")
        assertEquals(EM_FRONT to EM_BACK, front to back)
    }

    @Test fun `Unbound card - FireRed's in Unbound's colours`() {
        val art = art(RomFileReader.UNBOUND_PATH, CardStyle.UNBOUND)
        val c = card("unbound", NATIVE_UNBOUND_WITH_DEX)
        rendered(art, c.copy(female = true), false, "unbound_female_front")
        val front = rendered(art, c, false, "unbound_front")
        val back = rendered(art, c, true, "unbound_back")
        assertEquals(UB_FRONT to UB_BACK, front to back)
    }

    @Test fun `Emerald Legacy card - drawn like the game, its black card too`() {
        val art = art(RETAIL_ROM_DIR + "Pokemon Emerald Legacy (v1.1.4).gba", CardStyle.LEGACY)
        val c = card("emerald_legacy", NATIVE_EMERALD_LEGACY)
        val five = card("emerald_legacy_stars", NATIVE_EMERALD_LEGACY)
        val got = listOf(
            rendered(art, c, false, "legacy_front"), rendered(art, c, true, "legacy_back"),
            // That screenshot caught the time's colon blinked off.
            rendered(art, five, false, "legacy_stars_front", colon = false), rendered(art, five, true, "legacy_stars_back"),
        )
        assertEquals(listOf(LG_FRONT, LG_BACK, LG_STARS_FRONT, LG_STARS_BACK), got)
    }

    @Test fun `Lazarus - its own save offsets and star rules`() {
        // Its card for this save (headless): Kir, IDNo.28999, ₽38071, Pokédex 105, 18:53, five badges, no stars.
        val c = card("lazarus_card", NATIVE_LAZARUS)
        assertEquals("Kir", c.nameText)
        assertEquals(28999, c.trainerId)
        assertEquals(38071L, c.money)
        assertEquals(105, c.dexCaught)
        assertEquals(18 to 53, c.hours to c.minutes)
        assertEquals(0x1F, c.badges)
        assertEquals(0 to 0, c.stars to c.drawnStars)
        // Everything poked in (lazarus_card_max's README): the gold card (4) and four stars drawn by its own rule.
        val m = card("lazarus_card_max", NATIVE_LAZARUS)
        assertEquals(4 to 4, m.stars to m.drawnStars)
        assertEquals(0xFF, m.badges)
    }

    @Test fun `Lazarus card - drawn like the game`() {
        val art = art(RETAIL_ROM_DIR + "Pokemon Lazarus (v2.0).gba", CardStyle.LAZARUS)
        val c = card("lazarus_card", NATIVE_LAZARUS)
        val m = card("lazarus_card_max", NATIVE_LAZARUS)
        val got = listOf(
            rendered(art, c, false, "lazarus_front"), rendered(art, c, true, "lazarus_back"),
            // Its female card: the same save with SaveBlock2's gender byte set (pink backdrop, her pic).
            rendered(art, c.copy(female = true), false, "lazarus_female_front"),
            rendered(art, m, false, "lazarus_max_front"), rendered(art, m, true, "lazarus_max_back"),
        )
        assertEquals(listOf(LZ_FRONT, LZ_BACK, LZ_FEMALE_FRONT, LZ_MAX_FRONT, LZ_MAX_BACK), got)
    }

    @Test fun `SoulGold - its own save offsets and star rules`() {
        // Its card for this save (headless): Lidor, IDNo.25396, ₽3080, 0:25, no POKéDEX, no badges or stars.
        val c = card("soulgold_card", NATIVE_SOULGOLD)
        assertEquals("Lidor", c.nameText)
        assertEquals(25396, c.trainerId)
        assertEquals(3080L, c.money)
        assertNull(c.dexCaught)
        assertEquals(0 to 0, c.badges to c.stars)
        // The poked states (fixtures' READMEs): one star for a frontier record; three for the HALL OF FAME, every
        // non-legendary regional entry and a record, with 79 achievements (one short); everything for the max state.
        assertEquals(1, card("soulgold_card_1star", NATIVE_SOULGOLD).stars)
        assertEquals(3, card("soulgold_card_3star", NATIVE_SOULGOLD).stars)
        val m = card("soulgold_card_max", NATIVE_SOULGOLD)
        assertEquals(4, m.stars)
        assertEquals(1025, m.dexCaught)
        assertEquals(9_999_999L, m.money)
        assertEquals(4, card("soulgold_v12_card_max", NATIVE_SOULGOLD_V1_2).stars)
    }

    @Test fun `SoulGold card - drawn like the game`() {
        val art = art(RETAIL_ROM_DIR + "Pokemon SoulGold (v1.1.4).gba", CardStyle.SOULGOLD)
        val c = card("soulgold_card", NATIVE_SOULGOLD)
        val max = card("soulgold_card_max", NATIVE_SOULGOLD)
        val got = listOf(
            rendered(art, c, false, "soulgold_front"), rendered(art, c, true, "soulgold_back"),
            // Its female card: the max state with SaveBlock2's gender byte set (her pic; the backdrop palette is the same).
            rendered(art, max.copy(female = true), false, "soulgold_female_front"),
            // One star: the green card, and the ID turns white.
            rendered(art, card("soulgold_card_1star", NATIVE_SOULGOLD), false, "soulgold_1star_front"),
            rendered(art, card("soulgold_card_3star", NATIVE_SOULGOLD), false, "soulgold_3star_front"),
            rendered(art, max, false, "soulgold_max_front"), rendered(art, max, true, "soulgold_max_back"),
        )
        assertEquals(listOf(SG_FRONT, SG_BACK, SG_FEMALE_FRONT, SG_1STAR_FRONT, SG_3STAR_FRONT, SG_MAX_FRONT, SG_MAX_BACK), got)
        // v1.2: the same blobs elsewhere in its ROM, the same card.
        val v12 = art(RETAIL_ROM_DIR + "Pokemon SoulGold (v1.2).gba", CardStyle.SOULGOLD)
        assertEquals(SG_MAX_FRONT, rendered(v12, card("soulgold_v12_card_max", NATIVE_SOULGOLD_V1_2), false, "soulgold_v12_max_front"))
    }

    /** A Recharged Yellow card read with its ROM on the bus (the back's icons come from it). */
    private fun ryCard(key: String, rom: String, cfg: NativeConfig): TrainerCardInfo {
        val r = RomFileReader.load(RETAIL_ROM_DIR + rom)
        assumeTrue("no ROM $rom", r != null)
        return readTrainerCard(r!!.withRam(FixtureMemoryReader.load(key)), cfg)!!
    }

    @Test fun `Recharged Yellow - its own save offsets and star rules`() {
        // Its card for this save (headless): JANE, IDNo.42651, ₽4804, Pokédex 31, 9:59, three badges, no stars.
        val c = card("recharged_yellow_card", NATIVE_RECHARGED_YELLOW_V1_8_0)
        assertEquals("JANE", c.nameText)
        assertEquals(42651, c.trainerId)
        assertEquals(4804L, c.money)
        assertEquals(31, c.dexCaught)
        assertEquals(9 to 59, c.hours to c.minutes)
        assertEquals(0x07 to 0, c.badges to c.stars)
        // Everything poked in (its README): the gold card, four stars, 386 caught, stickers and six icons on the back.
        val m = ryCard("recharged_yellow_card_max", "Pokemon Recharged Yellow (v1.8.0).gba", NATIVE_RECHARGED_YELLOW_V1_8_0)
        assertEquals(4, m.stars)
        assertEquals(386, m.dexCaught)
        assertEquals(listOf(4, 4, 4), m.stickers)
        assertEquals(6, m.backIcons.count { it != null })
    }

    @Test fun `Recharged Yellow card - drawn like the game`() {
        val rom = "Pokemon Recharged Yellow (v1.8.0).gba"
        val art = art(RETAIL_ROM_DIR + rom, CardStyle.RECHARGED_YELLOW)
        val c = ryCard("recharged_yellow_card", rom, NATIVE_RECHARGED_YELLOW_V1_8_0)
        val m = ryCard("recharged_yellow_card_max", rom, NATIVE_RECHARGED_YELLOW_V1_8_0)
        val tint = ryCard("recharged_yellow_card_tint", rom, NATIVE_RECHARGED_YELLOW_V1_8_0)
        val v197 = ryCard("recharged_yellow_v197_card_max", "Pokemon Recharged Yellow (v1.9.7).gba", NATIVE_RECHARGED_YELLOW_V1_9_7)
        val art197 = art(RETAIL_ROM_DIR + "Pokemon Recharged Yellow (v1.9.7).gba", CardStyle.RECHARGED_YELLOW_V1_9)
        val got = listOf(
            rendered(art, c, false, "ry_front"), rendered(art, c, true, "ry_back"),
            rendered(art, m, false, "ry_max_front"), rendered(art, m, true, "ry_max_back"),
            // Its icons tinted black, the stickers' brag levels 3 / 1 / 2.
            rendered(art, tint, true, "ry_tint_back"),
            // v1.9.7: the same card, but its red is lighter.
            rendered(art197, v197, false, "ry197_max_front"), rendered(art197, v197, true, "ry197_max_back"),
        )
        assertEquals(listOf(RY_FRONT, RY_BACK, RY_MAX_FRONT, RY_MAX_BACK, RY_TINT_BACK, RY_MAX_FRONT, RY197_MAX_BACK), got)
    }

    @Test fun `every build of the three finds its card art`() {
        for ((rom, style) in listOf(
            "Pokemon SoulGold (v1.2) (Alt 1).gba" to CardStyle.SOULGOLD, "Pokemon SoulGold (v1.2) (Alt 2).gba" to CardStyle.SOULGOLD,
            "Pokemon SoulGold (v1.2.1).gba" to CardStyle.SOULGOLD, "Pokemon SoulGold (v1.2.1) (Alt 1).gba" to CardStyle.SOULGOLD,
            "Pokemon Recharged Yellow (v1.9.0).gba" to CardStyle.RECHARGED_YELLOW_V1_9,
            "Pokemon Recharged Yellow (v1.9.4).gba" to CardStyle.RECHARGED_YELLOW_V1_9,
        )) {
            val bytes = File(RETAIL_ROM_DIR + rom).takeIf { it.isFile }?.readBytes() ?: continue
            val found = RomArt.find(bytes)
            assertNotNull(rom, TrainerCardArt.art(style) { found[it] })
        }
    }

    private companion object {
        // CRC32 of the ARGB pixels, recorded once each matched a headless
        // screenshot of the game's own card for this save pixel for pixel.
        const val FR_FRONT = "d62339db"
        const val FR_BACK = "045996a2"
        const val EM_FRONT = "f3e14960"
        const val EM_BACK = "74484c21"
        // Not a copy of a game screen: recorded once it looked right.
        const val UB_FRONT = "be68c6aa"
        const val UB_BACK = "5155c5ba"
        // Emerald Legacy, its save and the same save with every star poked in (emerald_legacy_stars): matched against
        // headless screenshots of its own card, front and back.
        const val LG_FRONT = "6da94bfe"
        const val LG_BACK = "74484c21"
        const val LG_STARS_FRONT = "19efdadd"
        const val LG_STARS_BACK = "90074f39"
        const val LZ_FRONT = "4daa6af1"
        const val LZ_BACK = "4c5a1651"
        const val LZ_FEMALE_FRONT = "cfef77a8"
        const val LZ_MAX_FRONT = "f6224f73"
        const val LZ_MAX_BACK = "f2eda466"
        const val SG_FRONT = "da0265f8"
        const val SG_BACK = "03fb2d2e"
        const val SG_FEMALE_FRONT = "dc9b54be"
        const val SG_1STAR_FRONT = "aceca0ed"
        const val SG_3STAR_FRONT = "2496c107"
        const val SG_MAX_FRONT = "da68241d"
        const val SG_MAX_BACK = "fb5a508c"
        const val RY_FRONT = "1ff30636"
        const val RY_BACK = "f18cd53c"
        const val RY_MAX_FRONT = "a8c33291"
        const val RY_MAX_BACK = "3a1532af"
        const val RY_TINT_BACK = "f2c5a87a"
        const val RY197_MAX_BACK = "797f2e18"
    }
}
