package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A short label on a pill of the style's section colour (battle INFO's YOU / FOE, SUGGESTIONS' 1ST / 2ND / 3RD). */
@Composable
internal fun StylePill(text: String, small: GbaTextMetrics, modifier: Modifier = Modifier, fill: Color? = null, shadow: Color? = null) {
    val style = OptionColors.style
    val u = small.u
    val f = fill ?: style?.let { OptionColors.tint(it.sectionFill) } ?: OptionColors.value
    Box(
        // Notched like the games' tags (GameTag), the other tags beside it.
        modifier.background(f, NotchedShape(u)).padding(horizontal = u * 4, vertical = u),
        contentAlignment = Alignment.Center,
    ) {
        GbaText(text, style?.sectionText ?: Color.White, shadow ?: style?.sectionShadow ?: OptionColors.valueShadow, small, maxLines = 1)
    }
}

/** How thick a block's lines are, in GBA pixels (rounded down to whole screen pixels). */
internal const val CELL_LINE = 1.5f

/**
 * One cell of a connected block (HG's stats and move boxes, Black's move plates): filled [fill], ruled in [line]
 * ([CELL_LINE] thick). Cells that meet share one line - a cell not on the block's [top] / [left] edge draws that edge as
 * its divider and runs its fill under its neighbour's - and only the block's outer corners are rounded ([radius] GBA
 * pixels, [u] each).
 */
internal fun Modifier.cell(
    fill: Color, line: Color, u: androidx.compose.ui.unit.Dp,
    top: Boolean, bottom: Boolean, left: Boolean = true, right: Boolean = true, radius: Int = 3,
): Modifier = drawBehind {
    val px = u.toPx()
    val lw = kotlin.math.floor(CELL_LINE * px).coerceAtLeast(1f)
    val r = radius * px
    val x0 = if (left) 0f else -r - lw
    val x1 = if (right) size.width else size.width + r + lw
    val y0 = if (top) 0f else -r - lw
    val y1 = if (bottom) size.height else size.height + r + lw
    clipRect {
        drawPixelRoundRect(line, Offset(x0, y0), Size(x1 - x0, y1 - y0), r, step = px)
        drawPixelRoundRect(fill, Offset(x0 + lw, y0 + lw), Size(x1 - x0 - 2 * lw, y1 - y0 - 2 * lw), (r - lw).coerceAtLeast(0f), step = px)
        if (!top) drawRect(line, Offset.Zero, Size(size.width, lw))
        if (!left) drawRect(line, Offset.Zero, Size(lw, size.height))
    }
}

/** A heading cell, a block's first: [text] in the block's heading colours, [trailing] at its right end. */
@Composable
internal fun HeadCell(
    text: String,
    c: com.pokedaisy.app.companion.ui.theme.CellColors,
    small: GbaTextMetrics,
    modifier: Modifier = Modifier,
    top: Boolean = true,
    bottom: Boolean = false,
    fill: Color = c.head,
    shadow: Color = c.headShadow,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val u = small.u
    Row(
        modifier.fillMaxWidth().cell(fill, c.line, u, top, bottom).padding(horizontal = u * 5, vertical = u * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GbaText(text, c.headText, shadow, small, Modifier.weight(1f), maxLines = 1)
        trailing?.invoke(this)
    }
}

/** The most a move's row grows to, in GBA pixels, where a list shares out a tall pane (the summary's four moves, battle
 * INFO's, the move page's). Past it the rows keep their size and the list ends early instead of stretching down. */
internal const val MOVE_ROW_MAX = 50

/** SUGGESTIONS' rows (a Pokémon's card beside its best move): three lines on the left, so a little more room. */
internal const val SUGGESTION_ROW_MAX = 66

/** A row's share of a [ColumnScope]'s height, but at most [max] GBA pixels ([u] each): it fills its share up to that. */
internal fun androidx.compose.foundation.layout.ColumnScope.cappedRow(u: androidx.compose.ui.unit.Dp, max: Int = MOVE_ROW_MAX): Modifier =
    Modifier.weight(1f, fill = false).heightIn(max = u * max).fillMaxHeight()

/**
 * A block of cells with a heading cell over it ([title], in the block's heading colours) - but only where the pane has
 * room for the heading and [minBody] under it; a pane too short (the RG DS) keeps its rows. [content] is told whether the
 * heading is there, so its first cell joins it ([cell]'s `top`). In CLASSIC (no cells) there's never a heading.
 */
@Composable
internal fun HeadedBlock(
    title: String,
    small: GbaTextMetrics,
    modifier: Modifier,
    minBody: androidx.compose.ui.unit.Dp,
    c: com.pokedaisy.app.companion.ui.theme.CellColors? = OptionColors.cells,
    content: @Composable ColumnScope.(headed: Boolean) -> Unit,
) {
    if (c == null) {
        Column(modifier) { content(false) }
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val head = small.lineHeight + small.u * 4
        val headed = !constraints.hasBoundedHeight || maxHeight - head >= minBody
        Column(Modifier.fillMaxWidth().then(if (constraints.hasBoundedHeight) Modifier.fillMaxHeight() else Modifier)) {
            if (headed) HeadCell(title, c, small)
            content(headed)
        }
    }
}

/** The body of a [HeadedBlock] that isn't cells of its own (the summary's INFO, STATS): one cell under the heading. */
internal fun Modifier.headedBody(headed: Boolean, u: androidx.compose.ui.unit.Dp): Modifier {
    val c = OptionColors.cells ?: return this
    return if (!headed) this else cell(c.a, c.line, u, top = false, bottom = true).padding(horizontal = u * 4, vertical = u * 3)
}
