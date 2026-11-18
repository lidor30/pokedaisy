package com.pokedaisy.app.companion

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.data.readText
import kotlin.random.Random
import kotlin.test.*
import okio.FileSystem
import okio.Path
import okio.SYSTEM

class GameBindingsTest {
    private fun withDirectory(test: (Path) -> Unit) {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-bindings-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(directory)
        try {
            GbaControls.load(directory); Hotkeys.load(directory)
            test(directory)
        } finally { FileSystem.SYSTEM.deleteRecursively(directory) }
    }

    @Test fun successfulEditsArePersistedBeforeReloadAndClearKeepsOtherActions() = withDirectory { directory ->
        var reloads = 0
        val bindings = GameBindings(directory, GameActions.BASIC_ACTIONS) {
            reloads++
            if (reloads == 1) assertEquals(listOf("F5"), Hotkeys.load(directory).rawBindings[Hotkeys.Action.FF_HOLD])
        }
        val untouched = Hotkeys.load(directory).rawBindings[Hotkeys.Action.QUICK_MENU]
        assertTrue(bindings.setHotkey(Hotkeys.Action.FF_HOLD, listOf("F5"), emptyList()))
        assertTrue(bindings.setHotkey(Hotkeys.Action.FF_HOLD, emptyList(), emptyList()))
        assertTrue(bindings.hotkeys()[Hotkeys.Action.FF_HOLD].isNullOrEmpty())
        assertEquals(untouched, Hotkeys.load(directory).rawBindings[Hotkeys.Action.QUICK_MENU])
        assertTrue(bindings.setGameButton(GbaControls.Btn.A, "BUTTON_B"))
        assertTrue(bindings.clearGameButton(GbaControls.Btn.TURBO_A))
        assertEquals(4, reloads)
    }

    @Test fun invalidAndUnsupportedEditsDoNotWriteOrReload() = withDirectory { directory ->
        val controls = (directory / "controls.properties").readText()
        val hotkeys = (directory / "hotkeys.properties").readText()
        val bindings = GameBindings(directory, GameActions.BASIC_ACTIONS) { error("invalid edit reloaded") }
        assertFalse(bindings.setGameButton(GbaControls.Btn.A, "NOT_A_KEY"))
        assertFalse(bindings.setHotkey(Hotkeys.Action.FF_HOLD, listOf("F5", "NOT_A_KEY"), emptyList()))
        assertFalse(bindings.setHotkey(Hotkeys.Action.QUICK_MENU, null, emptyList()))
        assertFalse(bindings.setHotkey(Hotkeys.Action.FF_HOLD, listOf("F5"), listOf(Hotkeys.Action.QUICK_MENU)))
        assertEquals(controls, (directory / "controls.properties").readText())
        assertEquals(hotkeys, (directory / "hotkeys.properties").readText())
    }

    @Test fun failedAtomicWritesKeepTheOldFilesAndDoNotPublish() = withDirectory { directory ->
        val controls = (directory / "controls.properties").readText()
        val hotkeys = (directory / "hotkeys.properties").readText()
        val bindings = GameBindings(directory, GameActions.BASIC_ACTIONS) { error("failed write reloaded") }
        FileSystem.SYSTEM.createDirectories(directory / "controls.properties.tmp")
        FileSystem.SYSTEM.createDirectories(directory / "hotkeys.properties.tmp")
        assertFalse(bindings.setGameButton(GbaControls.Btn.A, "F5"))
        assertFalse(bindings.clearGameButton(GbaControls.Btn.TURBO_A))
        assertFalse(bindings.setHotkey(Hotkeys.Action.FF_HOLD, listOf("F5"), emptyList()))
        assertFalse(bindings.setHotkey(Hotkeys.Action.FF_HOLD, null, emptyList()))
        assertEquals(controls, (directory / "controls.properties").readText())
        assertEquals(hotkeys, (directory / "hotkeys.properties").readText())
    }

    @Test fun aFailedDpadHotkeyRemovalRollsBackTheButtonEdit() = withDirectory { directory ->
        assertTrue(Hotkeys.setBindingNamed(directory, Hotkeys.Action.ZOOM, listOf("F5")))
        val before = GbaControls.rawBindings(directory)
        val hotkeys = (directory / "hotkeys.properties").readText()
        FileSystem.SYSTEM.createDirectories(directory / "hotkeys.properties.tmp")
        val bindings = GameBindings(directory, GameActions.BASIC_ACTIONS) { error("partial edit reloaded") }
        assertFalse(bindings.setGameButton(GbaControls.Btn.UP, "F5"))
        assertEquals(before, GbaControls.rawBindings(directory))
        assertEquals(hotkeys, (directory / "hotkeys.properties").readText())
    }
}
