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
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import com.pokedaisy.app.companion.ui.theme.pixelFontFamily
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * BATTLE STYLE > GEN 5: the battle controls in the look of Black / White's touch screen, not a copy of it.
 * The tab is a framed panel split like a Poké Ball - red on top, pale lavender below, a dark band across - with
 * FIGHT! on a dark plate over the band and BAG / RUN / POKéMON as dark dotted octagons along the bottom (RUN set
 * lower), their bottoms running off the panel like the game's. FIGHT dims the ball and lays four plates over it,
 * each edged in its move's type colour, over a message bar and the U-turn back plate. Between turns the panel goes
 * dark around a big Poké Ball. Same inputs and taps as [BattleControlsScreen] (which calls this); the POKéMON pane
 * stays the party tab's own slots. Its colours are its own art, like the game's: no dark-mode twin.
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
    Box(modifier.fillMaxSize()) {
        if (page == Gen5Page.PARTY) {
            // The party tab's own slots, over the game's backdrop like PARTY.
            PartyPicker(
                party, foe, active, battleInput, busy, showHints,
                onBack = { if (battleInputState == BATTLE_INPUT_PARTY_OPEN) battleInput?.back() else choosingMon = false },
            )
        } else {
            val dim by animateFloatAsState(if (page == Gen5Page.MOVES) 1f else 0f, tween(220), label = "gen5-dim")
            val dark by animateFloatAsState(if (page == Gen5Page.WAIT || page == Gen5Page.BACK) 1f else 0f, tween(260), label = "gen5-dark")
            Gen5Panel(dim, dark, Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val h = maxHeight
                    val w = maxWidth
                    val topRow = (h * 0.13f).coerceIn(34.dp, 48.dp)
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        modifier = Modifier.fillMaxSize(),
                        label = "gen5-page",
                    ) { p ->
                        when (p) {
                            Gen5Page.ACTIONS -> Gen5Actions(
                                activeMon, party, battleInput, enabled, busy, showHints, oneTapMoves, w, h, topRow,
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
                    // INFO / SUGGESTIONS and the foe's balls, on every page: they only open panes, never wait on busy.
                    Row(
                        Modifier.fillMaxWidth().height(topRow).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Gen5Plate(
                            Modifier.width(92.dp).fillMaxHeight(), cut = 8.dp, onClick = onShowInfo,
                            contentPadding = PaddingValues(0.dp),
                        ) { Gen5Label(tr("INFO"), InfoText, 20.sp) }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (foeParty.isNotEmpty()) Gen5PartyBalls(foeParty, ball = 18.dp, mirrored = true)
                        }
                        Gen5Plate(
                            Modifier.width(150.dp).fillMaxHeight(), cut = 8.dp, onClick = onShowSuggestions,
                            contentPadding = PaddingValues(0.dp),
                        ) { Gen5Label(tr("SUGGESTIONS"), SuggestText, 18.sp) }
                    }
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

private enum class Gen5Page { ACTIONS, MOVES, PARTY, WAIT, BACK }

/** Where the red half meets the pale one, as a share of the panel's height. */
private const val BAND_AT = 0.45f

/** Your turn: FIGHT! on the band (or ONE-TAP MOVES' four plates), your party's balls under it, BAG / RUN / POKéMON below. */
@Composable
private fun Gen5Actions(
    activeMon: MonView?,
    party: List<MonView>,
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
        val buttonsTop = if (oneTapMoves) h * 0.74f else h * 0.69f
        if (oneTapMoves) {
            Column(
                Modifier.fillMaxWidth().height(buttonsTop - topRow - 6.dp).offset(y = topRow + 2.dp).padding(horizontal = 10.dp),
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
            val fightH = h * 0.24f
            val fightW = minOf(w * 0.66f, fightH * 4.4f)
            Gen5Plate(
                Modifier.size(fightW, fightH).align(Alignment.TopCenter).offset(y = h * BAND_AT - fightH / 2),
                cut = fightH * 0.42f, cutY = fightH * 0.5f, border = FightRim, fill = FightFill, dots = FightDots,
                enabled = enabled, pressesGame = onFight == null,
                onClick = { onFight?.invoke() ?: battleInput?.selectAction(0) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpeciesIcon(activeMon?.iconAsset, size = 44.dp)
                    Gen5Label(tr("FIGHT") + "!", FightText, 46.sp, shadow = FightShadow)
                }
            }
            // Your party, on the pale half under the plate.
            Box(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).offset(y = h * BAND_AT + fightH / 2 + h * 0.025f),
                contentAlignment = Alignment.Center,
            ) { Gen5PartyBalls(party, ball = 20.dp) }
        }
        // BAG and POKéMON at the sides, RUN between them and lower, all running off the panel's bottom.
        val bw = minOf(w * 0.31f, 300.dp)
        val drop = if (oneTapMoves) 0.dp else h * 0.06f
        val side = w * 0.025f
        val cutX = bw * 0.2f
        val cutY = (h - buttonsTop) * 0.34f
        Gen5Plate(
            Modifier.size(bw, h - buttonsTop).align(Alignment.BottomStart).offset(x = side),
            cut = cutX, cutY = cutY, openBottom = true, enabled = enabled, pressesGame = true,
            onClick = { battleInput?.selectAction(1) },
        ) { Gen5Label(tr("BAG"), BagText, 32.sp) }
        Gen5Plate(
            Modifier.size(bw, h - buttonsTop - drop).align(Alignment.BottomCenter),
            cut = cutX, cutY = cutY, openBottom = true, enabled = enabled, pressesGame = true,
            onClick = { battleInput?.selectAction(3) },
        ) { Gen5Label(tr("RUN"), RunText, 32.sp) }
        Gen5Plate(
            Modifier.size(bw, h - buttonsTop).align(Alignment.BottomEnd).offset(x = -side),
            cut = cutX, cutY = cutY, openBottom = true, enabled = enabled, pressesGame = onPokemon == null,
            onClick = { onPokemon?.invoke() ?: battleInput?.selectAction(2) },
        ) { Gen5Label(tr("POKéMON"), MonText, 28.sp) }
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
    val barH = (h * 0.2f).coerceIn(52.dp, 86.dp)
    Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = topRow + 4.dp)) {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0..1) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (col in 0..1) {
                    val i = row * 2 + col
                    Gen5MoveCard(activeMon?.moves?.getOrNull(i), battleInput, i, busy, showHints, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(barH), verticalAlignment = Alignment.Bottom) {
            Gen5MessageBar(Modifier.weight(1f).height(barH * 0.62f).padding(bottom = 6.dp)) {
                if (activeMon != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeciesIcon(activeMon.iconAsset, size = 32.dp)
                    Spacer(Modifier.width(6.dp))
                    Gen5Label(activeMon.name, Color.White, 22.sp, bold = false)
                }
            }
            Spacer(Modifier.width(10.dp))
            Gen5BackPlate(
                enabled, pressesGame = onCancel == null,
                onClick = { onCancel?.invoke() ?: battleInput?.back() },
                modifier = Modifier.width(barH * 1.7f).fillMaxHeight(),
            )
        }
    }
}

/** Not your turn: the dark panel's big ball says so; [message] in the bar under it. With [backInput] (the game's
 * bag or party screen is up) the bar gets the U-turn plate, the only thing to do here. */
@Composable
private fun Gen5Waiting(message: String?, backInput: BattleInput?, enabled: Boolean, h: Dp, topRow: Dp) {
    if (backInput != null) CompanionBackHandler(enabled = enabled) { backInput.back() }
    val barH = (h * 0.2f).coerceIn(52.dp, 86.dp)
    Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = topRow + 4.dp)) {
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(barH), verticalAlignment = Alignment.Bottom) {
            if (message != null) Gen5MessageBar(Modifier.weight(1f).height(barH * 0.62f).padding(bottom = 6.dp)) {
                Gen5Label(message, Color.White, 20.sp, bold = false)
            } else Spacer(Modifier.weight(1f))
            if (backInput != null) {
                Spacer(Modifier.width(10.dp))
                Gen5BackPlate(enabled, pressesGame = true, onClick = { backInput.back() }, modifier = Modifier.width(barH * 1.7f).fillMaxHeight())
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
        val cut = minOf(maxHeight * 0.26f, 30.dp)
        Gen5Plate(
            Modifier.fillMaxSize(), cut = cut, border = border, enabled = enabled, pressesGame = true,
            dimDisabled = mv != null,
            contentPadding = PaddingValues(horizontal = cut * 0.55f + 6.dp, vertical = 6.dp),
            onClick = { battleInput?.selectMove(moveIndex) },
        ) {
            if (mv == null) {
                Gen5Label("—", EmptyRim, 28.sp)
            } else {
                val shadowPx = with(LocalDensity.current) { 2.dp.toPx() }
                val tight = TextStyle(
                    fontFamily = pixelFontFamily(), lineHeight = 1.em, fontWeight = FontWeight.Bold,
                    shadow = Shadow(PlateShadow, Offset(shadowPx, shadowPx), 0f),
                )
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                        FittedText(
                            mv.name, Color.White, tight, maxSize = 30, minSize = 18,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Text(tr("PP {0}", mv.pp), color = PpText, fontSize = 20.sp, style = tight, maxLines = 1)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TypeBadge(mv.type, fontSize = 16.sp)
                        Text(tr("PWR {0}", powerLabel(mv.power)), color = PlateMuted, fontSize = 20.sp, style = tight, maxLines = 1, softWrap = false)
                        if (showHints) mv.vs.forEach { v -> MultiplierChip(v.label, v.pct, fontSize = 16.sp) }
                    }
                }
            }
        }
    }
}

