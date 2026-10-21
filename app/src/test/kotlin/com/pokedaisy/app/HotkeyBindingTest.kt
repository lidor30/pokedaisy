package com.pokedaisy.app

import com.pokedaisy.app.Hotkeys.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** HOTKEYS: CLEAR unbinds, and a chord another hotkey already has is only moved, never shared. */
class HotkeyBindingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun clearedHotkeyStaysUnbound() {
        val dir = tmp.newFolder()
        Hotkeys.clearBinding(dir, Action.UNDO_SAVE)
        // Loaded again (defaults fill in missing keys): still nothing, not F3 back.
        assertEquals(emptyList<String>(), Hotkeys.load(dir).rawBindings[Action.UNDO_SAVE])
    }

    @Test
    fun companionHotkeyIsUnboundUntilThePlayerBindsIt() {
        val dir = tmp.newFolder()
        // Its whole point on one screen is replacing a hidden COMPANION BUTTON tab, so it must not take a button
        // from the game by default.
        assertEquals(emptyList<String>(), Hotkeys.load(dir).rawBindings[Action.COMPANION])
    }

    @Test
    fun theSameChordInAnyOrderClashes() {
        val raw = mapOf(
            Action.SAVE_STATE to listOf("BUTTON_SELECT+BUTTON_R1", "F1"),
            Action.LOAD_STATE to listOf("BUTTON_SELECT+BUTTON_L1"),
        )
        assertEquals(listOf(Action.SAVE_STATE), Hotkeys.clashes(raw, Action.EXIT_GAME, listOf("BUTTON_R1", "BUTTON_SELECT")))
        assertEquals(listOf(Action.SAVE_STATE), Hotkeys.clashes(raw, Action.EXIT_GAME, listOf("f1")))
        // Sharing only some keys is how chords work, and rebinding an action to its own chord is no clash.
        assertTrue(Hotkeys.clashes(raw, Action.EXIT_GAME, listOf("BUTTON_SELECT")).isEmpty())
        assertTrue(Hotkeys.clashes(raw, Action.SAVE_STATE, listOf("F1")).isEmpty())
    }

    @Test
    fun movingAChordTakesOnlyThatChordOffTheOtherHotkey() {
        val dir = tmp.newFolder()
        Hotkeys.load(dir) // the defaults: SAVE STATE = SELECT+R1, F1
        Hotkeys.setBindingNamed(dir, Action.UNDO_SAVE, listOf("F1"), takeFrom = listOf(Action.SAVE_STATE))
        val raw = Hotkeys.load(dir).rawBindings
        assertEquals(listOf("F1"), raw[Action.UNDO_SAVE])
        assertEquals(listOf("BUTTON_SELECT+BUTTON_R1"), raw[Action.SAVE_STATE])
        assertTrue(Hotkeys.clashes(raw, Action.UNDO_SAVE, listOf("F1")).isEmpty())
    }
}
