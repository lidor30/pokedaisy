package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import com.pokedaisy.app.companion.data.LibraryView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.RomCategory
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing

enum class LibrarySupport { FULL, PARTIAL, UNSUPPORTED, BLOCKED }

/** Data the library draws. Native files, permissions, import and launching belong to its host. */
data class LibraryGame(
    val id: String, val label: String, val path: String = id,
    val playing: Boolean = false, val isNew: Boolean = false, val hasSave: Boolean = false,
    val favorite: Boolean = false, val support: LibrarySupport = LibrarySupport.FULL,
)

object LibraryBadgeColors {
    val blocked = Color(0xFF202020)
    val blockedShadow = Color(0x99000000)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LibraryCard(
    selected: Boolean,
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Where D-pad UP goes from the top row, if there is no card above it. */
    upTo: () -> FocusRequester? = { null },
    /** The launcher's cursor is on it (the launcher moves its own, not Compose focus): ring and white fill. */
    cursor: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hasFocus by interaction.collectIsFocusedAsState()
    val focused = hasFocus || cursor
    OptionListWindow(
        m,
        modifier
            // The list window's own corner (OptionListWindow draws its frame at a 2u radius).
            .gbaFocusRing(focused, PixelRoundedShape(m.u * 2))
            .focusProperties { upTo()?.let { up = it } }
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick),
        fill = if (selected || focused) OptionColors.rowSelected else OptionColors.listFill,
    ) { content() }
}

@Composable
private fun LibraryFavoriteStar(size: Dp) {
    PixelIcon(PixelIcons.star, OptionColors.favorite,
        Modifier.size(size).semantics { contentDescription = tr("Favorite") })
}

@Composable
fun LibraryRomRow(
    game: LibraryGame, m: GbaTextMetrics, small: GbaTextMetrics,
    selected: Boolean = game.playing, cursor: Boolean = false, upTo: () -> FocusRequester? = { null },
    showPath: Boolean = true,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    cover: @Composable (Modifier) -> Unit, menu: @Composable () -> Unit,
) {
    val isLast = game.playing
    val isNew = game.isNew
    val display = game.label
    val hasSave = game.hasSave
    val favorite = game.favorite
    LibraryCard(
        // In the launcher only its cursor gets the white fill (two white rows read as two cursors).
        selected, m, Modifier.fillMaxWidth(),
        onLongClick = onLongClick, upTo = upTo,
        cursor = cursor,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            cover(Modifier.height(56.dp))
            Spacer(Modifier.width(m.u * 6))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GbaText(display, OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f, fill = false))
                    if (favorite) {
                        Spacer(Modifier.width(m.u * 4))
                        LibraryFavoriteStar(small.lineHeight)
                    }
                    if (hasSave) {
                        Spacer(Modifier.width(m.u * 4))
                        Icon(
                            LibraryIcons.Save, contentDescription = tr("Save file exists"),
                            tint = OptionColors.label, modifier = Modifier.size(small.lineHeight),
                        )
                    }
                    if (isLast) {
                        Spacer(Modifier.width(m.u * 4))
                        GbaText(tr("PLAYING"), OptionColors.value, OptionColors.valueShadow, small)
                    }
                    if (isNew) {
                        Spacer(Modifier.width(m.u * 4))
                        OptionBadge(tk("NEW"), m, fill = OptionColors.newBadge)
                    }
                    if (game.support == LibrarySupport.BLOCKED) {
                        Spacer(Modifier.width(m.u * 4))
                        OptionBadge(tk("BLOCKED"), m, fill = LibraryBadgeColors.blocked)
                    } else if (game.support == LibrarySupport.UNSUPPORTED) {
                        Spacer(Modifier.width(m.u * 4))
                        OptionBadge(tk("NOT SUPPORTED"), m)
                    } else if (game.support == LibrarySupport.PARTIAL) {
                        Spacer(Modifier.width(m.u * 4))
                        OptionBadge(tk("PARTIALLY SUPPORTED"), m, fill = OptionColors.muted)
                    }
                }
                if (showPath) {
                    GbaText(
                        game.path,
                        OptionColors.muted, OptionColors.mutedShadow, small,
                    )
                }
            }
            menu()
        }
    }
}


