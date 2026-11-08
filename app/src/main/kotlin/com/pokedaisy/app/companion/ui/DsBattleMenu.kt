package com.pokedaisy.app.companion.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.typeLabel
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/*
 * A DS battle on one screen (HeartGold / SoulSilver, Platinum, Black / White; DS LAYOUT's ONE SCREEN and PICTURE IN PICTURE):
 * the game's own battle buttons, run like Gen 3's menus - the user's call. Every game gets HeartGold's layout in its own look
 * ([DsLook]: Platinum's bevelled buttons and cream move cards; Black / White's charcoal plates with coloured words, their
 * round-ended panel made a plain 2 x 2 - the user's call, with their magenta cursor; Black 2 / White 2's black plates rimmed
 * in each button's colour, the same way). The battle keeps its wide view; a strip across the whole screen takes
 * the text box's rows, in the band's own black: HeartGold's text box at its far left ("What will ... do?", drawn here, the
 * game's own hidden under the strip) and FIGHT / BAG / POKéMON / RUN as a 2 x 2 at its right, the cursor HeartGold's red
 * corners, pulsing. FIGHT turns the strip into the moves, HeartGold's cards 2 x 2, BACK beside them. The D-pad moves the cursor and A picks
 * ([DsBattleMenuState.key], fed by the activity, the keys kept from the game), B goes back from the moves; a tap picks too.
 * A pick presses the game's own touch-screen button ([BattleInput], NdsBattleTaps). The bag and the party are the game's own
 * screens, floating in over the battle.
 */

/** The menu's cursor and what it's showing, shared with the activity's keys. */
class DsBattleMenuState {
    /** 0 nothing up (the keys go to the game), 1 the actions, 2 the moves. */
    var mode by mutableIntStateOf(0)
        internal set
    /** FIGHT 0, BAG 1, POKéMON 2, RUN 3 (2 x 2: FIGHT BAG / POKéMON RUN). */
    var actionCursor by mutableIntStateOf(0)
    var moveCursor by mutableIntStateOf(0)

    internal var actionEnabled: (Int) -> Boolean = { true }
    internal var moveEnabled: (Int) -> Boolean = { true }
    internal var pickAction: (Int) -> Unit = {}
    internal var pickMove: (Int) -> Unit = {}
    internal var back: () -> Unit = {}

    /** A button while the menu is up: true = it's the menu's (kept from the game). */
    fun key(k: MenuKey): Boolean {
        val moves = mode == 2
        if (mode == 0) return false
        val c = if (moves) moveCursor else actionCursor
        fun to(n: Int) {
            if (n == c || moves && !moveEnabled(n)) return
            if (moves) moveCursor = n else actionCursor = n
        }
        when (k) {
            // Gen 3's grids don't wrap: UP / DOWN keep the column, LEFT / RIGHT the row.
            MenuKey.UP -> to(c % 2)
            MenuKey.DOWN -> to(2 + c % 2)
            MenuKey.LEFT -> to(c - c % 2)
            MenuKey.RIGHT -> to(c - c % 2 + 1)
            MenuKey.A -> if (moves) { if (moveEnabled(c)) pickMove(c) } else if (actionEnabled(c)) pickAction(c)
            MenuKey.B -> if (moves) back()
            else -> return false
        }
        return true
    }
}

/**
 * A game's look on the strip, with the rows it takes on the DS's top screen (of its 192, headless): [bandTop] where the band
 * starts, [textTop] where its message and buttons do. HeartGold / SoulSilver: the battle's black band from 144, the message
 * window 146 - 191. Platinum: its message window's black edge from 144, the window inside from 146. Black / White: the
 * message band's dark teal from 145, its red-brown lines at 147 and 188, the words from 149. Black 2 / White 2: their dark
 * blue band from 146, its lines at 147 - 148 and 187 - 188, the words from 149.
 */
enum class DsLook(val bandTop: Int, val textTop: Int) {
    HEARTGOLD(144, 146), SOULSILVER(144, 146), PLATINUM(144, 146), UNOVA(145, 149), UNOVA2(146, 149);

    companion object {
        fun of(game: GameKind?) = when (game) {
            GameKind.SOULSILVER -> SOULSILVER
            GameKind.PLATINUM -> PLATINUM
            GameKind.BLACK, GameKind.WHITE -> UNOVA
            GameKind.BLACK2, GameKind.WHITE2 -> UNOVA2
            else -> HEARTGOLD
        }
    }
}

/** The strip's band in [look]'s colours ([ds]: screen px a DS pixel; the strip starts at the look's band row). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBand(look: DsLook, ds: Float) {
    when (look) {
        DsLook.PLATINUM -> drawRect(PT_BAND)
        DsLook.UNOVA -> {
            // Black / White's message band: darker towards the bottom, a red-brown line and its shadow at either end.
            drawRect(UNOVA_BAND_TOP)
            drawRect(UNOVA_BAND, Offset(0f, size.height * 0.35f), Size(size.width, size.height * 0.65f))
            fun row(r: Int, c: Color) = drawRect(c, Offset(0f, (r - look.bandTop) * ds), Size(size.width, ds))
            row(147, UNOVA_LINE); row(148, UNOVA_LINE_SHADOW); row(187, UNOVA_LINE_SHADOW); row(188, UNOVA_LINE)
        }
        DsLook.UNOVA2 -> {
            // Black 2 / White 2's: dark blue, lighter in the middle, two lines at either end.
            drawRect(B2_BAND)
            drawRect(B2_BAND_MID, Offset(0f, size.height * 0.3f), Size(size.width, size.height * 0.4f))
            fun row(r: Int, c: Color) = drawRect(c, Offset(0f, (r - look.bandTop) * ds), Size(size.width, ds))
            row(147, B2_LINE_DARK); row(148, B2_LINE); row(187, B2_LINE_LOW); row(188, B2_LINE_LIGHT)
        }
        else -> drawRect(BAND)
    }
}

/**
 * The menu over the stage, for [snapshot]'s battle. [picture]: where the DS's top screen is in this box (px); [gameFills]:
 * the game's picture runs across the whole box (EXPAND carries the text box's rows into the margins), not just [picture];
 * [shown] false (no battle, BATTLE BUTTONS off, a double battle) = nothing, and [state] lets go of the keys; [active] false
 * (the floating touch screen is up) = the strip stays as it was, the keys the window's.
 */
