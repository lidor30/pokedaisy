package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.SnapshotView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameShinyAlertTest {
    private val field = SnapshotView(connected = true)
    private val foe = MonView(species = 25, name = "PIKACHU", level = 5, hp = 10, maxHp = 10,
        status = "", types = emptyList(), iconAsset = null)
    private val wild = field.copy(inBattle = true, isTrainerBattle = false, battleOpponent = listOf(foe))
    private val shiny = wild.copy(battleOpponent = listOf(foe.copy(shiny = true)))

    @Test
    fun countsOnceAndResetsWhenAShinyAppears() {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store())
        val hunt = GameShinyAlert(prefs)
        hunt.start("emerald")
        hunt.set(true)
        assertNull(hunt.observe(wild, true))
        assertNull(hunt.observe(wild, true))
        assertEquals(1, hunt.count)
        hunt.observe(field, true)
        assertNull(hunt.observe(shiny.copy(isTrainerBattle = true), true))
        hunt.observe(field, true)
        assertEquals(2, hunt.observe(shiny, true)?.battles)
        assertNull(hunt.observe(shiny, true))
        assertEquals(0, prefs.shinyHuntFor("emerald"))
    }

    @Test
    fun scopesPersistedCountsAndRequiresVerifiedSupport() {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store())
        prefs.shinyAlert = true
        prefs.setShinyHuntFor("emerald", 9)
        val hunt = GameShinyAlert(prefs)
        hunt.start("firered")
        assertEquals(0, hunt.count)
        assertNull(hunt.observe(shiny, false))
        assertNull(hunt.observe(shiny.copy(connected = false), true))
        assertEquals(0, hunt.count)
        hunt.start("emerald")
        assertEquals(9, hunt.count)
        hunt.set(false)
        assertNull(hunt.observe(shiny, true))
        assertEquals(9, hunt.count)
        hunt.set(true)
        assertEquals(0, hunt.count)
        assertEquals(1, hunt.observe(shiny, true)?.battles)
    }
}
