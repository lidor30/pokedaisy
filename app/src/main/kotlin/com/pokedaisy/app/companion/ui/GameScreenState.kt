package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/** The upright companion's saved shape and the host's current screen capabilities. */
data class PortraitPanelState(
    val enabled: Boolean = false,
    val ratio: Float = PortraitLayout.DEFAULT_RATIO,
    val hidden: Boolean = false,
    val underGame: Boolean = true,
    val expand: Boolean = false,
    val padStrip: Int = 0,
    val hinge: IntRange? = null,
    val canHide: Boolean = true,
)

/**
 * Shared screen interaction state. Hosts supply storage and manual-input ownership; Compose owns the panels,
 * their grips and the space left for the native renderer and controls. No game frames pass through this state.
 */
class GameScreenState(
    side: SidePanelState = SidePanelState(),
    portrait: PortraitPanelState = PortraitPanelState(),
    private val saveSide: (SidePanelState) -> Unit = {},
    private val savePortrait: (PortraitPanelState) -> Unit = {},
    private val windowChanged: (Boolean) -> Unit = {},
    private val companionHidden: () -> Unit = {},
) {
    /** One installed preference contract for both hosts, including Android's separate DS companion ratio. */
    constructor(prefs: com.pokedaisy.app.Prefs, windowChanged: (Boolean) -> Unit = {}, companionHidden: () -> Unit = {}) : this(
        side = SidePanelState(window = prefs.companionWindow, docked = prefs.sidePanelDocked, widthFraction = prefs.sidePanelWidth),
        portrait = PortraitPanelState(ratio = prefs.portraitCompanionRatio, hidden = prefs.portraitCompanionHidden,
            underGame = prefs.portraitCompanionUnderGame),
        saveSide = { prefs.sidePanelWidth = it.widthFraction; prefs.sidePanelDocked = it.docked },
        savePortrait = {
            if (it.canHide) { prefs.portraitCompanionRatio = it.ratio; prefs.portraitCompanionHidden = it.hidden }
            else prefs.portraitDsRatio = it.ratio
        },
        windowChanged = windowChanged, companionHidden = companionHidden,
    )

    var side by mutableStateOf(side)
        private set
    var portrait by mutableStateOf(portrait)
        private set
    var openTabShown by mutableStateOf(true)
    var tabInHud by mutableStateOf(false)
    var tabRaised by mutableStateOf(false)

    // Updated by measurement, read only by event handlers. They do not trigger a second composition/layout pass.
    var width: Int = 0
        internal set
    var height: Int = 0
        internal set
    var gameHeight: Int = 0
        internal set
    var gripHeight: Int = 0
        internal set
    private var dragStart = 0
    private var dragWidth = 0
    private var dragHeight = 0
    internal var gripPlacement by mutableStateOf(PortraitLayout.Grip.ABOVE)

    fun setSide(value: SidePanelState, save: Boolean = false) {
        val owned = side.windowOpen
        val wasShown = side.shown
        side = value
        if (owned != value.windowOpen) windowChanged(value.windowOpen)
        if (wasShown && !value.shown) companionHidden()
        if (save) saveSide(value)
    }

    fun setPortrait(value: PortraitPanelState, save: Boolean = false) {
        val wasShown = portrait.enabled && (!portrait.hidden || !portrait.canHide)
        portrait = value
        if (wasShown && (!value.enabled || value.hidden && value.canHide)) companionHidden()
        if (save) savePortrait(value)
    }

    fun show() = setSide(side.show())
    fun hide() = setSide(side.hide())
    fun toggleDock() = setSide(side.copy(docked = !side.docked), save = true)

    internal fun startSideDrag() { dragStart = side.arrange(width).panelWidth; dragWidth = width; dragHeight = height }
    internal fun dragSide(dx: Float) = setSide(side.resized(dragStart, dx, dragWidth))
    internal fun finishSideDrag() = setSide(side.snapped(dragWidth, dragHeight), save = true)

    private fun upright() = PortraitLayout.arrange(width, height, gameHeight, gripHeight, portrait.ratio,
        portrait.underGame, portrait.expand, portrait.padStrip, portrait.hidden && portrait.canHide)
    private fun reserve() = gripHeight + if (portrait.underGame && portrait.padStrip > 0) minOf(portrait.padStrip, height / 3) else 0

    fun togglePortrait() = setPortrait(portrait.copy(hidden = !portrait.hidden), save = true)
    internal fun stepPortrait() {
        if (portrait.hinge != null || width <= 0) return
        if (portrait.hidden && portrait.canHide) {
            setPortrait(portrait.copy(hidden = false), save = true)
            return
        }
        val now = upright().companionHeight.toFloat() / width
        val next = PortraitLayout.next(width, height, gameHeight, now, reserve())
        setPortrait(portrait.copy(ratio = next, hidden = portrait.canHide && next < now), save = true)
    }
    internal fun startPortraitDrag() {
        dragStart = upright().companionHeight
        dragWidth = width; dragHeight = height
        if (portrait.hidden && portrait.canHide) setPortrait(portrait.copy(hidden = false))
    }
    internal fun dragPortrait(dy: Float) {
        if (portrait.hinge != null || width <= 0) return
        val direction = if (portrait.underGame && !portrait.expand) 1 else -1
        setPortrait(portrait.copy(ratio = ((dragStart + direction * dy) / dragWidth.coerceAtLeast(1)).coerceAtLeast(0f)))
    }
    internal fun finishPortraitDrag() {
        if (portrait.hinge != null || width <= 0) return
        val ratio = if (width != dragWidth || height != dragHeight) portrait.ratio else
            PortraitLayout.snap(width, height, gameHeight, upright().companionHeight.toFloat() / width, reserve())
        setPortrait(portrait.copy(ratio = ratio), save = true)
    }
}

