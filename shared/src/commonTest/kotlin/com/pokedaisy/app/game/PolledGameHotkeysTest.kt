package com.pokedaisy.app.game

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.KeyCapture
import com.pokedaisy.app.companion.data.CoreKey
import com.pokedaisy.app.companion.data.KeyCodes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/** The real file-backed chord engine and mapper, on JVM and iOS. */
class PolledGameHotkeysTest {
    @Test fun menuOnlyShortcutCannotFireGameActionsAndNeedsAFreshPressAfterCapture() = withDirectory { dir ->
        Hotkeys.setBindingNamed(dir, Hotkeys.Action.QUICK_MENU, listOf("BUTTON_R2"))
        val menu = keys(dir, Hotkeys.Action.QUICK_MENU)
        val events = mutableListOf<Hotkeys.Event>()
        menu.onHotkey = { events += it }
        menu.quarantine(setOf(trigger)) // The same held key opened the menu through the gameplay mapper.
        menu.poll(setOf(trigger), false)
        assertEquals(emptyList(), events)
        menu.poll(emptySet(), false)
        menu.poll(setOf(trigger), false)
        assertEquals(listOf(Hotkeys.Event(Hotkeys.Action.QUICK_MENU, true)), events)
        menu.quarantine(setOf(trigger)) // Closing with the hotkey must not retrigger when gameplay resumes.
        menu.poll(setOf(trigger), false)
        assertEquals(listOf(true, false), events.map { it.pressed })
        assertEquals(0, menu.input.mask)
        assertEquals(0, menu.input.tapMask())
        menu.poll(emptySet(), true)
        menu.poll(setOf(trigger), true) // Editors, binding capture and background own even the menu shortcut.
        assertEquals(2, events.size)
    }
    private val select = KeyCodes.BUTTON_SELECT
    private val right = KeyCodes.BUTTON_R1
    private val trigger = KeyCodes.BUTTON_R2

    private fun withDirectory(block: (Path) -> Unit) {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-hotkeys-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(dir)
        try { block(dir) } finally { FileSystem.SYSTEM.deleteRecursively(dir) }
    }

    private fun keys(dir: Path, vararg actions: Hotkeys.Action) = PolledGameKeys().apply {
        input.setControls(GbaControls.load(dir))
        setHotkeys(Hotkeys.load(dir).forActions(actions.toList()), emptySet())
    }

    @Test fun ownershipEndsFfAndDisablingRestoresTheOldTriggerAfterRelease() = withDirectory { dir ->
        val k = keys(dir, Hotkeys.Action.FF_HOLD)
        k.input.setControls(mapOf(trigger to CoreKey.R))
        val events = mutableListOf<Boolean>()
        k.onHotkey = { events += it.pressed }
        k.poll(setOf(trigger), false); k.poll(setOf(trigger), false)
        assertEquals(listOf(true), events); assertEquals(0, k.input.mask)
        k.quarantine(setOf(trigger)); k.poll(setOf(trigger), false)
        assertEquals(listOf(true, false), events)
        k.poll(emptySet(), false); k.poll(setOf(trigger), false)
        assertEquals(listOf(true, false, true), events)
        k.setHotkeys(null, setOf(trigger)); k.poll(setOf(trigger), false)
        assertEquals(listOf(true, false, true, false), events); assertEquals(0, k.input.mask)
        k.poll(emptySet(), false); k.poll(setOf(trigger), false)
        assertEquals(CoreKey.R, k.input.mask)
    }

    @Test fun selectAloneBecomesOneSixFrameTap() = withDirectory { dir ->
        val k = keys(dir, Hotkeys.Action.SAVE_STATE)
        val events = mutableListOf<Hotkeys.Event>(); k.onHotkey = { events += it }
        k.poll(setOf(select), false); assertEquals(0, k.input.mask)
        k.poll(emptySet(), false)
        repeat(6) { assertEquals(CoreKey.SELECT, k.input.tapMask()) }
        assertEquals(0, k.input.tapMask()); assertEquals(0, k.input.mask)
        assertEquals(0, events.size)
    }

    @Test fun selectShoulderChordNeverTapsSelectOrPressesTheShoulder() = withDirectory { dir ->
        val k = keys(dir, Hotkeys.Action.SAVE_STATE)
        val events = mutableListOf<Pair<Hotkeys.Action, Boolean>>()
        k.onHotkey = { events += it.action to it.pressed }
        k.poll(setOf(select), false); k.poll(setOf(select, right), false)
        assertEquals(0, k.input.mask)
        k.poll(setOf(select), false); k.poll(emptySet(), false)
        assertEquals(listOf(Hotkeys.Action.SAVE_STATE to true, Hotkeys.Action.SAVE_STATE to false), events)
        assertEquals(0, k.input.tapMask()); assertEquals(0, k.input.mask)
    }

    @Test fun analogOrTouchMovementLetsAnUnusedChordPrefixThrough() = withDirectory { dir ->
        val k = keys(dir, Hotkeys.Action.SAVE_STATE)
        k.poll(setOf(select), false)
        k.input.setAxes(CoreKey.UP); k.letThrough()
        assertEquals(CoreKey.UP or CoreKey.SELECT, k.input.mask)
        k.poll(emptySet(), false)
        assertEquals(CoreKey.UP, k.input.mask); assertEquals(0, k.input.tapMask())
    }

