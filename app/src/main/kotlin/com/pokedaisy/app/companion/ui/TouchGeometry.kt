package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchLayout
import com.pokedaisy.app.companion.TouchPlace
import com.pokedaisy.app.companion.TouchPreset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Where the touch controls go and what a touch presses: one piece of plain math shared by the game's controls
 * ([TouchControls]), SETTINGS' preview ([TouchControlsPreview]) and EDIT LAYOUT ([TouchLayoutEditor]), so the
 * three always agree.
 *
 * Sizes are in dp (a thumb is the same size on every phone), scaled by SIZE and each control's own size, and shrunk
 * together when the controls' area is too small for them (an upright phone's gap under the game). A preset places
 * everything from the area's size ([TouchPreset]); the player's moved controls ([TouchLayout]) sit at a distance
 * from an edge (or the middle), so they stay under the thumb on another phone or with the status bar on.
 */
object TouchGeometry {
    /** What a control looks like (and how it's hit). */
    enum class Shape { CROSS, ROUND, PILL }

    /** One control placed: its centre and size in px. */
    data class Item(val button: TouchButton, val cx: Float, val cy: Float, val w: Float, val h: Float, val shape: Shape) {
        val left get() = cx - w / 2
        val top get() = cy - h / 2
        val right get() = cx + w / 2
        val bottom get() = cy + h / 2
    }

    /** Everything placed in a [w]x[h] area: the items, and the [preset] they came from (SIDES may fall back to CLASSIC). */
    data class Placed(val w: Float, val h: Float, val items: List<Item>, val preset: TouchPreset, val dp: Float) {
        fun item(b: TouchButton) = items.firstOrNull { it.button == b }
    }

    /** The room kept from the area's edges (dp). */
    const val EDGE = 14f

    // A control's size at SIZE NORMAL (dp).
    private const val DPAD = 136f
    private const val FACE = 66f
    private const val PILL_W = 74f
    private const val PILL_H = 30f
    private const val SHOULDER_W = 88f
    private const val SHOULDER_H = 36f
    private const val EXTRA_W = 66f
    private const val EXTRA_H = 30f
    private const val TURBO = 58f
    private const val GAP = 8f

    /** A DS game's A / B / X / Y: each this big (dp at NORMAL), a button apart in a diamond three wide - the D-pad's size. */
    private const val DS_FACE = 45f
    /** A DS game held sideways: SELECT / START this far in from the D-pad and the diamond (dp at NORMAL). */
    private const val DS_PILL_GAP = 24f

    /** A and B's centres from the middle of their pair (dp at NORMAL): A up and right, B down and left, like the GBA's. */
    private const val FACE_DX = 36f
    private const val FACE_DY = 12f

    /** SIDES needs margins this wide (dp) beside a 3:2 game, the controls shrunk to fit at most this much. */
    private const val SIDES_MIN_FIT = 0.78f

    fun baseSize(b: TouchButton): Pair<Float, Float> = when (b) {
        TouchButton.DPAD -> DPAD to DPAD
        TouchButton.A, TouchButton.B, TouchButton.X, TouchButton.Y -> FACE to FACE
        TouchButton.L, TouchButton.R -> SHOULDER_W to SHOULDER_H
        TouchButton.START, TouchButton.SELECT -> PILL_W to PILL_H
        TouchButton.TURBO_A, TouchButton.TURBO_B -> TURBO to TURBO
        else -> EXTRA_W to EXTRA_H
    }

    fun shape(b: TouchButton): Shape = when (b) {
        TouchButton.DPAD -> Shape.CROSS
        TouchButton.A, TouchButton.B, TouchButton.X, TouchButton.Y, TouchButton.TURBO_A, TouchButton.TURBO_B -> Shape.ROUND
        else -> Shape.PILL
    }

