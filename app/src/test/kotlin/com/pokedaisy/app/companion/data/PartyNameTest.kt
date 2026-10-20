package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The names the PARTY tab and its summary print (see [shownNickname]). */
class PartyNameTest {
    private fun mon(name: String, nickname: String? = null, egg: Boolean = false) =
        MonView(25, name, 10, 20, 20, "", listOf("Electric"), null, isEgg = egg, nickname = nickname)

    @Test
    fun `a stored nickname replaces the species name`() {
        assertEquals("Sparky", mon("PIKACHU", "Sparky").shownNickname)
    }

    @Test
    fun `no nickname, a blank one or an egg's leaves the species name showing`() {
        assertNull(mon("PIKACHU").shownNickname)
        assertNull(mon("PIKACHU", "  ").shownNickname)
        assertNull(mon("EGG", "???", egg = true).shownNickname)
    }
}
