package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import com.pokedaisy.app.companion.BatteryStatus

/**
 * The top screen's status bar (SETTINGS > STATUS BAR): the title window's
 * white strip across the top of the game, as wide as the game. The game's
 * name and where the player is on the left; money (for the games it's read
 * from), the clock and the battery on the right. [location] / [money] null
 * = not known (yet). [battery] null = the live [BatteryIndicator] reading.
 */
@Composable
fun GameStatusBar(
    gameName: String,
    location: String?,
    money: Long?,
    time: String,
    modifier: Modifier = Modifier,
    battery: BatteryStatus? = null,
) {
    val m = rememberGbaTextMetrics(1f)
    val u = m.u
    // One font pixel per cell, so the icons are as tall as the capitals.
    val cell = m.lineHeight / 16
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawLayeredBox(OptionColors.titleLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 2 * u.toPx()) }
            .padding(horizontal = u * 8, vertical = u * 3),
    ) {
        // A long name gives way (ellipsis) before the location does.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            GbaText(gameCaps(gameName), OptionColors.titleText, OptionColors.titleShadow, m, modifier = Modifier.weight(1f, fill = false))
            if (!location.isNullOrEmpty()) {
                Spacer(Modifier.width(u * 10))
                PixelArt(
                    MAP_PIN,
                    mapOf('o' to OptionColors.value, 's' to OptionColors.valueShadow),
                    Modifier.size(cell * MAP_PIN[0].length, cell * MAP_PIN.size),
                )
                Spacer(Modifier.width(cell * 2))
                GbaText(gameCaps(location), OptionColors.label, OptionColors.labelShadow, m)
            }
        }
        if (money != null) {
            Spacer(Modifier.width(u * 10))
            PixelArt(
                POKE_DOLLAR,
                mapOf('o' to OptionColors.titleText, 's' to OptionColors.titleShadow),
                Modifier.size(cell * POKE_DOLLAR[0].length, cell * POKE_DOLLAR.size),
            )
            Spacer(Modifier.width(cell))
            StatusText(money.toString(), m)
        }
        Spacer(Modifier.width(u * 10))
        StatusText(time, m)
        Spacer(Modifier.width(u * 10))
        BatteryIndicator(m, status = battery)
    }
}

@Composable
private fun StatusText(text: String, m: GbaTextMetrics) =
    GbaText(text, OptionColors.titleText, OptionColors.titleShadow, m)

/** Capitals like the OPTION screen's title, keeping the games' own "POKéMON". */
private fun gameCaps(s: String) = s.uppercase().replace('É', 'é')

/**
 * The Pokédollar sign: a P whose stem two bars cross, with the text's
 * one-pixel shadow ('s') right, down and diagonal.
 */
private val POKE_DOLLAR = withPixelShadow(
    listOf(
        ".oooo.",
        ".o...o",
        ".o...o",
        ".oooo.",
        ".o....",
        "oooo..",
        ".o....",
        "oooo..",
        ".o....",
    ),
)

/** A map pin before the location, in the OPTION screen's red. */
private val MAP_PIN = withPixelShadow(
    listOf(
        ".ooooo.",
        "ooooooo",
        "ooo.ooo",
        "oo...oo",
        "ooo.ooo",
        ".ooooo.",
        "..ooo..",
        "..ooo..",
        "...o...",
    ),
)

/** [rows] one pixel bigger each way, every empty pixel right / below / diagonal of an 'o' an 's'. */
internal fun withPixelShadow(rows: List<String>): List<String> {
    val w = rows.maxOf { it.length } + 1
    val g = Array(rows.size + 1) { y -> CharArray(w) { x -> rows.getOrNull(y)?.getOrNull(x) ?: '.' } }
    for (y in rows.indices) for (x in rows[y].indices) {
        if (rows[y][x] != 'o') continue
        for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1)) {
            if (g[y + dy][x + dx] == '.') g[y + dy][x + dx] = 's'
        }
    }
    return g.map { it.concatToString() }
}
