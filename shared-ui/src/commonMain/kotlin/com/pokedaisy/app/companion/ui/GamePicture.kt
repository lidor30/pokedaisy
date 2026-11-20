package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** A picture fitted inside its viewport. Renderer adapters share this calculation without sharing game frames. */
fun fittedGamePicture(width: Float, height: Float, aspect: Float, topAligned: Boolean = false, fill: Boolean = false,
                      viewport: Rect? = null, topStripHeight: Int = 0): Rect {
    if (viewport != null && topStripHeight == 0) return viewport
    val area = viewport ?: Rect(0f, 0f, width.coerceAtLeast(0f), height.coerceAtLeast(0f))
    val strip = topStripHeight.toFloat().coerceIn(0f, area.height.coerceAtLeast(0f))
    val w = area.width
    val h = area.height - strip
    if (fill) return Rect(area.left, area.top + strip, area.right, area.bottom)
    val gw = minOf(w, h * aspect)
    val gh = gw / aspect
    val x = area.left + (w - gw) / 2
    val y = area.top + strip + if (topAligned || viewport != null) 0f else (h - gh) / 2
    return Rect(x, y, x + gw, y + gh)
}

/** Keeps one native controller at the fitted picture's bounds, including top alignment in portrait. */
@Composable
fun GamePicture(aspect: Float, topAligned: Boolean, modifier: Modifier = Modifier, fill: Boolean = false,
                viewport: ((Int, Int) -> Rect?)? = null, topStripHeight: Int = 0,
                topStrip: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Layout(content = {
        content()
        if (topStrip != null) Box(propagateMinConstraints = true) { topStrip() }
    }, modifier = modifier) { items, c ->
        val window = viewport?.invoke(c.maxWidth, c.maxHeight)
        val stripHeight = topStripHeight.takeIf { topStrip != null } ?: 0
        val r = fittedGamePicture(c.maxWidth.toFloat(), c.maxHeight.toFloat(), aspect, topAligned, fill, window, stripHeight)
        val left = r.left.roundToInt()
        val top = r.top.roundToInt()
        val width = r.width.roundToInt()
        val picture = items.first().measure(Constraints.fixed(width, r.height.roundToInt()))
        val strip = items.getOrNull(1)?.measure(Constraints.fixed(width,
            minOf(stripHeight, (top - (window?.top ?: 0f).roundToInt()).coerceAtLeast(0))))
        layout(c.maxWidth, c.maxHeight) {
            picture.place(left, top)
            strip?.place(left, top - strip.height)
        }
    }
}
