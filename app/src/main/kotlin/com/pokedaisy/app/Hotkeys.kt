package com.pokedaisy.app

import com.pokedaisy.app.companion.NewFeatures
import android.util.Log
import android.view.KeyEvent
import com.pokedaisy.app.companion.i18n.tk
import java.io.File
import java.util.Properties

/**
 * Configurable emulator hotkeys, read from `files/hotkeys.properties`.
 *
 * A binding is a comma-separated list of *chords*; a chord is one or more key
 * names joined with `+` (e.g. `BUTTON_SELECT+BUTTON_R1`). A chord fires once,
 * on the press of its last key, while every key in it is held. A key is hidden
 * from the game only while it's completing/holding a chord — so `SELECT` alone
 * still works in-game, but `SELECT+R1` is captured.
 *
 * Rebind from the in-app Settings screen or edit the file directly.
 */
class Hotkeys private constructor(
    /** Raw chord strings per action, as written in the file (for the Settings UI). */
    val rawBindings: Map<Action, List<String>>,
) {
    /** [title]: the settings rows' name, English (translated where it's drawn). */
    enum class Action(val title: String) {
        SAVE_STATE(tk("SAVE STATE")), LOAD_STATE(tk("LOAD STATE")), UNDO_SAVE(tk("UNDO SAVE")),
        UNDO_LOAD(tk("UNDO LOAD")), SLOT_NEXT(tk("SLOT NEXT")), SLOT_PREV(tk("SLOT PREV")),
        FF_HOLD(tk("FF HOLD")), FF_TOGGLE(tk("FF TOGGLE")), SPEED_CYCLE(tk("SPEED CYCLE")),
        SLOWMO_HOLD(tk("SLOWMO HOLD")), REWIND_HOLD(tk("REWIND HOLD")), ZOOM(tk("ZOOM")),
        SOFT_RESET(tk("SOFT RESET")), EXIT_GAME(tk("EXIT GAME")),
        // No default keys (not in DEFAULTS): the player binds them.
        RESTART_GAME(tk("RESTART GAME")), CLOSE_APP(tk("CLOSE APP")),
        // Pressed: the QUICK MENU opens or closes (companion.ui.QuickMenu); L3 by default.
        QUICK_MENU(tk("QUICK MENU")),
        // No default keys: opens the companion over the game on one screen, where COMPANION BUTTON can hide its tab.
        COMPANION(tk("COMPANION"));

        val prop get() = name.lowercase()

        /** The NEW tag on its row in HOTKEYS (both screens). */
        val badge: String? get() = when (this) {
            RESTART_GAME, CLOSE_APP -> NewFeatures.badge(NewFeatures.GAME_HOTKEYS)
            QUICK_MENU -> NewFeatures.badge(NewFeatures.QUICK_MENU)
            COMPANION -> NewFeatures.badge(NewFeatures.COMPANION_HOTKEY)
            else -> null
        }
        val label get() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    data class Event(val action: Action, val pressed: Boolean)

    /** action -> list of chords; each chord is the set of keycodes that must all be held. */
    private val chords: Map<Action, List<Set<Int>>> = rawBindings.mapValues { (_, strs) ->
        strs.mapNotNull { s ->
            val ks = s.split('+').mapNotNull { resolveKey(it.trim()) }
            if (ks.isEmpty()) null else ks.toSet()
        }
    }

    private val held = HashSet<Int>()
    private val activeChords = HashSet<Pair<Action, Set<Int>>>()

    /** @return press/release events triggered by this key change. */
    fun onKey(keyCode: Int, down: Boolean): List<Event> {
        if (down) held.add(keyCode) else held.remove(keyCode)
        val out = ArrayList<Event>()
        for ((action, list) in chords) for (chord in list) {
            if (keyCode !in chord) continue
            val satisfied = held.containsAll(chord)
            val key = action to chord
            if (satisfied && activeChords.add(key)) out.add(Event(action, true))
            else if (!satisfied && activeChords.remove(key)) out.add(Event(action, false))
        }
        return out
    }

    /** True if this key should be withheld from the game right now (it's part of
     *  a chord whose other members are all held). */
    fun consumes(keyCode: Int): Boolean = chords.values.any { list ->
        list.any { chord -> keyCode in chord && (chord - keyCode).all { it in held } }
    }

    companion object {
        private val DEFAULTS = linkedMapOf(
            // X / Y are START 2 / SELECT 2 now ([GbaControls.Btn.START2]).
            "save_state" to "BUTTON_SELECT+BUTTON_R1, F1",
            "load_state" to "BUTTON_SELECT+BUTTON_L1, F2",
            "undo_save" to "F3",
            "undo_load" to "F4",
            // R3 is ZOOM's (the right stick looks around), L3 the QUICK MENU's (it was SLOT PREV's).
            "slot_next" to "RIGHT_BRACKET",
            "slot_prev" to "LEFT_BRACKET",
            "ff_hold" to "BUTTON_R2, SPACE",
            "ff_toggle" to "TAB",
            "speed_cycle" to "BUTTON_L2, EQUALS",
            "slowmo_hold" to "MINUS",
            // RetroArch's keyboard default; a pad button is the player's pick (HOTKEYS).
            "rewind_hold" to "R",
            // WIDE's zoom steps ([WideCamera]), on the stick that looks around.
            "zoom" to "BUTTON_THUMBR, C",
            // RetroArch's keyboard reset key; on a pad A + B + SELECT + START already is the game's own.
            "soft_reset" to "H",
            "exit_game" to "BUTTON_SELECT+BUTTON_START, ESCAPE",
            // The QUICK MENU (the user's pick, L3: no GBA button, so nothing in the game loses it).
            "quick_menu" to "BUTTON_THUMBL",
        )

        /** Earlier defaults (X / Y saved and loaded states, R3 the next slot), swapped for [DEFAULTS]' where a file still
         * has them - nobody changed those. */
        private val OLD_DEFAULTS = mapOf(
            "save_state" to "BUTTON_SELECT+BUTTON_R1, BUTTON_Y, F1",
            "load_state" to "BUTTON_SELECT+BUTTON_L1, BUTTON_X, F2",
            "slot_next" to "BUTTON_THUMBR, RIGHT_BRACKET",
            // L3 went to the QUICK MENU.
            "slot_prev" to "BUTTON_THUMBL, LEFT_BRACKET",
        )

        private fun file(filesDir: File) = File(filesDir, "hotkeys.properties")

        private fun readProps(filesDir: File): Properties {
            val props = Properties()
            val f = file(filesDir)
            if (f.isFile) {
                runCatching { f.inputStream().use(props::load) }
                    .onFailure { Log.w("pokedaisy", "hotkeys.properties unreadable, using defaults", it) }
            }
            var changed = false
            // X / Y left the state hotkeys for START 2 / SELECT 2: only where nobody changed them.
            for ((k, old) in OLD_DEFAULTS) if (props.getProperty(k) == old) { props.setProperty(k, DEFAULTS.getValue(k)); changed = true }
            for ((k, v) in DEFAULTS) if (!props.containsKey(k)) { props.setProperty(k, v); changed = true }
            if (changed) writeProps(filesDir, props)
            return props
        }

        private fun writeProps(filesDir: File, props: Properties) {
            runCatching {
                file(filesDir).outputStream().use {
                    props.store(it, "PokeDaisy hotkeys — comma-separated chords, '+' joins keys of one chord")
                }
            }
        }

        fun load(filesDir: File): Hotkeys {
            val props = readProps(filesDir)
            val raw = Action.entries.associateWith { a ->
                props.getProperty(a.prop).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
            return Hotkeys(raw)
        }

        /** Replace [action]'s binding with the single chord [keyCodes] and persist. */
        fun setBinding(filesDir: File, action: Action, keyCodes: Collection<Int>, takeFrom: Collection<Action> = emptyList()) =
            setBindingNamed(filesDir, action, keyCodes.map { keyName(it) }, takeFrom)

        /**
         * Replace [action]'s binding with the chord [keyNames] (KEYCODE_ suffixes) and persist; the same
         * chord comes off each of [takeFrom] (the player said MOVE to [clashes]), their other chords stay.
         */
        fun setBindingNamed(filesDir: File, action: Action, keyNames: Collection<String>, takeFrom: Collection<Action> = emptyList()) {
            val props = readProps(filesDir)
            val chord = chordOf(keyNames.joinToString("+"))
            for (other in takeFrom) if (other != action) {
                val kept = props.getProperty(other.prop).orEmpty().split(',').map { it.trim() }
                    .filter { it.isNotEmpty() && chordOf(it) != chord }
                props.setProperty(other.prop, kept.joinToString(", "))
            }
            props.setProperty(action.prop, keyNames.joinToString("+"))
            writeProps(filesDir, props)
        }

        /** Unbinds [action] (an empty value, so the default doesn't come back). */
        fun clearBinding(filesDir: File, action: Action) {
            val props = readProps(filesDir)
            props.setProperty(action.prop, "")
            writeProps(filesDir, props)
        }

        /**
         * The other actions already bound to exactly the chord [keyNames] - the same keys, in any order.
         * A chord that only shares some keys (SELECT vs SELECT+R1) isn't a clash: that's how chords work.
         */
        fun clashes(raw: Map<Action, List<String>>, action: Action, keyNames: Collection<String>): List<Action> {
            val chord = chordOf(keyNames.joinToString("+"))
            if (chord.isEmpty()) return emptyList()
            return raw.filter { (other, chords) -> other != action && chords.any { chordOf(it) == chord } }.keys.toList()
        }

        private fun chordOf(s: String): Set<String> =
            s.split('+').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

        /** Human name for a keycode (e.g. 96 -> "BUTTON_A"). */
        fun keyName(keyCode: Int): String =
            KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

        private fun resolveKey(name: String): Int? {
            if (name.isEmpty()) return null
            return runCatching {
                KeyEvent::class.java.getField("KEYCODE_$name").getInt(null)
            }.onFailure { Log.w("pokedaisy", "unknown hotkey key: $name") }.getOrNull()
        }
    }
}
