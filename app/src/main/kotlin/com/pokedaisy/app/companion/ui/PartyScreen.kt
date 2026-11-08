package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_FEMALE
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_MALE
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.gameText
import com.pokedaisy.app.companion.data.localText
import com.pokedaisy.app.companion.data.romGameCode
import com.pokedaisy.app.companion.data.romLanguage
import com.pokedaisy.app.companion.ui.theme.AppColors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.pokedaisy.app.companion.data.isGen2
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress

private const val PARTY_MAX = 6
/** A slot picked up to drag stands a little larger. */
private const val DRAGGED_SLOT_SCALE = 1.06f

/** The PARTY tab: the lead under the cursor, a tap opens that Pokémon's summary; what each one holds on a tag in its
 * corner, as ITEMS' GIVE pick shows it. */
@Composable
fun PartyScreen(party: List<MonView>, onMonClick: (MonView) -> Unit, onSwap: ((Int, Int) -> Unit)? = null) {
    PartyGrid(party, selected = 0, onSlotClick = onMonClick, onDragSwap = onSwap) { mon ->
        // NUZLOCKE: a Pokémon that fainted once carries a gravestone beside what it holds.
        val dead = com.pokedaisy.app.companion.Nuzlocke.isDead(mon.personality)
        if (mon.heldItemId != 0 || dead) Row(
            Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dead) NuzlockeDeadTag()
            if (mon.heldItemId != 0) HeldItemTag(mon.heldItemId)
        }
    }
}

/**
 * Six slots, in-game style: filled first, then empties. Deliberately NOT a
 * `LazyVerticalGrid` (which scrolls once its 96dp-tall cells don't all fit) —
 * a plain weighted Column/Row of 3 rows x 2 columns always fills exactly the
 * tab's height with no scroll, and each card's height adapts to whatever
 * space that leaves per row instead of a fixed dp.
 *
 * Shared by the PARTY tab and the battle POKéMON pane: [selected] is the slot
 * under the game's cursor (-1 = none), [badge] draws over a filled slot's
 * top-right corner, and [sound] false leaves taps silent for the pane, whose
 * picks press the game's own buttons (the game sounds those itself).
 * [dimmed] greys a slot out and ignores its taps (the ITEMS tab's pick: a
 * Pokémon the item won't help).
 */
@Composable
fun PartyGrid(
    party: List<MonView>,
    selected: Int,
    onSlotClick: (MonView) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    sound: Boolean = true,
    dimmed: (MonView) -> Boolean = { false },
    /** A long press picks a slot up and a drop on another Pokémon trades them; null = slots stay put. */
    onDragSwap: ((Int, Int) -> Unit)? = null,
    badge: @Composable BoxScope.(MonView) -> Unit = {},
) {
    val slots: List<MonView?> = (0 until PARTY_MAX).map { party.getOrNull(it) }
    // The slot being dragged, how far it has come, the slot under it, and each slot's place in the grid.
    var dragFrom by remember { mutableStateOf(-1) }
    var dragBy by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var dropOn by remember { mutableStateOf(-1) }
    val places = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(PARTY_MAX) }
    val swap by androidx.compose.runtime.rememberUpdatedState(onDragSwap)
    val count by androidx.compose.runtime.rememberUpdatedState(party.size)
    fun under(i: Int): Int {
        val centre = (places[i] ?: return -1).center + dragBy
        return places.indices.firstOrNull { it != i && it < count && places[it]?.contains(centre) == true } ?: -1
    }
    // FireRed/Emerald, Heart and Soul and the CFRU hacks get the game's own party-slot look
    // (drawn from the game's own data); every other game - or one whose art no
    // ROM has supplied yet (RomArt) - gets a FireRed-like slot drawn from a [PartyPalette].
    val gameStyle = partySlotStyleFor(activeGame)?.takeIf { rememberPartySlotAssets(it) != null }
    val palette = partyPaletteFor(activeGame)
    // The D-pad's slot (the companion's window): it takes the game's selected look, like the game's own cursor.
    var focused by remember { mutableStateOf(-1) }
    // Both sit edge to edge like in the game: each slot carries its own Poke
    // Ball overhang zone above/left of its frame (the CFRU boxes are only ~2px
    // apart there too) - extra spacing would just be dead margin.
    // Two columns like the game, three on a wide screen (the Thor's top screen,
    // SWAP SCREENS), where two stretched each slot to twice its width. Decided
    // while measuring, not with BoxWithConstraints: that composes the slots
    // late, and FireRed's icons missed the first frame.
    Layout(
        modifier = modifier,
        content = {
            slots.forEachIndexed { i, mon ->
                val isSelected = (if (dropOn >= 0) i == dropOn else if (focused >= 0) i == focused else i == selected) && mon != null
                val dim = mon != null && dimmed(mon)
                val onClick = { if (!dim) mon?.let(onSlotClick); Unit }
                Box(
                    (if (dim) Modifier.graphicsLayer { alpha = DIMMED_SLOT_ALPHA } else Modifier)
                        .onGloballyPositioned { places[i] = it.boundsInParent() }
                        .zIndex(if (dragFrom == i) 1f else 0f)
                        .graphicsLayer {
                            if (dragFrom == i) {
                                translationX = dragBy.x
                                translationY = dragBy.y
                                scaleX = DRAGGED_SLOT_SCALE
                                scaleY = DRAGGED_SLOT_SCALE
                            }
                        }
                        .then(
                            if (onDragSwap != null && mon != null) Modifier.pointerInput(i) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragFrom = i; dragBy = androidx.compose.ui.geometry.Offset.Zero; dropOn = -1 },
                                    onDrag = { change, by -> change.consume(); dragBy += by; dropOn = under(i) },
                                    onDragEnd = {
                                        val to = under(i)
                                        dragFrom = -1; dropOn = -1
                                        if (to >= 0) swap?.invoke(i, to)
                                    },
                                    onDragCancel = { dragFrom = -1; dropOn = -1 },
                                )
                            } else Modifier,
                        )
                        // The D-pad starts on the selected slot.
                        .focusStart(i == selected && mon != null)
                        .onFocusChanged { if (it.hasFocus) focused = i else if (focused == i) focused = -1 },
                ) {
                    if (gameStyle != null) {
                        GbaPartySlot(gameStyle, mon, selected = isSelected, onClick = onClick, modifier = Modifier.fillMaxSize(), sound = sound)
                    } else {
                        PartySlot(palette, mon, selected = isSelected, onClick = onClick, modifier = Modifier.fillMaxSize(), sound = sound)
                    }
                    if (mon != null) badge(mon)
                }
            }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val cols = if (w.toFloat() / h > WIDE_PARTY_ASPECT) 3 else 2
        val rows = (measurables.size + cols - 1) / cols
        // Whole pixels, the remainder spread over the first columns / rows like weights do.
        fun edge(total: Int, parts: Int, i: Int) = total * i / parts
        val placeables = measurables.mapIndexed { i, m ->
            val c = i % cols
            val r = i / cols
            m.measure(Constraints.fixed(edge(w, cols, c + 1) - edge(w, cols, c), edge(h, rows, r + 1) - edge(h, rows, r)))
        }
        layout(w, h) {
            placeables.forEachIndexed { i, p -> p.place(edge(w, cols, i % cols), edge(h, rows, i / cols)) }
        }
    }
}

/**
 * Wider than this (width / height), the party grid goes to three columns. The grids measured: the
 * Thor's bottom screen ~1.4, a 4:3 screen (the Retroid Pocket Duo Lite's 1280x960) ~1.56 and up to
 * ~1.75 with a bigger density or the status bar over the tabs - 1.6 put that one in three columns -
 * and the Thor's 1920x1080 top screen 2.2+.
 */
private const val WIDE_PARTY_ASPECT = 2.0f

/** A [PartyGrid] slot that can't be picked: as faint as a disabled battle button. */
private const val DIMMED_SLOT_ALPHA = 0.45f

/** A slot's tap: with the companion's click, or silent when the game makes its own sound - unless the game can't be
 * heard right now ([LocalGameSilent]: FF's STEADY music mutes it). */
internal fun Modifier.slotClickable(sound: Boolean, enabled: Boolean, onClick: () -> Unit): Modifier = composed {
    // No ripple: its focus layer greyed the slot over the game's own selected frame, which is the D-pad's cursor here.
    val noRipple = remember { MutableInteractionSource() }
    if (sound) soundClickable(interactionSource = noRipple, indication = null, enabled = enabled, onClick = onClick) else {
        val click = LocalClickSound.current
        val gameSilent = LocalGameSilent.current
        clickable(interactionSource = noRipple, indication = null, enabled = enabled) { onClick(); if (gameSilent()) click() }
    }
}