@Composable
fun DsBattleMenu(
    snapshot: SnapshotView, state: DsBattleMenuState, battleInput: BattleInput?, picture: Rect?, shown: Boolean, active: Boolean = true,
    gameFills: Boolean = false,
    /** BATTLE HINTS / BATTLE INFO: the moves' multipliers. */
    hints: Boolean = true,
) {
    val input = snapshot.battleInputState
    val busy = rememberBattleInputBusy(battleInput)
    val mode = when {
        !shown || !active || picture == null || battleInput == null -> 0
        input == BATTLE_INPUT_ACTION_SELECT -> 1
        input == BATTLE_INPUT_MOVE_SELECT -> 2
        else -> 0
    }
    val moves = snapshot.battlePlayer.getOrNull(0)?.moves.orEmpty().take(4).map { if (hints) it else it.copy(vs = emptyList()) }
    // The game asks by the nickname: the battler's from the party (species, level and HP alike), else its species.
    val battler = snapshot.battlePlayer.getOrNull(0)?.let { b ->
        snapshot.party.firstOrNull { it.species == b.species && it.level == b.level && it.hp == b.hp }?.shownName ?: b.name
    }.orEmpty()
    val wild = snapshot.enemyParty.isEmpty()
    // A move (or RUN) picked: the strip goes at once, so it never covers what the game says next (a Discord report: it
    // stayed over the foe's next Pokémon). Back after a moment if the game said no (a move it won't let you use).
    var leaving by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(leaving) {
        if (leaving) { kotlinx.coroutines.delay(LEAVE_MS); leaving = false }
    }
    SideEffect {
        if (state.mode != mode) {
            // The moves open on the first; the actions keep their place, as the game's do.
            if (mode == 2) state.moveCursor = 0
            state.mode = mode
        }
        state.actionEnabled = { it != 3 || wild }
        state.moveEnabled = { moves.getOrNull(it)?.let { m -> m.pp > 0 } == true }
        state.pickAction = { if (!busy) { if (it == 3) leaving = true; battleInput?.selectAction(it) } }
        state.pickMove = { if (!busy) { leaving = true; battleInput?.selectMove(it) } }
        state.back = { if (!busy) battleInput?.back() }
    }
    // What the strip shows: the menu up, or - the keys the game's meanwhile - the last one held while the battle's bag or
    // party floats over it, and for a moment between two menus (FIGHT's panel sliding in), so the game's own text box
    // never flashes up in the middle.
    val held = remember { mutableIntStateOf(0) }
    val holding = input == BATTLE_INPUT_BAG_OPEN || input == BATTLE_INPUT_PARTY_OPEN
    androidx.compose.runtime.LaunchedEffect(mode, holding) {
        if (mode != 0) held.intValue = mode
        else if (!holding) { if (!leaving) kotlinx.coroutines.delay(HOLD_MS); held.intValue = 0 }
    }
    val shownMode = if (leaving) 0 else if (mode != 0) mode else if (shown && picture != null) held.intValue else 0
    if (shownMode == 0 || picture == null) return
    val idle = busy || mode == 0
    val look = DsLook.of(snapshot.game)
    val density = LocalDensity.current
    val m = rememberGbaTextMetrics(TEXT_SCALE)
    val u = rememberGbaTextMetrics().u
    // HeartGold's cursor breathes out and back a pixel at a time.
    val pulse by rememberInfiniteTransition(label = "dsCursor").animateFloat(
        0f, PULSE_PX + 0.99f, infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Reverse), label = "dsCursorPulse",
    )
    val step = kotlin.math.floor(pulse)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = with(density) { maxWidth.toPx() }
        val ds = picture.height / 192f
        val top = picture.top + look.bandTop * ds
        val bottom = picture.bottom
        val textTop = picture.top + look.textTop * ds
        val gap = with(density) { (u * 3).toPx() }
        val buttonsW = (picture.width * BUTTONS_WIDTH).coerceAtMost(boxW / 2)
        val buttonsL = boxW - gap - buttonsW
        // Black 2 / White 2's band is glass (the scene shows through it: BLDALPHA's 16 + 7/16), which no flat colour can
        // be: there the game's own band shows - its message too, in its own words and font, while the buttons leave it
        // room - and the strip draws the band only where the game's picture doesn't reach (the bars beside a 4:3 game).
        val glass = look == DsLook.UNOVA2 && (shownMode == 2 || buttonsL >= picture.left + picture.width * GAME_MESSAGE_END)
        val gameL = if (gameFills) 0f else picture.left
        val gameR = if (gameFills) boxW else picture.right
        // The strip: the band's black across the screen, under everything else here (taps on it are the menu's, not the
        // game's).
        with(density) {
            Box(
                Modifier.offset(y = top.toDp()).size(maxWidth, (bottom - top).toDp())
                    .drawBehind {
                        if (!glass) drawBand(look, ds)
                        else {
                            if (gameL > 0f) clipRect(right = gameL) { drawBand(look, ds) }
                            if (gameR < size.width) clipRect(left = gameR) { drawBand(look, ds) }
                        }
                    }
                    .clickable(remember { MutableInteractionSource() }, null) {},
            )
        }
        @Composable
        fun Area(l: Float, r: Float, content: @Composable () -> Unit) {
            with(density) {
                Box(Modifier.offset(x = l.toDp(), y = textTop.toDp()).size((r - l).toDp(), (bottom - textTop - ds).toDp())) { content() }
            }
        }
        if (shownMode == 1) {
            // The buttons at the strip's right end, the message window filling the rest from the far left.
            if (!glass) Area(2 * ds, buttonsL - gap) {
                when (look) {
                    DsLook.PLATINUM -> PtMessageWindow(tr("What will {0} do?", battler), ds)
                    DsLook.UNOVA -> UnovaMessage(tr("What will {0} do?", battler), ds)
                    DsLook.UNOVA2 -> UnovaMessage(tr("What will {0} do?", battler), ds, B2_TEXT_SHADOW)
                    else -> DsMessageWindow(tr("What will {0} do?", battler), m, ds, silver = look == DsLook.SOULSILVER)
                }
            }
            Area(buttonsL, boxW - gap) {
                Grid(look, state.actionCursor, u, step) { i, on ->
                    val enabled = state.actionEnabled(i) && !idle
                    DsActionButton(look, i, enabled, m, u, Modifier.fillMaxSize()) {
                        state.actionCursor = i
                        state.pickAction(i)
                    }
                }
            }
        } else {
            // The moves across the strip, as Gen 3's take the text box; HeartGold's cards. BACK for a finger at the end.
            Area(gap, boxW - gap) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                    // (Over the game's band, the gaps between the cards in its colour: its message stays under them.)
                    Box(Modifier.weight(1f).fillMaxHeight().then(if (glass) Modifier.background(B2_BAND) else Modifier)) {
                        Grid(look, state.moveCursor, u, step) { i, on ->
                            val move = moves.getOrNull(i)
                            val enabled = state.moveEnabled(i) && !idle
                            val pick = { state.moveCursor = i; state.pickMove(i) }
                            when (look) {
                                DsLook.PLATINUM -> PtMoveCard(move, enabled, ds, Modifier.fillMaxSize(), pick)
                                DsLook.UNOVA -> UnovaMoveCard(move, enabled, ds, Modifier.fillMaxSize(), pick)
                                DsLook.UNOVA2 -> B2MoveCard(move, enabled, ds, Modifier.fillMaxSize(), pick)
                                else -> DsMoveCard(move, enabled, ds, Modifier.fillMaxSize(), pick)
                            }
                        }
                    }
                    // The game's own way back: its button with the return arrow, as tall as the two rows of moves.
                    val back = Modifier.fillMaxHeight().aspectRatio(BACK_ASPECT)
                    when (look) {
                        DsLook.PLATINUM -> PtBackButton(!idle, ds, back) { state.back() }
                        DsLook.UNOVA -> UnovaBackButton(!idle, ds, back) { state.back() }
                        DsLook.UNOVA2 -> B2BackButton(!idle, ds, back) { state.back() }
                        else -> DsBackButton(!idle, ds, back) { state.back() }
                    }
                }
            }
        }
    }
}

/** HeartGold's battle message window (sampled headless): a dark outline, a gold line lit top-left, a slate frame, white
 * inside, its words dark grey over a light shadow; SoulSilver's ([silver]) is the same with that line silver-blue.
 * [ds]: screen px a DS pixel. */
