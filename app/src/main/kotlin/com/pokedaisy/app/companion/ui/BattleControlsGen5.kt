package com.pokedaisy.app.companion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_TARGET_SELECT
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import com.pokedaisy.app.companion.ui.theme.pixelFontFamily
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * BATTLE STYLE > GEN 5: the battle controls in the look of Black / White's touch screen, not a copy of it.
 * The whole tab area (edge to edge: CompanionScreen drops its margin and docks the tab bar under it, [Gen5Dock])
 * is a Poké Ball - red on top, pale lavender below, a dark band across - with FIGHT! on a dark hexagon over the
 * band and BAG / RUN / POKéMON as half hexagons rising from the bottom edge (RUN lower). Plates are dark and
 * dotted inside a thick white (or type-coloured) border; labels are the pixel font in pixel bold with a dark
 * outline ([Gen5Text]). FIGHT dims the ball under four hexagon move plates, a message bar and the U-turn back
 * plate; between turns the tab goes dark around a big Poké Ball. Same inputs and taps as [BattleControlsScreen]
 * (which calls this); the POKéMON pane is the party tab's own slots on the dark field. Its colours are its own
 * art, like the game's: no dark-mode twin.
 */
@Composable
internal fun Gen5BattleControls(
    activeMon: MonView?,
    battleInput: BattleInput?,
    battleInputState: Int,
    party: List<MonView>,
    modifier: Modifier,
    showHints: Boolean,
    onShowInfo: () -> Unit,
    onShowSuggestions: () -> Unit,
    foeParty: List<MonView>,
    foe: MonView?,
    active: List<MonView>,
    oneTapMoves: Boolean,
) {
    val busy = rememberBattleInputBusy(battleInput)
    var choosingMon by remember { mutableStateOf(false) }
    var choosingMove by remember { mutableStateOf(false) }
    LaunchedEffect(battleInputState) {
        if (battleInputState != BATTLE_INPUT_ACTION_SELECT) { choosingMon = false; choosingMove = false }
    }
    val movesKnown = activeMon?.moves?.isNotEmpty() == true
    val canSwitch = battleInput?.canSwitch == true
    val picking = (battleInputState == BATTLE_INPUT_ACTION_SELECT && choosingMon) ||
        (battleInputState == BATTLE_INPUT_PARTY_OPEN && canSwitch)
    val page = when {
        picking -> Gen5Page.PARTY
        battleInputState == BATTLE_INPUT_MOVE_SELECT -> Gen5Page.MOVES
        battleInputState == BATTLE_INPUT_ACTION_SELECT && choosingMove -> Gen5Page.MOVES
        battleInputState == BATTLE_INPUT_ACTION_SELECT -> Gen5Page.ACTIONS
        battleInputState == BATTLE_INPUT_BAG_OPEN || battleInputState == BATTLE_INPUT_PARTY_OPEN -> Gen5Page.BACK
        else -> Gen5Page.WAIT
    }
    val enabled = battleInput != null && !busy
    val dim by animateFloatAsState(if (page == Gen5Page.MOVES) 1f else 0f, tween(220), label = "gen5-dim")
    val dark by animateFloatAsState(if (page == Gen5Page.ACTIONS || page == Gen5Page.MOVES) 0f else 1f, tween(260), label = "gen5-dark")
    Box(modifier.fillMaxSize()) {
        Gen5Panel(dim, dark, ball = page != Gen5Page.PARTY, modifier = Modifier.fillMaxSize()) {
            if (page == Gen5Page.PARTY) {
                // The party tab's own slots, on the dark field, inset like the other tabs.
                Box(Modifier.fillMaxSize().padding(12.dp)) {
                    PartyPicker(
                        party, foe, active, battleInput, busy, showHints,
                        onBack = { if (battleInputState == BATTLE_INPUT_PARTY_OPEN) battleInput?.back() else choosingMon = false },
                    )
                }
            } else BoxWithConstraints(Modifier.fillMaxSize()) {
                val h = maxHeight
                val w = maxWidth
                val topRow = (h * 0.15f).coerceIn(48.dp, 64.dp)
                AnimatedContent(
                    targetState = page,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    modifier = Modifier.fillMaxSize(),
                    label = "gen5-page",
                ) { p ->
                    when (p) {
                        Gen5Page.ACTIONS -> Gen5Actions(
                            activeMon, party, foeParty, battleInput, enabled, busy, showHints, oneTapMoves, w, h, topRow,
                            onFight = if (movesKnown) ({ choosingMove = true }) else null,
                            onPokemon = if (canSwitch) ({ choosingMon = true }) else null,
                        )
                        Gen5Page.MOVES -> Gen5Moves(
                            activeMon, battleInput, busy, showHints, h, topRow,
                            onCancel = if (battleInputState == BATTLE_INPUT_ACTION_SELECT) ({ choosingMove = false }) else null,
                        )
                        else -> Gen5Waiting(
                            when {
                                p == Gen5Page.BACK -> null
                                battleInputState == BATTLE_INPUT_TARGET_SELECT -> tr("Confirming target…")
                                else -> tr("Waiting for your turn…")
                            },
                            battleInput.takeIf { p == Gen5Page.BACK }, enabled, h, topRow,
                        )
                    }
                }
                // INFO / SUGGESTIONS on every page: they only open panes, never wait on busy.
                Row(
                    Modifier.fillMaxWidth().height(topRow).padding(start = 10.dp, end = 10.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Gen5Plate(
                        Modifier.width((w * 0.28f).coerceIn(124.dp, 200.dp)).fillMaxHeight(), borderPx = 2,
                        onClick = onShowInfo, contentPadding = PaddingValues(0.dp),
                    ) { Gen5Text(tr("INFO"), InfoText, 26) }
                    Spacer(Modifier.weight(1f))
                    Gen5Plate(
                        Modifier.width((w * 0.44f).coerceIn(204.dp, 320.dp)).fillMaxHeight(), borderPx = 2,
                        onClick = onShowSuggestions, contentPadding = PaddingValues(horizontal = 18.dp),
                    ) { Gen5Text(tr("SUGGESTIONS"), SuggestText, 26, minSize = 16) }
                }
            }
        }
        AnimatedVisibility(
            visible = busy,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.Center),
        ) { ProcessingBall() }
    }
}

