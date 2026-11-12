package com.pokedaisy.app

import android.util.Log
import android.view.KeyEvent
import com.pokedaisy.app.companion.i18n.tk
import java.io.File
import java.util.Properties

/**
 * Remappable mapping of physical keys/buttons to GBA buttons, from
 * `files/controls.properties`. One or more key names per GBA button
 * (comma-separated; any of them triggers it). The D-pad and analog stick stay
 * fixed — only the face/shoulder/menu buttons are here.
 */
object GbaControls {

    /** Which games a [Btn] row is for: the GBA's own buttons are every game's, X / Y only the DS's, and the GBA / Game
     * Boy games have their own spare buttons instead (a key can be on one of each: they never run at once). */
    enum class Group(val title: String) {
        ALL(tk("ALL GAMES")), DS(tk("DS GAMES")), GBA(tk("GBA AND GAME BOY"));

        fun overlaps(other: Group) = this == ALL || other == ALL || this == other
    }

    /** X / Y: the DS games' X (menu) and Y (registered item). START 2 / SELECT 2 are the other games' second START /
     * SELECT, and TO COMPANION ([COMPANION]) takes the buttons to the companion on the field of a two-screen device
     * (PokeDaisyActivity's bottom focus) - by default all on the DS's X / Y keys. TURBO A / B (issue #32, unbound by
     * default) press A / B on and off while held - [TURBO] marks them in [load]'s map, [GbaInput] pulses them. */
    enum class Btn(val bit: Int, val label: String, val group: Group = Group.ALL) {
        A(MgbaCore.Key.A, "A"), B(MgbaCore.Key.B, "B"),
        L(MgbaCore.Key.L, "L"), R(MgbaCore.Key.R, "R"),
        START(MgbaCore.Key.START, "START"), SELECT(MgbaCore.Key.SELECT, "SELECT"),
        TURBO_A(MgbaCore.Key.A or TURBO, "TURBO A"), TURBO_B(MgbaCore.Key.B or TURBO, "TURBO B"),
        X(MgbaCore.Key.X, "X", Group.DS), Y(MgbaCore.Key.Y, "Y", Group.DS),
        START_2(MgbaCore.Key.START, "START 2", Group.GBA), SELECT_2(MgbaCore.Key.SELECT, "SELECT 2", Group.GBA),
        TO_COMPANION(COMPANION, tk("TO COMPANION"), Group.GBA),
        // More keys for the D-pad, unbound by default (the D-pad itself stays fixed, GbaInput): a controller whose D-pad
        // sends other keys - the 8BitDo Micro's keyboard mode sends letters, its UP the C of ZOOM's hotkey.
        UP(MgbaCore.Key.UP, tk("D-PAD UP")), DOWN(MgbaCore.Key.DOWN, tk("D-PAD DOWN")),
        LEFT(MgbaCore.Key.LEFT, tk("D-PAD LEFT")), RIGHT(MgbaCore.Key.RIGHT, tk("D-PAD RIGHT"));

        /** One of the D-pad's extra keys ([UP] .. [RIGHT]). */
        val dpad get() = this == UP || this == DOWN || this == LEFT || this == RIGHT

