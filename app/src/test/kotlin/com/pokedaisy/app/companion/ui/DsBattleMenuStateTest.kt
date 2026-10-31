package com.pokedaisy.app.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A DS battle's Gen 3 menus on the D-pad: FireRed's 2 x 2 grids (no wrapping), A picks, B leaves the moves. */
class DsBattleMenuStateTest {
    private val picked = mutableListOf<String>()
    private val state = DsBattleMenuState().apply {
        pickAction = { picked += "action $it" }
        pickMove = { picked += "move $it" }
        back = { picked += "back" }
    }

    @Test
    fun theActionsAreATwoByTwoThatDoesNotWrap() {
        state.mode = 1
        assertTrue(state.key(MenuKey.RIGHT))
        assertEquals(1, state.actionCursor)   // BAG
        state.key(MenuKey.RIGHT)
        assertEquals(1, state.actionCursor)   // no wrap
        state.key(MenuKey.DOWN)
        assertEquals(3, state.actionCursor)   // RUN
        state.key(MenuKey.LEFT)
        assertEquals(2, state.actionCursor)   // POKéMON
        state.key(MenuKey.A)
        assertEquals(listOf("action 2"), picked)
        // B on the actions does nothing (the game's own B there doesn't either), but it's still the menu's.
        assertTrue(state.key(MenuKey.B))
        assertEquals(listOf("action 2"), picked)
    }

    @Test
    fun aMoveWithNoPpIsSkippedAndBGoesBack() {
        state.mode = 2
        state.moveEnabled = { it != 1 }
        state.key(MenuKey.RIGHT)
        assertEquals(0, state.moveCursor)    // move 2 is out of PP: the cursor stays
        state.key(MenuKey.DOWN)
        state.key(MenuKey.RIGHT)
        assertEquals(3, state.moveCursor)
        state.key(MenuKey.A)
        state.key(MenuKey.B)
        assertEquals(listOf("move 3", "back"), picked)
    }

    @Test
    fun aGreyedActionIsNotPicked() {
        state.mode = 1
        state.actionEnabled = { it != 3 }   // a trainer battle: no RUN
        state.actionCursor = 3
        state.key(MenuKey.A)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun withNothingUpTheKeysAreTheGames() {
        assertFalse(state.key(MenuKey.A))
        assertFalse(state.key(MenuKey.DOWN))
    }
}
