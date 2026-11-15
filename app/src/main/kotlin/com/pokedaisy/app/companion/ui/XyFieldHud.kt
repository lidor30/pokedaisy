package com.pokedaisy.app.companion.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import com.pokedaisy.app.companion.ItemUse
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/*
 * The field HUD for a 3DS game (Pokemon X), in the game's own smooth look (XyParty's pills, XyBattleMenu's round silver
 * buttons) instead of the app's pixel HUD (the user's call, 2026-11-15). Sized in the top screen's pixels: [frame] is the
 * top screen's box in this view, 400 x 240 of them.
 */

/** Where the player is on the game's Town Map (the 3DS session reads it from the cart, Gen6TownMap): the picture's
 * rom-art path and size, and the place's middle and size in its pixels. */
data class XyMapSpot(val asset: String, val pin: String?, val mapW: Int, val mapH: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/** A wild Pokemon of the zone the player is in, ready to draw: the 3DS session builds these from the cart (Gen6Wild). */
data class XyWildRow(val method: String, val name: String, val iconAsset: String?, val minLevel: Int, val maxLevel: Int, val percent: Int)

private enum class XyPanel { SUMMARY, WILD, MAP }

private val WILD_BUTTON = XyAction(tk("WILD"), Color(0xFF8ED870), Color(0xFF3C9A30), Color(0xFF3FA034), Color(0xFF1C3814))
private val MAP_BUTTON = XyAction(tk("MAP"), Color(0xFF64B6F0), Color(0xFF2E78D0), Color(0xFF2E7CD6), Color(0xFF14265A))
private val TOUCH_BUTTON = XyAction(tk("TOUCH"), Color(0xFFF6BE62), Color(0xFFE08A24), Color(0xFFE0922C), Color(0xFF4A2A0E))
private val COMPANION_BUTTON = XyAction(tk("COMPANION"), Color(0xFFE8E8E8), Color(0xFF9A9A9A), Color(0xFF8A8A8A), Color(0xFF2A2A2A))

@Composable
fun XyFieldHud(
    snapshot: SnapshotView,
    shown: Boolean,
    /** The top screen's box in this view (px); null = nothing to draw on. */
    frame: Rect?,
    modifier: Modifier = Modifier,
    wild: List<XyWildRow> = emptyList(),
    /** The Town Map and the player's place on it; null = no MAP button. */
    map: XyMapSpot? = null,
    itemUse: ItemUse? = null,
    onTouchScreen: (() -> Unit)? = null,
    onOpenCompanion: (() -> Unit)? = null,
    onTap: () -> Unit = {},
    onPanel: (Boolean) -> Unit = {},
    /** Previews: open with WILD's or MAP's panel up. */
    openWild: Boolean = false,
    openMap: Boolean = false,
) {
    var panel by remember { mutableStateOf(if (openWild) XyPanel.WILD else if (openMap) XyPanel.MAP else null) }
    var summary by remember { mutableIntStateOf(0) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(200), label = "xyHud")
    androidx.compose.runtime.LaunchedEffect(shown) { if (!shown && panel != null) { panel = null; onPanel(false) } }
    if (frame == null || alpha == 0f) return
    val density = LocalDensity.current
    fun dp(px: Float) = with(density) { px.toDp() }
    // A top-screen pixel, never under what makes the buttons tappable on a small dense screen.
    val p = maxOf(frame.height / 240f, with(density) { 2.2f * this.density })
    val margin = 5f * p
    fun open(what: XyPanel?) { panel = what; onPanel(what != null); onTap() }
    Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        // The party: a pill per Pokemon, bottom left.
        val party = snapshot.party.take(6)
        val cellW = 28f * p
        val cellH = 25f * p
        val gap = 2.5f * p
        party.forEachIndexed { i, mon ->
            val x = frame.left + margin + i * (cellW + gap)
            val y = frame.bottom - margin - cellH
            Box(
                Modifier.offset { IntOffset(x.toInt(), y.toInt()) }.size(dp(cellW), dp(cellH))
                    .clickable(remember { MutableInteractionSource() }, null) { summary = i; open(XyPanel.SUMMARY) },
            ) {
                Canvas(Modifier.fillMaxSize()) { drawXyCell(p) }
                SpeciesIcon(
                    mon.iconAsset, size = dp(20f * p),
                    modifier = Modifier.offset { IntOffset((4f * p).toInt(), (1f * p).toInt()) },
                )
                if (!mon.isEgg) Canvas(Modifier.fillMaxSize()) {
                    drawXyMiniHp(Rect(4f * p, 18.5f * p, size.width - 4f * p, 21.5f * p), mon.hp, mon.maxHp)
                }
                val status = if (mon.hp == 0) "FNT" else mon.status
                if (status.isNotEmpty() && !mon.isEgg) Box(Modifier.align(Alignment.TopEnd).padding(top = dp(1f * p), end = dp(1f * p))) {
                    XyStatusTag(status, 5f * p)
                }
            }
        }
        // The buttons, bottom right: WILD, MAP, TOUCH, COMPANION (each where there's something for it).
        val buttons = listOfNotNull(
            if (wild.isNotEmpty()) WILD_BUTTON to { open(if (panel == XyPanel.WILD) null else XyPanel.WILD) } else null,
            map?.let { MAP_BUTTON to { open(if (panel == XyPanel.MAP) null else XyPanel.MAP) } },
            onTouchScreen?.let { TOUCH_BUTTON to { onTap(); it() } },
            onOpenCompanion?.let { COMPANION_BUTTON to { onTap(); it() } },
        )
        val size = 25f * p
        val label = 6f * p
        val step = size + 11f * p
        buttons.forEachIndexed { i, (a, click) ->
            val x = frame.right - margin - size - (buttons.size - 1 - i) * step
            val y = frame.bottom - margin - size - label
            Column(
                Modifier.offset { IntOffset((x - (step - size) / 2f).toInt(), y.toInt()) }.width(dp(step))
                    .clickable(remember(a) { MutableInteractionSource() }, null, onClick = click),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.size(dp(size))) {
                    val face = drawRoundButton(a, p, ring = 1.2f * p, silver = 1.8f * p)
                    drawXyGlyph(a, face, p)
                }
                XyText(tr(a.word), label, Color.White, Color(0xFF1E1E1E), 0.9f * p, Modifier.padding(top = dp(0.5f * p)))
            }
        }
        when (panel) {
            XyPanel.WILD -> XyWildPanel(wild, frame, p, margin + size + label + 4f * p) { open(null) }
            XyPanel.MAP -> map?.let { XyMapPanel(it, snapshot.location?.mapSecName.orEmpty(), frame, p, margin + size + label + 4f * p) { open(null) } }
            XyPanel.SUMMARY -> {
                val m = rememberGbaTextMetrics(1f)
                HudSummaryPanel(snapshot, summary, { summary = it }, m, itemUse) { open(null) }
            }
            null -> Unit
        }
    }
}