        /** Its key in the file: START 2 / SELECT 2 kept the names X / Y had when they were one row each. */
        val prop get() = when (this) {
            X -> "ds_x"; Y -> "ds_y"; START_2 -> "start2"; SELECT_2 -> "select2"; TO_COMPANION -> "companion"
            else -> name.lowercase()
        }
        /** Its row's name in GAME BUTTONS: the GBA's own buttons say so, the others are their own. */
        val title get() = if (group == Group.ALL && !dpad) "GBA $label" else label
        /** START 2 / SELECT 2 / TO COMPANION are new rows (they were all X / Y once); TO COMPANION keeps X's tag. */
        val badge: String? get() = when (this) {
            TO_COMPANION -> com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.X_COMPANION)
            START_2, SELECT_2 -> com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.GBA_BUTTONS)
            UP, DOWN, LEFT, RIGHT -> com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.DPAD_KEYS)
            else -> null
        }
        /** A line under its row: what it does (English; translated where drawn). */
        val subtitle: String? get() = when (this) {
            START_2 -> "A second START"
            SELECT_2 -> "A second SELECT"
            TO_COMPANION -> "On the field: your buttons go to the companion"
            UP, DOWN, LEFT, RIGHT -> "Another key for the D-pad"
            else -> null
        }
        /** Every game needs the GBA's buttons and the DS its X / Y; the rest start unbound or can go back to it. */
        val canClear get() = this == TURBO_A || this == TURBO_B || group == Group.GBA || dpad
        /** TO COMPANION shares its key with a button (by default START 2's); it never takes the key from one. */
        fun sharesKeyWith(other: Btn) = this == other || !group.overlaps(other.group) || this == TO_COMPANION || other == TO_COMPANION
    }

    private val DEFAULTS = linkedMapOf(
        "a" to "BUTTON_A, Z",
        "b" to "BUTTON_B, X",
        "l" to "BUTTON_L1, A",
        "r" to "BUTTON_R1, S",
        "start" to "BUTTON_START, ENTER",
        "select" to "BUTTON_SELECT, BACKSLASH, SHIFT_RIGHT",
        // Face buttons go by Android's names (Xbox positions) on every device, even where
        // the printed labels differ (AYN, Retroid: BUTTON_A is the bottom one); GAME BUTTONS remaps.
        "ds_x" to "BUTTON_X",
        "ds_y" to "BUTTON_Y",
        "start2" to "BUTTON_X",
        "select2" to "BUTTON_Y",
        "companion" to "BUTTON_X",
        "turbo_a" to "",
        "turbo_b" to "",
        "up" to "",
        "down" to "",
        "left" to "",
        "right" to "",
    )

    /** Rows that came out of one: a file from before them gives each its old row's keys, so nothing moves (X was the
     * DS's X, a second START and the companion; Y the DS's Y and a second SELECT). */
    private val SPLIT_FROM = mapOf("ds_x" to "start2", "ds_y" to "select2", "companion" to "start2")

    /** Above every GBA key bit: a [load] value with it is a turbo button for the bits below. */
    const val TURBO = 1 shl 16

    /** TO COMPANION's mark in the GBA / Game Boy map ([Btn.TO_COMPANION]): never a game button. */
    const val COMPANION = 1 shl 17

    /** keycode -> button bits, for the GBA / Game Boy games ([gba]) and the DS games ([ds]). */
    class KeyMaps(val gba: Map<Int, Int>, val ds: Map<Int, Int>)

    private fun file(dir: File) = File(dir, "controls.properties")

    private fun readProps(dir: File): Properties {
        val p = Properties()
        val f = file(dir)
        if (f.isFile) runCatching { f.inputStream().use(p::load) }
        var changed = false
        // A file from before the split rows: start2 / select2 were X / Y, so they had keys if anything did.
        if (p.containsKey("start2")) for ((k, from) in SPLIT_FROM) if (!p.containsKey(k)) { p.setProperty(k, p.getProperty(from) ?: DEFAULTS.getValue(from)); changed = true }
        for ((k, v) in DEFAULTS) if (!p.containsKey(k)) { p.setProperty(k, v); changed = true }
        if (changed) runCatching {
            f.outputStream().use { p.store(it, "PokeDaisy GBA button map — Android key names, comma-separated") }
        }
        return p
    }

    /** keycode -> button bits per [Group] ([TURBO] set for TURBO A / B, [COMPANION] added to a key's button). A key
     * bound twice in one map takes the later button. */
    fun load(dir: File): KeyMaps {
        val props = readProps(dir)
        val gba = HashMap<Int, Int>()
        val ds = HashMap<Int, Int>()
        for (btn in Btn.entries) {
            props.getProperty(btn.prop).orEmpty().split(',').forEach { n ->
                val key = resolveKey(n.trim()) ?: return@forEach
                if (btn == Btn.TO_COMPANION) gba[key] = (gba[key] ?: 0) or COMPANION
                else {
                    if (btn.group != Group.DS) gba[key] = btn.bit or ((gba[key] ?: 0) and COMPANION)
                    if (btn.group != Group.GBA) ds[key] = btn.bit
                }
            }
        }
        return KeyMaps(gba, ds)
    }

    fun rawBindings(dir: File): Map<Btn, List<String>> {
        val props = readProps(dir)
        return Btn.entries.associateWith {
            props.getProperty(it.prop).orEmpty().split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
        }
    }

    /** [btn] is [keyCode] now - and that key leaves any other button it was on in the same games (else the later
     * button kept it); a DS row and a GBA / Game Boy row can share one, and TO COMPANION shares with anything. */
    fun setBinding(dir: File, btn: Btn, keyCode: Int) = setBindingNamed(dir, btn, Hotkeys.keyName(keyCode))

    /** [setBinding] by the key's name (no "KEYCODE_" prefix). */
    fun setBindingNamed(dir: File, btn: Btn, name: String) {
        val props = readProps(dir)
        for (other in Btn.entries) if (!btn.sharesKeyWith(other)) {
            val keys = props.getProperty(other.prop).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (name in keys) props.setProperty(other.prop, (keys - name).joinToString(", "))
        }
        props.setProperty(btn.prop, name)
        runCatching { file(dir).outputStream().use { props.store(it, "PokeDaisy GBA button map") } }
        // A D-pad key goes to the D-pad alone: a hotkey that was only this key loses it (else the hotkey kept it - the
        // 8BitDo Micro's UP is ZOOM's C).
        if (btn.dpad) Hotkeys.releaseKey(dir, name)
    }

    /** Leaves [btn] unbound - TURBO A / B and the GBA / Game Boy rows only ([Btn.canClear]). */
    fun clearBinding(dir: File, btn: Btn) {
        if (!btn.canClear) return
        val props = readProps(dir)
        props.setProperty(btn.prop, "")
        runCatching { file(dir).outputStream().use { props.store(it, "PokeDaisy GBA button map") } }
    }

    private fun resolveKey(name: String): Int? {
        if (name.isEmpty()) return null
        return runCatching { KeyEvent::class.java.getField("KEYCODE_$name").getInt(null) }
            .onFailure { Log.w("pokedaisy", "unknown control key: $name") }.getOrNull()
    }
}
