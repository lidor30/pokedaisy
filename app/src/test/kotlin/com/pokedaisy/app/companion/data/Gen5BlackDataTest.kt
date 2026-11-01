package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Black's tables (Gen5TablesBlack.kt, Gen5ItemsBlack.kt: scripts/gen_gen5_tables.py) and GUIDE data
 * (GuideGen5BlackGen.kt: scripts/gen_gen5_guide.py, read back by Gen5World), both generated from the cart - so this
 * needs no ROM. The facts pinned are the cart's: personal a/0/1/6, moves a/0/2/1, items a/0/2/4, zones a/0/1/2, wild
 * sets a/1/2/6, trainers a/0/9/2 / a/0/9/3, the zones' events a/1/2/5 and the item ball scripts (a/0/5/7 member 864).
 */
class Gen5BlackDataTest {
    /** A save with [badges] (the case's first n) and [month] (1-12) as Gen5World reads them. */
    private fun progress(badges: Int = 0, month: Int = 0, flags: List<Int> = emptyList()): SaveProgress {
        val bytes = ByteArray(0x200)
        for (f in flags) bytes[f / 8] = (bytes[f / 8].toInt() or (1 shl (f % 8))).toByte()
        return SaveProgress(
            bytes, ByteArray(0),
            flagBanks = listOf(SaveBank(GEN4_BADGE_FLAG, 0, 16) to byteArrayOf(((1 shl badges) - 1).toByte(), 0)),
            varBanks = listOf(SaveBank(GEN5_VAR_MONTH, 0, 1) to byteArrayOf(month.toByte(), 0)),
        )
    }

    private fun names(slots: List<EncounterSlot>) = slots.map { speciesNamesBlack[it.species] to it.percent }

    // The app's (Gen 3's) type ids.
    private val NORMAL = 0
    private val POISON = 3
    private val GRASS = 12
    private val ELECTRIC = 13
    private val DRAGON = 16
    private val DARK = 17

    @Test
    fun `species, types and moves are the cart's`() {
        assertEquals(649, speciesNamesBlack.size)
        assertEquals("Snivy", speciesNamesBlack[495])
        assertEquals(SpeciesTypes(GRASS, GRASS), speciesTypesBlack[495])
        // Gen 5's DARK (16) / DRAGON (15) moved up past Gen 3's ??? (9).
        assertEquals(SpeciesTypes(DARK, DRAGON), speciesTypesBlack[635])        // HYDREIGON
        assertEquals(SpeciesTypes(GRASS, POISON), speciesTypesBlack[1])          // BULBASAUR
        assertEquals(MoveInfo("Fusion Bolt", ELECTRIC, 100), moveDataBlack[559])
        assertEquals(MoveInfo("Pound", NORMAL, 40), moveDataBlack[1])
        assertEquals(MoveInfo("Hone Claws", DARK, 0), moveDataBlack[468])
    }

    @Test
    fun `items, their pockets and icons`() {
        assertEquals("Poké Ball", itemNamesBlack[4])
        assertEquals(null, itemPocketsBlack[4])                         // Poké Balls ride in ITEMS in Gen 5
        assertEquals(POCKET_MEDICINE, itemPocketsBlack[17])             // Potion
        assertEquals(POCKET_TM_HM, itemPocketsBlack[420])               // HM01
        assertEquals(POCKET_BERRIES, itemPocketsBlack[149])             // Cheri Berry
        assertEquals(POCKET_KEY_ITEMS, itemPocketsBlack[450])           // Bicycle
        assertFalse(itemNamesBlack.values.any { it == "???" || it.isEmpty() })
        assertEquals(0x00020003, itemIconsBlack[1])                     // Master Ball: a/0/2/5's members 2 / 3
        assertEquals(712, iconPalettesBlack.size)                       // 649 species, the eggs, the forms
        assertEquals(1, iconPalettesBlack[1].toInt())                   // BULBASAUR
        assertEquals(1, iconPalettesBlack[650].toInt())                 // the egg
    }

    @Test
    fun `zones are named by their place`() {
        assertEquals(427, mapPlaceIdsBlack.size)
        assertEquals("Gear Station", mapPlacesBlack[66])
        assertEquals("Route 1", mapPlacesBlack[317])
        assertEquals("Nuvema Town", placeNamesBlack[mapPlaceIdsBlack[389]])
        assertEquals(FlySpot(389, 782, 750, flag = 2480), flySpotsBlack.first())   // NUVEMA TOWN
    }