/** The dark strip the tab bar sits on under GEN 5's edge-to-edge screen (CompanionScreen). */
internal val Gen5Dock = Color(0xFF1C1C22)

private enum class Gen5Page { ACTIONS, MOVES, PARTY, WAIT, BACK }

/** Where the red half meets the pale one, as a share of the panel's height. */
private const val BAND_AT = 0.47f

/** Your turn: FIGHT! on the band (or ONE-TAP MOVES' four plates), the two parties' balls either side of it,
 * BAG / RUN / POKéMON rising from the bottom edge. */
@Composable
private fun Gen5Actions(
    activeMon: MonView?,
    party: List<MonView>,
    foeParty: List<MonView>,
    battleInput: BattleInput?,
    enabled: Boolean,
    busy: Boolean,
    showHints: Boolean,
    oneTapMoves: Boolean,
    w: Dp,
    h: Dp,
    topRow: Dp,
    onFight: (() -> Unit)?,
    onPokemon: (() -> Unit)?,
) {
    Box(Modifier.fillMaxSize()) {
        val buttonsTop = if (oneTapMoves) h * 0.76f else h * 0.70f
        if (oneTapMoves) {
            Column(
                Modifier.fillMaxWidth().height(buttonsTop - topRow - 12.dp).offset(y = topRow + 6.dp).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (row in 0..1) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (col in 0..1) {
                        val i = row * 2 + col
                        Gen5MoveCard(activeMon?.moves?.getOrNull(i), battleInput, i, busy, showHints, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        } else {
            val band = h * BAND_AT
            val fightH = h * 0.25f
            val fightW = minOf(w * 0.72f, fightH * 4.6f)
            // The foe's balls in the red half (a trainer's), between the top row and FIGHT!.
            if (foeParty.isNotEmpty()) Box(
                Modifier.fillMaxWidth().height(band - fightH / 2 - topRow).offset(y = topRow),
                contentAlignment = Alignment.Center,
            ) { Gen5PartyBalls(foeParty, ball = 20.dp, mirrored = true) }
            Gen5Plate(
                Modifier.size(fightW, fightH).align(Alignment.TopCenter).offset(y = band - fightH / 2),
                border = FightRim, fill = FightFill, dots = FightDots,
                enabled = enabled, pressesGame = onFight == null, focusFirst = true,
                onClick = { onFight?.invoke() ?: battleInput?.selectAction(0) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SpeciesIcon(activeMon?.iconAsset, size = 48.dp)
                    Gen5Text(tr("FIGHT") + "!", FightText, 54, outline = FightOutline)
                }
            }
            // Your balls on the pale half, between FIGHT! and the buttons.
            Box(
                Modifier.fillMaxWidth().height(buttonsTop - band - fightH / 2).offset(y = band + fightH / 2),
                contentAlignment = Alignment.Center,
            ) { Gen5PartyBalls(party, ball = 22.dp) }
        }
        // BAG and POKéMON at the sides, RUN between them and lower: half hexagons rising from the bottom edge.
        val bw = minOf(w * 0.315f, 320.dp)
        val drop = if (oneTapMoves) 0.dp else h * 0.06f
        val side = w * 0.02f
        // Clear of the slanted sides (an eighth of the width each, HexGeo.of) and the border.
        val sidePad = PaddingValues(horizontal = bw * 0.125f + 6.dp)
        Gen5Plate(
            Modifier.size(bw, h - buttonsTop).align(Alignment.BottomStart).offset(x = side),
            openBottom = true, contentPadding = sidePad, enabled = enabled, pressesGame = true,
            onClick = { battleInput?.selectAction(1) },
        ) { Gen5Text(tr("BAG"), BagText, 38) }
        Gen5Plate(
            Modifier.size(bw, h - buttonsTop - drop).align(Alignment.BottomCenter),
            openBottom = true, contentPadding = sidePad, enabled = enabled, pressesGame = true,
            onClick = { battleInput?.selectAction(3) },
        ) { Gen5Text(tr("RUN"), RunText, 38) }
        Gen5Plate(
            Modifier.size(bw, h - buttonsTop).align(Alignment.BottomEnd).offset(x = -side),
            openBottom = true, contentPadding = sidePad, enabled = enabled, pressesGame = onPokemon == null,
            onClick = { onPokemon?.invoke() ?: battleInput?.selectAction(2) },
        ) { Gen5Text(tr("POKéMON"), MonText, 34, minSize = 22) }
    }
}

/** FIGHT: four plates over the dimmed ball, then a message bar with the one battling and the U-turn back plate. */
@Composable
private fun Gen5Moves(
    activeMon: MonView?,
    battleInput: BattleInput?,
    busy: Boolean,
    showHints: Boolean,
    h: Dp,
    topRow: Dp,
    /** The plates were opened by us (FIGHT not sent yet): back just closes them; null = press B. */
    onCancel: (() -> Unit)?,
) {
    val enabled = battleInput != null && !busy
    CompanionBackHandler(enabled = enabled) { onCancel?.invoke() ?: battleInput?.back() }
    val barH = (h * 0.2f).coerceIn(56.dp, 90.dp)
    Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = topRow + 8.dp)) {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0..1) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0..1) {
                    val i = row * 2 + col
                    Gen5MoveCard(activeMon?.moves?.getOrNull(i), battleInput, i, busy, showHints, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(barH), verticalAlignment = Alignment.Bottom) {
            Gen5MessageBar(Modifier.weight(1f).height(barH * 0.66f).padding(bottom = 8.dp)) {
                if (activeMon != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeciesIcon(activeMon.iconAsset, size = 34.dp)
                    Spacer(Modifier.width(8.dp))
                    Gen5Text(activeMon.name, Color.White, 24)
                }
            }
            Spacer(Modifier.width(10.dp))
            Gen5BackPlate(
                enabled, pressesGame = onCancel == null,
                onClick = { onCancel?.invoke() ?: battleInput?.back() },
                modifier = Modifier.width(barH * 1.8f).fillMaxHeight(),
            )
        }
    }
}