@Composable
private fun DsMessageWindow(text: String, m: GbaTextMetrics, ds: Float, silver: Boolean = false) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            val w = size.width
            val h = size.height
            fun rect(c: Color, inset: Float, inB: Float = inset) =
                drawRect(c, androidx.compose.ui.geometry.Offset(inset * ds, inset * ds), androidx.compose.ui.geometry.Size(w - (inset + inB) * ds, h - (inset + inB) * ds))
            drawPixelRoundRect(MSG_OUTLINE, radius = 2 * ds, step = ds)
            rect(if (silver) MSG_SILVER_DARK else MSG_GOLD_DARK, 2f)
            rect(if (silver) MSG_SILVER else MSG_GOLD, 2f, 3f)
            rect(MSG_SLATE, 3f)
            drawRect(MSG_FILL, androidx.compose.ui.geometry.Offset(8 * ds, 4 * ds), androidx.compose.ui.geometry.Size(w - 16 * ds, h - 9 * ds))
        }.padding(start = with(LocalDensity.current) { (14 * ds).toDp() }, end = with(LocalDensity.current) { (10 * ds).toDp() },
            top = with(LocalDensity.current) { (MSG_TEXT_TOP * ds).toDp() }),
        contentAlignment = Alignment.TopStart,
    ) { GbaText(text, MSG_TEXT, MSG_SHADOW, dsTextMetrics(ds, 1f), maxLines = 2) }
}

/** Text at a DS font's size: Pixel Operator's capitals are 9 font pixels, as the DS games' are 9 DS pixels, so a font pixel is
 * [scale] DS pixels, rounded to whole screen pixels ([ds]: screen px a DS pixel). */
@Composable
private fun dsTextMetrics(ds: Float, scale: Float): GbaTextMetrics {
    val density = LocalDensity.current
    return remember(density, ds, scale) {
        with(density) {
            val k = kotlin.math.round(20.dp.toPx() / 16f).toInt().coerceAtLeast(2)
            val per = kotlin.math.round(ds * scale).toInt().coerceAtLeast(1)
            val fontPx = 16f * per
            GbaTextMetrics(k, k.toDp(), fontPx.toSp(), per.toFloat(), fontPx.toDp(), (fontPx * 1.25f).toDp())
        }
    }
}

/** The message's first line: its letters' tops 9 DS pixels under the window's (the game's, headless), less the font's own
 * room above its capitals. */
private const val MSG_TEXT_TOP = 5

/** The menus' text, against the companion's. */
private const val TEXT_SCALE = 1.3f

/** The buttons' share of the DS top screen's width (at most half the strip). */
private const val BUTTONS_WIDTH = 0.46f

/** Where Black 2 / White 2's "What will ... do?" ends on the DS top screen, a long nickname's (its share of the width):
 * buttons left of it would cover the game's own message, so the strip draws its own then. */
private const val GAME_MESSAGE_END = 0.62f

/** How long the strip stays after its menu goes (not to the bag / party): FIGHT's panel slides in meanwhile. */
private const val HOLD_MS = 300L
/** How long a picked strip stays away with the game still on its menu. */
private const val LEAVE_MS = 1000L

/** The DS cursors' pulse in whole pixels (0 .. [PULSE_PX]), breathing out and back: for the companion's battle buttons. */
@Composable
internal fun rememberDsCursorPulse(max: Int = PULSE_PX): Float {
    val pulse by rememberInfiniteTransition(label = "dsCursor").animateFloat(
        0f, max + 0.99f, infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Reverse), label = "dsCursorPulse",
    )
    return kotlin.math.floor(pulse)
}

/** The cursor's pulse: out this many GBA pixels and back over [PULSE_MS] each way. */
private const val PULSE_PX = 2
private const val PULSE_MS = 420

/** A DS battle button's two halves (HeartGold / SoulSilver's battle panel, sampled headless: light over dark). */
private class DsButtonColors(val top: Color, val bottom: Color)
private val ACTIONS = listOf(
    tk("FIGHT") to DsButtonColors(Color(0xFFEB3838), Color(0xFF8A4141)),
    tk("BAG") to DsButtonColors(Color(0xFFE39A28), Color(0xFF8A6938)),
    tk("POKéMON") to DsButtonColors(Color(0xFF59B228), Color(0xFF517141)),
    tk("RUN") to DsButtonColors(Color(0xFF2892CB), Color(0xFF415971)),
)
/** Their outline, the white rim inside it, the labels' cream and the cursor's red (the battle bag's corners). */
private val OUTLINE = Color(0xFF414141)
private val RIM = Color(0xFFFBFBFB)
private val LABEL = Color(0xFFFBF3E3)
private val CURSOR = Color(0xFFE83020)
private val CARD_FILL = Color(0xFFFBFBFB)
private val CARD_TEXT = Color(0xFF383838)
private val CARD_TEXT_SHADOW = Color(0xFF8A8A8A)
private val CARD_NAME = Color(0xFF797979)
private val CARD_NAME_SHADOW = Color(0xFFE3E3E3)
private val CARD_OUTLINE = Color(0xFF303030)
private val CARD_RIM = Color(0xFFFBFBFB)
private val CARD_TAN = Color(0xFFCBBAA2)
private val CARD_TAN_LINE = Color(0xFFE3DBC3)
/** The return button's colours. */
private val BACK_OUTLINE = Color(0xFF414141)
private val BACK_RIM = Color(0xFFFBF3E3)
private val BACK_EDGE = Color(0xFF1869B2)
private val BACK_LIGHT = Color(0xFF49AAFB)
private val BACK_MID = Color(0xFF3892E3)
private val BACK_LOW = Color(0xFF2882CB)
/** The back button's width against its height (a whole square read too big beside the moves). */
private const val BACK_ASPECT = 0.72f

/** A move button's name and its small print (type, PP, multiplier), in DS pixels a font pixel. */
private const val NAME_SCALE = 0.75f
private const val SMALL_SCALE = 0.5f
/** The battle's band and its message window's colours. */
private val BAND = Color(0xFF202020)
private val MSG_OUTLINE = Color(0xFF494141)
private val MSG_GOLD = Color(0xFFFBDB69)
private val MSG_GOLD_DARK = Color(0xFFEBA238)
private val MSG_SILVER = Color(0xFFDBEBFB)
private val MSG_SILVER_DARK = Color(0xFFAABADB)
private val MSG_SLATE = Color(0xFF616169)
private val MSG_FILL = Color(0xFFFBFBFB)
private val MSG_TEXT = Color(0xFF515159)
private val MSG_SHADOW = Color(0xFFA2A2AA)

/** Two rows of two, a little apart; [cell] draws cell i (0 1 / 2 3), [cursor]'s with [look]'s cursor [pulse] px out. */
@Composable
private fun Grid(look: DsLook, cursor: Int, u: Dp, pulse: Float, cell: @Composable (Int, Boolean) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(u * 3)) {
        for (row in 0..1) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
            for (col in 0..1) {
                val i = row * 2 + col
                val corners = when (look) {
                    DsLook.PLATINUM -> Modifier.ptCursor(i == cursor, u, pulse)
                    DsLook.UNOVA -> Modifier.unovaCursor(i == cursor, u, pulse)
                    DsLook.UNOVA2 -> Modifier.b2Cursor(i == cursor, u, pulse)
                    else -> Modifier.cursorCorners(i == cursor, u, pulse)
                }
                Box(Modifier.weight(1f).fillMaxHeight().zIndex(if (i == cursor) 1f else 0f).then(corners)) {
                    cell(i, i == cursor)
                }
            }
        }
    }
}

