package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.pokedaisy.app.companion.data.LibraryView
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing

/**
 * The library's quick views: a row of pills over the games, one per [LibraryView], each in its own colour - the
 * consoles and HACKS in their [CategoryArt] colours (GB grey, GBC yellow, GBA purple, DS turquoise, HACKS red), ALL
 * slate, FAVORITES the favorite star's gold, RECENT blue. The open one is filled with its colour and [CategoryArt]'s stripes, the
 * others a white pill framed in it. [counts]: how many games each view holds, shown after its name.
 */
@Composable
fun QuickViewPills(
    views: List<LibraryView>,
    selected: LibraryView,
    counts: Map<LibraryView, Int>,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    onPick: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
    /** Inside the scroll, so a pill slides off the screen's edge rather than an inner box's. */
    contentPadding: PaddingValues = PaddingValues(),
    /** Put on the open pill: where D-pad UP from the games' top row lands. */
    focusRequester: FocusRequester? = null,
    /** The launcher's cursor is on the row (its own, not Compose focus): the open pill gets the focus ring. */
    cursor: Boolean = false,
) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    // Each pill's left and right edge in the row's content (inside its padding).
    val edges = remember { mutableStateMapOf<LibraryView, IntRange>() }
    // The open pill scrolled into view (a phone's row is wider than the screen) when it's picked or the library
    // opens on it: just far enough, a pill's gap to spare.
    val density = LocalDensity.current
    val gap = with(density) { (m.u * 4).roundToPx() }
    // The edges are the Row's; the scroll's content starts at its padding.
    val start = with(density) { contentPadding.calculateLeftPadding(LayoutDirection.Ltr).roundToPx() }
    val open = edges[selected]?.let { (it.first + start)..(it.last + start) }
    LaunchedEffect(selected, open, viewport) {
        if (open == null || viewport == 0) return@LaunchedEffect
        val target = when {
            open.first - gap < scroll.value -> open.first - gap
            open.last + gap > scroll.value + viewport -> open.last + gap - viewport
            else -> return@LaunchedEffect
        }
        scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
    }
    Row(
        modifier.onSizeChanged { viewport = it.width }.horizontalScroll(scroll).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(m.u * 4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (view in views) {
            val on = view == selected
            QuickViewPill(
                view, on, counts[view], m, small, cursor = on && cursor,
                Modifier.onPlaced { c -> c.parentLayoutCoordinates?.let { row ->
                    val x = row.localPositionOf(c, androidx.compose.ui.geometry.Offset.Zero).x.toInt()
                    edges[view] = x..(x + c.size.width)
                } }.then(if (on && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            ) { onPick(view) }
        }
    }
}

@Composable
private fun QuickViewPill(
    view: LibraryView, on: Boolean, count: Int?, m: GbaTextMetrics, small: GbaTextMetrics, cursor: Boolean, modifier: Modifier,
    onClick: () -> Unit,
) {
    val look = QuickViewLooks.of(view)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val u = m.u
    val dark = OptionColors.dark
    val text = if (on) Color.White else if (dark) OptionColors.titleText else look.shadow
    val shadow = if (on) look.shadow else OptionColors.titleShadow
    val fill = OptionColors.titleFill
    Row(
        modifier
            .gbaFocusRing(focused || cursor, PixelRoundedShape(u * PILL_RADIUS))
            .semantics { selected = on }
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .drawBehind {
                val px = u.toPx()
                val r = PILL_RADIUS * px
                if (on) {
                    // CategoryArt's backdrop: two shades in diagonal stripes, a cell of 2 GBA pixels.
                    drawPixelRoundRect(look.back, radius = r)
                    clipPath(pixelRoundRectPath(size, r, px)) {
                        val cell = 2 * px
                        val cols = kotlin.math.ceil(size.width / cell).toInt()
                        val rows = kotlin.math.ceil(size.height / cell).toInt()
                        for (y in 0 until rows) for (x in 0 until cols) {
                            if ((x + y) % 4 < 1) drawRect(look.stripe, Offset(x * cell, y * cell), Size(cell, cell))
                        }
                    }
                    drawPixelRoundFrame(look.shadow, Offset.Zero, size, r, 2 * px)
                } else {
                    drawPixelRoundRect(fill, radius = r)
                    drawPixelRoundFrame(look.back, Offset.Zero, size, r, 3 * px)
                }
            }
            .padding(horizontal = u * 7, vertical = u * 5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (view) {
            LibraryView.FAVORITES -> PixelIcons.star
            LibraryView.RECENT -> PixelIcons.clock
            else -> null
        }
        if (icon != null) {
            PixelIcon(icon, if (on) Color.White else look.back, Modifier.size(small.lineHeight))
            Spacer(Modifier.width(u * 3))
        }
        // The list's smaller text (the paths'): the rows' size made the row too loud on a big screen.
        GbaText(quickViewLabel(view), text, shadow, small)
        if (count != null) {
            Spacer(Modifier.width(u * 3))
            GbaText(count.toString(), text.copy(alpha = 0.7f), Color.Transparent, small)
        }
    }
}

/** [view]'s name in the app's language (literal keys, for the translation check); the consoles' stay as they are. */
fun quickViewLabel(view: LibraryView): String = when (view) {
    LibraryView.ALL -> tr("ALL")
    LibraryView.FAVORITES -> tr("FAVORITES")
    LibraryView.RECENT -> tr("RECENT")
    LibraryView.HACKS -> tr("HACKS")
    else -> view.label
}

/** A pill's corner, in GBA pixels: a rounded box rather than a full pill. */
private const val PILL_RADIUS = 5

/** Each quick view's colours: [CategoryLook]s, the consoles' being [CategoryLooks]' own. */
object QuickViewLooks {
    private fun c(rgb: Long) = Color(0xFF000000 or rgb)

    private val ALL = CategoryLook(c(0x4A5670), c(0x404B63), c(0xFFFFFF), c(0x1C2233))
    private val FAVORITES = CategoryLook(c(0xE0A010), c(0xCC9008), c(0xFFFFFF), c(0x6E4A00))
    private val RECENT = CategoryLook(c(0x2F7FD8), c(0x2670C4), c(0xFFFFFF), c(0x0C315E))

    fun of(view: LibraryView): CategoryLook = when (view) {
        LibraryView.ALL -> ALL
        LibraryView.FAVORITES -> FAVORITES
        LibraryView.RECENT -> RECENT
        else -> CategoryLooks.of(view.platform ?: view.category!!)
    }
}
