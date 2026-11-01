package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The ROM hacks' POKéDEX: each `<game>_dex` fixture is a headless capture of
 * the user's save taken while (or right before) the game's own POKéDEX was on
 * screen - its README says what the game showed, and the flags must say the
 * same. The ROM half decodes every entry the dex lists from the user's ROM
 * (skipped when the ROM isn't on this machine).
 */
class HackPokedexTest {
    private fun state(key: String, cfg: NativeConfig): PokedexState {
        val dex = readPokedexState(FixtureMemoryReader.load(key), cfg, cfg.pokedex!!)
        assertNotNull("no pokedex state decoded", dex)
        return dex!!
    }

    /** Emerald Rogue: a 2-bit state per species - its dex said Seen 2 / Caught 1 (COTTONEE seen, ROCKRUFF caught). */
    @Test
    fun `Emerald Rogue - species-indexed 2-bit state`() {
        // No. n reads its species' bit, and species come from the ROM (Gen 9 isn't No. = species).
        val rom = RomFileReader.load("$ROM_DIR/Pokemon Emerald Rogue (v2.2.1-EX).gba")
        assumeTrue(rom != null)
        PokedexSource.reader = rom!!
        val dex = state("emerald_rogue", NATIVE_EMERALD_ROGUE)
        assertEquals(setOf(546, 744), dex.seen)
        assertEquals(setOf(744), dex.caught)
    }

    /** Quetzal: 3-bit seen levels (6 species), caught per region (CHARMANDER, Kanto) - its dex said SEEN 6 / OWN 1. */
    @Test
    fun `Quetzal - seen levels and caught per region`() {
        val dex = state("quetzal", NATIVE_QUETZAL)
        assertEquals(6, dex.seen.size)
        assertTrue(dex.seen.containsAll(listOf(4, 7, 19, 133)))
        assertEquals(setOf(4), dex.caught)
        assertTrue(dex.national)
    }

    /** Quetzal's Spanish release (its dex said AVISTADOS 468 / ATRAPADOS 349) and a second English save. */
    @Test
    fun `Quetzal - the Spanish save and the Johto one`() {
        val es = state("quetzal_es", NATIVE_QUETZAL_ES)
        assertEquals(468 to 349, es.seen.size to es.caught.size)
        assertTrue(es.caught.containsAll(listOf(6, 130)))
        val johto = state("quetzal_johto", NATIVE_QUETZAL)
        assertTrue(johto.caught.containsAll(listOf(4, 215, 408, 442, 627)))
    }

    /** Quetzal Alpha 0.8.4 (every build) with Spanish Alpha 9's save: its own POKéDEX said SEEN 466 / OWN 347 (the
     * continue screen 347) - two fewer than Alpha 9, which counts species 0.8.4 doesn't have. */
    @Test
    fun `Quetzal 0_8_4 - Alpha 9's save, its own counts`() {
        for ((key, cfg) in listOf("quetzal_084" to NATIVE_QUETZAL_V084, "quetzal_es_084" to NATIVE_QUETZAL_V084_ES,
            "quetzal_moemon_084" to NATIVE_QUETZAL_MOEMON_V084, "quetzal_moemon_es_084" to NATIVE_QUETZAL_MOEMON_V084_ES)) {
            val dex = state(key, cfg)
            assertEquals(key, 466 to 347, dex.seen.size to dex.caught.size)
            assertTrue(dex.caught.containsAll(listOf(6, 130)))
        }
    }

    /** Unbound FR: CFRU's flags, as English's - its dex said Seen 6 Borrius / 8 National, Caught 2 / 3. */
    @Test
    fun `Unbound FR - seen 8, caught 3`() {
        val dex = state("unbound_fr", NATIVE_UNBOUND_FR)
        assertEquals(8 to 3, dex.seen.size to dex.caught.size)
        assertEquals(setOf(225, 246, 361), dex.caught)
    }

    /** Orange Islands: FireRed's flags - its continue screen said POKéDEX 2 (PIKACHU and the MEWTWO it starts with). */
    @Test
    fun `Orange Islands - two caught`() {
        val dex = state("orange_islands", NATIVE_ORANGE_ISLANDS)
        assertEquals(setOf(25, 150), dex.caught)
        assertFalse(dex.national)
    }

    /** Recharged Yellow: FireRed's struct Pokedex with no anti-cheat copies - its POKéDEX said Seen 58 / Own 31,
     * in every release (they all load the same save). */
    @Test
    fun `Recharged Yellow - seen 58, owned 31`() {
        for ((key, cfg) in listOf(
            "recharged_yellow_v180" to NATIVE_RECHARGED_YELLOW_V1_8_0, "recharged_yellow_v190" to NATIVE_RECHARGED_YELLOW_V1_9_0,
            "recharged_yellow_v194" to NATIVE_RECHARGED_YELLOW_V1_9_4, "recharged_yellow" to NATIVE_RECHARGED_YELLOW_V1_9_7,
        )) {
            val dex = state(key, cfg)
            assertEquals(key, 58 to 31, dex.seen.size to dex.caught.size)
            assertTrue(dex.caught.containsAll(listOf(1, 8, 25, 27, 37))) // its party's
            assertTrue(dex.seen.containsAll(dex.caught))
            assertFalse(dex.national)
        }
    }

    /** Recharged Emerald: retail Emerald's save converted keeps its SaveBlock2 dex - SEEN 108 / OWN 28 as retail's
     * POKéDEX said (EmeraldPokedexTest), in every build (Rebalanced's 4 bytes later). */
    @Test
    fun `Recharged Emerald - seen 108, owned 28`() {
        for ((key, cfg) in listOf(
            "recharged_emerald_v225" to NATIVE_RECHARGED_EMERALD_V2_2_5, "recharged_emerald" to NATIVE_RECHARGED_EMERALD_V2_2_8,
            "recharged_emerald_rebalanced" to NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8,
        )) {
            val dex = state(key, cfg)
            assertEquals(key, 108 to 28, dex.seen.size to dex.caught.size)
            assertTrue(dex.seen.containsAll(dex.caught))
        }
    }

    /** Recharged Emerald's entries: Emerald's shape at its own addresses, its own casing ("Seed"). */
    @Test
    fun `Recharged Emerald entries - every build`() {
        for ((file, t) in listOf(
            "Pokemon Recharged Emerald (v2.2.5).gba" to POKEDEX_RECHARGED_EMERALD_V2_2_5,
            "Pokemon Recharged Emerald (v2.2.8).gba" to POKEDEX_RECHARGED_EMERALD_V2_2_8,
            "Pokemon Recharged Emerald Rebalanced (v2.2.8).gba" to POKEDEX_RECHARGED_EMERALD_REBALANCED_V2_2_8,
        )) roms(file, GameKind.EMERALD, t) {
            assertTrue(file, pokedexMatchesRom(PokedexSource.reader!!, t))
            val bulbasaur = PokedexSource.entry(t, 1)!!
            assertEquals("Seed" to (7 to 69), bulbasaur.category to (bulbasaur.heightDm to bulbasaur.weightHg))
            assertEquals(202, PokedexSource.regionalOrder(t)!!.size)
        }
    }

    /** Glazed: retail's flags (its own numbering, CHIMCHAR No.322); Imperium: SaveBlock1's, by national number. */
    @Test
    fun `Glazed and Imperium - the starter caught`() {
        assertEquals(setOf(322), state("glazed", NATIVE_GLAZED).caught)
        assertEquals(setOf(4), state("imperium", NATIVE_IMPERIUM).caught)
    }

    /** Super Mariomon: SaveBlock1 dexSeen / dexCaught by dex number - its TATTLEDEX said SEEN 2 / OWN 1. */
    @Test
    fun `Super Mariomon - PIRANHA P seen, BOB-OMB owned`() {
        for ((key, cfg) in listOf("mariomon_v1_5_1" to NATIVE_MARIOMON_V1_5_1, "mariomon_v1_3" to NATIVE_MARIOMON_V1_3)) {
            val dex = state(key, cfg)
            assertEquals(setOf(1, 7), dex.seen)
            assertEquals(setOf(7), dex.caught)
            assertFalse(dex.national)
        }
    }

    @Test
    fun `Odyssey - Talrega 1, National 8 seen, 2 owned`() {
        val dex = state("odyssey_dex", NATIVE_ODYSSEY)
        assertEquals(setOf(137, 263, 285, 303, 311, 312, 316, 352), dex.seen)
        assertEquals(setOf(311, 312), dex.caught)
        assertEquals(1, dex.seen.count { it <= 151 })
        assertTrue(dex.national)
    }

    @Test
    fun `Radical Red - CHARMANDER caught, SQUIRTLE seen`() {
        val dex = state("radical_red_dex", NATIVE_RADICAL_RED_V4_1)
        assertEquals(setOf(4, 7), dex.seen)
        assertEquals(setOf(4), dex.caught)
        assertFalse(dex.national)
    }

    @Test
    fun `Amethyst - its own numbers, TEPIG is No 340`() {
        val dex = state("amethyst_dex", NATIVE_AMETHYST)
        assertEquals(setOf(182, 340), dex.seen)
        assertEquals(setOf(340), dex.caught)
    }

    @Test
    fun `Gaia - flags at fixed EWRAM addresses`() {
        val dex = state("gaia_dex", NATIVE_GAIA_V3_2)
        assertEquals(setOf(390), dex.seen)
        assertEquals(setOf(390), dex.caught)
        assertTrue(dex.national)
    }

    @Test
    fun `Celia - No n is bit n`() {
        val dex = state("celia_dex", NATIVE_CELIA)
        assertEquals(setOf(2), dex.seen)
        assertEquals(setOf(2), dex.caught)
    }

    @Test
    fun `Heart and Soul - Johto seen 2, own 1`() {
        val dex = state("heart_and_soul_dex", NATIVE_HEART_AND_SOUL)
        assertEquals(setOf(16, 155), dex.seen)
        assertEquals(setOf(155), dex.caught)
        assertFalse(dex.national)
    }

    @Test
    fun `Lazarus - FENNEKIN seen and caught`() {
        val dex = state("lazarus_dex", NATIVE_LAZARUS)
        assertEquals(setOf(653), dex.seen)
        assertEquals(setOf(653), dex.caught)
    }

    @Test
    fun `SoulGold - Johto seen 6, own 1`() {
        // The game's own POKéDEX on this save: Seen 6 / Own 1, CYNDAQUIL caught.
        val dex = state("soulgold", NATIVE_SOULGOLD)
        assertEquals(setOf(16, 19, 155, 163, 183, 263), dex.seen)
        assertEquals(setOf(155), dex.caught)
        assertFalse(dex.national)
    }

    @Test
    fun `SoulGold v1_2 - the same save, the same dex`() {
        val dex = state("soulgold_v12", NATIVE_SOULGOLD_V1_2)
        assertEquals(setOf(16, 19, 155, 163, 183, 263), dex.seen)
        assertEquals(setOf(155), dex.caught)
    }

    @Test
    fun `Seaglass - Hoenn seen 2, own 1`() {
        // Its own numbering: TORCHIC is No. 4, POOCHYENA No. 12.
        val dex = state("emerald_seaglass_dex", NATIVE_EMERALD_SEAGLASS)
        assertEquals(setOf(4, 12), dex.seen)
        assertEquals(setOf(4), dex.caught)
    }

    @Test
    fun `TMT2 - CHIMCHAR (its No 397) seen and caught`() {
        val dex = state("tmt2_dex", NATIVE_TMT2)
        assertEquals(setOf(397), dex.seen)
        assertEquals(setOf(397), dex.caught)
    }

    @Test
    fun `Odyssey entries`() = roms("Pokemon Odyssey (v4.1.1).gba", GameKind.ODYSSEY, POKEDEX_ODYSSEY) { t ->
        assertEquals("Seed", PokedexSource.entry(t, 1)!!.category)
        val roserade = PokedexSource.entry(t, 387)!!
        assertEquals(252, roserade.species)
        assertEquals("Bouquet", roserade.category)
        assertEquals(listOf("Natural Cure", "Poison Point"), roserade.abilities)
        // Type 9 is the game's own AETHER, not Fairy (GitHub #206).
        assertEquals(listOf("Aether"), PokedexSource.entry(t, 35)!!.types)
        assertEquals(listOf("Ground", "Aether"), PokedexSource.entry(t, 104)!!.types)
    }

    @Test
    fun `Radical Red entries`() = roms("Pokemon Radical Red (v4.1).gba", GameKind.RADICAL_RED, POKEDEX_RADICAL_RED) { t ->
        val charmander = PokedexSource.entry(t, 4)!!
        assertEquals("Lizard", charmander.category)
        assertEquals("Solar Power", charmander.hiddenAbility)
        assertEquals(listOf("Fairy"), PokedexSource.entry(t, 35)!!.types)
    }

    @Test
    fun `Amethyst entries`() = roms("Pokemon Amethyst (v1.3.0).gba", GameKind.AMETHYST, POKEDEX_AMETHYST) { t ->
        val tepig = PokedexSource.entry(t, 340)!!
        assertEquals(551, tepig.species)
        assertEquals("Fire Pig", tepig.category)
        assertEquals(340, PokedexSource.nationalOf(t, 551))
        assertEquals("Seed", PokedexSource.entry(t, 301)!!.category)
    }

    @Test
    fun `Amethyst v1_4_1 entries`() = roms("Pokemon Amethyst (v1.4.1).gba", GameKind.AMETHYST, POKEDEX_AMETHYST_V1_4_1) { t ->
        val tepig = PokedexSource.entry(t, 340)!!
        assertEquals(551, tepig.species)
        assertEquals("Fire Pig", tepig.category)
        assertEquals(340, PokedexSource.nationalOf(t, 551))
        assertEquals("Seed", PokedexSource.entry(t, 301)!!.category)
    }

    @Test
    fun `Gaia entries`() = roms("Pokemon Gaia (v3.2).gba", GameKind.GAIA, POKEDEX_GAIA) { t ->
        val chimchar = PokedexSource.entry(t, 390)!!
        assertEquals(443, chimchar.species)
        assertEquals("Chimp", chimchar.category)
        assertFalse(t.hasRegional)
    }

    @Test
    fun `Celia entries`() = roms("Pokemon Celia's Stupid Romhack (v1.1.4).gba", GameKind.CELIA, POKEDEX_CELIA) { t ->
        val charmander = PokedexSource.entry(t, 2)!!
        assertEquals(3, charmander.species)
        assertEquals("FIGHTING", charmander.category)
        assertEquals(2, PokedexSource.nationalOf(t, 3))
        assertEquals(150, PokedexSource.entry(t, 150)!!.national)
    }

    @Test
    fun `Heart and Soul entries and the Johto order`() =
        roms("Pokemon Heart and Soul (v2.0.6).gba", GameKind.HEART_AND_SOUL, POKEDEX_HEART_AND_SOUL) { t ->
            val johto = PokedexSource.regionalOrder(t)!!
            assertEquals(listOf(152, 153, 154, 155, 156, 157, 158, 159, 160, 16), johto.take(10))
            assertEquals(251, johto.last())
            val cyndaquil = PokedexSource.entry(t, 155)!!
            assertEquals("FIRE MOUSE", cyndaquil.category.uppercase())
            assertEquals(listOf("Blaze"), cyndaquil.abilities.map { it.lowercase().replaceFirstChar(Char::uppercase) })
            assertEquals(5, cyndaquil.heightDm)
        }

    @Test
    fun `Lazarus entries`() = roms("Pokemon Lazarus (v2.0).gba", GameKind.LAZARUS, POKEDEX_LAZARUS) { t ->
        val pikachu = PokedexSource.entry(t, 25)!!
        assertEquals("Mouse", pikachu.category)
        assertEquals(listOf("Static"), pikachu.abilities)
        assertEquals("Lightning Rod", pikachu.hiddenAbility)
        assertEquals(60, pikachu.weightHg)
        assertTrue(653 in PokedexSource.regionalOrder(t)!!)
    }

    @Test
    fun `SoulGold entries and the Johto order`() = roms("Pokemon SoulGold (v1.1.4).gba", GameKind.SOULGOLD, POKEDEX_SOULGOLD) { t ->
        val pikachu = PokedexSource.entry(t, 25)!!
        assertEquals(listOf("Static"), pikachu.abilities)
        assertEquals("Lightning Rod", pikachu.hiddenAbility)
        assertEquals(4 to 60, pikachu.heightDm to pikachu.weightHg)
        assertEquals("Fire Mouse", PokedexSource.entry(t, 155)!!.category)
        val johto = PokedexSource.regionalOrder(t)!!
        assertEquals(23, johto.indexOf(25) + 1) // the game's Johto 023
        assertEquals(136, johto.indexOf(155) + 1)
    }

    @Test
    fun `SoulGold v1_2 entries and the Johto order`() = roms("Pokemon SoulGold (v1.2).gba", GameKind.SOULGOLD, POKEDEX_SOULGOLD_V1_2) { t ->
        val pikachu = PokedexSource.entry(t, 25)!!
        assertEquals(listOf("Static"), pikachu.abilities)
        assertEquals("Lightning Rod", pikachu.hiddenAbility)
        assertEquals("Fire Mouse", PokedexSource.entry(t, 155)!!.category)
        assertEquals(136, PokedexSource.regionalOrder(t)!!.indexOf(155) + 1)
    }

    @Test
    fun `SoulGold v1_2 second build entries and the Johto order`() = roms("Pokemon SoulGold (v1.2) (Alt 1).gba", GameKind.SOULGOLD, POKEDEX_SOULGOLD_V1_2B) { t ->
        val pikachu = PokedexSource.entry(t, 25)!!
        assertEquals(listOf("Static"), pikachu.abilities)
        assertEquals("Lightning Rod", pikachu.hiddenAbility)
        assertEquals("Fire Mouse", PokedexSource.entry(t, 155)!!.category)
        assertEquals(136, PokedexSource.regionalOrder(t)!!.indexOf(155) + 1)
    }

    @Test
    fun `SoulGold v1_2 third build entries and the Johto order`() = roms("Pokemon SoulGold (v1.2) (Alt 2).gba", GameKind.SOULGOLD, POKEDEX_SOULGOLD_V1_2C) { t ->
        val pikachu = PokedexSource.entry(t, 25)!!
        assertEquals(listOf("Static"), pikachu.abilities)
        assertEquals("Lightning Rod", pikachu.hiddenAbility)
        assertEquals("Fire Mouse", PokedexSource.entry(t, 155)!!.category)
        assertEquals(136, PokedexSource.regionalOrder(t)!!.indexOf(155) + 1)
    }

    @Test
    fun `SoulGold v1_2_1 entries and the Johto order`() = listOf("Pokemon SoulGold (v1.2.1).gba", "Pokemon SoulGold (v1.2.1) (Alt 1).gba").forEach { rom ->
        roms(rom, GameKind.SOULGOLD, POKEDEX_SOULGOLD_V1_2_1) { t ->
            val pikachu = PokedexSource.entry(t, 25)!!
            assertEquals(listOf("Static"), pikachu.abilities)
            assertEquals("Lightning Rod", pikachu.hiddenAbility)
            assertEquals("Fire Mouse", PokedexSource.entry(t, 155)!!.category)
            assertEquals(136, PokedexSource.regionalOrder(t)!!.indexOf(155) + 1)
        }
    }

    @Test
    fun `Seaglass entries`() = roms("Pokemon Emerald Seaglass (v3.0).gba", GameKind.EMERALD_SEAGLASS, POKEDEX_EMERALD_SEAGLASS) { t ->
        assertEquals(252, PokedexSource.speciesFor(t, 1)) // TREECKO
        assertEquals(1, PokedexSource.speciesFor(t, 141)) // BULBASAUR
        assertEquals(255, PokedexSource.entry(t, 4)!!.species) // TORCHIC
    }

    @Test
    fun `TMT2 entries and the Hoenn order`() = roms("Pokemon Too Many Types 2 (v1.5.2).gba", GameKind.TMT2, POKEDEX_TMT2) { t ->
        val hoenn = PokedexSource.regionalOrder(t)!!
        assertEquals(listOf(913, 914, 915, 397), hoenn.take(4))
        val chimchar = PokedexSource.entry(t, 397)!!
        assertEquals(390, chimchar.species)
        assertEquals("Chimp", chimchar.category)
        assertEquals(listOf("Blaze"), chimchar.abilities)
        assertEquals("Iron Fist", chimchar.hiddenAbility)
        assertEquals(62, chimchar.weightHg)
    }

    @Test
    fun `Emerald Rogue entries and the MODERN list`() = roms("Pokemon Emerald Rogue (v2.2.1-EX).gba", GameKind.EMERALD_ROGUE, POKEDEX_EMERALD_ROGUE) { t ->
        assertEquals(179, PokedexSource.regionalOrder(t)!!.first()) // MAREEP is MODERN's No. 001
        assertEquals("Seed", PokedexSource.entry(t, 1)!!.category)
        assertEquals(1305, PokedexSource.speciesFor(t, 921)) // PAWMI
    }

    @Test
    fun `Quetzal entries`() = roms("Pokemon Quetzal (Alpha 9.0) (English).gba", GameKind.QUETZAL, POKEDEX_QUETZAL) { t ->
        val charmander = PokedexSource.entry(t, 4)!!
        assertEquals("Lizard" to (6 to 85), charmander.category to (charmander.heightDm to charmander.weightHg))
        assertEquals(listOf("Blaze"), charmander.abilities)
        assertEquals("Solar Power", charmander.hiddenAbility)
        assertEquals(31, charmander.genderRatio)
        assertEquals(1244, PokedexSource.speciesFor(t, 915)) // LECHONK
    }

    @Test
    fun `Quetzal Spanish entries`() = roms("Pokemon Quetzal (Alpha 9.0) (Spanish).gba", GameKind.QUETZAL, POKEDEX_QUETZAL_ES) { t ->
        val charmander = PokedexSource.entry(t, 4)!!
        assertEquals("Lagartija" to (6 to 85), charmander.category to (charmander.heightDm to charmander.weightHg))
        assertTrue(charmander.description.startsWith("Prefiere las cosas calientes"))
        assertEquals(1244, PokedexSource.speciesFor(t, 915)) // LECHONK
    }

    @Test
    fun `Quetzal 0_8_4 entries - every build`() {
        for ((file, t) in listOf("Pokemon Quetzal (Alpha 0.8.4) (English).gba" to POKEDEX_QUETZAL_V084,
            "Moemon Quetzal (Alpha 0.8.4 Hotfix) (English).gba" to POKEDEX_QUETZAL_MOEMON_V084)) roms(file, GameKind.QUETZAL, t) { t ->
            val charmander = PokedexSource.entry(t, 4)!!
            assertEquals("Lizard" to (6 to 85), charmander.category to (charmander.heightDm to charmander.weightHg))
            assertEquals(listOf("Blaze"), charmander.abilities)
            assertEquals("Solar Power", charmander.hiddenAbility)
            assertEquals(1244, PokedexSource.speciesFor(t, 915)) // LECHONK
            assertEquals(0, PokedexSource.speciesFor(t, 1026)) // no Alpha 9 starters
        }
        for ((file, t) in listOf("Pokemon Quetzal (Alpha 0.8.4) (Spanish).gba" to POKEDEX_QUETZAL_V084_ES,
            "Moemon Quetzal (Alpha 0.8.4 Hotfix) (Spanish).gba" to POKEDEX_QUETZAL_MOEMON_V084_ES)) roms(file, GameKind.QUETZAL, t) { t ->
            val charmander = PokedexSource.entry(t, 4)!!
            assertEquals("Lagartija" to (6 to 85), charmander.category to (charmander.heightDm to charmander.weightHg))
            assertEquals(1244, PokedexSource.speciesFor(t, 915))
        }
    }

    @Test
    fun `Unbound FR entries in French`() = roms("Pokemon Unbound (v2.1.1.1) (French).gba", GameKind.UNBOUND, POKEDEX_UNBOUND_FR) { t ->
        val charmander = PokedexSource.entry(t, 4)!!
        assertEquals("Lézard" to (6 to 85), charmander.category to (charmander.heightDm to charmander.weightHg))
        assertEquals(listOf("Brasier"), charmander.abilities)
    }

    @Test
    fun `Orange Islands entries - FireRed rev 0's tables, edited`() = roms("Pokemon Orange Islands.gba", GameKind.ORANGE_ISLANDS, POKEDEX_ORANGE_ISLANDS) { t ->
        val charmander = PokedexSource.entry(t, 4)!!
        assertEquals("LIZARD" to (6 to 85), charmander.category to (charmander.heightDm to charmander.weightHg))
        assertEquals(listOf("BLAZE"), charmander.abilities)
    }

    /** Recharged Yellow's entries: 14-byte categories, the game's own casing ("Seed", 2'04", 15.2 lbs. on its screen). */
    @Test
    fun `Recharged Yellow entries - every release`() {
        for ((v, t) in listOf(
            "1.8.0" to POKEDEX_RECHARGED_YELLOW_V1_8_0, "1.9.0" to POKEDEX_RECHARGED_YELLOW_V1_9_0,
            "1.9.4" to POKEDEX_RECHARGED_YELLOW_V1_9_4, "1.9.7" to POKEDEX_RECHARGED_YELLOW_V1_9_7,
        )) roms("Pokemon Recharged Yellow (v$v).gba", GameKind.RECHARGED_YELLOW, t) {
            assertTrue(pokedexMatchesRom(PokedexSource.reader!!, t))
            val bulbasaur = PokedexSource.entry(t, 1)!!
            assertEquals("Seed" to (7 to 69), bulbasaur.category to (bulbasaur.heightDm to bulbasaur.weightHg))
            assertTrue(bulbasaur.description.startsWith("It can go for days"))
            assertEquals(listOf("Overgrow"), bulbasaur.abilities)
            val pikachu = PokedexSource.entry(t, 25)!!
            assertEquals(25 to "Mouse", pikachu.species to pikachu.category)
        }
    }

    @Test
    fun `Ultra Violet entries - FireRed rev 0's tables`() = roms("Pokemon Ultra Violet (v1.22).gba", GameKind.FIRERED, POKEDEX_ULTRA_VIOLET) { t ->
        val charmander = PokedexSource.entry(t, 4)!!
        assertEquals("LIZARD" to (6 to 85), charmander.category to (charmander.heightDm to charmander.weightHg))
        assertEquals(listOf("BLAZE"), charmander.abilities)
    }

    @Test
    fun `Rocket Edition entries - rev 0's tables, its own ability names`() =
        roms("Pokemon FireRed Rocket Edition.gba", GameKind.FIRERED, POKEDEX_ROCKET_EDITION) { t ->
            val clefairy = PokedexSource.entry(t, 35)!!
            assertEquals(35 to "Fairy", clefairy.species to clefairy.category)
            assertTrue(clefairy.abilities.isNotEmpty())
        }

    @Test
    fun `Elite Redux entries - by dex number, its fakemon past Gen 9`() {
        for ((file, tables) in listOf("Pokemon Elite Redux (2.65.4.1b).gba" to POKEDEX_ELITE_REDUX_V2_65_4_1B,
                "Pokemon Elite Redux (2.65.3b).gba" to POKEDEX_ELITE_REDUX_V2_65_3B)) {
            roms(file, GameKind.ELITE_REDUX, tables) { t ->
                val lucineon = PokedexSource.entry(t, 1072)!!
                assertEquals(1071 to "Bioluminescent", lucineon.species to lucineon.category)
                assertEquals(21 to 450, lucineon.heightDm to lucineon.weightHg)
                assertEquals(listOf("Protean"), lucineon.abilities.take(1))
                assertEquals(644 to "Deep Black", PokedexSource.entry(t, 644)!!.let { it.species to it.category })
                assertEquals(1, PokedexSource.speciesFor(t, 1))
            }
        }
    }

    @Test
    fun `Ghost Grey entries - rev 0's tables rewritten, its own ability names`() =
        roms("Pokemon Ghost Grey (v1.0.4).gba", GameKind.FIRERED, POKEDEX_GHOST_GREY) { t ->
            val rowlet = PokedexSource.entry(t, 1)!!
            assertEquals(1 to "Grass Quill", rowlet.species to rowlet.category)
            assertEquals(3 to 15, rowlet.heightDm to rowlet.weightHg)
            // VIVILLAIN, its summary's No012.
            assertEquals(12, PokedexSource.entry(t, 12)!!.species)
            assertTrue(rowlet.abilities.isNotEmpty())
        }

    @Test
    fun `Ash Gray entries - rev 0's tables`() = roms("Pokemon Ash Gray (v4.5.3).gba", GameKind.FIRERED, POKEDEX_ULTRA_VIOLET) { t ->
        val pikachu = PokedexSource.entry(t, 25)!!
        assertEquals(25 to "MOUSE", pikachu.species to pikachu.category)
        assertEquals(listOf("STATIC"), pikachu.abilities)
    }

    @Test
    fun `Sword and Shield entries - one national dex`() = roms("Pokemon Sword and Shield Ultimate Plus (v1.2.1.2).gba", GameKind.SWORD_SHIELD, POKEDEX_SWORD_SHIELD) { t ->
        val scorbunny = PokedexSource.entry(t, 813)!!
        assertEquals(1105 to "Rabbit", scorbunny.species to scorbunny.category)
        assertEquals(3 to 45, scorbunny.heightDm to scorbunny.weightHg)
        assertEquals(1105, PokedexSource.speciesFor(t, 813))
        assertEquals("Seed", PokedexSource.entry(t, 1)!!.category)
    }

    /** GS Chronicles: its own Johto list of 501 species ids (the game's POKéDEX: No001 Chikorita, No004 Cyndaquil, No007 Totodile). */
    @Test
    fun `GS Chronicles entries and the Johto order`() = roms("Pokemon GS Chronicles (v2.7.6).gba", GameKind.GS_CHRONICLES, POKEDEX_GS_CHRONICLES) { t ->
        val johto = PokedexSource.regionalOrder(t)!!
        assertEquals(501, johto.size)
        assertEquals(listOf(152, 153, 154, 155), johto.take(4).toList())
        val cyndaquil = PokedexSource.entry(t, 155)!!
        assertEquals(155 to "Fire Mouse", cyndaquil.species to cyndaquil.category)
        assertEquals(5 to 79, cyndaquil.heightDm to cyndaquil.weightHg)
        assertEquals("Blaze", cyndaquil.abilities.first())
        assertEquals("Seed", PokedexSource.entry(t, 1)!!.category)
    }

    @Test
    fun `Imperium entries and the Hoenn order`() = roms("Pokemon Emerald Imperium (v1.3.1).gba", GameKind.IMPERIUM, POKEDEX_IMPERIUM) { t ->
        assertEquals(252, PokedexSource.regionalOrder(t)!!.first())
        val charmander = PokedexSource.entry(t, 4)!!
        assertEquals("Lizard" to (6 to 85), charmander.category to (charmander.heightDm to charmander.weightHg))
        assertEquals(listOf("Blaze"), charmander.abilities)
    }

    /** Wishes of Tomorrow (expansion 1.15.3): expansion's Hoenn list (u32 national numbers), every National entry readable. */
    @Test
    fun `Wishes of Tomorrow entries and the Hoenn order`() = roms("Pokemon Wishes of Tomorrow (v1.2.6).gba", GameKind.WISHES, POKEDEX_WISHES) { t ->
        assertEquals(252, PokedexSource.regionalOrder(t)!!.first())
        val pikachu = PokedexSource.entry(t, 25)!!
        assertEquals("Mouse" to (4 to 60), pikachu.category to (pikachu.heightDm to pikachu.weightHg))
        assertEquals(listOf("Static"), pikachu.abilities)
    }

    @Test
    fun `Glazed entries in its own numbering`() = roms("Pokemon Glazed (v9.2.0).gba", GameKind.GLAZED, POKEDEX_EMERALD) { t ->
        val chimchar = PokedexSource.entry(t, 322)!!
        assertEquals(298 to "CHIMP", chimchar.species to chimchar.category)
        assertEquals(5 to 62, chimchar.heightDm to chimchar.weightHg)
    }

    /** Its TATTLEDEX: No.1 PIRANHA P. (species 906), BOB-OMB's page as the game prints it, the WORLD list's
     * 152 (v1.5: ends at No.152 TIPTRON) / 151 (v1.3: No.151 SONIC). */
    @Test
    fun `Super Mariomon v1_5_1 entries and the WORLD list`() = roms("Super Mariomon (v1.5.1 Anniversary).gba", GameKind.MARIOMON, POKEDEX_MARIOMON_V1_5_1) { t ->
        mariomonV13 = false
        assertTrue(pokedexMatchesRom(PokedexSource.reader, t))
        assertEquals(906, PokedexSource.speciesFor(t, 1))
        val bobomb = PokedexSource.entry(t, 7)!!
        assertEquals(912 to "Explosive", bobomb.species to bobomb.category)
        assertEquals(6 to 104, bobomb.heightDm to bobomb.weightHg)
        assertTrue(bobomb.description.startsWith("A hot-tempered piece of munition."))
        assertEquals(listOf(1, 2, 3), PokedexSource.regionalOrder(t)!!.take(3))
        assertEquals(1060, PokedexSource.speciesFor(t, PokedexSource.regionalOrder(t)!!.last())) // TIPTRON
    }

    @Test
    fun `Super Mariomon v1_3, v1_2_2, v1_2_1, v1_5_2 entries`() {
        for ((file, t) in listOf("Super Mariomon (v1.3).gba" to POKEDEX_MARIOMON_V1_3, "Super Mariomon (v1.2.2).gba" to POKEDEX_MARIOMON_V1_2_2,
                "Super Mariomon (v1.2.1).gba" to POKEDEX_MARIOMON_V1_2_1, "Super Mariomon (v1.5.2 Anniversary).gba" to POKEDEX_MARIOMON_V1_5_2)) {
            mariomonV13 = t !== POKEDEX_MARIOMON_V1_5_2
            roms(file, GameKind.MARIOMON, t) {
                assertTrue(file, pokedexMatchesRom(PokedexSource.reader, t))
                assertEquals("Explosive", PokedexSource.entry(t, 7)!!.category)
                val last = PokedexSource.speciesFor(t, PokedexSource.regionalOrder(t)!!.last())
                assertEquals(file, if (mariomonV13) "SONIC" else "TIPTRON", activeSpeciesNames[last])
            }
        }
    }

    /** Loads the user's ROM (or skips), checks [check], then that every entry
     * the national and regional lists show decodes to clean text. */
    private fun roms(file: String, game: GameKind, t: PokedexTables, check: (PokedexTables) -> Unit) {
        val rom = RomFileReader.load("$ROM_DIR/$file")
        assumeTrue("$file not on this machine", rom != null)
        activeGame = game
        PokedexSource.reader = rom!!
        check(t)
        val listed = (1..t.nationalCount).filter { PokedexSource.speciesFor(t, it) != 0 }
        // Lazarus and SoulGold leave many numbers without a species (disabled / unused).
        assertTrue("most numbers have a species", listed.size > t.nationalCount * 9 / 10 || game == GameKind.LAZARUS || game == GameKind.SOULGOLD)
        if (t.hasRegional && t.regionalOrder != 0L) {
            val order = PokedexSource.regionalOrder(t)!!
            assertEquals(t.regionalCount, order.size)
            assertTrue("regional entries are national numbers", order.all { it in listed })
        }
        for (n in listed) {
            val e = PokedexSource.entry(t, n)
            assertNotNull("entry $n", e)
            // Elite Redux leaves some of its own fakemon's entries empty (no category or text yet, in the game too).
            assertTrue("entry $n category '${e!!.category}'", (e.category.isNotBlank() || game == GameKind.ELITE_REDUX) && Gen3Text.UNKNOWN !in e.category)
            // TMT2's MissingNo. (No. 1034) has deliberately glitched text.
            if (!(game == GameKind.TMT2 && n == 1034)) {
                assertTrue("entry $n description '${e.description}'", Gen3Text.UNKNOWN !in e.description)
            }
            assertTrue("entry $n abilities ${e.abilities}", e.abilities.none { Gen3Text.UNKNOWN in it })
        }
    }

    private companion object {
        val ROM_DIR: String = System.getProperty("romDir") ?: RETAIL_ROM_DIR.trimEnd('/')
    }
}