    /**
     * Lays the controls out in a [w]x[h] px area at [dp] px per dp. [portrait]: a phone held upright (the area is the
     * gap under the game, or over it); [gameAspect] the game's shape (SIDES measures the margins beside it).
     * [layout] = the player's moved controls, null = the preset's alone (EDIT LAYOUT's RESET, the preview of a preset).
     */
    fun place(
        style: TouchControlsStyle, w: Float, h: Float, dp: Float, portrait: Boolean,
        gameAspect: Float = 1.5f, gameBoy: Boolean = false, layout: TouchLayout? = style.layoutFor(portrait),
        ds: Boolean = false,
        /** Extras switched on that do nothing in this game now ([TouchControlsStyle.shown]). */
        unavailable: Set<TouchButton> = emptySet(),
    ): Placed {
        if (w <= 0f || h <= 0f) return Placed(w, h, emptyList(), style.preset, dp)
        val buttons = style.shown(gameBoy, ds, unavailable)
        val (preset, presetItems) = presetItems(style, buttons, w, h, dp, portrait, gameAspect, ds)
        val custom = layout?.places.orEmpty()
        if (custom.isEmpty()) return Placed(w, h, presetItems, preset, dp)
        // A moved control keeps the preset's size times its own (SIZE and the area's fit still apply).
        val items = presetItems.mapNotNull { item ->
            val p = custom[item.button] ?: return@mapNotNull item
            if (p.hidden) return@mapNotNull null
            val iw = item.w * p.scale
            val ih = item.h * p.scale
            val cx = resolve(p.ax, p.dx * dp, w).coerceIn(iw / 2, max(iw / 2, w - iw / 2))
            val cy = resolve(p.ay, p.dy * dp, h).coerceIn(ih / 2, max(ih / 2, h - ih / 2))
            item.copy(cx = cx, cy = cy, w = iw, h = ih)
        }
        return Placed(w, h, items, preset, dp)
    }

    /**
     * How tall the controls want to be upright on a [w]-wide screen (px): the preset's block with its edges - EXPAND +
     * UNDER GAME's strip along the bottom is this tall ([PortraitLayout.arrange]'s padStrip).
     */
    fun naturalHeight(style: TouchControlsStyle, w: Float, dp: Float, gameBoy: Boolean = false, ds: Boolean = false): Int {
        val tall = style.copy(position = com.pokedaisy.app.companion.TouchPosition.TOP)
        val p = place(tall, w, w * 3, dp, portrait = true, gameBoy = gameBoy, layout = null, ds = ds)
        return ((p.items.maxOfOrNull { it.bottom } ?: 0f) + EDGE * dp).toInt()
    }

    /** How much the whole set shrinks to fit a small area (an upright phone's gap): never grows. */
    fun fit(w: Float, h: Float, dp: Float, portrait: Boolean): Float {
        val needW = if (portrait) 380f else 420f
        val needH = if (portrait) 230f else 260f
        return min(1f, min(w / dp / needW, h / dp / needH)).coerceAtLeast(0.45f)
    }

    private fun resolve(a: TouchPlace.Anchor, d: Float, size: Float) = when (a) {
        TouchPlace.Anchor.START -> d
        TouchPlace.Anchor.CENTER -> size / 2 + d
        TouchPlace.Anchor.END -> size - d
    }

    /** [item]'s place as the player leaves it: from the nearest edge (or the middle third: the middle), in dp;
     * [scale] = its size against the preset's. */
    fun placeOf(item: Item, w: Float, h: Float, dp: Float, scale: Float, hidden: Boolean = false): TouchPlace {
        fun anchor(c: Float, size: Float): Pair<TouchPlace.Anchor, Float> = when {
            c < size / 3 -> TouchPlace.Anchor.START to c / dp
            c > size * 2 / 3 -> TouchPlace.Anchor.END to (size - c) / dp
            else -> TouchPlace.Anchor.CENTER to (c - size / 2) / dp
        }
        val (ax, dx) = anchor(item.cx, w)
        val (ay, dy) = anchor(item.cy, h)
        return TouchPlace(ax, dx, ay, dy, scale, hidden)
    }

