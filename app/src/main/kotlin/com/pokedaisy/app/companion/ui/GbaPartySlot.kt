package com.pokedaisy.app.companion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_FEMALE
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_MALE
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.shownNickname
import com.pokedaisy.app.companion.ui.PartySlotLayout as L
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The game's own party slot - FireRed's / Emerald's MAIN slot (the big left
 * box of their party menus), or the CFRU hacks' shared 112x40 slot - drawn at
 * the GBA's native pixel grid from assets generated out of each game's own data
 * (scripts/gen_party_assets.py, scripts/gen_cfru_party_assets.py) and scaled up
 * by a whole number with nearest-neighbour filtering. Frame, text, colours and
 * HP bar were checked pixel-for-pixel against real screenshots of every game
 * (mgba_dump `shot`); per-game numbers live in the generated [PartySlotStyle]s.
 *
 * The gender symbol needs a native read that knows the game's gender
 * ratios - otherwise it's reported unknown and just left off.
 */
@Composable
fun GbaPartySlot(
    style: PartySlotStyle, mon: MonView?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    /** False for a tap the game sounds itself (see [PartyGrid]). */
    sound: Boolean = true,
) {
    val st = style
    val assets = rememberPartySlotAssets(st) ?: return
    val sheet = rememberMonIconSheet(mon?.iconAsset)
    val sheetImg = remember(sheet) { sheet?.asImageBitmap() }
    // The icon's two-frame animation, at the game's HP-based speed.
    val ticks = mon?.let { iconFrameTicks(st, it) } ?: 0
    val iconPhase by rememberBlink(gbaFramesMs(ticks), enabled = ticks > 0 && CompanionTweaks[CompanionTweaks.Tweak.PARTY_ICONS_MOVE], label = "party-icon")
    val phase = if (iconPhase) 1 else 0

    Canvas(modifier = modifier.slotClickable(sound, enabled = mon != null, onClick = onClick)) {
        // Region drawn: the slot window plus the sprites that overhang it
        // up/left (FireRed's Poke Ball at -8,-6). Pixels stay square at an
        // integer scale; whatever width (and, where the frame allows, height)
        // is left over grows the slot itself (see Px.slot) instead of leaving
        // the card letterboxed in its cell.
        val regionX0 = st.regionX0
        val regionY0 = st.regionY0
        // Sized from the box's VISIBLE extent: the window's last columns and
        // rows can be transparent in every state.
        val minRegionH = st.visibleH - regionY0
        val minRegionW = st.visibleW - regionX0
        val scale = max(1, floor(min(size.width / minRegionW, size.height / minRegionH)).toInt())
        val regionW = (size.width / scale).toInt()
        val regionH = if (st.stretchRows) (size.height / scale).toInt() else minRegionH
        val extraW = max(0, regionW - minRegionW)
        // A band frame's empty slot grows by whole stripe pairs - keep it even.
        val extraH = max(0, regionH - minRegionH).let { if (st.band != null) it and 1.inv() else it }
        val ox = ((size.width - regionW * scale) / 2).toInt() - regionX0 * scale
        val oy = ((size.height - regionH * scale) / 2).toInt() - regionY0 * scale
        val px = Px(
            this, st, assets, ox, oy, scale,
            stretchLeft = extraW / 3, stretchMid = extraW - extraW / 3,
            // Split the extra height between the top padding, the gap between
            // level and HP box, and the bottom padding - roughly even spacing.
            stretchTop = extraH / 3, stretchMidV = (extraH - extraH / 3) / 2,
            stretchBottom = extraH - extraH / 3 - (extraH - extraH / 3) / 2,
        )

        if (mon == null) {
            if (st.hasEmptySlot) px.window(assets.slotEmpty!!) {}
            else px.slot(assets.slotNoHp, alpha = 0.35f)
            return@Canvas
        }
        if (mon.isEgg) {
            // DisplayPartyPokemonData's egg branch: the no-HP box + nickname only.
            px.window(if (selected) assets.slotNoHpSelected else assets.slotNoHp) {
                px.text(assets, st.eggName, px.left(st.nameX), px.textY(st.nameY), st.text, st.textShadow)
            }
            px.ball(assets, selected)
            sheetImg?.let { px.monIcon(it, mon, selected, phase) }
            px.heldItem(assets, mon)
            return@Canvas
        }
        val fainted = mon.hp == 0
        val slot = when {
            selected && fainted -> assets.slotSelectedFainted
            selected -> assets.slotSelected
            fainted -> assets.slotFainted
            else -> assets.slotNormal
        }
        val statusIdx = statusIconIndex(mon)
        px.window(slot) {
            // Name: the nickname exactly as the game stores it, else the species name (FireRed/Emerald print those in caps).
            val name = mon.shownNickname ?: if (st.upperCaseNames) mon.name.uppercase() else mon.name
            px.text(assets, name, px.left(st.nameX), px.textY(st.nameY), st.text, st.textShadow)
            // Gender symbol (DisplayPartyPokemonGender) - drawn whenever known,
            // even alongside a status icon, like the game.
            when (mon.genderSymbol) {
                GENDER_SYMBOL_MALE -> px.text(assets, "♂", px.right(st.genderX), px.textY(st.genderY), st.male, st.maleShadow)
                GENDER_SYMBOL_FEMALE -> px.text(assets, "♀", px.right(st.genderX), px.textY(st.genderY), st.female, st.femaleShadow)
            }
            // Level, unless a status icon takes its place (the game hides "Lv" then).
            if (statusIdx < 0) {
                px.text(assets, "{LV}${mon.level}", px.left(st.levelX), px.textY(st.levelY), st.text, st.textShadow)
            }
            // "hp/" right-aligned in 3 digits + "/max" (DisplayPartyPokemonHP/MaxHP).
            // Both halves move with the (stretched) bar's right end - they share
            // the slash, so they must stay together.
            // (NUM_PAD: the game's right-align pad character, not a plain space.)
            px.text(assets, "%3d/".format(mon.hp).replace(' ', NUM_PAD), px.end(st.hpX), px.textY(st.hpY), st.text, st.textShadow)
            px.text(assets, "/%3d".format(mon.maxHp).replace(' ', NUM_PAD), px.end(st.maxHpX), px.textY(st.hpY), st.text, st.textShadow)
            px.hpBar(mon.hp, mon.maxHp)
        }
        if (statusIdx >= 0) {
            px.image(assets.statusIcons, statusIdx * 32, 0, px.left(st.statusX), px.y(st.statusY), 32, 8)
        }

        // Poke Ball (open while selected), then the icon above it, then the held-item marker over the icon.
        px.ball(assets, selected)
        sheetImg?.let { px.monIcon(it, mon, selected, phase) }
        px.heldItem(assets, mon)
    }
}