/** One slot state's frame colors: [outline] 2px, a 1px [light] inner edge,
 * the upper [fill], the HP [band] below it (FireRed's is the light edge's
 * color; flat slots like Gaia's are the fill's), a 1px bottom [shade]. [text]
 * / [textShadow] override the palette's for this state (Lazarus colors its
 * text per state); a transparent shadow = none. A cut-corner slot ([PartyPalette.chamfer])
 * is a 1px [outline], [light] and [shade] line round its [fill], with a [stripe] across it. */
data class PartySlotColors(
    val outline: Color, val light: Color, val fill: Color, val shade: Color,
    val band: Color = light,
    val text: Color? = null, val textShadow: Color? = null,
    /** A cut-corner slot's diagonal band (Black's): leaning like "/", over the slot's right half. */
    val stripe: Color? = null,
)

/** HP bar fill: a darker [top] row over the [main] color. */
data class HpBarColors(val top: Color, val main: Color)

/**
 * The generic party slot's colors - the party-screen counterpart of the Items
 * tab's BagPalette: the layout is FireRed's MAIN party slot for every game
 * without a [PartySlotStyle] of its own, and a game's look is just a palette
 * picked in [partyPaletteFor].
 */
data class PartyPalette(
    val normal: PartySlotColors,
    val selected: PartySlotColors,
    val fainted: PartySlotColors,
    val selectedFainted: PartySlotColors = fainted.copy(outline = selected.outline),
    val text: Color,
    val textShadow: Color,
    val male: Color, val maleShadow: Color,
    val female: Color, val femaleShadow: Color,
    /** The HP bar's dark outline (also the "HP" label box) and its white inner edge. */
    val hpFrame: Color, val hpInner: Color,
    /** "HP" letters: top rows / bottom rows. */
    val hpLabel: Color, val hpLabelShade: Color,
    val hpGreen: HpBarColors, val hpYellow: HpBarColors, val hpRed: HpBarColors, val hpEmpty: HpBarColors,
    /** The Poke Ball overhanging each slot's top-left corner, by [PartyBall]'s letters. */
    val ball: Map<Char, Color>,
    /** How faintly empty slots show their (no-HP) box. */
    val emptyAlpha: Float = 0.35f,
    /** The game's own empty-slot box, drawn solid instead of a faint [normal] one; null = faint. */
    val empty: PartySlotColors? = null,
    /** The slot's corners cut at 45 degrees this many slot pixels (Black's eight-sided boxes); 0 = FireRed's rounded. */
    val chamfer: Int = 0,
    /** An empty slot as lines of this colour every other row ([empty]'s outline and fill round them): the DS games'. */
    val emptyLines: Color? = null,
)

/** FireRed's party menu (partyfr/slot_*.png, pokeball.png; Emerald's is identical). */
val FireRedPartyPalette = PartyPalette(
    normal = PartySlotColors(Color(0xFF4A4A63), Color(0xFF84C6DE), Color(0xFF3994DE), Color(0xFF297BB5)),
    selected = PartySlotColors(Color(0xFFFF7331), Color(0xFFADEFFF), Color(0xFF7BD6EF), Color(0xFF4AADCE)),
    fainted = PartySlotColors(Color(0xFF4A4A63), Color(0xFFD6A521), Color(0xFFC66B10), Color(0xFFA54A00)),
    text = Color.White, textShadow = Color(0xFF737373),
    male = Color(0xFF42CEFF), maleShadow = Color(0xFF006394),
    female = Color(0xFFFF9C94), femaleShadow = Color(0xFF9C4239),
    hpFrame = Color(0xFF525252), hpInner = Color.White,
    hpLabel = Color(0xFFFFD652), hpLabelShade = Color(0xFFFFB542),
    hpGreen = HpBarColors(Color(0xFF5AD684), Color(0xFF73FFAD)),
    hpYellow = HpBarColors(Color(0xFFCEAD08), Color(0xFFFFE739)),
    hpRed = HpBarColors(Color(0xFFC63900), Color(0xFFFF7331)),
    hpEmpty = HpBarColors(Color(0xFF525252), Color(0xFF737373)),
    ball = mapOf('K' to Color(0xFF4A4A63), 'R' to Color(0xFFB54200), 'H' to Color(0xFFC65200), 'W' to Color(0xFFB5BDCE)),
)

/*
 * The games below use FireRed's party layout (or near it) in their own colors,
 * sampled from each game's party menu with headless mGBA: the save's first mon
 * copied into slots 2-3, slot 3 at 0 HP, so normal / selected / fainted all
 * show (native-capture/menu-shots/<game>/party.png has the real screen).
 */

/** Emerald Rogue: FireRed's slots in purple-blue, fainted and the rest unchanged. */
val RoguePartyPalette = FireRedPartyPalette.copy(
    normal = PartySlotColors(Color(0xFF4A4A63), Color(0xFF7B6BFF), Color(0xFF6352F7), Color(0xFF4A42C6)),
    selected = PartySlotColors(Color(0xFFFF7331), Color(0xFF7B6BFF), Color(0xFF6352F7), Color(0xFF635ACE)),
)

/** Gaia: flat brown slots (no lighter HP band) with a light edge, a red cursor, grey fainted slots, a yellow "HP". */
val GaiaPartyPalette = FireRedPartyPalette.copy(
    normal = PartySlotColors(Color(0xFF4A4A63), Color(0xFFB5945A), Color(0xFF8C6B31), Color(0xFFB5945A), band = Color(0xFF8C6B31)),
    selected = PartySlotColors(Color(0xFFFF2121), Color(0xFFE7C69C), Color(0xFFBD9C63), Color(0xFFE7C69C), band = Color(0xFFBD9C63)),
    fainted = PartySlotColors(Color(0xFF4A4A63), Color(0xFFC6C6C6), Color(0xFF949494), Color(0xFFC6C6C6), band = Color(0xFF949494)),
    hpLabel = Color(0xFFFFDE00), hpLabelShade = Color(0xFFFFDE00),
    hpGreen = HpBarColors(Color(0xFF00A500), Color(0xFF18D621)),
)

/** Lazarus: flat boxes - light blue / orange edge under the cursor, dark blue / cyan otherwise - text in the edge's color, no shadow. */
val LazarusPartyPalette = FireRedPartyPalette.copy(
    normal = flatSlot(Color(0xFF4AF7FF), Color(0xFF187BB5), text = Color(0xFF4AF7FF)),
    selected = flatSlot(Color(0xFFFF7331), Color(0xFF7BD6EF), text = Color(0xFFFF7331)),
    fainted = flatSlot(Color(0xFF4A4A63), Color(0xFFC66B10), text = Color(0xFF4A4A63)),
    empty = flatSlot(Color(0xFF737373), Color.White),
)

/** Emerald Seaglass: plain white boxes on slate, orange text under the cursor, dark otherwise; empty slots slate in white lines. */
val SeaglassPartyPalette = FireRedPartyPalette.copy(
    normal = flatSlot(Color.White, Color.White, text = Color(0xFF4A4A63)),
    selected = flatSlot(Color(0xFF293131), Color.White, text = Color(0xFFFF7331)),
    fainted = flatSlot(Color.White, Color.White, text = Color(0xFF4A4A63)),
    empty = flatSlot(Color.White, Color(0xFF4A4A63)),
)

/** Glazed and Quetzal: Emerald's slots over olive stripes; an empty slot is the stripes in a frame (headless, their party menus). */
val GlazedPartyPalette = FireRedPartyPalette.copy(
    empty = PartySlotColors(Color(0xFF4A4A63), Color(0xFFE7EF8C), Color(0xFFCED67B), Color(0xFF635A00)),
)

/**
 * Emerald Imperium (headless, its party grid): blue boxes in a cyan line with a white edge
 * inside, light blue in an orange line under the cursor, white text; empty slots are the
 * dark stripes in a navy line.
 */
val ImperiumPartyPalette = FireRedPartyPalette.copy(
    normal = PartySlotColors(Color(0xFF4AF7FF), Color.White, Color(0xFF187BB5), Color(0xFF187BB5)),
    selected = PartySlotColors(Color(0xFFFF7331), Color.White, Color(0xFF7BD6EF), Color(0xFF7BD6EF)),
    empty = flatSlot(Color(0xFF315A73), Color(0xFF101821)),
)

