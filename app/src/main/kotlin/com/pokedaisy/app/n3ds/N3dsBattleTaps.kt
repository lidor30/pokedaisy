package com.pokedaisy.app.n3ds

import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT

/**
 * The battle strip's picks on a 3DS game: the game's own touch-screen buttons pressed (DS games' NdsBattleTaps, in time
 * rather than frames: Azahar runs on its own thread). FIGHT, then the move's card once the move list is up; BAG /
 * POKéMON / RUN where Pokemon X draws them. [state] is the battle's (BATTLE_INPUT_*, the session's battle reader), [touch]
 * presses the touch screen at a point of its 320 x 240 (or lets go: down false).
 */
class N3dsBattleTaps(
    private val state: () -> Int,
    private val touch: (down: Boolean, x: Int, y: Int) -> Unit,
    private val layout: Layout = XY,
) {
    /** Where Pokemon X's battle panels have their buttons (touch-screen pixels, read off its screens on the RP6). */
    class Layout(val actions: List<Pair<Int, Int>>, val moves: List<Pair<Int, Int>>, val cancel: Pair<Int, Int>)

    @Volatile private var worker: Thread? = null

    /** A pick is still being pressed: the strip waits. */
    val busy: Boolean get() = worker?.isAlive == true

    /** 0 FIGHT (the move list opens), 1 BAG, 2 POKéMON, 3 RUN. */
    fun selectAction(index: Int) {
        val at = layout.actions.getOrNull(index) ?: return
        run { tapUntilLeft(at, BATTLE_INPUT_ACTION_SELECT) }
    }

    /** FIGHT and move [index] (0-3: the list's 2 x 2, left to right then down), or just the move with the list up. */
    fun selectMove(index: Int) {
        val move = layout.moves.getOrNull(index) ?: return
        run {
            if (state() != BATTLE_INPUT_MOVE_SELECT) {
                tapUntilLeft(layout.actions[0], BATTLE_INPUT_ACTION_SELECT)
                if (!waitFor(BATTLE_INPUT_MOVE_SELECT)) return@run
            }
            tapUntilLeft(move, BATTLE_INPUT_MOVE_SELECT)
        }
    }

    /** The move list's way back. */
    fun back() {
        if (state() == BATTLE_INPUT_MOVE_SELECT) run { tapUntilLeft(layout.cancel, BATTLE_INPUT_MOVE_SELECT) }
    }

    private fun run(steps: () -> Unit) {
        // One pick at a time, and only from a menu the battle shows.
        if (busy) return
        val s = state()
        if (s != BATTLE_INPUT_ACTION_SELECT && s != BATTLE_INPUT_MOVE_SELECT) return
        worker = Thread({ runCatching { steps() }.onFailure { touch(false, 0, 0) } }, "pokedaisy-n3ds-battle").also { it.start() }
    }

    private fun tap(at: Pair<Int, Int>) {
        touch(true, at.first, at.second)
        Thread.sleep(TAP_MS)
        touch(false, 0, 0)
        Thread.sleep(GAP_MS)
    }

    /**
     * [at], tapped again (at most [RETRIES] times) while the battle still shows [from] a while later: a menu still sliding in
     * drops a tap (Sun / Moon headless, 2026-11-17: FIGHT, RUN and the moves' return arrow tapped as their menu appeared
     * did nothing, the same taps a moment later worked), and the corner's buttons show as soon as the menu does.
     */
    private fun tapUntilLeft(at: Pair<Int, Int>, from: Int) {
        repeat(RETRIES + 1) { i ->
            tap(at)
            val until = System.currentTimeMillis() + RETRY_MS
            while (System.currentTimeMillis() < until) {
                if (state() != from) return
                Thread.sleep(POLL_MS)
            }
            if (i == RETRIES) return
        }
    }

    /** Waits for the battle to show [want] (the move list slides in after FIGHT), then a moment for it to settle. */
    private fun waitFor(want: Int): Boolean {
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            if (state() == want) { Thread.sleep(SETTLE_MS); return true }
            Thread.sleep(POLL_MS)
        }
        return false
    }

    companion object {
        /** Pokemon X's command panel (FIGHT the big oval, BAG / RUN / POKéMON along the bottom), its move list's cards
         * 2 x 2 and the return arrow at the bottom right (from its touch screen at 320 x 240, 2026-11-15). */
        val XY = Layout(
            actions = listOf(160 to 100, 42 to 212, 282 to 212, 160 to 220),
            moves = listOf(76 to 67, 241 to 67, 76 to 129, 241 to 129),
            cancel = 294 to 217,
        )
        /** Omega Ruby / Alpha Sapphire: X's places (Alpha Sapphire's command menu and move list, headless, 2026-11-17:
         * every button covers X's point). */
        val ORAS = XY
        /** Sun / Moon: FIGHT the big plate on the right, POKéMON / BAG down the left, RUN at the bottom; the moves a
         * column of four on the right, the return arrow at the bottom left (Sun's touch screen, headless, 2026-11-17:
         * FIGHT and the first move tapped there used it). */
        val SM = Layout(
            actions = listOf(280 to 115, 35 to 170, 35 to 80, 160 to 222),
            moves = listOf(240 to 50, 240 to 100, 240 to 150, 240 to 200),
            cancel = 15 to 222,
        )

        fun forTitle(titleId: Long): Layout = when (titleId) {
            N3dsCart.POKEMON_SUN_USA, N3dsCart.POKEMON_MOON_USA -> SM
            N3dsCart.POKEMON_OR_USA, N3dsCart.POKEMON_AS_USA -> ORAS
            else -> XY
        }

        /** The stylus down this long: several of the game's frames. */
        private const val TAP_MS = 110L
        private const val GAP_MS = 60L
        private const val WAIT_MS = 3000L
        private const val POLL_MS = 30L
        /** A tap the battle hasn't answered this long is tapped again, at most this many times. */
        private const val RETRY_MS = 700L
        private const val RETRIES = 2
        /** The move list's cards take a touch once it has slid in. */
        private const val SETTLE_MS = 250L
    }
}
