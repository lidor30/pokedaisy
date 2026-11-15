package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.KeyCodes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * GAME BUTTONS / HOTKEYS rebinding by pressing the keys themselves - never by picking a key's name from a list.
 * One of these per activity: the top-screen Settings, first-time setup (the library) and the game, which runs the
 * companion's (its Presentation never gets keys: every key goes to the game's activity, and the game's screen
 * shows what to press, [com.pokedaisy.app.companion.ui.KeyCapturePopup]).
 *
 * While a [Request] waits, every key goes here and nowhere else - not the game, the hotkeys or the UI's focus -
 * and so do the analog triggers ([onTriggers], L2 / R2 as their keys). Once every key pressed is let go the
 * request ends with them: a hotkey takes them all (a chord), a GBA button the first. BACK cancels. A key already
 * down when the capture began only counts once pressed again (its release - often the A that opened the row -
 * ends nothing), and a key still held when a capture ends keeps its release here, so nothing sees half a press.
 */
class KeyCapture {

    class Request(
        /** What's being bound, as the row says it ("GBA A", "SAVE STATE"): the row knows itself by it. */
        val title: String,
        /** A hotkey: several keys together. A GBA button takes one, and never the D-pad (fixed). */
        val chord: Boolean,
        /** The keys pressed, in order (keycodes). */
        val onDone: (List<Int>) -> Unit,
        /** Leaves it unbound (a hotkey, TURBO A / B); null where that isn't allowed. */
        val onClear: (() -> Unit)? = null,
    )

    var request by mutableStateOf<Request?>(null)
        private set

    /** The keys pressed so far in this capture (keycodes), for the row and the popup to show. */
    var keys by mutableStateOf<List<Int>>(emptyList())
        private set

    val active get() = request != null

    /** A capture began or ended (the game's activity shows / hides its popup, lets go of the game's keys). */
    var onActiveChanged: ((Boolean) -> Unit)? = null

    private val held = LinkedHashSet<Int>()
    /** Keys whose release is still ours though the capture is over. */
    private val swallowUp = HashSet<Int>()
    private var ltDown = false
    private var rtDown = false

    fun isFor(title: String) = request?.title == title

    fun start(r: Request) {
        val was = active
        swallowUp += held
        held.clear()
        keys = emptyList()
        request = r
        if (!was) onActiveChanged?.invoke(true)
    }

    fun cancel() {
        if (!active) return
        end()
    }

    /** CLEAR: the binding left empty. */
    fun clear() {
        val r = request ?: return
        end()
        r.onClear?.invoke()
    }

    private fun end() {
        swallowUp += held
        held.clear()
        keys = emptyList()
        request = null
        onActiveChanged?.invoke(false)
    }

    /** A key event that's neither a press nor a release (Android's ACTION_MULTIPLE): the capture's while it waits. */
    fun onOtherKey(keyCode: Int): Boolean = active && keyCode !in SYSTEM_KEYS

    /** A press or release ([repeatCount] > 0: held down); true when it's the capture's (the caller passes it on otherwise). */

    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int = 0): Boolean {
        val r = request
        if (r == null) {
            // A new press is the UI's / game's again (a release kept from a capture that never came, say).
            if (down) { swallowUp.remove(keyCode); return false }
            return swallowUp.remove(keyCode)
        }
        if (keyCode in SYSTEM_KEYS) return false
        if (down) {
            swallowUp.remove(keyCode)
            if (repeatCount > 0) return true
            if (keyCode == KeyCodes.BACK) {
                end()
                swallowUp += keyCode
                return true
            }
            if (!r.chord && keyCode in DPAD) return true
            if (keyCode !in keys) keys = keys + keyCode
            held += keyCode
        } else {
            // Pressed before the capture began: not one of its keys.
            if (!held.remove(keyCode)) return true
            if (held.isEmpty() && keys.isNotEmpty()) {
                val got = if (r.chord) keys else keys.take(1)
                end()
                r.onDone(got)
            }
        }
        return true
    }

    /**
     * The analog triggers (0..1; Retroid's send no keys) as BUTTON_L2 / R2 past half way. True while a capture
     * waits: the stick and the rest of the motion are its too, so the game doesn't move meanwhile.
     */
    fun onTriggers(left: Float, right: Float): Boolean {
        val l = left > 0.5f
        val r = right > 0.5f
        if (!active) {
            // Held into a capture, a trigger's release isn't a key of it: start from where they are.
            ltDown = l; rtDown = r
            return false
        }
        if (l != ltDown) { ltDown = l; onKey(KeyCodes.BUTTON_L2, l) }
        if (r != rtDown) { rtDown = r; onKey(KeyCodes.BUTTON_R2, r) }
        return true
    }

    companion object {
        /** Left to Android while capturing: volume, power, HOME, recents. */
        private val SYSTEM_KEYS = setOf(
            KeyCodes.VOLUME_UP, KeyCodes.VOLUME_DOWN, KeyCodes.VOLUME_MUTE,
            KeyCodes.POWER, KeyCodes.HOME, KeyCodes.APP_SWITCH,
        )
        /** The GBA's D-pad is fixed to these (GbaInput): a GBA button can't take one. */
        private val DPAD = setOf(
            KeyCodes.DPAD_UP, KeyCodes.DPAD_DOWN, KeyCodes.DPAD_LEFT, KeyCodes.DPAD_RIGHT,
            KeyCodes.DPAD_UP_LEFT, KeyCodes.DPAD_UP_RIGHT, KeyCodes.DPAD_DOWN_LEFT, KeyCodes.DPAD_DOWN_RIGHT,
        )

        /** A key's name as stored ("BUTTON_A", "Z"). */
        fun keyName(keyCode: Int): String = KeyCodes.name(keyCode)

        /** A stored key name as the player reads it: "BUTTON_L1" -> "L1", "SHIFT_RIGHT" -> "SHIFT RIGHT". */
        fun label(name: String) = name.replace("BUTTON_", "").replace('_', ' ')

        /** A binding's keys for a row: "A / Z", or "-" when it has none. */
        fun labels(names: List<String>?) = names.orEmpty().joinToString(" / ") { label(it) }.ifEmpty { "-" }

        /** The keys held so far in a capture: "SELECT + R1". */
        fun chordLabel(keyCodes: List<Int>) = keyCodes.joinToString(" + ") { label(keyName(it)) }
    }
}
