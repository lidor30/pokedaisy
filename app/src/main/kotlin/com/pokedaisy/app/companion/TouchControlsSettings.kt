package com.pokedaisy.app.companion

import androidx.compose.ui.geometry.Rect
import com.pokedaisy.app.companion.i18n.tk

// SETTINGS > TOUCH CONTROLS' choices (the controls themselves are companion.ui.TouchControls, their geometry
// companion.ui.TouchLayout): here, beside the other settings types, so Prefs and CompanionSettings can hold them.

/** When the on-screen controls show. Stored as its ordinal ([com.pokedaisy.app.Prefs.touchControlsMode]: 0 / 1 / 2). */
enum class TouchShow(val label: String) {
    /** Unless a controller is connected (or a real button was pressed this game). */
    AUTO(tk("AUTO")),
    ALWAYS(tk("ALWAYS")),
    NEVER(tk("NEVER")),
}

/** A ready-made arrangement. The player's own (EDIT LAYOUT) is a [TouchLayout] on top of one, per orientation. */
enum class TouchPreset(val label: String) {
    /** D-pad bottom left, A / B bottom right, START / SELECT bottom middle, L / R in the top corners. */
    CLASSIC(tk("CLASSIC")),

    /** Everything in the black margins beside the game, nothing over the picture; [CLASSIC] where the margins are
     * too narrow (an upright phone, a 4:3 screen). The default. */
    SIDES(tk("SIDES")),

    /** [CLASSIC], smaller and pulled into the corners, START / SELECT beside the D-pad and A / B. */
    COMPACT(tk("COMPACT")),

    /** [SIDES] (or [CLASSIC] where it falls back) the other way round: A / B on the left, the D-pad on the right, for
     * left-handed players. L / R stay on their own sides. */
    MIRRORED(tk("MIRRORED")),

    /** Held sideways, [CLASSIC] with the D-pad and A / B raised to where thumbs rest, L / R right above them instead
     * of in the top corners. Upright, [CLASSIC]. */
    RAISED(tk("RAISED")),
}

/** How the buttons are drawn (LOOK). */
enum class TouchLook(val label: String) {
    /** See-through, a light edge to find them by: the HUD's GLASS. The default. */
    GLASS(tk("GLASS")),
    /** Filled in [TouchColors]' colour, nothing of the game showing through a button (OPACITY still applies). */
    SOLID(tk("SOLID")),
    /** Just the outlines and words; a press fills the button. */
    OUTLINE(tk("OUTLINE")),
    /** A handheld's buttons: raised, a shade under each, sinking when pressed, A / B in their colours. */
    CONSOLE(tk("CONSOLE")),
    /** Smooth round buttons and a plain font, see-through like GLASS: nothing pixelated. */
    MODERN(tk("MODERN")),
}

/**
 * The buttons' colours (COLORS), as ARGB: the body, its edge, the words and their shadow, and optionally A / B / X / Y
 * in colours of their own. [fill]'s alpha is GLASS's see-through; the other looks take the colour solid.
 */