/** Not your turn: the dark field's big ball says so; [message] in the bar under it. With [backInput] (the game's
 * bag or party screen is up) the bar gets the U-turn plate, the only thing to do here. */
@Composable
private fun Gen5Waiting(message: String?, backInput: BattleInput?, enabled: Boolean, h: Dp, topRow: Dp) {
    if (backInput != null) CompanionBackHandler(enabled = enabled) { backInput.back() }
    val barH = (h * 0.2f).coerceIn(56.dp, 90.dp)
    Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = topRow + 8.dp)) {
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(barH), verticalAlignment = Alignment.Bottom) {
            if (message != null) Gen5MessageBar(Modifier.weight(1f).height(barH * 0.66f).padding(bottom = 8.dp)) {
                Gen5Text(message, Color.White, 22)
            } else Spacer(Modifier.weight(1f))
            if (backInput != null) {
                Spacer(Modifier.width(10.dp))
                Gen5BackPlate(enabled, pressesGame = true, onClick = { backInput.back() }, modifier = Modifier.width(barH * 1.8f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Gen5MoveCard(
    mv: MoveView?,
    battleInput: BattleInput?,
    moveIndex: Int,
    busy: Boolean,
    showHints: Boolean,
    modifier: Modifier,
) {
    val enabled = mv != null && mv.pp > 0 && battleInput != null && !busy
    val border = mv?.let { AppColors.typeColors[it.type] } ?: EmptyRim
    BoxWithConstraints(modifier) {
        // The hexagon's points take this much of each side (HexGeo.of); the text stays clear of them.
        val point = minOf(maxHeight * 0.5f * HEX_SLOPE, maxWidth * 0.125f)
        Gen5Plate(
            Modifier.fillMaxSize(), border = border, enabled = enabled, pressesGame = true,
            dimDisabled = mv != null, focusFirst = moveIndex == 0,
            contentPadding = PaddingValues(start = point + 2.dp, end = point + 2.dp, top = 8.dp, bottom = 8.dp),
            onClick = { battleInput?.selectMove(moveIndex) },
        ) {
            if (mv == null) {
                Gen5Text("—", EmptyRim, 28)
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f).padding(end = 6.dp)) { Gen5Text(mv.name, Color.White, 30, minSize = 18) }
                        Gen5Text(tr("PP {0}", mv.pp), PpText, 22)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TypeBadge(mv.type, fontSize = 16.sp)
                        // Measured last (a weight): the power steps down before a verdict wraps.
                        Box(Modifier.weight(1f, fill = false)) { Gen5Text(tr("PWR {0}", powerLabel(mv.power)), PlateMuted, 20, minSize = 12) }
                        if (showHints) mv.vs.forEach { v -> MultiplierChip(v.label, v.pct, fontSize = 16.sp) }
                    }
                }
            }
        }
    }
}