/**
 * Pokémon Monomyth (headless, its party grid on the user's save and a five-Pokémon one): teal boxes striped lighter in a
 * slate line, the cursor's pale cyan in grey, empty slots grey lines every other row in a near-black line. Text, HP bar
 * and Poké Ball are FireRed's; the HP bar's frame is the slot's dark line.
 */
val MonomythPartyPalette = FireRedPartyPalette.copy(
    normal = PartySlotColors(Color(0xFF293942), Color(0xFF6BCEC6), Color(0xFF42AD9C), Color(0xFF42AD9C)),
    selected = PartySlotColors(Color(0xFF636363), Color(0xFFBDE7E7), Color(0xFF84DED6), Color(0xFF84DED6)),
    fainted = FireRedPartyPalette.fainted.copy(outline = Color(0xFF293942)),
    empty = PartySlotColors(Color(0xFF292129), Color(0xFF525252), Color(0xFF525252), Color(0xFF525252)),
    emptyLines = Color(0xFF737373),
    hpFrame = Color(0xFF292129),
)

/**
 * Super Mariomon (headless, its party grid with a selected, a low-HP, a fainted and empty slots):
 * FireRed's slot in greens - mint boxes in a dark brown line, pale green in a lilac line under the
 * cursor - the fainted slot FireRed's orange in its own darker line, empty slots the menu's wood
 * planks (two browns) in the brown line. Text, HP bar and Poké Ball are FireRed's.
 */
val MariomonPartyPalette = FireRedPartyPalette.copy(
    normal = PartySlotColors(Color(0xFF4A4239), Color(0xFFB5FFA5), Color(0xFF7BDE84), Color(0xFF42A521)),
    selected = PartySlotColors(Color(0xFFA573FF), Color(0xFFC6F7B5), Color(0xFF84D694), Color(0xFF84D694)),
    fainted = PartySlotColors(Color(0xFFA54A00), Color(0xFFD6A521), Color(0xFFC66B10), Color(0xFFA54A00)),
    empty = PartySlotColors(Color(0xFF4A4239), Color(0xFFC69C7B), Color(0xFFC69C7B), Color(0xFFA57B63)),
)

/**
 * R.O.W.E. (headless, its party grid with a selected, a poisoned, a fainted, a low-HP and empty
 * slots): slate boxes in a white line, the cursor's box charcoal in red; a fainted slot looks like
 * any other (its FNT pill says it); empty slots are the charcoal grid in a white line. Its own
 * blue / red gender symbols and HP bar colours.
 */
val RowePartyPalette = FireRedPartyPalette.copy(
    normal = flatSlot(Color(0xFFEFEFEF), Color(0xFF39394A)),
    selected = flatSlot(Color(0xFFC60000), Color(0xFF292929)),
    fainted = flatSlot(Color(0xFFEFEFEF), Color(0xFF39394A)),
    selectedFainted = flatSlot(Color(0xFFC60000), Color(0xFF292929)),
    empty = flatSlot(Color.White, Color(0xFF292929)),
    text = Color(0xFFEFEFEF), textShadow = Color(0xFFA5A5A5),
    male = Color(0xFF0000D6), maleShadow = Color(0xFF7394FF),
    female = Color(0xFFC60000), femaleShadow = Color(0xFFFF8484),
    hpFrame = Color(0xFF181818), hpLabel = Color(0xFFEFEFEF), hpLabelShade = Color(0xFFA5A5A5),
    hpGreen = HpBarColors(Color(0xFF08FF5A), Color(0xFF08FF5A)),
    hpYellow = HpBarColors(Color(0xFFFFCE31), Color(0xFFFFCE31)),
    hpRed = HpBarColors(Color(0xFFFF8484), Color(0xFFFF8484)),
    hpEmpty = HpBarColors(Color(0xFFD6D6D6), Color(0xFFD6D6D6)),
)

/**
 * SoulGold (headless, its party list): flat slots in a 1px grey outline - slate
 * over a black band normally, light grey over white under the cursor, dark red
 * when fainted (pink under the cursor) - light text with a black shadow (black
 * on white when selected), a frameless HP bar on grey (green / FFBD00 / red) and
 * its own blue / red gender symbols. Re-sampled on a six-mon save with HP poked
 * low and a mon fainted.
 */
val SoulGoldPartyPalette = FireRedPartyPalette.copy(
    normal = PartySlotColors(Color(0xFF52525A), Color(0xFF4A4A63), Color(0xFF4A4A63), Color.Black, band = Color.Black,
        text = Color(0xFFD6D6CE), textShadow = Color.Black),
    selected = PartySlotColors(Color(0xFF52525A), Color(0xFFCECEC6), Color(0xFFCECEC6), Color.White, band = Color.White,
        text = Color.Black, textShadow = Color.White),
    fainted = PartySlotColors(Color(0xFF52525A), Color(0xFF7B2121), Color(0xFF7B2121), Color.Black, band = Color.Black,
        text = Color(0xFFD6D6CE), textShadow = Color.Black),
    selectedFainted = PartySlotColors(Color(0xFF52525A), Color(0xFFFF9494), Color(0xFFFF9494), Color.White, band = Color.White,
        text = Color.Black, textShadow = Color.White),
    text = Color(0xFFD6D6CE), textShadow = Color.Black,
    male = Color(0xFF3994DE), maleShadow = Color(0xFF52525A),
    female = Color(0xFFFF4A4A), femaleShadow = Color(0xFF52525A),
    hpGreen = HpBarColors(Color(0xFF00DE00), Color(0xFF00DE00)),
    hpYellow = HpBarColors(Color(0xFFFFBD00), Color(0xFFFFBD00)),
    hpRed = HpBarColors(Color(0xFFFF4A4A), Color(0xFFFF4A4A)),
    hpEmpty = HpBarColors(Color(0xFF848484), Color(0xFF848484)),
)

/**
 * HeartGold / SoulSilver (headless, their party screen on the player's saves, a slot fainted by zeroing its HP): green
 * rounded slots in a dark green line with a light green edge, the cursor's brighter green in a red line; fainted slots
 * orange-red, brighter orange under the cursor; white names on a dark grey shadow, the HP bar's two greens.
 */
val HeartGoldPartyPalette = FireRedPartyPalette.copy(
    normal = PartySlotColors(Color(0xFF004900), Color(0xFF69DB71), Color(0xFF009238), Color(0xFF006920)),
    selected = PartySlotColors(Color(0xFFFB2020), Color(0xFF79F382), Color(0xFF30DB51), Color(0xFF10AA41)),
    fainted = PartySlotColors(Color(0xFF490000), Color(0xFFF37900), Color(0xFFD33810), Color(0xFF821000)),
    selectedFainted = PartySlotColors(Color(0xFFFB2020), Color(0xFFFBAA41), Color(0xFFFB7900), Color(0xFFFB4100)),
    text = Color(0xFFFBFBFB), textShadow = Color(0xFF282828),
    hpFrame = Color(0xFF414141), hpInner = Color(0xFFFBFBFB),
    hpGreen = HpBarColors(Color(0xFF61FB61), Color(0xFF18C320)),
)

/**
 * Sword and Shield Ultimate Plus (headless, its CFRU party grid with a selected, a fainted and
 * empty slots): charcoal boxes in a black line with a cyan edge, the cursor's a little bluer in a
 * white edge; fainted slots dark red with a deeper diagonal band in a pink edge, bright pink under
 * the cursor; white text, green "HP" letters, a frameless bar on charcoal (the green flat, the
 * yellow CFRU's), no Poké Ball; empty slots are the backdrop's dark stripes on red.
 */
val SwordShieldPartyPalette = FireRedPartyPalette.copy(
    normal = PartySlotColors(Color(0xFF101010), Color(0xFF94FFFF), Color(0xFF212121), Color(0xFF212121), band = Color(0xFF212121)),
    selected = PartySlotColors(Color(0xFFFFFFFF), Color(0xFF9CF7FF), Color(0xFF293131), Color(0xFF293131), band = Color(0xFF293131)),
    fainted = PartySlotColors(Color(0xFFC63184), Color(0xFFFF94AD), Color(0xFF8C1821), Color(0xFF8C1821), band = Color(0xFF6B2121)),
    selectedFainted = PartySlotColors(Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFEF086B), Color(0xFFEF086B), band = Color(0xFFB50852)),
    empty = flatSlot(Color(0xFFCEE7EF), Color(0xFF9C2121)),
    text = Color.White, textShadow = Color(0xFF737373),
    male = Color(0xFF00EFFF), maleShadow = Color(0xFF006B63),
    female = Color(0xFFE73939), femaleShadow = Color(0xFF732131),
    hpFrame = Color(0xFF212121), hpInner = Color(0xFF212121),
    hpLabel = Color(0xFF00FF4A), hpLabelShade = Color(0xFF00FF4A),
    hpGreen = HpBarColors(Color(0xFF18C621), Color(0xFF18C621)),
    hpYellow = HpBarColors(Color(0xFFEFAD00), Color(0xFFFFDE00)),
    hpRed = HpBarColors(Color(0xFFFF4A39), Color(0xFFFF9C9C)),
    hpEmpty = HpBarColors(Color(0xFF737373), Color(0xFF737373)),
    ball = mapOf('K' to Color.Transparent, 'R' to Color.Transparent, 'H' to Color.Transparent, 'W' to Color.Transparent),
)