/** HeartGold's red corner brackets round the cursor's button, thick, in a white rim, [pulse] GBA pixels out from its edges. */
private fun Modifier.cursorCorners(on: Boolean, u: Dp, pulse: Float) = if (!on) this else drawWithContent {
    drawContent()
    // The strip's buttons are a quarter of the game's height: its brackets a little lighter.
    drawHgCursor(u.toPx(), pulse, thick = 3, arm = 9)
}

/** [cursorCorners]' brackets round this box, [px] a DS pixel (HeartGold's, headless: arms 10 long and 4 thick in a 1 px white
 * rim, 1 px out from the button and breathing a pixel further): also the companion's GEN 4 battle buttons' cursor. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHgCursor(
    px: Float, pulse: Float, thick: Int = 4, arm: Int = 10, insideOf: Int = -1,
) {
    // [insideOf] >= 0: the companion's buttons, whose brackets must stay in the button's own box (past it they ran over
    // the tab's edge and the next button): the rim's outside on the box's edge at the pulse's widest ([insideOf]).
    val rim = px
    val out = if (insideOf >= 0) (pulse - insideOf) * px - rim else (1 + pulse) * px
    val t = thick * px
    val l = arm * px
    val w = size.width
    val h = size.height
    for ((cx, cy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        // The corner's outer point, then its two arms running in along the edges.
        val x = if (cx < 0) -out else w + out
        val y = if (cy < 0) -out else h + out
        val armX = androidx.compose.ui.geometry.Rect(if (cx < 0) x else x - l, if (cy < 0) y else y - t, if (cx < 0) x + l else x, if (cy < 0) y + t else y)
        val armY = androidx.compose.ui.geometry.Rect(if (cx < 0) x else x - t, if (cy < 0) y else y - l, if (cx < 0) x + t else x, if (cy < 0) y + l else y)
        for (a in listOf(armX, armY)) drawRect(RIM, a.topLeft - androidx.compose.ui.geometry.Offset(rim, rim), androidx.compose.ui.geometry.Size(a.width + 2 * rim, a.height + 2 * rim))
        for (a in listOf(armX, armY)) drawRect(CURSOR, a.topLeft, a.size)
    }
}

/** Action [i] (FIGHT, BAG, POKéMON, RUN) as [look]'s button. */
@Composable
private fun DsActionButton(look: DsLook, i: Int, enabled: Boolean, m: GbaTextMetrics, u: Dp, modifier: Modifier, onClick: () -> Unit) {
    val label = ACTIONS[i].first
    when (look) {
        DsLook.PLATINUM -> {
            val c = PT_ACTIONS[i]
            Box(
                modifier.alpha(if (enabled) 1f else 0.45f)
                    .drawBehind { drawPtButton(c, u.toPx()) }
                    .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)
                    .padding(horizontal = u * 5),
                contentAlignment = Alignment.Center,
            ) { FittedGbaText(tr(label), PT_LABEL, c.shadow, m) }
        }
        DsLook.UNOVA -> Box(
            modifier.alpha(if (enabled) 1f else 0.45f)
                .drawBehind { drawUnovaPlate(u.toPx()) }
                .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)
                .padding(horizontal = u * 4),
            contentAlignment = Alignment.Center,
        ) { FittedGbaText(tr(label), UNOVA_ACTIONS[i], UNOVA_LABEL_OUTLINE, m) }
        DsLook.UNOVA2 -> Box(
            modifier.alpha(if (enabled) 1f else 0.45f)
                .drawBehind { drawB2Plate(B2_ACTIONS[i].first, B2_ACTIONS[i].second, u.toPx()) }
                .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)
                .padding(horizontal = u * 4),
            contentAlignment = Alignment.Center,
        ) { FittedGbaText(tr(label), B2_ACTIONS[i].first, B2_ACTIONS[i].second, m) }
        else -> DsButton(label, ACTIONS[i].second, enabled, m, u, modifier, onClick)
    }
}

/** A HeartGold battle button: dark outline with pixel corners, a white rim, its colour light over dark, the label in cream. */
@Composable
private fun DsButton(
    label: String, colors: DsButtonColors, enabled: Boolean, m: GbaTextMetrics, u: Dp, modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f)
            .drawBehind { drawDsButton(colors.top, colors.bottom, u.toPx()) }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = u * 4),
        contentAlignment = Alignment.Center,
    ) { FittedGbaText(tr(label), LABEL, OUTLINE, m) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDsButton(top: Color, bottom: Color, px: Float) {
    drawPixelRoundRect(OUTLINE, radius = 5 * px, step = px)
    drawPixelRoundRect(RIM, androidx.compose.ui.geometry.Offset(2 * px, 2 * px), androidx.compose.ui.geometry.Size(size.width - 4 * px, size.height - 4 * px), radius = 4 * px, step = px)
    val inner = androidx.compose.ui.geometry.Size(size.width - 6 * px, size.height - 6 * px)
    drawPixelRoundRect(bottom, androidx.compose.ui.geometry.Offset(3 * px, 3 * px), inner, radius = 3 * px, step = px)
    val half = kotlin.math.floor(inner.height / 2 / px) * px
    drawPixelRoundRect(top, androidx.compose.ui.geometry.Offset(3 * px, 3 * px), androidx.compose.ui.geometry.Size(inner.width, half + 3 * px), radius = 3 * px, step = px)
    drawRect(bottom, androidx.compose.ui.geometry.Offset(3 * px, 3 * px + half), androidx.compose.ui.geometry.Size(inner.width, inner.height - half))
}

/** HeartGold's move button (sampled headless), at the strip's size: a dark outline and white rim round a thick frame in the
 * move's type colour - bright over darker, its inner edge lit - a white top with the name in the middle, a tan bottom with
 * the type and PP; [ds] screen px a DS pixel, every part a whole number of them. Top right, its multiplier on the foe. */
@Composable
private fun DsMoveCard(move: MoveView?, enabled: Boolean, ds: Float, modifier: Modifier, onClick: () -> Unit) {
    val type = move?.let { com.pokedaisy.app.companion.ui.theme.AppColors.typeColors[it.type] } ?: Color(0xFF8A8A8A)
    val d = kotlin.math.floor(ds).coerceAtLeast(1f)
    val density = LocalDensity.current
    fun dp(n: Float) = with(density) { (n * d).toDp() }
    Box(
        modifier.alpha(if (move != null && enabled) 1f else 0.45f)
            .drawBehind {
                val w = size.width
                val h = size.height
                fun box(c: Color, l: Float, t: Float, r: Float, b: Float, radius: Float = 0f) =
                    if (radius > 0f) drawPixelRoundRect(c, Offset(l * d, t * d), Size(w - (l + r) * d, h - (t + b) * d), radius = radius * d, step = d)
                    else drawRect(c, Offset(l * d, t * d), Size(w - (l + r) * d, h - (t + b) * d))
                // (In DS pixels from the card's edge, as the game's at the sides: outline, white rim, the frame's dark line,
                // its colour 4 wide, its lit inner edge, the inside from 8 / 7. Top and bottom a pixel of each: a button here
                // is 21 DS pixels tall, the game's 52, and the name and the small print need the room.)
                val split = kotlin.math.floor((h / d) * 0.56f)
                box(CARD_OUTLINE, 0f, 0f, 0f, 0f, 3f)
                box(CARD_RIM, 1f, 1f, 1f, 1f, 2f)
                box(lerp(type, Color.Black, 0.55f), 2f, 1f, 2f, 1f, 1f)
                box(type, 3f, 1f, 3f, 1f)
                drawRect(lerp(type, Color.Black, 0.3f), Offset(3 * d, split * d), Size(w - 6 * d, h - split * d - d))
                box(lerp(type, Color.White, 0.45f), 7f, 2f, 7f, 2f)
                box(CARD_FILL, 8f, 3f, 7f, 2f)
                drawRect(CARD_TAN_LINE, Offset(8 * d, split * d), Size(w - 15 * d, d))
                drawRect(CARD_TAN, Offset(8 * d, (split + 1) * d), Size(w - 15 * d, h - (split + 3) * d))
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = move != null && enabled, onClick = onClick)
            .padding(start = dp(9f), end = dp(8f), top = dp(3f), bottom = dp(2f)),
    ) { if (move != null) MoveCardText(move, ds, d, CARD_NAME, CARD_NAME_SHADOW, CARD_TEXT, CARD_TEXT_SHADOW) }
}

/** The game's square way back (its battle bag's, sampled headless): a dark outline, a cream rim, blue shaded light to dark
 * downwards, the cream return arrow in the middle. */
@Composable
private fun DsBackButton(enabled: Boolean, ds: Float, modifier: Modifier, onClick: () -> Unit) {
    val d = kotlin.math.floor(ds).coerceAtLeast(1f)
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f)
            .drawBehind {
                val w = size.width
                val h = size.height
                drawPixelRoundRect(BACK_OUTLINE, radius = 4 * d, step = d)
                drawPixelRoundRect(BACK_RIM, Offset(2 * d, 2 * d), Size(w - 4 * d, h - 4 * d), radius = 3 * d, step = d)
                drawPixelRoundRect(BACK_EDGE, Offset(3 * d, 3 * d), Size(w - 6 * d, h - 6 * d), radius = 2 * d, step = d)
                // The blue: light over the top half, then three darker bands to the edge's own blue.
                val innerTop = 4 * d
                val innerH = h - 8 * d
                val half = kotlin.math.floor(innerH / d / 2f) * d
                drawRect(BACK_LIGHT, Offset(4 * d, innerTop), Size(w - 8 * d, half))
                drawRect(BACK_MID, Offset(4 * d, innerTop + half), Size(w - 8 * d, 2 * d))
                drawRect(BACK_LOW, Offset(4 * d, innerTop + half + 2 * d), Size(w - 8 * d, 2 * d))
                // The arrow, a pixel of its drawing to a DS pixel, as big as fits.
                val cell = kotlin.math.floor(minOf(w, h) * 0.5f / BACK_ARROW[0].length / d).coerceAtLeast(1f) * d
                val aw = BACK_ARROW[0].length * cell
                val ah = BACK_ARROW.size * cell
                val x0 = kotlin.math.floor((w - aw) / 2f / d) * d
                val y0 = kotlin.math.floor((h - ah) / 2f / d) * d
                BACK_ARROW.forEachIndexed { row, bits ->
                    bits.forEachIndexed { col, c -> if (c == '#') drawRect(BACK_RIM, Offset(x0 + col * cell, y0 + row * cell), Size(cell, cell)) }
                }
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick),
    )
}

