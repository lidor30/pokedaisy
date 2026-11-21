package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Crystal's PC boxes from the cart's battery RAM ([readGen2Boxes]) on `crystal_boxes`: Pokémon put in three boxes by the
 * game's own functions (native-capture/boot/crystal_boxes.txt) - BOX 1 in SRAM bank 2, BOX 8 (renamed TEAM) in bank 3,
 * BOX 3 the current one, read from sBox while its place in bank 2 still holds the empty box the game loaded.
 */
class Gen2BoxesTest {
    @After
    fun tearDown() {
        Gen1Dex.rom = null
    }

    private fun read(): PcBoxes {
        activeGame = GameKind.CRYSTAL
        romLanguage = 'E'
        romGameCode = ""
        return readGen2Telemetry(FixtureMemoryReader.load("crystal_boxes"), GEN2_CRYSTAL).pcBoxes.also { assertNotNull(it) }!!
    }

    private fun PcBox.species() = mons.filterNotNull().map { it.species to it.level }

    @Test
    fun `three boxes from both banks and sBox, the current one included`() {
        val boxes = read()
        assertEquals(14, boxes.boxes.size)
        assertEquals(2, boxes.currentBox)
        assertTrue(boxes.boxes.all { it.mons.size == 20 })
        assertEquals("BOX1", boxes.boxes[0].name)
        assertEquals("TEAM", boxes.boxes[7].name)
        assertEquals("BOX14", boxes.boxes[13].name)
        assertEquals(listOf(129 to 5, 74 to 8), boxes.boxes[0].species())
        assertEquals(listOf(163 to 4, 41 to 6), boxes.boxes[7].species())
        assertEquals(listOf(16 to 3), boxes.boxes[2].species())
        assertEquals(setOf(0, 2, 7), boxes.boxes.indices.filter { boxes.boxes[it].mons.any { m -> m != null } }.toSet())
        val pidgey = boxes.boxes[2].mons[0]!!
        assertEquals("PIDGEY", pidgey.nickname)
        assertTrue(pidgey.moves.any { it != 0 })
    }

    @Test
    fun `the party's own boxes stay apart from it`() {
        activeGame = GameKind.CRYSTAL
        val t = readGen2Telemetry(FixtureMemoryReader.load("crystal_boxes"), GEN2_CRYSTAL)
        assertEquals(listOf(152, 16, 25, 133), t.party.map { it.species })
        // Other Gen 2 configs get no boxes (their cart RAM isn't mapped).
        assertNull(readGen2Telemetry(FixtureMemoryReader.load("crystal_boxes"), GEN2_CRYSTAL_LEGACY).pcBoxes)
    }

    @Test
    fun `stats worked out from the cart's base stats, HP full`() {
        val rom = File(File(RETAIL_ROM_DIR).parentFile, "gbc/Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc")
        assumeTrue("no Crystal cart at $rom", rom.isFile)
        Gen1Dex.rom = rom.readBytes()
        val boxes = read()
        // BaseData: HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF.
        val base = mapOf(
            129 to listOf(20, 10, 55, 80, 15, 20), 74 to listOf(40, 80, 100, 20, 30, 30),
            163 to listOf(60, 30, 30, 50, 36, 56), 41 to listOf(40, 45, 35, 55, 30, 40), 16 to listOf(40, 45, 40, 56, 35, 35),
        )
        var n = 0
        for (box in boxes.boxes) for (mon in box.mons.filterNotNull()) {
            val s = mon.stats!!
            val want = List(6) { i ->
                val exp = kotlin.math.ceil(kotlin.math.sqrt(s.evs[i].toDouble())).toInt() / 4
                val core = ((base.getValue(mon.species)[i] + s.ivs[i]) * 2 + exp) * mon.level / 100
                if (i == STAT_HP) core + mon.level + 10 else core + 5
            }
            assertEquals("species ${mon.species}", want, s.stats)
            assertEquals(s.stats[STAT_HP], mon.hp)
            assertEquals(s.stats[STAT_HP], mon.maxHp)
            n++
        }
        assertEquals(5, n)
    }

    @Test
    fun `German Kristall keeps English's boxes, its names with umlauts`() {
        activeGame = GameKind.CRYSTAL
        romLanguage = 'D'
        romGameCode = "BYTD"
        // crystal_de_boxes: the save written by the game's own SaveGameData, then loaded with CONTINUE.
        val t = readGen2Telemetry(FixtureMemoryReader.load("crystal_de_boxes"), GEN2_CRYSTAL_DE)
        assertEquals(listOf(152, 16, 25, 133, 179, 74), t.party.map { it.species })
        val boxes = t.pcBoxes!!
        assertEquals(0, boxes.currentBox)
        assertEquals(listOf("BOX1", "WASSER", "HÖHLE", "SELTEN"), listOf(0, 1, 2, 7).map { boxes.boxes[it].name })
        assertEquals(listOf(19 to 4, 161 to 5, 163 to 6, 10 to 4, 13 to 5, 167 to 7, 165 to 6, 69 to 8), boxes.boxes[0].species())
        assertEquals(listOf(129 to 10, 54 to 12, 60 to 11, 72 to 14, 98 to 13, 118 to 9, 183 to 12), boxes.boxes[1].species())
        assertEquals(listOf(92 to 15, 41 to 10, 95 to 14, 63 to 9, 96 to 12), boxes.boxes[2].species())
        assertEquals(listOf(147 to 15, 246 to 20, 123 to 18, 131 to 25), boxes.boxes[7].species())
        // Nicknames are the German species names the game gave them.
        assertEquals(listOf("RATTFRATZ", "WIESOR"), boxes.boxes[0].mons.take(2).map { it!!.nickname })
    }
}
