package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UpcomingMovesTest {
    /** The game's "forget a move?" comes at the level the move is learned: LEARN has to still list it. */
    @Test fun keepsTheMoveOfItsOwnLevel() {
        val moves = listOf(LevelMove(10, 1), LevelMove(20, 2), LevelMove(25, 3))
        assertEquals(listOf(2, 3), MoveLearning.upcoming(moves, 20, emptyList()).map { it.move })
        assertEquals(listOf(3), MoveLearning.upcoming(moves, 20, listOf(2)).map { it.move })
    }
}
