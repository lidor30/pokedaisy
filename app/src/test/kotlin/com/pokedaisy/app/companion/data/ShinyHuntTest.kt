package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SHINY ALERT's counting ([ShinyHunt]): one count a wild battle, once its foes are read; one alert a battle. */
class ShinyHuntTest {
    private val plain = MonView(species = 25, name = "A", level = 5, hp = 10, maxHp = 10, status = "", types = emptyList(), iconAsset = null)
    private val shiny = plain.copy(species = 26, shiny = true)

    @Test
    fun oneCountEachWildBattle() {
        val h = ShinyHunt()
        var counts = 0
        repeat(3) {
            // The intro reads no foe yet, then several samples of the same battle, then the field again.
            listOf(emptyList(), listOf(plain), listOf(plain), listOf(plain)).forEach { foes ->
                if (h.sample(inBattle = true, wild = true, foes = foes).counted) counts++
            }
            h.sample(inBattle = false, wild = false, foes = emptyList())
        }
        assertEquals(3, counts)
    }

    @Test
    fun trainersDontCount() {
        val h = ShinyHunt()
        val s = h.sample(inBattle = true, wild = false, foes = listOf(shiny))
        assertFalse(s.counted)
        assertNull(s.shiny)
    }

    @Test
    fun aShinyOnceABattle() {
        val h = ShinyHunt()
        val first = h.sample(inBattle = true, wild = true, foes = listOf(shiny))
        assertTrue(first.counted)
        assertEquals(26, first.shiny?.species)
        assertNull(h.sample(inBattle = true, wild = true, foes = listOf(shiny)).shiny)
        h.sample(inBattle = false, wild = false, foes = emptyList())
        assertEquals(26, h.sample(inBattle = true, wild = true, foes = listOf(plain, shiny)).shiny?.species)
    }

    @Test
    fun wildByTheReaderElseNoFoeTeam() {
        val base = SnapshotView(connected = true)
        assertTrue(ShinyHunt.wild(base.copy(isTrainerBattle = false, enemyParty = listOf(plain))))
        assertFalse(ShinyHunt.wild(base.copy(isTrainerBattle = true)))
        assertTrue(ShinyHunt.wild(base))
        assertFalse(ShinyHunt.wild(base.copy(enemyParty = listOf(plain))))
    }

    /** Real battles: the headless shiny foes SHINY ODDS made (FireRed, Emerald) and a plain wild one (German Emerald). */
    @Test
    fun theFixturesBattles() {
        for ((fixture, cfg) in listOf("emerald_shiny_battle" to NATIVE_EMERALD_RETAIL, "firered_shiny_battle" to NATIVE_FIRERED_REV1)) {
            val view = buildSnapshotView(readNativeTelemetry(FixtureMemoryReader.load(fixture), cfg.copy(shinyBelow = 8)))
            assertTrue(fixture, view.inBattle)
            assertTrue(fixture, ShinyHunt.wild(view))
            val step = ShinyHunt().sample(view.inBattle, ShinyHunt.wild(view), view.battleOpponent)
            assertTrue(fixture, step.counted)
            assertEquals(fixture, view.battleOpponent.first().species, step.shiny?.species)
        }
        val plain = buildSnapshotView(readNativeTelemetry(FixtureMemoryReader.load("emerald_de_battle"), NATIVE_EMERALD_RETAIL.copy(shinyBelow = 8)))
        val step = ShinyHunt().sample(plain.inBattle, ShinyHunt.wild(plain), plain.battleOpponent)
        assertTrue(step.counted)
        assertNull(step.shiny)
    }
}