    // ---- presets --------------------------------------------------------------------------------------------------

    private class Builder(val w: Float, val h: Float, val dp: Float, val s: Float, val dsGame: Boolean = false) {
        val items = ArrayList<Item>()
        fun size(b: TouchButton) = baseSize(b).let { (bw, bh) -> bw * s * dp to bh * s * dp }
        fun shape(b: TouchButton) = TouchGeometry.shape(b)
        /** [b] centred [x] / [y] px from the left / top. */
        fun at(b: TouchButton, x: Float, y: Float) {
            val (iw, ih) = size(b)
            items += Item(b, x, y, iw, ih, shape(b))
        }
        fun px(d: Float) = d * dp
        fun sc(d: Float) = d * s * dp
    }

    private fun presetItems(
        style: TouchControlsStyle, buttons: List<TouchButton>, w: Float, h: Float, dp: Float,
        portrait: Boolean, gameAspect: Float, dsGame: Boolean,
    ): Pair<TouchPreset, List<Item>> = when (style.preset) {
        TouchPreset.MIRRORED -> {
            val (_, items) = basePresetItems(style.copy(preset = TouchPreset.SIDES), buttons, w, h, dp, portrait, gameAspect, dsGame)
            TouchPreset.MIRRORED to mirrored(items, w, portrait, dsGame)
        }
        TouchPreset.RAISED -> {
            val (_, items) = basePresetItems(style.copy(preset = TouchPreset.CLASSIC), buttons, w, h, dp, portrait, gameAspect, dsGame)
            TouchPreset.RAISED to if (portrait) items else raised(items, h, dp, dsGame)
        }
        else -> basePresetItems(style, buttons, w, h, dp, portrait, gameAspect, dsGame)
    }

    /** MIRRORED: [items] flipped left to right, but A / B (and X / Y, and upright the TURBOs over them) moved as one
     * block so A stays right of B, L / R left where they are and SELECT kept left of START. */
    private fun mirrored(items: List<Item>, w: Float, portrait: Boolean, dsGame: Boolean): List<Item> {
        val face = setOf(TouchButton.A, TouchButton.B, TouchButton.X, TouchButton.Y) +
            if (portrait) setOf(TouchButton.TURBO_A, TouchButton.TURBO_B) else emptySet()
        val cluster = items.filter { it.button in face }
        val shift = if (cluster.isEmpty()) 0f else w - (cluster.minOf { it.left } + cluster.maxOf { it.right })
        val flipped = items.map { item ->
            when (item.button) {
                TouchButton.L, TouchButton.R -> item
                in face -> item.copy(cx = item.cx + shift)
                else -> item.copy(cx = w - item.cx)
            }
        }
        val select = flipped.firstOrNull { it.button == TouchButton.SELECT }
        val start = flipped.firstOrNull { it.button == TouchButton.START }
        if (select == null || start == null || select.cx < start.cx) return flipped
        return flipped.map { when (it.button) {
            TouchButton.SELECT -> it.copy(cx = start.cx, cy = start.cy)
            TouchButton.START -> it.copy(cx = select.cx, cy = select.cy)
            else -> it
        } }
    }

