package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.pokedaisy.app.companion.BatteryStatus
import com.pokedaisy.app.companion.DeviceBattery

private const val BODY_W = 15
private const val BODY_H = 9
private const val INNER_W = BODY_W - 4

/** Charging fill: a green that reads on the white title window. */
private val BatteryCharging = Color(0xFF20A040)

/**
 * The device battery as pixel art in the title window's text colours (with
 * its shadow, like [GbaText]), then its percentage. The fill steps in whole
 * pixels; red at 15% or less, green while charging. Nothing until the first
 * reading ([DeviceBattery]).
 */
@Composable
fun BatteryIndicator(m: GbaTextMetrics, modifier: Modifier = Modifier, status: BatteryStatus? = null) {
    val live by DeviceBattery.status.collectAsState()
    val b = status ?: live ?: return
    val fill = when {
        b.charging -> BatteryCharging
        b.percent <= 15 -> OptionColors.value
        else -> OptionColors.titleText
    }
    val art = batteryBitmap(b.percent)
    // One font pixel, so the body is as tall as the capitals (~9 font pixels).
    val cell = m.lineHeight / 16
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PixelArt(
            art,
            mapOf('o' to OptionColors.titleText, 'f' to fill, 's' to OptionColors.titleShadow),
            Modifier.size(cell * art[0].length, cell * art.size),
        )
        Spacer(Modifier.width(cell * 3))
        GbaText("${b.percent}%", OptionColors.titleText, OptionColors.titleShadow, m)
    }
}

/**
 * The battery as rows of 'o' (outline), 'f' (fill), 's' (shadow, one pixel
 * right / down / diagonal like the text's) and '.': a 15x9 body with a 2x3
 * nub, and [percent] of its 11 inner columns filled (at least one while any
 * charge is left).
 */
fun batteryBitmap(percent: Int): List<String> {
    val w = BODY_W + 2 + 1
    val h = BODY_H + 1
    val g = Array(h) { CharArray(w) { '.' } }
    for (y in 0 until BODY_H) for (x in 0 until BODY_W) {
        if (y == 0 || y == BODY_H - 1 || x == 0 || x == BODY_W - 1) g[y][x] = 'o'
    }
    for (y in 3..5) for (x in BODY_W until BODY_W + 2) g[y][x] = 'o'
    val filled = if (percent <= 0) 0 else ((percent.coerceAtMost(100) * INNER_W + 50) / 100).coerceIn(1, INNER_W)
    for (y in 2..BODY_H - 3) for (x in 2 until 2 + filled) g[y][x] = 'f'
    val drawn = (0 until h).flatMap { y -> (0 until w).filter { x -> g[y][x] != '.' }.map { x -> x to y } }
    for ((x, y) in drawn) for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1)) {
        val nx = x + dx
        val ny = y + dy
        if (nx < w && ny < h && g[ny][nx] == '.') g[ny][nx] = 's'
    }
    return g.map { it.concatToString() }
}
