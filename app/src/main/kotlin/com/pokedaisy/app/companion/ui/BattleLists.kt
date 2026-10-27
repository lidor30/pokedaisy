package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_FEMALE
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_MALE
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.AppColors
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * BATTLE BUTTONS' FIGHT / POKéMON windows as lists down the screen's right side (the user's call, after a modern game's
 * move list): no window behind them, just the rows over the dimmed game. Moves keep the move cards' look, one card a row;
 * the party is drawn like FireRed's party menu list (its right column: the Poké Ball and icon, name and level, the HP bar
 * and numbers), in the running game's party colours ([partyPaletteFor]).
 */

/** FIGHT's moves, one card a row, then CANCEL: [width] wide, each row [rowH] tall. Gen 1 keeps its own list. */
@Composable
internal fun BattleMoveList(
    moves: List<MoveView>, battleInput: BattleInput?, busy: Boolean, showHints: Boolean, width: Dp, rowH: Dp, gap: Dp,
    /** How far the rows run past the screen's right edge (their content keeps clear of it). */
    edge: Dp,
    onCancel: () -> Unit,
    /** With no [battleInput] (the co-op partner's menu): a tap on row i calls this; [selected] is the cursor row. */
    onPick: ((Int) -> Unit)? = null,
    selected: Int = -1,
) {
    if (gen1Buttons && onPick == null) {
        Box(Modifier.width(width).height(rowH * 5 + gap * 4)) {
            MoveGrid(moves, battleInput, busy, showHints, Modifier.fillMaxSize(), onCancel = onCancel, compact = true)
        }
        return
    }
    CompanionBackHandler(enabled = (battleInput != null || onPick != null) && !busy, onBack = onCancel)
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(gap)) {
        for (i in 0 until 4) {
            MoveListRow(moves.getOrNull(i), i, battleInput, busy, showHints, edge, Modifier.focusStart(i == 0).fillMaxWidth().height(rowH), onPick, i == selected)
        }
        ListCancel((battleInput != null || onPick != null) && !busy, rowH * 0.7f, edge, onCancel)
    }
}

/**
 * A move as one row, in the move cards' look (their pale card in the move's type colour): its name and PP on top, its
 * type and power under them, and with BATTLE HINTS on what it does to the foe (SUPER 2x ...) bottom right. Sized in the
 * HUD's GBA pixels, so it follows SIZE like the rest of the HUD.
 */
@Composable
private fun MoveListRow(
    mv: MoveView?, index: Int, battleInput: BattleInput?, busy: Boolean, showHints: Boolean, edge: Dp, modifier: Modifier,
    onPick: ((Int) -> Unit)? = null, cursor: Boolean = false,
) {
    val m = rememberGbaTextMetrics(1f)
    val small = rememberGbaTextMetrics(0.75f)
    val u = m.u
    val enabled = mv != null && mv.pp > 0 && (battleInput != null || onPick != null) && !busy
    PlatinumButton(
        fill = if (mv == null) AppColors.slotFillDark else MoveCardFill,
        // The callback's cursor row: a gold frame (the party list's cursor is its selected colours).
        frame = if (cursor && mv != null) SuggestGold else mv?.let { AppColors.typeColors[it.type] } ?: AppColors.slotFrame,
        modifier = modifier, enabled = enabled, pressesGame = onPick == null, outline = u,
        // Clear of the bevel's side bands.
        contentPadding = PaddingValues(start = u * 9, end = u * 4 + edge, top = u * 2, bottom = u * 2),
        onClick = { if (onPick != null) onPick(index) else battleInput?.selectMove(index) },
    ) {
        if (mv == null) {
            GbaText("—", AppColors.muted, Color.Transparent, m)
            return@PlatinumButton
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GbaText(mv.name, AppColors.text, MoveTextShadow, m, Modifier.weight(1f), bold = true)
                GbaText(tr("PP {0}", mv.pp), AppColors.text, MoveTextShadow, small)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                ListPill(com.pokedaisy.app.companion.data.typeLabel(mv.type), AppColors.typeColors[mv.type] ?: AppColors.muted, small)
                GbaText(tr("PWR {0}", powerLabel(mv.power)), AppColors.muted, Color.Transparent, small, Modifier.weight(1f))
                val v = mv.vs.firstOrNull()
                if (showHints && v != null) {
                    val words = when {
                        v.pct == 0 -> tr("IMMUNE {0}", v.label)
                        v.pct > 100 -> tr("SUPER {0}", v.label)
                        v.pct < 100 -> tr("RESISTED {0}", v.label)
                        else -> tr("NEUTRAL {0}", v.label)
                    }
                    ListPill(words, verdictColor(v.pct), small)
                }
            }
        }
    }
}