/** The U-turn back plate: a half hexagon, bottom right. */
@Composable
private fun Gen5BackPlate(enabled: Boolean, pressesGame: Boolean, onClick: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val h = maxHeight
        Gen5Plate(
            Modifier.fillMaxSize(), openBottom = true, enabled = enabled, pressesGame = pressesGame,
            contentPadding = PaddingValues(top = 6.dp), onClick = onClick,
        ) {
            PixelArt(UTurn, mapOf('B' to UTurnBlue, 'L' to UTurnLight, 'K' to UTurnDark), Modifier.size(h * 0.55f))
        }
    }
}

/** The long dark bar the game writes its prompts in. */
@Composable
private fun Gen5MessageBar(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.drawBehind {
            val px = gbaPixelPx()
            drawPixelRoundRect(PlateOutline, radius = 4 * px, step = px)
            drawPixelRoundRect(BarRim, Offset(px, px), Size(size.width - 2 * px, size.height - 2 * px), radius = 3 * px, step = px)
            drawPixelRoundRect(PlateOutline, Offset(3 * px, 3 * px), Size(size.width - 6 * px, size.height - 6 * px), radius = 2 * px, step = px)
            drawPixelRoundRect(BarFill, Offset(4 * px, 4 * px), Size(size.width - 8 * px, size.height - 8 * px), radius = 2 * px, step = px)
        }.padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

/** A ball per member, a dot per empty slot (the game's party row). [mirrored] (the foe's) fills from the right. */
@Composable
private fun Gen5PartyBalls(party: List<MonView>, ball: Dp, mirrored: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(ball * 0.5f), verticalAlignment = Alignment.CenterVertically) {
        for (slot in 0 until 6) {
            val mon = party.getOrNull(if (mirrored) 5 - slot else slot)
            if (mon != null) PokeballDot(alive = mon.hp > 0, dotSize = ball)
            else Box(Modifier.size(ball), contentAlignment = Alignment.Center) {
                Box(Modifier.size(ball * 0.32f).clip(PixelPillShape).drawBehind { drawRect(EmptyDot) })
            }
        }
    }
}