/**
 * GS Chronicles' party menu (headless): CFRU's shared slots, grid and colours, which RomArt finds in
 * its ROM, no Poké Ball, and Odyssey's status icons (its FRZ icon matched Odyssey's pixels, not
 * Unbound's, with a frozen lead poked in). Its text is mixed case, so the egg reads "Egg".
 */
val GsChroniclesPartyStyle = OdysseyPartyStyle.copy(eggName = "Egg")

/** A slot in one [fill] inside an [outline]: no separate edge, band or shade. */
private fun flatSlot(outline: Color, fill: Color, text: Color? = null) =
    PartySlotColors(outline, fill, fill, fill, text = text, textShadow = if (text != null) Color.Transparent else null)

/**
 * Pokémon Yellow (Game Boy): the GBC party menu's few colours - black text and frames
 * on white, no shadows, the HP bar's GBC green / orange / red on white (pokeyellow's
 * CGB PAL_GREENBAR / YELLOWBAR / REDBAR) - and no Poké Ball (Gen 1 draws none). The
 * cursor row is a light grey (the game marks it with its ▶).
 */
private val G1_BLACK = Color(0xFF181818)
private fun yellowPartyPalette(ink: Color, paper: Color, cursor: Color) = FireRedPartyPalette.copy(
    normal = PartySlotColors(ink, paper, paper, paper, band = paper, text = ink, textShadow = Color.Transparent),
    selected = PartySlotColors(ink, cursor, cursor, cursor, band = cursor, text = ink, textShadow = Color.Transparent),
    fainted = PartySlotColors(ink, paper, paper, paper, band = paper, text = ink, textShadow = Color.Transparent),
    text = ink, textShadow = Color.Transparent,
    male = ink, maleShadow = Color.Transparent,
    female = ink, femaleShadow = Color.Transparent,
    hpFrame = ink, hpInner = paper,
    hpLabel = paper, hpLabelShade = paper,
    hpGreen = HpBarColors(Color(0xFF00FF00), Color(0xFF00FF00)),
    hpYellow = HpBarColors(Color(0xFFFF9400), Color(0xFFFF9400)),
    hpRed = HpBarColors(Color(0xFFFF0000), Color(0xFFFF0000)),
    hpEmpty = HpBarColors(paper, paper),
    ball = mapOf('K' to Color.Transparent, 'R' to Color.Transparent, 'H' to Color.Transparent, 'W' to Color.Transparent),
)
private val YellowPartyPaletteLight = yellowPartyPalette(G1_BLACK, Color.White, Color(0xFFDCDCDC))
// Dark mode: the menu turned round like OptionColors' Gen 1 windows - light lines and text on black.
private val YellowPartyPaletteNight = yellowPartyPalette(Color(0xFFE8E8E8), Color(0xFF181818), Color(0xFF3A3A3A))
val YellowPartyPalette: PartyPalette get() = if (OptionColors.dark) YellowPartyPaletteNight else YellowPartyPaletteLight

/**
 * Pokémon Crystal (Game Boy Color): Yellow's black-on-white slots with Crystal's own party-menu HP bar,
 * sampled headless - the cream "HP:" box, a black frame, the bar green / amber / red on white.
 */
private fun crystalPartyPalette(base: PartyPalette, paper: Color) = base.copy(
    hpLabel = Color(0xFFF7D67B), hpLabelShade = Color(0xFFF7D67B),
    hpGreen = HpBarColors(Color(0xFF00BD00), Color(0xFF00BD00)),
    hpYellow = HpBarColors(Color(0xFFFFBD00), Color(0xFFFFBD00)),
    hpRed = HpBarColors(Color(0xFFFF0000), Color(0xFFFF0000)),
    hpEmpty = HpBarColors(paper, paper),
)
private val CrystalPartyPaletteLight = crystalPartyPalette(YellowPartyPaletteLight, Color.White)
private val CrystalPartyPaletteNight = crystalPartyPalette(YellowPartyPaletteNight, Color(0xFF181818))
val CrystalPartyPalette: PartyPalette get() = if (OptionColors.dark) CrystalPartyPaletteNight else CrystalPartyPaletteLight

/** Pokémon Polished Crystal: Crystal's party menu with its gold "HP" label (sampled headless; the bars are Crystal's). */
private fun polishedPartyPalette(base: PartyPalette) = base.copy(hpLabel = Color(0xFFFFCE10), hpLabelShade = Color(0xFFFFCE10))
private val PolishedCrystalPartyPaletteLight = polishedPartyPalette(CrystalPartyPaletteLight)
private val PolishedCrystalPartyPaletteNight = polishedPartyPalette(CrystalPartyPaletteNight)
val PolishedCrystalPartyPalette: PartyPalette
    get() = if (OptionColors.dark) PolishedCrystalPartyPaletteNight else PolishedCrystalPartyPaletteLight

/**
 * Pokémon Red / Blue (a Super Game Boy, in mGBA): Yellow's slots in the SGB's white and black, the HP bar in
 * pokered's PAL_GREENBAR / YELLOWBAR / REDBAR (sampled headless: the green 0x4AA55A).
 */
private fun redBluePartyPalette(ink: Color, paper: Color, cursor: Color) = yellowPartyPalette(ink, paper, cursor).copy(
    hpGreen = HpBarColors(Color(0xFF4AA55A), Color(0xFF4AA55A)),
    hpYellow = HpBarColors(Color(0xFFD6A500), Color(0xFFD6A500)),
    hpRed = HpBarColors(Color(0xFFD65231), Color(0xFFD65231)),
)
private val RedBluePartyPaletteLight = redBluePartyPalette(Color(0xFF181010), Color(0xFFFFEFFF), Color(0xFFE6D6E6))
private val RedBluePartyPaletteNight = redBluePartyPalette(Color(0xFFE8E8E8), Color(0xFF181818), Color(0xFF3A3A3A))
val RedBluePartyPalette: PartyPalette get() = if (OptionColors.dark) RedBluePartyPaletteNight else RedBluePartyPaletteLight

/**
 * PureRed / PureBlue on a Color (their default COLORS, OG: the Color's two-tone palettes, sampled headless): Yellow's
 * slots in pure black and white, every HP bar the screen's dark colour - PureRed's dark red, PureBlue's blue.
 */
private fun purePartyPalette(ink: Color, paper: Color, cursor: Color, bar: Color) = yellowPartyPalette(ink, paper, cursor).copy(
    hpGreen = HpBarColors(bar, bar), hpYellow = HpBarColors(bar, bar), hpRed = HpBarColors(bar, bar),
)
private val PureRedPartyPaletteLight = purePartyPalette(Color.Black, Color.White, Color(0xFFDCDCDC), Color(0xFF943939))
private val PureRedPartyPaletteNight = purePartyPalette(Color(0xFFE8E8E8), Color(0xFF181818), Color(0xFF3A3A3A), Color(0xFFFF8484))
private val PureBluePartyPaletteLight = purePartyPalette(Color.Black, Color.White, Color(0xFFDCDCDC), Color(0xFF0000FF))
private val PureBluePartyPaletteNight = purePartyPalette(Color(0xFFE8E8E8), Color(0xFF181818), Color(0xFF3A3A3A), Color(0xFF63A5FF))
val PureRedPartyPalette: PartyPalette get() = if (OptionColors.dark) PureRedPartyPaletteNight else PureRedPartyPaletteLight
val PureBluePartyPalette: PartyPalette get() = if (OptionColors.dark) PureBluePartyPaletteNight else PureBluePartyPaletteLight

/**
 * Pokémon Ghost Grey (headless, its party menu): four greys - white slots in a black line, the cursor's slot light grey,
 * black text with no shadow, the HP bar black on white under a white-on-black "HP", a grey ghost's face where FireRed has
 * its Poké Ball. Dark mode turns it round like Yellow's.
 */
