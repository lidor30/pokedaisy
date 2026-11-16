package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pokedaisy.app.companion.data.AreaMap
import com.pokedaisy.app.companion.data.AreaMaps
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.gameCase
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which map the MAP tab and the HUD's minimap show while the player is in an area with a map of its own (AreaMap.kt):
 * on a large floor the AREA map, on a small room of it the region map ([AreaMap.Area.opensOn]); REGION MAP / AREA MAP
 * switch, and the pick holds on every floor of the area. Shared by both screens; a new area starts on its default again.
 */
object AreaMapChoice {
    /** The area the player picked a map in (its key) and whether that pick was the AREA map; null = none yet. */
    private var picked by mutableStateOf<Pair<AreaMap.MapId?, Boolean>?>(null)

    /** The area's key: its first floor (the same whichever floor the player is on). */
    private fun key(area: AreaMap.Area) = area.floors.firstOrNull()?.id

    fun showsArea(area: AreaMap.Area?, here: AreaMap.MapId): Boolean {
        if (area == null) return false
        val p = picked
        return if (p != null && p.first == key(area)) p.second else area.opensOn(here)
    }
    fun showRegion(area: AreaMap.Area) { picked = key(area) to false }
    fun showArea(area: AreaMap.Area) { picked = key(area) to true }
    /** Back to each floor's default (previews and tests). */
    fun reset() { picked = null }
}

/** The area the player is in, when it's worth a map ([AreaMap.Area.worthShowing]); null outdoors and in a small
 * building, or before it's read. */
@Composable
fun rememberPlayerArea(snapshot: SnapshotView): AreaMap.Area? {
    val id = AreaMap.MapId(snapshot.mapGroup, snapshot.mapNum)
    val area by produceState(AreaMaps.cachedArea(id), snapshot.game, id) {
        value = withContext(Dispatchers.IO) { runCatching { AreaMaps.area(id) }.getOrNull() }
    }
    return area?.takeIf { it.worthShowing && it.indexOf(id) >= 0 }
}

@Composable
private fun rememberAreaImage(id: AreaMap.MapId): ImageBitmap? {
    val img by produceState(AreaMaps.cachedImage(id)?.bitmap()?.asImageBitmap(), id) {
        value = withContext(Dispatchers.IO) { runCatching { AreaMaps.image(id)?.bitmap()?.asImageBitmap() }.getOrNull() }
    }
    return img
}

/**
 * The MAP tab's AREA map: the floor the player is on drawn whole (fitted to the tab, or at the game's own size around
 * the player with ZOOM, dragged to look around), their head where they stand; over it the place and floor with ME /
 * REGION MAP / ZOOM, under it a chip per floor.
 */
@Composable
fun AreaMapPane(area: AreaMap.Area, snapshot: SnapshotView, onRegion: () -> Unit, modifier: Modifier = Modifier) {
    val m = rememberGbaTextMetrics(1f)
    val u = m.u
    val here = AreaMap.MapId(snapshot.mapGroup, snapshot.mapNum)
    var shown by remember(area) { mutableStateOf(area.indexOf(here).coerceAtLeast(0)) }
    // Back to the player's floor when they take the stairs.
    LaunchedEffect(here) { area.indexOf(here).takeIf { it >= 0 }?.let { shown = it } }
    var zoomed by remember { mutableStateOf(false) }
    var pan by remember(shown, zoomed) { mutableStateOf(Offset.Zero) }
    val floor = area.floors[shown]
    val onFloor = floor.id == here
    val image = rememberAreaImage(floor.id)
    val style = MapLabelStyle.of(snapshot.game)
    val head = regionMapBitmap(style.headAsset(snapshot.playerGender, snapshot.game))
    val headImage = remember(head) { head?.asImageBitmap() }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(u * 4)) {
        // The place and floor, REGION MAP, ZOOM: a bar over the map (the map stays whole under it).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 4)) {
            OptionTitleWindow(gameCase(snapshot.location.mapSecName).ifEmpty { " " }, m, Modifier.weight(1f), trailing = floor.label)
            if (!onFloor) OptionButton(tr("ME"), m, emphasis = true, onClick = { area.indexOf(here).takeIf { it >= 0 }?.let { shown = it } })
            OptionButton(tr("REGION MAP"), m, onClick = onRegion)
            OptionButton(
                null, rememberGbaTextMetrics(), onClick = { zoomed = !zoomed }, contentDescription = tr("ZOOM"),
                pixelIcon = if (zoomed) PixelIcons.zoomOut else PixelIcons.zoomIn,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(PixelRoundedShape(u * 2)).background(Color.Black)) {
            if (image != null) Canvas(
                Modifier.fillMaxSize().pointerInput(zoomed, shown) {
                    if (zoomed) detectDragGestures { _, drag -> pan += drag }
                },
            ) {
                val gba = u.toPx()
                val s = if (zoomed) gba else minOf(size.width / image.width, size.height / image.height)
                val w = image.width * s
                val h = image.height * s
                // Fitted: centred. Zoomed: the player (or the floor's middle) in the middle, the map kept on screen.
                val focusX = if (onFloor) (snapshot.x * 16 + 8) * s else w / 2
                val focusY = if (onFloor) (snapshot.y * 16 + 8) * s else h / 2
                fun place(len: Float, room: Float, focus: Float, p: Float) =
                    if (len <= room) (room - len) / 2 else (room / 2 - focus + p).coerceIn(room - len, 0f)
                val left = if (zoomed) place(w, size.width, focusX, pan.x) else (size.width - w) / 2
                val top = if (zoomed) place(h, size.height, focusY, pan.y) else (size.height - h) / 2
                drawImage(
                    image, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(w.toInt(), h.toInt()),
                    filterQuality = FilterQuality.None,
                )
                if (onFloor) drawHead(headImage, left + (snapshot.x * 16 + 8) * s, top + (snapshot.y * 16 + 8) * s, maxOf(s, gba), style.cursorColor)
            }
        }
        // A button per floor: the one shown red, the player's with their pin. Big enough to hit: the buttons share the
        // row (up to [FLOOR_MAX_W] each, centred) when they fit at [FLOOR_MIN_W] GBA pixels, else keep that width and
        // the row scrolls (Silph Co.'s 11).
        if (area.floors.size > 1) {
            val chipM = rememberGbaTextMetrics()
            val scroll = rememberScrollState()
            // The shown floor in view (stepping floors, or the player taking the stairs to one off the edge).
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val gap = u * 3
                val n = area.floors.size
                val chipW = ((maxWidth - gap * (n - 1)) / n).coerceIn(u * FLOOR_MIN_W, u * FLOOR_MAX_W)
                val stepPx = with(LocalDensity.current) { (chipW + gap).roundToPx() }
                val viewPx = constraints.maxWidth
                LaunchedEffect(shown, stepPx) {
                    scroll.animateScrollTo((stepPx * shown - (viewPx - stepPx) / 2).coerceAtLeast(0))
                }
                Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    area.floors.forEachIndexed { i, f ->
                        OptionButton(
                            f.label, chipM, onClick = { shown = i }, emphasis = i == shown,
                            modifier = Modifier.width(chipW).heightIn(min = u * FLOOR_MIN_H),
                            pixelIcon = if (f.id == here) PixelIcons.pin else null, sidePadding = 4,
                        )
                    }
                }
            }
        }
    }
}