/** The battle bag's return arrow, cream on blue (headless, x 226 - 244, y 164 - 181 of its touch screen). */
private val BACK_ARROW = listOf(
    "...............####",
    "...............####",
    "...............####",
    "...............####",
    "...............####",
    "...............####",
    "....##.........####",
    "....###........####",
    "...####........####",
    "..#####........####",
    ".##################",
    "###################",
    "##################.",
    ".################..",
    "..#####............",
    "...####............",
    "....###............",
    ".....##............",
)

/** Platinum's red corner brackets (its battle panel's cursor, headless: 2 pixels thick, no rim), [pulse] px out. */
private fun Modifier.ptCursor(on: Boolean, u: Dp, pulse: Float) = if (!on) this else drawWithContent {
    drawContent()
    val px = u.toPx()
    val out = (1 + pulse) * px
    val t = 2 * px
    val l = 8 * px
    for ((cx, cy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        val x = if (cx < 0) -out else size.width + out
        val y = if (cy < 0) -out else size.height + out
        drawRect(PT_CURSOR, Offset(if (cx < 0) x else x - l, if (cy < 0) y else y - t), Size(l, t))
        drawRect(PT_CURSOR, Offset(if (cx < 0) x else x - t, if (cy < 0) y else y - l), Size(t, l))
    }
}

/** Black / White's cursor: a magenta quarter ring at each corner, white outside it (their battle panel's, headless),
 * [pulse] px out. Drawn a pixel at a time, so its curve steps like the game's. */
private fun Modifier.unovaCursor(on: Boolean, u: Dp, pulse: Float) = if (!on) this else drawWithContent {
    drawContent()
    drawUnovaCursor(u.toPx(), pulse, r = 9)
}

/** [unovaCursor]'s rings round a [box] at this scope's origin, [px] a game pixel: Black's, headless, are 3 thick on a
 * radius of 13 and breathe 2 px out. Also the companion's GEN 5 battle screen's cursor. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUnovaCursor(px: Float, pulse: Float, box: Size = size, r: Int = 13) {
    val out = (1 + pulse) * px
    for ((cx, cy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        // The ring's centre r pixels in from the corner's outer point, the ring r - 3 .. r from it (r + 1: the rim).
        val ox = if (cx < 0) -out else box.width + out
        val oy = if (cy < 0) -out else box.height + out
        for (j in 0..r) for (k in 0..r) {
            val dx = r - j + 0.5f
            val dy = r - k + 0.5f
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val c = when {
                d > r + 1 -> continue
                d > r -> UNOVA_CURSOR_RIM
                d > r - 3 -> UNOVA_CURSOR
                else -> continue
            }
            val x = if (cx < 0) ox + j * px else ox - (j + 1) * px
            val y = if (cy < 0) oy + k * px else oy - (k + 1) * px
            drawRect(c, Offset(x, y), Size(px, px))
        }
    }
}

/** A Platinum battle button's colours (headless): its face, the dark / middle / light bands down its sides, the lit strip
 * along its top, and the words' shadow. */
private class PtButtonColors(val face: Color, val dark: Color, val mid: Color, val light: Color, val lit: Color, val shadow: Color)
private val PT_ACTIONS = listOf(
    PtButtonColors(Color(0xFFEB3838), Color(0xFF8A4141), Color(0xFFAA4141), Color(0xFFC34141), Color(0xFFFB9AAA), Color(0xFF592020)),
    PtButtonColors(Color(0xFFE39A28), Color(0xFF8A6938), Color(0xFFAA7930), Color(0xFFCB8A28), Color(0xFFFBEB8A), Color(0xFF614100)),
    PtButtonColors(Color(0xFF59B228), Color(0xFF517141), Color(0xFF498230), Color(0xFF499228), Color(0xFFB2EB8A), Color(0xFF204918)),
    PtButtonColors(Color(0xFF2892CB), Color(0xFF415971), Color(0xFF38698A), Color(0xFF3079AA), Color(0xFFB2E3FB), Color(0xFF102059)),
)
private val PT_BAND = Color(0xFF000000)
private val PT_OUTLINE = Color(0xFF303030)
private val PT_EDGE = Color(0xFF000000)
private val PT_WHITE = Color(0xFFFBFBFB)
private val PT_LABEL = Color(0xFFFBFBFB)
private val PT_CURSOR = Color(0xFFFB0000)
private val PT_FRAME = Color(0xFF283028)
private val PT_BROWN = Color(0xFF927159)
private val PT_BROWN_LIGHT = Color(0xFFB2A292)
private val PT_BROWN_PALE = Color(0xFFDBD3CB)
private val PT_CARD = Color(0xFFF3EBAA)
private val PT_CARD_EDGE = Color(0xFFE3D369)
private val PT_CARD_TEXT = Color(0xFF383830)
private val PT_CARD_SHADOW = Color(0xFFCBC38A)

/** Platinum's battle button ([px]: a GBA pixel): a dark outline and black edge, the face with darker bands down its sides,
 * a white line and its lit strip along the top, a darker line at the bottom. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPtButton(c: PtButtonColors, px: Float) {
    val w = size.width
    val h = size.height
    drawPixelRoundRect(PT_OUTLINE, radius = 2 * px, step = px)
    drawRect(PT_EDGE, Offset(2 * px, 2 * px), Size(w - 4 * px, h - 4 * px))
    val l = 3 * px
    val t = 3 * px
    val iw = w - 6 * px
    val ih = h - 6 * px
    drawRect(c.face, Offset(l, t), Size(iw, ih))
    for ((k, col) in listOf(c.dark, c.dark, c.dark, c.mid, c.light).withIndex()) {
        drawRect(col, Offset(l + k * px, t), Size(px, ih))
        drawRect(col, Offset(l + iw - (k + 1) * px, t), Size(px, ih))
    }
    drawRect(PT_WHITE, Offset(l, t), Size(iw, px))
    drawRect(c.lit, Offset(l, t + px), Size(iw, 2 * px))
    drawRect(c.mid, Offset(l, t + 3 * px), Size(iw, px))
    drawRect(c.mid, Offset(l, t + ih - px), Size(iw, px))
}

/** Platinum's battle message window (headless): its black edge (the band), a white line, a dark frame, brown down the left
 * edge and a wide brown band on the right, white inside; the words HeartGold's grey. [ds]: screen px a DS pixel. */
@Composable
private fun PtMessageWindow(text: String, ds: Float) {
    val d = kotlin.math.floor(ds).coerceAtLeast(1f)
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize().drawBehind {
            val w = size.width
            val h = size.height
            drawRect(PT_WHITE)
            drawRect(PT_FRAME, Offset(d, d), Size(w - 2 * d, h - 2 * d))
            drawRect(PT_WHITE, Offset(3 * d, 3 * d), Size(w - 6 * d, h - 6 * d))
            for ((k, col) in listOf(PT_BROWN, PT_BROWN, PT_BROWN, PT_BROWN, PT_BROWN_LIGHT, PT_BROWN_PALE, PT_BROWN_PALE).withIndex())
                drawRect(col, Offset((3 + k) * d, 3 * d), Size(d, h - 6 * d))
            val right = 13 * d
            drawRect(PT_BROWN, Offset(w - 3 * d - right, 3 * d), Size(right, h - 6 * d))
            drawRect(PT_BROWN_LIGHT, Offset(w - 3 * d - right - d, 3 * d), Size(d, h - 6 * d))
            drawRect(PT_BROWN_PALE, Offset(w - 3 * d - right - 3 * d, 3 * d), Size(2 * d, h - 6 * d))
        }.padding(start = with(density) { (14 * ds).toDp() }, end = with(density) { (20 * ds).toDp() },
            top = with(density) { (MSG_TEXT_TOP * ds).toDp() }),
        contentAlignment = Alignment.TopStart,
    ) { GbaText(text, MSG_TEXT, MSG_SHADOW, dsTextMetrics(ds, 1f), maxLines = 2) }
}

