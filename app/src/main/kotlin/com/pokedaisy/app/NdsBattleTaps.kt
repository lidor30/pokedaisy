package com.pokedaisy.app

import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import java.util.ArrayDeque

/**
 * BATTLE BUTTONS on a DS game (HeartGold / SoulSilver, Platinum, Black / White): a pick is the game's own touch-screen
 * button pressed with the stylus - FIGHT, then the move's card once the move panel has slid in; BAG / POKéMON / RUN where
 * the battle draws them ([Layout]: each game's panels, their middles). Steps run on the emu thread, one frame each
 * ([tick], before the frame), so the stylus stays down for whole frames at any speed. The battle's state ([onState]: the
 * fast poll's BATTLE_INPUT_*) says when the move panel is up.
 */
class NdsBattleTaps(private val touch: (down: Boolean, x: Int, y: Int) -> Unit = MgbaCore::pkNdsTouch) {
    /** Where a game's battle panels have their buttons (touch-screen pixels). */
    class Layout(
        /** FIGHT, BAG, POKéMON, RUN on the command panel. */
        val actions: List<Pair<Int, Int>>,
        /** The move panel's four cards (left to right, then down) and its way back. */
        val moves: List<Pair<Int, Int>>,
        val cancel: Pair<Int, Int>,
        /** The party screen's six panels (left to right, then down) and the SHIFT a panel opens; null = no switch here. */
        val partySlots: List<Pair<Int, Int>>? = null,
        val shift: Pair<Int, Int>? = null,
    )

    /** The running game's panels ([layoutFor]); set before its battles. */
    @Volatile var layout: Layout = GEN4
    private sealed class Step {
        /** The stylus at ([x], [y]) for [frames] frames, then lifted for [gap]. */
        class Tap(val x: Int, val y: Int, val frames: Int = TAP_FRAMES, val gap: Int = GAP_FRAMES) : Step()
        /** Waits (at most [timeout] frames) for the battle to report [state] for [settle] frames in a row (Black / White's
         * move panel says so for 3 frames between its two slides, which take no touch). */
        class WaitFor(val state: Int, val settle: Int = SETTLE_FRAMES, val timeout: Int = WAIT_FRAMES) : Step()
        /** Nothing for [frames] frames. */
        class Wait(val frames: Int) : Step()
        /** If the battle still reports [state], these steps next (a switch's taps once more); else nothing. */
        class IfStill(val state: Int, val then: List<Step>) : Step()
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
    fun selectAction(index: Int) = layout.actions.getOrNull(index)?.let { queue(listOf(Step.Tap(it.first, it.second))) }

    /** FIGHT and move [index] (0-3: the panel's 2 x 2, left to right then down), or just the move with the panel up. */
    fun selectMove(index: Int) {
        val l = layout
        val move = l.moves.getOrNull(index) ?: return
        val pick = Step.Tap(move.first, move.second)
        queue(
            if (state == BATTLE_INPUT_MOVE_SELECT) listOf(pick)
            else listOf(Step.Tap(l.actions[0].first, l.actions[0].second), Step.WaitFor(BATTLE_INPUT_MOVE_SELECT), pick),
        )
    }

    /**
     * POKéMON, then the party screen's panel at [place] (0 top left, 1 top right, then down: the battle's own order) and the SHIFT
     * it opens - or, with the party screen already up (after a faint), just the panel and SHIFT. The screen fades in for about
     * 50 frames after it's reported up and drops touches meanwhile (headless on HeartGold: a panel 45 frames after POKéMON did
     * nothing, 60 did), so the panel waits [PARTY_SETTLE]; if the screen is still up a second after SHIFT, the panel and SHIFT
     * once more.
     */
    fun switchTo(place: Int) {
        val l = layout
        val slot = l.partySlots?.getOrNull(place) ?: return
        val shift = l.shift ?: return
        val pick = listOf(Step.Tap(slot.first, slot.second), Step.Wait(SHIFT_FRAMES), Step.Tap(shift.first, shift.second))
        val again = listOf(Step.Wait(SWITCH_CHECK_FRAMES), Step.IfStill(BATTLE_INPUT_PARTY_OPEN, pick))
        val open = if (state == BATTLE_INPUT_PARTY_OPEN) emptyList()
            else listOf(Step.Tap(l.actions[2].first, l.actions[2].second))
        queue(open + Step.WaitFor(BATTLE_INPUT_PARTY_OPEN, settle = PARTY_SETTLE) + pick + again, party = true)
    }