private fun ghostGreyPartyPalette(ink: Color, paper: Color, cursor: Color, ghost: Color) = FireRedPartyPalette.copy(
    normal = PartySlotColors(ink, paper, paper, paper, band = paper, text = ink, textShadow = Color.Transparent),
    selected = PartySlotColors(ink, cursor, cursor, cursor, band = cursor, text = ink, textShadow = Color.Transparent),
    fainted = PartySlotColors(ink, paper, paper, paper, band = paper, text = ink, textShadow = Color.Transparent),
    text = ink, textShadow = Color.Transparent,
    male = ink, maleShadow = Color.Transparent,
    female = ink, femaleShadow = Color.Transparent,
    hpFrame = ink, hpInner = paper,
    hpLabel = paper, hpLabelShade = paper,
    hpGreen = HpBarColors(ink, ink), hpYellow = HpBarColors(ink, ink), hpRed = HpBarColors(ink, ink),
    hpEmpty = HpBarColors(paper, paper),
    ball = mapOf('K' to ghost, 'R' to cursor, 'H' to cursor, 'W' to paper),
)
private val GhostGreyPartyPaletteLight =
    ghostGreyPartyPalette(Color.Black, Color.White, Color(0xFFADADAD), Color(0xFF525252))
private val GhostGreyPartyPaletteNight =
    ghostGreyPartyPalette(Color(0xFFE8E8E8), Color(0xFF181818), Color(0xFF525252), Color(0xFFADADAD))
val GhostGreyPartyPalette: PartyPalette get() = if (OptionColors.dark) GhostGreyPartyPaletteNight else GhostGreyPartyPaletteLight

/**
 * Pokémon Black (headless, its party screen on the player's save, HP poked low / to 0 in two slots): eight-sided boxes in
 * a dark cyan / bright cyan / dark cyan line, steel blue with a darker diagonal band (lighter blue under the cursor), dark
 * red with a pink line when fainted (magenta under the cursor), white text on a grey shadow, the "HP" letters green on
 * charcoal, a frameless bar on near-black; no Poké Ball. Empty slots are blue lines on the navy grid. Its columns stay
 * level here (the game sets the right one a quarter of a slot lower: the player's call).
 */
val BlackPartyPalette = FireRedPartyPalette.copy(
    normal = PartySlotColors(Color(0xFF0C719E), Color(0xFF96FFFF), Color(0xFF0C5175), Color(0xFF0C719E), band = Color(0xFF0C5175), stripe = Color(0xFF0C304D)),
    selected = PartySlotColors(Color(0xFF0C719E), Color(0xFF96FFFF), Color(0xFF4579A6), Color(0xFF0C719E), band = Color(0xFF4579A6), stripe = Color(0xFF0C304D)),
    fainted = PartySlotColors(Color(0xFFC73886), Color(0xFFFF9AAE), Color(0xFF8E2024), Color(0xFFC73886), band = Color(0xFF8E2024), stripe = Color(0xFF6D2824)),
    selectedFainted = PartySlotColors(Color(0xFF0C699E), Color(0xFFC7CBCF), Color(0xFFEF106D), Color(0xFF0C699E), band = Color(0xFFEF106D), stripe = Color(0xFFB61055)),
    text = Color(0xFFFBFBFB), textShadow = Color(0xFF828282),
    male = Color(0xFF00EBFB), maleShadow = Color(0xFF006961),
    female = Color(0xFFE33838), femaleShadow = Color(0xFF712030),
    hpFrame = Color(0xFF303030), hpInner = Color(0xFF303030),
    hpLabel = Color(0xFF00FB49), hpLabelShade = Color(0xFF00BA20),
    hpGreen = HpBarColors(Color(0xFF61FB61), Color(0xFF18C320)),
    hpYellow = HpBarColors(Color(0xFFFBDB00), Color(0xFFEBAA00)),
    hpRed = HpBarColors(Color(0xFFFB9A9A), Color(0xFFFB4938)),
    hpEmpty = HpBarColors(Color(0xFF202020), Color(0xFF202020)),
    ball = mapOf('K' to Color.Transparent, 'R' to Color.Transparent, 'H' to Color.Transparent, 'W' to Color.Transparent),
    chamfer = 9,
    emptyLines = Color(0xFF0C415D),
)

/**
 * Pokémon Platinum (headless, its party screen on the player's save, the lead copied into four more slots, one at 0 HP
 * and two low): blue slots - light blue over a deeper blue HP band - in a slate line, light blue in an orange line under
 * the cursor, orange-brown when fainted (salmon under the cursor), the "HP" letters red on black, a black-framed bar;
 * its pale Poké Ball. Empty slots are the slate stripes in a slate line. Its columns stay level, like Black's.
 */
val PlatinumPartyPalette = FireRedPartyPalette.copy(
    normal = PartySlotColors(Color(0xFF494961), Color(0xFF9AC3F3), Color(0xFF82B2F3), Color(0xFF41699A), band = Color(0xFF5982D3)),
    selected = PartySlotColors(Color(0xFFEB5900), Color(0xFFB2DBEB), Color(0xFF92DBF3), Color(0xFF69BAEB), band = Color(0xFF69BAEB)),
    fainted = PartySlotColors(Color(0xFF494961), Color(0xFFDBA251), Color(0xFFCB8228), Color(0xFF923800), band = Color(0xFFBA6108)),
    selectedFainted = PartySlotColors(Color(0xFFEB5900), Color(0xFFEBBAAA), Color(0xFFEBBAAA), Color(0xFFE39A79), band = Color(0xFFE39A79)),
    text = Color(0xFFFBFBFB), textShadow = Color(0xFF515151),
    male = Color(0xFF0071FB), maleShadow = Color(0xFF79BAEB),
    hpFrame = Color.Black, hpInner = Color(0xFFA2AA92),
    hpLabel = Color(0xFFFB9A9A), hpLabelShade = Color(0xFFFB4938),
    hpGreen = HpBarColors(Color(0xFF61FB61), Color(0xFF18C320)),
    hpYellow = HpBarColors(Color(0xFFFBDB00), Color(0xFFEBAA00)),
    hpRed = HpBarColors(Color(0xFFFB9A9A), Color(0xFFFB4938)),
    hpEmpty = HpBarColors(Color(0xFF697161), Color(0xFFA2AA92)),
    ball = mapOf('K' to Color(0xFF828282), 'R' to Color(0xFFFBBABA), 'H' to Color(0xFFCBAAAA), 'W' to Color(0xFFF3F3F3)),
    empty = PartySlotColors(Color(0xFF494961), Color(0xFF383849), Color(0xFF383849), Color(0xFF383849)),
    emptyLines = Color(0xFF798292),
)

/** The generic slot's palette for [game] - add a palette here to restyle a game. */
fun partyPaletteFor(game: GameKind): PartyPalette = when (game) {
    GameKind.FIRERED -> if (com.pokedaisy.app.companion.data.runningGhostGrey) GhostGreyPartyPalette else FireRedPartyPalette
    GameKind.EMERALD_ROGUE -> RoguePartyPalette
    GameKind.GAIA -> GaiaPartyPalette
    GameKind.LAZARUS -> LazarusPartyPalette
    GameKind.EMERALD_SEAGLASS -> SeaglassPartyPalette
    GameKind.ROWE -> RowePartyPalette
    GameKind.SOULGOLD -> SoulGoldPartyPalette
    GameKind.YELLOW, GameKind.YELLOW_LEGACY -> YellowPartyPalette
    GameKind.RED, GameKind.BLUE -> RedBluePartyPalette
    GameKind.PURE_RED -> PureRedPartyPalette
    GameKind.PURE_BLUE -> PureBluePartyPalette
    // Gold's and Silver's party menu is Crystal's, pixel for pixel (sampled headless in both).
    GameKind.CRYSTAL, GameKind.CRYSTAL_CLEAR, GameKind.PRISM, GameKind.GOLD, GameKind.SILVER, GameKind.CRYSTAL_LEGACY -> CrystalPartyPalette
    GameKind.POLISHED_CRYSTAL -> PolishedCrystalPartyPalette
    GameKind.GLAZED, GameKind.QUETZAL -> GlazedPartyPalette
    GameKind.IMPERIUM -> ImperiumPartyPalette
    // Dreamstone Mysteries' party grid is Imperium's, colour for colour (sampled headless: boxes, cursor, empty slots).
    GameKind.DREAMSTONE -> ImperiumPartyPalette
    GameKind.MONOMYTH -> MonomythPartyPalette
    GameKind.SWORD_SHIELD -> SwordShieldPartyPalette
    GameKind.MARIOMON -> MariomonPartyPalette
    GameKind.HEARTGOLD, GameKind.SOULSILVER -> HeartGoldPartyPalette
    GameKind.BLACK, GameKind.WHITE, GameKind.BLACK2, GameKind.WHITE2 -> BlackPartyPalette
    GameKind.PLATINUM -> PlatinumPartyPalette
    // Celia's and Too Many Types 2's slots are FireRed's own colors (only TMT2's backdrop differs).
    else -> FireRedPartyPalette
}