/** A small white-on-[color] tag in the HUD's pixel units (a type, a verdict). */
@Composable
private fun ListPill(text: String, color: Color, m: GbaTextMetrics) {
    Box(Modifier.background(color, PixelRoundedShape(m.u * 2)).padding(horizontal = m.u * 2)) {
        GbaText(text, Color.White, Color.Transparent, m)
    }
}

/**
 * POKéMON's party, one row each like FireRed's party list, then CANCEL. The game's cursor (the selected colours) is on
 * SUGGESTIONS' pick with BATTLE HINTS on, tagged BEST, else on the one out, tagged OUT; a tap sends one in
 * ([BattleInput.switchTo]) - not a fainted one, an egg or the one already out.
 */
@Composable
internal fun BattlePartyList(
    party: List<MonView>, foe: MonView?, active: List<MonView>, battleInput: BattleInput?, busy: Boolean, showHints: Boolean,
    width: Dp, rowH: Dp, gap: Dp, u: Dp, edge: Dp, onBack: () -> Unit,
    /** With no [battleInput] (the co-op partner's menu): a tap on row i calls this; [selected] is the cursor row and
     * [pickable] says which rows may be picked (default: not fainted, not an egg). */
    onPick: ((Int) -> Unit)? = null,
    selected: Int = -1,
    pickable: (Int) -> Boolean = { i -> party[i].let { !it.isEgg && it.hp > 0 } },
) {
    CompanionBackHandler(enabled = !busy, onBack = onBack)
    val callback = onPick != null
    val best = remember(party, foe, active) { recommendedSwitch(party, foe, active) }?.takeIf { showHints && !callback }
    val cursor = if (callback) selected
        else party.indexOfFirst { it == best }.takeIf { it >= 0 } ?: party.indexOfFirst { isOut(it, active) }
    val enabled = (battleInput != null || callback) && !busy
    val palette = partyPaletteFor(activeGame ?: com.pokedaisy.app.companion.data.GameKind.FIRERED)
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(gap)) {
        party.forEachIndexed { i, mon ->
            val tag = when {
                mon == best -> tr("BEST") to SuggestGold
                isOut(mon, active) -> tr("OUT") to VerdictGrey
                else -> null
            }
            val pick = enabled && (if (callback) pickable(i) else canSendIn(mon, active))
            Box(
                Modifier.focusStart(i == cursor).fillMaxWidth().height(rowH)
                    .slotClickable(sound = callback, enabled = pick) { if (onPick != null) onPick(i) else battleInput?.switchTo(mon.personality) },
            ) { PartyListRow(palette, mon, selected = i == cursor, u, edge, tag) }
        }
        ListCancel(!busy, rowH * 0.7f, edge, onBack)
    }
}

@Composable
private fun ListCancel(enabled: Boolean, h: Dp, edge: Dp, onClick: () -> Unit) {
    val m = rememberGbaTextMetrics(1f)
    PlatinumButton(
        fill = RunBlue, frame = RunBlueDark, modifier = Modifier.fillMaxWidth().height(h),
        enabled = enabled, pressesGame = false, outline = m.u, contentPadding = PaddingValues(end = edge), onClick = onClick,
    ) { GbaText(tr("CANCEL"), buttonContent, if (buttonContent == Color.White) Color(0xFF383838) else Color.Transparent, m, bold = gameBoldLabels()) }
}

/**
 * One party member as FireRed's party list draws it (a wide slot: the Poké Ball and icon on the left, the name over
 * Lv / the status and the gender, the HP bar over its numbers on the right), in [palette]'s normal / selected / fainted
 * colours. [u]: a slot pixel (the HUD's GBA pixel), so the row is ~26 of them tall whatever its width.
 */
