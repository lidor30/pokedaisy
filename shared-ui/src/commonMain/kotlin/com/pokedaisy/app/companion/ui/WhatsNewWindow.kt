package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.WhatsNew
import com.pokedaisy.app.companion.i18n.tr

/**
 * The Library's WHAT'S NEW, once after an update to a version with [WhatsNew] notes: the flower and the title, the version
 * as a red pill; then each section under a coloured band (NEW green with a sparkle, FIXES blue with a tick), its lines in
 * the smaller text, each behind a little daisy, the words before a line's colon (its feature, "SHINY ODDS:") in red. The
 * lines scroll on a small screen (the D-pad too), fading out over a down arrow (right, clear of the words) while there's
 * more below; RELEASE PAGE (the GitHub release, for everything else) and OK stay below them.
 */
@Composable
fun WhatsNewWindow(
    notes: WhatsNew.Notes,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    onReleasePage: () -> Unit,
    onClose: () -> Unit,
) {
    val u = m.u
    val scroll = rememberScrollState()
    OptionOverlay(onClose, Modifier.widthIn(max = 760.dp)) {
        Column {
            OptionTitleWindow("WHAT'S NEW", m, leading = { PixelArt(LOGO_ROWS, LOGO_PALETTE, Modifier.size(m.lineHeight)) }) {
                OptionBadge("v${notes.version}", m)
            }
            Spacer(Modifier.height(u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(u * 4)) {
                    Box(Modifier.weight(1f, fill = false)) {
                    Column(Modifier.dpadScroll(scroll, RectangleShape, small.lineHeight * 3).verticalScroll(scroll)) {
                        notes.sections.forEachIndexed { i, section ->
                            if (i > 0) Spacer(Modifier.height(u * 8))
                            SectionBand(section.title, m, Modifier.padding(horizontal = u * 2))
                            section.items.forEach { item ->
                                Spacer(Modifier.height(u * 4))
                                Row(Modifier.padding(horizontal = u * 4)) {
                                    // A daisy, centred on the first line, a font pixel per cell like the words beside it.
                                    val cell = with(LocalDensity.current) { small.fontPixel.toDp() }
                                    PixelArt(
                                        PixelIcons.daisy, mapOf('P' to OptionColors.value, 'C' to OptionColors.favorite),
                                        Modifier.padding(top = (small.lineHeight - cell * 5) / 2).size(cell * 5),
                                    )
                                    Spacer(Modifier.width(u * 5))
                                    GbaRichText(lineParts(item), small, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    if (scroll.canScrollForward) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(small.lineHeight * 1.5f)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, optionLook.listFill))),
                            contentAlignment = Alignment.BottomEnd,
                        ) { ShadowedPixelIcon(PixelIcons.arrowDown, OptionColors.value, OptionColors.valueShadow, m, Modifier.padding(end = u * 4)) }
                    }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(u * 4),
                        modifier = Modifier.fillMaxWidth().padding(top = u * 8),
                    ) {
                        OptionButton(tr("RELEASE PAGE"), m, onClick = onReleasePage, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                        OptionButton(tr("OK"), m, onClick = onClose, emphasis = true, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                    }
                }
            }
        }
    }
}

/** A section's title on its own colour: NEW in the NEW tags' green with a sparkle, FIXES in the field-move blue with a tick. */
@Composable
private fun SectionBand(title: String, m: GbaTextMetrics, modifier: Modifier = Modifier) {
    val u = m.u
    val new = title == WhatsNew.NEW
    val fill = if (new) OptionColors.newBadge else OptionColors.fieldMove
    val shadow = if (new) OptionColors.newBadgeShadow else OptionColors.fieldMoveShadow
    val cell = with(LocalDensity.current) { m.fontPixel.toDp() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.background(fill, PixelRoundedShape(u * 3)).padding(start = u * 6, end = u * 8, top = u * 2, bottom = u * 2),
    ) {
        ShadowedPixelIcon(if (new) PixelIcons.sparkle else PixelIcons.tick, OptionColors.badgeText, shadow, m, cell = cell)
        Spacer(Modifier.width(u * 5))
        GbaText(tr(title), OptionColors.badgeText, shadow, m)
    }
}

/** A line's words before its colon in red (its feature or game: "SHINY ODDS:"; every line has one, WhatsNewTest), the rest grey. */
@Composable
private fun lineParts(item: String): List<Triple<String, Color, Color>> {
    val colon = item.indexOf(": ")
    val rest = Triple(item, OptionColors.label, OptionColors.labelShadow)
    if (colon !in 1..36) return listOf(rest)
    return listOf(
        Triple(item.substring(0, colon + 1), OptionColors.value, OptionColors.valueShadow),
        Triple(item.substring(colon + 1), OptionColors.label, OptionColors.labelShadow),
    )
}
