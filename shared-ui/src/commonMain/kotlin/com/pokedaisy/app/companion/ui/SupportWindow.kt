package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.SupportAsk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing

/**
 * The support window ([SupportAsk]): the flower in the title, a gold frame round the rest, a red heart and a thank you
 * (no hours: a count read as being watched, the user's call), a few lines on why it helps, then a big tile per site - its
 * own mark in its own colours over its name - and REMIND ME LATER (where the D-pad starts) beside NO THANKS. Tiles sit side by side, or one under another in
 * a narrow window (a phone held upright). Outside the window (BACK, a tap on the dim) is a REMIND ME LATER.
 */
@Composable
fun SupportWindow(
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    onSite: (SupportAsk.Site) -> Unit,
    onLater: () -> Unit,
    onNoThanks: () -> Unit,
) {
    val u = m.u
    OptionOverlay(onLater, Modifier.widthIn(max = 760.dp)) {
        Column {
            OptionTitleWindow("ENJOYING POKéDAISY?", m, leading = { PixelArt(LOGO_ROWS, LOGO_PALETTE, Modifier.size(m.lineHeight)) })
            Spacer(Modifier.height(u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().goldFrame(u)) {
                BoxWithConstraints(Modifier.padding(u * 4)) {
                    // Stacked tiles only in a tall, narrow window (a phone held upright); a short screen keeps them in a
                    // row, tight ones with the small text and no note (the RG DS's 640x480).
                    val narrow = maxWidth < u * 300
                    val stacked = narrow && maxHeight > maxWidth
                    // No scrolling anywhere: a short screen (the RG DS, a phone on its side) gets the short paragraph.
                    val short = maxHeight < m.lineHeight * 11
                    Column {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = u * 4)) {
                                val cell = with(LocalDensity.current) { m.fontPixel.toDp() }
                                ShadowedPixelIcon(PixelIcons.heart, OptionColors.value, OptionColors.valueShadow, m, cell = cell)
                                Spacer(Modifier.width(u * 5))
                                GbaText(tr("Thank you for playing!"), OptionColors.value, OptionColors.valueShadow, m, maxLines = 2)
                            }
                            Spacer(Modifier.height(u * 5))
                            GbaText(
                                if (short) tr("Free, no ads, made by one person. A small tip helps PokéDaisy grow.")
                                else tr("PokéDaisy is free, has no ads and is made by one person in their spare time. If it has made your games better and you'd like to see it grow, with more games and more features, a small tip goes a long way."),
                                OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                                modifier = Modifier.padding(horizontal = u * 4),
                            )
                        }
                        Column(Modifier.padding(top = u * if (short) 5 else 8)) {
                            if (stacked) {
                                Column(verticalArrangement = Arrangement.spacedBy(u * 4)) {
                                    SupportAsk.Site.entries.forEach { SiteTile(it, m, small, wide = true, Modifier.fillMaxWidth()) { onSite(it) } }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(u * 4)) {
                                    SupportAsk.Site.entries.forEach { SiteTile(it, m, small, wide = false, Modifier.weight(1f), compact = narrow || short) { onSite(it) } }
                                }
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(u * 4),
                            modifier = Modifier.fillMaxWidth().padding(top = u * if (short) 5 else 8),
                        ) {
                            OptionButton(tr("NO THANKS"), m, onClick = onNoThanks, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                            OptionButton(
                                tr("REMIND ME LATER"), m, onClick = onLater, focusStart = true,
                                modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The gold frame round the support window's list: outside the list window's own, so it reads as that window, gilded. */
private fun Modifier.goldFrame(u: Dp): Modifier = drawBehind {
    val px = u.toPx()
    drawLayeredBox(
        listOf(Color(0xFF6A4300) to 1f * px, Color(0xFFE0A010) to 2f * px, Color(0xFFF8D870) to 1f * px),
        Color.Transparent, radius = 3 * px,
    )
}.padding(u * 4)

/** One site's tile: its mark big over its name ([wide]: side by side); [compact] (a tight row) draws them smaller. */
@Composable
private fun SiteTile(
    site: SupportAsk.Site, m: GbaTextMetrics, small: GbaTextMetrics, wide: Boolean, modifier: Modifier, compact: Boolean = false,
    onClick: () -> Unit,
) {
    val u = m.u
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val cell = with(LocalDensity.current) { m.fontPixel.toDp() }
    val mark: @Composable (Modifier) -> Unit = { mod ->
        when (site) {
            SupportAsk.Site.COFFEE -> CoffeeCup(mod)
            SupportAsk.Site.PATREON -> PatreonMark(mod)
            SupportAsk.Site.KOFI -> KofiMug(mod)
        }
    }
    val frame = Modifier
        .gbaFocusRing(focused, PixelRoundedShape(u * 3))
        .drawBehind {
            drawLayeredBox(
                OptionColors.titleLayers.inPx(u.toPx()), if (focused) OptionColors.listFill else OptionColors.titleFill, radius = 3 * u.toPx(),
            )
        }
        .soundClickable(interactionSource = interaction, indication = null, onClick = onClick)
    if (wide) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.then(frame).padding(horizontal = u * 8, vertical = u * 5)) {
            mark(Modifier.size(cell * 16))
            Spacer(Modifier.width(u * 8))
            GbaText(site.label, OptionColors.value, OptionColors.valueShadow, m)
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier.then(frame).padding(horizontal = u * 4, vertical = u * if (compact) 4 else 7),
        ) {
            mark(Modifier.size(cell * if (compact) 18 else 26))
            Spacer(Modifier.height(u * 5))
            // A tight tile: the short name, its letters narrowed before they shrink.
            if (compact) FittedGbaText(site.shortLabel, OptionColors.value, OptionColors.valueShadow, m)
            else GbaText(site.label, OptionColors.value, OptionColors.valueShadow, m)
        }
    }
}
