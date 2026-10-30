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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import kotlin.math.floor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import com.pokedaisy.app.companion.i18n.tr

/** Tiles a row on the MORE grid: every tab can be off the bar (9), so three rows at most. */
private const val MORE_COLUMNS = 4

/** A tile's width in its column (it's square): a little smaller than the column. */
private const val TILE_FRACTION = 0.88f

/** How far a tile's fill goes from a tab chip's grey toward the title window's white: lighter than the bar's chips. */
private const val TILE_LIGHTEN = 0.55f

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
    if (usesKantoMenu) {
        KantoMoreScreen(tabs, onOpen, onConfigure, modifier, usable, m, small)
        return
    }
    Column(modifier.fillMaxSize()) {
        OptionTitleWindow(tk("MORE"), m)
        Spacer(Modifier.height(m.u * 4))
        OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
            // The same room on every side of the grid (the window pads its top and sides alike).
            Column(Modifier.fillMaxSize().padding(m.u * 4)) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(m.u * 6),
                ) {
                    tabs.chunked(MORE_COLUMNS).forEachIndexed { row, line ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(m.u * 6),
                        ) {
                            line.forEachIndexed { col, id ->
                                // Square, a little inside its column, whatever the screen's shape.
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    AppTile(
                                        id, m, enabled = usable(id),
                                        modifier = Modifier.fillMaxWidth(TILE_FRACTION).aspectRatio(1f).focusStart(row == 0 && col == 0),
                                    ) { onOpen(id) }
                                }
                            }
                            repeat(MORE_COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Separator(m, Modifier.padding(vertical = m.u * 4))
                // The line over its button: the button's label (TAB BAR's own name) runs long in some languages.
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    GbaText(
                        tr("Tabs that aren't on the tab bar are here. Pick the bar's tabs in TAB BAR."),
                        OptionColors.label, OptionColors.labelShadow, small, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(m.u * 4))
                    OptionButton(tk("TAB BAR"), m, onClick = onConfigure, pixelIcon = PixelIcons.gear, modifier = Modifier.testTag("more-tab-bar"))
                }
            }
        }
    }
}

/** FR/LG's START-menu idiom: a white command list with a solid cursor and a separate help window. */
@Composable
private fun KantoMoreScreen(
    tabs: List<String>,
    onOpen: (String) -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier,
    usable: (String) -> Boolean,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
) {
    var focused by remember(tabs) { mutableStateOf(tabs.firstOrNull { usable(it) } ?: "TAB BAR") }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
        OptionTitleWindow(tk("MORE"), m)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
            KantoMenuWindow(m, Modifier.widthIn(max = 320.dp).fillMaxWidth()) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    tabs.forEach { id ->
                        KantoMenuCommand(
                            companionTabLabel(id), m, selected = focused == id,
                            modifier = Modifier.focusStart(id == focused).testTag("more-$id"),
                            enabled = usable(id), onFocus = { focused = id }, onClick = { onOpen(id) },
                        )
                    }
                    Separator(m, Modifier.padding(vertical = m.u * 3))
                    KantoMenuCommand(
                        tk("TAB BAR"), m, selected = focused == "TAB BAR",
                        modifier = Modifier.testTag("more-tab-bar"),
                        onFocus = { focused = "TAB BAR" }, onClick = onConfigure,
                    )
                }
            }
        }
        KantoMenuWindow(m, Modifier.fillMaxWidth()) {
            GbaText(
                tr("Tabs that aren't on the tab bar are here. Pick the bar's tabs in TAB BAR."),
                OptionColors.label, OptionColors.labelShadow, small, maxLines = 4,
                modifier = Modifier.padding(horizontal = m.u * 3, vertical = m.u * 2),
            )
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
                drawLayeredBox(OptionColors.chipLayers.inPx(px), fill, radius = 3 * px)
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
