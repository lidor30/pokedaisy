package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** [moveKey]'s accent table gives what the JDK's NFD normalisation it replaced gave. */
class MoveKeyTest {
    private fun nfdKey(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).uppercase().filter { it in 'A'..'Z' }

    @Test fun matchesNfdOnLatinLetters() {
        for (cp in 0x20..0x24F) {
            val s = "A" + cp.toChar() + "z"
            assertEquals("U+%04X".format(cp), nfdKey(s), moveKey(s))
        }
    }

    @Test fun matchesNfdOnTheGamesSpellings() {
        for (s in listOf("Freeze-Dry", "Lyophilisation", "Lioflización", "Liofilización", "Plancha Voladora", "Mil Flechas", "Électrique", "Psíquico"))
            assertEquals(s, nfdKey(s), moveKey(s))
    }
}
