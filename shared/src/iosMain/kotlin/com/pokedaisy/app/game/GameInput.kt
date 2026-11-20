package com.pokedaisy.app.game

import com.pokedaisy.app.GbaInput
import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.KeyCapture
import com.pokedaisy.app.companion.data.KeyCodes
import platform.GameController.*

/** GameController only supplies canonical keys and axes; shared models own mapping, capture and turbo. */
class GameInput internal constructor(
    private val pads: () -> List<GCExtendedGamepad>,
    private val loadControls: () -> GbaControls.KeyMaps,
) {
    constructor() : this(
        { GCController.controllers().mapNotNull { (it as? GCController)?.extendedGamepad } }, IosGameInputBindings::load)
    var touchKeys = 0
    var touchTurbo = 0
    /** On-screen X/Y are canonical controller keys, so the existing bindings and hotkeys decide their meaning. */
    var touchButtonKeys: Set<Int> = emptySet()
    private var frame = 0L
    private val buttons = PolledGameKeys()
    // A second view of the same bindings handles only QUICK MENU while it owns manual input.
    // Reusing PolledGameKeys preserves chord quarantine and reentrant ownership changes.
    private val menuShortcut = PolledGameKeys().also { keys ->
        keys.onHotkey = { if (quickMenuCaptured) onHotkey(it) }
    }
    val capture: KeyCapture get() = buttons.capture
    var onHotkey: (Hotkeys.Event) -> Unit
        get() = buttons.onHotkey
        set(value) { buttons.onHotkey = value }
    fun setHotkeys(model: Hotkeys?) {
        val held = hardware().keys + touchButtonKeys
        buttons.setHotkeys(model, held)
        menuShortcut.setHotkeys(model?.forActions(listOf(Hotkeys.Action.QUICK_MENU)), held)
    }
    private val touchGate = ManualInputGate()
    private val axesGate = ManualInputGate()

    init {
        buttons.input.setControls(loadControls())
        capture.onActiveChanged = { ownership(ManualInputGate.Owner.BINDING, it) }
    }

    var companionCaptured = false
        set(value) {
            if (field == value) return
            field = value
            ownership(ManualInputGate.Owner.COMPANION, value)
        }
    var onCompanionButtons: (Int) -> Unit = {}
    var onCompanionAim: (Float, Float) -> Boolean = { _, _ -> false }
    var quickMenuCaptured = false
        set(value) {
            if (field == value) return
            if (!value) axesGate.quarantineUntilNeutral()
            field = value
            menuShortcut.quarantine(hardware().keys + touchButtonKeys)
        }
    var keyboardCaptured = false
        set(value) {
            if (field == value) return
            field = value
            buttons.quarantine(hardware().keys + touchButtonKeys)
            menuShortcut.quarantine(hardware().keys + touchButtonKeys)
        }
    fun editorCaptured(on: Boolean) = ownership(ManualInputGate.Owner.EDITOR, on)
    fun backgroundCaptured(on: Boolean) = ownership(ManualInputGate.Owner.BACKGROUND, on)

    private fun ownership(owner: ManualInputGate.Owner, on: Boolean) {
        touchGate.setBlocked(owner, on, touchKeys or touchTurbo)
        val hardware = hardware()
        axesGate.setBlocked(owner, on, hardware.axes)
        buttons.quarantine(hardware.keys + touchButtonKeys)
        menuShortcut.quarantine(hardware.keys + touchButtonKeys)
    }

    fun reloadBindings() {
        buttons.quarantine(hardware().keys + touchButtonKeys)
        buttons.input.setControls(loadControls())
    }

    fun controllerKeys(): Int = controllerKeys(hardware())
    private fun controllerKeys(h: Hardware): Int = h.controllers.fold(h.axes) { bits, code ->
        bits or (buttons.input.buttonFor(code) ?: 0)
    }

    /** UI and hardware ownership are polled at display pace, including slow-motion refreshes with no game frame. */
    fun poll() {
        val h = hardware()
        // A rebound menu key may also mean A/START. The shortcut closes the menu before that key can select a tile.
        val menuBlocked = !quickMenuCaptured || capture.active || touchGate.blockedExcept(ManualInputGate.Owner.COMPANION)
        if (quickMenuCaptured) menuShortcut.poll((if (keyboardCaptured) h.controllers else h.keys) + touchButtonKeys, menuBlocked)
        if (companionCaptured && !capture.active && !touchGate.blockedExcept(ManualInputGate.Owner.COMPANION)) {
            val aiming = quickMenuCaptured && onCompanionAim(h.stickX, h.stickY)
            val keys = if (quickMenuCaptured && !keyboardCaptured) h.keys else h.controllers
            val directions = if (aiming) h.dpad else h.axes
            onCompanionButtons(keys.fold(directions) { bits, code -> bits or (buttons.input.buttonFor(code) ?: 0) })
        }
        val beforeButtons = touchButtonKeys
        val beforeTouch = touchKeys
        val beforeTurbo = touchTurbo
        val allowed = touchGate.filter(touchKeys or touchTurbo)
        buttons.input.setTouchBits(touchKeys and allowed, touchTurbo and allowed)
        val axes = axesGate.filter(h.axes, h.neutral)
        buttons.input.setAxes(axes)
        if (axes != 0 || allowed != 0) buttons.letThrough()
        fun held() = (if (keyboardCaptured && !capture.active) h.controllers else h.keys) + touchButtonKeys
        buttons.poll(held(), touchGate.blocked)
        // A state/hotkey callback can release the initiating on-screen key during this same poll.
        if (touchButtonKeys != beforeButtons) buttons.poll(held(), touchGate.blocked)
        // State operations can release touch from a hotkey callback during this poll.
        if (touchKeys != beforeTouch || touchTurbo != beforeTurbo) {
            val after = touchGate.filter(touchKeys or touchTurbo)
            buttons.input.setTouchBits(touchKeys and after, touchTurbo and after)
        }
    }

    /** The native batch reuses the manual snapshot; turbo advances at game-frame pace. */
    fun keys(): Int {
        val turbo = if ((frame++ / 2) % 2 == 0L) buttons.input.turboMask else 0
        return buttons.input.mask or turbo or buttons.input.tapMask()
    }

    /** Library settings need capture polling without starting the emulator. */
    fun pollCapture() = buttons.poll(hardware().keys, blocked = true)

    private data class Hardware(val controllers: Set<Int>, val keys: Set<Int>, val axes: Int,
        val dpad: Int, val stickX: Float, val stickY: Float, val neutral: Boolean)
    private fun hardware(): Hardware {
        val pads = pads()
        val controllers = pads.flatMap { it.canonicalKeys() }.toSet()
        val keyboard = buildSet {
            val keyboard = GCKeyboard.coalescedKeyboard()?.keyboardInput ?: return@buildSet
            for ((native, canonical) in KEYBOARD) if (keyboard.buttonForKeyCode(native)?.pressed == true) add(canonical)
        }
        var axes = 0; var dpad = 0; var x = 0f; var y = 0f; var neutral = true
        val watchMenuAxes = quickMenuCaptured || axesGate.awaitingNeutral
        for (pad in pads) {
            val sx = pad.leftThumbstick.xAxis.value; val sy = -pad.leftThumbstick.yAxis.value
            val dx = pad.dpad.xAxis.value; val dy = -pad.dpad.yAxis.value
            axes = axes or GbaInput.axisBits(sx, sy, dx, dy)
            if (watchMenuAxes) {
                val hat = GbaInput.axisBits(0f, 0f, dx, dy)
                dpad = dpad or hat
                if (sx * sx + sy * sy > x * x + y * y) { x = sx; y = sy }
                neutral = neutral && sx * sx + sy * sy < .25f && hat == 0
            }
        }
        return Hardware(controllers, controllers + keyboard, axes, dpad, x, y, neutral)
    }

    private companion object {
        val KEYBOARD = listOf(
            GCKeyCodeUpArrow to "DPAD_UP", GCKeyCodeDownArrow to "DPAD_DOWN",
            GCKeyCodeLeftArrow to "DPAD_LEFT", GCKeyCodeRightArrow to "DPAD_RIGHT",
            GCKeyCodeReturnOrEnter to "ENTER", GCKeyCodeDeleteOrBackspace to "DEL", GCKeyCodeEscape to "ESCAPE",
            GCKeyCodeSpacebar to "SPACE", GCKeyCodeTab to "TAB", GCKeyCodeHyphen to "MINUS",
            GCKeyCodeEqualSign to "EQUALS", GCKeyCodeOpenBracket to "LEFT_BRACKET", GCKeyCodeCloseBracket to "RIGHT_BRACKET",
            GCKeyCodeBackslash to "BACKSLASH", GCKeyCodeSemicolon to "SEMICOLON", GCKeyCodeQuote to "APOSTROPHE",
            GCKeyCodeComma to "COMMA", GCKeyCodePeriod to "PERIOD", GCKeyCodeSlash to "SLASH", GCKeyCodeGraveAccentAndTilde to "GRAVE",
            GCKeyCodeLeftShift to "SHIFT_LEFT", GCKeyCodeRightShift to "SHIFT_RIGHT",
            GCKeyCodeLeftControl to "CTRL_LEFT", GCKeyCodeRightControl to "CTRL_RIGHT",
            GCKeyCodeLeftAlt to "ALT_LEFT", GCKeyCodeRightAlt to "ALT_RIGHT",
            GCKeyCodeLeftGUI to "META_LEFT", GCKeyCodeRightGUI to "META_RIGHT",
        ).map { (code, name) -> code to KeyCodes.code(name)!! } +
            ('A'..'Z').mapIndexed { index, key -> (GCKeyCodeKeyA + index) to KeyCodes.code(key.toString())!! } +
            (1..12).map { n -> (GCKeyCodeF1 + n - 1) to KeyCodes.code("F$n")!! } +
            (1..9).map { n -> (GCKeyCodeOne + n - 1) to KeyCodes.code(n.toString())!! } +
            listOf(GCKeyCodeZero to KeyCodes.code("0")!!)
    }
}