/** The game's Town Map in its green frame over the top screen, the place's name on a tab, the player's pin on the
 * place (its area lit). A tap anywhere closes it. */
@Composable
private fun XyMapPanel(map: XyMapSpot, place: String, frame: Rect, p: Float, bottomRoom: Float, onClose: () -> Unit) {
    val density = LocalDensity.current
    fun dp(px: Float) = with(density) { px.toDp() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val gen = rememberArtGeneration()
    val picture = remember(map.asset, gen) { GameArt.get(context, map.asset)?.asImageBitmap() }
    val pin = remember(map.pin, gen) { map.pin?.let { GameArt.get(context, it)?.asImageBitmap() } }
    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
    // As large as fits over the top screen above the buttons, the picture's own shape.
    val border = 4f * p
    val roomH = frame.height - bottomRoom - 14f * p
    val k = minOf((frame.width - 40f * p) / map.mapW, (roomH - border * 2) / map.mapH)
    val w = map.mapW * k + border * 2
    val h = map.mapH * k + border * 2
    val left = frame.left + (frame.width - w) / 2f
    val top = frame.top + 10f * p
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "xyMapPulse").animateFloat(
        0.35f, 0.85f, androidx.compose.animation.core.infiniteRepeatable(tween(700), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "xyMapPulseA",
    )
    Box(Modifier.offset { IntOffset(left.toInt(), top.toInt()) }.size(dp(w), dp(h))) {
        Canvas(Modifier.fillMaxSize()) {
            val r = CornerRadius(8f * p)
            drawRoundRect(Color.Black.copy(alpha = 0.3f), Offset(0f, 1.5f * p), size, r)
            drawRoundRect(XyColors.outline, cornerRadius = r)
            val rim = Rect(1.2f * p, 1.2f * p, size.width - 1.2f * p, size.height - 1.2f * p)
            drawRoundRect(Brush.verticalGradient(listOf(XyColors.rimTop, XyColors.rimBottom), rim.top, rim.bottom), rim.topLeft, rim.size, CornerRadius(7f * p))
            val inner = Rect(border, border, size.width - border, size.height - border)
            picture?.let {
                drawImage(
                    it, dstOffset = androidx.compose.ui.unit.IntOffset(inner.left.toInt(), inner.top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(inner.width.toInt(), inner.height.toInt()),
                )
            }
            // The place's area, lit and pulsing.
            val aw = maxOf(map.w, 8f) * k
            val ah = maxOf(map.h, 8f) * k
            val c = Offset(inner.left + map.x * k, inner.top + map.y * k)
            drawRoundRect(
                Color(0xFFFF8A1E).copy(alpha = pulse), Offset(c.x - aw / 2, c.y - ah / 2), Size(aw, ah), CornerRadius(minOf(aw, ah) / 2),
                style = Stroke(1.6f * p),
            )
            // The player's pin, its tip on the place (the game's own; a red dot without it).
            if (pin != null) {
                val ps = 24f * k * 1.4f
                drawImage(
                    pin, dstOffset = androidx.compose.ui.unit.IntOffset((c.x - ps / 2).toInt(), (c.y - ps * 0.92f).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(ps.toInt(), ps.toInt()),
                )
            } else drawCircle(Color(0xFFE83030), 3f * p, c)
        }
        if (place.isNotEmpty()) Box(
            Modifier.offset { IntOffset((8f * p).toInt(), (-5f * p).toInt()) }.height(dp(11f * p)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.matchParentSize()) { drawXyTab() }
            XyText(" $place ", 7f * p, Color.White, Color.Black, 0.7f * p, Modifier.padding(horizontal = dp(3f * p)))
        }
    }
}

/** The zone's wild Pokemon: a panel on the right over the buttons, a green tab per way of meeting them, a pill per
 * Pokemon (icon, name, levels, chance). A tap outside closes it. */
@Composable
private fun XyWildPanel(rows: List<XyWildRow>, frame: Rect, p: Float, bottomRoom: Float, onClose: () -> Unit) {
    val density = LocalDensity.current
    fun dp(px: Float) = with(density) { px.toDp() }
    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
    val w = 150f * p
    val top = frame.top + 8f * p
    val h = (frame.bottom - bottomRoom - top).coerceAtLeast(60f * p)
    Box(
        Modifier.offset { IntOffset((frame.right - 6f * p - w).toInt(), top.toInt()) }.size(dp(w), dp(h))
            .clickable(remember { MutableInteractionSource() }, null) {}
            .drawBehind {
                val r = CornerRadius(10f * p)
                drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(0f, 1.5f * p), this.size, r)
                drawRoundRect(XyColors.outline, cornerRadius = r)
                val i = Rect(1.2f * p, 1.2f * p, this.size.width - 1.2f * p, this.size.height - 1.2f * p)
                drawRoundRect(Brush.verticalGradient(listOf(XyColors.backTop, XyColors.backBottom), i.top, i.bottom), i.topLeft, i.size, CornerRadius(9f * p))
            },
    ) {
        Column(
            Modifier.fillMaxSize().padding(dp(5f * p)).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dp(2.5f * p)),
        ) {
            rows.groupBy { it.method }.forEach { (method, list) ->
                Box(Modifier.height(dp(10f * p)).padding(start = dp(4f * p)), contentAlignment = Alignment.CenterStart) {
                    Canvas(Modifier.matchParentSize()) { drawXyTab() }
                    XyText(" ${tr(method)} ", 6.5f * p, Color.White, Color.Black, 0.7f * p, Modifier.padding(horizontal = dp(2f * p)))
                }
                list.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().height(dp(17f * p)).drawBehind { drawXyRowPill(p) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SpeciesIcon(row.iconAsset, size = dp(15f * p), modifier = Modifier.padding(start = dp(4f * p)))
                        Box(Modifier.weight(1f).padding(start = dp(2f * p))) {
                            XyText(row.name, 7f * p, XyColors.name, Color.Transparent, 0f, weight = FontWeight.Medium)
                        }
                        val lv = if (row.minLevel == row.maxLevel) "${row.minLevel}" else "${row.minLevel}-${row.maxLevel}"
                        XyText(tr("LV {0}", lv), 5.5f * p, XyColors.name, Color.Transparent, 0f)
                        Box(Modifier.width(dp(24f * p)).padding(end = dp(4f * p)), contentAlignment = Alignment.CenterEnd) {
                            XyText("${row.percent}%", 6.5f * p, Color.White, XyColors.outline, 0.8f * p)
                        }
                    }
                }
            }
        }
    }
}

