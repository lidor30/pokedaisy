package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoveLearningWatchTest {
    private val w = MoveLearningWatch()

    @Test fun `the summary is detected from a level-up and stays up while it reads SELECT_MOVE`() {
        w.reset()
        assertNull(w.check(levelMove = 0, menuMove = 0, mode = 3, newMove = 33, monIndex = 0, partySize = 1))
        // A level-up: gMoveToLearn changes before the summary opens.
        assertNull(w.check(levelMove = 33, menuMove = 0, mode = 0, newMove = 0, monIndex = 0, partySize = 1))
        // The select-move page comes up.
        assertEquals(
            MoveLearningPrompt(0, 33),
            w.check(levelMove = 33, menuMove = 0, mode = 3, newMove = 33, monIndex = 0, partySize = 1),
        )
        // A page already open needs no new event.
        assertEquals(
            MoveLearningPrompt(0, 33),
            w.check(levelMove = 33, menuMove = 0, mode = 3, newMove = 33, monIndex = 0, partySize = 1),
        )
        // Closed: the struct reads 0.
        assertNull(w.check(levelMove = 33, menuMove = 0, mode = 0, newMove = 0, monIndex = 0, partySize = 1))
        // Opening that same page again is recognised by the mode change.
        assertEquals(
            MoveLearningPrompt(0, 33),
            w.check(levelMove = 33, menuMove = 0, mode = 3, newMove = 33, monIndex = 0, partySize = 1),
        )
    }

    @Test fun `a TM's move from gPartyMenu is detected`() {
        w.reset()
        assertNull(w.check(levelMove = 0, menuMove = 0, mode = 0, newMove = 0, monIndex = 0, partySize = 1))
        // The party menu sets data1 to the move; the summary is the select-move screen that follows.
        assertNull(w.check(levelMove = 0, menuMove = 57, mode = 0, newMove = 0, monIndex = 1, partySize = 2))
        assertEquals(
            MoveLearningPrompt(1, 57),
            w.check(levelMove = 0, menuMove = 57, mode = 3, newMove = 57, monIndex = 1, partySize = 2),
        )
    }
}