enum class TouchColors(
    val label: String,
    val fill: Long, val edge: Long, val ink: Long, val shadow: Long,
    val a: Long? = null, val b: Long? = null, val x: Long? = null, val y: Long? = null,
) {
    DARK(tk("DARK"), 0xB8121620, 0xB0FFFFFF, 0xFFF4F6FA, 0xFF0C0F15),
    LIGHT(tk("LIGHT"), 0xD0F2F3F7, 0xE0343846, 0xFF1E2130, 0xFFC4C8D2),
    INDIGO(tk("INDIGO"), 0xD8463C8E, 0xFFB4AAF0, 0xFFFFFFFF, 0xFF1E1840),
    GLACIER(tk("GLACIER"), 0xA06AA6D6, 0xFFE2F4FF, 0xFFFFFFFF, 0xFF1F4466),
    SPICE(tk("SPICE"), 0xD8D9682A, 0xFFFFD2B0, 0xFFFFFFFF, 0xFF5A2408),
    KIWI(tk("KIWI"), 0xD860A034, 0xFFDDF5C0, 0xFFFFFFFF, 0xFF24400E),
    FUCHSIA(tk("FUCHSIA"), 0xD8C23A86, 0xFFFFC8E6, 0xFFFFFFFF, 0xFF4C0F32),
    /** A grey body, A and B in deep berry, like the first handhelds. */
    RETRO(tk("RETRO"), 0xE0C8C6BE, 0xE04A4A52, 0xFF2A2A30, 0xFFE8E6DE, a = 0xFF8E1F4E, b = 0xFF8E1F4E),
    /** A dark body, A red, B yellow, X blue, Y green. */
    PRIMARY(tk("PRIMARY"), 0xC0202028, 0xB0FFFFFF, 0xFFFFFFFF, 0xFF0C0F15, a = 0xFFD03A30, b = 0xFFE8B820, x = 0xFF3060C8, y = 0xFF2E9A48),
    /** An old console's light grey, every face button its own colour: A red, B blue, X green, Y yellow. */
    OLD_SCHOOL(tk("OLD SCHOOL"), 0xE0CFCDC6, 0xE0585860, 0xFF2A2A30, 0xFFF0EEE8, a = 0xFFD8342C, b = 0xFF2F5FC4, x = 0xFF2E9A48, y = 0xFFE8B820);

    /** [button]'s own colour, or null for the body's. */
    fun accent(button: TouchButton): Long? = when (button) {
        TouchButton.A, TouchButton.TURBO_A -> a
        TouchButton.B, TouchButton.TURBO_B -> b
        TouchButton.X -> x
        TouchButton.Y -> y
        else -> null
    }
}

/** Where the controls sit in their room with the phone upright (the gap beside the companion, or over the game). */
enum class TouchPosition(val label: String) {
    TOP(tk("TOP")),
    MIDDLE(tk("MIDDLE")),
    BOTTOM(tk("BOTTOM")),
}

/** One thing on the touch controls. [gba] = the GBA buttons it holds while touched (0 for the extras). */
enum class TouchButton(val label: String, val gba: Int, val extra: Boolean = false) {
    DPAD(tk("D-PAD"), 0),
    A("A", 1), B("B", 2),
    L("L", 512), R("R", 256),
    START(tk("START"), 8), SELECT(tk("SELECT"), 4),
    /** The DS's own X and Y in a DS game; elsewhere the device's X / Y keys ([TouchControlsStyle.xyButtons]): whatever
     * GAME BUTTONS and HOTKEYS put on them (START 2, SELECT 2, TO COMPANION, a hotkey). */
    X("X", com.pokedaisy.app.MgbaCore.Key.X), Y("Y", com.pokedaisy.app.MgbaCore.Key.Y),
    /** A tap turns FF on / off, held = fast while held. */
    FF(tk("FF"), 0, extra = true),
    /** Opens the QUICK MENU (a phone has no L3). */
    QUICK_MENU(tk("QUICK MENU"), 0, extra = true),
    /** A and B pressed and let go 2 frames on / 2 off while held, like GAME BUTTONS' turbo. */
    TURBO_A(tk("TURBO A"), 1, extra = true),
    TURBO_B(tk("TURBO B"), 2, extra = true),
    SAVE_STATE(tk("SAVE STATE"), 0, extra = true),
    LOAD_STATE(tk("LOAD STATE"), 0, extra = true),
    /** EXPAND's next zoom step (the ZOOM hotkey): a phone has no R3. Drawn only while EXPAND is on. */
    ZOOM(tk("ZOOM"), 0, extra = true),
    /** REWIND while held (REWIND HOLD), in a GBA / Game Boy game (with REWIND off, a press says where to turn it on). */
    REWIND(tk("REWIND"), 0, extra = true);

    /** The short word drawn on the button. */
    val face: String get() = when (this) {
        DPAD -> ""
        QUICK_MENU -> tk("MENU")
        // Two lines in a round button: "TURBO" over the letter.
        TURBO_A -> tk("TURBO") + "\nA"
        TURBO_B -> tk("TURBO") + "\nB"
        SAVE_STATE -> tk("SAVE")
        LOAD_STATE -> tk("LOAD")
        REWIND -> tk("REWIND|button")
        else -> label
    }