/** HP_BAR_FULL/GREEN/YELLOW/RED/EMPTY = 0..4 (battle_interface.c GetHPBarLevel). */
private fun hpBarLevel(hp: Int, maxHp: Int, barW: Int): Int {
    if (hp == maxHp) return 0
    val f = scaledHpFraction(hp, maxHp, barW)
    return when {
        f > barW * 50 / 100 -> 1
        f > barW * 20 / 100 -> 2
        f > 0 -> 3
        else -> 4
    }
}

/** GetScaledHPFraction: floor(hp*scale/max), but never 0 while alive. */
private fun scaledHpFraction(hp: Int, maxHp: Int, scale: Int): Int {
    if (maxHp <= 0) return 0
    val r = hp * scale / maxHp
    return if (r == 0 && hp > 0) 1 else r
}

/** Column in status_icons.png: PSN, PAR, SLP, FRZ, BRN, PKRS, FNT; -1 = none. */
private fun statusIconIndex(mon: MonView): Int = when {
    mon.hp == 0 -> 6
    else -> when (mon.status) {
        "PSN", "TOX" -> 0
        "PAR" -> 1
        "SLP" -> 2
        "FRZ" -> 3
        "BRN" -> 4
        else -> -1
    }
}

/**
 * Draws in GBA pixels: (x, y) are slot-window coordinates. A bigger slot is
 * made by repeating columns/rows the frame has uniform in every state
 * (asserted by the generators for every slot_*.png): PartySlotStyle.leftCol
 * and .midCol (so the HP bar grows with the card), and .topRow/.midRow/
 * .bottomRow for height where the frame has uniform rows at all.
 * [left]/[right]/[end]/[y] map a game coordinate past the stretch points.
 */
