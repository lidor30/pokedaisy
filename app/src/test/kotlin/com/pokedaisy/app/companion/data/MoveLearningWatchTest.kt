package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoveLearningWatchTest {
    @Test fun `the summary is detected from a level-up and stays up while it reads SELECT_MOVE`() {
        MoveLearningWatch.reset()
        assertNull(MoveLearningWatch.check(levelMove = 0, menuMove = 0, mode = 3, newMove = 33, monIndex = 0, partySize = 1))
        // A level-up: gMoveToLearn changes before the summary opens.
        assertNull(MoveLearningWatch.check(levelMove = 33, menuMove = 0, mode = 0, newMove = 0, monIndex = 0, partySize = 1))
        // The select-move page comes up.
        assertEquals(
            MoveLearningPrompt(0, 33),
            MoveLearningWatch.check(levelMove = 33, menuMove = 0, mode = 3, newMove = 33, monIndex = 0, partySize = 1),
        )
        // A page already open needs no new event.
        assertEquals(
            MoveLearningPrompt(0, 33),
            MoveLearningWatch.check(levelMove = 33, menuMove = 0, mode = 3, newMove = 33, monIndex = 0, partySize = 1),
        )
        // Closed: the struct reads 0.
        assertNull(MoveLearningWatch.check(levelMove = 33, menuMove = 0, mode = 0, newMove = 0, monIndex = 0, partySize = 1))
        // Opening that same page again is recognised by the mode change.
        assertEquals(
            MoveLearningPrompt(0, 33),
            MoveLearningWatch.check(levelMove = 33, menuMove = 0, mode = 3, newMove = 33, monIndex = 0, partySize = 1),
        )
    }

    @Test fun `a TM's move from gPartyMenu is detected`() {
        MoveLearningWatch.reset()
        assertNull(MoveLearningWatch.check(levelMove = 0, menuMove = 0, mode = 0, newMove = 0, monIndex = 0, partySize = 1))
        // The party menu sets data1 to the move; the summary is the select-move screen that follows.
        assertNull(MoveLearningWatch.check(levelMove = 0, menuMove = 57, mode = 0, newMove = 0, monIndex = 1, partySize = 2))
        assertEquals(
            MoveLearningPrompt(1, 57),
            MoveLearningWatch.check(levelMove = 0, menuMove = 57, mode = 3, newMove = 57, monIndex = 1, partySize = 2),
        )
    }
}