/**
 * Text the way the plates letter it: the pixel font in pixel bold (each glyph twice, a font pixel apart, spaced a
 * font pixel wider) inside an [outline] one font pixel wide all round. One line at the largest size from [maxSize] down to [minSize] (sp, in
 * steps of 2) that fits the width it's given; past that it's cut at the box. Its semantics carry the text, so
 * tests find it like any Text.
 */
@Composable
internal fun Gen5Text(text: String, color: Color, maxSize: Int, minSize: Int = maxSize, outline: Color = TextOutline) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val family = pixelFontFamily()
    BoxWithConstraints(Modifier.semantics { this.text = AnnotatedString(text) }) {
        val maxW = constraints.maxWidth
        val fit: Pair<TextLayoutResult, Float> = remember(text, maxW, maxSize, minSize, density, family) {
            var pick: Pair<TextLayoutResult, Float>? = null
            var s = maxSize
            while (true) {
                // One font pixel in whole screen pixels (the font's pixel is a sixteenth of its size).
                val fp = with(density) { (s.sp.toPx() / 16f).roundToInt().coerceAtLeast(1) }.toFloat()
                // A font pixel more between letters: the bold copy fills the font's own gap, and the outline needs one.
                val style = TextStyle(fontFamily = family, fontSize = s.sp, letterSpacing = (s / 16f).sp)
                pick = measurer.measure(text, style, maxLines = 1, softWrap = false) to fp
                if (pick.first.size.width + 3 * fp <= maxW || s - 2 < minSize) break
                s -= 2
            }
            pick!!
        }
        val (layout, fp) = fit
        val w = minOf(layout.size.width + 3 * fp, maxW.toFloat())
        val hgt = layout.size.height + 2 * fp
        Box(
            Modifier
                .size(with(density) { w.toDp() }, with(density) { hgt.toDp() })
                .drawBehind {
                    clipRect {
                        // The outline: the bold glyph (two copies a font pixel apart) stamped all round it.
                        for (dx in -1..2) for (dy in -1..1) {
                            if (dx in 0..1 && dy == 0) continue
                            drawText(layout, outline, Offset(fp + dx * fp, fp + dy * fp))
                        }
                        drawText(layout, color, Offset(fp, fp))
                        drawText(layout, color, Offset(2 * fp, fp))
                    }
                },
        )
    }
}

/**
 * A dark plate: a near-black outline, a [borderPx]-GBA-pixel [border] band (white, or a move's type colour; its
 * lower half a shade darker), a dark line, then a dark fill dotted like the game's carbon texture, lit along its
 * top. Its left and right sides come to a point halfway down (a hexagon); with [openBottom] it's a hexagon's top
 * half, rising from the bottom edge. Edges step in whole GBA pixels. Taps click like [PlatinumButton]; a pressed
 * plate lights up and its content sinks a pixel.
 */
