package com.pokedaisy.app.companion.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pokedaisy.app.companion.data.BoxView
import com.pokedaisy.app.companion.data.BoxesView
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.PokedexSource
import com.pokedaisy.app.companion.data.PokedexTables
import com.pokedaisy.app.companion.data.itemName
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import kotlin.math.floor
import kotlin.math.roundToInt

/** The BOXES tab's place, kept across tab switches: the box on show, the slot looked at last, the summary open. */
class BoxesUiState {
    /** -1 = the box the PC opens on (the save's current box). */
    var box by mutableIntStateOf(-1)
    /** The slot whose Pokémon the info panel shows (the one tapped or focused last); null = the box's first. */
    var looked by mutableStateOf<Int?>(null)
    /** The open summary's index among the box's Pokémon; null = the box. */
    var open by mutableStateOf<Int?>(null)
    var picking by mutableStateOf(false)
    /** SWAP's party pick is up for this box Pokémon; null = not. */
    var swapping by mutableStateOf<MonView?>(null)
    /** What the last SWAP did, in a window until OK; null = none. */
    var swapMessage by mutableStateOf<String?>(null)

    fun boxOf(boxes: BoxesView): Int = (if (box >= 0) box else boxes.currentBox).coerceIn(0, boxes.boxes.size - 1)
}

/**
 * The BOXES tab: the PC's box screen in the app's own drawing, so every game that can read its boxes gets it, in the
 * colours of FireRed's (sampled from its screen: [PcColors], [BoxWallpapers]) - the PKMN DATA panel on the left
 * (picture well, name, level, item), the box on the right filling the rest: its name on the wallpaper's header strip
 * between two arrows, then the wallpaper with its bevelled frame and the 30 Pokémon (6 x 5), their icons at the largest
 * whole scale the cells take, all over the PC's peach backdrop. A tall tab puts the panel over the box. A tap on a
 * Pokémon opens its summary (like PARTY's); the arrows, a swipe or the name (a pick-list) change the box. With the
 * D-pad, the focused Pokémon shows in the panel.
 */
