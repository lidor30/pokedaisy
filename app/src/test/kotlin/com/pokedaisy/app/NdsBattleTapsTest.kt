package com.pokedaisy.app

import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BUSY
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BATTLE BUTTONS on a DS game: the stylus taps a pick makes, frame by frame. */
class NdsBattleTapsTest {
    /** Each frame's stylus: (x, y) while down, null while up. */
    private val frames = mutableListOf<Pair<Int, Int>?>()
    private val taps = NdsBattleTaps { down, x, y -> frames += if (down) x to y else null }

    private fun run(n: Int) = repeat(n) { taps.tick() }

    /** The places the stylus went down, in order (a press held for several frames counts once). */
    private fun presses(): List<Pair<Int, Int>> = frames.withIndex().filter { (i, f) -> f != null && frames.getOrNull(i - 1) != f }.map { it.value!! }

    @Test
    fun aMoveIsFightThenItsCardOnceThePanelIsUp() {
        taps.onState(BATTLE_INPUT_ACTION_SELECT)
        taps.selectMove(2)
        assertTrue(taps.busy)
        run(20)
        // FIGHT pressed, then nothing until the battle reports the move panel.
        assertEquals(listOf(128 to 90), presses())
        taps.onState(BATTLE_INPUT_MOVE_SELECT)
        run(20)
        assertEquals(listOf(128 to 90, 64 to 113), presses())
        assertFalse(taps.busy)
    }

    @Test
    fun withThePanelUpOnlyTheCard() {
        taps.onState(BATTLE_INPUT_MOVE_SELECT)
        taps.selectMove(1)
        run(10)
        assertEquals(listOf(192 to 42), presses())
    }

    @Test
    fun actionsAndTheirPlaces() {
        taps.onState(BATTLE_INPUT_ACTION_SELECT)
        for (i in 1..3) { taps.selectAction(i); run(10) }
        assertEquals(listOf(40 to 170, 215 to 170, 128 to 170), presses())
    }

    @Test
    fun nothingWhileTheTurnPlaysAndAPanelThatNeverComesGivesUp() {
        taps.onState(BATTLE_INPUT_BUSY)
        taps.selectAction(3)
        assertFalse(taps.busy)
        taps.onState(BATTLE_INPUT_ACTION_SELECT)
        taps.selectMove(0)
        run(200)
        assertEquals(listOf(128 to 90), presses())
        assertFalse(taps.busy)
    }

    @Test
    fun blackAndWhitePressTheirOwnButtons() {
        taps.layout = NdsBattleTaps.layoutFor("IRBO")
        taps.onState(BATTLE_INPUT_ACTION_SELECT)
        for (i in 1..3) { taps.selectAction(i); run(10) }
        taps.onState(BATTLE_INPUT_MOVE_SELECT)
        taps.selectMove(3); run(10)
        taps.back(); run(10)
        assertEquals(listOf(30 to 172, 222 to 172, 128 to 178, 191 to 104, 230 to 175), presses())
        assertEquals(NdsBattleTaps.GEN5, NdsBattleTaps.layoutFor("IRAO"))
        assertEquals(NdsBattleTaps.GEN4, NdsBattleTaps.layoutFor("CPUE"))
    }

    @Test
    fun theCardWaitsForThePanelToStayUp() {
        // Black / White's move panel reports itself for a few frames between its two slides: not long enough to tap.
        taps.onState(BATTLE_INPUT_ACTION_SELECT)
        taps.selectMove(0)
        run(10)
        taps.onState(BATTLE_INPUT_MOVE_SELECT)
        run(3)
        taps.onState(BATTLE_INPUT_BUSY)
        run(6)
        assertEquals(listOf(128 to 90), presses())
        taps.onState(BATTLE_INPUT_MOVE_SELECT)
        run(10)
        assertEquals(listOf(128 to 90, 64 to 42), presses())
    }

    @Test
    fun aSwitchIsPokemonThenThePanelOnceTheScreenTakesTouchesThenShift() {
        taps.onState(BATTLE_INPUT_ACTION_SELECT)
        taps.switchTo(3)
        run(20)
        assertEquals(listOf(215 to 170), presses())
        // The party screen reported up: its panels wait out its fade (60 frames) before the panel, then SHIFT.
        taps.onState(BATTLE_INPUT_PARTY_OPEN)
        run(55)
        assertEquals(listOf(215 to 170), presses())
        run(30)
        assertEquals(listOf(215 to 170, 192 to 71, 128 to 80), presses())
        // The battle went on: no second try.
        taps.onState(BATTLE_INPUT_BUSY)
        run(100)
        assertEquals(3, presses().size)
        assertFalse(taps.busy)
    }

    @Test
    fun aSwitchTheScreenDroppedIsTriedOnceMore() {
        taps.onState(BATTLE_INPUT_PARTY_OPEN)   // the player opened the party screen in the game: just the panel and SHIFT
        taps.switchTo(1)
        run(200)
        assertEquals(listOf(192 to 23, 128 to 80, 192 to 23, 128 to 80), presses())
        assertFalse(taps.busy)
    }

    @Test
    fun blackAndWhiteHaveNoSwitchYet() {
        taps.layout = NdsBattleTaps.layoutFor("IRBO")
        taps.onState(BATTLE_INPUT_ACTION_SELECT)
        taps.switchTo(2)
        assertFalse(taps.busy)
    }
}
