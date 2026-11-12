package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The companion on a single-screen device: a panel beside (or over) the game
 * instead of a second screen. The companion's layouts are made for the Thor's
 * bottom screen (1240x1080 @ 2.625 = 472x411 dp), so the panel draws it at the
 * density that gives it at least that much room: the Thor's own where the panel
 * is that big, smaller (the pixel font steps down to 2 screen pixels per font
 * pixel, still crisp) for a narrower one - half a 1080p screen comes out at
 * ~1.9, where every tab fits without truncating.
 */
fun sidePanelDensity(widthPx: Int, heightPx: Int): Float =
    min(heightPx / THOR_BOTTOM_HEIGHT_DP, widthPx / SIDE_PANEL_MIN_WIDTH_DP).coerceAtLeast(1f)

/** The Thor's bottom screen height in dp (1080 px @ 2.625). */
private const val THOR_BOTTOM_HEIGHT_DP = 1080f / 2.625f

/** A bit over the Thor's 472 dp, so half a 1080p screen lands below 2.0 (whole-pixel text steps down). */
private const val SIDE_PANEL_MIN_WIDTH_DP = 500f

/** [content] (the companion) at [sidePanelDensity] for the space it's given. */
@Composable
fun SidePanelCompanion(compactText: Boolean = false, content: @Composable () -> Unit) {
    BoxWithConstraints {
        val outer = LocalDensity.current
        val d = sidePanelDensity(constraints.maxWidth, constraints.maxHeight)
        CompositionLocalProvider(
            LocalDensity provides Density(d, outer.fontScale),
            // A big screen ([compactText]): the main text a step smaller ([LocalTextStep]).
            LocalTextStep provides if (compactText) -1 else 0,
        ) { content() }
    }
}

/**
 * Screens this tall (inches, the short side) or taller get the companion's smaller text on one screen: the RP6 (5.5",
 * 16:9) and the RP Nova (4.5", 4:3) are both ~2.7" tall; a 3.9" 4:3 (the RP Mini) is ~2.35". Height, not the diagonal:
 * the companion is laid out to the screen's height. (Devices misreport their dpi - the RP6 says 319, ~3.4" tall -
 * which only ever overshoots on these.)
 */
const val COMPACT_TEXT_INCHES = 2.5f

/**
 * The companion's window over the game on a [widthPx] x [heightPx] screen of [density]: [WINDOW_HEIGHT] of its height, at
 * least the Thor bottom screen's shape (1240x1080, so every tab lays out as it was made to) and wider on a wider screen (the
 * RP6's 16:9), narrower only where the screen is; never taller than [OVERLAY_MAX_HEIGHT_DP] (a tablet: the window and what's
 * in it stay about a handheld's size, not most of a 10" screen). Width x height, px.
 */
fun companionWindowSize(widthPx: Int, heightPx: Int, density: Float): Pair<Int, Int> {
    val h = min((heightPx * WINDOW_HEIGHT).roundToInt(), overlayMaxHeight(density))
    // Wider on a wider screen (16:9 comes out ~1.39:1), never narrower than the Thor's bottom screen.
    val aspect = (widthPx.toFloat() / heightPx * WIDE_SHARE).coerceIn(THOR_BOTTOM_ASPECT, MAX_WINDOW_ASPECT)
    val w = min((widthPx * WINDOW_MAX_WIDTH).roundToInt(), (h * aspect).roundToInt())
    return w to h
}

/**
 * The tallest a window over the game gets, dp: the companion window and the HUD's MAP and summary alike, so on a big
 * screen they come out the same size (the user's call, 2026-11-08: on a 10" tablet all three filled the screen). The
 * handhelds' screens are under it (the RP6's window ~320 dp, the Thor's bottom screen 411 dp), so only tablets see it.
 */
const val OVERLAY_MAX_HEIGHT_DP = 480f

/** [OVERLAY_MAX_HEIGHT_DP] in px at [density]. */
fun overlayMaxHeight(density: Float): Int = (OVERLAY_MAX_HEIGHT_DP * density).roundToInt()

private const val THOR_BOTTOM_ASPECT = 1240f / 1080f
/** The window's shape follows the screen's, this much of it: a 16:9 screen's game still shows either side. */
private const val WIDE_SHARE = 0.78f
private const val MAX_WINDOW_ASPECT = 1.4f
/** The window's share of the screen's height: a window over the game, not a screen of its own. */
private const val WINDOW_HEIGHT = 0.88f
private const val WINDOW_MAX_WIDTH = 0.94f

/** The game under the window: dimmed, still there (it keeps running). */
private val WINDOW_SCRIM = Color(0x8C000000.toInt())