    @Test fun unavailableActionsCannotTakeAMappedGameKey() = withDirectory { dir ->
        GbaControls.setBindingNamed(dir, GbaControls.Btn.A, "C")
        val model = Hotkeys.load(dir).forActions(listOf(Hotkeys.Action.FF_HOLD))
        assertEquals(setOf(Hotkeys.Action.FF_HOLD), model.rawBindings.keys)
        val k = PolledGameKeys(); k.input.setControls(GbaControls.load(dir)); k.setHotkeys(model, emptySet())
        k.poll(setOf(KeyCodes.code("C")!!), false)
        assertEquals(CoreKey.A, k.input.mask) // C's unavailable ZOOM binding is harmless.
    }

    @Test fun anActionThatChangesOwnershipStopsOtherPressActionsFromThatPoll() = withDirectory { dir ->
        Hotkeys.setBindingNamed(dir, Hotkeys.Action.FF_TOGGLE, listOf("BUTTON_R2"))
        val k = keys(dir, Hotkeys.Action.FF_HOLD, Hotkeys.Action.FF_TOGGLE)
        val pressed = mutableListOf<Hotkeys.Action>()
        k.onHotkey = { if (it.pressed) { pressed += it.action; k.quarantine(setOf(trigger)) } }
        k.poll(setOf(trigger), false)
        assertEquals(listOf(Hotkeys.Action.FF_HOLD), pressed)
        assertEquals(0, k.input.mask)
    }

    @Test fun captureOwnsAHotkeyAndItMustBePressedAgainAfterward() = withDirectory { dir ->
        val k = keys(dir, Hotkeys.Action.FF_HOLD)
        var held = emptySet<Int>(); var bound = emptyList<Int>()
        val events = mutableListOf<Boolean>(); k.onHotkey = { events += it.pressed }
        k.capture.onActiveChanged = { k.quarantine(held) }
        k.capture.start(KeyCapture.Request("FF HOLD", true, { bound = it }))
        held = setOf(trigger); k.poll(held, true)
        held = emptySet(); k.poll(held, true)
        assertEquals(listOf(trigger), bound); assertEquals(emptyList(), events); assertFalse(k.capture.active)
        held = setOf(trigger); k.poll(held, false)
        assertEquals(listOf(true), events)
    }

    @Test fun aConsumedReleaseCannotLeaveAGameButtonStuck() = withDirectory { dir ->
        Hotkeys.setBindingNamed(dir, Hotkeys.Action.FF_TOGGLE, listOf("BUTTON_A", "BUTTON_X"))
        val k = keys(dir, Hotkeys.Action.FF_TOGGLE)
        k.poll(setOf(KeyCodes.BUTTON_A), false)
        assertEquals(CoreKey.A, k.input.mask)
        k.poll(setOf(KeyCodes.BUTTON_A, KeyCodes.BUTTON_X), false)
        k.poll(setOf(KeyCodes.BUTTON_X), false)
        assertEquals(0, k.input.mask)
        k.poll(emptySet(), false); assertEquals(0, k.input.mask)
    }
    @Test fun anUnfinishedPrefixCannotTapTheGameAfterAnOwnershipChange() = withDirectory { dir ->
        val k = keys(dir, Hotkeys.Action.SAVE_STATE)
        k.poll(setOf(select), false)
        k.quarantine(setOf(select))
        k.poll(emptySet(), false)
        assertEquals(0, k.input.mask); assertEquals(0, k.input.tapMask())
    }

    @Test fun holdEndsOnlyAfterTheLastKeyboardOrControllerAliasIsReleased() = withDirectory { dir ->
        for (action in listOf(Hotkeys.Action.FF_HOLD, Hotkeys.Action.SLOWMO_HOLD, Hotkeys.Action.REWIND_HOLD)) {
            FileSystem.SYSTEM.write(dir / "hotkeys.properties") { writeUtf8("${action.prop}=BUTTON_R2, SPACE") }
            val k = keys(dir, action)
            val space = KeyCodes.code("SPACE")!!
            val events = mutableListOf<Boolean>(); k.onHotkey = { events += it.pressed }
            k.poll(setOf(trigger), false)
            k.poll(setOf(trigger, space), false)
            k.poll(setOf(space), false)
            assertEquals(listOf(true), events, action.name)
            k.poll(emptySet(), false)
            assertEquals(listOf(true, false), events, action.name)
            k.poll(setOf(trigger, space), false)
            k.quarantine(setOf(trigger, space))
            assertEquals(listOf(true, false, true, false), events, action.name)
        }
    }

    @Test fun toggleAliasesKeepTheirSeparatePressEdges() = withDirectory { dir ->
        FileSystem.SYSTEM.write(dir / "hotkeys.properties") { writeUtf8("ff_toggle=BUTTON_R2, SPACE") }
        val k = keys(dir, Hotkeys.Action.FF_TOGGLE)
        val events = mutableListOf<Boolean>(); k.onHotkey = { events += it.pressed }
        k.poll(setOf(trigger), false)
        k.poll(setOf(trigger, KeyCodes.code("SPACE")!!), false)
        assertEquals(listOf(true, true), events)
    }

}
