package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The names the PARTY tab and its summary print (see [MonView.shownName]). */
class PartyNameTest {
    private fun mon(name: String, nickname: String? = null) =
        MonView(25, name, 10, 20, 20, "", listOf("Electric"), null, nickname = nickname)

    @Test
    fun `a stored nickname replaces the species name`() {
        assertEquals("Sparky", mon("PIKACHU", "Sparky").shownName)
        assertTrue(mon("PIKACHU", "Sparky").renamed)
    }

    @Test
    fun `no nickname, or the species' own, leaves the species name showing`() {
        assertEquals("PIKACHU", mon("PIKACHU").shownName)
        assertFalse(mon("Pikachu", "PIKACHU").renamed)
    }
}