@Composable
private fun PartyListRow(palette: PartyPalette, mon: MonView, selected: Boolean, u: Dp, edge: Dp, tag: Pair<String, Color>?) {
    val density = LocalDensity.current
    val px = with(density) { u.toPx() }.coerceAtLeast(1f)
    val edgePx = with(density) { edge.toPx() }
    val colors = when {
        mon.isEgg -> if (selected) palette.selected else palette.normal
        mon.hp == 0 -> if (selected) palette.selectedFainted else palette.fainted
        selected -> palette.selected
        else -> palette.normal
    }
    val text = colors.text ?: palette.text
    val shadow = colors.textShadow ?: palette.textShadow
    val showHp = !mon.isEgg
    val m = rememberGbaTextMetrics(1f)
    val small = rememberGbaTextMetrics(0.75f)
    // The icon's two poses at the game's HP-based pace, and the selected one's bob (SpriteCB_BouncePartyMonIcon).
    // From an already-decoded sheet where there is one (no blank first frame), then the loaded one.
    val sheet = rememberMonIconSheet(mon.iconAsset) ?: remember(mon.iconAsset) { peekMonIcon(mon.iconAsset) }
    val icon = remember(sheet) { sheet?.asImageBitmap() }
    val ticks = if (mon.isEgg || mon.hp <= 0) 0 else PartySlotLayout.ICON_FRAME_TICKS[listHpLevel(mon.hp, mon.maxHp)]
    val moving = CompanionTweaks[CompanionTweaks.Tweak.PARTY_ICONS_MOVE]
    val pose by rememberBlink(gbaFramesMs(ticks.coerceAtLeast(1)), enabled = ticks > 0 && moving, label = "list-icon")
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowHeightPx = constraints.maxHeight.toFloat()
        // The Poké Ball as tall as the row, poking out of the slot's left edge (the game's party list); the icon at the
        // same pixel size over it, whole screen pixels per sprite pixel.
        val cell = floor(rowHeightPx / 20f).coerceAtLeast(1f)
        val ballW = 20 * cell
        val frameX = floor(ballW * 0.4f / px) * px
        val iconCell = cell
        Canvas(Modifier.fillMaxSize()) {
            drawListSlot(colors, px, frameX)
            drawPixelBitmap(PartyBall, palette.ball, 0f, floor((size.height - ballW) / 2), cell)
            if (showHp) drawListHpBar(palette, mon.hp, mon.maxHp, px, edgePx, hpBarTop(size.height, px))
            icon?.let { img ->
                val frameH = img.width
                val srcY = if (pose && img.height >= frameH * 2) frameH else 0
                val bob = when {
                    !selected || ticks == 0 || !moving -> 0
                    pose -> PartySlotLayout.ICON_BOUNCE_UP
                    else -> PartySlotLayout.ICON_BOUNCE_DOWN
                }
                val side = (32 * iconCell).toInt()
                val x = (ballW / 2 - side / 2f).toInt()
                // Its feet near the row's bottom, the head out over the top like the game's.
                val y = (size.height - side + 2 * iconCell + bob * iconCell).toInt()
                drawImage(
                    img, srcOffset = IntOffset(0, srcY), srcSize = androidx.compose.ui.unit.IntSize(frameH, frameH),
                    dstOffset = IntOffset(x, y), dstSize = androidx.compose.ui.unit.IntSize(side, side),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                )
            }
        }
        val textStart = with(density) { (ballW + 3 * px).toDp() }
        Column(Modifier.padding(start = textStart, top = u * 3).fillMaxWidth(0.5f), verticalArrangement = Arrangement.spacedBy(u)) {
            GbaText(mon.shownName, text, shadow, m)
            // Lv (or the status) and then BEST / OUT: the name's line can be full, the HP bar has the right side.
            Row(Modifier.padding(start = u * 6), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                if (showHp) {
                    val status = if (mon.hp == 0) "FNT" else mon.status
                    if (status.isNotEmpty()) StatusChip(status, px.roundToInt(), slotTextMetrics(density, (px * 0.6f).roundToInt().coerceAtLeast(1)), Modifier)
                    else GbaText(tr("Lv{0}", mon.level), text, shadow, m)
                }
                // The status chip's size, beside it.
                tag?.let { (t, c) -> ListPill(t, c, slotTextMetrics(density, (px * 0.75f).roundToInt().coerceAtLeast(1))) }
                // The gender, last on the line (the game puts it by the level).
                val gender = if (!showHp) null else when (mon.genderSymbol) {
                    GENDER_SYMBOL_MALE -> Triple(PixelIcons.male, palette.male, palette.maleShadow)
                    GENDER_SYMBOL_FEMALE -> Triple(PixelIcons.female, palette.female, palette.femaleShadow)
                    else -> null
                }
                gender?.let { (bitmap, color, sh) ->
                    Canvas(Modifier.size(u * 8)) {
                        drawPixelBitmap(bitmap, mapOf('1' to sh), px, px, px)
                        drawPixelBitmap(bitmap, mapOf('1' to color), 0f, 0f, px)
                    }
                }
            }
        }
        if (showHp) {
            // Right under the bar, as the game stacks them.
            val numbersTop = with(density) { (hpBarTop(rowHeightPx, px) + 8 * px).toDp() }
            Box(Modifier.align(Alignment.TopEnd).padding(end = u * 5 + edge, top = numbersTop)) {
                GbaText("${mon.hp}/${mon.maxHp}", text, shadow, m)
            }
        }
    }
}

