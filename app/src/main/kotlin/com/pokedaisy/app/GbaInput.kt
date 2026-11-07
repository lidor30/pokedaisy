package com.pokedaisy.app

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Translates Android key/motion events into a live GBA key bitmask. Face /
 * shoulder / menu buttons are remappable via [setControls] (GbaControls); the
 * D-pad and analog stick are fixed.
 */
class GbaInput : Hotkeys.GameKeys {
    @Volatile
    var mask: Int = 0
        private set

    @Volatile private var buttonBits = 0   // from key events: the bits of every key in heldKeys
    private var turboBits = 0    // TURBO A / B held: pulsed by the engine ([turboMask])
    private val turboKeys = HashMap<Int, Int>()
    // Per key, not per bit: START and X are two keys for one bit, and
    // letting go of one mustn't release the other.
    private val heldKeys = HashMap<Int, Int>()
    @Volatile private var hatBits = 0      // from D-pad HAT / analog axes
    @Volatile private var touchBits = 0    // from the on-screen TouchControlsView (any thread: TouchInputWindows)
    @Volatile private var scriptBits = 0   // synthetic presses (BattleInputController)
    @Volatile private var scriptExclusive = false

    @Volatile
    private var gbaControls: Map<Int, Int> = DEFAULT_CONTROLS
    @Volatile
    private var dsControls: Map<Int, Int> = DEFAULT_CONTROLS

    fun setControls(maps: GbaControls.KeyMaps) { gbaControls = maps.gba; dsControls = maps.ds }

    /** One map for every game (tests). */
    fun setControls(map: Map<Int, Int>) { gbaControls = map; dsControls = map }

    /** A DS game runs: the keys go by GAME BUTTONS' DS map (X / Y), else by the GBA / Game Boy one (START 2, SELECT 2,
     * TO COMPANION). */
    @Volatile var ds = false

    private val controls get() = if (ds) dsControls else gbaControls

    /** @return true if the event was consumed. */
    override fun onKey(keyCode: Int, down: Boolean): Boolean {
        val bit = keyToBit(keyCode) ?: return false
        if (bit and GbaControls.TURBO != 0) {
            if (down) turboKeys[keyCode] = bit and (GbaControls.TURBO or GbaControls.COMPANION).inv() else turboKeys.remove(keyCode)
            turboBits = turboKeys.values.fold(0) { acc, b -> acc or b }
            return true
        }
        // A TO COMPANION key with no button of its own is still the player's: it's taken, the game gets nothing.
        if (down) heldKeys[keyCode] = bit and GbaControls.COMPANION.inv() else heldKeys.remove(keyCode)
        buttonBits = heldKeys.values.fold(0) { acc, b -> acc or b }
        recompute()
        return true
    }

    /** The GBA button [keyCode] is bound to (D-pad, or the player's GAME BUTTONS; a TURBO key counts as its button),
     * null = none. The companion's window reads keys through this, so it follows the player's bindings. */
    override fun buttonFor(keyCode: Int): Int? = keyToBit(keyCode)?.let { it and (GbaControls.TURBO or GbaControls.COMPANION).inv() }

    /** [keyCode] is GAME BUTTONS' TO COMPANION (a GBA / Game Boy game), whatever else it presses in the game. */
    fun isCompanion(keyCode: Int): Boolean = !ds && (gbaControls[keyCode] ?: 0) and GbaControls.COMPANION != 0

    /** The player holds a game button (a key, the stick / D-pad or the touch controls). */
    override val holdsButtons: Boolean get() = heldKeys.isNotEmpty() || turboKeys.isNotEmpty() || hatBits != 0 || touchBits != 0

    /** A press that was held back from the game (SELECT / START of a hotkey chord, [Hotkeys.gameKey]) and let go:
     * pressed for [TAP_FRAMES] game frames from now ([tapMask]). */
    @Volatile private var tapBits = 0
    @Volatile private var tapFrames = 0

    override fun tap(keyCode: Int) {
        tapBits = buttonFor(keyCode) ?: return
        tapFrames = TAP_FRAMES
    }

    /** [tap]'s button for this frame, counting it down; the engine calls it once per game frame. None while a script
     * steers, like [turboMask]. */
    fun tapMask(): Int {
        if (tapFrames <= 0) return 0
        val bits = tapBits
        if (--tapFrames == 0) tapBits = 0
        return if (scriptExclusive) 0 else bits
    }

    /** Lets go of every key the player holds (not a script's): the companion's window took the buttons, or a rebind
     *  the keys' releases. */
    fun releaseAll() {
        tapFrames = 0
        tapBits = 0
        heldKeys.clear()
        turboKeys.clear()
        buttonBits = 0
        turboBits = 0
        hatBits = 0
        touchBits = 0
        touchTurboBits = 0
        recompute()
    }