/** A floor button's width (least, most) and least height in GBA pixels: a thumb's width on the Thor's bottom screen. */
private const val FLOOR_MIN_W = 40
private const val FLOOR_MAX_W = 72
private const val FLOOR_MIN_H = 26

/** The player's region-map head centred on (cx, cy), [px] screen pixels a head pixel; no head = a cursor box. */
private fun DrawScope.drawHead(head: ImageBitmap?, cx: Float, cy: Float, px: Float, cursor: Color) {
    if (head != null) {
        val w = head.width * px
        val h = head.height * px
        drawImage(
            head, dstOffset = IntOffset((cx - w / 2).toInt(), (cy - h / 2).toInt()), dstSize = IntSize(w.toInt(), h.toInt()),
            filterQuality = FilterQuality.None,
        )
    } else {
        val r = 8 * px
        drawMapCursor((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt(), 1f, 1f, big = false, color = cursor)
    }
}

/**
 * The HUD minimap's AREA map: [mapW] x [mapH] GBA pixels of the player's floor around them, a quarter size (a metatile
 * 4 pixels, [AreaMaps.miniImage]: about three screens of room around the player, the whole of most floors), their head
 * in the middle.
 */
@Composable
internal fun HudAreaMap(snapshot: SnapshotView, mapW: Int, mapH: Int, u: Dp, modifier: Modifier = Modifier) {
    val id = AreaMap.MapId(snapshot.mapGroup, snapshot.mapNum)
    val image by produceState(AreaMaps.cachedMiniImage(id)?.bitmap()?.asImageBitmap(), id) {
        value = withContext(Dispatchers.IO) { runCatching { AreaMaps.miniImage(id)?.bitmap()?.asImageBitmap() }.getOrNull() }
    }
    val img = image ?: return
    val style = MapLabelStyle.of(snapshot.game)
    val head = regionMapBitmap(style.headAsset(snapshot.playerGender, snapshot.game))
    val headImage = remember(head) { head?.asImageBitmap() }
    Box(modifier.size(u * mapW, u * mapH).clip(PixelRoundedShape(u * 2)).background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            // A map pixel a GBA pixel, at whole screen pixels like the region minimap.
            val s = u.toPx()
            val w = img.width * s
            val h = img.height * s
            val cell = AreaMaps.MINI_SCALE
            val px = (snapshot.x * cell + cell / 2f) * s
            val py = (snapshot.y * cell + cell / 2f) * s
            fun place(len: Float, room: Float, focus: Float) =
                if (len <= room) (room - len) / 2 else (room / 2 - focus).coerceIn(room - len, 0f)
            // Whole GBA pixels, so the map's pixels stay even.
            val left = (place(w, size.width, px) / s).toInt() * s
            val top = (place(h, size.height, py) / s).toInt() * s
            drawImage(img, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.None)
            drawHead(headImage, left + px, top + py, s, style.cursorColor)
        }
    }
}