    /** RAISED: CLASSIC's D-pad and A / B block lifted to about 60% down the area (no higher than the top row allows),
     * L over the D-pad and R over A / B. Where there's no room to lift, CLASSIC as it is. */
    private fun raised(items: List<Item>, h: Float, dp: Float, dsGame: Boolean): List<Item> {
        val dpad = items.firstOrNull { it.button == TouchButton.DPAD } ?: return items
        val faceSet = setOf(TouchButton.A, TouchButton.B, TouchButton.X, TouchButton.Y)
        val face = items.filter { it.button in faceSet }
        if (face.isEmpty()) return items
        val gap = GAP * dp
        val l = items.firstOrNull { it.button == TouchButton.L }
        val r = items.firstOrNull { it.button == TouchButton.R }
        val moving = faceSet + TouchButton.DPAD + TouchButton.L + TouchButton.R
        // The top row (the extras along the top) stays: everything raised stays under it.
        val topLimit = (items.filter { it.button !in moving && it.cy < h / 2 }.maxOfOrNull { it.bottom } ?: (EDGE * dp)) + gap
        val shoulder = l?.h ?: 0f
        val blockTop = minOf(dpad.top, face.minOf { it.top })
        val minDy = topLimit + (if (l != null) shoulder + gap else 0f) - blockTop
        val want = h * 0.6f - dpad.cy
        val dy = maxOf(want, minDy)
        if (dy >= -1f) return items   // nowhere to lift it to
        val faceTop = face.minOf { it.top } + dy
        val faceCx = (face.minOf { it.left } + face.maxOf { it.right }) / 2
        return items.map { item ->
            when {
                item.button == TouchButton.DPAD || item.button in faceSet -> item.copy(cy = item.cy + dy)
                item === l -> item.copy(cx = dpad.cx, cy = dpad.top + dy - gap - item.h / 2)
                item === r -> item.copy(cx = faceCx, cy = faceTop - gap - item.h / 2)
                else -> item
            }
        }
    }