/**
 * A localized game's slot: English's layout with its language's art (RomArt's
 * partyfr_<lang> / partyem_<lang>) and FONT_SMALL widths. Ruby / Sapphire borrow
 * that language's Emerald art, as English Ruby / Sapphire borrow English's.
 * Null for Japanese, whose fonts aren't the Western ones the slot draws names with.
 */
private fun localPartyStyle(base: PartySlotStyle, fireRed: Boolean): PartySlotStyle? {
    val lang = when (romLanguage) {
        'S' -> "es"; 'D' -> "de"; 'F' -> "fr"; 'I' -> "it"
        // A hack's own names under an English header (Emerald Kaizo / Legacy's text keys): English's art.
        'E' -> return base
        else -> return null
    }
    val widths = (if (fireRed) localText else gameText("BPE$romLanguage"))?.smallFontWidths?.takeIf { it.isNotEmpty() }
        ?: return null
    val dir = if (fireRed) RomArt.fireRedPartyDir(lang) else RomArt.emeraldPartyDir(lang)
    return localStyles.getOrPut(dir) {
        // The Poke Ball stays English's (the same sprite in every language).
        base.copy(frameDir = dir, fontAsset = "$dir/font_small.png", statusAsset = "$dir/status_icons.png", glyphWidths = widths)
    }
}

private val localStyles = java.util.concurrent.ConcurrentHashMap<String, PartySlotStyle>()

/**
 * Let's Go Pikachu / Eevee's slot: CFRU's party menu (its slot tilemaps, layout and FireRed's font and Poké Ball) over its own
 * tiles, palette and status icons (RomArt's partylg/: navy slots, a blue one selected; the status icons still its Spanish
 * original's).
 */
private val LetsGoPartyStyle: PartySlotStyle by lazy {
    val dir = com.pokedaisy.app.companion.data.RomArt.LETS_GO_PARTY_DIR
    UnboundPartyStyle.copy(frameDir = dir, statusAsset = "$dir/status_icons.png")
}

private val UnboundFrPartyStyle: PartySlotStyle by lazy {
    val dir = com.pokedaisy.app.companion.data.RomArt.UNBOUND_FR_PARTY_DIR
    UnboundPartyStyle.copy(frameDir = dir, fontAsset = "$dir/font_small.png", statusAsset = "$dir/status_icons.png")
}

/**
 * Recharged Emerald's slot: Emerald's party_menu.c with its own art (RomArt's partyre/: its slot frames, status icons and
 * FONT_SMALL; the Poké Ball Emerald's, partyem/). Its font widened glyphs 0x30-0x33 to 5 and the 0xF9 0x18 symbol to 8
 * (its gFontSmallLatinGlyphWidths, 0x08869844 in v2.2.8; the rest Emerald's).
 */
private val RechargedEmeraldPartyStyle: PartySlotStyle by lazy {
    val dir = com.pokedaisy.app.companion.data.RomArt.RE_PARTY_DIR
    EmeraldPartyStyle.copy(
        frameDir = dir, fontAsset = "$dir/font_small.png", statusAsset = "$dir/status_icons.png",
        glyphWidths = EmeraldPartyStyle.glyphWidths.copyOf().also { w ->
            for (g in 0x30..0x33) w[g] = 5
            w[0x118] = 8
        },
    )
}

/** Elite Redux's and Team Rocket Edition: Dragonsden's party slots: Emerald's, but the names in their own mixed case
 * ("Zekrom", "Houndoom"), as their party menus print them. */
private val EliteReduxPartyStyle by lazy { EmeraldPartyStyle.copy(upperCaseNames = false) }

/** The in-game party-slot look for [game], or null for games that don't have one yet. */
fun partySlotStyleFor(game: GameKind): PartySlotStyle? = when (game) {
    GameKind.FIRERED -> when {
        // Ghost Grey's grey slots aren't FireRed's art: its palette slot.
        com.pokedaisy.app.companion.data.runningGhostGrey -> null
        // Let's Go's party menu is CFRU's in its own blues (RomArt's partylg/).
        com.pokedaisy.app.companion.data.runningLetsGo -> LetsGoPartyStyle
        romGameCode.isEmpty() -> FireRedPartyStyle
        else -> localPartyStyle(FireRedPartyStyle, fireRed = true)
    }
    GameKind.EMERALD -> when {
        com.pokedaisy.app.companion.data.runningRechargedEmerald -> RechargedEmeraldPartyStyle
        romGameCode.isEmpty() -> EmeraldPartyStyle
        else -> localPartyStyle(EmeraldPartyStyle, fireRed = false)
    }
    // Elite Redux's party menu is Emerald's slots (its ROM kept the art: RomArt's partyem/) in a two-column grid.
    GameKind.ELITE_REDUX -> EliteReduxPartyStyle
    // Team Rocket Edition: Dragonsden's party menu is Emerald's slots and list, as its battle party screen showed.
    GameKind.TEAM_ROCKET -> EliteReduxPartyStyle
    // So is Wishes of Tomorrow's (its own blue backdrop: Theme's backdropColors).
    GameKind.WISHES -> EliteReduxPartyStyle
    GameKind.HEART_AND_SOUL -> HeartAndSoulPartyStyle
    // Unbound FR redrew "HP" / "Lv" as "PV" / "N." and its status icons (GitHub #227): its own art, English's layout.
    GameKind.UNBOUND -> if (romGameCode == com.pokedaisy.app.companion.data.UNBOUND_FR_TEXT) UnboundFrPartyStyle else UnboundPartyStyle
    GameKind.RADICAL_RED -> RadicalRedPartyStyle
    GameKind.ODYSSEY -> OdysseyPartyStyle
    GameKind.AMETHYST -> AmethystPartyStyle
    // CFRU's slot in its own palette (RomArt's partyswsh/); SwordShieldPartyPalette until a scan has made it.
    GameKind.SWORD_SHIELD -> SwordShieldPartyStyle
    // CFRU's own slots and grid (RomArt's partycfru/, found in its ROM), no Poké Ball.
    GameKind.GS_CHRONICLES -> GsChroniclesPartyStyle
    // Orange Islands kept FireRed's party menu art (its own backdrop: theme's backdropColors).
    GameKind.ORANGE_ISLANDS -> FireRedPartyStyle
    // Recharged Yellow's party menu is FireRed's, slots and teal backdrop alike (sampled headless: 216B63
    // stripes, the bag FireRed's too). Its ROM changed the backdrop tiles, the status icons
    // and the small font (RomArt finds only the slot frames and Poké Ball there), so the slot art comes from a
    // FireRed ROM's scan; without one, FireRed's palette slot.
    GameKind.RECHARGED_YELLOW -> FireRedPartyStyle
    else -> null
}

// FireRed's closed party Poke Ball (partyfr/pokeball.png, 20x20 of its 32x32 cell).
internal val PartyBall = listOf(
    ".......KKKKKK.......",
    ".....KKKKKKKKKK.....",
    "....KKKRRRRRRKKK....",
    "...KKRRRRRRRRRRKK...",
    "..KKRRRRRRRRRRRRKK..",
    ".KKRRRRRRRRRRRRRRKK.",
    ".KKRRRRRRRRRRRRRRKK.",
    "KKRRRRRRKKKKRRRRRRKK",
    "KKHHHHHKKWWKKRRRRRKK",
    "KKKKKKKKWWWWKKKKKKKK",
    "KKKKKKKKWWWWKKKKKKKK",
    "KKWWWWWKKWWKKWWWWWKK",
    "KKWWWWWWKKKKWWWWWWKK",
    ".KKWWWWWWWWWWWWWWKK.",
    ".KKWWWWWWWWWWWWWWKK.",
    "..KKWWWWWWWWWWWWKK..",
    "...KKWWWWWWWWWWKK...",
    "....KKKWWWWWWKKK....",
    ".....KKKKKKKKKK.....",
    ".......KKKKKK.......",
)

// The "HP" label's letters, 5 rows (the top 3 in hpLabel, the rest in hpLabelShade).
internal val HpLetters = listOf(
    "1001.1110",
    "1001.1001",
    "1111.1110",
    "1001.1000",
    "1001.1000",
)