/** SETTINGS > COMPANION VIEW's value: a window in the middle over the game, or the side panel. */
fun companionViewLabel(window: Boolean) = if (window) tk("WINDOW") else tk("SIDE PANEL")

/**
 * What the single-screen companion's view draws: the side panel, the companion filling it; or the window
 * ([window]), the companion framed like the game's windows in the middle of a dim over the game, the X that closes it
 * ([onClose]) at its top right - beside it where the screen leaves room, else on its corner - and L / R hints at its
 * tab bar's ends ([LocalCompanionWindow]). A tap on the dim closes it too. The companion is the same node
 * either way, so switching keeps its tab and pages.
 */
@Composable
fun SidePanelHost(
    window: Boolean, onClose: () -> Unit,
    /** The window's keys: the D-pad and A that nothing in the companion took go to [focusKeys]. */
    keys: CompanionKeys? = null,
    /** A tall screen ([COMPACT_TEXT_INCHES]): the companion's main text a step smaller. */
    compactText: Boolean = false,
    /**
     * Hands a key on to Compose's focus (SidePanel: the D-pad's key event, sent to this view): the D-pad moves the
     * focus and A presses what has it, as on the top screen. It calls `start` once the view can take the focus, which
     * puts it on the newest start ([FocusStarts]) when nothing in the companion has it yet. True = taken.
     */
    focusKeys: ((key: MenuKey, repeat: Boolean, start: () -> Unit) -> Boolean)? = null,
    /** The window fills the screen, unframed (the game's screen of a two-screen device whose companion was closed). */
    fullScreen: Boolean = false,
    content: @Composable () -> Unit,
) {
    val box = remember { IntArray(4) }   // the window's left / top / right / bottom, for the dim's taps
    val starts = remember { FocusStarts() }
    val overlays = remember { OverlayStack() }
    var hasFocus by remember { mutableStateOf(false) }
    // Registered before the companion's own handlers, so it gets only what they leave.
    keys?.Handler(enabled = window) { key, repeat ->
        focusKeys?.invoke(key, repeat) { if (!hasFocus) starts.focusNewest() } ?: false
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().then(
            if (!window) Modifier
            else Modifier.background(WINDOW_SCRIM).pointerInput(Unit) {
                // The companion's own buttons take their taps first; this sees what fell on the dim (or on a part of
                // the companion with nothing to tap, which the window's bounds rule out).
                detectTapGestures { p ->
                    if (p.x < box[0] || p.y < box[1] || p.x > box[2] || p.y > box[3]) onClose()
                }
            },
        ),
    ) {
        val outer = LocalDensity.current
        val (ww, wh) = if (fullScreen) constraints.maxWidth to constraints.maxHeight else companionWindowSize(constraints.maxWidth, constraints.maxHeight, outer.density)
        // The window's chrome (frame, X, legend) at the companion's own scale.
        val chrome = Density(sidePanelDensity(ww, wh), outer.fontScale)
        Layout(
            content = {
                CompositionLocalProvider(
                    LocalDensity provides if (window) chrome else outer,
                    // Pop-ups keep the focus in and hand it back when they close; the D-pad's starting places.
                    LocalOverlayStack provides overlays.takeIf { window },
                    LocalFocusStarts provides starts.takeIf { window },
                    // L / R at the tab bar's ends: the buttons that step it.
                    LocalCompanionWindow provides window,
                ) {
                    WindowFrame(window && !fullScreen, Modifier.onFocusChanged { hasFocus = it.hasFocus }) { SidePanelCompanion(compactText, content) }
                    if (window) WindowCloseButton(onClose, if (fullScreen) FULL_SCREEN_CLOSE_ALPHA else 1f)
                }
            },
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val (cw, ch) = if (window && !fullScreen) companionWindowSize(w, h, density) else w to h
            val companion = measurables[0].measure(Constraints.fixed(cw, ch))
            val close = measurables.getOrNull(1)?.measure(Constraints())
            val left = (w - cw) / 2
            val top = (h - ch) / 2
            box[0] = left; box[1] = top; box[2] = left + cw; box[3] = top + ch
            layout(w, h) {
                companion.place(left, top)
                if (close != null) {
                    val gap = close.width / 4
                    // Beside the window's top right corner; on it where the screen has no room.
                    val x = if (left + cw + gap + close.width <= w - gap) left + cw + gap else left + cw - close.width - gap
                    val y = if (left + cw + gap + close.width <= w - gap) top else top + gap
                    close.place(x, y)
                }
            }
        }
    }
}

/** The companion as a window ([window]): clipped to the game windows' pixel corners, their frame over its edge,
 * a drop shadow under it. The side panel: as it is. */
