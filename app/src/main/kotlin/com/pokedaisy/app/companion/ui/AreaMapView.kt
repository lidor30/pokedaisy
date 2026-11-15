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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * the AREA map by default, the region map after REGION MAP, back with AREA MAP. Shared by both screens; a new area starts
 * on its AREA map again.
 */
object AreaMapChoice {
    private var regionFor by mutableStateOf<AreaMap.MapId?>(null)

    /** The area's key: its first floor (the same whichever floor the player is on). */
    private fun key(area: AreaMap.Area) = area.floors.firstOrNull()?.id

    fun showsArea(area: AreaMap.Area?): Boolean = area != null && regionFor != key(area)
    fun showRegion(area: AreaMap.Area) { regionFor = key(area) }
    fun showArea() { regionFor = null }
}

/** The area the player is in, when it's worth a map ([AreaMap.Area.worthShowing]); null outdoors and in a one-room
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
        // A chip per floor: the one shown red, the player's with their pin.
        if (area.floors.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                area.floors.forEachIndexed { i, f ->
                    OptionButton(
                        f.label, m, onClick = { shown = i }, emphasis = i == shown,
                        pixelIcon = if (f.id == here) PixelIcons.pin else null, sidePadding = 5,
                    )
                }
            }
        }
    }
}

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
 * The HUD minimap's AREA map: [mapW] x [mapH] GBA pixels of the player's floor around them, half size (a metatile 8
 * pixels: the room around the player, more than the screen shows), their head in the middle.
 */
@Composable
internal fun HudAreaMap(snapshot: SnapshotView, mapW: Int, mapH: Int, u: Dp, modifier: Modifier = Modifier) {
    val id = AreaMap.MapId(snapshot.mapGroup, snapshot.mapNum)
    val image = rememberAreaImage(id) ?: return
    val style = MapLabelStyle.of(snapshot.game)
    val head = regionMapBitmap(style.headAsset(snapshot.playerGender, snapshot.game))
    val headImage = remember(head) { head?.asImageBitmap() }
    Box(modifier.size(u * mapW, u * mapH).clip(PixelRoundedShape(u * 2)).background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val gba = u.toPx()
            val s = gba / 2
            val w = image.width * s
            val h = image.height * s
            val px = (snapshot.x * 16 + 8) * s
            val py = (snapshot.y * 16 + 8) * s
            fun place(len: Float, room: Float, focus: Float) =
                if (len <= room) (room - len) / 2 else (room / 2 - focus).coerceIn(room - len, 0f)
            val left = place(w, size.width, px)
            val top = place(h, size.height, py)
            drawImage(image, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.None)
            drawHead(headImage, left + px, top + py, gba, style.cursorColor)
        }
    }
}