internal fun GCExtendedGamepad.canonicalKeys(): Set<Int> = buildSet {
    if (buttonA.pressed) add(KeyCodes.BUTTON_A)
    if (buttonB.pressed) add(KeyCodes.BUTTON_B)
    if (buttonX.pressed) add(KeyCodes.BUTTON_X)
    if (buttonY.pressed) add(KeyCodes.BUTTON_Y)
    if (buttonMenu.pressed) add(KeyCodes.BUTTON_START)
    if (buttonOptions?.pressed == true) add(KeyCodes.BUTTON_SELECT)
    if (leftShoulder.pressed) add(KeyCodes.BUTTON_L1)
    if (rightShoulder.pressed) add(KeyCodes.BUTTON_R1)
    if (leftTrigger.pressed) add(KeyCodes.BUTTON_L2)
    if (rightTrigger.pressed) add(KeyCodes.BUTTON_R2)
    if (leftThumbstickButton?.pressed == true) add(KeyCodes.BUTTON_THUMBL)
    if (rightThumbstickButton?.pressed == true) add(KeyCodes.BUTTON_THUMBR)
}

internal fun GCExtendedGamepad.canonicalAxes(): Int =
    GbaInput.axisBits(leftThumbstick.xAxis.value, -leftThumbstick.yAxis.value, dpad.xAxis.value, -dpad.yAxis.value)