@Composable
fun LibraryRomTile(
    game: LibraryGame, m: GbaTextMetrics, small: GbaTextMetrics,
    selected: Boolean = game.playing, cursor: Boolean = false, upTo: () -> FocusRequester? = { null },
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    cover: @Composable (Modifier) -> Unit, menu: @Composable () -> Unit,
) {
    val isNew = game.isNew
    val display = game.label
    val hasSave = game.hasSave
    val favorite = game.favorite
    Box {
        LibraryCard(
            selected, m, Modifier.fillMaxWidth(),
            onLongClick = onLongClick,
            upTo = upTo,
            cursor = cursor,
            onClick = onClick,
        ) {
            val support = when (game.support) {
                LibrarySupport.BLOCKED -> tk("BLOCKED") to LibraryBadgeColors.blocked
                LibrarySupport.UNSUPPORTED -> tk("NOT SUPPORTED") to OptionColors.value
                LibrarySupport.PARTIAL -> tk("PARTIALLY SUPPORTED") to OptionColors.muted
                else -> null
            }
            Column {
                // The tags sit on the cover's top-left corner, inside its frame, so they take no line of their own.
                Box {
                    cover(Modifier.fillMaxWidth())
                    if (isNew || support != null) {
                        Column(
                            Modifier.align(Alignment.TopStart).padding(m.u * 3),
                            verticalArrangement = Arrangement.spacedBy(m.u * 2),
                        ) {
                            if (isNew) OptionBadge(tk("NEW"), m, fill = OptionColors.newBadge)
                            support?.let { (label, fill) -> OptionBadge(label, m, fill = fill) }
                        }
                    }
                }
                Spacer(Modifier.height(m.u * 3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GbaText(display, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f))
                    if (favorite) {
                        Spacer(Modifier.width(m.u * 2))
                        LibraryFavoriteStar(small.lineHeight)
                    }
                    if (hasSave) {
                        Spacer(Modifier.width(m.u * 2))
                        Icon(
                            LibraryIcons.Save, contentDescription = tr("Save file exists"),
                            tint = OptionColors.label, modifier = Modifier.size(small.lineHeight),
                        )
                    }
                }
            }
        }
        menu()
    }
}