    private fun basePresetItems(
        style: TouchControlsStyle, buttons: List<TouchButton>, w: Float, h: Float, dp: Float,
        portrait: Boolean, gameAspect: Float, dsGame: Boolean,
    ): Pair<TouchPreset, List<Item>> {
        val size = style.size.scale
        val fit = fit(w, h, dp, portrait)
        // A DS game held sideways: its two screens fill the width, so no margins to put SIDES' columns in - the D-pad and
        // A / B / X / Y in the bottom corners instead, SELECT / START just inside them (the user's call).
        if (style.preset == TouchPreset.SIDES && !portrait && !dsGame) {
            // The margins beside the game, the game as tall as the area.
            val margin = (w - h * gameAspect) / 2 / dp
            val sidesFit = min(1f, (margin - 2 * GAP) / (DPAD * size))
            if (sidesFit >= SIDES_MIN_FIT) return TouchPreset.SIDES to sides(buttons, w, h, dp, size * sidesFit, margin * dp)
        }
        val preset = if (style.preset == TouchPreset.SIDES) TouchPreset.CLASSIC else style.preset
        val compact = preset == TouchPreset.COMPACT
        var s = size * fit * if (compact) 0.82f else 1f
        if (portrait && !compact) {
            // Upright, L, SELECT, START and R share the top row: never wider than the phone.
            val shoulders = if (TouchButton.L in buttons) 2 * SHOULDER_W else 0f
            s = min(s, (w / dp - 2 * EDGE - 3 * GAP) / (shoulders + 2 * PILL_W))
        }
        val withExtras = { t: TouchButton -> t.extra }
        // The bottom row - the D-pad, upright CLASSIC's extras column, A and B - never wider than the area either.
        val column = if (portrait && !compact && buttons.any(withExtras)) EXTRA_W * 0.8f + GAP else 0f
        s = min(s, (w / dp - 2 * EDGE - GAP) / (DPAD + FACE + 2 * FACE_DX + column))
        // The left column top to bottom (the top row, COMPACT's SELECT, the D-pad): never taller than the area. The top
        // row holds the extras too, but upright in CLASSIC (their own column there).
        val extrasRow = if (buttons.none(withExtras)) 0f
            else max(EXTRA_H, if (TouchButton.TURBO_A in buttons || TouchButton.TURBO_B in buttons) TURBO else 0f)
        val topRow = when {
            portrait && !compact -> max(SHOULDER_H, PILL_H)
            else -> max(if (TouchButton.L in buttons) SHOULDER_H else 0f, extrasRow)
        }
        val gaps = (if (topRow > 0f) GAP else 0f) + (if (compact) GAP else 0f)
        s = min(s, (h / dp - 2 * EDGE - gaps) / (topRow + DPAD + if (compact) PILL_H else 0f))
        val b = Builder(w, h, dp, s, dsGame)
        val e = b.px(EDGE)
        val has = { t: TouchButton -> t in buttons }

        val dpadR = b.sc(DPAD) / 2
        val faceR = b.sc(FACE) / 2
        val shoulderH = b.sc(SHOULDER_H)
        val shoulderW = b.sc(SHOULDER_W)
        val pillW = b.sc(PILL_W)
        val pillH = b.sc(PILL_H)
        // The D-pad and the A / B pair along the bottom corners; upright, in the middle of the room under the top row
        // (a tall gap left them far below it).
        val dpadX = e + dpadR
        val rowBottom = e + max(shoulderH, pillH)
        // Upright the D-pad sits right under the top row (room for TURBO A / B over A and B when they're on), and the
        // whole block then goes where POSITION puts it ([shiftTo]).
        val turboRoom = if (TouchButton.TURBO_A in buttons || TouchButton.TURBO_B in buttons) b.sc(TURBO * 0.5f) else 0f
        val dpadY = if (portrait && !compact) min(h - e - dpadR, rowBottom + b.px(GAP) + turboRoom + dpadR) else h - e - dpadR
        b.at(TouchButton.DPAD, dpadX, dpadY)
        val faceX = w - e - faceR - b.sc(FACE_DX)
        val faceY = dpadY + dpadR - faceR - b.sc(FACE_DY)
        // X / Y on (a DS game's, or a GBA / Game Boy game's with X AND Y): A / B / X / Y in the DS's diamond.
        val diamondFace = has(TouchButton.X)
        if (diamondFace) diamond(b, w - e - dpadR, dpadY)
        else {
            b.at(TouchButton.A, faceX + b.sc(FACE_DX), faceY - b.sc(FACE_DY))
            b.at(TouchButton.B, faceX - b.sc(FACE_DX), faceY + b.sc(FACE_DY))
        }
        if (has(TouchButton.L)) {
            b.at(TouchButton.L, e + shoulderW / 2, e + shoulderH / 2)
            b.at(TouchButton.R, w - e - shoulderW / 2, e + shoulderH / 2)
        }
        val faceTop = if (diamondFace) dpadY - dpadR else faceY - b.sc(FACE_DY) - faceR
        when {
            compact -> {
                // Over the D-pad and over A / B.
                b.at(TouchButton.SELECT, dpadX, dpadY - dpadR - b.px(GAP) - pillH / 2)
                b.at(TouchButton.START, if (diamondFace) w - e - dpadR else faceX, faceTop - b.px(GAP) - pillH / 2)
            }
            portrait -> {
                // The top row's middle, between L and R.
                val y = e + pillH / 2
                b.at(TouchButton.SELECT, w / 2 - b.px(GAP) / 2 - pillW / 2, y)
                b.at(TouchButton.START, w / 2 + b.px(GAP) / 2 + pillW / 2, y)
            }
            dsGame -> {
                // Along the bottom, a little in from the D-pad and the diamond (the touch screen's COMPANION switch goes
                // above it sideways, DsTouchPane, out of START's way).
                val y = h - e - pillH / 2
                b.at(TouchButton.SELECT, e + 2 * dpadR + b.px(DS_PILL_GAP) + pillW / 2, y)
                b.at(TouchButton.START, w - e - 2 * dpadR - b.px(DS_PILL_GAP) - pillW / 2, y)
            }
            else -> {
                val y = h - e - pillH / 2
                b.at(TouchButton.SELECT, w / 2 - b.px(GAP) / 2 - pillW / 2, y)
                b.at(TouchButton.START, w / 2 + b.px(GAP) / 2 + pillW / 2, y)
            }
        }
        val extras = buttons.filter(withExtras)
        if (extras.isNotEmpty()) {
            val shouldersEnd = if (has(TouchButton.L)) e + shoulderW + b.px(GAP) else e
            if (portrait && !compact) {
                // TURBO A / B over A and B (where the thumb already is), as big as the room under the top row allows.
                val column = extras.toMutableList()
                val gap = b.px(GAP)
                for ((turbo, faceCx, faceCy) in listOf(
                    Triple(TouchButton.TURBO_A, faceX + b.sc(FACE_DX), faceY - b.sc(FACE_DY)),
                    Triple(TouchButton.TURBO_B, faceX - b.sc(FACE_DX), faceY + b.sc(FACE_DY)),
                )) {
                    if (turbo !in extras) continue
                    if (diamondFace) {
                        // The diamond's X sits over A: TURBO A / B take the diamond's empty corners by A and by B.
                        val d = b.sc(DS_FACE)
                        val cx = w - e - dpadR
                        val (tx, ty) = if (turbo == TouchButton.TURBO_A) cx + d to dpadY - d else cx - d to dpadY + d
                        b.items += Item(turbo, tx, ty, d, d, Shape.ROUND)
                        column -= turbo
                        continue
                    }
                    val r = min(b.sc(TURBO) / 2, (faceCy - faceR - gap - rowBottom - gap) / 2)
                    if (r < b.px(TURBO * 0.35f)) continue
                    b.items += Item(turbo, faceCx, faceCy - faceR - gap - r, 2 * r, 2 * r, Shape.ROUND)
                    column -= turbo
                }
                // The rest in the column between the D-pad and A / B, under the top row, centred on the D-pad's height.
                val left = e + dpadR * 2 + gap
                val right = (if (diamondFace) w - e - 2 * dpadR else faceX - b.sc(FACE_DX) - faceR) - gap
                val half = max(dpadR, min(dpadY - rowBottom - gap, h - e - dpadY))
                flow(b, column, left, dpadY - half, right, dpadY + half)
            } else {
                // A row along the top, between L and R (CLASSIC's START / SELECT have the bottom's middle). A DS game held
                // sideways keeps them over its top screen: the touch screen's COMPANION switch is above that one.
                val top = e
                val rowEnd = if (dsGame && !portrait) w / 2 - b.px(GAP) else w - shouldersEnd
                flow(b, extras, shouldersEnd, top, rowEnd, top + b.sc(extrasRow))
            }
        }
        val items = b.items.filter { it.button in buttons }
        return preset to if (portrait) shiftTo(items, h, b.px(EDGE), style.position) else items
    }

