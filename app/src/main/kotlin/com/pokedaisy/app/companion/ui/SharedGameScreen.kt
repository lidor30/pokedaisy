package com.pokedaisy.app.companion.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * One screen assembly for Android and iOS: stable native renderer and input slots, shared panel geometry/chrome.
 * A hidden companion stays composed and measured, but is not placed or hit-tested. Native hosts clear its focus
 * when they release manual-input ownership. Screen overlays (FPS, key capture) remain above the companion.
 */
@Composable
fun GameScreen(
    state: GameScreenState, gameHeight: (Int) -> Int, modifier: Modifier = Modifier,
    game: @Composable () -> Unit, controls: @Composable () -> Unit,
    companion: @Composable () -> Unit,
    gameUnderlay: @Composable () -> Unit = {}, gameOverlay: @Composable () -> Unit = {},
    screenOverlay: @Composable () -> Unit = {}, pictureOverlay: @Composable () -> Unit = {},
    overlayRect: () -> Rect? = { null },
    screenOverlayTop: () -> Int = { 0 },
    dismissSideOnGameTap: Boolean = false, chromeModifier: Modifier = Modifier,
    onFrames: (GameScreenFrames, List<Rect>) -> Unit = { _, _ -> },
) {
    val side = state.side
    val portrait = state.portrait
    val reveal by animateFloatAsState(if (side.shown) 1f else 0f,
        tween(if (side.isWindow) 120 else 160), label = "companionVisibility")
    val sideTabs = !portrait.enabled && side.enabled && !side.isWindow && side.book == null && (side.open || reveal > 0f)
    val minimum = LocalViewConfiguration.current.minimumTouchTargetSize
    val reserved = remember { arrayOf(emptyList<Rect>()) }
    val outside = Modifier.pointerInput(state, dismissSideOnGameTap) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val current = state.side
            val edge = state.width - current.arrange(state.width).panelWidth
            if (dismissSideOnGameTap && current.sideShown && !current.isDocked && down.position.x < edge &&
                reserved[0].none { it.contains(down.position) }) state.hide()
        }
    }
    Layout(modifier = modifier.fillMaxSize().clipToBounds().then(outside), content = {
        Box(Modifier.clipToBounds(), propagateMinConstraints = true) { game() }
        Box(Modifier.clipToBounds(), propagateMinConstraints = true) { controls() }
        Box(propagateMinConstraints = true) { gameOverlay() }
        Box(Modifier.clipToBounds().alpha(if (!portrait.enabled && side.isWindow) reveal else 1f), propagateMinConstraints = true) {
            if (side.enabled || portrait.enabled) companion()
        }
        Box(chromeModifier) {
            if (portrait.enabled && portrait.hinge == null) {
                PanelResizeHandle(true, state::startPortraitDrag, state::dragPortrait, state::finishPortraitDrag) {
                    // The placement depends on the same arrangement as the companion below.
                    PortraitGrip(state.gripPlacement,
                        state::stepPortrait)
                }
            }
        }
        Box(chromeModifier) {
            if (sideTabs) {
                PanelResizeHandle(false, state::startSideDrag, state::dragSide, state::finishSideDrag) {
                    SidePanelHandle(side.docked, state::toggleDock)
                }
            }
        }
        Box(chromeModifier.alpha(if (side.docked) .35f else 1f)) {
            if (sideTabs) SidePanelCloseTab(state::hide)
        }
        Box(chromeModifier.alpha(if (side.docked && !side.isWindow) .35f else 1f)) {
            if (!portrait.enabled && side.closed && side.book == null && state.openTabShown && !state.tabInHud) SidePanelOpenTab(state::show)
        }
        Box(propagateMinConstraints = true) { screenOverlay() }
        Box(propagateMinConstraints = true) { gameUnderlay() }
        Box(propagateMinConstraints = true) { pictureOverlay() }
    }) { items, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val loose = Constraints(maxWidth = w, maxHeight = h)
        val grip = items[4].measure(loose)
        val handle = items[5].measure(loose)
        val close = items[6].measure(loose)
        val open = items[7].measure(loose)
        val gh = gameHeight(w)
        state.width = w; state.height = h; state.gameHeight = gh; state.gripHeight = grip.height
        val frames = gameScreenFrames(w, h, gh, grip.height, side, portrait)
        state.gripPlacement = frames.portrait?.grip ?: PortraitLayout.Grip.ABOVE
        val full = Rect(0f, 0f, w.toFloat(), h.toFloat())
        fun measured(index: Int, rect: Rect) = items[index].measure(Constraints.fixed(
            rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0)))
        val surface = measured(0, frames.game)
        val pad = measured(1, frames.controls)
        val picture = overlayRect()?.translate(frames.game.left, frames.game.top) ?: frames.game
        val hud = measured(2, picture)
        val underlay = measured(9, frames.game)
        val capture = measured(10, picture)
        // Window chrome computes its own density. Retain its composition with finite dimensions even during a
        // zero-size host transition; the outer screen clips it and no zero-size companion is ever placed.
        val panel = items[3].measure(Constraints.fixed(frames.companion.width.roundToInt().coerceAtLeast(64),
            frames.companion.height.roundToInt().coerceAtLeast(64)))
        val overlayTop = screenOverlayTop().coerceIn(0, h)
        val overlay = measured(8, Rect(0f, overlayTop.toFloat(), full.right, full.bottom))
        val gap = (12 * density).roundToInt()
        val slide = if (!portrait.enabled && !side.isWindow) ((frames.companion.width + handle.width) * (1f - reveal)).roundToInt() else 0
        val panelLeft = frames.companion.left.roundToInt() + slide
        fun chromeRect(x: Int, y: Int, width: Int, height: Int) = Rect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        val gripRect = chromeRect((w - grip.width) / 2, frames.portrait?.gripTop ?: 0, grip.width, grip.height)
        val handleRect = chromeRect(panelLeft - handle.width, gap, handle.width, handle.height)
        val closeRect = chromeRect(panelLeft - close.width, h - gap - close.height, close.width, close.height)
        val openRect = chromeRect(w - open.width, if (state.tabRaised) gap else h - gap - open.height, open.width, open.height)
        val chrome = listOf(gripRect, handleRect, closeRect, openRect).filter { it.width > 0 && it.height > 0 }.map {
            val dx = ((minimum.width.toPx() - it.width) / 2).coerceAtLeast(0f)
            val dy = ((minimum.height.toPx() - it.height) / 2).coerceAtLeast(0f)
            Rect((it.left - dx).coerceIn(0f, w.toFloat()), (it.top - dy).coerceIn(0f, h.toFloat()),
                (it.right + dx).coerceIn(0f, w.toFloat()), (it.bottom + dy).coerceIn(0f, h.toFloat()))
        }
        reserved[0] = chrome
        layout(w, h) {
            surface.place(frames.game.left.roundToInt(), frames.game.top.roundToInt())
            underlay.place(frames.game.left.roundToInt(), frames.game.top.roundToInt())
            pad.place(frames.controls.left.roundToInt(), frames.controls.top.roundToInt())
            hud.place(picture.left.roundToInt(), picture.top.roundToInt())
            if (w > 0 && h > 0 && (frames.companionShown || !portrait.enabled && side.enabled && reveal > 0f)) panel.place(panelLeft, frames.companion.top.roundToInt())
            grip.place(gripRect.left.roundToInt(), gripRect.top.roundToInt())
            handle.place(handleRect.left.roundToInt(), handleRect.top.roundToInt())
            close.place(closeRect.left.roundToInt(), closeRect.top.roundToInt())
            open.place(openRect.left.roundToInt(), openRect.top.roundToInt())
            overlay.place(0, overlayTop)
            capture.place(picture.left.roundToInt(), picture.top.roundToInt())
            onFrames(frames, chrome)
        }
    }
}
