package com.pokedaisy.app

import com.pokedaisy.app.companion.data.CoreKey
import okio.Path
import okio.Path.Companion.toOkioPath
import android.view.KeyEvent
import com.pokedaisy.app.GbaControls.Btn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** GAME BUTTONS' rows per game: X / Y are the DS games', START 2 / SELECT 2 / TO COMPANION the GBA / Game Boy games',
 *  so one key can be both, and the player can pull them apart. */
class GbaControlsSplitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val x = KeyEvent.KEYCODE_BUTTON_X
    private val y = KeyEvent.KEYCODE_BUTTON_Y
    private val r3 = KeyEvent.KEYCODE_BUTTON_THUMBR

    private fun input(dir: Path, ds: Boolean) = GbaInput().apply { setControls(GbaControls.load(dir)); this.ds = ds }

    @Test fun theDefaultsKeepXAndYAsTheyWere() {
        val dir = tmp.newFolder().toOkioPath()
        val gba = input(dir, ds = false)
        assertEquals(CoreKey.START, gba.buttonFor(x))
        assertEquals(CoreKey.SELECT, gba.buttonFor(y))
        assertTrue(gba.isCompanion(x))
        assertFalse(gba.isCompanion(y))
        val ds = input(dir, ds = true)
        assertEquals(CoreKey.X, ds.buttonFor(x))
        assertEquals(CoreKey.Y, ds.buttonFor(y))
        assertFalse(ds.isCompanion(x))
    }

    @Test fun aFileFromBeforeTheSplitGivesEachRowItsOldKeys() {
        val dir = tmp.newFolder().toOkioPath()
        // X / Y moved to L3 / R3 when they were one row each.
        File(dir.toFile(), "controls.properties").writeText("start2=BUTTON_THUMBL\nselect2=BUTTON_THUMBR\n")
        val raw = GbaControls.rawBindings(dir)
        assertEquals(listOf("BUTTON_THUMBL"), raw[Btn.X])
        assertEquals(listOf("BUTTON_THUMBR"), raw[Btn.Y])
        assertEquals(listOf("BUTTON_THUMBL"), raw[Btn.START_2])
        assertEquals(listOf("BUTTON_THUMBR"), raw[Btn.SELECT_2])
        assertEquals(listOf("BUTTON_THUMBL"), raw[Btn.TO_COMPANION])
    }

    @Test fun startTwoMovesWithoutTakingTheDsX() {
        val dir = tmp.newFolder().toOkioPath()
        GbaControls.setBindingNamed(dir, Btn.START_2, "BUTTON_THUMBR")
        val raw = GbaControls.rawBindings(dir)
        assertEquals(listOf("BUTTON_X"), raw[Btn.X])
        assertEquals(listOf("BUTTON_X"), raw[Btn.TO_COMPANION])
        // X on a GBA game: only the companion now, nothing in the game.
        val gba = input(dir, ds = false)
        assertEquals(0, gba.buttonFor(x))
        assertTrue(gba.isCompanion(x))
        assertTrue(gba.onKey(x, true))
        assertEquals(0, gba.mask)
        assertEquals(CoreKey.START, gba.buttonFor(r3))
        assertEquals(null, input(dir, ds = true).buttonFor(r3))
    }

    @Test fun toCompanionCanBeClearedLeavingStartTwo() {
        val dir = tmp.newFolder().toOkioPath()
        GbaControls.clearBinding(dir, Btn.TO_COMPANION)
        val gba = input(dir, ds = false)
        assertFalse(gba.isCompanion(x))
        assertEquals(CoreKey.START, gba.buttonFor(x))
    }

    @Test fun aSharedButtonTakesTheKeyFromBothGroupsButNotFromToCompanion() {
        val dir = tmp.newFolder().toOkioPath()
        GbaControls.setBindingNamed(dir, Btn.B, "BUTTON_X")
        val raw = GbaControls.rawBindings(dir)
        assertEquals(emptyList<String>(), raw[Btn.X])
        assertEquals(emptyList<String>(), raw[Btn.START_2])
        assertEquals(listOf("BUTTON_X"), raw[Btn.TO_COMPANION])
        val gba = input(dir, ds = false)
        assertEquals(CoreKey.B, gba.buttonFor(x))
        assertTrue(gba.isCompanion(x))
        assertEquals(CoreKey.B, input(dir, ds = true).buttonFor(x))
    }

    @Test fun theCompanionMarkNeverReachesTheGame() {
        val dir = tmp.newFolder().toOkioPath()
        val gba = input(dir, ds = false)
        gba.onKey(x, true)
        assertEquals(CoreKey.START, gba.mask)
    }
}