    /** A DS game's A / B / X / Y around ([cx], [cy]) as on the DS: X top, A right, B bottom, Y left - the D-pad's size. */
    private fun diamond(b: Builder, cx: Float, cy: Float) {
        val d = b.sc(DS_FACE)
        val r = d
        for ((t, x, y) in listOf(
            Triple(TouchButton.X, cx, cy - r), Triple(TouchButton.A, cx + r, cy),
            Triple(TouchButton.B, cx, cy + r), Triple(TouchButton.Y, cx - r, cy),
        )) b.items += Item(t, x, y, d, d, Shape.ROUND)
    }

    /** [items] moved up or down together so their block sits at the top, middle or bottom of an [h]-tall area. */
    private fun shiftTo(items: List<Item>, h: Float, edge: Float, position: com.pokedaisy.app.companion.TouchPosition): List<Item> {
        if (items.isEmpty()) return items
        val top = items.minOf { it.top }
        val bottom = items.maxOf { it.bottom }
        val room = h - 2 * edge - (bottom - top)
        if (room <= 0f) return items
        val want = edge + when (position) {
            com.pokedaisy.app.companion.TouchPosition.TOP -> 0f
            com.pokedaisy.app.companion.TouchPosition.MIDDLE -> room / 2
            com.pokedaisy.app.companion.TouchPosition.BOTTOM -> room
        }
        val dy = want - top
        return items.map { it.copy(cy = it.cy + dy) }
    }

