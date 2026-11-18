package com.pokedaisy.app

import com.pokedaisy.app.companion.data.CoreKey
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StartSelect2Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun hotkeysFile(dir: Path, save: String, load: String) = File(dir.toFile(), "hotkeys.properties").writeText(
        "save_state=$save\nload_state=$load\n",
    )

    @Test
    fun untouchedStateHotkeysGiveUpXAndY() {
        val dir = tmp.newFolder().toOkioPath()
        hotkeysFile(dir, "BUTTON_SELECT+BUTTON_R1, BUTTON_Y, F1", "BUTTON_SELECT+BUTTON_L1, BUTTON_X, F2")
        val raw = Hotkeys.load(dir).rawBindings
        assertEquals(listOf("BUTTON_SELECT+BUTTON_R1", "F1"), raw[Hotkeys.Action.SAVE_STATE])
        assertEquals(listOf("BUTTON_SELECT+BUTTON_L1", "F2"), raw[Hotkeys.Action.LOAD_STATE])
    }

    @Test
    fun customStateHotkeysAreKept() {
        val dir = tmp.newFolder().toOkioPath()
        hotkeysFile(dir, "BUTTON_Y", "BUTTON_X")
        val raw = Hotkeys.load(dir).rawBindings
        assertEquals(listOf("BUTTON_Y"), raw[Hotkeys.Action.SAVE_STATE])
        assertEquals(listOf("BUTTON_X"), raw[Hotkeys.Action.LOAD_STATE])
    }

    @Test
    fun releasingOneOfTwoStartKeysKeepsStartHeld() {
        val start = 108   // KEYCODE_BUTTON_START
        val x = 99        // KEYCODE_BUTTON_X
        val input = GbaInput().apply { setControls(mapOf(start to CoreKey.START, x to CoreKey.START)) }
        input.onKey(start, true)
        input.onKey(x, true)
        input.onKey(x, false)
        assertEquals(CoreKey.START, input.mask and CoreKey.START)
        input.onKey(start, false)
        assertEquals(0, input.mask)
    }
}
