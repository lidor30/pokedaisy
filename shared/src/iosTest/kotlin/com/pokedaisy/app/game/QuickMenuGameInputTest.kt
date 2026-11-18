package com.pokedaisy.app.game

import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.companion.data.CoreKey
import com.pokedaisy.app.companion.data.KeyCodes
import okio.FileSystem
import okio.SYSTEM
import platform.GameController.*
import kotlin.random.Random
import kotlin.test.*

/** Actual GameController snapshot objects through the native polling adapter, without physical devices. */
class QuickMenuGameInputTest {
    @Test fun reboundAClosesBeforeSelectingAndHeldStickMustCentreBeforePlaying() {
        val pad = assertNotNull(GCController.controllerWithExtendedGamepad().extendedGamepad)
        val input = GameInput({ listOf(pad) }, { GbaControls.KeyMaps(mapOf(KeyCodes.BUTTON_A to CoreKey.A), emptyMap()) })
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "quick-menu-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(dir)
        try {
            Hotkeys.setBindingNamed(dir, Hotkeys.Action.QUICK_MENU, listOf("BUTTON_A"))
            input.setHotkeys(Hotkeys.load(dir).forActions(listOf(Hotkeys.Action.QUICK_MENU)))
            var open = false
            var toggles = 0
            val menuButtons = mutableListOf<Int>()
            input.onCompanionButtons = { menuButtons += it }
            input.onHotkey = { if (it.action == Hotkeys.Action.QUICK_MENU && it.pressed) {
                open = !open; toggles++
                input.quickMenuCaptured = open; input.companionCaptured = open
            } }
            pad.buttonA.setValue(1f); input.poll()
            assertTrue(open); assertEquals(1, toggles); assertEquals(0, input.keys())
            pad.buttonA.setValue(0f); input.poll()
            pad.leftThumbstick.setValueForXAxis(.8f, yAxis = 0f); input.poll()
            menuButtons.clear()
            pad.buttonA.setValue(1f); input.poll()
            assertFalse(open); assertEquals(2, toggles)
            assertTrue(menuButtons.isEmpty(), "The close shortcut also selected a tile")
            assertEquals(0, input.keys())
            pad.buttonA.setValue(0f)
            pad.leftThumbstick.setValueForXAxis(0f, yAxis = 1f); input.poll()
            assertEquals(0, input.keys(), "Turning a held stick leaked into gameplay")
            pad.leftThumbstick.setValueForXAxis(.4f, yAxis = .4f); input.poll()
            pad.leftThumbstick.setValueForXAxis(.8f, yAxis = 0f); input.poll()
            assertEquals(0, input.keys(), "A non-centred diagonal released the axis gate")
            pad.leftThumbstick.setValueForXAxis(0f, yAxis = 0f); input.poll()
            pad.leftThumbstick.setValueForXAxis(0f, yAxis = 1f); input.poll()
            assertEquals(CoreKey.UP, input.keys())
        } finally { FileSystem.SYSTEM.deleteRecursively(dir) }
    }

    @Test fun ringAimingUsesTheActiveControllerAndLeavesTheDpadIndependent() {
        val first = assertNotNull(GCController.controllerWithExtendedGamepad().extendedGamepad)
        val second = assertNotNull(GCController.controllerWithExtendedGamepad().extendedGamepad)
        val input = GameInput({ listOf(first, second) }, { GbaControls.KeyMaps(emptyMap(), emptyMap()) })
        input.quickMenuCaptured = true; input.companionCaptured = true
        var aim = 0f to 0f; var menuButtons = 0
        input.onCompanionAim = { x, y -> aim = x to y; true }
        input.onCompanionButtons = { menuButtons = it }
        second.leftThumbstick.setValueForXAxis(0f, yAxis = -1f)
        second.dpad.setValueForXAxis(-1f, yAxis = 0f)
        input.poll()
        assertEquals(0f to 1f, aim)
        assertEquals(CoreKey.LEFT, menuButtons)
        assertEquals(0, input.keys())
        input.editorCaptured(true)
        aim = 0f to 0f; menuButtons = 0
        input.poll()
        assertEquals(0f to 0f, aim); assertEquals(0, menuButtons)
    }
}