    /** SIDES: a column in each margin ([margin] px wide): L, extras, the D-pad, SELECT on the left; R, extras, A / B,
     * START on the right. */
    private fun sides(buttons: List<TouchButton>, w: Float, h: Float, dp: Float, s: Float, margin: Float): List<Item> {
        val b = Builder(w, h, dp, s)
        val e = b.px(EDGE)
        val lx = margin / 2
        val rx = w - margin / 2
        val has = { t: TouchButton -> t in buttons }
        val shoulderH = b.sc(SHOULDER_H)
        val pillH = b.sc(PILL_H)
        val top = if (has(TouchButton.L)) {
            b.at(TouchButton.L, lx, e + shoulderH / 2)
            b.at(TouchButton.R, rx, e + shoulderH / 2)
            e + shoulderH + b.px(GAP)
        } else e
        val bottomY = h - e - pillH / 2
        b.at(TouchButton.SELECT, lx, bottomY)
        b.at(TouchButton.START, rx, bottomY)
        val dpadR = b.sc(DPAD) / 2
        val clusterH = if (has(TouchButton.X)) b.sc(DPAD) else b.sc(FACE + 2 * FACE_DY)
        // The D-pad and A / B a little under the middle, where thumbs rest; above SELECT / START.
        val maxCy = h - e - pillH - b.px(GAP) - dpadR
        val cy = min(h * 0.6f, maxCy)
        b.at(TouchButton.DPAD, lx, cy)
        if (has(TouchButton.X)) diamond(b, rx, cy)
        else {
            b.at(TouchButton.A, rx + b.sc(FACE_DX), cy - b.sc(FACE_DY))
            b.at(TouchButton.B, rx - b.sc(FACE_DX), cy + b.sc(FACE_DY))
        }
        // Extras between the shoulder and the pad above it: the right hand's (FF, turbo) on the right.
        val extras = buttons.filter { it.extra }
        val right = extras.filter { it == TouchButton.FF || it == TouchButton.TURBO_A || it == TouchButton.TURBO_B }
        val left = extras - right.toSet()
        val gap = b.px(GAP)
        flow(b, left, gap, top, margin - gap, cy - dpadR - gap)
        flow(b, right, w - margin + gap, top, w - gap, cy - clusterH / 2 - gap)
        return b.items.filter { it.button in buttons }
    }

    /** [list] in rows inside the box, centred, shrunk together if they don't fit. */
    private fun flow(b: Builder, list: List<TouchButton>, left: Float, top: Float, right: Float, bottom: Float) {
        if (list.isEmpty()) return
        val boxW = max(right - left, 1f)
        val boxH = max(bottom - top, 1f)
        val gap = b.px(GAP) * 0.75f
        val sizes = list.map { b.size(it) }
        val cellW = sizes.maxOf { it.first }
        val cellH = sizes.maxOf { it.second }
        var k = 1f
        var cols: Int
        var rows: Int
        while (true) {
            cols = max(1, floor((boxW + gap) / (cellW * k + gap)).toInt()).coerceAtMost(list.size)
            rows = ceil(list.size / cols.toFloat()).toInt()
            // Down to a third: every extra on with X / Y, SIZE X-LARGE, COMPACT upright wraps them into two short rows.
            if ((rows * (cellH * k + gap) - gap <= boxH && cellW * k <= boxW) || k <= 0.35f) break
            k -= 0.05f
        }
        val usedH = rows * (cellH * k + gap) - gap
        list.forEachIndexed { i, t ->
            val row = i / cols
            val inRow = min(cols, list.size - row * cols)
            val col = i % cols
            val rowW = inRow * (cellW * k + gap) - gap
            val x = left + (boxW - rowW) / 2 + col * (cellW * k + gap) + cellW * k / 2
            val y = top + (boxH - usedH) / 2 + row * (cellH * k + gap) + cellH * k / 2
            val (iw, ih) = sizes[i]
            b.items += Item(t, x, y, iw * k, ih * k, b.shape(t))
        }
    }