/** The U-turn back plate, bottom right of the move plates. */
@Composable
private fun Gen5BackPlate(enabled: Boolean, pressesGame: Boolean, onClick: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val h = maxHeight
        Gen5Plate(
            Modifier.fillMaxSize(), cut = h * 0.3f, openBottom = true, enabled = enabled, pressesGame = pressesGame,
            contentPadding = PaddingValues(top = 4.dp), onClick = onClick,
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
            drawPixelRoundRect(BarFill, Offset(3 * px, 3 * px), Size(size.width - 6 * px, size.height - 6 * px), radius = 2 * px, step = px)
        }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

/** A ball per member, a dot per empty slot (the game's party row). [mirrored] (the foe's) fills from the right. */
@Composable
private fun Gen5PartyBalls(party: List<MonView>, ball: Dp, mirrored: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(ball * 0.45f), verticalAlignment = Alignment.CenterVertically) {
        for (slot in 0 until 6) {
            val mon = party.getOrNull(if (mirrored) 5 - slot else slot)
            if (mon != null) PokeballDot(alive = mon.hp > 0, dotSize = ball)
            else Box(
                Modifier.size(ball), contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(ball * 0.3f).clip(PixelPillShape).drawBehind { drawRect(EmptyDot) })
            }
        }
    }
}

