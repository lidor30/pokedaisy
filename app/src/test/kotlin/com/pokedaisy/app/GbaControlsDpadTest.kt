package com.pokedaisy.app

import com.pokedaisy.app.companion.data.CoreKey
import okio.Path
import okio.Path.Companion.toOkioPath
import android.view.KeyEvent
import com.pokedaisy.app.GbaControls.Btn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** GAME BUTTONS' D-pad rows: another key for each direction (the 8BitDo Micro's keyboard mode sends C / D / E / F). */
class GbaControlsDpadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun input(dir: Path, ds: Boolean) = GbaInput().apply { setControls(GbaControls.load(dir)); this.ds = ds }

    @Test fun unboundByDefaultAndTheDpadStillMoves() {
        val dir = tmp.newFolder().toOkioPath()
        val raw = GbaControls.rawBindings(dir)
        for (b in listOf(Btn.UP, Btn.DOWN, Btn.LEFT, Btn.RIGHT)) assertEquals(emptyList<String>(), raw[b])
        val gba = input(dir, ds = false)
        assertEquals(CoreKey.UP, gba.buttonFor(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(gba.buttonFor(KeyEvent.KEYCODE_C))
    }

    @Test fun aLetterBecomesADirectionInEveryGame() {
        val dir = tmp.newFolder().toOkioPath()
        GbaControls.setBindingNamed(dir, Btn.UP, "C")
        GbaControls.setBindingNamed(dir, Btn.DOWN, "D")
        GbaControls.setBindingNamed(dir, Btn.LEFT, "E")
        GbaControls.setBindingNamed(dir, Btn.RIGHT, "F")
        for (ds in listOf(false, true)) {
            val i = input(dir, ds)
            assertEquals(CoreKey.UP, i.buttonFor(KeyEvent.KEYCODE_C))
            assertEquals(CoreKey.DOWN, i.buttonFor(KeyEvent.KEYCODE_D))
            assertEquals(CoreKey.LEFT, i.buttonFor(KeyEvent.KEYCODE_E))
            assertEquals(CoreKey.RIGHT, i.buttonFor(KeyEvent.KEYCODE_F))
            // The D-pad itself is unchanged.
            assertEquals(CoreKey.UP, i.buttonFor(KeyEvent.KEYCODE_DPAD_UP))
        }
    }

    @Test fun theKeyLeavesAHotkeyThatWasItAlone() {
        val dir = tmp.newFolder().toOkioPath()
        // ZOOM is R3 and C by default: C goes to the D-pad, R3 stays ZOOM's.
        assertTrue("C" in Hotkeys.load(dir).rawBindings[Hotkeys.Action.ZOOM].orEmpty())
        GbaControls.setBindingNamed(dir, Btn.UP, "C")
        assertEquals(listOf("BUTTON_THUMBR"), Hotkeys.load(dir).rawBindings[Hotkeys.Action.ZOOM])
        // A chord with the key in it is left alone: SELECT + R1 stays SAVE STATE's.
        GbaControls.setBindingNamed(dir, Btn.DOWN, "BUTTON_R1")
        assertTrue("BUTTON_SELECT+BUTTON_R1" in Hotkeys.load(dir).rawBindings[Hotkeys.Action.SAVE_STATE].orEmpty())
    }

    @Test fun cleared() {
        val dir = tmp.newFolder().toOkioPath()
        GbaControls.setBindingNamed(dir, Btn.UP, "C")
        assertTrue(Btn.UP.canClear)
        GbaControls.clearBinding(dir, Btn.UP)
        assertEquals(emptyList<String>(), GbaControls.rawBindings(dir)[Btn.UP])
    }
}