@Composable
fun BoxesScreen(boxes: BoxesView, ui: BoxesUiState, dex: PokedexTables?, modifier: Modifier = Modifier) {
    val boxIndex = ui.boxOf(boxes)
    val box = boxes.boxes[boxIndex]
    fun step(by: Int) {
        ui.box = Math.floorMod(boxIndex + by, boxes.boxes.size)
        ui.looked = null
    }
    val m = rememberGbaTextMetrics()
    val u = m.u
    val firstMon = box.mons.indexOfFirst { it != null }
    val lookedSlot = ui.looked?.takeIf { box.mons.getOrNull(it) != null } ?: firstMon
    val looked = box.mons.getOrNull(lookedSlot)

    // The PC's peach backdrop is the screen's while this tab is open (CompanionScreen draws it, like the bag's).
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Side by side unless the tab is taller than it is wide (an upright phone's).
        val sideBySide = maxWidth >= maxHeight
        val gap = u * 4
        val content = @Composable { boxModifier: Modifier ->
            BoxWindow(box, boxIndex, lookedSlot, ui, m, ::step, boxModifier)
        }
        if (sideBySide) {
            // The window about as wide as the tab's height allows a tall picture in, never most of the tab.
            val panelW = minOf(maxWidth * 0.36f, maxHeight * 0.5f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                MonPanel(looked, dex, m, Modifier.width(panelW).fillMaxHeight(), wide = false)
                content(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            val panelH = minOf(maxHeight * 0.3f, maxWidth * 0.45f)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                MonPanel(looked, dex, m, Modifier.fillMaxWidth().height(panelH), wide = true)
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    if (ui.picking) {
        OptionSelector(
            title = tr("BOXES"),
            options = boxes.boxes.indices.toList(),
            selected = boxIndex,
            label = { i -> boxes.boxes[i].let { b -> "${b.name}   ${b.count}/$SLOTS" } },
            m = m,
            onPick = { ui.box = it; ui.looked = null; ui.picking = false },
            onDismiss = { ui.picking = false },
        )
    }
}

/** The Pokémon of [box] as the summary pages through them (empty slots skipped). */
fun boxSummaryList(box: BoxView): List<MonView> = box.mons.filterNotNull()

/**
 * SWAP's party pick: the PARTY tab's own slots, a tap trades that Pokémon for [boxMon] ([onPick] with its slot). A
 * Pokémon holding Mail is dimmed (the game wants the Mail taken first).
 */
@Composable
internal fun BoxSwapPicker(boxMon: MonView?, party: List<MonView>, onCancel: () -> Unit, onPick: (Int, MonView) -> Unit) {
    val m = rememberGbaTextMetrics()
    CompanionBackHandler(onBack = onCancel)
    fun holdsMail(mon: MonView) = mon.heldItemId in com.pokedaisy.app.companion.data.MAIL_ITEMS
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.u * 3)) {
        PartyGrid(
            party, selected = -1,
            onSlotClick = { mon -> if (!holdsMail(mon)) onPick(party.indexOf(mon), mon) },
            modifier = Modifier.fillMaxWidth().weight(1f),
            dimmed = ::holdsMail,
        )
        OptionTitleWindow(
            tr("Swap {0} with which POKéMON?", boxMon?.shownName.orEmpty()), m,
            leading = { SpeciesIcon(boxMon?.iconAsset, m.lineHeight * 1.5f) },
            titleMaxLines = 2,
        ) {
            OptionButton(tr("CANCEL"), m, onClick = onCancel, modifier = Modifier.padding(start = m.u * 8))
        }
    }
}

/** What a SWAP of [boxMon] for [partyMon] (into box [boxName]) did, in the companion's words. */
internal fun boxSwapMessage(r: com.pokedaisy.app.companion.data.BoxSwapResult, boxMon: MonView, partyMon: MonView, boxName: String): String = when (r) {
    com.pokedaisy.app.companion.data.BoxSwapResult.SWAPPED ->
        tr("{0} joined the party and {1} went to {2}. Save in the game to keep it.", boxMon.shownName, partyMon.shownName, boxName)
    com.pokedaisy.app.companion.data.BoxSwapResult.NOT_NOW -> tr("Not now: close the game's menus and battles first.")
    com.pokedaisy.app.companion.data.BoxSwapResult.IN_BATTLE -> tr("You can't do that during a battle.")
    com.pokedaisy.app.companion.data.BoxSwapResult.CHANGED -> tr("The party or the box has changed. Try again.")
    com.pokedaisy.app.companion.data.BoxSwapResult.HOLDS_MAIL -> tr("Take the MAIL from {0} first.", partyMon.shownName)
    com.pokedaisy.app.companion.data.BoxSwapResult.LAST_MON -> tr("An EGG can't take the place of your last POKéMON that can battle.")
    com.pokedaisy.app.companion.data.BoxSwapResult.FAILED -> tr("The swap didn't work. Nothing was changed.")
}

/** The last SWAP's message ([BoxesUiState.swapMessage]) in a window, until OK. */
@Composable
internal fun BoxSwapNotice(ui: BoxesUiState) {
    val text = ui.swapMessage ?: return
    OptionConfirm(tk("SWAP"), text, tk("OK"), rememberGbaTextMetrics(), onConfirm = { ui.swapMessage = null },
        onDismiss = { ui.swapMessage = null }, cancelLabel = null)
}

/**
 * The game's PKMN DATA panel: a dark grey frame, its title in gold, [mon]'s picture in the blue checked well, then its
 * nickname, species, level + gender and held item in white on the panel's lilac grey. [wide]: the well left of the
 * lines (a short panel over the box).
 */
@Composable
private fun MonPanel(mon: MonView?, dex: PokedexTables?, m: GbaTextMetrics, modifier: Modifier, wide: Boolean) {
    val u = m.u
    val small = rememberGbaTextMetrics(1f)
    val night = OptionColors.dark
    val species = mon?.species?.takeIf { !mon.isEgg && dex != null } ?: 0
    val well = @Composable { wellModifier: Modifier ->
        Box(wellModifier.drawBehind { pictureWell(u.toPx(), night) }.padding(u * 5)) {
            val sprite = if (dex != null && species > 0) rememberRomBitmap(dex, species, PokedexSource::cachedFrontSprite, PokedexSource::frontSprite) else null
            if (sprite != null || mon == null) TrimmedSprite(sprite, Modifier.fillMaxSize())
            // No picture to read (no Pokédex tables for the game, an egg): its icon, as large as whole pixels allow.
            else BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val room = with(density) { minOf(maxWidth, maxHeight).toPx() }
                SpeciesIcon(mon.iconAsset, with(density) { (ICON * floor(room / ICON).coerceAtLeast(1f)).toDp() })
            }
        }
    }
    val text = PcColors.shade(PcColors.PANEL_TEXT, night)
    val shadow = PcColors.shade(PcColors.PANEL_SHADOW, night)
    val lines = @Composable {
        if (mon != null) GbaText(mon.shownName, text, shadow, m)
        if (mon != null && !mon.isEgg) {
            GbaText("/" + mon.name, text, shadow, small)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 4)) {
                GbaText(tr("Lv{0}", mon.level), text, shadow, m)
                GenderMark(mon.genderSymbol, m)
            }
            if (mon.heldItemId != 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                    ItemIcon(com.pokedaisy.app.companion.data.spriteAsset("items", mon.heldItemId), size = small.lineHeight)
                    GbaText(itemName(mon.heldItemId), text, shadow, small)
                }
            }
        }
    }
    val title = @Composable {
        GbaText(tr("PKMN DATA"), PcColors.shade(PcColors.TITLE_TEXT, night), PcColors.shade(PcColors.FRAME, night), small)
    }
    val frame = PcColors.shade(PcColors.FRAME, night)
    val top = PcColors.shade(PcColors.PANEL_TOP, night)
    val body = PcColors.shade(PcColors.PANEL, night)
    if (wide) {
        Row(modifier.drawBehind { drawRect(frame); drawRect(body, Offset(2 * u.toPx(), 2 * u.toPx()), Size(size.width - 4 * u.toPx(), size.height - 4 * u.toPx())) }) {
            // The title and well on the panel's darker top part, the lines on its lilac part beside them.
            Column(
                Modifier.fillMaxHeight().aspectRatio(0.85f).drawBehind {
                    val p = u.toPx()
                    drawRect(top, Offset(2 * p, 2 * p), Size(size.width - 2 * p, size.height - 4 * p))
                    drawRect(frame, Offset(size.width - 2 * p, 0f), Size(2 * p, size.height))
                }.padding(start = u * 6, end = u * 6, top = u * 4, bottom = u * 6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(u * 3),
            ) {
                title()
                well(Modifier.fillMaxWidth().weight(1f))
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(u * 8), verticalArrangement = Arrangement.spacedBy(u * 2, Alignment.CenterVertically)) { lines() }
        }
    } else {
        Column(modifier.drawBehind { drawRect(frame); drawRect(body, Offset(2 * u.toPx(), 2 * u.toPx()), Size(size.width - 4 * u.toPx(), size.height - 4 * u.toPx())) }) {
            Column(
                Modifier.fillMaxWidth().weight(1f).drawBehind {
                    val p = u.toPx()
                    drawRect(top, Offset(2 * p, 2 * p), Size(size.width - 4 * p, size.height - 2 * p))
                    // The well's lip, then the dark line the game draws under the top part.
                    drawRect(PcColors.shade(PcColors.LIP, night), Offset(2 * p, size.height - 5 * p), Size(size.width - 4 * p, p))
                    drawRect(frame, Offset(0f, size.height - 2 * p), Size(size.width, 2 * p))
                }.padding(start = u * 6, end = u * 6, top = u * 4, bottom = u * 8),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(u * 3),
            ) {
                title()
                well(Modifier.fillMaxWidth().weight(1f))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = u * 8, vertical = u * 6), verticalArrangement = Arrangement.spacedBy(u * 2)) { lines() }
        }
    }
}

/**
 * [bitmap] (a 64x64 front picture) cut to its drawn pixels and scaled by the largest whole number of screen pixels that
 * fits, centred: the well's room goes to the Pokémon, not to the picture's empty margin.
 */
@Composable
private fun TrimmedSprite(bitmap: Bitmap?, modifier: Modifier) {
    val density = LocalDensity.current
    val trim = remember(bitmap) { bitmap?.let(::opaqueBounds) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        if (bitmap == null || trim == null) return@BoxWithConstraints
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val k = floor(minOf(w / trim.width, h / trim.height)).coerceAtLeast(1f)
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        Canvas(Modifier.size(with(density) { (trim.width * k).toDp() }, with(density) { (trim.height * k).toDp() })) {
            drawImage(
                image, srcOffset = IntOffset(trim.left, trim.top), srcSize = IntSize(trim.width, trim.height),
                dstSize = IntSize((trim.width * k).roundToInt(), (trim.height * k).roundToInt()), filterQuality = FilterQuality.None,
            )
        }
    }
}

private class Trim(val left: Int, val top: Int, val width: Int, val height: Int)