/** Platinum's move button (headless), at the strip's size: the buttons' outline round a frame in the move's type colour -
 * dark down the sides, lit inside - a cream inside with the name over the type and PP. */
@Composable
private fun PtMoveCard(move: MoveView?, enabled: Boolean, ds: Float, modifier: Modifier, onClick: () -> Unit) {
    val type = move?.let { com.pokedaisy.app.companion.ui.theme.AppColors.typeColors[it.type] } ?: Color(0xFF8A8A8A)
    val d = kotlin.math.floor(ds).coerceAtLeast(1f)
    val density = LocalDensity.current
    fun dp(n: Float) = with(density) { (n * d).toDp() }
    Box(
        modifier.alpha(if (move != null && enabled) 1f else 0.45f)
            .drawBehind {
                val w = size.width
                val h = size.height
                // (In DS pixels from the card's edge, the sides as the game's - outline, black, the type's dark band, its
                // lit edge, the cream - top and bottom a pixel of each, as HeartGold's: the card is 21 DS pixels tall.)
                fun box(c: Color, l: Float, t: Float, r: Float, b: Float) = drawRect(c, Offset(l * d, t * d), Size(w - (l + r) * d, h - (t + b) * d))
                drawPixelRoundRect(PT_OUTLINE, radius = 2 * d, step = d)
                box(PT_EDGE, 1f, 1f, 1f, 1f)
                box(lerp(type, Color.Black, 0.45f), 2f, 1f, 2f, 1f)
                box(lerp(type, Color.Black, 0.2f), 4f, 1f, 4f, 1f)
                box(type, 5f, 1f, 5f, 1f)
                box(PT_CARD_EDGE, 7f, 2f, 7f, 2f)
                box(PT_CARD, 8f, 2f, 8f, 2f)
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = move != null && enabled, onClick = onClick)
            .padding(start = dp(9f), end = dp(9f), top = dp(3f), bottom = dp(2f)),
    ) { if (move != null) MoveCardText(move, ds, d, PT_CARD_TEXT, PT_CARD_SHADOW, PT_CARD_TEXT, Color.Transparent) }
}

/** A move card's words, as HeartGold's: the name centred and bold, its multiplier on the foe top right; the type chip and
 * the PP under it. */
@Composable
private fun MoveCardText(move: MoveView, ds: Float, d: Float, name: Color, nameShadow: Color, small: Color, smallShadow: Color) {
    val density = LocalDensity.current
    fun dp(n: Float) = with(density) { (n * d).toDp() }
    val type = com.pokedaisy.app.companion.ui.theme.AppColors.typeColors[move.type] ?: Color(0xFF8A8A8A)
    val smallM = dsTextMetrics(ds, SMALL_SCALE)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(0.56f), contentAlignment = Alignment.Center) {
            // At its whole line's height, centred on the panel: the line (16 font pixels) is taller than the panel, and
            // text squeezed into less is clipped - the letters (9) fit, the rest of the line is empty. Bold, as the
            // games' heavy letters. (Up a DS pixel: the font's capitals sit low in its line, so centred they met the
            // bottom half; up 2 left them too close to the card's top - the user's call.)
            FittedGbaText(
                move.name, name, nameShadow, dsTextMetrics(ds, NAME_SCALE),
                Modifier.wrapContentHeight(unbounded = true).offset(y = -dp(1f)), bold = true,
            )
            // The multiplier on the foe, as a number alone (x2, x0.5...): coloured as battle INFO's verdicts.
            move.vs.firstOrNull()?.let { v ->
                Box(Modifier.align(Alignment.TopEnd).background(verdictColor(v.pct), PixelRoundedShape(dp(1f))).padding(horizontal = dp(1f))) {
                    GbaText(v.label, Color.White, Color.Transparent, smallM)
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(0.44f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.background(type, PixelRoundedShape(dp(1f))).drawBehind {
                    drawRect(lerp(type, Color.White, 0.45f), Offset(0f, 0f), Size(size.width, d))
                }.padding(horizontal = dp(2f)),
            ) { GbaText(typeLabel(move.type), Color.White, lerp(type, Color.Black, 0.45f), smallM) }
            Spacer(Modifier.weight(1f))
            GbaText(tr("PP {0}", move.pp), small, smallShadow, smallM)
        }
    }
}

