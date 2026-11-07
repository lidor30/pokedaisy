package com.pokedaisy.app

import android.view.KeyEvent
import com.pokedaisy.app.Hotkeys.Action
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** L2 / R2: TOUCH SCREEN / DS LAYOUT in a DS game on one screen, SPEED CYCLE / FF HOLD everywhere else. */
class HotkeyDsTriggersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val l2 = KeyEvent.KEYCODE_BUTTON_L2
    private val r2 = KeyEvent.KEYCODE_BUTTON_R2

    private val hotkeys by lazy { Hotkeys.load(tmp.newFolder()) }

    private fun press(code: Int) = hotkeys.onKey(code, true)
    private fun release(code: Int) = hotkeys.onKey(code, false)

    @After fun reset() { Hotkeys.dsActive = { false } }

    @Test
    fun outsideADsGameTheTriggersStaySpeedAndFf() {
        assertEquals(listOf(Hotkeys.Event(Action.SPEED_CYCLE, true)), press(l2))
        release(l2)
        assertEquals(listOf(Hotkeys.Event(Action.FF_HOLD, true)), press(r2))
        assertEquals(listOf(Hotkeys.Event(Action.FF_HOLD, false)), release(r2))
    }

    @Test
    fun inADsGameTheyOpenTheTouchScreenAndChangeTheLayout() {
        Hotkeys.dsActive = { true }
        assertEquals(listOf(Hotkeys.Event(Action.TOUCH_SCREEN, true)), press(l2))
        release(l2)
        assertEquals(listOf(Hotkeys.Event(Action.DS_LAYOUT, true)), press(r2))
    }

    @Test
    fun aLayoutWithNoFloatingScreenLeavesL2ToSpeed() {
        Hotkeys.dsActive = { it == Action.DS_LAYOUT }
        assertEquals(listOf(Hotkeys.Event(Action.SPEED_CYCLE, true)), press(l2))
    }

    @Test
    fun anFfHoldLetGoAfterTheScreenChangedStillEnds() {
        assertEquals(listOf(Hotkeys.Event(Action.FF_HOLD, true)), press(r2))
        Hotkeys.dsActive = { true }
        assertTrue(Hotkeys.Event(Action.FF_HOLD, false) in release(r2))
    }

    @Test
    fun theyShareTheTriggersWithoutAClash() {
        val raw = hotkeys.rawBindings
        assertTrue(Hotkeys.clashes(raw, Action.DS_LAYOUT, listOf("BUTTON_R2")).isEmpty())
        assertEquals(listOf(Action.FF_HOLD), Hotkeys.clashes(raw, Action.SAVE_STATE, listOf("BUTTON_R2")))
    }
}
