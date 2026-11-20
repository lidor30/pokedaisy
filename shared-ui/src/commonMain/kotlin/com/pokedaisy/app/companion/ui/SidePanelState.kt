package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.SIDE_PANEL_DEFAULT_WIDTH
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The one-screen companion's state and layout rules, shared by the Android view host and [GameScreen].
 * Hosts keep preferences, animation, focus and input dispatch; this value decides what is open and whose area it uses.
 * A book hinge overrides window/dock preferences without replacing them, so unfolding restores the player's choice.
 */
data class SidePanelState(
    val enabled: Boolean = false,
    val open: Boolean = false,
    val window: Boolean = true,
    val docked: Boolean = false,
    val fullScreen: Boolean = false,
    val book: IntRange? = null,
    val widthFraction: Float = SIDE_PANEL_DEFAULT_WIDTH,
) {
    val isWindow: Boolean get() = (window || fullScreen) && book == null
    val isDocked: Boolean get() = docked || book != null
    val shown: Boolean get() = enabled && open
    val sideShown: Boolean get() = shown && !isWindow
    val tabsShown: Boolean get() = sideShown && book == null
    val closed: Boolean get() = enabled && !open
    /** Game buttons belong to the companion only while its window is open. */
    val windowOpen: Boolean get() = shown && isWindow
    /** Native touch-input windows must step aside for an overlay, including an unlocked side panel. */
    val overGame: Boolean get() = shown && (isWindow || !isDocked)
    private val initiallyOpen: Boolean get() = book != null || (!isWindow && isDocked)

    /** Enabling reads the host's current preferences; disabling releases its game and controls areas. */
    fun withEnabled(on: Boolean, window: Boolean = this.window, docked: Boolean = this.docked): SidePanelState {
        if (on == enabled) return this
        if (!on) return copy(enabled = false, open = false)
        val next = copy(enabled = true, window = window, docked = docked)
        return next.copy(open = next.initiallyOpen)
    }

    fun withBook(hinge: IntRange?): SidePanelState {
        if (book == hinge) return this
        val next = copy(book = hinge)
        return if (enabled) next.copy(open = next.initiallyOpen) else next
    }

    fun withFullScreen(on: Boolean): SidePanelState {
        if (fullScreen == on) return this
        val next = copy(fullScreen = on)
        return if (enabled) next.copy(open = next.initiallyOpen) else next
    }

    fun show(): SidePanelState = if (open) this else copy(open = true)
    fun hide(): SidePanelState = if (!open || book != null) this else copy(open = false)

    data class Arrangement(val panelWidth: Int, val gameRightMargin: Int, val controlsRightMargin: Int)

    /** The game shrinks only for a docked panel; touch controls stay clear of every open side panel. */
    fun arrange(width: Int): Arrangement {
        val w = width.coerceAtLeast(0)
        val panel = book?.let { (w - it.last).coerceIn(0, w) }
            ?: (w * widthFraction.coerceIn(MIN_FRACTION, MAX_FRACTION)).roundToInt()
        val edge = book?.let { (w - it.first).coerceIn(0, w) } ?: panel
        return Arrangement(panel, if (sideShown && isDocked) edge else 0, if (sideShown) edge else 0)
    }

    fun resized(startWidth: Int, dx: Float, width: Int): SidePanelState {
        if (book != null || width <= 0) return this
        return copy(widthFraction = ((startWidth - dx) / width).coerceIn(MIN_FRACTION, MAX_FRACTION))
    }

    /** A nearby whole-number GBA scale wins when it fits the screen's height (Android's existing drag rule). */
    fun snapped(width: Int, height: Int): SidePanelState {
        if (book != null || width <= 0) return this
        val area = width - arrange(width).panelWidth
        val scale = (area / 240f).roundToInt()
        return if (scale >= 1 && 160 * scale <= height && abs(240 * scale - area) <= 48) {
            copy(widthFraction = ((width - 240 * scale).toFloat() / width).coerceIn(MIN_FRACTION, MAX_FRACTION))
        } else this
    }

    companion object {
        const val MIN_FRACTION = 0.3f
        const val MAX_FRACTION = 0.75f
    }
}
