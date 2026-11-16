package com.pokedaisy.app.n3ds

import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
import org.junit.Assert.assertEquals
import org.junit.Test

/** The battle corner's picks against a simulated battle whose menu drops the first tap while it slides in (as Sun / Moon's
 * did headless): each pick is tapped again until the battle answers. */
class N3dsBattleTapsTest {
    /** A battle on its command menu: a tap on FIGHT opens the moves, one on a move starts the turn, but [drop] taps on
     * each menu are lost first. */
    private class Battle(val layout: N3dsBattleTaps.Layout, var drop: Int) {
        @Volatile var state = BATTLE_INPUT_ACTION_SELECT
        val taps = ArrayList<Pair<Int, Int>>()
        private var dropped = 0
        fun touch(down: Boolean, x: Int, y: Int) {
            if (!down) return
            synchronized(taps) { taps += x to y }
            if (dropped < drop) { dropped++; return }
            dropped = 0
            state = when {
                state == BATTLE_INPUT_ACTION_SELECT && (x to y) == layout.actions[0] -> BATTLE_INPUT_MOVE_SELECT
                state == BATTLE_INPUT_ACTION_SELECT -> BATTLE_INPUT_NONE
                state == BATTLE_INPUT_MOVE_SELECT && (x to y) == layout.cancel -> BATTLE_INPUT_ACTION_SELECT
                state == BATTLE_INPUT_MOVE_SELECT && (x to y) in layout.moves -> BATTLE_INPUT_NONE
                else -> state
            }
        }
    }

    private fun play(drop: Int, pick: (N3dsBattleTaps) -> Unit): Battle {
        val b = Battle(N3dsBattleTaps.SM, drop)
        val taps = N3dsBattleTaps({ b.state }, b::touch, N3dsBattleTaps.SM)
        pick(taps)
        Thread.sleep(50)
        while (taps.busy) Thread.sleep(20)
        return b
    }

    @Test fun aMoveWhoseTapsWereDroppedIsTappedAgain() {
        val b = play(1) { it.selectMove(2) }
        assertEquals(BATTLE_INPUT_NONE, b.state)
        assertEquals(listOf(N3dsBattleTaps.SM.actions[0], N3dsBattleTaps.SM.actions[0], N3dsBattleTaps.SM.moves[2], N3dsBattleTaps.SM.moves[2]), b.taps)
    }

    @Test fun aTakenTapIsntRepeated() {
        val b = play(0) { it.selectAction(3) }
        assertEquals(BATTLE_INPUT_NONE, b.state)
        assertEquals(listOf(N3dsBattleTaps.SM.actions[3]), b.taps)
    }

    @Test fun aBattleThatNeverAnswersGetsThreeTaps() {
        val b = play(99) { it.selectAction(3) }
        assertEquals(BATTLE_INPUT_ACTION_SELECT, b.state)
        assertEquals(3, b.taps.size)
    }
}