/** The smallest rectangle holding [b]'s non-transparent pixels (the whole picture if it has none). */
private fun opaqueBounds(b: Bitmap): Trim {
    val w = b.width
    val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    var l = w; var t = h; var r = -1; var bt = -1
    for (y in 0 until h) for (x in 0 until w) if (px[y * w + x] ushr 24 != 0) {
        if (x < l) l = x
        if (x > r) r = x
        if (y < t) t = y
        if (y > bt) bt = y
    }
    return if (r < 0) Trim(0, 0, w, h) else Trim(l, t, r + 1 - l, bt + 1 - t)
}

/** The PKMN DATA picture well: a dark frame, a blue edge, then the game's light blue checks, a band darker at the edges. */
private fun DrawScope.pictureWell(p: Float, night: Boolean) {
    val r = 4 * p
    drawPixelRoundRect(PcColors.shade(PcColors.FRAME, night), radius = r, step = p)
    drawPixelRoundRect(PcColors.shade(PcColors.WELL_EDGE, night), Offset(2 * p, 2 * p), Size(size.width - 4 * p, size.height - 4 * p), r - 2 * p, p)
    val inner = Size(size.width - 8 * p, size.height - 8 * p)
    clipPath(pixelRoundRectPath(inner, r - 4 * p, p, Offset(4 * p, 4 * p))) {
        drawRect(PcColors.shade(PcColors.WELL_DARK, night), Offset(4 * p, 4 * p), inner)
        drawRect(PcColors.shade(PcColors.WELL, night), Offset(8 * p, 8 * p), Size(inner.width - 8 * p, inner.height - 8 * p))
        val light = PcColors.shade(PcColors.WELL_LIGHT, night)
        val cell = 2 * p
        var y = 4 * p
        var row = 0
        while (y < size.height - 4 * p) {
            var x = 4 * p + if (row % 2 == 0) 0f else cell
            while (x < size.width - 4 * p) {
                drawRect(light, Offset(x, y), Size(cell, cell))
                x += 2 * cell
            }
            y += cell
            row++
        }
    }
}

/** The box: its name on the wallpaper's header strip between the two arrows, then the wallpaper with the Pokémon on it. */
@Composable
private fun BoxWindow(
    box: BoxView, boxIndex: Int, lookedSlot: Int, ui: BoxesUiState, m: GbaTextMetrics, step: (Int) -> Unit, modifier: Modifier,
) {
    val u = m.u
    val night = OptionColors.dark
    val paper = BoxWallpapers.paper(box.wallpaper)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(u * 3)) {
        Row(Modifier.fillMaxWidth().height(m.rowHeight + u * 12), verticalAlignment = Alignment.CenterVertically) {
            HeaderButton(m, tr("Previous box"), Modifier.fillMaxHeight().width(u * 30), onClick = { step(-1) }) {
                ScrollArrow(left = true, u, night)
            }
            HeaderButton(m, tr("Pick a box"), Modifier.weight(1f).fillMaxHeight(), onClick = { ui.picking = true }) {
                Box(Modifier.fillMaxSize().drawBehind { header(paper, u.toPx(), night) }, contentAlignment = Alignment.Center) {
                    GbaText(box.name, PcColors.shade(PcColors.TITLE, night), PcColors.shade(PcColors.TITLE_SHADOW, night), m)
                }
            }
            HeaderButton(m, tr("Next box"), Modifier.fillMaxHeight().width(u * 30), onClick = { step(1) }) {
                ScrollArrow(left = false, u, night)
            }
        }
        Wallpaper(box, boxIndex, lookedSlot, ui, m, step, Modifier.fillMaxWidth().weight(1f))
    }
}

/** A button with no look of its own (no ripple: a focus ring and the click sound). */
@Composable
private fun HeaderButton(m: GbaTextMetrics, description: String, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier
            .gbaFocusRing(focused, PixelRoundedShape(m.u * 2))
            .semantics { contentDescription = description }
            .soundClickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The box's scroll arrow, in the game's greys: a lighter edge on top, a dark one below, two GBA pixels a cell. */
@Composable
private fun ScrollArrow(left: Boolean, u: androidx.compose.ui.unit.Dp, night: Boolean) {
    val bits = if (left) ARROW else ARROW.map { it.reversed() }
    PixelArt(
        bits,
        mapOf(
            'L' to PcColors.shade(PcColors.ARROW_LIGHT, night), 'F' to PcColors.shade(PcColors.ARROW, night),
            'D' to PcColors.shade(PcColors.FRAME, night),
        ),
        Modifier.size(u * 2 * bits[0].length, u * 2 * bits.size),
    )
}

/** The wallpaper's header strip: light frame top / left, dark bottom / right, a white line inside, its sky in bands. */
private fun DrawScope.header(paper: BoxWallpapers.Paper, p: Float, night: Boolean) {
    bevel(0f, 2 * p, BoxWallpapers.shade(paper.frameLight, night), BoxWallpapers.shade(paper.frameDark, night))
    val inset = 2 * p
    drawRect(BoxWallpapers.shade(0xFFFFFFFF, night), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset))
    val top = inset + p
    val h = size.height - 2 * top
    val bands = paper.header
    bands.forEachIndexed { i, c ->
        // Whole GBA pixels per band, the last ones taking what's left.
        val y0 = top + floor(h * i / bands.size / p) * p
        val y1 = if (i == bands.lastIndex) top + h else top + floor(h * (i + 1) / bands.size / p) * p
        drawRect(BoxWallpapers.shade(c, night), Offset(top, y0), Size(size.width - 2 * top, y1 - y0))
    }
}

/** A frame [width] wide [inset] in: [light] along the top and left, [dark] along the bottom and right. */
private fun DrawScope.bevel(inset: Float, width: Float, light: Color, dark: Color, left: Color = light, bottom: Color = dark) {
    val w = size.width - 2 * inset
    val h = size.height - 2 * inset
    drawRect(light, Offset(inset, inset), Size(w, width))
    drawRect(left, Offset(inset, inset), Size(width, h))
    drawRect(dark, Offset(inset + w - width, inset), Size(width, h))
    drawRect(bottom, Offset(inset, inset + h - width), Size(w, width))
}

/** The PC's peach backdrop: rings of our own drawing in the game's three peaches, scrolling slowly like the game's. */
@Composable
internal fun PcBackdrop(modifier: Modifier = Modifier.fillMaxSize()) {
    val night = OptionColors.dark
    val tile = remember(night) { PcColors.backdropTile(night) }
    val px = with(LocalDensity.current) { gbaPixelPx() }
    // Read only while drawing, so a frame of it redraws the canvas without recomposing the tab; still on a light companion.
    val scroll = if (com.pokedaisy.app.companion.LightCompanion.on) null else rememberInfiniteTransition(label = "pc-backdrop").animateFloat(
        0f, BACKDROP_TILE.toFloat(), infiniteRepeatable(tween(BACKDROP_SCROLL_MS, easing = LinearEasing), RepeatMode.Restart), label = "pc-scroll",
    )
    Canvas(modifier.clipToBounds()) {
        val step = BACKDROP_TILE * px
        val shift = floor((scroll?.value ?: 0f)) * px
        var y = -step + shift
        while (y < size.height) {
            var x = -step + shift
            while (x < size.width) {
                drawImage(tile, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(step.roundToInt(), step.roundToInt()), filterQuality = FilterQuality.None)
                x += step
            }
            y += step
        }
    }
}