/**
 * A dark plate: a near-black outline, a [border] band (white, or a move's type colour) and a dark fill dotted like
 * the game's carbon texture, cut at the corners in whole GBA pixels ([cut] across, [cutY] down). [openBottom]: no
 * bottom edge - the plate runs off the panel. Taps click like [PlatinumButton].
 */
@Composable
private fun Gen5Plate(
    modifier: Modifier,
    cut: Dp,
    cutY: Dp = cut,
    border: Color = PlateWhite,
    fill: Color = PlateFill,
    dots: Color = PlateDots,
    openBottom: Boolean = false,
    enabled: Boolean = true,
    /** Faded while disabled (a move with no PP); false for an empty move slot, which reads by its dash. */
    dimDisabled: Boolean = true,
    pressesGame: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(6.dp),
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val sound = LocalClickSound.current
    val gameSilent = LocalGameSilent.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val density = LocalDensity.current
    val dotBrush = rememberDotBrush(dots, density.gbaPixelPx())
    val shape = remember(cut, cutY, openBottom) { ChamferShape(cut, cutY, openBottom) }
    Box(
        modifier
            .alpha(if (enabled || !dimDisabled) 1f else 0.45f)
            .drawBehind {
                val px = gbaPixelPx()
                val cx = cut.toPx()
                val cy = cutY.toPx()
                fun layer(inset: Float, color: Color? = null, brush: Brush? = null) {
                    val p = chamferPath(
                        inset, inset, size.width - 2 * inset, size.height - (if (openBottom) inset else 2 * inset),
                        (cx - inset * 0.5f).coerceAtLeast(0f), (cy - inset * 0.5f).coerceAtLeast(0f), px, openBottom,
                    )
                    if (color != null) drawPath(p, color)
                    if (brush != null) drawPath(p, brush)
                }
                layer(0f, PlateOutline)
                layer(px, border)
                layer(3 * px, if (pressed) lerpColor(fill, Color.White, 0.18f) else fill)
                layer(3 * px, brush = dotBrush)
            }
            .clip(shape)
            .then(
                if (enabled) Modifier.clickable(interactionSource = interaction, indication = null) {
                    onClick()
                    if (!pressesGame || gameSilent()) sound()
                } else Modifier,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

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

private class ChamferShape(private val cut: Dp, private val cutY: Dp, private val openBottom: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        Outline.Generic(chamferPath(0f, 0f, size.width, size.height, cut.toPx(), cutY.toPx(), gbaPixelPx(), openBottom))
    }
}

/**
 * An octagon whose corners are cut [cx] across and [cy] down as a staircase of whole [step]s (the app's corners are
 * pixel art). [openBottom]: square at the bottom.
 */
internal fun chamferPath(l: Float, t: Float, w: Float, h: Float, cx: Float, cy: Float, step: Float, openBottom: Boolean = false): Path {
    val maxRows = floor((if (openBottom) h else h / 2f) / step).toInt()
    val n = (cy / step).roundToInt().coerceIn(0, maxRows.coerceAtLeast(0))
    val across = cx.coerceAtMost(w / 2f)
    fun inset(j: Int) = if (j >= n) 0f else floor(across * (n - j) / n / step) * step
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
                lineTo(l + w - inset(j + 1), t + h - (j + 1) * step)
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
 * The panel behind it all: a pixel-rounded frame around the ball - red over pale lavender, a dark band between them.
 * [dim] (0..1) darkens it for the move plates; [dark] (0..1) fades it to the between-turns screen, a dark field with
 * a big Poké Ball in the middle.
 */
@Composable
private fun Gen5Panel(dim: Float, dark: Float, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    val texture = rememberDotBrush(Color(0x14FFFFFF), density.gbaPixelPx())
    Box(
        modifier
            .clip(PixelRoundedShape(8.dp))
            .drawBehind {
                val px = gbaPixelPx()
                val band = size.height * BAND_AT
                // Red half: lighter in the middle, darker toward the rim.
                drawRect(
                    Brush.radialGradient(listOf(RedLight, RedMid, RedDark), Offset(size.width / 2, band * 0.55f), size.width * 0.62f),
                    size = Size(size.width, band),
                )
                // Pale half.
                drawRect(
                    Brush.radialGradient(listOf(PaleLight, PaleMid, PaleDark), Offset(size.width / 2, band + (size.height - band) * 0.35f), size.width * 0.7f),
                    topLeft = Offset(0f, band), size = Size(size.width, size.height - band),
                )
                // The band: a red rim above, the black line, a grey shade below.
                drawRect(RedRim, Offset(0f, band - 3 * px), Size(size.width, 2 * px))
                drawRect(BandBlack, Offset(0f, band - px), Size(size.width, 3 * px))
                drawRect(
                    Brush.verticalGradient(listOf(PaleShade, PaleShade.copy(alpha = 0f)), band + 2 * px, band + 10 * px),
                    Offset(0f, band + 2 * px), Size(size.width, 8 * px),
                )
                if (dim > 0f) drawRect(DimScrim.copy(alpha = DimScrim.alpha * dim))
                if (dark > 0f) {
                    drawRect(DarkField.copy(alpha = dark))
                    drawRect(texture, alpha = dark)
                    drawBigBall(dark, px)
                }
            }
            .drawBehind {
                // The frame, over the art's edge.
                val px = gbaPixelPx()
                drawPixelRoundFrame(PlateOutline, Offset.Zero, size, radius = 8.dp.toPx(), width = 2 * px, step = px)
            },
        content = content,
    )
}

/** The between-turns Poké Ball: dark red over grey, a black band, the button - pixel circles throughout. */
private fun DrawScope.drawBigBall(alpha: Float, px: Float) {
    val d = (minOf(size.width, size.height) * 0.66f / px).roundToInt() * px
    val left = ((size.width - d) / 2 / px).roundToInt() * px
    val top = ((size.height - d) / 2 / px).roundToInt() * px
    val r = d / 2
    fun circle(inset: Float, color: Color) {
        val s = d - 2 * inset
        if (s > 0) drawPath(pixelRoundRectPath(Size(s, s), s / 2, px, Offset(left + inset, top + inset)), color, alpha = alpha)
    }
    circle(0f, BallOutline)
    clipRect(top = top, bottom = top + r) { circle(2 * px, BallRed) }
    clipRect(top = top + r, bottom = top + d) { circle(2 * px, BallGrey) }
    drawRect(BallOutline, Offset(left, top + r - 3 * px), Size(d, 6 * px), alpha = alpha)
    val b = (d * 0.3f / px).roundToInt() * px
    val bl = left + (d - b) / 2
    val bt = top + (d - b) / 2
    drawPath(pixelRoundRectPath(Size(b, b), b / 2, px, Offset(bl, bt)), BallOutline, alpha = alpha)
    drawPath(pixelRoundRectPath(Size(b - 6 * px, b - 6 * px), b, px, Offset(bl + 3 * px, bt + 3 * px)), BallButton, alpha = alpha)
    drawPath(pixelRoundRectPath(Size(b - 14 * px, b - 14 * px), b, px, Offset(bl + 7 * px, bt + 7 * px)), BallButtonInner, alpha = alpha)
}

/** The plates' label: the pixel font in [color] with a dark drop shadow. */
@Composable
private fun Gen5Label(text: String, color: Color, fontSize: TextUnit, shadow: Color = LabelShadow, bold: Boolean = true) {
    val s = with(LocalDensity.current) { (fontSize.toPx() / 14f).coerceAtLeast(2f) }
    Text(
        text, color = color, fontSize = fontSize, maxLines = 1,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = pixelFontFamily(), shadow = Shadow(shadow, Offset(s, s), 0f)),
    )
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

private val RedLight = Color(0xFFE84050)
private val RedMid = Color(0xFFC82838)
private val RedDark = Color(0xFF8C1828)
private val RedRim = Color(0xFF701020)
private val PaleLight = Color(0xFFF4F4FA)
private val PaleMid = Color(0xFFE2E2EE)
private val PaleDark = Color(0xFFBEBED2)
private val PaleShade = Color(0x669090A8)
private val BandBlack = Color(0xFF282028)
private val DimScrim = Color(0x8C101018)
private val DarkField = Color(0xFF26262A)
private val BallOutline = Color(0xFF141418)
private val BallRed = Color(0xFF7A2430)
private val BallGrey = Color(0xFF5C5C66)
private val BallButton = Color(0xFF3A3A42)
private val BallButtonInner = Color(0xFF4A4A54)
private val PlateOutline = Color(0xFF181818)
private val PlateWhite = Color(0xFFF4F4F8)
private val PlateFill = Color(0xFF343438)
private val PlateDots = Color(0xFF4A4A50)
private val PlateShadow = Color(0xFF101014)
private val PlateMuted = Color(0xFFB8B8C8)
private val PpText = Color(0xFFE8E8F0)
private val FightRim = Color(0xFF5C5054)
private val FightFill = Color(0xFF3A3034)
private val FightDots = Color(0xFF4A3E42)
private val FightText = Color(0xFFF05868)
private val FightShadow = Color(0xFF601420)
private val LabelShadow = Color(0xFF141414)
private val BagText = Color(0xFFF8C840)
private val RunText = Color(0xFF68B8F8)
private val MonText = Color(0xFF80DC60)
private val InfoText = Color(0xFFC0A0F8)
private val SuggestText = Color(0xFFF8D050)
private val EmptyRim = Color(0xFF808088)
private val EmptyDot = Color(0xFF505058)
private val BarRim = Color(0xFF606068)
private val BarFill = Color(0xFF1C1C20)
private val UTurnBlue = Color(0xFF3890F0)
private val UTurnLight = Color(0xFFA8D8FF)
private val UTurnDark = Color(0xFF103060)
