package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A phone held upright (one screen, taller than wide): the game across the top,
 * the companion under it ([com.pokedaisy.app.PortraitPanel]), as wide as the
 * screen and as tall as the player picks - a height / width ratio, so it keeps
 * its shape across phones - from [MIN_RATIO] to everything under the game. It
 * sits along the screen's bottom, or right under the game ([arrange]'s
 * `underGame`) so the touch controls get the screen's bottom instead. What's left
 * is the touch controls' when it's shown and fits there ([padInGap]); else black.
 */
object PortraitLayout {
    /** The Thor's bottom screen (1240x1080), the shape the companion is made for. */
    const val DEFAULT_RATIO = 1080f / 1240f

    /** Shorter than the Thor's top screen's 16:9 squeezes the tabs. */
    const val MIN_RATIO = 0.6f

    /** A drag that ends this close to the default or the largest size lands on it. */
    const val SNAP_PX = 48

    /** The touch controls go in the gap beside the companion once that's at least this much of the width. */
    private const val PAD_GAP_RATIO = 0.45f

    /**
     * The companion's height on a [w]x[h] screen whose game (status bar included)
     * is [gameH] tall, with [reserve] px kept free beside it (the grip's strip).
     */
    fun companionHeight(w: Int, h: Int, gameH: Int, ratio: Float, reserve: Int = 0): Int {
        val lo = minOf((w * MIN_RATIO).roundToInt(), h / 2)
        return (w * ratio).roundToInt().coerceIn(lo, maxOf(lo, h - gameH - reserve))
    }

    /** The largest companion: everything under the game (but [reserve]), as a ratio. */
    fun maxRatio(w: Int, h: Int, gameH: Int, reserve: Int = 0): Float = maxOf(MIN_RATIO, (h - gameH - reserve).toFloat() / w)

    /** Where a drag to [ratio] settles: on the default or the largest size when it's within [SNAP_PX] of it. */
    fun snap(w: Int, h: Int, gameH: Int, ratio: Float, reserve: Int = 0): Float {
        val max = maxRatio(w, h, gameH, reserve)
        return when {
            abs((ratio - DEFAULT_RATIO) * w) <= SNAP_PX -> DEFAULT_RATIO
            (max - ratio) * w <= SNAP_PX -> max
            else -> ratio.coerceIn(MIN_RATIO, max)
        }
    }

    /** A tap on the grip: smallest, the Thor's shape, everything under the game, round again ([PortraitPanel] hides it between). */
    fun next(w: Int, h: Int, gameH: Int, ratio: Float, reserve: Int = 0): Float {
        val max = maxRatio(w, h, gameH, reserve)
        val stops = listOf(MIN_RATIO, DEFAULT_RATIO, max).filter { it <= max }.distinct().sorted()
        return stops.firstOrNull { it > ratio + 0.01f } ?: stops.first()
    }

    /** The touch controls fit in a [gap]-tall strip on a [w]-wide screen. */
    fun padInGap(w: Int, gap: Int): Boolean = gap >= w * PAD_GAP_RATIO

    /** Where the grip goes: on the companion's top edge, inside its top (no room above), or under it. */
    enum class Grip { ABOVE, INSIDE, BELOW }

    /** Where everything goes, in px from the screen's top. */
    data class Arrangement(
        /** The game (with its status bar) ends here; the game's area ends at [gameAreaBottom]. */
        val gameBottom: Int,
        val gameAreaBottom: Int,
        val companionTop: Int,
        val companionHeight: Int,
        val grip: Grip,
        val gripTop: Int,
        /** The touch controls' strip. */
        val padTop: Int,
        val padBottom: Int,
    ) {
        /** Kept clear at the companion's top for an [Grip.INSIDE] grip. */
        fun topInset(gripH: Int) = if (grip == Grip.INSIDE) gripH else 0
    }