@Composable
private fun Gen5Plate(
    modifier: Modifier,
    border: Color = PlateWhite,
    fill: Color = PlateFill,
    dots: Color = PlateDots,
    borderPx: Int = 3,
    openBottom: Boolean = false,
    enabled: Boolean = true,
    /** Faded while disabled (a move with no PP); false for an empty move slot, which reads by its dash. */
    dimDisabled: Boolean = true,
    pressesGame: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(6.dp),
    /** Where the D-pad starts on this page (FIGHT!, the first move), like the game's cursor. */
    focusFirst: Boolean = false,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val sound = LocalClickSound.current
    val gameSilent = LocalGameSilent.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val density = LocalDensity.current
    val px = density.gbaPixelPx()
    val dotBrush = rememberDotBrush(dots, px)
    val shape = remember(openBottom) { HexShape(openBottom) }
    Box(
        modifier
            .focusStart(focusFirst)
            .alpha(if (enabled || !dimDisabled) 1f else 0.45f)
            // The D-pad's focus, along the hexagon's own edge.
            .gbaFocusRing(focused, shape)
            .drawBehind {
                var g = HexGeo.of(size, openBottom)
                drawPath(g.path(px), PlateOutline)
                g = g.inset(px)
                drawPath(g.path(px), border)
                // The border's lower half a shade darker: a bevel.
                clipRect(top = size.height * 0.5f) { drawPath(g.path(px), lerp(border, Color.Black, 0.2f)) }
                g = g.inset(borderPx * px)
                drawPath(g.path(px), PlateOutline)
                g = g.inset(px)
                val inner = g.path(px)
                val top = lerp(fill, Color.White, if (pressed) 0.2f else 0.07f)
                val bottom = if (pressed) lerp(fill, Color.White, 0.1f) else lerp(fill, Color.Black, 0.22f)
                drawPath(inner, Brush.verticalGradient(listOf(top, bottom), g.t, g.t + g.h))
                drawPath(inner, dotBrush)
                // A lit row along the fill's top.
                clipRect(top = g.t, bottom = g.t + px) { drawPath(inner, Color.White.copy(alpha = 0.2f)) }
            }
            .clip(shape)
            .then(
                if (enabled) Modifier.clickable(interactionSource = interaction, indication = null) {
                    onClick()
                    if (!pressesGame || gameSilent()) sound()
                } else Modifier,
            )
            .padding(contentPadding)
            .offset(y = if (pressed) with(density) { px.toDp() } else 0.dp),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * A plate's outline: a box with its left and right sides cut to a point - halfway down, or ([open]) at the bottom
 * edge, a hexagon's top half. [inset] shrinks it by a band of even thickness, so a border runs parallel all round.
 */
private data class HexGeo(val l: Float, val t: Float, val w: Float, val h: Float, val cx: Float, val cy: Float, val open: Boolean) {
    fun path(step: Float): Path = chamferPath(l, t, w, h, cx, cy, step, open)

    fun inset(d: Float): HexGeo {
        val nh = h - if (open) d else 2 * d
        if (cx <= 0f || cy <= 0f) return HexGeo(l + d, t + d, w - 2 * d, nh, 0f, 0f, open)
        // Moving each slanted side in by d moves its point in by d / cos(slant) and keeps the slope.
        val side = d * hypot(cx, cy) / cy
        return HexGeo(l + side, t + d, w - 2 * side, nh, cx * (1 - d / cy), if (open) nh else nh / 2, open)
    }

    companion object {
        fun of(size: Size, open: Boolean): HexGeo {
            val cy = if (open) size.height else size.height / 2
            // Steep points (about 60° to the horizontal), never more than an eighth of the width.
            val cx = minOf(cy * HEX_SLOPE, size.width * 0.125f)
            return HexGeo(0f, 0f, size.width, size.height, cx, cy, open)
        }
    }
}

private const val HEX_SLOPE = 0.55f

private class HexShape(private val open: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(HexGeo.of(size, open).path(density.gbaPixelPx()))
}

/** The plates' dot texture: one GBA pixel lit every three, tiled. */
@Composable
private fun rememberDotBrush(color: Color, px: Float): Brush = remember(color, px) {
    val p = px.roundToInt().coerceAtLeast(1)
    val tile = ImageBitmap(3 * p, 3 * p)
    androidx.compose.ui.graphics.Canvas(tile).drawRect(
        p.toFloat(), p.toFloat(), 2f * p, 2f * p, Paint().apply { this.color = color },
    )
    ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
}

/**
 * An octagon whose corners are cut [cx] across and [cy] down as a staircase of whole [step]s (the app's corners are
 * pixel art); [cy] at half the height (or the whole, [openBottom]) makes it a hexagon. [openBottom]: no bottom edge.
 */
internal fun chamferPath(l: Float, t: Float, w: Float, h: Float, cx: Float, cy: Float, step: Float, openBottom: Boolean = false): Path {
    val maxRows = floor((if (openBottom) h else h / 2f) / step).toInt()
    val n = (cy / step).roundToInt().coerceIn(0, maxRows.coerceAtLeast(0))
    val across = cx.coerceIn(0f, w / 2f)
    fun inset(j: Int) = if (j >= n) 0f else (across * (n - j) / n / step).roundToInt() * step
    return Path().apply {
        moveTo(l + inset(0), t)
        lineTo(l + w - inset(0), t)
        for (j in 0 until n) {
            lineTo(l + w - inset(j), t + (j + 1) * step)
            lineTo(l + w - inset(j + 1), t + (j + 1) * step)
        }
        if (openBottom) {
            lineTo(l + w, t + h)
            lineTo(l, t + h)
        } else {
            lineTo(l + w, t + h - n * step)
            for (j in n - 1 downTo 0) {
                lineTo(l + w - inset(j), t + h - (j + 1) * step)
                lineTo(l + w - inset(j), t + h - j * step)
            }
            lineTo(l + inset(0), t + h)
            for (j in 0 until n) {
                lineTo(l + inset(j), t + h - (j + 1) * step)
                lineTo(l + inset(j + 1), t + h - (j + 1) * step)
            }
        }
        lineTo(l, t + n * step)
        for (j in n - 1 downTo 0) {
            lineTo(l + inset(j), t + (j + 1) * step)
            lineTo(l + inset(j), t + j * step)
        }
        close()
    }
}

/**
 * The screen behind it all, edge to edge: the ball - red over pale lavender, a dark band between them. [dim] (0..1)
 * darkens it for the move plates; [dark] (0..1) fades it to the dark field, with a big Poké Ball in the middle
 * while [ball] (not behind the party slots).
 */
@Composable
private fun Gen5Panel(dim: Float, dark: Float, ball: Boolean, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    val texture = rememberDotBrush(Color(0x12FFFFFF), density.gbaPixelPx())
    Box(
        modifier.drawBehind {
            val px = gbaPixelPx()
            val band = (size.height * BAND_AT / px).roundToInt() * px
            val mid = Offset(size.width / 2, band)
            // Red half: deep at the top, brightest just above the band's middle.
            drawRect(Brush.verticalGradient(listOf(RedDark, RedMid), 0f, band), size = Size(size.width, band))
            drawRect(
                Brush.radialGradient(listOf(RedLight, RedLight.copy(alpha = 0f)), Offset(size.width / 2, band * 0.62f), size.width * 0.55f),
                size = Size(size.width, band),
            )
            // Faint rings around the middle, like the game's.
            for (r in listOf(0.42f, 0.7f)) drawRect(
                Brush.radialGradient(
                    0.9f to Color.Transparent, 0.95f to Color.White.copy(alpha = 0.06f), 1f to Color.Transparent,
                    center = mid, radius = size.width * r,
                ),
                size = size,
            )
            // Pale half: brightest in the middle, lilac toward the rim.
            drawRect(
                Brush.radialGradient(listOf(PaleLight, PaleMid, PaleDark), Offset(size.width / 2, band + (size.height - band) * 0.3f), size.width * 0.72f),
                topLeft = Offset(0f, band), size = Size(size.width, size.height - band),
            )
            // The band: a deep red rim, the black line, a lit edge and a grey shade under it.
            drawRect(RedRim, Offset(0f, band - 4 * px), Size(size.width, 2 * px))
            drawRect(BandBlack, Offset(0f, band - 2 * px), Size(size.width, 4 * px))
            drawRect(BandLight, Offset(0f, band + 2 * px), Size(size.width, px))
            drawRect(
                Brush.verticalGradient(listOf(PaleShade, PaleShade.copy(alpha = 0f)), band + 3 * px, band + 14 * px),
                Offset(0f, band + 3 * px), Size(size.width, 11 * px),
            )
            if (dim > 0f) drawRect(DimScrim.copy(alpha = DimScrim.alpha * dim))
            if (dark > 0f) {
                drawRect(Brush.verticalGradient(listOf(DarkFieldTop, DarkField)), alpha = dark)
                drawRect(texture, alpha = dark)
                if (ball) drawBigBall(dark, px)
            }
        },
        content = content,
    )
}

/** The between-turns Poké Ball: dark red over grey, a black band, the button - pixel circles throughout. */
private fun DrawScope.drawBigBall(alpha: Float, px: Float) {
    val d = (minOf(size.width, size.height) * 0.62f / px).roundToInt() * px
    val left = ((size.width - d) / 2 / px).roundToInt() * px
    val top = ((size.height - d) / 2 / px).roundToInt() * px
    val r = d / 2
    fun circle(inset: Float, brush: Brush) {
        val s = d - 2 * inset
        if (s > 0) drawPath(pixelRoundRectPath(Size(s, s), s / 2, px, Offset(left + inset, top + inset)), brush, alpha = alpha)
    }
    circle(0f, SolidColor(BallOutline))
    clipRect(top = top, bottom = top + r) { circle(2 * px, Brush.verticalGradient(listOf(BallRedLight, BallRed), top, top + r)) }
    clipRect(top = top + r, bottom = top + d) { circle(2 * px, Brush.verticalGradient(listOf(BallGreyLight, BallGrey), top + r, top + d)) }
    drawRect(BallOutline, Offset(left, top + r - 4 * px), Size(d, 8 * px), alpha = alpha)
    val b = (d * 0.32f / px).roundToInt() * px
    val bl = left + (d - b) / 2
    val bt = top + (d - b) / 2
    drawPath(pixelRoundRectPath(Size(b, b), b / 2, px, Offset(bl, bt)), BallOutline, alpha = alpha)
    drawPath(pixelRoundRectPath(Size(b - 6 * px, b - 6 * px), b, px, Offset(bl + 3 * px, bt + 3 * px)), BallRing, alpha = alpha)
    drawPath(pixelRoundRectPath(Size(b - 12 * px, b - 12 * px), b, px, Offset(bl + 6 * px, bt + 6 * px)), BallOutline, alpha = alpha)
    drawPath(pixelRoundRectPath(Size(b - 16 * px, b - 16 * px), b, px, Offset(bl + 8 * px, bt + 8 * px)), BallButton, alpha = alpha)
}

/** The game's back icon: a U-turn arrow. */
private val UTurn = listOf(
    "...K..........",
    "..KLK.........",
    ".KLBBK........",
    "KLBBBBK.......",
    "KKKBBKKK.KKKK.",
    "..KLBK...KLBK.",
    "..KLBK...KLBK.",
    "..KLBK...KLBK.",
    "..KLBBK.KLBBK.",
    "...KLBBKLBBK..",
    "....KLBBBBK...",
    ".....KKKKK....",
)

private val RedLight = Color(0xFFF05462)
private val RedMid = Color(0xFFD02C3C)
private val RedDark = Color(0xFF8A1424)
private val RedRim = Color(0xFF6A0C1A)
private val PaleLight = Color(0xFFF8F8FC)
private val PaleMid = Color(0xFFE4E4F0)
private val PaleDark = Color(0xFFB8B8D0)
private val PaleShade = Color(0x7A8080A0)
private val BandBlack = Color(0xFF221A22)
private val BandLight = Color(0xFF9898AC)
private val DimScrim = Color(0x99101018)
private val DarkField = Color(0xFF1E1E24)
private val DarkFieldTop = Color(0xFF2E2E36)
private val BallOutline = Color(0xFF101014)
private val BallRedLight = Color(0xFF9A3040)
private val BallRed = Color(0xFF5E1822)
private val BallGreyLight = Color(0xFF6C6C78)
private val BallGrey = Color(0xFF44444E)
private val BallRing = Color(0xFF7A7A86)
private val BallButton = Color(0xFF3C3C46)
private val PlateOutline = Color(0xFF121216)
private val PlateWhite = Color(0xFFF8F8FC)
private val PlateFill = Color(0xFF3A3A42)
private val PlateDots = Color(0xFF50505A)
private val PlateMuted = Color(0xFFC0C0D0)
private val PpText = Color(0xFFF0F0F8)
private val TextOutline = Color(0xFF101014)
private val FightRim = Color(0xFF6A5A60)
private val FightFill = Color(0xFF3E3236)
private val FightDots = Color(0xFF524448)
private val FightText = Color(0xFFFF5A6A)
private val FightOutline = Color(0xFF3A0810)
private val BagText = Color(0xFFFFCC40)
private val RunText = Color(0xFF6CC0FF)
private val MonText = Color(0xFF84E05C)
private val InfoText = Color(0xFFC8A8FF)
private val SuggestText = Color(0xFFFFD84C)
private val EmptyRim = Color(0xFF80808A)
private val EmptyDot = Color(0xFF50505A)
private val BarRim = Color(0xFF6A6A74)
private val BarFill = Color(0xFF1A1A20)
private val UTurnBlue = Color(0xFF3890F0)
private val UTurnLight = Color(0xFFA8D8FF)
private val UTurnDark = Color(0xFF103060)
