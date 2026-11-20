package com.pokedaisy.app.companion.ui

import androidx.compose.ui.geometry.Rect
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchStage
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The game's screen on one screen laid out by Android's own rules: upright [PortraitLayout] (the companion under the
 * game when [underGame], the touch controls in a strip of their own along the bottom, [style]'s natural height, as
 * PokeDaisyActivity gives them; along the bottom, the gap the companion leaves, or over the game when there's none),
 * sideways [sidePanel] decides whether the companion is docked, over the game or put away. The default remains
 * docked for hosts that do not yet expose the panel controls. Portrait sizing reuses [PortraitLayout].
 */
fun gamePanelLayout(
    width: Float, height: Float, dp: Float,
    controlsVisible: Boolean = true, gameBoy: Boolean = false, underGame: Boolean = true,
    style: TouchControlsStyle? = null,
    sidePanel: SidePanelState = SidePanelState(enabled = true, open = true, window = false, docked = true),
    portraitRatio: Float = PortraitLayout.DEFAULT_RATIO, portraitHidden: Boolean = false,
): TouchStage {
    val w = width.coerceAtLeast(0f)
    val h = height.coerceAtLeast(0f)
    // Book mode uses left/right pages even when the enclosing window is taller than wide, as on Android.
    val portrait = h >= w && !(sidePanel.enabled && sidePanel.book != null)
    val aspect = if (gameBoy) 160f / 144 else 1.5f
    if (portrait) {
        val strip = if (controlsVisible && underGame && style != null && w > 0f) TouchGeometry.naturalHeight(style, w, dp, gameBoy) else 0
        val a = PortraitLayout.arrange(w.roundToInt(), h.roundToInt(), (w / aspect).roundToInt(),
            gripH = 0, ratio = portraitRatio, underGame = underGame, padStrip = strip, hidden = portraitHidden)
        val gh = min(w / aspect, a.gameBottom.toFloat()).coerceAtLeast(0f)
        val gw = gh * aspect
        val area = if (controlsVisible) Rect(0f, a.padTop.toFloat(), w, a.padBottom.toFloat()) else Rect(0f, h, w, h)
        return TouchStage(w, h, dp, true, Rect((w - gw) / 2, 0f, (w + gw) / 2, gh), area,
            if (portraitHidden) null else Rect(0f, a.companionTop.toFloat(), w, (a.companionTop + a.companionHeight).toFloat()), aspect, gameBoy)
    }
    val side = sidePanel.arrange(w.roundToInt())
    val column = (w - side.gameRightMargin).coerceAtLeast(0f)
    val gw = min(column, h * aspect)
    val gh = gw / aspect
    val companion = when {
        !sidePanel.shown -> null
        sidePanel.isWindow -> Rect(0f, 0f, w, h)
        else -> Rect((w - side.panelWidth).coerceAtLeast(0f), 0f, w, h)
    }
    return TouchStage(w, h, dp, false, Rect((column - gw) / 2, (h - gh) / 2, (column + gw) / 2, (h + gh) / 2),
        Rect(0f, 0f, (w - side.controlsRightMargin).coerceAtLeast(0f), if (controlsVisible) h else 0f),
        companion, aspect, gameBoy)
}
