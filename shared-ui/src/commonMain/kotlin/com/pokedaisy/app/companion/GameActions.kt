package com.pokedaisy.app.companion

import com.pokedaisy.app.Hotkeys
import kotlin.time.TimeSource

private val actionClockOrigin = TimeSource.Monotonic.markNow()

/** Common game shortcuts and touch extras. Hosts supply native input effects and their existing slot actions. */
class GameActions(
    private val settings: CompanionSettings,
    private val slots: StateSlots,
    private val selectSlot: (Int) -> Unit,
    private val hold: (Hotkeys.Action, Boolean) -> Unit,
    private val companion: () -> Unit,
    private val nowMillis: () -> Long = { actionClockOrigin.elapsedNow().inWholeMilliseconds },
) {
    enum class Source { HARDWARE, TOUCH }
    private var closed = false
    private var ffDownAt: Long? = null
    private val held = mutableMapOf<Hotkeys.Action, Set<Source>>()

    private fun hold(action: Hotkeys.Action, down: Boolean, source: Source) {
        val before = held[action].orEmpty()
        val after = if (down) before + source else before - source
        if (after.isEmpty()) held.remove(action) else held[action] = after
        if (before.isNotEmpty() != after.isNotEmpty()) hold(action, after.isNotEmpty())
    }

    /** Release one input owner without cancelling the other's holds. Called on the host's input thread. */
    fun cancelHardware() = release(Source.HARDWARE)
    private fun release(source: Source) {
        for (action in held.keys.toList()) hold(action, false, source)
    }

    /** State changes can quarantine a particular action until a new press. */
    fun release(action: Hotkeys.Action) {
        if (action == Hotkeys.Action.FF_HOLD) ffDownAt = null
        if (held.remove(action)?.isNotEmpty() == true) hold(action, false)
    }

    fun clear() {
        ffDownAt = null
        for (action in held.keys.toList()) release(action)
    }

    fun close() {
        if (closed) return
        closed = true
        clear()
    }

    /** False leaves platform-only shortcuts with their host, including QUICK MENU's release event. */
    fun hotkey(event: Hotkeys.Event): Boolean {
        val action = event.action
        if (closed) return action in BASIC_ACTIONS
        if (action in HELD) {
            hold(action, event.pressed, Source.HARDWARE)
            return true
        }
        if (action !in BASIC_ACTIONS) return false
        if (!event.pressed) return true
        when (action) {
            Hotkeys.Action.SAVE_STATE -> slots.requestSave(slots.currentIndex)
            Hotkeys.Action.LOAD_STATE -> slots.requestLoad(slots.currentIndex)
            Hotkeys.Action.UNDO_SAVE -> slots.requestUndoSave()
            Hotkeys.Action.UNDO_LOAD -> slots.requestUndoLoad()
            Hotkeys.Action.SLOT_NEXT -> selectSlot((slots.currentIndex + 1) % 10)
            Hotkeys.Action.SLOT_PREV -> selectSlot((slots.currentIndex + 9) % 10)
            Hotkeys.Action.FF_TOGGLE -> settings.setFfToggled(!settings.ffToggled)
            Hotkeys.Action.SPEED_CYCLE -> settings.setGameSpeed(PlaybackPolicy.cycleSpeed(settings.gameSpeed))
            Hotkeys.Action.EXIT_GAME -> settings.closeGame()
            Hotkeys.Action.RESTART_GAME -> settings.restartGame()
            Hotkeys.Action.COMPANION -> companion()
            Hotkeys.Action.MUTE -> settings.setGameMuted(!settings.gameMuted)
            else -> Unit
        }
        return true
    }

    /** FF taps toggle, holds last until release. X/Y and platform-only extras remain native input. */
    fun touch(button: TouchButton, down: Boolean): Boolean {
        if (closed) return button in setOf(TouchButton.FF, TouchButton.REWIND, TouchButton.SAVE_STATE, TouchButton.LOAD_STATE)
        when (button) {
            TouchButton.FF -> {
                if (down) {
                    ffDownAt = nowMillis()
                    hold(Hotkeys.Action.FF_HOLD, true, Source.TOUCH)
                } else {
                    hold(Hotkeys.Action.FF_HOLD, false, Source.TOUCH)
                    ffDownAt?.let { if (nowMillis() - it in 0L until 300L) settings.setFfToggled(!settings.ffToggled) }
                    ffDownAt = null
                }
            }
            TouchButton.SAVE_STATE -> if (down) slots.requestSave(slots.currentIndex)
            TouchButton.LOAD_STATE -> if (down) slots.requestLoad(slots.currentIndex)
            TouchButton.REWIND -> hold(Hotkeys.Action.REWIND_HOLD, down, Source.TOUCH)
            else -> return false
        }
        return true
    }

    fun cancelTouch() {
        ffDownAt = null
        release(Source.TOUCH)
    }

    companion object {
        private val HELD = setOf(Hotkeys.Action.FF_HOLD, Hotkeys.Action.SLOWMO_HOLD, Hotkeys.Action.REWIND_HOLD)
        val BASIC_ACTIONS = listOf(Hotkeys.Action.SAVE_STATE, Hotkeys.Action.LOAD_STATE, Hotkeys.Action.UNDO_SAVE,
            Hotkeys.Action.UNDO_LOAD, Hotkeys.Action.SLOT_NEXT, Hotkeys.Action.SLOT_PREV, Hotkeys.Action.FF_HOLD,
            Hotkeys.Action.FF_TOGGLE, Hotkeys.Action.SPEED_CYCLE, Hotkeys.Action.SLOWMO_HOLD, Hotkeys.Action.REWIND_HOLD,
            Hotkeys.Action.EXIT_GAME, Hotkeys.Action.RESTART_GAME, Hotkeys.Action.COMPANION, Hotkeys.Action.MUTE)
    }
}