    @Test
    fun `Route 1 - grass, rustling grass and fishing`() {
        val e = BLACK_WORLD.encounters(0, 317, progress(month = 1))
        assertEquals(listOf("Patrat" to 50, "Lillipup" to 50), names(e.grass).sortedBy { it.first }.reversed())
        assertEquals(2, e.grass.minOf { it.minLevel })
        assertEquals(4, e.grass.maxOf { it.maxLevel })
        assertEquals(listOf("Basculin" to 95, "Feebas" to 5), names(e.superRod))
        assertTrue(e.oldRod.isEmpty() && e.goodRod.isEmpty() && e.rockSmash.isEmpty())
        val x = BLACK_WORLD.extras(317, progress(month = 1))
        assertEquals(listOf("Audino" to 100), names(x.rustlingGrass))
        assertFalse(BLACK_WORLD.seasonal(317))
    }

    @Test
    fun `the Moor of Icirrus follows the seasons`() {
        assertTrue(BLACK_WORLD.seasonal(346))
        // January: spring; April: winter, when its grass has nothing.
        assertEquals("SPRING", BLACK_WORLD.clockNote(progress(month = 1)))
        assertEquals("WINTER", BLACK_WORLD.clockNote(progress(month = 4)))
        assertEquals("SUMMER", BLACK_WORLD.clockNote(progress(month = 10)))
        assertTrue(BLACK_WORLD.encounters(0, 346, progress(month = 1)).grass.isNotEmpty())
        assertTrue(BLACK_WORLD.encounters(0, 346, progress(month = 4)).grass.isEmpty())
        assertTrue(BLACK_WORLD.encounters(0, 346, progress(month = 4)).water.isNotEmpty())
        assertEquals(0, BLACK_WORLD.season(progress(month = 5)))
    }

    @Test
    fun `NEXT BOSS walks the gyms, then the Elite Four`() {
        val bosses = GUIDE_BLACK.bosses
        assertEquals(14, bosses.size)
        assertFalse(GUIDE_BLACK.verified)
        val trio = bosses.first { !it.isDone(progress()) }
        assertEquals("STRIATON GYM LEADER", trio.title)
        assertEquals(listOf("CHILI" to 11, "CRESS" to 13, "CILAN" to 12), trio.variants)
        val chili = BLACK_WORLD.party(11, null)
        assertEquals(listOf("Lillipup" to 12, "Pansear" to 14), chili.map { speciesNamesBlack[it.species] to it.level })
        assertEquals(listOf("Incinerate", "Work Up"), chili[1].moves.map { moveDataBlack[it]?.name })
        assertEquals("LEADER LENORA", bosses.first { !it.isDone(progress(badges = 1)) }.title)
        assertEquals("ELITE FOUR SHAUNTAL", bosses.first { !it.isDone(progress(badges = 8)) }.title)
        assertEquals("ELITE FOUR MARSHAL", bosses.first { !it.isDone(progress(badges = 8, flags = listOf(BW_FLAG_E4, BW_FLAG_E4 + 1))) }.title)
        val n = bosses.first { it.title == "N" }
        assertEquals(listOf(644, 565, 584, 567, 571, 601), BLACK_WORLD.party(n.trainer(progress()), null).map { it.species })   // ZEKROM first
        for (b in bosses) for (t in b.trainers(progress())) {
            val team = BLACK_WORLD.party(t, null)
            assertTrue(b.title, team.size in 2..6 && team.all { it.moves.isNotEmpty() && it.level in 2..100 })
        }
    }

    @Test
    fun `HERE's item balls are keyed by place`() {
        val route1 = mapPlaceIdsBlack.getValue(317)
        val things = BLACK_AREAS_RAW.flatMap { it.lines() }.filter { it.startsWith("$route1|ITEM|") }
        // Route 1's two item balls: a Max Ether (39) and a Pearl (88), each with its hide flag.
        assertEquals(listOf("$route1|ITEM|39|1230|1|0|", "$route1|ITEM|88|1143|1|0|").sorted(), things.sorted())
        val dreamyard = mapPlaceIdsBlack.getValue(152)
        assertTrue(BLACK_AREAS_RAW.any { it.contains("\n$dreamyard|ITEM|${328 + 84}|") })   // TM85
    }
}
