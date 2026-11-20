package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AmethystDecodeTest {
    @Test
    fun `party and bag populated with the real live state`() {
        // Fixture is a live in-progress save: 1 Tepig (species=551 in
        // Amethyst's own relocated species table - see
        // SpeciesNamesAmethyst.kt - not National Dex #498 or the internal
        // Gen3 index), level 6, full HP, at Route 17. Unlike the
        // Emerald-based hacks (Lazarus/Emerald Rogue), Amethyst is a
        // FireRed-based ASM hack that kept vanilla's RAM layout unchanged -
        // NATIVE_AMETHYST needed zero address changes, only its own species
        // table (party/bag/location all read correctly out of the box).
        val t = decodeNative("amethyst", NATIVE_AMETHYST)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals(551, mon.species)
        assertEquals(6, mon.level)
        assertEquals(25, mon.hp)
        assertEquals(25, mon.maxHp)
        assertEquals(10, t.items.size)
        assertPocketsInRange(t)
    }

    @Test
    fun `native gender matches the game's own party screen`() {
        // PID + genderRatiosAmethyst (the live gBaseStats, not the stale
        // vanilla copy - that one has Tepig's slot at a different ratio); the
        // game shows this Tepig as female (headless mgba_dump `shot`, 2026-09-27).
        activeGame = GameKind.AMETHYST
        val t = decodeNative("amethyst", NATIVE_AMETHYST)
        assertEquals(GENDER_SYMBOL_FEMALE, t.party[0].genderSymbol)
    }

    @Test
    fun `v1_4_1 - the v1_3_0 save at the same RAM addresses`() {
        activeGame = GameKind.AMETHYST
        amethystV141 = true
        try {
            val t = decodeNative("amethyst_v141", NATIVE_AMETHYST_V1_4_1)
            val mon = t.party.single()
            assertEquals(551 to 6, mon.species to mon.level)
            assertEquals("Tepig", speciesName(mon.species))
            assertEquals(25 to 25, mon.hp to mon.maxHp)
            assertEquals(GENDER_SYMBOL_FEMALE, mon.genderSymbol)
            assertEquals(10, t.items.size)
            assertPocketsInRange(t)
            t.items.forEach { assert('#' !in itemName(it.itemId)) { "item ${it.itemId}" } }
        } finally {
            amethystV141 = false
        }
    }

    @Test
    fun `moves past Gen 3 have their names (GitHub 96)`() {
        activeGame = GameKind.AMETHYST
        for (v141 in listOf(false, true)) {
            amethystV141 = v141
            try {
                // The report's two, and a Fairy move (CFRU's type 23).
                assertEquals("Worry Seed", lookupMove(475).name)
                assertEquals("Power Trip", lookupMove(602).name)
                assertEquals(23, lookupMove(461).type)
                val t = decodeNative(if (v141) "amethyst_v141" else "amethyst", if (v141) NATIVE_AMETHYST_V1_4_1 else NATIVE_AMETHYST)
                t.party.flatMap { it.moves.toList() }.filter { it != 0 }.forEach { assert('#' !in lookupMove(it).name) { "move $it" } }
            } finally {
                amethystV141 = false
            }
        }
    }
}
