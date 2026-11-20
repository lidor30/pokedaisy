package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.i18n.tk
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SETTINGS > DS LAYOUT ([com.pokedaisy.app.Prefs.dsLayout]): where a DS game's two screens go on one screen held
 * sideways. ONE SCREEN (the default) gives the game the whole screen (EXPAND's view where ASPECT says so) and floats the
 * touch screen in over it whenever the game wants it there (PokeDaisyActivity's DS touch window); the others lay both
 * screens out side by side, one large and one small, one inset in the other's corner or one over the other, the game's
 * own backdrop around them (never black bars). Held upright, the game sits on top with its touch screen under it
 * whatever this says (PortraitPanel).
 */
enum class DsLayout(val id: Int, val label: String, val subtitle: String) {
    ONE_SCREEN(0, tk("ONE SCREEN"), tk("The game fills the screen, the touch screen floats in")),
    SIDE_BY_SIDE(1, tk("SIDE BY SIDE"), tk("Both screens at the same size")),
    LARGE_SMALL(2, tk("LARGE + SMALL"), tk("The game large, the touch screen beside it")),
    PICTURE_IN_PICTURE(3, tk("PICTURE IN PICTURE"), tk("The touch screen in the game's corner")),
    STACKED(4, tk("STACKED"), tk("The touch screen under the game")),
    ;

    /** The touch screen floats in over the game when it's wanted (it isn't on screen, or only small). */
    val floats get() = this == ONE_SCREEN || this == PICTURE_IN_PICTURE

    /** What this layout is on a game whose touch-screen moments aren't followed ([followed] false): ONE SCREEN can't
     * float the touch screen in by itself there, so it keeps it small in the corner (PICTURE IN PICTURE). */
    fun forGame(followed: Boolean) = if (this == ONE_SCREEN && !followed) PICTURE_IN_PICTURE else this

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: ONE_SCREEN

        /** The DS games whose touch-screen moments are followed (nds/pk_nds_field.cpp's touch rules): HeartGold and
         * SoulSilver (USA, Germany, France), Platinum (USA rev 1, France), Black, White (both also France), Black 2 and
         * White 2, and the language ports whose menu functions are known ([com.pokedaisy.app.companion.data.NdsPort.followed]). */
        fun followed(gameCode: String) =
            gameCode in FOLLOWED || com.pokedaisy.app.companion.data.NDS_PORTS.any { it.code == gameCode && it.followed }

        private val FOLLOWED =
            setOf("IPKE", "IPKD", "IPKF", "IPGE", "IPGD", "IPGF", "CPUE", "CPUF", "IRBO", "IRBF", "IRAO", "IRAF", "IREO", "IRDO")
    }
}

/** A rectangle in whole pixels (the views' layout, the picker's miniatures). */
data class DsBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Where the top screen (the game) and the touch screen go: [touch] null = not on screen (it floats in when wanted). */
data class DsArrangement(val top: DsBox, val touch: DsBox?)

object DsLayouts {
    /** A DS screen's shape (256 x 192). */
    const val ASPECT = 4f / 3f

    /** A 3DS's screens: the top one 400 x 240 (5:3), the touch screen 320 x 240 (4:3). */
    const val N3DS_TOP_ASPECT = 5f / 3f

    /**
     * [layout] in a [w] x [h] area. [fill]: the game takes the whole area where it's alone on it (ASPECT's EXPAND or
     * STRETCH), else its own shape, centred. The two-screen layouts keep both at their shapes, centred as a pair.
     * [topAspect] / [touchAspect]: the screens' shapes (a DS's both 4:3; a 3DS's top screen [N3DS_TOP_ASPECT]).
     */
    fun arrange(
        layout: DsLayout, w: Int, h: Int, fill: Boolean,
        topAspect: Float = ASPECT, touchAspect: Float = ASPECT,
    ): DsArrangement {
        val alone = if (fill) DsBox(0, 0, w, h) else fit(w, h, 0, 0, topAspect)
        return when (layout) {
            DsLayout.ONE_SCREEN -> DsArrangement(alone, null)
            DsLayout.SIDE_BY_SIDE -> {
                // Two at one height, edge to edge (one size on a DS).
                val sh = min(h.toFloat(), w / (topAspect + touchAspect))
                val tw = sh * topAspect
                val sw = sh * touchAspect
                val x = (w - tw - sw) / 2f
                val y = (h - sh) / 2f
                DsArrangement(box(x, y, tw, sh), box(x + tw, y, sw, sh))
            }
            DsLayout.LARGE_SMALL -> {
                // The game at the full height where it leaves the small screen at least a quarter of its width (a 16:9
                // screen: a third, beside it); the small one as wide as what's left, at most half the large one,
                // level with its bottom edge as the DS's touch screen sits under the top one.
                val lw = min(h * topAspect, w / (1f + SMALL_MIN))
                val lh = lw / topAspect
                val sw = min(w - lw, lw * SMALL_MAX)
                val sh = sw / touchAspect
                val x = (w - lw - sw) / 2f
                val y = (h - lh) / 2f
                DsArrangement(box(x, y, lw, lh), box(x + lw, y + lh - sh, sw, sh))
            }
            DsLayout.PICTURE_IN_PICTURE -> {
                // The game as on its own; the touch screen in the bottom right corner of the screen, a margin in (the games
                // put a new area's name top left; the HUD moves its buttons to the bottom left for it: FieldHud's dsCorner).
                val sw = min(w * PIP_SIZE, h * PIP_SIZE * 1.6f * touchAspect)
                val sh = sw / touchAspect
                val margin = h * PIP_MARGIN
                DsArrangement(alone, box(w - margin - sw, h - margin - sh, sw, sh))
            }
            DsLayout.STACKED -> {
                // The DS held as it is: the game over its touch screen at one height (one size on a DS; a 3DS's touch
                // screen narrower, centred under the top one).
                val th = min(h / 2f, w / topAspect)
                val tw = th * topAspect
                val sw = th * touchAspect
                val x = (w - tw) / 2f
                val y = (h - th * 2) / 2f
                DsArrangement(box(x, y, tw, th), box(x + (tw - sw) / 2f, y + th, sw, th))
            }
        }
    }

    /** The largest box of [aspect] (a DS screen's 4:3) in [w] x [h], centred, offset by ([x], [y]). */
    fun fit(w: Int, h: Int, x: Int, y: Int, aspect: Float = ASPECT): DsBox {
        val bw = min(w.toFloat(), h * aspect)
        val bh = bw / aspect
        return box(x + (w - bw) / 2f, y + (h - bh) / 2f, bw, bh)
    }

    private fun box(x: Float, y: Float, w: Float, h: Float) =
        DsBox(x.roundToInt(), y.roundToInt(), (x + w).roundToInt(), (y + h).roundToInt())

    /** LARGE + SMALL: the small screen at least / at most this share of the large one's width. */
    private const val SMALL_MIN = 0.25f
    private const val SMALL_MAX = 0.5f

    /** PICTURE IN PICTURE: the inset's width (a share of the screen's) and its margin (of the height). */
    private const val PIP_SIZE = 0.24f
    private const val PIP_MARGIN = 0.03f
}