    /**
     * Lays the screen out. The grip always has a strip of its own, so it never
     * takes the companion's room: along the bottom it sits on the companion's top
     * edge (dragging it up makes the companion taller), right under the game
     * ([underGame]) it hangs below the companion (dragging it down does). Only a
     * screen too short for even the smallest companion and the grip puts it inside
     * the companion's top; it never covers the game. (Inside, it pushed the
     * companion's content down at its largest size: with ASPECT > WIDE, which is
     * always along the bottom, a Galaxy Z Fold's battle move cards lost their
     * PWR / PP line - GitHub #136.)
     */
    fun arrange(w: Int, h: Int, gameH: Int, gripH: Int, ratio: Float, underGame: Boolean, expand: Boolean = false, padStrip: Int = 0, hidden: Boolean = false): Arrangement {
        // Hidden: no companion, only its grip along the bottom (over the touch controls' own strip, if they have one).
        if (hidden) {
            val strip = if (expand && underGame && padStrip > 0) minOf(padStrip, h / 3) else 0
            val gripTop = h - strip - gripH
            val gameBottom = minOf(gameH, gripTop)
            val inGap = !expand && padInGap(w, gripTop - gameBottom)
            val padTop = if (strip > 0) h - strip else if (inGap) gameBottom else 0
            return Arrangement(gameBottom, gripTop, h - strip, 0, Grip.ABOVE, gripTop, padTop, if (strip > 0) h else gripTop)
        }
        // WIDE ([expand]): the game takes everything above the companion, so right under the game and along the
        // bottom would be the same place. UNDER GAME there gives the touch controls a strip of their own along the
        // screen's bottom instead ([padStrip] tall, 0 = no touch controls: the same as along the bottom), the
        // companion above it and the game above that; along the bottom they lie over the game.
        if (expand && underGame && padStrip > 0) {
            val strip = minOf(padStrip, h / 3)
            val c = companionHeight(w, h, gameH, ratio, gripH + strip)
            val stripTop = h - strip
            val companionTop = stripTop - c
            val gripTop = maxOf(0, companionTop - gripH)
            return Arrangement(gripTop, gripTop, companionTop, c, Grip.ABOVE, gripTop, stripTop, h)
        }
        @Suppress("NAME_SHADOWING") val underGame = underGame && !expand
        if (underGame) {
            // The controls' strip ([padStrip], when they're shown) is kept free at the bottom whatever's above it: on a
            // screen with no gap left under the companion (a tablet, a DS game's 4:3) they lay over the game instead.
            val strip = if (padStrip > 0) minOf(padStrip, h / 3) else 0
            val c = companionHeight(w, h, gameH, ratio, gripH + strip)
            val gameBottom = minOf(gameH, h - c - gripH - strip)
            val gripTop = gameBottom + c
            val below = gripTop + gripH
            val inGap = strip > 0 || padInGap(w, h - below)
            return Arrangement(
                gameBottom, gameBottom, gameBottom, c, Grip.BELOW, gripTop,
                if (inGap) below else 0, if (inGap) h else gameBottom,
            )
        }
        val c = companionHeight(w, h, gameH, ratio, gripH)
        val companionTop = h - c
        val gameBottom = minOf(gameH, companionTop)
        val inside = companionTop - gameBottom < gripH
        val gripTop = if (inside) companionTop else companionTop - gripH
        val inGap = !expand && padInGap(w, gripTop - gameBottom)
        return Arrangement(
            gameBottom, gripTop, companionTop, c, if (inside) Grip.INSIDE else Grip.ABOVE, gripTop,
            if (inGap) gameBottom else 0, gripTop,
        )
    }
}

/** SETTINGS > COMPANION's value with a phone upright: where the companion sits. */
fun portraitPlaceLabel(underGame: Boolean) = if (underGame) tk("UNDER GAME") else tk("AT BOTTOM")

/**
 * The px [CompanionScreen] keeps clear at its top (inside its backdrop): where the
 * portrait companion's grip sits when there's no room for it above.
 */
val LocalCompanionTopInset = compositionLocalOf { 0 }

/**
 * The grip on the portrait companion: a white title-window tab with arrows up and
 * down, running into the companion from above ([PortraitLayout.Grip.ABOVE]) or
 * below ([PortraitLayout.Grip.BELOW]), or whole inside its top. Dragging it
 * (handled by the view around it, in screen coordinates) resizes the companion; a
 * tap steps through the sizes ([PortraitLayout.next]).
 */
@Composable
fun PortraitGrip(grip: PortraitLayout.Grip = PortraitLayout.Grip.ABOVE, onClick: () -> Unit) {
    val m = rememberGbaTextMetrics()
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(u * 48, u * 24)
            .drawBehind {
                drawLayeredBox(
                    OptionColors.titleLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 3 * u.toPx(),
                    openBottom = grip == PortraitLayout.Grip.ABOVE, openTop = grip == PortraitLayout.Grip.BELOW,
                )
            }
            .semantics { contentDescription = tr("Resize the companion") }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick),
    ) {
        ShadowedPixelIcon(PixelIcons.resizeVertical, OptionColors.label, OptionColors.labelShadow, m)
    }
}