    /** The TURBO A / B buttons held (keys or the touch controls'), which the engine presses every other 2 frames;
     * none while a script steers. */
    val turboMask: Int get() = if (scriptExclusive) 0 else turboBits or touchTurboBits

    @Volatile private var touchTurboBits = 0

    /** The touch controls' GBA buttons held ([bits]) and their TURBO A / B ([turbo], pulsed like [turboMask]). */
    fun setTouchBits(bits: Int, turbo: Int = 0) {
        touchBits = bits
        touchTurboBits = turbo
        recompute()
    }

    /**
     * Synthetic presses from the companion's battle controls. While
     * [exclusive] (an automated sequence is running) the player's own keys -
     * buttons, D-pad, touch controls - are ignored, so they can't knock the
     * sequence off course; they count again the moment it ends.
     */
    fun setScript(bits: Int, exclusive: Boolean) {
        scriptBits = bits
        scriptExclusive = exclusive
        recompute()
    }

    /** One-off presses for the game (the QUICK MENU's SELECT for a bike or a rod): pressed on top of everything else,
     * apart from [setScript]'s channel, which the battle controls send every frame (they'd wipe it). */
    @Volatile private var pressBits = 0

    fun setPress(bits: Int) {
        pressBits = bits
        recompute()
    }

    fun onMotion(event: MotionEvent): Boolean {
        hatBits = motionBits(event) ?: return false
        recompute()
        return true
    }

    // Synchronized: the touch controls' input windows set their bits from a thread of their own (TouchInputWindows), the
    // battle scripts from the emu thread, the keys from the UI thread - each recompute must see the others' latest.
    @Synchronized
    private fun recompute() {
        mask = (if (scriptExclusive) scriptBits else buttonBits or hatBits or touchBits or scriptBits) or pressBits
    }

    private fun keyToBit(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> MgbaCore.Key.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> MgbaCore.Key.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> MgbaCore.Key.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> MgbaCore.Key.RIGHT
        // Some controllers (8BitDo, GameSir in a few modes) send a diagonal as one key.
        KeyEvent.KEYCODE_DPAD_UP_LEFT -> MgbaCore.Key.UP or MgbaCore.Key.LEFT
        KeyEvent.KEYCODE_DPAD_UP_RIGHT -> MgbaCore.Key.UP or MgbaCore.Key.RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> MgbaCore.Key.DOWN or MgbaCore.Key.LEFT
        KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> MgbaCore.Key.DOWN or MgbaCore.Key.RIGHT
        else -> controls[keyCode]   // remappable face/shoulder/menu buttons
    }

    companion object {
        private const val DEAD = 0.5f
        /** A held-back tap's length: the Game Boy games miss shorter presses outside their menus (3 frames), and
         * Crystal Clear's menus want 6. */
        const val TAP_FRAMES = 6

        /** The D-pad directions a joystick event holds (its HAT, else the left stick); null = not a joystick's. */
        fun motionBits(event: MotionEvent): Int? {
            if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return null
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val x = if (abs(hx) > 0.5f) hx else event.getAxisValue(MotionEvent.AXIS_X)
            val y = if (abs(hy) > 0.5f) hy else event.getAxisValue(MotionEvent.AXIS_Y)
            var bits = 0
            if (x < -DEAD) bits = bits or MgbaCore.Key.LEFT
            if (x > DEAD) bits = bits or MgbaCore.Key.RIGHT
            if (y < -DEAD) bits = bits or MgbaCore.Key.UP
            if (y > DEAD) bits = bits or MgbaCore.Key.DOWN
            return bits
        }

        private val DEFAULT_CONTROLS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to MgbaCore.Key.A, KeyEvent.KEYCODE_Z to MgbaCore.Key.A,
            KeyEvent.KEYCODE_BUTTON_B to MgbaCore.Key.B, KeyEvent.KEYCODE_X to MgbaCore.Key.B,
            KeyEvent.KEYCODE_BUTTON_L1 to MgbaCore.Key.L, KeyEvent.KEYCODE_A to MgbaCore.Key.L,
            KeyEvent.KEYCODE_BUTTON_R1 to MgbaCore.Key.R, KeyEvent.KEYCODE_S to MgbaCore.Key.R,
            KeyEvent.KEYCODE_BUTTON_START to MgbaCore.Key.START, KeyEvent.KEYCODE_ENTER to MgbaCore.Key.START,
            KeyEvent.KEYCODE_BUTTON_SELECT to MgbaCore.Key.SELECT,
            KeyEvent.KEYCODE_BACKSLASH to MgbaCore.Key.SELECT, KeyEvent.KEYCODE_SHIFT_RIGHT to MgbaCore.Key.SELECT,
        )
    }
}