    companion object {
        val extras get() = entries.filter { it.extra }
    }
}

/** SIZE: every control's scale (the player's own per-control size multiplies it). */
enum class TouchSize(val label: String, val scale: Float) {
    SMALL(tk("SMALL"), 0.8f),
    NORMAL(tk("NORMAL"), 1f),
    LARGE(tk("LARGE"), 1.2f),
    HUGE(tk("X-LARGE"), 1.4f),
}

/**
 * Where one control sits in a layout the player moved: its centre as a distance from an edge of the controls' area
 * (or its middle), in dp, so a thumb finds it in the same place on another phone or with the status bar on;
 * [scale] = its own size on top of SIZE; [hidden] = taken off in EDIT LAYOUT.
 */
data class TouchPlace(
    val ax: Anchor, val dx: Float,
    val ay: Anchor, val dy: Float,
    val scale: Float = 1f,
    val hidden: Boolean = false,
) {
    /** START = left / top edge, CENTER = the middle, END = right / bottom edge; the distance runs inwards from it. */
    enum class Anchor { START, CENTER, END }
}

/** The player's own layout for one orientation: the controls they moved, resized or hid (the rest follow the preset). */
data class TouchLayout(val places: Map<TouchButton, TouchPlace> = emptyMap()) {
    val isEmpty get() = places.isEmpty()

    /** Stored as `NAME:ax,dx,ay,dy,scale,hidden;...` ([com.pokedaisy.app.Prefs.touchLayoutFor]). */
    fun encode(): String = places.entries.joinToString(";") { (b, p) ->
        "${b.name}:${p.ax.ordinal},${"%.1f".format(java.util.Locale.ROOT, p.dx)},${p.ay.ordinal},${"%.1f".format(java.util.Locale.ROOT, p.dy)}," +
            "${"%.2f".format(java.util.Locale.ROOT, p.scale)},${if (p.hidden) 1 else 0}"
    }

    companion object {
        fun decode(s: String?): TouchLayout {
            if (s.isNullOrBlank()) return TouchLayout()
            val anchors = TouchPlace.Anchor.entries
            return TouchLayout(s.split(';').mapNotNull { part ->
                val (name, v) = part.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                val b = TouchButton.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                val f = v.split(',')
                if (f.size < 6) return@mapNotNull null
                runCatching {
                    b to TouchPlace(
                        anchors[f[0].toInt()], f[1].toFloat(), anchors[f[2].toInt()], f[3].toFloat(),
                        f[4].toFloat().coerceIn(MIN_SCALE, MAX_SCALE), f[5] == "1",
                    )
                }.getOrNull()
            }.toMap())
        }

        /** A control's own size in EDIT LAYOUT: from half to twice SIZE's. */
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2f
    }
}