/** Platinum's way back in its blue (its move panel's CANCEL), the return arrow in white. */
@Composable
private fun PtBackButton(enabled: Boolean, ds: Float, modifier: Modifier, onClick: () -> Unit) {
    val d = kotlin.math.floor(ds).coerceAtLeast(1f)
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f)
            .drawBehind {
                drawPtButton(PT_ACTIONS[3], d)
                drawArrow(PT_WHITE, PT_ACTIONS[3].shadow, d)
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick),
    )
}

/** Black / White's message (and their sequels'): no window, the words white over the band with a pale shadow. */
@Composable
private fun UnovaMessage(text: String, ds: Float, shadow: Color = UNOVA_TEXT_SHADOW) {
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize().padding(start = with(density) { (6 * ds).toDp() }, top = with(density) { (2 * ds).toDp() }),
        contentAlignment = Alignment.TopStart,
    ) { GbaText(text, UNOVA_TEXT, shadow, dsTextMetrics(ds, 1f), maxLines = 2) }
}

/** Black / White's battle plate ([px]: a GBA pixel): a white rim between a grey shade and a darker one, its face charcoal
 * dotted in a lighter grey (2 x 2 dots every 5 pixels), darker towards the bottom. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUnovaPlate(px: Float) {
    val w = size.width
    val h = size.height
    drawPixelRoundRect(UNOVA_SHADE, radius = 4 * px, step = px)
    drawPixelRoundRect(UNOVA_RIM, Offset(px, px), Size(w - 2 * px, h - 2 * px), radius = 3 * px, step = px)
    drawPixelRoundRect(UNOVA_RIM_EDGE, Offset(3 * px, 3 * px), Size(w - 6 * px, h - 6 * px), radius = 2 * px, step = px)
    drawUnovaFace(Offset(4 * px, 4 * px), Size(w - 8 * px, h - 8 * px), px)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUnovaFace(o: Offset, s: Size, px: Float) {
    drawPixelRoundRect(UNOVA_FACE, o, s, radius = px, step = px)
    // The dots, kept inside the face's rounded corners.
    val cols = (s.width / px).toInt()
    val rows = (s.height / px).toInt()
    var y = 1
    while (y + 1 < rows - 1) {
        var x = 1
        while (x + 1 < cols - 1) {
            drawRect(UNOVA_DOT, Offset(o.x + x * px, o.y + y * px), Size(2 * px, 2 * px))
            x += 5
        }
        y += 5
    }
    // Darker towards the bottom, a step each.
    val shade = kotlin.math.floor(s.height / px / 8f).coerceAtLeast(1f) * px
    for ((k, c) in listOf(UNOVA_FACE_LOW, UNOVA_FACE_LOWER, UNOVA_FACE_LOWEST).withIndex())
        drawRect(c, Offset(o.x + px, o.y + s.height - (3 - k) * shade), Size(s.width - 2 * px, shade))
}

/** Black / White's move button, made a plain card: a black outline, the move's type colour in a frame lit inside, the
 * charcoal face, the name white over a grey shadow. */
@Composable
private fun UnovaMoveCard(move: MoveView?, enabled: Boolean, ds: Float, modifier: Modifier, onClick: () -> Unit) {
    val type = move?.let { com.pokedaisy.app.companion.ui.theme.AppColors.typeColors[it.type] } ?: Color(0xFF8A8A8A)
    val d = kotlin.math.floor(ds).coerceAtLeast(1f)
    val density = LocalDensity.current
    fun dp(n: Float) = with(density) { (n * d).toDp() }
    Box(
        modifier.alpha(if (move != null && enabled) 1f else 0.45f)
            .drawBehind {
                val w = size.width
                val h = size.height
                // The sides a black outline, the type's dark line and its colour 2 wide; top and bottom the outline and the
                // colour (the card is 21 DS pixels tall: the words need the room).
                drawPixelRoundRect(UNOVA_CARD_OUTLINE, radius = 3 * d, step = d)
                drawPixelRoundRect(lerp(type, Color.Black, 0.5f), Offset(d, d), Size(w - 2 * d, h - 2 * d), radius = 2 * d, step = d)
                drawRect(type, Offset(2 * d, d), Size(w - 4 * d, h - 2 * d))
                drawUnovaFace(Offset(4 * d, 2 * d), Size(w - 8 * d, h - 4 * d), d)
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = move != null && enabled, onClick = onClick)
            .padding(start = dp(6f), end = dp(6f), top = dp(3f), bottom = dp(2f)),
    ) { if (move != null) MoveCardText(move, ds, d, UNOVA_TEXT, UNOVA_NAME_SHADOW, UNOVA_TEXT, UNOVA_NAME_SHADOW) }
}

/** Black / White's way back: the plate with its blue return arrow, outlined dark. */
@Composable
private fun UnovaBackButton(enabled: Boolean, ds: Float, modifier: Modifier, onClick: () -> Unit) {
    val d = kotlin.math.floor(ds).coerceAtLeast(1f)
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f)
            .drawBehind {
                drawUnovaPlate(d)
                drawArrow(UNOVA_ARROW, UNOVA_ARROW_EDGE, d, outlined = true)
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick),
    )
}

/** The return arrow ([BACK_ARROW]) in the middle, as big as fits: [shadow] a pixel down and right, or all round it
 * ([outlined]). [d]: screen px a DS pixel. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(color: Color, shadow: Color, d: Float, outlined: Boolean = false) {
    val w = size.width
    val h = size.height
    val cell = kotlin.math.floor(minOf(w, h) * 0.5f / BACK_ARROW[0].length / d).coerceAtLeast(1f) * d
    val x0 = kotlin.math.floor((w - BACK_ARROW[0].length * cell) / 2f / d) * d
    val y0 = kotlin.math.floor((h - BACK_ARROW.size * cell) / 2f / d) * d
    val offsets = if (outlined) listOf(-d to 0f, d to 0f, 0f to -d, 0f to d) else listOf(d to d)
    for ((ox, oy) in offsets) BACK_ARROW.forEachIndexed { row, bits ->
        bits.forEachIndexed { col, c -> if (c == '#') drawRect(shadow, Offset(x0 + col * cell + ox, y0 + row * cell + oy), Size(cell, cell)) }
    }
    BACK_ARROW.forEachIndexed { row, bits ->
        bits.forEachIndexed { col, c -> if (c == '#') drawRect(color, Offset(x0 + col * cell, y0 + row * cell), Size(cell, cell)) }
    }
}

/** Black / White's colours (headless in White's battle): the message band and its lines, the words, the plates, the
 * labels (FIGHT's red, BAG's yellow, POKéMON's green, RUN's blue, each outlined dark), the cursor, the return arrow. */