@Composable
private fun WindowFrame(window: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val u = rememberGbaTextMetrics().u
    // One Box either way (only its modifier changes), so switching keeps the companion's state.
    Box(
        if (!window) modifier
        else modifier
            .drawBehind {
                val px = u.toPx()
                drawPixelRoundRect(Color.Black.copy(alpha = 0.35f), Offset(0f, 3 * px), size, radius = 4 * px, step = px)
            }
            .pixelClip(PixelRoundedShape(u * 4))
            // LIGHT COMPANION: the window drawn into a layer of its own, which the renderer keeps until something in
            // it changes - an unchanged window cost a weak tablet 40-55 ms of GPU commands on every frame anything
            // else on screen drew (2026-11-08).
            .then(if (com.pokedaisy.app.companion.LightCompanion.on) Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen } else Modifier)
            .drawWithContent {
                drawContent()
                drawLayeredFrame(OptionColors.titleLayers.inPx(u.toPx()), radius = 4 * u.toPx())
            },
    ) { content() }
}

/** The full-screen companion's X sits on its top right corner, over what's there (a title's battery, a party slot):
 *  see-through, so that stays readable. */
private const val FULL_SCREEN_CLOSE_ALPHA = 0.55f

/** The window's X: a white framed square like the side panel's tabs, top right of the window (the DS touch window's too). */
@Composable
internal fun WindowCloseButton(onClose: () -> Unit, alpha: Float = 1f, description: String = tr("Close the companion")) {
    val m = rememberGbaTextMetrics()
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(u * 24, u * 24)
            .alpha(alpha)
            .drawBehind {
                val px = u.toPx()
                drawPixelRoundRect(Color.Black.copy(alpha = 0.25f), Offset(0f, 2 * px), size, radius = 3 * px, step = px)
                drawLayeredBox(OptionColors.titleLayers.inPx(px), OptionColors.titleFill, radius = 3 * px)
            }
            .semantics { contentDescription = description }
            // Touch only: START / B close the window from the buttons, and the D-pad stays inside it.
            .focusProperties { canFocus = false }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClose),
    ) {
        ShadowedPixelIcon(PixelIcons.panelClose, OptionColors.label, OptionColors.labelShadow, m)
    }
}


/**
 * The tab on the side panel's edge: a white title-window tab running into the
 * panel (like a folder tab) with a padlock - open = the panel lies over the
 * game, shut = locked beside it - in OPTION's grey / red, with the text's
 * shadow. A tap locks / unlocks; dragging it sideways (handled by the view
 * around it, in screen coordinates, since the tab moves with the finger)
 * resizes the panel.
 */
@Composable
fun SidePanelHandle(docked: Boolean, onToggle: () -> Unit) = SidePanelTab(
    if (docked) PixelIcons.lockClosed else PixelIcons.lockOpen,
    red = docked,
    description = if (docked) tr("Unlock the companion") else tr("Lock the companion beside the game"),
    onClick = onToggle,
)

/** The tab on the screen's right edge, bottom, that slides the side panel in (while it's closed). */
@Composable
fun SidePanelOpenTab(onOpen: () -> Unit) = SidePanelTab(PixelIcons.panelOpen, red = false, tr("Open the companion"), onOpen)

/** The tab outside the side panel's bottom-left corner that slides it away. */
@Composable
fun SidePanelCloseTab(onClose: () -> Unit) = SidePanelTab(PixelIcons.panelClose, red = false, tr("Close the companion"), onClose)

/**
 * A DS game's touch screen filling the second display (DS TOUCH SCREEN > FILL, [com.pokedaisy.app.NdsBottomHost]): its
 * COMPANION / GAME SCREEN switch as a small tab on the right edge, the side panel's open tab while the touch screen shows
 * and its close tab over the companion.
 */
@Composable
fun DsScreenSwitchTab(showingCompanion: Boolean, onClick: () -> Unit) =
    if (showingCompanion) SidePanelTab(PixelIcons.panelClose, red = false, tr("GAME SCREEN"), onClick, cells = 24)
    else SidePanelTab(PixelIcons.panelOpen, red = false, tr("COMPANION"), onClick, cells = 24)

/** One of the side panel's tabs: [icon] on a white title-window tab running off its right edge, [cells] GBA pixels square. */
@Composable
private fun SidePanelTab(icon: List<String>, red: Boolean, description: String, onClick: () -> Unit, cells: Int = 32) {
    val m = rememberGbaTextMetrics()
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(u * cells, u * cells)
            .drawBehind {
                drawLayeredBox(OptionColors.titleLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 3 * u.toPx(), openRight = true)
            }
            .semantics { contentDescription = description }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick),
    ) {
        if (red) ShadowedPixelIcon(icon, OptionColors.value, OptionColors.valueShadow, m)
        else ShadowedPixelIcon(icon, OptionColors.label, OptionColors.labelShadow, m)
    }
}