    // ---- touches --------------------------------------------------------------------------------------------------

    /** The D-pad's directions for a touch at ([dx], [dy]) from its centre, [r] its radius; 0 = none. */
    fun dpadBits(dx: Float, dy: Float, r: Float, eightWay: Boolean): Int {
        val d = hypot(dx, dy)
        if (d > r * 1.4f || d < r * 0.18f) return 0
        val dead = r * 0.2f
        if (!eightWay) {
            return if (abs(dx) > abs(dy)) (if (dx < 0) KEY_LEFT else KEY_RIGHT) else (if (dy < 0) KEY_UP else KEY_DOWN)
        }
        // 8-way: a diagonal within ~30° of the 45° line (the arms' own axis wins near it).
        var bits = 0
        val ax = abs(dx); val ay = abs(dy)
        if (ay > dead && ay > ax * 0.42f) bits = bits or if (dy < 0) KEY_UP else KEY_DOWN
        if (ax > dead && ax > ay * 0.42f) bits = bits or if (dx < 0) KEY_LEFT else KEY_RIGHT
        return bits
    }

    /**
     * Every control a touch at ([x], [y]) lands on. Touch areas reach past the drawn button - a round one by a quarter
     * of its radius, the rest by [slop] px - so a thumb a little off still presses it; between A and B, both.
     */
    fun hits(placed: Placed, x: Float, y: Float, slop: Float): List<Item> = placed.items.filter { it.contains(x, y, slop) }

    /**
     * The FIELD HUD's room to keep clear of the controls over the columns [[l], [r]) (px): down to the bottom of the
     * top row, up from the bottom to the top of the bottom row. A row is the controls along that edge, within
     * [HUD_ROW_GAP] of it (every preset's L / R, extras, D-pad, A / B and SELECT / START). A control a CUSTOM layout or
     * RAISED puts further in (FF or the D-pad halfway up the side, GitHub #493) is left alone: keeping clear of it
     * squeezed the HUD into a strip and lost its minimap.
     */
    fun hudClear(placed: Placed, l: Float, r: Float): Pair<Int, Int> {
        val over = placed.items.filter { it.right > l && it.left < r }
        val h = placed.h
        val near = HUD_ROW_GAP * placed.dp
        val top = over.filter { it.cy < h / 2 && it.top <= near }.maxOfOrNull { it.bottom } ?: 0f
        val bottom = over.filter { it.cy >= h / 2 && h - it.bottom <= near }.minOfOrNull { it.top }?.let { h - it } ?: 0f
        return top.toInt().coerceAtLeast(0) to bottom.toInt().coerceAtLeast(0)
    }

    /** How far from the top or bottom edge (dp) a control still counts as that edge's row for the HUD ([hudClear]):
     * past every preset's (CLASSIC's A at HUGE, 14 + 2 x 12 x 1.4), short of a button moved up the side. */
    const val HUD_ROW_GAP = 64f

    fun Item.contains(x: Float, y: Float, slop: Float): Boolean = when (shape) {
        Shape.CROSS -> hypot(x - cx, y - cy) <= w / 2 * 1.4f
        Shape.ROUND -> hypot(x - cx, y - cy) <= w / 2 * 1.25f + slop * 0.25f
        Shape.PILL -> x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
    }

    // mGBA's key bits (MgbaCore.Key), here so the math has no Android in it.
    const val KEY_RIGHT = 1 shl 4
    const val KEY_LEFT = 1 shl 5
    const val KEY_UP = 1 shl 6
    const val KEY_DOWN = 1 shl 7
}
