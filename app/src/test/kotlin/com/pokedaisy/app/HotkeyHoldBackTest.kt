package com.pokedaisy.app

import com.pokedaisy.app.companion.data.CoreKey
import okio.Path.Companion.toOkioPath
import android.view.KeyEvent
import com.pokedaisy.app.Hotkeys.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SELECT / START of a chord wait for the release before the game gets them, so SELECT + START (EXIT GAME) never
 *  presses SELECT in the game first; alone they're a tap, with other buttons they go through at once. */
class HotkeyHoldBackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val select = KeyEvent.KEYCODE_BUTTON_SELECT
    private val start = KeyEvent.KEYCODE_BUTTON_START
    private val a = KeyEvent.KEYCODE_BUTTON_A
    private val r1 = KeyEvent.KEYCODE_BUTTON_R1

    private val input = GbaInput().apply {
        setControls(mapOf(select to CoreKey.SELECT, start to CoreKey.START, a to CoreKey.A, r1 to CoreKey.R))
    }
    private val fired = ArrayList<Action>()

    /** The defaults: EXIT GAME = SELECT + START, SAVE STATE = SELECT + R1. */
    private val hotkeys by lazy { Hotkeys.load(tmp.newFolder().toOkioPath()) }

    /** What dispatchKeyEvent does with a key: the hotkeys first, then the game. */
    private fun key(code: Int, down: Boolean) {
        if (!hotkeys.gameKey(code, down, input) { if (it.pressed) fired.add(it.action) }) input.onKey(code, down)
    }

    /** The next [n] game frames' buttons, as the engine sets them. */
    private fun frames(n: Int) = List(n) { input.mask or input.tapMask() }

    @Test fun selectHeldForAChordNeverReachesTheGame() {
        key(select, true)
        assertEquals(0, input.mask)
        key(start, true)
        assertEquals(listOf(Action.EXIT_GAME), fired)
        key(start, false)
        key(select, false)
        assertTrue(frames(GbaInput.TAP_FRAMES + 2).all { it == 0 })
    }

    @Test fun selectAloneIsATapOnRelease() {
        key(select, true)
        assertEquals(0, frames(1).single())
        key(select, false)
        val f = frames(GbaInput.TAP_FRAMES + 1)
        assertTrue(f.take(GbaInput.TAP_FRAMES).all { it == CoreKey.SELECT })
        assertEquals(0, f.last())
        assertTrue(fired.isEmpty())
    }

    @Test fun anotherGameButtonLetsItThroughHeld() {
        key(select, true)
        key(a, true)
        assertEquals(CoreKey.SELECT or CoreKey.A, input.mask)
        key(select, false)
        assertEquals(CoreKey.A, input.mask)
        assertEquals(0, input.tapMask())
    }

    @Test fun theGamesSoftResetStillWorks() {
        // A and B held first: SELECT and START go straight through (EXIT GAME still fires on its chord, as before).
        key(a, true)
        key(select, true)
        assertEquals(CoreKey.A or CoreKey.SELECT, input.mask)
    }

    @Test fun startIsHeldBackToo() {
        key(start, true)
        assertEquals(0, input.mask)
        key(start, false)
        assertEquals(CoreKey.START, input.tapMask())
    }

    @Test fun selectR1SavesWithoutSelect() {
        key(select, true)
        key(r1, true)
        assertEquals(listOf(Action.SAVE_STATE), fired)
        assertEquals(0, input.mask)
        key(r1, false)
        key(select, false)
        assertEquals(0, input.tapMask())
    }

    @Test fun repeatsOfAHeldBackKeyStayAway() {
        key(select, true)
        key(select, true)   // the key's auto-repeat
        assertEquals(0, input.mask)
        key(select, false)
        assertEquals(CoreKey.SELECT, input.tapMask())
    }

    @Test fun aKeyInNoChordIsUntouched() {
        key(a, true)
        assertEquals(CoreKey.A, input.mask)
        assertFalse(hotkeys.gameKey(a, false, input) {})
    }
}
