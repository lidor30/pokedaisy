package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.NuzlockeEncounter
import com.pokedaisy.app.companion.data.NuzlockeOutcome
import com.pokedaisy.app.companion.data.NuzlockeRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NuzlockeSessionTest {
    @Test
    fun keepsEachRomsRunAndIgnoresTeardownFromThePreviousGame() {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store())
        val encounter = NuzlockeEncounter(3, "Route", 25, NuzlockeOutcome.CAUGHT)
        prefs.setNuzlockeRunFor("emerald", NuzlockeRun(encounters = listOf(encounter)).encode())
        prefs.setNuzlockeFor("emerald", true)
        val emerald = Nuzlocke.start(prefs, "emerald")
        assertTrue(Nuzlocke.on)
        assertEquals(listOf(encounter), Nuzlocke.run.encounters)
        val fireRed = Nuzlocke.start(prefs, "firered")
        try {
            assertFalse(Nuzlocke.on)
            assertTrue(Nuzlocke.run.encounters.isEmpty())
            Nuzlocke.setOn(true)
            Nuzlocke.close(emerald)
            assertTrue(Nuzlocke.on)
            assertTrue(prefs.nuzlockeFor("firered"))
            assertEquals(listOf(encounter), NuzlockeRun.decode(prefs.nuzlockeRunFor("emerald")).encounters)
        } finally {
            Nuzlocke.close(fireRed)
        }
        assertFalse(Nuzlocke.on)
    }
}