/**
 * Where everything sits, in whole slot pixels [u] (screen px): FireRed's MAIN
 * slot (80x56 window, 49px-tall frame with the Poke Ball overhanging 8px left
 * and 6px up) scaled by a whole number and stretched to the cell, the way
 * [GbaPartySlot] stretches the real one.
 */
private class SlotGeometry(w: Float, h: Float) {
    /** Screen pixels per slot pixel before rounding down: the text goes by it ([u] alone shrank it a whole step when a
     * slot came out just short of the next pixel - a DS game's companion over its button bar). */
    val scale = min(w / 86f, h / 57f)
    val u = max(2, floor(scale).toInt())
    val fx = 8f * u
    val fy = 6f * u
    val fw = floor((w - fx - 2 * u) / u) * u
    val fh = floor((h - fy - 2 * u) / u) * u
    val bottom = fy + fh
    /** The light row splitting the name area from the HP band. */
    val dividerY = bottom - 19 * u
    val barY = dividerY + u
    val labelX = fx + 20 * u
    val barX = labelX + 11 * u
    val barRight = fx + fw - 4 * u
    val textX = fx + 30 * u
    /** Name and level line centres, spread over the upper area however tall it got. */
    val nameMid = fy + 3 * u + (dividerY - fy - 3 * u) * 0.3f
    val levelMid = fy + 3 * u + (dividerY - fy - 3 * u) * 0.74f
    val hpTextMid = barY + 7 * u + 3 * u
}

@Composable
private fun PartySlot(palette: PartyPalette, mon: MonView?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, sound: Boolean = true) {
    val density = LocalDensity.current
    // The selected icon's bob (SpriteCB_BouncePartyMonIcon): up / down every 10 frames.
    val bobDown by rememberBlink(gbaFramesMs(10), enabled = selected && CompanionTweaks[CompanionTweaks.Tweak.PARTY_ICONS_MOVE])
    BoxWithConstraints(modifier.slotClickable(sound, enabled = mon != null, onClick = onClick)) {
        val g = with(density) { SlotGeometry(maxWidth.toPx(), maxHeight.toPx()) }
        val u = g.u
        val colors = when {
            mon == null -> palette.empty ?: if (selected) palette.selected else palette.normal
            mon.isEgg -> if (selected) palette.selected else palette.normal
            mon.hp == 0 -> if (selected) palette.selectedFainted else palette.fainted
            selected -> palette.selected
            else -> palette.normal
        }
        val showHp = mon != null && !mon.isEgg
        Canvas(
            Modifier.fillMaxSize().then(
                if (mon == null && palette.empty == null && palette.emptyLines == null) {
                    Modifier.graphicsLayer { alpha = palette.emptyAlpha; compositingStrategy = CompositingStrategy.Offscreen }
                } else Modifier,
            ),
        ) {
            when {
                mon == null && palette.emptyLines != null -> drawEmptyLines(g, palette, palette.emptyLines)
                palette.chamfer > 0 -> drawCutSlotFrame(g, colors, palette.chamfer)
                else -> drawSlotFrame(g, colors)
            }
            if (showHp) drawHpBar(g, palette, mon!!.hp, mon.maxHp)
        }
        if (mon == null) return@BoxWithConstraints

        // Pixel Operator's caps are 9 font pixels to the game font's 7: 3/4 of a slot pixel keeps them in proportion.
        val m = slotTextMetrics(density, max(1, (max(u.toFloat(), g.scale) * 0.75f).roundToInt()))
        // A game font (Yellow's, drawn condensed) sets the name and level larger: a slot pixel per font pixel.
        val nameM = if (com.pokedaisy.app.companion.ui.theme.LocalGameFont.current != null) slotTextMetrics(density, max(1, u)) else m
        val chipText = slotTextMetrics(density, max(1, (u * 0.6f).roundToInt()))
        fun Density.at(x: Float, midY: Float, tm: GbaTextMetrics = m) = Modifier.offset { IntOffset(x.toInt(), (midY - 8 * tm.px).toInt()) }
        val text = colors.text ?: palette.text
        val textShadow = colors.textShadow ?: palette.textShadow

        // Poke Ball, then the icon over it: resting 4px up, bobbing -3/+1 while selected.
        Canvas(Modifier.fillMaxSize()) { drawPixelBitmap(PartyBall, palette.ball, g.fx - 6 * u, g.fy - 4 * u, u.toFloat()) }
        val iconDy = if (!selected) -4 else if (!bobDown) -3 else 1
        SpeciesIcon(
            mon.iconAsset, size = with(density) { (32 * u).toDp() },
            modifier = Modifier.offset { IntOffset((g.fx - 8 * u).toInt(), (g.fy + iconDy * u).toInt()) },
        )
        // The held-item marker, where GbaPartySlot puts it: the resting icon's place + (16, 22). It doesn't bob.
        HeldItemMarker(mon.heldItemId, u, g.fx - 8 * u + 16 * u, g.fy + 22 * u)

        val textWidth = with(density) { (g.fx + g.fw - 4 * u - g.textX).toDp() }
        GbaText(mon.shownName, text, textShadow, nameM, density.at(g.textX, g.nameMid, nameM).width(textWidth))
        if (!showHp) return@BoxWithConstraints

        val status = if (mon.hp == 0) "FNT" else mon.status
        if (status.isNotEmpty()) {
            StatusChip(status, u, chipText, Modifier.offset { IntOffset((g.textX + 6 * u).toInt(), (g.levelMid - 5 * u).toInt()) })
        } else {
            GbaText(tr("Lv{0}", mon.level), text, textShadow, nameM, density.at(g.textX + 6 * u, g.levelMid, nameM))
        }
        val gender = when (mon.genderSymbol) {
            GENDER_SYMBOL_MALE -> Triple(PixelIcons.male, palette.male, palette.maleShadow)
            GENDER_SYMBOL_FEMALE -> Triple(PixelIcons.female, palette.female, palette.femaleShadow)
            else -> null
        }
        gender?.let { (bitmap, color, shadow) ->
            Canvas(Modifier.fillMaxSize()) {
                val x = g.fx + g.fw - 14 * u
                val y = g.levelMid - 4 * u
                drawPixelBitmap(bitmap, mapOf('1' to shadow), x + u, y + u, u.toFloat())
                drawPixelBitmap(bitmap, mapOf('1' to color), x, y, u.toFloat())
            }
        }
        Box(density.at(0f, g.hpTextMid).width(with(density) { g.barRight.toDp() }), contentAlignment = Alignment.TopEnd) {
            GbaText("${mon.hp}/${mon.maxHp}", text, textShadow, m)
        }
    }
}

/** Pixel-font metrics at [fp] whole screen pixels per font pixel. */
internal fun slotTextMetrics(density: Density, fp: Int) = with(density) {
    GbaTextMetrics(fp, fp.toDp(), (16f * fp).toSp(), fp.toFloat(), (16f * fp).toDp(), (20f * fp).toDp())
}

@Composable
internal fun StatusChip(status: String, u: Int, m: GbaTextMetrics, modifier: Modifier) {
    val color = if (status == "FNT") Color(0xFF6B6B6B) else AppColors.statusColor(status)
    val dp = with(LocalDensity.current) { u.toDp() }
    Box(
        modifier
            .clip(PixelRoundedShape(dp * 2))
            .background(lerp(color, Color.Black, 0.35f))
            .padding(dp)
            .clip(PixelRoundedShape(dp))
            .background(color)
            .height(dp * 8)
            .padding(horizontal = dp * 3),
        contentAlignment = Alignment.Center,
    ) {
        GbaText(status, Color.White, lerp(color, Color.Black, 0.5f), m)
    }
}

private fun DrawScope.drawSlotFrame(g: SlotGeometry, c: PartySlotColors) {
    val u = g.u.toFloat()
    drawPixelRoundRect(c.outline, Offset(g.fx, g.fy), Size(g.fw, g.fh), radius = 3 * u, step = u)
    // Light inner edge, then the HP band below the divider (the edge's color on FireRed).
    drawPixelRoundRect(c.light, Offset(g.fx + 2 * u, g.fy + 2 * u), Size(g.fw - 4 * u, g.fh - 4 * u), radius = u, step = u)
    if (c.band != c.light) drawRect(c.band, Offset(g.fx + 3 * u, g.dividerY), Size(g.fw - 6 * u, g.bottom - 3 * u - g.dividerY))
    drawRect(c.fill, Offset(g.fx + 3 * u, g.fy + 3 * u), Size(g.fw - 6 * u, g.dividerY - g.fy - 3 * u))
    drawRect(c.shade, Offset(g.fx + 2 * u, g.bottom - 3 * u), Size(g.fw - 4 * u, u))
}

