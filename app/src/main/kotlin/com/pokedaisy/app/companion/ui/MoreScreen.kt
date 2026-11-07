package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import kotlin.math.floor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import com.pokedaisy.app.companion.i18n.tr

/** A tile's smallest width, in GBA pixels: the grid takes as many columns as fit tiles this wide. */
private const val TILE_MIN = 64

/** At most this many tiles a row, however wide the tab (the Thor's bottom screen fits four). */
private const val MAX_COLUMNS = 4

/** The room between two tiles, and between the tiles and the window's edge: the same everywhere, in GBA pixels. */
private const val TILE_GAP = 6

/** How far a tile's fill goes from a tab chip's grey toward the title window's white: lighter than the bar's chips. */
private const val TILE_LIGHTEN = 0.9f

/** How much of a button's darker lower half a tile keeps ([twoToneLow]): a lighter grey than the bar's chips. */
private const val TILE_LOW = 0.5f

/**
 * The MORE tab (left of the gear, at the bar's end): every tab this game has that isn't on the tab bar ([tabs], ids), as app
 * icons on a grid ([TabIcons]) - a tap opens it ([onOpen]). Under the grid, a line saying the bar's tabs are
 * the player's pick and a TAB BAR button to SETTINGS' page for it ([onConfigure]). A tab [usable] says no to
 * (an unsupported ROM: only ACHIEVEMENTS works there) is greyed out.
 */
@Composable
internal fun MoreScreen(
    tabs: List<String>,
    onOpen: (String) -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    usable: (String) -> Boolean = { true },
) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    Column(modifier.fillMaxSize()) {
        OptionTitleWindow(tk("MORE"), m)
        Spacer(Modifier.height(m.u * 4))
        OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
            // One gap everywhere: between tiles across and down, and round the grid (the window pads its top and sides
            // alike); the tiles fill their columns, so no column slack adds to it.
            val gap = m.u * TILE_GAP
            Column(Modifier.fillMaxSize().padding(gap)) {
                // Square tiles across the whole width: as many columns as fit tiles at least [TILE_MIN] wide, [MAX_COLUMNS]
                // at most (fewer on a small screen), each stretched to share the row; the gaps stay the same, more rows scroll.
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val columns = ((maxWidth + gap) / (m.u * TILE_MIN + gap)).toInt().coerceIn(1, MAX_COLUMNS)
                    val tile = (maxWidth - gap * (columns - 1)) / columns
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        tabs.chunked(columns).forEachIndexed { row, line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                line.forEachIndexed { col, id ->
                                    AppTile(
                                        id, m, enabled = usable(id),
                                        modifier = Modifier.size(tile).focusStart(row == 0 && col == 0),
                                    ) { onOpen(id) }
                                }
                            }
                        }
                    }
                }
                Separator(m, Modifier.padding(vertical = gap))
                // The line left of its button, which keeps its whole label; the line wraps in what's left.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    GbaText(
                        tr("Tabs that aren't on the tab bar are here. Pick the bar's tabs in TAB BAR."),
                        OptionColors.label, OptionColors.labelShadow, small, maxLines = 4, modifier = Modifier.weight(1f),
                    )
                    OptionButton(tk("TAB BAR"), m, onClick = onConfigure, pixelIcon = PixelIcons.gear, modifier = Modifier.testTag("more-tab-bar"))
                }
            }
        }
    }
}

/** One app on the MORE grid: its coloured icon over its name, in a tab chip's frame. */
@Composable
private fun AppTile(id: String, m: GbaTextMetrics, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val u = m.u
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val label = companionTabLabel(id)
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .gbaFocusRing(focused, PixelRoundedShape(u * 3))
            .drawBehind {
                val px = u.toPx()
                val fill = if (focused) OptionColors.tabSelectedFill else lerp(OptionColors.tabIdleFill, OptionColors.titleFill, TILE_LIGHTEN)
                // The lower half half as far toward slate as a button's: a light tile, still two-tone.
                val low = lerp(fill, twoToneLow(fill), TILE_LOW)
                drawLayeredBox(OptionColors.chipLayers.inPx(px), fill, radius = 3 * px, twoTone = true, low = low)
            }
            .soundClickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("more-$id")
            .padding(u * 4),
    ) {
        // The icon takes what the square leaves under it for the name, in whole screen pixels per icon pixel (crisp),
        // three GBA pixels per icon pixel at most.
        val density = LocalDensity.current
        val iconPx = with(density) {
            val room = minOf(maxWidth.toPx(), maxHeight.toPx() - m.lineHeight.toPx() - (u * 4).toPx())
            val perPixel = minOf(floor(room / TabIcons.SIZE), floor((u * 3).toPx())).coerceAtLeast(1f)
            perPixel * TabIcons.SIZE
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            TabIcons.forTab(id)?.let { icon ->
                PixelArt(icon.bitmap, icon.palette, Modifier.size(with(density) { iconPx.toDp() }))
            }
            Spacer(Modifier.height(u * 4))
            // Narrowed letters rather than cut short: NUZLOCKE is the longest name.
            GbaText(label, OptionColors.label, OptionColors.labelShadow, m, bold = gameBoldLabels(), maxLines = 1, modifier = Modifier.narrowToFit(m))
        }
    }
}