    /** The move panel's CANCEL (back to FIGHT / BAG / RUN / POKéMON). */
    fun back() {
        if (state == BATTLE_INPUT_MOVE_SELECT) layout.cancel.let { queue(listOf(Step.Tap(it.first, it.second))) }
    }

    fun cancel() = synchronized(lock) {
        if (steps.isNotEmpty()) touch(false, 0, 0)
        steps.clear()
        frames = 0
        phase = 0
    }

    private fun queue(list: List<Step>, party: Boolean = false) = synchronized(lock) {
        // One pick at a time: a second tap while one plays is dropped, as the GBA's controller does.
        if (steps.isNotEmpty()) return
        // A pick is made from the menu the battle shows: none up, nothing to press (a switch also from the party screen).
        if (state != BATTLE_INPUT_ACTION_SELECT && state != BATTLE_INPUT_MOVE_SELECT && !(party && state == BATTLE_INPUT_PARTY_OPEN)) return
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
                } else if (state != step.state) { phase = 0; frames = 0 }
                else if (++frames >= step.settle) next()
            }
            is Step.Wait -> if (++frames >= step.frames) next()
            is Step.IfStill -> {
                next()
                if (state == step.state) step.then.asReversed().forEach { steps.addFirst(it) }
            }
        }
    }

    private fun next() {
        steps.pollFirst()
        frames = 0
        phase = 0
    }

    companion object {
        /** HeartGold / SoulSilver's panels (battle_input.c's, sampled headless) - Platinum's too: its battle_subscreen.c's
         * touch rects are the same (FIGHT 0x18-0x90 the whole width, BAG / POKéMON 0x90-0xC0 at the sides, RUN 0x98-0xC0
         * between; the cards 0x18-0x50 / 0x58-0x90, CANCEL 0x98-0xC0). */
        val GEN4 = Layout(
            actions = listOf(128 to 90, 40 to 170, 215 to 170, 128 to 170),
            moves = listOf(64 to 42, 192 to 42, 64 to 113, 192 to 113),
            cancel = 128 to 175,
            // The party screen's panels (two columns of three) and the SHIFT plate a panel opens (headless on HeartGold).
            partySlots = listOf(64 to 23, 192 to 23, 64 to 71, 192 to 71, 64 to 119, 192 to 119),
            shift = 128 to 80,
        )
        /** Black / White's and their sequels' (headless in White and Black 2: FIGHT the big plate up top, BAG / RUN /
         * POKéMON along the bottom, the cards 2 x 2 from y 35, the return arrow bottom right). */
        val GEN5 = Layout(
            actions = listOf(128 to 80, 30 to 172, 222 to 172, 128 to 178),
            moves = listOf(63 to 56, 191 to 56, 63 to 104, 191 to 104),
            cancel = 230 to 175,
        )

        /** The panels of the DS game with this header code. */
        fun layoutFor(gameCode: String) = if (gameCode.startsWith("IR")) GEN5 else GEN4

        private const val TAP_FRAMES = 3
        private const val GAP_FRAMES = 2
        /** After FIGHT the move panel slides in; its cards take a touch from the frame it's reported drawn (headless on
         * HeartGold: 7 frames after FIGHT, a tap then or 4 / 12 frames later all used the move). A few frames' margin. */
        private const val SETTLE_FRAMES = 4
        private const val WAIT_FRAMES = 120
        /** The party screen's fade (above): frames reported up before its panels take a touch, with a margin. */
        private const val PARTY_SETTLE = 60
        /** A panel's page (SHIFT) takes a touch 10 frames after the panel's (headless); a little more. */
        private const val SHIFT_FRAMES = 12
        private const val SWITCH_CHECK_FRAMES = 60
    }
}
