package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** A picture fitted inside its viewport. Renderer adapters share this calculation without sharing game frames. */
fun fittedGamePicture(width: Float, height: Float, aspect: Float, topAligned: Boolean = false): Rect {
    val w = width.coerceAtLeast(0f)
    val h = height.coerceAtLeast(0f)
    val gw = minOf(w, h * aspect)
    val gh = gw / aspect
    val x = (w - gw) / 2
    val y = if (topAligned) 0f else (h - gh) / 2
    return Rect(x, y, x + gw, y + gh)
}

/** Keeps one native controller at the fitted picture's bounds, including top alignment in portrait. */
@Composable
fun GamePicture(aspect: Float, topAligned: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { items, c ->
        val r = fittedGamePicture(c.maxWidth.toFloat(), c.maxHeight.toFloat(), aspect, topAligned)
        val picture = items.single().measure(Constraints.fixed(r.width.roundToInt(), r.height.roundToInt()))
        layout(c.maxWidth, c.maxHeight) { picture.place(r.left.roundToInt(), r.top.roundToInt()) }
    }
}