/** The box's wallpaper in its bevelled frame, and its 30 slots: a tap = the summary, focus = the panel. */
@Composable
private fun Wallpaper(box: BoxView, boxIndex: Int, lookedSlot: Int, ui: BoxesUiState, m: GbaTextMetrics, step: (Int) -> Unit, modifier: Modifier) {
    val u = m.u
    val density = LocalDensity.current
    val px = with(density) { u.toPx() }
    val night = OptionColors.dark
    val paper = BoxWallpapers.paper(box.wallpaper)
    val tile = remember(box.wallpaper, night, paper) { BoxWallpapers.tile(box.wallpaper, night) }
    val inset = 4 * px
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .drawBehind {
                // The game's two bevels: the frame (light top / left, dark bottom / right), then the wallpaper's own edge.
                bevel(0f, 2 * px, BoxWallpapers.shade(paper.frameLight, night), BoxWallpapers.shade(paper.frameDark, night))
                bevel(
                    2 * px, 2 * px, BoxWallpapers.shade(paper.edgeTop, night), BoxWallpapers.shade(paper.edgeRight, night),
                    left = BoxWallpapers.shade(paper.edgeLeft, night), bottom = BoxWallpapers.shade(paper.edgeBottom, night),
                )
                // The pattern from the inner corner, a GBA pixel per tile pixel.
                clipRect(inset, inset, size.width - inset, size.height - inset) {
                    val stepPx = tile.width * px
                    var y = inset
                    while (y < size.height) {
                        var x = inset
                        while (x < size.width) {
                            drawImage(
                                tile, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                                dstSize = IntSize(stepPx.roundToInt(), stepPx.roundToInt()), filterQuality = FilterQuality.None,
                            )
                            x += stepPx
                        }
                        y += stepPx
                    }
                }
            }
            .pointerInput(boxIndex) {
                // A swipe across the box changes it: left for the next one, right for the one before.
                var dx = 0f
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                    if (kotlin.math.abs(dx) > 24 * px) step(if (dx < 0) 1 else -1)
                }) { _, amount -> dx += amount }
            },
    ) {
        val pad = inset + 2 * px
        val w = constraints.maxWidth - 2 * pad
        val h = constraints.maxHeight - 2 * pad
        val cellW = w / COLUMNS
        val cellH = h / ROWS
        // The game's 32x32 icons sit in 24-pixel cells: whole screen pixels per icon pixel, as many as that allows.
        val k = floor(minOf(cellW, cellH) / ICON_CELL).coerceAtLeast(1f)
        val iconPx = ICON * k
        val inBox = box.mons.filterNotNull()
        box.mons.forEachIndexed { slot, mon ->
            val col = slot % COLUMNS
            val row = slot / COLUMNS
            val x = pad + col * cellW
            val y = pad + row * cellH
            fun Modifier.place(left: Float, top: Float, width: Float, height: Float) = this
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            if (mon != null) key(boxIndex, slot) {
                val focus = remember { MutableInteractionSource() }
                val focused by focus.collectIsFocusedAsState()
                val cursor = slot == lookedSlot
                Box(
                    Modifier.place(x, y, cellW, cellH)
                        .onFocusChanged { if (it.isFocused) ui.looked = slot }
                        .gbaFocusRing(focused, PixelRoundedShape(u * 3))
                        .semantics { contentDescription = mon.shownName }
                        .soundClickable(interactionSource = focus, indication = null) {
                            ui.looked = slot
                            ui.open = inBox.indexOf(mon)
                        }
                        .focusStart(cursor),
                    contentAlignment = Alignment.Center,
                ) {
                    // The looked-at Pokémon: a white cell behind its icon (the game's hand would point at it).
                    if (cursor) Canvas(Modifier.fillMaxSize().padding(u * 2)) {
                        drawPixelRoundRect(CURSOR_FILL, Offset.Zero, size, 3 * px, px)
                        drawPixelRoundFrame(CURSOR_FRAME, Offset.Zero, size, 3 * px, px, px)
                    }
                    // Past the cell's edges where it must (the game's 32-pixel icon in a 24-pixel cell), never squeezed.
                    val side = with(density) { iconPx.toDp() }
                    SpeciesIcon(mon.iconAsset, side, Modifier.requiredSize(side))
                }
            }
        }
    }
}

/** The PC screen's own colours, sampled from FireRed's (its interface palette and backdrop). */
internal object PcColors {
    const val FRAME = 0xFF52525A
    const val PANEL_TOP = 0xFF73737B
    const val PANEL = 0xFF9494AC
    const val LIP = 0xFFA4A4B4
    const val WELL_EDGE = 0xFF7BB4D5
    const val WELL_DARK = 0xFF9CCDEE
    const val WELL = 0xFFB4D5F6
    const val WELL_LIGHT = 0xFFD5E6FF
    const val TITLE_TEXT = 0xFFFFDE94
    const val PANEL_TEXT = 0xFFFFFFFF
    const val PANEL_SHADOW = 0xFF000000
    // The box's name (sBoxTitleColors): white, RGB 7,7,7 shadow.
    const val TITLE = 0xFFFFFFFF
    const val TITLE_SHADOW = 0xFF393939
    const val ARROW = 0xFF73737B
    const val ARROW_LIGHT = 0xFF9494AC
    private const val PEACH = 0xFFFFE6D5
    private const val PEACH_DARK = 0xFFFFDECD
    private const val PEACH_LIGHT = 0xFFFFEEE6

    /** [c], lowered towards the night backdrop in dark mode. */
    fun shade(c: Long, night: Boolean): Color = if (night) lerp(Color(c), NIGHT, 0.45f) else Color(c)