/** The player's choices for the touch controls (SETTINGS > TOUCH CONTROLS). */
data class TouchControlsStyle(
    val show: TouchShow = TouchShow.AUTO,
    val preset: TouchPreset = TouchPreset.SIDES,
    val size: TouchSize = TouchSize.NORMAL,
    /** How the buttons are drawn, and in which colours. */
    val look: TouchLook = TouchLook.GLASS,
    val colors: TouchColors = TouchColors.DARK,
    /** SKIN: the imported Delta skin used for GBA games and for Game Boy games (its folder, [InstalledSkin.id]), or
     * null for the drawn buttons. */
    val skinGba: String? = null,
    val skinGb: String? = null,
    /** How solid the controls are: 25 / 50 / 75 / 100 %. */
    val opacity: Int = 50,
    /** The extras switched on ([TouchButton.extra]): [DEFAULT_EXTRAS] to start with. */
    val extras: Set<TouchButton> = DEFAULT_EXTRAS,
    /** X and Y in a GBA / Game Boy game too (the device's X / Y keys, [TouchButton.X]); a DS game always has its own. */
    val xyButtons: Boolean = true,
    /** Upright, where the preset's buttons sit in their room (a CUSTOM layout keeps its own places). */
    val position: TouchPosition = TouchPosition.MIDDLE,
    /** Off = the D-pad presses one direction at a time (no diagonals). */
    val eightWay: Boolean = true,
    /** The controls fade to a trace after a few seconds without a touch, and come back on the next one. */
    val fadeIdle: Boolean = false,
    /** VIBRATION: a buzz on each press (off to start with), at [vibrationStrength]. */
    val vibrate: Boolean = false,
    /** STRENGTH, kept while VIBRATION is off: 1 LIGHT, 2 MEDIUM, 3 STRONG. */
    val vibrationStrength: Int = 2,
    /** LAYOUT is CUSTOM: EDIT LAYOUT's work below is used (else it's kept for later, and [preset] alone counts). */
    val useCustom: Boolean = false,
    /** EDIT LAYOUT's work for a phone upright and held sideways (the controls not in it follow [preset]). */
    val portrait: TouchLayout = TouchLayout(),
    val landscape: TouchLayout = TouchLayout(),
) {
    /** The buzz a press gets: 0 none, else [vibrationStrength] (com.pokedaisy.app.TouchHaptics). */
    val vibration: Int get() = if (vibrate) vibrationStrength.coerceIn(1, 3) else 0

    /** SKIN's pick for a game of [system] (DS: none yet). */
    fun skinFor(system: SkinSystem): String? = when (system) {
        SkinSystem.GBA -> skinGba
        SkinSystem.GB -> skinGb
        SkinSystem.DS -> null
    }
    fun withSkin(system: SkinSystem, id: String?) = when (system) {
        SkinSystem.GBA -> copy(skinGba = id)
        SkinSystem.GB -> copy(skinGb = id)
        SkinSystem.DS -> this
    }

    /** The moved controls in use for this orientation: none while LAYOUT is a preset. */
    fun layoutFor(portrait: Boolean) = if (!useCustom) TouchLayout() else if (portrait) this.portrait else landscape
    /** EDIT LAYOUT's DONE: [layout] for this orientation, and CUSTOM in use. */
    fun withLayout(portrait: Boolean, layout: TouchLayout) =
        (if (portrait) copy(portrait = layout) else copy(landscape = layout)).copy(useCustom = true)
    /** Whether EDIT LAYOUT has saved anything (CUSTOM can be picked). */
    val hasCustom get() = !portrait.isEmpty || !landscape.isEmpty

    /** What a control-by-control layout is built from: the GBA buttons and the extras switched on. */
    /** The buttons on screen for a game: [unavailable] are extras switched on that do nothing in it now (ZOOM without
     * EXPAND, REWIND with REWIND off), left off rather than drawn dead. */
    fun shown(gameBoy: Boolean, ds: Boolean = false, unavailable: Set<TouchButton> = emptySet()): List<TouchButton> = TouchButton.entries.filter { b ->
        when {
            b.extra -> b in extras && b !in unavailable
            gameBoy && (b == TouchButton.L || b == TouchButton.R) -> false
            !ds && !xyButtons && (b == TouchButton.X || b == TouchButton.Y) -> false
            else -> true
        }
    }

    companion object {
        val OPACITIES = listOf(25, 50, 75, 100)
        /** FF and the QUICK MENU to start with (a phone has no other way to open it), ZOOM and REWIND (no R3, no hotkey). */
        val DEFAULT_EXTRAS = setOf(TouchButton.FF, TouchButton.QUICK_MENU, TouchButton.ZOOM, TouchButton.REWIND)
    }
}

/**
 * The game screen as the touch controls see it, in px: its size, the game's picture, the companion (a phone held
 * upright) and the area the controls lie in. The game's activity measures the real one; SETTINGS (no game open)
 * builds one with companion.ui.simulateTouchStage the way the game's screen would be laid out.
 */
data class TouchStage(
    val w: Float, val h: Float, val dp: Float,
    val portrait: Boolean,
    val game: Rect,
    val area: Rect,
    val companion: Rect? = null,
    val gameAspect: Float = 1.5f,
    val gameBoy: Boolean = false,
    /** A DS game: X and Y join A and B. */
    val ds: Boolean = false,
)
