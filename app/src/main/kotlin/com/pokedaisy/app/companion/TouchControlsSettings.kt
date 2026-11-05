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
    /** The DS's own X and Y, so only shown in a DS game. */
    X("X", com.pokedaisy.app.MgbaCore.Key.X), Y("Y", com.pokedaisy.app.MgbaCore.Key.Y),
    /** A tap turns FF on / off, held = fast while held. */
    FF(tk("FF"), 0, extra = true),
    /** Opens the QUICK MENU (a phone has no L3). */
    QUICK_MENU(tk("QUICK MENU"), 0, extra = true),
    /** A and B pressed and let go 2 frames on / 2 off while held, like GAME BUTTONS' turbo. */
    TURBO_A(tk("TURBO A"), 1, extra = true),
    TURBO_B(tk("TURBO B"), 2, extra = true),
    SAVE_STATE(tk("SAVE STATE"), 0, extra = true),
    LOAD_STATE(tk("LOAD STATE"), 0, extra = true);

    /** The short word drawn on the button. */
    val face: String get() = when (this) {
        DPAD -> ""
        QUICK_MENU -> tk("MENU")
        // Two lines in a round button: "TURBO" over the letter.
        TURBO_A -> tk("TURBO") + "\nA"
        TURBO_B -> tk("TURBO") + "\nB"
        SAVE_STATE -> tk("SAVE")
        LOAD_STATE -> tk("LOAD")
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
    /** How solid the controls are: 25 / 50 / 75 / 100 %. */
    val opacity: Int = 50,
    /** The extras switched on ([TouchButton.extra]). FF and the QUICK MENU to start with: a phone has no other way to
     * open the QUICK MENU, and FF is what a player reaches for most. */
    val extras: Set<TouchButton> = setOf(TouchButton.FF, TouchButton.QUICK_MENU),
    /** Upright, where the preset's buttons sit in their room (a CUSTOM layout keeps its own places). */
    val position: TouchPosition = TouchPosition.MIDDLE,
    /** Off = the D-pad presses one direction at a time (no diagonals). */
    val eightWay: Boolean = true,
    /** The controls fade to a trace after a few seconds without a touch, and come back on the next one. */
    val fadeIdle: Boolean = false,
    /** Vibration on each press: 0 off, 1 the phone's own light tick, 2 / 3 a stronger buzz (VIBRATION_NAMES). */
    val vibration: Int = 0,
    /** LAYOUT is CUSTOM: EDIT LAYOUT's work below is used (else it's kept for later, and [preset] alone counts). */
    val useCustom: Boolean = false,
    /** EDIT LAYOUT's work for a phone upright and held sideways (the controls not in it follow [preset]). */
    val portrait: TouchLayout = TouchLayout(),
    val landscape: TouchLayout = TouchLayout(),
) {
    /** The moved controls in use for this orientation: none while LAYOUT is a preset. */
    fun layoutFor(portrait: Boolean) = if (!useCustom) TouchLayout() else if (portrait) this.portrait else landscape
    /** EDIT LAYOUT's DONE: [layout] for this orientation, and CUSTOM in use. */
    fun withLayout(portrait: Boolean, layout: TouchLayout) =
        (if (portrait) copy(portrait = layout) else copy(landscape = layout)).copy(useCustom = true)
    /** Whether EDIT LAYOUT has saved anything (CUSTOM can be picked). */
    val hasCustom get() = !portrait.isEmpty || !landscape.isEmpty

    /** What a control-by-control layout is built from: the GBA buttons and the extras switched on. */
    fun shown(gameBoy: Boolean, ds: Boolean = false): List<TouchButton> = TouchButton.entries.filter { b ->
        when {
            b.extra -> b in extras
            gameBoy && (b == TouchButton.L || b == TouchButton.R) -> false
            !ds && (b == TouchButton.X || b == TouchButton.Y) -> false
            else -> true
        }
    }

    companion object {
        val OPACITIES = listOf(25, 50, 75, 100)
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