private val UNOVA_BAND_TOP = Color(0xFF0C3C34)
private val UNOVA_BAND = Color(0xFF08302C)
private val UNOVA_LINE = Color(0xFF4D201C)
private val UNOVA_LINE_SHADOW = Color(0xFF24201C)
private val UNOVA_TEXT = Color(0xFFFBFBFB)
private val UNOVA_TEXT_SHADOW = Color(0xFF75A29E)
private val UNOVA_SHADE = Color(0xFF717171)
private val UNOVA_RIM = Color(0xFFFBFBFB)
private val UNOVA_RIM_EDGE = Color(0xFF9A9A9A)
private val UNOVA_FACE = Color(0xFF383838)
private val UNOVA_DOT = Color(0xFF414141)
private val UNOVA_FACE_LOW = Color(0xFF303030)
private val UNOVA_FACE_LOWER = Color(0xFF282828)
private val UNOVA_FACE_LOWEST = Color(0xFF202020)
private val UNOVA_ACTIONS = listOf(Color(0xFFCB0038), Color(0xFFFBB228), Color(0xFF51CB28), Color(0xFF28BAF3))
private val UNOVA_LABEL_OUTLINE = Color(0xFF000000)
private val UNOVA_NAME_SHADOW = Color(0xFF717171)
private val UNOVA_CARD_OUTLINE = Color(0xFF000000)
private val UNOVA_CURSOR = Color(0xFFFB0092)
private val UNOVA_CURSOR_RIM = Color(0xFFFBFBFB)
private val UNOVA_ARROW = Color(0xFF28BAF3)
private val UNOVA_ARROW_EDGE = Color(0xFF202020)

/** Black 2 / White 2's colours (headless in Black 2's battle): the message band and its lines, the words' shadow, the plates
 * (black, rimmed in the button's colour - bright down the sides, its shade along the top and bottom - over a face striped
 * in two greys), each button's colour and shade (FIGHT red, BAG yellow, POKéMON green, RUN blue), the cursor's layers. */
private val B2_BAND = Color(0xFF0C3851)
private val B2_BAND_MID = Color(0xFF184155)
private val B2_LINE_DARK = Color(0xFF083855)
private val B2_LINE = Color(0xFF305D7D)
private val B2_LINE_LOW = Color(0xFF285165)
private val B2_LINE_LIGHT = Color(0xFF41697D)
private val B2_TEXT_SHADOW = Color(0xFF82AABE)
private val B2_OUTLINE = Color(0xFF202020)
private val B2_FACE = Color(0xFF282828)
private val B2_STRIPE = Color(0xFF383838)
private val B2_ACTIONS = listOf(
    Color(0xFFFB2018) to Color(0xFF921818),
    Color(0xFFFBCB28) to Color(0xFF927108),
    Color(0xFF71DB30) to Color(0xFF207108),
    Color(0xFF20A2FB) to Color(0xFF005992),
)
private val B2_ARROW = Color(0xFFEBF3FB)
private val B2_CURSOR = listOf(Color(0xFFF3F3F3), Color(0xFFEB7192), Color(0xFFC31028), Color(0xFFF3F3F3))

/** A Black 2 / White 2 plate ([px]: a GBA pixel): a black outline, the button's colour 2 pixels thick all round, its shade
 * inside that, black again, then the face in grey stripes (a light row over two darker ones). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawB2Plate(color: Color, shade: Color, px: Float) {
    val w = size.width
    val h = size.height
    drawPixelRoundRect(B2_OUTLINE, radius = 4 * px, step = px)
    drawPixelRoundRect(color, Offset(px, px), Size(w - 2 * px, h - 2 * px), radius = 3 * px, step = px)
    drawRect(shade, Offset(3 * px, 3 * px), Size(w - 6 * px, h - 6 * px))
    drawRect(B2_OUTLINE, Offset(4 * px, 4 * px), Size(w - 8 * px, h - 8 * px))
    drawB2Face(Offset(5 * px, 5 * px), Size(w - 10 * px, h - 10 * px), px)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawB2Face(o: Offset, s: Size, px: Float) {
    drawRect(B2_FACE, o, s)
    var y = 0
    val rows = (s.height / px).toInt()
    while (y < rows) {
        drawRect(B2_STRIPE, Offset(o.x, o.y + y * px), Size(s.width, px))
        y += 3
    }
}

/** Black 2 / White 2's move button, made a plain card: black, the type's dark line, its colour 2 wide down the sides (a
 * line along the top and bottom), its dark line, black, the striped face; the name white over a grey shadow. */
@Composable
private fun B2MoveCard(move: MoveView?, enabled: Boolean, ds: Float, modifier: Modifier, onClick: () -> Unit) {
    val type = move?.let { com.pokedaisy.app.companion.ui.theme.AppColors.typeColors[it.type] } ?: Color(0xFF8A8A8A)
    val d = kotlin.math.floor(ds).coerceAtLeast(1f)
    val density = LocalDensity.current
    fun dp(n: Float) = with(density) { (n * d).toDp() }
    Box(
        modifier.alpha(if (move != null && enabled) 1f else 0.45f)
            .drawBehind {
                val w = size.width
                val h = size.height
                val dark = lerp(type, Color.Black, 0.35f)
                drawPixelRoundRect(Color.Black, radius = 3 * d, step = d)
                drawPixelRoundRect(dark, Offset(d, d), Size(w - 2 * d, h - 2 * d), radius = 2 * d, step = d)
                drawRect(type, Offset(2 * d, d), Size(w - 4 * d, h - 2 * d))
                drawRect(dark, Offset(4 * d, 2 * d), Size(w - 8 * d, h - 4 * d))
                drawRect(Color.Black, Offset(5 * d, 2 * d), Size(w - 10 * d, h - 4 * d))
                drawB2Face(Offset(6 * d, 2 * d), Size(w - 12 * d, h - 4 * d), d)
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = move != null && enabled, onClick = onClick)
            .padding(start = dp(7f), end = dp(7f), top = dp(3f), bottom = dp(2f)),
    ) { if (move != null) MoveCardText(move, ds, d, UNOVA_TEXT, UNOVA_NAME_SHADOW, UNOVA_TEXT, UNOVA_NAME_SHADOW) }
}

/** Black 2 / White 2's way back: a plate rimmed in RUN's blue, the return arrow white outlined dark. */
@Composable
private fun B2BackButton(enabled: Boolean, ds: Float, modifier: Modifier, onClick: () -> Unit) {
    val d = kotlin.math.floor(ds).coerceAtLeast(1f)
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f)
            .drawBehind {
                drawB2Plate(B2_ACTIONS[3].first, B2_ACTIONS[3].second, d)
                drawArrow(B2_ARROW, B2_OUTLINE, d, outlined = true)
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick),
    )
}

/** Black 2 / White 2's cursor (headless): an L at each corner, its outer corner rounded, four pixels thick - white, pink,
 * red, white from the outside in - [pulse] px out. */
private fun Modifier.b2Cursor(on: Boolean, u: Dp, pulse: Float) = if (!on) this else drawWithContent {
    drawContent()
    val px = u.toPx()
    val out = (1 + pulse) * px
    val arm = 10
    val rc = 5
    for ((cx, cy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        val ox = if (cx < 0) -out else size.width + out
        val oy = if (cy < 0) -out else size.height + out
        for (j in 0 until arm) for (k in 0 until arm) {
            // How deep the pixel is from the bracket's outer edge: round within the corner, straight along the arms.
            val depth = if (j < rc && k < rc) {
                val dx = rc - j - 0.5f
                val dy = rc - k - 0.5f
                rc - kotlin.math.sqrt(dx * dx + dy * dy)
            } else minOf(j, k).toFloat()
            if (depth < 0f || depth >= B2_CURSOR.size) continue
            val x = if (cx < 0) ox + j * px else ox - (j + 1) * px
            val y = if (cy < 0) oy + k * px else oy - (k + 1) * px
            drawRect(B2_CURSOR[depth.toInt()], Offset(x, y), Size(px, px))
        }
    }
}