/** A party cell: the pill's layers, rounded (not a full pill: it's nearly square). */
private fun DrawScope.drawXyCell(p: Float) {
    val r = CornerRadius(8f * p)
    drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(0f, 1.2f * p), size, r)
    drawRoundRect(XyColors.outline, cornerRadius = r)
    val rim = Rect(0.7f * p, 0.7f * p, size.width - 0.7f * p, size.height - 0.7f * p)
    drawRoundRect(Brush.verticalGradient(listOf(XyColors.rimTop, XyColors.rimBottom), rim.top, rim.bottom), rim.topLeft, rim.size, CornerRadius(7.3f * p))
    val face = rim.deflate(1.8f * p)
    drawRoundRect(Brush.verticalGradient(listOf(XyColors.faceTop, XyColors.faceBottom), face.top, face.bottom), face.topLeft, face.size, CornerRadius(5.5f * p))
}

/** A wild row's pill: a thin dark green edge round the game's white-grey face. */
private fun DrawScope.drawXyRowPill(p: Float) {
    val r = CornerRadius(size.height / 2)
    drawRoundRect(XyColors.outline, cornerRadius = r)
    val face = Rect(0.9f * p, 0.9f * p, size.width - 0.9f * p, size.height - 0.9f * p)
    drawRoundRect(Brush.verticalGradient(listOf(XyColors.faceLow, XyColors.faceBottom), face.top, face.bottom), face.topLeft, face.size, CornerRadius(face.height / 2))
}