private class Px(
    val scope: DrawScope, val st: PartySlotStyle, val assets: PartySlotAssets, val ox: Int, val oy: Int, val scale: Int,
    val stretchLeft: Int = 0, val stretchMid: Int = 0,
    val stretchTop: Int = 0, val stretchMidV: Int = 0, val stretchBottom: Int = 0,
) {
    fun left(x: Int) = if (x > st.leftCol) x + stretchLeft else x
    fun right(x: Int) = left(x) + if (x > st.midCol) stretchMid else 0
    /** For things anchored to the HP bar's right end, wherever they start. */
    fun end(x: Int) = x + stretchLeft + stretchMid
    /** A game y-coordinate past any of the vertical stretch rows. */
    fun y(v: Int) = v + (if (v > st.topRow) stretchTop else 0) +
        (if (v > st.midRow) stretchMidV else 0) + (if (v > st.bottomRow) stretchBottom else 0)
    /** A text origin: moved by the stretch rows above where its ink starts. */
    fun textY(v: Int) = y(v + L.TEXT_TOP) - L.TEXT_TOP

    /** The icon's offset from [PartySlotStyle.iconX] / iconY: resting, or its bounce while selected. */
    private fun iconOffset(mon: MonView, selected: Boolean, phase: Int): Pair<Int, Int> {
        val ticks = iconFrameTicks(st, mon)
        val dx = if (selected) 0 else st.iconRestDx
        val dy = when {
            !selected -> st.iconRestDy
            ticks == 0 -> 0
            phase == 1 -> L.ICON_BOUNCE_UP
            else -> L.ICON_BOUNCE_DOWN
        }
        return dx to dy
    }

    /** The party menu's held-item marker (CreatePartyMonHeldItemSprite): at the icon's top-left + (16, 22) - sprite
     * coords (20, 50) beside the icon's (16, 40), 8x8 sprites centred - Mail's frame for Mail. It stays put while the
     * icon rests and bounces: the party menu's sprite has SpriteCallbackDummy (only the trade screen's copy follows
     * its icon, SpriteCB_HeldItem). */
    fun heldItem(assets: PartySlotAssets, mon: MonView) {
        val icons = assets.heldIcons ?: return
        if (mon.heldItemId == 0) return
        val mail = mon.heldItemId in com.pokedaisy.app.companion.data.MAIL_ITEMS
        image(icons, 0, if (mail) 8 else 0, st.iconX + 16, st.iconY + 22 + stretchTop, 8, 8)
    }

    /** The party icon, animated like the game (see PartySlotLayout.ICON_*). */
    fun monIcon(icon: ImageBitmap, mon: MonView, selected: Boolean, phase: Int) {
        val (dx, dy) = iconOffset(mon, selected, phase)
        val frameH = icon.width // square frames stacked vertically
        val srcY = if (icon.height >= frameH * 2) phase * frameH else 0
        // Moves down with the name (top stretch); the ball stays pinned to
        // the frame's top-left corner.
        image(icon, 0, srcY, st.iconX + dx, st.iconY + dy + stretchTop, 32, 32)
    }

    fun ball(assets: PartySlotAssets, selected: Boolean) {
        val dy = if (st.ballFollowsIcon) stretchTop else 0
        assets.pokeball?.let { image(it, 0, if (selected) 32 else 0, st.ballX, st.ballY + dy, 32, 32) }
    }

    /**
     * The slot window: [frame] plus whatever [content] draws into it (text, HP
     * bar). Where the game alpha-blends the window over its backdrop
     * (PartySlotStyle.blendOverBackdrop: box + backdrop / 2), the backdrop
     * already drawn under this Canvas is halved inside the box first, then the
     * window is added on top of it.
     */
    fun window(frame: ImageBitmap, content: () -> Unit) {
        if (!st.blendOverBackdrop) {
            slot(frame)
            content()
            return
        }
        slot(frame, alpha = 0.5f, colorFilter = ColorFilter.tint(Color.Black))
        val canvas = scope.drawContext.canvas
        canvas.saveLayer(Rect(Offset.Zero, scope.size), Paint().apply { blendMode = BlendMode.Plus })
        slot(frame)
        content()
        canvas.restore()
    }

    /** One axis of the frame: (srcStart, srcLen, dstLen) - a 1px source repeated when dstLen > srcLen. */
    private fun segments(size: Int, cuts: List<Pair<Int, Int>>): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        var pos = 0
        for ((at, n) in cuts) {
            if (at + 1 > pos) out.add(Triple(pos, at + 1 - pos, at + 1 - pos))
            if (n > 0) out.add(Triple(at, 1, n))
            pos = max(pos, at + 1)
        }
        out.add(Triple(pos, size - pos, size - pos))
        return out
    }

    /** The frame, grown by repeating its uniform columns/rows (see PartySlotStyle's stretch lines). */
    fun slot(img: ImageBitmap, alpha: Float = 1f, colorFilter: ColorFilter? = null) {
        if (st.band != null) {
            // Rebuilt at this size once (diagonals redrawn), then drawn whole.
            val built = assets.bandFrame(st, img, stretchLeft, stretchMid, stretchTop, stretchMidV, stretchBottom)
            scope.drawImage(
                built, dstOffset = IntOffset(ox, oy), dstSize = IntSize(built.width * scale, built.height * scale),
                alpha = alpha, colorFilter = colorFilter, filterQuality = FilterQuality.None,
            )
            return
        }
        val cols = segments(st.slotW, listOf(st.leftCol to stretchLeft, st.midCol to stretchMid))
        val rows = segments(st.slotH, listOf(st.topRow to stretchTop, st.midRow to stretchMidV, st.bottomRow to stretchBottom))
        var dy = 0
        for ((sy, sh, dh) in rows) {
            var dx = 0
            for ((sx, sw, dw) in cols) {
                scope.drawImage(
                    img,
                    srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh),
                    dstOffset = IntOffset(ox + dx * scale, oy + dy * scale), dstSize = IntSize(dw * scale, dh * scale),
                    alpha = alpha, colorFilter = colorFilter, filterQuality = FilterQuality.None,
                )
                dx += dw
            }
            dy += dh
        }
    }

    fun image(img: ImageBitmap, sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int, alpha: Float = 1f) {
        scope.drawImage(
            img,
            srcOffset = IntOffset(sx, sy), srcSize = IntSize(w, h),
            dstOffset = IntOffset(ox + x * scale, oy + y * scale), dstSize = IntSize(w * scale, h * scale),
            alpha = alpha, filterQuality = FilterQuality.None,
        )
    }

    fun rect(color: Long, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0) return
        scope.drawRect(
            Color(color),
            topLeft = androidx.compose.ui.geometry.Offset((ox + x * scale).toFloat(), (oy + y * scale).toFloat()),
            size = androidx.compose.ui.geometry.Size((w * scale).toFloat(), (h * scale).toFloat()),
        )
    }

    /**
     * DisplayPartyPokemonHPBar: 1px highlight row + 2px body, empty remainder
     * in window colours. Colour thresholds use the game's own 48px scale;
     * the fill spans the (possibly stretched) bar.
     */
    fun hpBar(hp: Int, maxHp: Int) {
        val (top, body) = when (hpBarLevel(hp, maxHp, st.barW)) {
            0, 1 -> st.hpGreenTop to st.hpGreen
            2 -> st.hpYellowTop to st.hpYellow
            else -> st.hpRedTop to st.hpRed
        }
        val x = left(st.barX)
        val by = y(st.barY)
        val w = right(st.barX + st.barW - 1) - x + 1
        val f = scaledHpFraction(hp, maxHp, w)
        rect(top, x, by, f, 1)
        rect(body, x, by + 1, f, 2)
        rect(st.hpEmptyTop, x + f, by, w - f, 1)
        rect(st.hpEmpty, x + f, by + 1, w - f, 2)
    }

    fun text(assets: PartySlotAssets, s: String, x0: Int, y: Int, fg: Long, shadow: Long) {
        val atlas = assets.font(fg, shadow)
        var x = x0
        for (g in encodeSmallFont(st, s)) {
            image(atlas, (g % 32) * 8, (g / 32) * 16, x, y, 8, L.GLYPH_H)
            x += st.glyphWidths[g]
        }
    }
}

