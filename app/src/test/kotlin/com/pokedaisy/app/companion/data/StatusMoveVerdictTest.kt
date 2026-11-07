package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusMoveVerdictTest {
    private fun mon(moves: List<MoveView>) = MonView(1, "A", 5, 10, 10, "", listOf("NORMAL"), null, moves = moves)

    /** The chart rates damage: a status move has no verdict to show (GitHub #90). */
    @Test fun onlyDamagingMovesGetAVerdict() {
        val rated = mon(listOf(MoveView("GROWL", "NORMAL", 40, 0), MoveView("TACKLE", "NORMAL", 35, 35))).withMovesVs(mon(emptyList()))
        assertTrue(rated.moves[0].vs.isEmpty())
        assertEquals(1, rated.moves[1].vs.size)
    }
}
