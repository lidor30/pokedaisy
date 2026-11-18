package com.pokedaisy.app.game

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.KeyCapture
import com.pokedaisy.app.companion.data.CoreKey
import com.pokedaisy.app.companion.data.KeyCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.FileSystem
import okio.SYSTEM

/** The real native poller, with on-screen canonical keys and the shared file-backed bindings. */
class TouchButtonBindingsTest {
    private fun withInput(block: (GameInput) -> Unit) {
        val file = IosGameInputBindings.directory / "controls.properties"
        val fs = FileSystem.SYSTEM
        val before = if (fs.exists(file)) fs.read(file) { readByteArray() } else null
        val hotkeys = IosGameInputBindings.directory / "hotkeys.properties"
        val beforeHotkeys = if (fs.exists(hotkeys)) fs.read(hotkeys) { readByteArray() } else null
        try {
            val input = GameInput()
            GbaControls.clearBinding(IosGameInputBindings.directory, GbaControls.Btn.START_2)
            GbaControls.clearBinding(IosGameInputBindings.directory, GbaControls.Btn.TO_COMPANION)
            GbaControls.setBindingNamed(IosGameInputBindings.directory, GbaControls.Btn.A, "BUTTON_X")
            input.reloadBindings()
            block(input)
        } finally {
            if (before == null) fs.delete(file, mustExist = false) else fs.write(file) { write(before) }
            if (beforeHotkeys == null) fs.delete(hotkeys, mustExist = false) else fs.write(hotkeys) { write(beforeHotkeys) }
        }
    }

    @Test fun touchXUsesThePlayersMappingAndReleases() = withInput { input ->
        input.touchButtonKeys = setOf(KeyCodes.BUTTON_X); input.poll()
        assertEquals(CoreKey.A, input.keys())
        input.touchButtonKeys = emptySet(); input.poll()
        assertEquals(0, input.keys())
    }

    @Test fun hotkeysTakeTheTouchKeyAndOwnershipRequiresANewDown() = withInput { input ->
        Hotkeys.setBindingNamed(IosGameInputBindings.directory, Hotkeys.Action.FF_HOLD, listOf("BUTTON_X"))
        input.setHotkeys(Hotkeys.load(IosGameInputBindings.directory).forActions(listOf(Hotkeys.Action.FF_HOLD)))
        val events = mutableListOf<Boolean>(); input.onHotkey = { events += it.pressed }
        input.touchButtonKeys = setOf(KeyCodes.BUTTON_X); input.poll()
        assertEquals(listOf(true), events); assertEquals(0, input.keys())
        input.companionCaptured = true
        input.companionCaptured = false; input.poll()
        assertEquals(listOf(true, false), events)
        input.touchButtonKeys = emptySet(); input.poll()
        input.touchButtonKeys = setOf(KeyCodes.BUTTON_X); input.poll()
        assertEquals(listOf(true, false, true), events)
    }

    @Test fun aStateCallbackReleasesTouchBeforeTheNextCoreFrame() = withInput { input ->
        Hotkeys.setBindingNamed(IosGameInputBindings.directory, Hotkeys.Action.SAVE_STATE, listOf("BUTTON_Y"))
        input.setHotkeys(Hotkeys.load(IosGameInputBindings.directory).forActions(listOf(Hotkeys.Action.SAVE_STATE)))
        var saves = 0
        input.onHotkey = { if (it.pressed) { saves++; input.touchButtonKeys = emptySet() } }
        input.touchButtonKeys = setOf(KeyCodes.BUTTON_X, KeyCodes.BUTTON_Y); input.poll()
        assertEquals(1, saves); assertEquals(0, input.keys())
    }

    @Test fun bindingCaptureTakesTouchXWithoutPlayingIt() = withInput { input ->
        var captured = emptyList<Int>()
        input.capture.start(KeyCapture.Request("GBA A", false, { captured = it }))
        input.touchButtonKeys = setOf(KeyCodes.BUTTON_X); input.poll()
        input.touchButtonKeys = emptySet(); input.poll()
        assertEquals(listOf(KeyCodes.BUTTON_X), captured); assertEquals(0, input.keys())
    }
}