@Composable
fun LibraryCover(bitmap: ImageBitmap?, category: RomCategory?, m: GbaTextMetrics, modifier: Modifier = Modifier) {
    val u = m.u
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(PixelRoundedShape(u * 3))
            .background(OptionColors.imageWell)
            .drawWithContent {
                drawContent()
                val px = u.toPx()
                drawLayeredFrame(listOf(OptionColors.frameDark to px, OptionColors.frameLight to px), radius = 3 * px)
            },
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None,
            )
        } else {
            // No art: the game's kind (GB / GBC / GBA / NDS / HACK) as a little pixel picture, once the background
            // title pass has looked at the file (GameTitles.identify); the plain icon until then.
            if (category != null) {
                CategoryArt(category, Modifier.fillMaxSize())
            } else {
                Icon(
                    LibraryIcons.VideogameAsset, contentDescription = null,
                    tint = OptionColors.muted, modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** The library's existing header and GAMES / TOOLS / SETTINGS rail, with platform work supplied as content/actions. */
@Composable
fun LibraryFrame(
    section: LibrarySection, m: GbaTextMetrics, small: GbaTextMetrics,
    onSection: (LibrarySection) -> Unit, modifier: Modifier = Modifier,
    toolsBadge: Boolean = false, compactTitle: Boolean = false, headerGap: Dp = m.u * 8, showHeader: Boolean = true,
    header: @Composable RowScope.() -> Unit,
    overlays: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= maxHeight
        AppBackdrop(logos = true)
        Row(Modifier.fillMaxSize()) {
            if (wide) LibraryNav(section, vertical = true, m, small, toolsBadge = toolsBadge, top = 16.dp, onPick = onSection)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (showHeader) {
                    OptionTitleWindow(if (compactTitle) "" else "POKéDAISY", m,
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                        leading = { PixelArt(LOGO_ROWS, LOGO_PALETTE, Modifier.size(m.lineHeight)) }, actions = header)
                    Spacer(Modifier.height(headerGap))
                }
                content()
                if (!wide) LibraryNav(section, vertical = false, m, small, toolsBadge = toolsBadge, onPick = onSection)
            }
        }
        overlays()
    }
}

@Composable
fun LibraryHeaderIcon(icon: ImageVector, label: String, m: GbaTextMetrics, modifier: Modifier = Modifier,
                      compact: Boolean = false, cursor: Boolean = false, onClick: () -> Unit) {
    Spacer(Modifier.width(m.u * if (compact) 2 else 4))
    OptionButton(null, m, onClick = onClick, modifier = modifier.gbaFocusRing(cursor, PixelRoundedShape(m.u * 3)),
        icon = icon, contentDescription = label, sidePadding = if (compact) 4 else 8)
}

/** A host's available game actions, drawn by one shared menu. [opens] lets a launcher retain its cursor for a page. */
class LibraryMenuItem(
    val label: String, val icon: ImageVector, val danger: Boolean = false, val opens: Boolean = false,
    val pixel: List<String>? = null, val onClick: () -> Unit,
)

private val LibraryMenuBorder get() = if (OptionColors.dark) Color(0xFF4A5262) else Color(0xFFA8A8B0)

@Composable
fun LibraryGameMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<LibraryMenuItem>, m: GbaTextMetrics, screenSize: DpSize) {
    @Composable
    fun MenuItem(item: LibraryMenuItem) {
        val fg = if (item.danger) OptionColors.value else OptionColors.label
        DropdownMenuItem(
            text = { GbaText(item.label, fg, if (item.danger) OptionColors.valueShadow else OptionColors.labelShadow, m) },
            leadingIcon = {
                val px = item.pixel
                if (px != null) com.pokedaisy.app.companion.ui.PixelIcon(px, fg, Modifier.size(24.dp)) else Icon(item.icon, null, tint = fg)
            },
            onClick = { onDismiss(); item.onClick() },
        )
    }
    // Two columns where they fit across the screen and one would run off it, or fill it: the list had grown past a
    // landscape screen's height, and held upright it took the whole screen (the user's call).
    val itemHeight = maxOf(48.dp, m.lineHeight + 16.dp)
    // Upright the columns share the screen's width (a phone has no room for two of 280.dp).
    val upright = screenSize.height > screenSize.width && screenSize.width >= 320.dp
    val twoColumns = upright || (
        screenSize.width >= 280.dp * 2 + 32.dp && itemHeight * items.size + 16.dp > screenSize.height - 32.dp
        )
    // Square corners on the popup's own surface (its shape must be a
    // CornerBasedShape - no pixel steps possible), and a subtle 1u frame
    // just inside it rather than Material's smooth-rounded edge.
    val u = m.u
    MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(0.dp))) {
        DropdownMenu(
            expanded = expanded, onDismissRequest = onDismiss,
            modifier = Modifier
                .background(OptionColors.titleFill)
                .drawWithContent {
                    drawContent()
                    drawLayeredFrame(listOf(LibraryMenuBorder to u.toPx()), radius = 0f)
                },
        ) {
            if (!twoColumns) {
                items.forEach { MenuItem(it) }
            } else {
                // Down the first column, then the second (DELETE last, bottom right); a 1u rule between.
                val half = (items.size + 1) / 2
                val rowWidth = if (upright) Modifier.width(screenSize.width - 32.dp) else Modifier
                Row(rowWidth.height(IntrinsicSize.Min)) {
                    val column = if (upright) Modifier.weight(1f) else Modifier.width(IntrinsicSize.Max)
                    Column(column) { items.take(half).forEach { MenuItem(it) } }
                    Box(Modifier.fillMaxHeight().padding(vertical = u * 4).width(u).background(LibraryMenuBorder))
                    Column(column) { items.drop(half).forEach { MenuItem(it) } }
                }
            }
        }
    }
}


