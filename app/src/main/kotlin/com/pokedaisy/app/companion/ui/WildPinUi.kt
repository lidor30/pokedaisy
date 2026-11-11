package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.pokedaisy.app.companion.PinControl
import com.pokedaisy.app.companion.WildPins
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.i18n.tr

/*
 * WILD PIN's pieces ([com.pokedaisy.app.companion.data.WildPin]): the PIN button on GUIDE > HERE's and the HUD's WILD
 * GRASS / CAVE rows, the reason line when it's greyed, and the HUD WILD button's face while a pin is in.
 */

/** A push pin: its round head over a short point (the pixel font's cap height, like [PixelIcons.star]). */
val WILD_PIN_ICON = listOf(
    "..11111..",
    ".1111111.",
    ".1111111.",
    "..11111..",
    "...111...",
    "111111111",
    "....1....",
    "....1....",
    "....1....",
)

/** The pin mark on the HUD's WILD button: K outline, R head, W its glint, G the point. */
private val PIN_MARK = listOf(
    "..KKKKK..",
    ".KRRRRRK.",
    "KRWRRRRRK",
    "KRRRRRRRK",
    ".KRRRRRK.",
    "..KKKKK..",
    "...KGK...",
    "...KGK...",
    "....K....",
)
private val PIN_MARK_COLORS = mapOf('K' to Color(0xFF202020), 'R' to Color(0xFFE83028), 'W' to Color(0xFFF8C8C0), 'G' to Color(0xFFB8B8C8))

/**
 * A row's PIN: white, the pin before its label (PIN, or UNPIN in red while it's the one pinned); greyed while [control]
 * says why not ([WildPinReason] says it once over the list). [label] false: the pin alone (the HUD's narrow rows).
 */
@Composable
fun WildPinButton(control: PinControl, m: GbaTextMetrics, modifier: Modifier = Modifier, label: Boolean = true) {
    val text = if (control.pinned) tr("UNPIN") else tr("PIN")
    OptionButton(
        if (label) text else null, m, onClick = { WildPins.toggle(control) }, modifier = modifier,
        emphasis = control.pinned, enabled = control.blocked == null, contentDescription = text,
        pixelIcon = WILD_PIN_ICON, sidePadding = if (label) 4 else 2, rainbow = false,
    )
}

/** Why the PINs are greyed (once, under the GRASS / CAVE heading), or nothing. */
fun wildPinReason(controls: List<PinControl?>): String? = controls.firstNotNullOfOrNull { it?.blocked }?.let { tr(it) }

/**
 * The HUD WILD button's face while [species] is pinned on this map: its party icon ([seen]: else a "?", like the WILD
 * list) with a red pin on its top-left corner.
 */
@Composable
fun PinnedWildFace(species: Int, seen: Boolean, size: Dp, m: GbaTextMetrics) {
    val u = m.u
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (seen) HudSpecies(spriteAsset("pokemon", species), size)
        else GbaText("?", ink.text, ink.shadow, m)
        // Top left: the top right is the uncaught count's badge.
        PixelArt(PIN_MARK, PIN_MARK_COLORS, Modifier.align(Alignment.TopStart).offset(x = -u * 4, y = -u * 2).size(u * 9))
    }
}