/** A box [w] x [h] slot pixels at ([x], [y]) with its corners cut [cut] pixels at 45 degrees, row by row in [c]; with
 * [span], each row's own part of it (columns from, to) instead of the whole row. */
private fun DrawScope.cutBox(x: Float, y: Float, w: Int, h: Int, cut: Int, u: Float, c: Color, span: ((Int) -> IntRange)? = null) {
    for (r in 0 until h) {
        val inset = maxOf(0, cut - r, cut - (h - 1 - r))
        var from = inset
        var to = w - inset
        span?.invoke(r)?.let { s -> from = maxOf(from, s.first); to = minOf(to, s.last) }
        if (to > from) drawRect(c, Offset(x + from * u, y + r * u), Size((to - from) * u, u))
    }
}

/** Black's party slot: three 1px lines round the box ([PartySlotColors.outline], light, shade), its corners cut, the
 * fill crossed by the diagonal [PartySlotColors.stripe] (leaning "/", a pixel left a row). */
private fun DrawScope.drawCutSlotFrame(g: SlotGeometry, c: PartySlotColors, cut: Int) {
    val u = g.u.toFloat()
    val w = (g.fw / u).toInt()
    val h = (g.fh / u).toInt()
    cutBox(g.fx, g.fy, w, h, cut, u, c.outline)
    cutBox(g.fx + u, g.fy + u, w - 2, h - 2, cut, u, c.light)
    cutBox(g.fx + 2 * u, g.fy + 2 * u, w - 4, h - 4, cut, u, c.shade)
    val fw = w - 6
    cutBox(g.fx + 3 * u, g.fy + 3 * u, fw, h - 6, cut, u, c.fill)
    val stripe = c.stripe ?: return
    val start = fw * 62 / 100
    val width = fw * 46 / 100
    cutBox(g.fx + 3 * u, g.fy + 3 * u, fw, h - 6, cut, u, stripe) { r -> (start - r)..(start - r + width) }
}

/** A DS game's empty slot: [lines] every other row inside the slot's shape, over [PartyPalette.empty]'s fill in its outline. */
private fun DrawScope.drawEmptyLines(g: SlotGeometry, p: PartyPalette, lines: Color) {
    val u = g.u.toFloat()
    val w = (g.fw / u).toInt()
    val h = (g.fh / u).toInt()
    val box = p.empty
    if (p.chamfer > 0) {
        box?.let { cutBox(g.fx, g.fy, w, h, p.chamfer, u, it.outline); cutBox(g.fx + u, g.fy + u, w - 2, h - 2, p.chamfer, u, it.fill) }
        for (r in 1 until h - 1 step 2) cutBox(g.fx, g.fy + r * u, w, 1, 0, u, lines) { (p.chamfer - minOf(r, h - 1 - r)).coerceAtLeast(0)..(w - (p.chamfer - minOf(r, h - 1 - r)).coerceAtLeast(0)) }
    } else {
        box?.let {
            drawPixelRoundRect(it.outline, Offset(g.fx, g.fy), Size(g.fw, g.fh), radius = 3 * u, step = u)
            drawPixelRoundRect(it.fill, Offset(g.fx + 2 * u, g.fy + 2 * u), Size(g.fw - 4 * u, g.fh - 4 * u), radius = u, step = u)
        }
        val inset = if (box != null) 2 else 0
        for (r in inset + 1 until h - inset step 2) drawRect(lines, Offset(g.fx + inset * u, g.fy + r * u), Size(g.fw - 2 * inset * u, u))
    }
}

private fun DrawScope.drawHpBar(g: SlotGeometry, p: PartyPalette, hp: Int, maxHp: Int) {
    val u = g.u.toFloat()
    // "HP" label box, sharing its right edge with the bar's outline.
    drawPixelRoundRect(p.hpFrame, Offset(g.labelX, g.barY), Size(g.barX - g.labelX + u, 7 * u), radius = u, step = u)
    HpLetters.forEachIndexed { row, line ->
        line.forEachIndexed { col, ch ->
            if (ch == '1') drawRect(
                if (row < 3) p.hpLabel else p.hpLabelShade,
                Offset(g.labelX + (1 + col) * u, g.barY + (1 + row) * u), Size(u, u),
            )
        }
    }
    val w = g.barRight - g.barX
    drawPixelRoundRect(p.hpFrame, Offset(g.barX, g.barY), Size(w, 7 * u), radius = 2 * u, step = u)
    drawPixelRoundRect(p.hpInner, Offset(g.barX + u, g.barY + u), Size(w - 2 * u, 5 * u), radius = u, step = u)
    val inner = w - 4 * u
    val filled = if (maxHp > 0) (floor(inner / u * hp.coerceIn(0, maxHp) / maxHp) * u).coerceAtLeast(if (hp > 0) u else 0f) else 0f
    val color = when {
        maxHp <= 0 || hp * 2 > maxHp -> p.hpGreen
        hp * 5 > maxHp -> p.hpYellow
        else -> p.hpRed
    }
    fun band(c: HpBarColors, x: Float, width: Float) {
        if (width <= 0f) return
        drawRect(c.top, Offset(x, g.barY + 2 * u), Size(width, u))
        drawRect(c.main, Offset(x, g.barY + 3 * u), Size(width, 2 * u))
    }
    band(p.hpEmpty, g.barX + 2 * u, inner)
    band(color, g.barX + 2 * u, filled)
}

/** [bitmap] rows with each character colored by [palette] (others transparent), [cell] px per pixel. */
internal fun DrawScope.drawPixelBitmap(bitmap: List<String>, palette: Map<Char, Color>, x: Float, y: Float, cell: Float) {
    bitmap.forEachIndexed { row, line ->
        line.forEachIndexed { col, ch ->
            palette[ch]?.let { drawRect(it, Offset(x + col * cell, y + row * cell), Size(cell, cell)) }
        }
    }
}

/** The party menu's held-item marker (RomArt.HOLD_ICONS, from the ROM): an item, or Mail's, [u] screen px per GBA px
 * with its top-left at ([x], [y]). Nothing while no ROM has supplied the art, or for [itemId] 0. */
@Composable
internal fun HeldItemMarker(itemId: Int, u: Int, x: Float, y: Float) {
    if (itemId == 0) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val gen = rememberArtGeneration()
    val icons = androidx.compose.runtime.remember(gen) {
        // The Gen 2 games' own marks (Gen2Art), the GBA games' from RomArt.
        GameArt.get(context, when (com.pokedaisy.app.companion.data.activeGame) {
            com.pokedaisy.app.companion.data.GameKind.CRYSTAL -> com.pokedaisy.app.companion.data.Gen2Art.CRYSTAL_HOLD_ICONS
            com.pokedaisy.app.companion.data.GameKind.CRYSTAL_CLEAR -> com.pokedaisy.app.companion.data.Gen2Art.CRYSTAL_CLEAR_HOLD_ICONS
            com.pokedaisy.app.companion.data.GameKind.CRYSTAL_LEGACY -> com.pokedaisy.app.companion.data.Gen2Art.CRYSTAL_LEGACY_HOLD_ICONS
            com.pokedaisy.app.companion.data.GameKind.PRISM -> com.pokedaisy.app.companion.data.Gen2Art.PRISM_HOLD_ICONS
            com.pokedaisy.app.companion.data.GameKind.POLISHED_CRYSTAL -> com.pokedaisy.app.companion.data.Gen2Art.POLISHED_HOLD_ICONS
            com.pokedaisy.app.companion.data.GameKind.GOLD, com.pokedaisy.app.companion.data.GameKind.SILVER ->
                com.pokedaisy.app.companion.data.Gen2Art.GOLD_SILVER_HOLD_ICONS
            else -> RomArt.HOLD_ICONS
        })?.asImageBitmap()
    } ?: return
    val mail = if (com.pokedaisy.app.companion.data.activeGame.isGen2) {
        itemId in com.pokedaisy.app.companion.data.activeGen2.mailItems
    } else itemId in com.pokedaisy.app.companion.data.MAIL_ITEMS
    Canvas(Modifier.fillMaxSize()) {
        drawImage(
            icons, srcOffset = IntOffset(0, if (mail) 8 else 0), srcSize = androidx.compose.ui.unit.IntSize(8, 8),
            dstOffset = IntOffset(x.toInt(), y.toInt()), dstSize = androidx.compose.ui.unit.IntSize(8 * u, 8 * u),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
        )
    }
}
