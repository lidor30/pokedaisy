package com.pokedaisy.app.companion

import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.Prefs
import kotlin.test.*

class GameActionsTest {
    private val stored = PrefsCompanionSettingsTest.Settings(Prefs(PrefsCompanionSettingsTest.Store()))
    private var speed = 1f
    private var closed = 0
    private var restarted = 0
    private var companion = 0
    private val settings = object : CompanionSettings by stored {
        override val gameSpeed get() = speed
        override fun setGameSpeed(v: Float) { speed = v }
        override fun closeGame() { closed++ }
        override fun restartGame() { restarted++ }
    }
    private val requests = mutableListOf<String>()
    private var index = 0
    private val slots = object : StateSlots {
        override val currentIndex get() = index
        override fun list(): List<StateSlots.Slot> = emptyList()
        override fun requestSave(index: Int) { requests += "save $index" }
        override fun requestLoad(index: Int) { requests += "load $index" }
        override fun requestDelete(index: Int) { error("not a shortcut") }
        override fun requestUndoSave() { requests += "undo save" }
        override fun requestUndoLoad() { requests += "undo load" }
    }
    private val holds = mutableListOf<Pair<Hotkeys.Action, Boolean>>()
    private var time = 1_000L
    private val actions = GameActions(settings, slots, { index = it },
        { action, down -> holds += action to down }, { companion++ }, { time })
    private fun key(action: Hotkeys.Action, down: Boolean = true) = actions.hotkey(Hotkeys.Event(action, down))

    @Test fun shortcutReleasesNeverRepeatActionsAndSlotsWrap() {
        key(Hotkeys.Action.SLOT_PREV); assertEquals(9, index)
        key(Hotkeys.Action.SAVE_STATE); key(Hotkeys.Action.SAVE_STATE, false)
        key(Hotkeys.Action.SLOT_NEXT); key(Hotkeys.Action.LOAD_STATE)
        key(Hotkeys.Action.UNDO_SAVE); key(Hotkeys.Action.UNDO_LOAD)
        assertEquals(listOf("save 9", "load 0", "undo save", "undo load"), requests)
        assertFalse(key(Hotkeys.Action.QUICK_MENU, false))
    }

    @Test fun touchCancellationKeepsHardwareOwnershipAndNeverToggles() {
        key(Hotkeys.Action.FF_HOLD)
        actions.touch(TouchButton.FF, true); time += 299; actions.touch(TouchButton.FF, false)
        assertTrue(settings.ffToggled)
        actions.touch(TouchButton.FF, true); time += 300; actions.touch(TouchButton.FF, false)
        assertTrue(settings.ffToggled)
        actions.touch(TouchButton.FF, true); actions.cancelTouch(); actions.touch(TouchButton.FF, false)
        assertTrue(settings.ffToggled)
        assertEquals(listOf(Hotkeys.Action.FF_HOLD to true), holds)
        key(Hotkeys.Action.FF_HOLD, false)
        assertEquals(listOf(Hotkeys.Action.FF_HOLD to true, Hotkeys.Action.FF_HOLD to false), holds)
    }

    @Test fun touchAndHardwareUseTheSameSlotsAndSettingsActions() {
        index = 4
        actions.touch(TouchButton.SAVE_STATE, true); actions.touch(TouchButton.SAVE_STATE, false)
        key(Hotkeys.Action.LOAD_STATE)
        assertEquals(listOf("save 4", "load 4"), requests)
        key(Hotkeys.Action.FF_TOGGLE); assertTrue(settings.ffToggled)
        key(Hotkeys.Action.MUTE); assertTrue(settings.gameMuted)
        key(Hotkeys.Action.SPEED_CYCLE); assertEquals(PlaybackPolicy.cycleSpeed(1f), speed)
        key(Hotkeys.Action.COMPANION); key(Hotkeys.Action.EXIT_GAME); key(Hotkeys.Action.RESTART_GAME)
        assertEquals(1, companion); assertEquals(1, closed); assertEquals(1, restarted)
        assertFalse(actions.touch(TouchButton.X, true))
    }
    @Test fun hardwareCancellationKeepsTouchAndActionReleaseQuarantinesEveryOwner() {
        actions.touch(TouchButton.REWIND, true)
        key(Hotkeys.Action.REWIND_HOLD)
        actions.cancelHardware()
        assertEquals(listOf(Hotkeys.Action.REWIND_HOLD to true), holds)
        actions.release(Hotkeys.Action.REWIND_HOLD)
        assertEquals(Hotkeys.Action.REWIND_HOLD to false, holds.last())
        actions.touch(TouchButton.REWIND, false)
        assertEquals(2, holds.size)
        key(Hotkeys.Action.REWIND_HOLD)
        assertEquals(Hotkeys.Action.REWIND_HOLD to true, holds.last())
        actions.clear(); actions.clear()
        assertEquals(4, holds.size)
    }

    @Test fun clearingAllHoldsCancelsPendingTapAndIsIdempotent() {
        actions.touch(TouchButton.FF, true)
        key(Hotkeys.Action.SLOWMO_HOLD)
        actions.clear(); actions.clear()
        actions.touch(TouchButton.FF, false)
        assertFalse(settings.ffToggled)
        assertEquals(1, holds.count { it == Hotkeys.Action.FF_HOLD to false })
        assertEquals(1, holds.count { it == Hotkeys.Action.SLOWMO_HOLD to false })
    }

    @Test fun closingReleasesHoldsAndMakesDelayedInputInert() {
        actions.touch(TouchButton.FF, true)
        actions.close(); actions.close()
        actions.touch(TouchButton.FF, false)
        actions.touch(TouchButton.SAVE_STATE, true)
        key(Hotkeys.Action.FF_TOGGLE); key(Hotkeys.Action.LOAD_STATE)
        assertFalse(settings.ffToggled)
        assertTrue(requests.isEmpty())
        assertEquals(listOf(Hotkeys.Action.FF_HOLD to true, Hotkeys.Action.FF_HOLD to false), holds)
        assertFalse(key(Hotkeys.Action.QUICK_MENU))
    }
}