/** Stands for the game's right-align pad character in number strings. */
private const val NUM_PAD = '\u2007'

/** The rest of charmap.txt's Western letters - the European Emeralds' names (KÜKEN, VILLA RAÍZ). */
private val WESTERN_CODES: Map<Char, Int> = HashMap<Char, Int>().apply {
    // From 0x01, as Gen3Text has them (É / é are mapped above).
    "ÀÁÂÇÈÉÊËÌ ÎÏÒÓÔŒÙÚÛÑßàá çèéêëì îïòóôœùúûñºª".forEachIndexed { i, c -> if (c != ' ' && c != 'É' && c != 'é') put(c, 0x01 + i) }
    "ÄÖÜäöü".forEachIndexed { i, c -> put(c, 0xF1 + i) }
    put('&', 0x2D); put('+', 0x2E); put('¿', 0x51); put('¡', 0x52); put('Í', 0x5A); put('%', 0x5B)
    put('(', 0x5C); put(')', 0x5D); put('â', 0x68); put('í', 0x6F); put(',', 0xB8)
}

/** Gen 3 character codes for FONT_SMALL; "{LV}" = the game's level glyph. */
private fun encodeSmallFont(st: PartySlotStyle, s: String): List<Int> {
    val out = ArrayList<Int>(s.length)
    var i = 0
    while (i < s.length) {
        if (s.startsWith("{LV}", i)) { out.add(st.lvGlyph); i += 4; continue }
        val c = s[i]
        val code = when (c) {
            NUM_PAD -> st.padGlyph
            in 'A'..'Z' -> 0xBB + (c - 'A')
            in 'a'..'z' -> 0xD5 + (c - 'a')
            in '0'..'9' -> 0xA1 + (c - '0')
            ' ' -> 0x00
            '/' -> 0xBA
            '.' -> 0xAD
            '-' -> 0xAE
            '!' -> 0xAB
            '?' -> 0xAC
            '\'', '’' -> 0xB4
            ':' -> 0xF0
            'é' -> 0x1B
            'É' -> 0x1B
            '♂' -> 0xB5
            '♀' -> 0xB6
            else -> WESTERN_CODES[c] ?: 0xAC // '?' for anything the font doesn't have
        }
        out.add(code)
        i++
    }
    return out
}