@Composable
fun LibraryMenuDots(m: GbaTextMetrics, onClick: () -> Unit) {
    Box(Modifier.clip(PixelRoundedShape(m.u * 3)).clickable(onClick = onClick).padding(m.u * 3)) {
        Icon(Icons.Filled.MoreVert, contentDescription = tr("ROM options"), tint = OptionColors.label,
            modifier = Modifier.size(m.lineHeight))
    }
}

@Composable
fun libraryLongPress(open: () -> Unit): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return { haptic.performHapticFeedback(HapticFeedbackType.LongPress); open() }
}

/** The same list/grid spacing, stable keys and UP-to-header routing on every host. Items keep their native identity. */
@Composable
fun <T> LibraryCollection(
    games: List<T>, grid: Boolean, m: GbaTextMetrics, key: (T) -> String,
    modifier: Modifier = Modifier, headerUp: () -> FocusRequester? = { null },
    item: @Composable (T, () -> FocusRequester?) -> Unit,
) {
    val padding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
    val gap = Arrangement.spacedBy(m.u * 4)
    if (grid) {
        val state = rememberLazyGridState()
        LazyVerticalGrid(GridCells.Adaptive(150.dp), state = state, modifier = modifier.fillMaxWidth(),
            contentPadding = padding, horizontalArrangement = gap, verticalArrangement = gap) {
            gridItemsIndexed(games, key = { _, game -> key(game) }) { index, game ->
                item(game) { headerUp()?.takeIf { state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.row == 0 } }
            }
        }
    } else {
        LazyColumn(modifier.fillMaxWidth(), contentPadding = padding, verticalArrangement = gap) {
            itemsIndexed(games, key = { _, game -> key(game) }) { index, game ->
                item(game) { headerUp().takeIf { index == 0 } }
            }
        }
    }
}

/** Display state for one library screen, independent of each host's file and preference types. */
data class LibraryPresentation<T>(
    val games: List<T>, val grid: Boolean, val section: LibrarySection,
    val views: List<LibraryView>, val view: LibraryView, val counts: Map<LibraryView, Int>,
    val hasCatalog: Boolean, val query: String, val emptyText: String,
    val tools: List<LibraryTool> = emptyList(),
)

typealias LibraryActions = com.pokedaisy.app.companion.LibraryActions

class LibraryFocus(val header: FocusRequester = FocusRequester(), val views: FocusRequester = FocusRequester()) {
    var headerAttached = false
    var viewsAttached = false
    val up get() = views.takeIf { viewsAttached } ?: header.takeIf { headerAttached }
}

