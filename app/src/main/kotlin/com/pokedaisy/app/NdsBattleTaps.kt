package com.pokedaisy.app

import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import java.util.ArrayDeque

/**
 * BATTLE BUTTONS on a DS game (HeartGold / SoulSilver): a pick is the game's own touch-screen button pressed with the
 * stylus - FIGHT, then the move's card once the move panel has slid in; BAG / POKéMON / RUN where the battle draws them
 * (battle_input.c's panels, sampled headless: their middles). Steps run on the emu thread, one frame each ([tick], before
 * the frame), so the stylus stays down for whole frames at any speed. The battle's state ([onState]: the fast poll's
 * BATTLE_INPUT_*) says when the move panel is up.
 */
class NdsBattleTaps(private val touch: (down: Boolean, x: Int, y: Int) -> Unit = MgbaCore::pkNdsTouch) {
    private sealed class Step {
        /** The stylus at ([x], [y]) for [frames] frames, then lifted for [gap]. */
        class Tap(val x: Int, val y: Int, val frames: Int = TAP_FRAMES, val gap: Int = GAP_FRAMES) : Step()
        /** Waits (at most [timeout] frames) for the battle to report [state], then [settle] frames more. */
        class WaitFor(val state: Int, val settle: Int = SETTLE_FRAMES, val timeout: Int = WAIT_FRAMES) : Step()
    }

    private val steps = ArrayDeque<Step>()
    private var frames = 0
    private var phase = 0
    @Volatile private var state = 0
    private val lock = Any()

    /** A pick is still being pressed: the buttons wait. */
    val busy: Boolean get() = synchronized(lock) { steps.isNotEmpty() }

    /** The fast poll's battle state (emu thread). */
    fun onState(state: Int) { this.state = state }

    /** 0 FIGHT (the move panel opens), 1 BAG, 2 POKéMON, 3 RUN. */
    fun selectAction(index: Int) = queue(listOf(Step.Tap(ACTIONS[index].first, ACTIONS[index].second)))

    /** FIGHT and move [index] (0-3: the panel's 2 x 2, left to right then down), or just the move with the panel up. */
    fun selectMove(index: Int) {
        val move = MOVES.getOrNull(index) ?: return
        val pick = Step.Tap(move.first, move.second)
        queue(
            if (state == BATTLE_INPUT_MOVE_SELECT) listOf(pick)
            else listOf(Step.Tap(ACTIONS[0].first, ACTIONS[0].second), Step.WaitFor(BATTLE_INPUT_MOVE_SELECT), pick),
        )
    }

    /** The move panel's CANCEL (back to FIGHT / BAG / RUN / POKéMON). */
    fun back() {
        if (state == BATTLE_INPUT_MOVE_SELECT) queue(listOf(Step.Tap(CANCEL.first, CANCEL.second)))
    }

    fun cancel() = synchronized(lock) {
        if (steps.isNotEmpty()) touch(false, 0, 0)
        steps.clear()
        frames = 0
        phase = 0
    }

    private fun queue(list: List<Step>) = synchronized(lock) {
        // One pick at a time: a second tap while one plays is dropped, as the GBA's controller does.
        if (steps.isNotEmpty()) return
        // A pick is made from the menu the battle shows: none up, nothing to press.
        if (state != BATTLE_INPUT_ACTION_SELECT && state != BATTLE_INPUT_MOVE_SELECT) return
        steps.addAll(list)
        frames = 0
        phase = 0
    }

    /** Emu thread, before each frame. */
    fun tick() = synchronized(lock) {
        val step = steps.peekFirst() ?: return
        when (step) {
            is Step.Tap -> {
                // Down for [frames], up for [gap]: the game reads a press, then its release.
                if (frames < step.frames) touch(true, step.x, step.y)
                else if (frames == step.frames) touch(false, 0, 0)
                if (++frames >= step.frames + step.gap) next()
            }
            is Step.WaitFor -> {
                if (phase == 0) {
                    if (state == step.state) { phase = 1; frames = 0 }
                    else if (++frames >= step.timeout) { steps.clear(); frames = 0; phase = 0 }
                } else if (++frames >= step.settle) next()
            }
        }
    }

    private fun next() {
        steps.pollFirst()
        frames = 0
        phase = 0
    }

    companion object {
        /** FIGHT, BAG, POKéMON, RUN on the battle's command panel (touch-screen pixels). */
        private val ACTIONS = listOf(128 to 90, 40 to 170, 215 to 170, 128 to 170)
        /** The move panel's four cards and its CANCEL bar. */
        private val MOVES = listOf(64 to 42, 192 to 42, 64 to 113, 192 to 113)
        private val CANCEL = 128 to 175

        private const val TAP_FRAMES = 3
        private const val GAP_FRAMES = 2
        /** After FIGHT the move panel slides in; its cards take a touch from the frame it's reported drawn (headless on
         * HeartGold: 7 frames after FIGHT, a tap then or 4 / 12 frames later all used the move). A few frames' margin. */
        private const val SETTLE_FRAMES = 4
        private const val WAIT_FRAMES = 120
    }
}