/** HP under a party cell's icon: the bar alone, in its dark frame. */
private fun DrawScope.drawXyMiniHp(r: Rect, hp: Int, maxHp: Int) {
    drawRoundRect(XyColors.hpFrame, r.topLeft, r.size, CornerRadius(r.height / 2))
    val share = if (maxHp > 0) (hp.toFloat() / maxHp).coerceIn(0f, 1f) else 0f
    if (share <= 0f) return
    val (light, dark) = when {
        share > 0.5f -> XyColors.hpHigh to XyColors.hpHighDark
        share > 0.2f -> XyColors.hpMid to XyColors.hpMidDark
        else -> XyColors.hpLow to XyColors.hpLowDark
    }
    val bar = r.deflate(r.height * 0.22f)
    drawRoundRect(Brush.verticalGradient(listOf(light, dark), bar.top, bar.bottom), bar.topLeft, Size(bar.width * share, bar.height), CornerRadius(bar.height / 2))
}

/** A button's picture in white with a dark edge: grass blades (WILD), a folded map (MAP), a screen and stylus (TOUCH),
 * a chevron (COMPANION: it opens from the right). */
private fun DrawScope.drawXyGlyph(a: XyAction, face: Rect, p: Float) {
    val c = face.center
    val s = face.width
    fun stroke(path: Path, w: Float) {
        drawPath(path, a.ink.copy(alpha = 0.55f), style = Stroke(w + 1.4f * p, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color.White, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    when (a) {
        WILD_BUTTON -> stroke(Path().apply {
            moveTo(c.x - s * 0.22f, c.y + s * 0.2f); quadraticBezierTo(c.x - s * 0.24f, c.y - s * 0.02f, c.x - s * 0.32f, c.y - s * 0.16f)
            moveTo(c.x, c.y + s * 0.2f); lineTo(c.x, c.y - s * 0.26f)
            moveTo(c.x + s * 0.22f, c.y + s * 0.2f); quadraticBezierTo(c.x + s * 0.24f, c.y - s * 0.02f, c.x + s * 0.32f, c.y - s * 0.16f)
        }, 2.2f * p)
        MAP_BUTTON -> stroke(Path().apply {
            moveTo(c.x - s * 0.3f, c.y - s * 0.2f); lineTo(c.x - s * 0.1f, c.y - s * 0.28f); lineTo(c.x + s * 0.1f, c.y - s * 0.2f)
            lineTo(c.x + s * 0.3f, c.y - s * 0.28f); lineTo(c.x + s * 0.3f, c.y + s * 0.2f); lineTo(c.x + s * 0.1f, c.y + s * 0.28f)
            lineTo(c.x - s * 0.1f, c.y + s * 0.2f); lineTo(c.x - s * 0.3f, c.y + s * 0.28f); close()
            moveTo(c.x - s * 0.1f, c.y - s * 0.28f); lineTo(c.x - s * 0.1f, c.y + s * 0.2f)
            moveTo(c.x + s * 0.1f, c.y - s * 0.2f); lineTo(c.x + s * 0.1f, c.y + s * 0.28f)
        }, 1.6f * p)
        TOUCH_BUTTON -> stroke(Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(c.x - s * 0.28f, c.y - s * 0.2f, c.x + s * 0.2f, c.y + s * 0.2f), CornerRadius(2f * p)))
            moveTo(c.x + s * 0.06f, c.y + s * 0.04f); lineTo(c.x + s * 0.32f, c.y - s * 0.28f)
        }, 1.6f * p)
        else -> stroke(Path().apply {
            moveTo(c.x + s * 0.08f, c.y - s * 0.24f); lineTo(c.x - s * 0.14f, c.y); lineTo(c.x + s * 0.08f, c.y + s * 0.24f)
        }, 2.6f * p)
    }
}