/** One library screen on every platform. Hosts supply data, supported actions, native settings and card callbacks. */
@Composable
fun <T> LibraryScreen(
    state: LibraryPresentation<T>, actions: LibraryActions, m: GbaTextMetrics, small: GbaTextMetrics,
    key: (T) -> String, modifier: Modifier = Modifier, focus: LibraryFocus = remember { LibraryFocus() },
    overlays: @Composable () -> Unit = {}, settings: @Composable ColumnScope.() -> Unit = {},
    item: @Composable (T, () -> FocusRequester?) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val toolsOpen = state.section == LibrarySection.TOOLS
        LibraryFrame(state.section, m, small, actions.section,
            toolsBadge = state.tools.any { it.newBadge != null } && !toolsOpen, showHeader = state.section != LibrarySection.SETTINGS,
            compactTitle = compact && !toolsOpen && state.section == LibrarySection.GAMES,
            headerGap = m.u * if (state.hasCatalog || state.section != LibrarySection.GAMES) 8 else 4,
            header = {
                if (state.section == LibrarySection.GAMES) {
                    LibraryHeaderIcon(LibraryIcons.Sort, tr("Sort"), m,
                        Modifier.focusRequester(focus.header), compact = compact, onClick = actions.sort)
                    DisposableEffect(focus) { focus.headerAttached = true; onDispose { focus.headerAttached = false } }
                    LibraryHeaderIcon(Icons.Filled.Search, tr("SEARCH"), m, compact = compact, onClick = actions.search)
                    LibraryHeaderIcon(if (state.grid) LibraryIcons.ViewList else LibraryIcons.GridView,
                        tr("Toggle view"), m, compact = compact, onClick = actions.toggleGrid)
                    LibraryHeaderIcon(Icons.Filled.Add, tr("Add games"), m, compact = compact, onClick = actions.add)
                    LibraryHeaderIcon(Icons.Filled.Refresh, tr("Refresh"), m, compact = compact, onClick = actions.refresh)
                }
                LibraryHeaderIcon(LibraryIcons.HelpOutline, tr("Help"), m, compact = compact, onClick = actions.help)
            }, overlays = overlays,
        ) {
            when (state.section) {
                LibrarySection.SETTINGS -> settings()
                LibrarySection.TOOLS -> ToolsPage(state.tools, m, small,
                    PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), Modifier.weight(1f))
                LibrarySection.GAMES -> {
                    LibrarySearchStrip(state.query, m, actions.search, actions.query)
                    if (state.hasCatalog) {
                        QuickViewPills(state.views, state.view, state.counts, m, small, actions.view,
                            contentPadding = PaddingValues(horizontal = 16.dp), focusRequester = focus.views)
                        DisposableEffect(focus) { focus.viewsAttached = true; onDispose { focus.viewsAttached = false } }
                        Spacer(Modifier.height(m.u * 8))
                    }
                    if (state.games.isEmpty()) {
                        OptionListWindow(m, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            GbaText(if (state.hasCatalog) libraryEmptyViewText(state.view)
                                else if (state.query.isNotBlank()) tr("No games match your search.") else state.emptyText,
                                OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                                modifier = Modifier.padding(m.u * 4))
                        }
                    } else androidx.compose.runtime.key(state.view) {
                        LibraryCollection(state.games, state.grid, m, key, Modifier.weight(1f), headerUp = { focus.up }, item = item)
                    }
                }
            }
        }
    }
}

fun libraryEmptyViewText(view: LibraryView) =
    if (view == LibraryView.FAVORITES) tr("No favorites yet. Long press a game and pick FAVORITE to keep it here.")
    else tr("No games played yet. The games you play show here, the last one first.")

@Composable
fun LibrarySearchDialog(query: String, onQuery: (String) -> Unit, m: GbaTextMetrics, small: GbaTextMetrics, onDismiss: () -> Unit) {
    OptionOverlay(onDismiss, Modifier.widthIn(max = 520.dp)) {
        Column {
            OptionTitleWindow(tr("SEARCH"), m)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column {
                    OptionTextField(
                        query, onQuery, small,
                        Modifier.padding(horizontal = m.u * 8, vertical = m.u * 2),
                        placeholder = tr("SEARCH GAMES"), leadingIcon = com.pokedaisy.app.companion.ui.PixelIcons.search,
                        // Opened to type in: the field and its keyboard at once.
                        typeAtOnce = true,
                    )
                    OptionLine(tr("CLEAR"), null, selected = false, m, height = m.rowHeight * 1.3f) { onQuery(""); onDismiss() }
                    OptionLine(tr("DONE"), null, selected = true, m, height = m.rowHeight * 1.3f, onClick = onDismiss)
                }
            }
        }
    }
}


@Composable
fun LibrarySearchStrip(query: String, m: GbaTextMetrics, onSearch: () -> Unit, onQuery: (String) -> Unit) {
    if (query.isBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = m.u * 4),
        horizontalArrangement = Arrangement.spacedBy(m.u * 4),
    ) {
        OptionButton(tr("SEARCH") + ": " + query, m, modifier = Modifier.weight(1f, fill = false), onClick = onSearch)
        OptionButton(tr("CLEAR"), m, onClick = { onQuery("") })
    }
}