/** The list slot's frame from [x0]: an outline, a light edge, the fill on top and the light band under it, a shade line at the bottom. */
private fun DrawScope.drawListSlot(c: PartySlotColors, u: Float, x0: Float) {
    val w = floor((size.width - x0) / u) * u
    val h = floor(size.height / u) * u
    val mid = floor(h * 0.5f / u) * u
    drawPixelRoundRect(c.outline, Offset(x0, 0f), Size(w, h), radius = 3 * u, step = u)
    drawPixelRoundRect(c.light, Offset(x0 + u, u), Size(w - 2 * u, h - 2 * u), radius = 2 * u, step = u)
    drawRect(c.fill, Offset(x0 + 2 * u, 2 * u), Size(w - 4 * u, mid - 2 * u))
    if (c.band != c.light) drawRect(c.band, Offset(x0 + 2 * u, mid), Size(w - 4 * u, h - 3 * u - mid))
    drawRect(c.shade, Offset(x0 + 2 * u, h - 3 * u), Size(w - 4 * u, u))
}

/** GetHPBarLevel's full / green / yellow / red (0..3) for the icon's pace; at 0 HP the icon stands still. */
private fun listHpLevel(hp: Int, maxHp: Int): Int = when {
    maxHp <= 0 || hp >= maxHp -> 0
    hp * 2 > maxHp -> 1
    hp * 5 > maxHp -> 2
    else -> 3
}

/** Where the HP bar's top sits in a row [h] px tall: its 7 pixels and the numbers' line under it, centred together. */
private fun hpBarTop(h: Float, u: Float) = floor((h - 7 * u - 14 * u) / 2 / u) * u

/** The HP label and bar on the right, [y] from the top, as the party menu draws them. */
private fun DrawScope.drawListHpBar(p: PartyPalette, hp: Int, maxHp: Int, u: Float, edge: Float, y: Float) {
    val right = floor((size.width - edge) / u) * u - 5 * u
    val barW = floor((size.width - edge) * 0.32f / u) * u
    val barX = right - barW
    val labelX = barX - 10 * u
    drawPixelRoundRect(p.hpFrame, Offset(labelX, y), Size(barX - labelX + u, 7 * u), radius = u, step = u)
    HpLetters.forEachIndexed { row, line ->
        line.forEachIndexed { col, ch ->
            if (ch == '1') drawRect(if (row < 3) p.hpLabel else p.hpLabelShade, Offset(labelX + (1 + col) * u, y + (1 + row) * u), Size(u, u))
        }
    }
    drawPixelRoundRect(p.hpFrame, Offset(barX, y), Size(barW, 7 * u), radius = 2 * u, step = u)
    drawPixelRoundRect(p.hpInner, Offset(barX + u, y + u), Size(barW - 2 * u, 5 * u), radius = u, step = u)
    val inner = barW - 4 * u
    val filled = if (maxHp > 0) (floor(inner / u * hp.coerceIn(0, maxHp) / maxHp) * u).coerceAtLeast(if (hp > 0) u else 0f) else 0f
    val color = when {
        maxHp <= 0 || hp * 2 > maxHp -> p.hpGreen
        hp * 5 > maxHp -> p.hpYellow
        else -> p.hpRed
    }
    fun band(c: HpBarColors, x: Float, w: Float) {
        if (w <= 0f) return
        drawRect(c.top, Offset(x, y + 2 * u), Size(w, u))
        drawRect(c.main, Offset(x, y + 3 * u), Size(w, 2 * u))
    }
    band(p.hpEmpty, barX + 2 * u, inner)
    band(color, barX + 2 * u, filled)
}