class PartySlotAssets(
    val slotNormal: ImageBitmap,
    val slotSelected: ImageBitmap,
    val slotFainted: ImageBitmap,
    val slotSelectedFainted: ImageBitmap,
    val slotNoHp: ImageBitmap,
    val slotNoHpSelected: ImageBitmap,
    val slotEmpty: ImageBitmap?,
    val pokeball: ImageBitmap?,
    val statusIcons: ImageBitmap,
    /** The held-item marker (RomArt.HOLD_ICONS); null until a ROM has supplied it. */
    val heldIcons: ImageBitmap?,
    private val fontMask: Bitmap,
) {
    private val tinted = HashMap<Pair<Long, Long>, ImageBitmap>()
    private val bandFrames = HashMap<List<Any>, ImageBitmap>()

    /** [frame] at a stretched size (PartySlotStyle.band styles), built once per size. */
    fun bandFrame(st: PartySlotStyle, frame: ImageBitmap, sL: Int, sM: Int, kT: Int, kM: Int, kB: Int): ImageBitmap {
        val key = listOf(frame, sL, sM, kT, kM, kB)
        bandFrames[key]?.let { return it }
        if (bandFrames.size > 32) bandFrames.clear() // sizes only change with the layout
        return buildBandFrame(frame.asAndroidBitmap(), st, st.band!!, frame === slotEmpty, sL, sM, kT, kM, kB)
            .asImageBitmap().also { bandFrames[key] = it }
    }

    /** font_small.png marks text pixels red and shadow pixels blue; recolour once per pair. */
    fun font(fg: Long, shadow: Long): ImageBitmap = tinted.getOrPut(fg to shadow) {
        val w = fontMask.width
        val h = fontMask.height
        val px = IntArray(w * h)
        fontMask.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            if ((p ushr 24) == 0) continue
            px[i] = if ((p shr 16 and 0xFF) > 0x80) fg.toInt() else shadow.toInt()
        }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

/**
 * Grows a [DiagonalBand] frame: columns repeated like Px.slot does, copies of
 * the plain stretch rows inserted (whole stripe/blank pairs for the empty
 * slot), then every fill pixel recoloured by the band rule with the diagonals
 * redrawn as straight lines between their original end points.
 */
private fun buildBandFrame(
    src: Bitmap, st: PartySlotStyle, band: DiagonalBand, empty: Boolean, sL: Int, sM: Int, kT: Int, kM: Int, kB: Int,
): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val cols = ArrayList<Int>()
    for (x in 0 until w) {
        cols.add(x)
        if (x == st.leftCol) repeat(sL) { cols.add(x) }
        if (x == st.midCol) repeat(sM) { cols.add(x) }
    }
    val k = kT + kM + kB
    val rows = ArrayList<Int>()
    for (y in 0 until h) {
        rows.add(y)
        if (empty) {
            if (y == band.emptyStripeRow + 1) repeat(k / 2) { rows.add(band.emptyStripeRow); rows.add(band.emptyStripeRow + 1) }
        } else {
            if (y == st.topRow) repeat(kT) { rows.add(y) }
            if (y == st.midRow) repeat(kM) { rows.add(y) }
            if (y == st.bottomRow) repeat(kB) { rows.add(y) }
        }
    }
    val ow = cols.size
    val oh = rows.size
    val out = IntArray(ow * oh)
    for (j in 0 until oh) for (i in 0 until ow) out[j * ow + i] = px[rows[j] * w + cols[i]]
    if (!empty) {
        fun at(x: Int, y: Int) = px[y * w + x]
        val light = at(band.leftTopX - 1, band.top)
        val edge = at(band.leftTopX, band.top)
        val dark = at(band.leftTopX + 1, band.top)
        fun mapX(x: Int) = x + (if (x > st.leftCol) sL else 0) + (if (x > st.midCol) sM else 0)
        val lt = mapX(band.leftTopX).toDouble()
        val lb = mapX(band.leftBottomX).toDouble()
        val rt = mapX(band.rightTopX).toDouble()
        val rb = mapX(band.rightBottomX).toDouble()
        val span = (band.bottom + k - band.top).toDouble()
        for (j in band.top..band.bottom + k) {
            val t = (j - band.top) / span
            val xl = floor(lt + (lb - lt) * t + 0.5).toInt()
            val xr = floor(rt + (rb - rt) * t + 0.5).toInt()
            for (i in 0 until ow) {
                val p = out[j * ow + i]
                if (p != light && p != edge && p != dark) continue
                out[j * ow + i] = when {
                    i == xl || i == xr -> edge
                    i < xl || i > xr -> light
                    else -> dark
                }
            }
        }
    }
    return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
}