    /** One 32x32 backdrop tile: a ring, light on its upper left and darker on its lower right, on the peach. */
    fun backdropTile(night: Boolean): ImageBitmap {
        val base = shade(PEACH, night).toArgb()
        val dark = shade(PEACH_DARK, night).toArgb()
        val light = shade(PEACH_LIGHT, night).toArgb()
        val n = BACKDROP_TILE
        val px = IntArray(n * n) { i ->
            val dx = i % n - 15.5f
            val dy = i / n - 15.5f
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            when {
                d in 8f..11f -> if (dx + dy < 0) light else dark
                d < 3f -> light
                else -> base
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

// The box's scroll arrow, our own drawing in the game's greys (L light edge, F fill, D dark edge).
private val ARROW = listOf(
    "....LD",
    "...LFD",
    "..LFFD",
    ".LFFFD",
    "LFFFFD",
    ".DFFFD",
    "..DFFD",
    "...DFD",
    "....DD",
)
private const val BACKDROP_TILE = 32
// A tile at half a GBA pixel a frame (60 a second), like the game's BG3.
private const val BACKDROP_SCROLL_MS = BACKDROP_TILE * 2 * 1000 / 60

/**
 * The boxes' wallpapers, drawn: Gen 3's 16 (FOREST ... PLAIN, the same ids in every Gen 3 game; any other id, and the
 * games without wallpapers, get PLAIN; Emerald's friends' wallpapers too) in their own colours, sampled from each game's
 * (FireRed's, Emerald's: the PC screen around them is the same in both) - the frame and edge bevels, the header strip's
 * picture in bands, the fill - with a pattern of our own on a 16x16 tile, a GBA pixel per tile pixel.
 */
internal object BoxWallpapers {
    enum class Pattern { TUFTS, BRICKS, WAVES, STRIPES, CHECKS, DIAGONAL, DOTS, BUBBLES, POLKA, GRID, PLAIN }

    class Paper(
        val frameLight: Long, val frameDark: Long,
        val edgeTop: Long, val edgeLeft: Long, val edgeRight: Long, val edgeBottom: Long,
        val base: Long, val mark: Long, val mark2: Long, val pattern: Pattern,
        /** The header strip's colours, top to bottom (the game's picture there, as 16 bands). */
        val header: List<Long>,
    )

    private fun hdr(vararg c: Int) = c.map { 0xFF000000L or it.toLong() }

    private val FIRERED = listOf(
        // FOREST
        Paper(0xFFA48B52, 0xFF7B6229, 0xFFE6FFAC, 0xFFCDFF94, 0xFF6A9C31, 0xFF528318, 0xFF9CCD62, 0xFF83B44A, 0xFFB4E67B, Pattern.TUFTS,
            hdr(0xDEFFBD, 0xC5F6A4, 0xC5F6A4, 0xDEFFBD, 0xC5F6A4, 0xC5F6A4, 0x9CFF8B, 0x7BDE6A, 0x7BDE6A, 0x5ABD4A, 0x5ABD4A, 0x7BDE6A, 0x949C73, 0x949C73, 0x949C73, 0xACB48B)),
        // CITY
        Paper(0xFF9C7B52, 0xFF735229, 0xFFFFFFF6, 0xFFFFEEF6, 0xFF8B7B83, 0xFF8B7B83, 0xFFCDBDC5, 0xFFA4949C, 0xFFE6D5DE, Pattern.BRICKS,
            hdr(0x5A83C5, 0x5A83C5, 0x5A83C5, 0x5A83C5, 0x7BA4D5, 0x7BA4D5, 0x9CC5E6, 0x7BA4D5, 0x9CC5E6, 0x9CC5E6, 0xBDD5D5, 0xDEF6F6, 0xDEF6F6, 0xDEF6F6, 0xDEF6F6, 0xDEF6F6)),
        // DESERT
        Paper(0xFFA48B52, 0xFF7B6229, 0xFFFFEEBD, 0xFFFFD5A4, 0xFF9C7341, 0xFF835A29, 0xFFCDA473, 0xFFB48B5A, 0xFFE6BD8B, Pattern.WAVES,
            hdr(0xACA4AC, 0xACA4AC, 0xCDBD83, 0xCDBD83, 0xD5BD62, 0xF6CD4A, 0xFFE66A, 0xFFFF8B, 0xFFFF8B, 0xFFFFFF, 0xFFFF8B, 0xFFFF8B, 0xFFFF8B, 0xE6B45A, 0xACACAC, 0x7B7B7B)),
        // SAVANNA
        Paper(0xFFBD9C52, 0xFF947329, 0xFFFFFF83, 0xFFDEDE62, 0xFF5A5A00, 0xFF393900, 0xFFBDBD41, 0xFFCDCD52, 0xFF9C9C20, Pattern.STRIPES,
            hdr(0xBDCDDE, 0xBDCDDE, 0xBDCDDE, 0xBDCDDE, 0xD5E6F6, 0xD5E6F6, 0xD5E6F6, 0xD5E6F6, 0xEEFFFF, 0xEEFFFF, 0xEEFFFF, 0xEEFFFF, 0x94C54A, 0xBDBD41, 0xA4A429, 0xA4A429)),
        // CRAG
        Paper(0xFFD5BD52, 0xFFAC9429, 0xFFDEDEDE, 0xFFBDBDBD, 0xFF393939, 0xFF292929, 0xFFC5A4B4, 0xFFAC8B9C, 0xFFDEBDCD, Pattern.CHECKS,
            hdr(0xAC8B9C, 0xAC8B9C, 0x7B7B7B, 0x9C9C9C, 0x9C9C9C, 0x9C9C9C, 0x8B8B83, 0x7B7B7B, 0x7B7B7B, 0x9C9C9C, 0x9C9C9C, 0x9C9C9C, 0x9C9C9C, 0x8B8B83, 0x8B8B83, 0x8B8B83)),
        // VOLCANO
        Paper(0xFFD5838B, 0xFFAC5A62, 0xFFFFF6E6, 0xFFFFEECD, 0xFFB46A4A, 0xFF944A29, 0xFFDE834A, 0xFFCD7339, 0xFFF69C62, Pattern.DIAGONAL,
            hdr(0xBDBDBD, 0x4A527B, 0x4A527B, 0x6A739C, 0x6A739C, 0xB45218, 0xB45218, 0xB45218, 0xB45218, 0xBDBDBD, 0xDEDEDE, 0xDEDEDE, 0xB45218, 0xB45218, 0xCD7339, 0xDE834A)),
        // SNOW
        Paper(0xFFD5838B, 0xFFAC5A62, 0xFFE6E6E6, 0xFFCDCDCD, 0xFF29315A, 0xFF081039, 0xFFFFFFFF, 0xFFE6E6E6, 0xFFCDCDCD, Pattern.DOTS,
            hdr(0x4A527B, 0x4A527B, 0x4A527B, 0x4A527B, 0x6A739C, 0x6A739C, 0xE6E6E6, 0xE6E6E6, 0xE6E6E6, 0xE6E6E6, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xE6E6E6, 0xE6E6E6, 0xCDCDCD)),
        // CAVE
        Paper(0xFF5A5A83, 0xFF31315A, 0xFFF6D583, 0xFFD5B462, 0xFF523100, 0xFF311000, 0xFF947320, 0xFF735200, 0xFFB49441, Pattern.CHECKS,
            hdr(0xD5B462, 0x947320, 0x947320, 0x947320, 0x947320, 0x947320, 0x735200, 0x735200, 0x735200, 0x735200, 0x947320, 0x947320, 0xB49441, 0xB49441, 0xD5B462, 0xD5B462)),
        // BEACH
        Paper(0xFF5262FF, 0xFF31419C, 0xFFFFFFDE, 0xFFF6EEB4, 0xFFD5BD62, 0xFFCDA45A, 0xFFEEDE83, 0xFFDECD73, 0xFFEEE694, Pattern.WAVES,
            hdr(0xC5E6FF, 0xC5E6FF, 0xC5E6FF, 0xC5E6FF, 0xC5F6FF, 0xC5F6FF, 0xE6FFFF, 0xC5F6FF, 0xE6FFFF, 0xE6FFFF, 0xFFFFFF, 0xFFFFFF, 0xC5F6FF, 0x8BE6CD, 0xE6FFFF, 0xF6E6A4)),
        // SEAFLOOR
        Paper(0xFF94B429, 0xFF6A8B00, 0xFFA4D5FF, 0xFF83B4FF, 0xFF00317B, 0xFF00105A, 0xFF00317B, 0xFF4173BD, 0xFF20529C, Pattern.BUBBLES,
            hdr(0x83B4FF, 0x83B4FF, 0x83B4FF, 0x83B4FF, 0x6294DE, 0x6294DE, 0x6294DE, 0x4173BD, 0x6294DE, 0x4173BD, 0x4173BD, 0x4173BD, 0x4173BD, 0x20529C, 0x20529C, 0x001862)),
        // RIVER
        Paper(0xFFB4946A, 0xFF8B6A41, 0xFFE6FF9C, 0xFFC5FF7B, 0xFF418300, 0xFF206200, 0xFFE6FFFF, 0xFFA4FFFF, 0xFFC5FFFF, Pattern.WAVES,
            hdr(0x73B400, 0x73B400, 0x73B400, 0x73B400, 0x94D518, 0x83DEDE, 0x83DEDE, 0xA4FFFF, 0xA4FFFF, 0xCDFFFF, 0xCDFFFF, 0xEEFFFF, 0xEEFFFF, 0xEEFFFF, 0xEEFFFF, 0xEEFFFF)),
        // SKY
        Paper(0xFFCD8B29, 0xFFA46200, 0xFFEEFFFF, 0xFFCDFFFF, 0xFF3994B4, 0xFF187394, 0xFF7BD5F6, 0xFFFFFFFF, 0xFFCDFFFF, Pattern.DOTS,
            hdr(0x187394, 0x187394, 0x187394, 0x187394, 0x3994B4, 0x3994B4, 0x3994B4, 0x5AB4D5, 0x3994B4, 0x5AB4D5, 0x5AB4D5, 0x5AB4D5, 0x7BD5F6, 0x7BD5F6, 0xFFFFFF, 0xE6E6E6)),
        // POLKA-DOT
        Paper(0xFFCD4AB4, 0xFF6A0873, 0xFFE6CDF6, 0xFFD5BDE6, 0xFF7B628B, 0xFF7B628B, 0xFFC5ACDE, 0xFFFF6ABD, 0xFF739CFF, Pattern.POLKA,
            hdr(0x4A6A9C, 0x627BCD, 0x4A6A9C, 0x627BCD, 0x4A6A9C, 0x627BCD, 0x4A6A9C, 0x627BCD, 0x4A6A9C, 0x627BCD, 0x4A6A9C, 0x627BCD, 0x4A6A9C, 0x627BCD, 0x4A6A9C, 0x627BCD)),
        // POKéCENTER
        Paper(0xFF73A49C, 0xFF295252, 0xFFD5FFDE, 0xFFACD5AC, 0xFF5A8352, 0xFF5A8352, 0xFFBDE6C5, 0xFFFFFFFF, 0xFFFFA48B, Pattern.POLKA,
            hdr(0x8BB494, 0x8BB494, 0x8BB494, 0x8BB494, 0xA4CDAC, 0xA4CDAC, 0x8BB494, 0x8BB494, 0x8BB494, 0x8BB494, 0xA4CDAC, 0xA4CDAC, 0x8BB494, 0x8BB494, 0x8BB494, 0x8BB494)),
        // MACHINE
        Paper(0xFFE69C08, 0xFF9C5A29, 0xFFD5D5D5, 0xFFC5C5C5, 0xFF838383, 0xFF838383, 0xFFB4B4B4, 0xFFA49C9C, 0xFFC5C5BD, Pattern.GRID,
            hdr(0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC, 0xD5CDAC)),
        // PLAIN
        Paper(0xFF8373A4, 0xFF524173, 0xFFBDCDE6, 0xFFFFFFD5, 0xFFBDBD94, 0xFF415A73, 0xFFE6E6A4, 0xFFCDCD9C, 0xFFCDCD9C, Pattern.PLAIN,
            hdr(0xBDBDFF, 0xACACFF, 0xBDBDFF, 0xACACFF, 0xBDBDFF, 0xACACFF, 0xBDBDFF, 0xACACFF, 0xBDBDFF, 0xACACFF, 0xBDBDFF, 0xACACFF, 0xBDBDFF, 0xACACFF, 0xBDBDFF, 0xACACFF)),
    )

    // Emerald's (Ruby / Sapphire's and the hacks running as Emerald share them): the same 16 ids, several redrawn.
    private val EMERALD = listOf(
        // FOREST
        Paper(0xFFA48B52, 0xFF7B6229, 0xFFE6FFAC, 0xFFCDFF94, 0xFF6A9C31, 0xFF528318, 0xFF9CCD62, 0xFFB4E67B, 0xFF83B44A, Pattern.TUFTS,
            hdr(0x7BDE6A, 0x7BDE6A, 0x5ABD4A, 0x7BDE6A, 0x7BDE6A, 0x7BDE6A, 0x7BDE6A, 0x5ABD4A, 0x9CCDFF, 0x9CCDFF, 0x7BB4FF, 0x9CCDFF, 0x7BB4FF, 0x7BB4FF, 0x7BB4FF, 0x7BB4FF)),
        // CITY
        Paper(0xFF9C7B52, 0xFF735229, 0xFFFFEEF6, 0xFFFFEEF6, 0xFF8B7B83, 0xFF8B7B83, 0xFFBDACB4, 0xFFB4A4AC, 0xFFCDBDC5, Pattern.BRICKS,
            hdr(0x949CA4, 0x949CA4, 0xDEF6F6, 0x949CA4, 0x949CA4, 0x949CA4, 0x949CA4, 0x949CA4, 0x737B83, 0x737B83, 0x737B83, 0x737B83, 0x525A62, 0x525A62, 0x525A62, 0x525A62)),
        // DESERT
        Paper(0xFFA48B52, 0xFF7B6229, 0xFFFFEEBD, 0xFFFFD5A4, 0xFF9C7341, 0xFF835A29, 0xFFDEB473, 0xFFCDA46A, 0xFFD5AC73, Pattern.WAVES,
            hdr(0xACA4AC, 0xACA4AC, 0xACA4AC, 0xC5B48B, 0xC5B48B, 0xC5B48B, 0xC5B48B, 0xDEBD7B, 0xDEBD7B, 0xAC945A, 0xAC945A, 0xAC945A, 0xAC945A, 0xDEBD7B, 0xDEBD7B, 0xDEBD7B)),
        // SAVANNA
        Paper(0xFFBD9C52, 0xFF947329, 0xFFD5FF94, 0xFFBDDE73, 0xFF395A10, 0xFF183910, 0xFFB4CD5A, 0xFFA4B44A, 0xFFBDDE73, Pattern.STRIPES,
            hdr(0x83B4FF, 0x83B4FF, 0x83B4FF, 0x83B4FF, 0x83B4FF, 0xACCDFF, 0xACCDFF, 0xACCDFF, 0xACCDFF, 0xBDDEFF, 0xBDDEFF, 0xBDDEFF, 0xD5EEFF, 0xEEEEFF, 0xA4BD7B, 0x8BA45A)),
        // CRAG
        Paper(0xFFD5BD52, 0xFFAC9429, 0xFFDEDEDE, 0xFFBDBDC5, 0xFF4A4A4A, 0xFF4A4A4A, 0xFFB4B4B4, 0xFFA4A4A4, 0xFFC5C5C5, Pattern.CHECKS,
            hdr(0x9CACBD, 0x9CACBD, 0xB4C5D5, 0xB4C5D5, 0xB4C5D5, 0xB4C5D5, 0xACB4BD, 0xACB4BD, 0xACB4BD, 0xACB4BD, 0x949CAC, 0x838B9C, 0x838B9C, 0x737B8B, 0x737B8B, 0x737B8B)),
        // VOLCANO
        Paper(0xFFD5838B, 0xFFAC5A62, 0xFFFFEEDE, 0xFFFFE6C5, 0xFFB46A4A, 0xFF944A29, 0xFFE67B52, 0xFFD56A41, 0xFFF68B62, Pattern.DIAGONAL,
            hdr(0x9494AC, 0x9494AC, 0x9494AC, 0x9494AC, 0xA4ACBD, 0xA4ACBD, 0xA4ACBD, 0xA4ACBD, 0xA4ACBD, 0xBDBDCD, 0xBDBDCD, 0xBDBDCD, 0xBDBDCD, 0xBDBDCD, 0x7B6A5A, 0x8B7B6A)),
        // SNOW
        Paper(0xFFD5838B, 0xFFAC5A62, 0xFFE6E6E6, 0xFFCDCDCD, 0xFF29315A, 0xFF081039, 0xFFE6EEFF, 0xFFCDE6F6, 0xFFFFFFFF, Pattern.DOTS,
            hdr(0x73A4C5, 0x5283A4, 0x94BDD5, 0x73A4C5, 0x73A4C5, 0x94BDD5, 0x73A4C5, 0x73A4C5, 0x94BDD5, 0x94BDD5, 0x94BDD5, 0x94BDD5, 0x94BDD5, 0xB4D5EE, 0x94BDD5, 0xD5D5E6)),
        // CAVE
        Paper(0xFF5A5A83, 0xFF31315A, 0xFFF6D583, 0xFFD5B462, 0xFF523100, 0xFF311000, 0xFFB4946A, 0xFFBDAC7B, 0xFF9C7B62, Pattern.CHECKS,
            hdr(0x837B6A, 0xC5B483, 0xC5B483, 0x837B6A, 0x837B6A, 0xA4946A, 0x837B6A, 0xA4946A, 0xA4946A, 0x837B6A, 0xA4946A, 0xC5B483, 0xA4946A, 0xA4946A, 0x4A9431, 0x4A9431)),
        // BEACH
        Paper(0xFF5262FF, 0xFF31419C, 0xFFFFFFDE, 0xFFEEE6B4, 0xFFD5BD62, 0xFFD5E6E6, 0xFFE6D57B, 0xFFDEC573, 0xFFC5D5B4, Pattern.WAVES,
            hdr(0xFFFFFF, 0xBDF6F6, 0x83E6C5, 0x83E6C5, 0x83E6C5, 0x5AD5CD, 0x5AD5CD, 0x5AD5CD, 0x83E6C5, 0x83E6C5, 0x83E6C5, 0xBDF6F6, 0xFFFFFF, 0xF6E6A4, 0xF6E6A4, 0xFFF6B4)),
        // SEAFLOOR
        Paper(0xFF94B429, 0xFF6A8B00, 0xFFA4D5FF, 0xFF7BACEE, 0xFF00317B, 0xFF00105A, 0xFF4173BD, 0xFF20529C, 0xFF7BACEE, Pattern.BUBBLES,
            hdr(0x83CDF6, 0x83CDF6, 0x83CDF6, 0x83CDF6, 0x6AA4CD, 0x6AA4CD, 0x6AA4CD, 0x6AA4CD, 0x6AA4CD, 0x417BBD, 0x417BBD, 0x6AA4CD, 0x6AA4CD, 0x417BBD, 0x105A9C, 0x105A9C)),
        // RIVER
        Paper(0xFFB4946A, 0xFF8B6A41, 0xFFE6FF9C, 0xFFC5FF7B, 0xFF418300, 0xFF206200, 0xFF8BE6E6, 0xFF9CEEF6, 0xFFBDFFFF, Pattern.WAVES,
            hdr(0xB4E69C, 0xB4E69C, 0xB4E69C, 0xB4E69C, 0x7BDE62, 0x7BDE62, 0x94D518, 0xC5EEF6, 0x8BE6E6, 0xC5EEF6, 0x8BE6E6, 0x8BE6E6, 0x8BE6E6, 0x8BE6E6, 0x418B00, 0x418B00)),
        // SKY
        Paper(0xFFCD8B29, 0xFFA46200, 0xFFEEF6FF, 0xFFD5EEFF, 0xFF4A83EE, 0xFF294AC5, 0xFF7BBDFF, 0xFF6AACFF, 0xFFD5EEFF, Pattern.DOTS,
            hdr(0x204AE6, 0x204AE6, 0x204AE6, 0x3973F6, 0x3973F6, 0x3973F6, 0x5294FF, 0x5294FF, 0x5294FF, 0x5294FF, 0x7BBDFF, 0x7BBDFF, 0x7BBDFF, 0x7BBDFF, 0x94D5FF, 0x94D5FF)),
        // POLKA-DOT
        Paper(0xFF626AAC, 0xFF20296A, 0xFFD5DEFF, 0xFFBDC5E6, 0xFF5A6283, 0xFF394162, 0xFFB4E6FF, 0xFFD5EEFF, 0xFFD5EEFF, Pattern.POLKA,
            hdr(0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6, 0x73ACF6)),
        // POKéCENTER
        Paper(0xFFA47362, 0xFF7B3939, 0xFFFFE6D5, 0xFFEECDB4, 0xFFAC6A52, 0xFF8B4A39, 0xFFFFCDB4, 0xFFFFBDA4, 0xFFFFE6D5, Pattern.POLKA,
            hdr(0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4, 0xFFBDA4)),
        // MACHINE
        Paper(0xFF838394, 0xFF4A526A, 0xFFE6E6E6, 0xFFCDCDCD, 0xFF6A6A6A, 0xFF525252, 0xFFCDCDCD, 0xFFDEDEDE, 0xFFB4B4B4, Pattern.GRID,
            hdr(0x9C9C9C, 0xE6E6E6, 0xD5D5D5, 0xD5D5D5, 0xD5D5D5, 0xD5D5D5, 0xD5D5D5, 0xD5D5D5, 0xD5D5D5, 0xD5D5D5, 0xD5D5D5, 0xD5D5D5, 0xD5D5D5, 0xD5D5D5, 0xBDBDBD, 0x9C9C9C)),
        // PLAIN
        Paper(0xFF7B838B, 0xFF525A62, 0xFFEEEEEE, 0xFFE6E6E6, 0xFFB4B4B4, 0xFF8B8B8B, 0xFFEEEEEE, 0xFFFFFFFF, 0xFFE6E6E6, Pattern.PLAIN,
            hdr(0xD5D5D5, 0xFFFFFF, 0xDEDEDE, 0xDEDEDE, 0xDEDEDE, 0xDEDEDE, 0xDEDEDE, 0xDEDEDE, 0xDEDEDE, 0xDEDEDE, 0xDEDEDE, 0xDEDEDE, 0xDEDEDE, 0xDEDEDE, 0xFFFFFF, 0xD5D5D5)),
    )

    /** The running game's set: Emerald's for the games that run as Emerald, else FireRed's. */
    private val papers get() = if (com.pokedaisy.app.companion.data.activeGame == com.pokedaisy.app.companion.data.GameKind.EMERALD) EMERALD else FIRERED

    fun paper(id: Int): Paper = papers.let { it.getOrNull(id) ?: it.last() }

    /** [c], lowered towards the night backdrop in dark mode. */
    fun shade(c: Long, night: Boolean): Color = PcColors.shade(c, night)

    /** 0 = the base colour, 1 = [Paper.mark], 2 = [Paper.mark2]. */
    private fun ink(p: Pattern, x: Int, y: Int): Int = when (p) {
        // Little pairs of grass blades, every other row shifted half a tile.
        Pattern.TUFTS -> { val sx = (x + if ((y / 8) % 2 == 0) 0 else 4) % 8; if (y % 8 in 3..4 && (sx == 2 || sx == 4)) 1 else 0 }
        // Bricks: dark mortar under each course and between bricks, a light line along each brick's top.
        Pattern.BRICKS -> { val off = if ((y / 4) % 2 == 0) 0 else 4; when { y % 4 == 3 || (x + off) % 8 == 7 -> 1; y % 4 == 0 -> 2; else -> 0 } }
        Pattern.WAVES -> when ((y + WAVE[x % 8]) % 8) { 0 -> 2; 1 -> 1; else -> 0 }
        Pattern.STRIPES -> when (x % 8) { 0, 1 -> 1; 2 -> 2; else -> 0 }
        Pattern.CHECKS -> if ((x / 8 + y / 8) % 2 == 0) 1 else if (x % 8 == 0 || y % 8 == 0) 2 else 0
        Pattern.DIAGONAL -> when ((x + y) % 8) { 0, 1 -> 1; 2 -> 2; else -> 0 }
        Pattern.DOTS -> if ((x % 8 in 2..3 && y % 8 in 2..3) || (x % 8 in 6..7 && y % 8 in 6..7 && (x / 8 + y / 8) % 2 == 0)) 1 else 0
        Pattern.BUBBLES -> if (BUBBLE.any { (bx, by) -> x == bx && y == by }) 1 else if (BUBBLE.any { (bx, by) -> x == bx + 1 && y == by + 1 }) 2 else 0
        // Round dots in two colours, alternating.
        Pattern.POLKA -> { val cx = x % 8; val cy = y % 8; if (cx in 2..4 && cy in 2..4 && !(cx != 3 && cy != 3)) (if ((x / 8 + y / 8) % 2 == 0) 1 else 2) else 0 }
        Pattern.GRID -> if (x % 8 == 0 || y % 8 == 0) 1 else if (x % 8 == 1 || y % 8 == 1) 2 else 0
        Pattern.PLAIN -> 0
    }

    private val WAVE = intArrayOf(0, 0, 1, 2, 2, 2, 1, 0)
    private val BUBBLE = listOf(3 to 2, 2 to 3, 4 to 3, 3 to 4, 11 to 9, 10 to 10, 12 to 10, 11 to 11, 13 to 3, 6 to 12)

    private val cache = HashMap<Triple<List<Paper>, Int, Boolean>, ImageBitmap>()

    /** Wallpaper [id]'s 16x16 fill tile. */
    fun tile(id: Int, night: Boolean): ImageBitmap = synchronized(cache) {
        cache.getOrPut(Triple(papers, id, night)) {
            val paper = paper(id)
            val inks = intArrayOf(shade(paper.base, night).toArgb(), shade(paper.mark, night).toArgb(), shade(paper.mark2, night).toArgb())
            val px = IntArray(TILE * TILE) { i -> inks[ink(paper.pattern, i % TILE, i / TILE)] }
            Bitmap.createBitmap(px, TILE, TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    private const val TILE = 16
}

private const val COLUMNS = 6
private const val ROWS = 5
private const val SLOTS = 30
private const val ICON = 32f
// The game's icon is 32 pixels in a 24-pixel cell: a cell this many screen pixels per icon pixel.
private const val ICON_CELL = 24f
private val CURSOR_FILL = Color(0x66FFFFFF)
private val CURSOR_FRAME = Color(0xFFFFFFFF)