/** Renderer/control viewports, not the native renderer's fitted picture. All rectangles are screen-local pixels. */
data class GameScreenFrames(
    val game: Rect, val controls: Rect, val companion: Rect,
    val companionShown: Boolean, val portrait: PortraitLayout.Arrangement? = null,
)

fun gameScreenFrames(width: Int, height: Int, gameHeight: Int, gripHeight: Int,
                     side: SidePanelState, portrait: PortraitPanelState): GameScreenFrames {
    val w = width.coerceAtLeast(0)
    val h = height.coerceAtLeast(0)
    fun rect(top: Int, bottom: Int, right: Int = w, left: Int = 0) = Rect(
        left.coerceIn(0, w).toFloat(), top.coerceIn(0, h).toFloat(),
        right.coerceIn(left.coerceIn(0, w), w).toFloat(), bottom.coerceIn(top.coerceIn(0, h), h).toFloat())
    if (portrait.enabled) {
        portrait.hinge?.let {
            return GameScreenFrames(rect(0, it.first), rect(0, it.first), rect(it.last, h), true)
        }
        val hidden = portrait.hidden && portrait.canHide
        val a = PortraitLayout.arrange(w, h, gameHeight, gripHeight, portrait.ratio,
            portrait.underGame, portrait.expand, portrait.padStrip, hidden)
        // Keep a hidden companion measured at its saved positive size so its navigation and scroll state survive.
        val visible = if (hidden) PortraitLayout.arrange(w, h, gameHeight, gripHeight, portrait.ratio,
            portrait.underGame, portrait.expand, portrait.padStrip) else a
        return GameScreenFrames(rect(0, a.gameAreaBottom), rect(a.padTop, a.padBottom),
            rect(visible.companionTop, visible.companionTop + visible.companionHeight), !hidden, a)
    }
    val a = side.arrange(w)
    val panel = if (side.isWindow) rect(0, h) else rect(0, h, left = w - a.panelWidth)
    return GameScreenFrames(rect(0, h, w - a.gameRightMargin), rect(0, h, w - a.controlsRightMargin),
        panel, side.shown)
}
