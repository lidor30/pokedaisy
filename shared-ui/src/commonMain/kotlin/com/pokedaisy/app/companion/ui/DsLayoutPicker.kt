package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.DsBox
import com.pokedaisy.app.companion.DsLayout
import com.pokedaisy.app.companion.DsLayouts
import com.pokedaisy.app.companion.i18n.tk

/*
 * SETTINGS > DS LAYOUT's pick-list (both screens): each layout's name and what it does, beside a miniature of a
 * sideways screen laid out that way - the game (a town seen from above) and the touch screen (a menu of buttons), the
 * game's backdrop around them, ONE SCREEN's touch screen floating over the dimmed game. Drawn, not screenshots: the
 * same [DsLayouts.arrange] the game screen uses, at the miniature's size.
 */

/** The DS LAYOUT pick-list over the screen; [onPick] with the layout tapped (then it closes, as [OptionSelector] does). */
@Composable
fun DsLayoutPicker(current: DsLayout, m: GbaTextMetrics, onPick: (DsLayout) -> Unit, onDismiss: () -> Unit) {
    OptionOverlay(onDismiss, Modifier.widthIn(max = 560.dp)) {
        // One row lit: the D-pad's, else the current choice.
        var focusedRow by remember { mutableStateOf(-1) }
        // Tall enough for the miniature and for the name over its line of what it does.
        val rowH = maxOf(m.u * (MINI_H + 10), m.lineHeight + subtitleMetrics(m).lineHeight + m.u * 12)
        Column {
            OptionTitleWindow(tk("DS LAYOUT"), m, onBack = onDismiss)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DsLayout.entries.forEachIndexed { i, layout ->
                        val on = layout == current
                        OptionLine(
                            layout.label, if (on) "◀" else null, selected = on && (focusedRow < 0 || focusedRow == i), m,
                            height = rowH, labelWeight = 0.88f, divider = true, subtitle = layout.subtitle,
                            onFocus = { focusedRow = i },
                            leading = { DsLayoutMiniature(layout, Modifier.size(m.u * MINI_W, m.u * MINI_H)) },
                        ) { onPick(layout) }
                    }
                    OptionLine(tk("CANCEL"), null, selected = false, m, onFocus = { focusedRow = DsLayout.entries.size }, onClick = onDismiss)
                }
            }
        }
    }
}

/** A miniature's size, in its own pixels (each one GBA pixel on screen): a sideways screen, about 16:9. */
private const val MINI_W = 56
private const val MINI_H = 32

/** [layout] on a sideways 16:9 screen, in whole pixels of [MINI_W] x [MINI_H] stretched over [modifier]'s box. */
@Composable
fun DsLayoutMiniature(layout: DsLayout, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = size.width / MINI_W
        fun rect(x: Int, y: Int, w: Int, h: Int, c: Color) {
            if (w > 0 && h > 0) drawRect(c, Offset(x * p, y * p), Size(w * p, h * p))
        }
        // The screen's frame, then the game's backdrop over it.
        rect(0, 0, MINI_W, MINI_H, BEZEL)
        rect(1, 1, MINI_W - 2, MINI_H - 2, BACKDROP)
        val inner = DsLayouts.arrange(layout, MINI_W - 2, MINI_H - 2, fill = true)
        fun shift(b: DsBox) = DsBox(b.left + 1, b.top + 1, b.right + 1, b.bottom + 1)
        drawGame(shift(inner.top), ::rect)
        inner.touch?.let { t ->
            val b = shift(t)
            // An inset over the game gets a white edge to stand off it.
            if (layout == DsLayout.PICTURE_IN_PICTURE) rect(b.left - 1, b.top - 1, b.width + 2, b.height + 2, Color.White)
            drawTouch(b, ::rect)
        }
        if (layout == DsLayout.ONE_SCREEN) {
            // Over the dimmed game: the touch screen in its floating window.
            drawRect(Color.Black.copy(alpha = 0.45f), Offset(p, p), Size((MINI_W - 2) * p, (MINI_H - 2) * p))
            val wh = 15
            val ww = wh * 4 / 3
            val x = (MINI_W - ww) / 2
            val y = (MINI_H - wh) / 2
            rect(x - 1, y - 1, ww + 2, wh + 2, Color.White)
            drawTouch(DsBox(x, y, x + ww, y + wh), ::rect)
        }
    }
}

/** The game: a town seen from above - grass, a path across, a house with a red roof and the player on the path. */
private fun DrawScope.drawGame(b: DsBox, rect: (Int, Int, Int, Int, Color) -> Unit) {
    rect(b.left, b.top, b.width, b.height, GRASS)
    val pathY = b.top + b.height * 5 / 8
    rect(b.left, pathY, b.width, maxOf(1, b.height / 6), PATH)
    val hw = maxOf(3, b.width / 4)
    val hh = maxOf(3, b.height * 3 / 8)
    val hx = b.left + b.width / 6
    val hy = pathY - hh
    rect(hx, hy, hw, hh, WALL)
    rect(hx, hy, hw, maxOf(1, hh / 2), ROOF)
    rect(b.left + b.width * 2 / 3, b.top + b.height / 5, maxOf(2, b.width / 6), maxOf(2, b.height / 5), TREE)
    rect(b.left + b.width / 2, pathY - 1, 1, 2, PLAYER)
}

/** The touch screen: a menu of buttons in a frame. */
private fun DrawScope.drawTouch(b: DsBox, rect: (Int, Int, Int, Int, Color) -> Unit) {
    rect(b.left, b.top, b.width, b.height, MENU_FRAME)
    rect(b.left + 1, b.top + 1, b.width - 2, b.height - 2, MENU_FILL)
    // Two columns of three buttons where there's room, else one row.
    val cols = 2
    val rows = if (b.height >= 9) 3 else 1
    val cw = (b.width - 2 - (cols + 1)) / cols
    val ch = (b.height - 2 - (rows + 1)) / rows
    if (cw < 1 || ch < 1) return
    for (r in 0 until rows) for (c in 0 until cols) {
        rect(b.left + 2 + c * (cw + 1), b.top + 2 + r * (ch + 1), cw, ch, if ((r + c) % 2 == 0) BUTTON_A else BUTTON_B)
    }
}

private val BEZEL = Color(0xFF2A2A33)
/** HeartGold's party screen teal ([GameBackdrop]'s): the game's backdrop around the screens. */
private val BACKDROP = Color(0xFF8ADBDB)
private val GRASS = Color(0xFF60B850)
private val PATH = Color(0xFFD8C890)
private val WALL = Color(0xFFE8E0D0)
private val ROOF = Color(0xFFD05038)
private val TREE = Color(0xFF2E7A38)
private val PLAYER = Color(0xFF202838)
private val MENU_FRAME = Color(0xFF1E6E3A)
private val MENU_FILL = Color(0xFFD8F0DC)
private val BUTTON_A = Color(0xFF3CB060)
private val BUTTON_B = Color(0xFF58A8E0)