/** The slot's art, or null while no ROM has supplied it (FireRed / Emerald: see RomArt). */
@Composable
internal fun rememberPartySlotAssets(st: PartySlotStyle): PartySlotAssets? {
    val context = LocalContext.current
    val gen = rememberArtGeneration()
    return remember(context, st, gen) {
        // Shared by every slot and every visit: the grid and its 6 slots used to build 7
        // sets, each recolouring the 256x256 font again (MBs per PARTY visit).
        slotAssetsCache[st to gen]?.let { return@remember it }
        runCatching {
            fun load(path: String): Bitmap = GameArt.get(context, path) ?: error("no $path")
            fun frame(name: String) = load("${st.frameDir}/$name").asImageBitmap()
            PartySlotAssets(
                slotNormal = frame("slot_normal.png"),
                slotSelected = frame("slot_selected.png"),
                slotFainted = frame("slot_fainted.png"),
                slotSelectedFainted = frame("slot_selected_fainted.png"),
                slotNoHp = frame("slot_nohp_normal.png"),
                slotNoHpSelected = frame("slot_nohp_selected.png"),
                slotEmpty = if (st.hasEmptySlot) frame("slot_empty.png") else null,
                pokeball = st.pokeballAsset?.let { load(it).asImageBitmap() },
                statusIcons = load(st.statusAsset).asImageBitmap(),
                heldIcons = GameArt.get(context, com.pokedaisy.app.companion.data.RomArt.HOLD_ICONS)?.asImageBitmap(),
                fontMask = load(st.fontAsset),
            )
        }.getOrNull()?.also {
            // A new art generation replaces the old art: drop what it was built from.
            slotAssetsCache.keys.removeAll { (_, g) -> g != gen }
            slotAssetsCache[st to gen] = it
        }
    }
}

/** [rememberPartySlotAssets]' sets by style and art generation (main thread only, like the drawing). */
private val slotAssetsCache = HashMap<Pair<PartySlotStyle, Int>, PartySlotAssets>()

/** Game frames per pose of [mon]'s party icon (by its HP bar), 0 = still. */
private fun iconFrameTicks(st: PartySlotStyle, mon: MonView): Int {
    val level = if (mon.isEgg) 0 else hpBarLevel(mon.hp, mon.maxHp, st.barW)
    return if (level >= 4) 0 else L.ICON_FRAME_TICKS[level]
}
