package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import kotlin.math.hypot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.FieldHudLook
import com.pokedaisy.app.companion.NewFeatures
import com.pokedaisy.app.companion.QuickAction
import com.pokedaisy.app.companion.QuickMenuStyle
import com.pokedaisy.app.companion.REPEL_KINDS
import com.pokedaisy.app.companion.data.FlyTargets
import com.pokedaisy.app.companion.data.dsFlySpotName
import com.pokedaisy.app.companion.data.KeyItems
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.activeMapSecData
import com.pokedaisy.app.companion.data.isDsGame
import com.pokedaisy.app.companion.data.gameCase
import com.pokedaisy.app.companion.data.itemName
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What the QUICK MENU's actions do in the running game (PokeDaisyActivity). */
interface QuickActions {
    /** Offered at all: the game has it (PC / FLY code, the BIKE / ROD register's layout, WILD's switch; COMPANION on one screen). */
    fun offered(action: QuickAction): Boolean

    /** Runs [action]; FLY answers with its destinations ([QuickMenuState.fly]) or what kept it from starting. */
    fun run(action: QuickAction)

    /** FLY to [mapsec], one of [targets]' destinations. */
    fun flyTo(mapsec: Int, targets: FlyTargets)

    /** Uses one [itemId] of the bag's REPEL kinds ([REPEL_KINDS]). */
    fun repel(itemId: Int) {}

    /** The player is on a bike now (the field read's gPlayerAvatar): BIKE is GET OFF. */
    fun riding(): Boolean = false

    /** FREE FLIGHT is up (EmulatorEngine.flightState): TAKE OFF is LAND. */
    fun flying(): Boolean = false

    /** WILD ENCOUNTERS now, as SETTINGS has it (100 = NORMAL, 0 = OFF). */
    fun wildEncounters(): Int = 100

    /** Steps left on the REPEL in effect (0 = none). */
    fun repelSteps(): Int = 0

    /** FLASH would light this place up (a dark cave, the light not on yet); false = it's light here, null = can't tell. */
    fun flashDark(): Boolean? = null
}

/**
 * The QUICK MENU (the QUICK MENU hotkey, L3 by default): a press opens it, the D-pad (or, as a [ring], the left stick)
 * picks an action and A runs it; B or the button again closes it. Taps work too. FLY, and REPEL with more than one kind in the bag, turn it into a list. Compose state; its keys come from the
 * activity while it's open ([key], [aim], [trigger]), withheld from the game like the companion window's.
 */
class QuickMenuState {
    var open by mutableStateOf(false)
        private set

    /** Laid out as a circle (QUICK MENU > STYLE): the stick points at a tile ([aim]). */
    var ring by mutableStateOf(false)

    /** Each tile's name under its icon (QUICK MENU > LABELS); without, the highlighted one is named on the hint line / in
     * the ring's middle. */
    var labels by mutableStateOf(true)

    /** Where the ring put each tile, as a turn from the top clockwise (0..1), for [aim]; set as it lays out. */
    internal var ringTurns: FloatArray = FloatArray(0)

    /** The highlighted tile, in [items]. */
    var index by mutableIntStateOf(0)
        private set

    /** FLY's destinations once asked for: the menu turns into their list. */
    var fly by mutableStateOf<FlyTargets?>(null)

    /** REPEL's kinds in the bag (item ids) when there's more than one: the menu turns into their list. */
    var repels by mutableStateOf<List<Int>?>(null)

    /** The highlighted row of FLY's / REPEL's list. */
    var pickIndex by mutableIntStateOf(0)
        private set

    /** The tiles shown (set as it draws), so the D-pad knows how many there are. */
    internal var items: List<QuickAction> = emptyList()

    /** Why each tile is dimmed now (null = ready), as it draws. */
    internal var blocked: (QuickAction) -> String? = { null }

    /** REPEL's kinds in the bag, weakest first, as it draws. */
    internal var repelKinds: List<Int> = emptyList()

    /** FLY's destination ids in its list's order. */
    internal var flyIds: List<Int> = emptyList()

    /** Runs a tile / flies / uses a REPEL (the activity's). */
    var onRun: (QuickAction) -> Unit = {}
    var onFly: (Int, FlyTargets) -> Unit = { _, _ -> }
    var onRepel: (Int) -> Unit = {}

    /** It closed (any way: a pick, B, the button, a tap outside). */
    var onClose: () -> Unit = {}

    /** The left stick moved the highlight to another tile ([aim]): the activity's haptic tick. */
    var onAimMove: () -> Unit = {}

    private val listing get() = fly != null || repels != null
    private val listSize get() = (if (fly != null) flyIds.size else repels?.size ?: 0).coerceAtLeast(1)

    /**
     * The hotkey: a press opens it, or closes it again; letting go does nothing. (Holding it, moving and letting go to run
     * the pick was tried and dropped, the user's call: a slip on release ran an action nobody meant.)
     */
    fun trigger(pressed: Boolean) {
        if (!pressed) return
        if (open) close() else {
            open = true
            fly = null
            repels = null
        }
    }

    fun close() {
        val was = open
        open = false
        fly = null
        repels = null
        if (was) onClose()
    }

    /** From a list back to the tiles. */
    fun back() {
        fly = null
        repels = null
    }

    /** A game button while it's open: true = taken. */
    fun key(key: MenuKey): Boolean {
        if (!open) return false
        if (listing) {
            when (key) {
                MenuKey.UP -> pickIndex = (pickIndex - 1).mod(listSize)
                MenuKey.DOWN -> pickIndex = (pickIndex + 1).mod(listSize)
                MenuKey.A -> pick()
                MenuKey.B -> back()
                else -> {}
            }
            return true
        }
        val n = items.size.coerceAtLeast(1)
        when (key) {
            MenuKey.LEFT, MenuKey.UP -> index = (index - 1).mod(n)
            MenuKey.RIGHT, MenuKey.DOWN -> index = (index + 1).mod(n)
            MenuKey.A -> activate()
            MenuKey.B, MenuKey.START -> close()
            else -> {}
        }
        return true
    }

    /**
     * The left stick on the [ring], pushed out past the dead zone ([x] right, [y] down, as Android reports them): the tile
     * that way, the first one at the top and on round clockwise. True = taken (a ring with tiles, no list up).
     */
    fun aim(x: Float, y: Float): Boolean {
        if (!open || !ring || listing || items.isEmpty()) return false
        if (sqrt(x * x + y * y) < AIM_DEAD) return true
        val n = items.size
        val turn = (atan2(x, -y) / (2 * PI)).toFloat().let { if (it < 0) it + 1 else it }
        // The tile nearest that way: by where the ring put them (an oval spaces them unevenly), else evenly round.
        val turns = ringTurns.takeIf { it.size == n } ?: FloatArray(n) { it / n.toFloat() }
        fun apart(a: Float, b: Float) = abs(a - b).let { minOf(it, 1 - it) }
        val i = turns.indices.minByOrNull { apart(turns[it], turn) } ?: 0
        if (i != index) {
            index = i
            onAimMove()
        }
        return true
    }

    /** A tap on tile [i]. */
    fun tap(i: Int) {
        index = i
        activate()
    }

    /** A tap on row [i] of FLY's / REPEL's list. */
    fun tapPick(i: Int) {
        pickIndex = i
        pick()
    }

    /** Back on the first tile when the list got shorter (a setting changed). */
    internal fun clamp() {
        if (index >= items.size) index = 0
    }

    internal fun startFly(targets: FlyTargets, ids: List<Int>) {
        flyIds = ids
        pickIndex = 0
        fly = targets
    }

    private fun pick() {
        val f = fly
        if (f != null) {
            flyIds.getOrNull(pickIndex)?.let { id -> close(); onFly(id, f) }
            return
        }
        repels?.getOrNull(pickIndex)?.let { id -> close(); onRepel(id) }
    }

    private fun activate() {
        val a = items.getOrNull(index) ?: return
        // Dimmed: it stays open, its reason already on the hint line.
        if (blocked(a) != null) return
        when {
            a == QuickAction.FLY -> onRun(a)
            // More than one kind: which one is the player's.
            a == QuickAction.REPEL && repelKinds.size > 1 -> {
                pickIndex = 0
                repels = repelKinds
            }
            a == QuickAction.REPEL -> repelKinds.firstOrNull()?.let { id -> close(); onRepel(id) }
            else -> { close(); onRun(a) }
        }
    }

    private companion object {
        const val AIM_DEAD = 0.5f
    }
}

/** REPEL's kinds the bag holds, weakest first. */
fun repelKindsIn(s: SnapshotView): List<Int> =
    REPEL_KINDS.map { it.first }.filter { id -> s.items.any { it.itemId == id && it.quantity > 0 } }

/** Why [action] can't run now in [s]; null = it can. The game-side checks (a menu up, fields locked) answer when it runs. */
fun quickBlocked(action: QuickAction, s: SnapshotView, repelSteps: Int = 0, dark: Boolean? = null, flying: Boolean = false): String? {
    val field = !s.inBattle
    return when (action) {
        // Up there, LAND always can (the bird finds the nearest ground).
        QuickAction.FLIGHT -> when {
            flying -> null
            !field -> tr("Not during a battle")
            s.party.none { !it.isEgg && it.moves.any { mv -> mv.id == FLY_MOVE } } -> tr("No one in your party knows FLY")
            else -> null
        }
        QuickAction.PC -> if (!field) tr("Not during a battle") else null
        QuickAction.FLY -> when {
            !field -> tr("Not during a battle")
            s.party.none { !it.isEgg && it.moves.any { mv -> mv.id == FLY_MOVE } } -> tr("No one in your party knows FLY")
            else -> null
        }
        QuickAction.FLASH -> when {
            !field -> tr("Not during a battle")
            s.party.none { !it.isEgg && it.moves.any { mv -> mv.id == FLASH_MOVE } } -> tr("No one in your party knows FLASH")
            dark == false -> tr("It isn't dark here")
            else -> null
        }
        QuickAction.REPEL -> when {
            !field -> tr("Not during a battle")
            repelSteps > 0 -> tr("Still working: {0} steps left", repelSteps)
            repelKindsIn(s).isEmpty() -> tr("No REPEL in your bag")
            else -> null
        }
        QuickAction.BIKE -> when {
            !field -> tr("Not during a battle")
            KeyItems.firstIn(s.items, KeyItems.BIKES) == null -> tr("No bike in your bag")
            else -> null
        }
        QuickAction.ROD -> when {
            !field -> tr("Not during a battle")
            KeyItems.firstIn(s.items, KeyItems.RODS) == null -> tr("No rod in your bag")
            else -> null
        }
        else -> null
    }
}

/** Why FLASH didn't start, in the companion's words (the HUD's notice). */
internal fun flashMessage(r: com.pokedaisy.app.companion.data.FlashResult): String = when (r) {
    com.pokedaisy.app.companion.data.FlashResult.NOT_NOW -> tr("Not now: close the game's menus and battles first.")
    com.pokedaisy.app.companion.data.FlashResult.IN_BATTLE -> tr("You can't do that during a battle.")
    com.pokedaisy.app.companion.data.FlashResult.NO_BADGE -> tr("FLASH needs a new BADGE first.")
    com.pokedaisy.app.companion.data.FlashResult.NO_FLASHER -> tr("No one in your party knows FLASH")
    com.pokedaisy.app.companion.data.FlashResult.CANT_USE_HERE -> tr("It isn't dark here")
    com.pokedaisy.app.companion.data.FlashResult.ALREADY_LIT -> tr("This place is lit already.")
    else -> tr("It can't be used here.")
}

/** A tile's text under its icon. */
private fun quickLabel(action: QuickAction, actions: QuickActions): String = when (action) {
    QuickAction.BIKE -> if (actions.riding()) tr("GET OFF") else tr(action.label)
    QuickAction.FLIGHT -> if (actions.flying()) tr("LAND") else tr(action.label)
    QuickAction.WILD -> tr("WILD")
    else -> tr(action.label)
}

/** The hint line for the highlighted tile when it can run: what it does, where the label alone doesn't say. */
private fun quickHint(action: QuickAction, actions: QuickActions): String = when (action) {
    QuickAction.WILD -> tr("WILD ENCOUNTERS") + ": " + tr(wildEncountersLabel(actions.wildEncounters()))
    else -> quickLabel(action, actions)
}

/**
 * The QUICK MENU over the game (the HUD's view, so it's there in a battle too): the player's actions as tiles of one
 * size, in rows in a panel in the middle or round a circle ([QuickMenuState.ring]), with a hint line (the highlighted
 * action, or why it's dimmed), over a light dim whose tap closes it. [look]: the HUD's LOOK.
 */
@Composable
fun QuickMenu(state: QuickMenuState, snapshot: SnapshotView, actions: QuickActions, shown: Set<QuickAction>, look: FieldHudLook) {
    // 0 closed, 1 open: the tiles come out of the middle one by one ([tileOut]) and go back the same way, the last one first.
    // It stays drawn while they go, the keys already the game's.
    val fan by androidx.compose.animation.core.animateFloatAsState(
        if (state.open) 1f else 0f,
        // Not LIGHT COMPANION's business: it moves a dozen tiles at placement, nothing composes.
        androidx.compose.animation.core.tween(if (state.open) RING_OPEN_MS else RING_CLOSE_MS, easing = androidx.compose.animation.core.LinearEasing),
        label = "quick-menu-fan",
    )
    if (!state.open && fan == 0f) return
    val items = QuickAction.entries.filter { it in shown && actions.offered(it) }
    val repelSteps = actions.repelSteps()
    val dark = if (QuickAction.FLASH in shown) actions.flashDark() else null
    state.items = items
    val flying = actions.flying()
    state.blocked = { quickBlocked(it, snapshot, repelSteps, dark, flying) }
    state.repelKinds = repelKindsIn(snapshot)
    LaunchedEffect(items.size) { state.clamp() }
    val m = rememberGbaTextMetrics(1f)
    val u = m.u
    CompanionBackHandler(enabled = state.open) { if (state.fly != null || state.repels != null) state.back() else state.close() }
    CompositionLocalProvider(LocalHudLook provides look) {
        BoxWithConstraints(
            Modifier.fillMaxSize().drawBehind { drawRect(Color(0x55000000).copy(alpha = 0x55 / 255f * fan)) }
                .pointerInput(Unit) { detectTapGestures { state.close() } },
            contentAlignment = Alignment.Center,
        ) {
            val fly = state.fly
            val repels = state.repels
            val current = items.getOrNull(state.index)
            val why = current?.let { quickBlocked(it, snapshot, repelSteps, dark, flying) }
            val hint = current?.let { why ?: quickHint(it, actions) }
            val ring = state.ring
            val tile: @Composable (Int) -> Unit = { i ->
                val a = items[i]
                QuickTile(a, snapshot, actions, selected = i == state.index, blocked = quickBlocked(a, snapshot, repelSteps, dark, flying) != null, m, label = state.labels) { state.tap(i) }
            }
            if (fly != null || repels != null || items.isEmpty() || !ring) {
                Column(
                    // Taps on the panel stay in it (the dim around it closes the menu).
                    Modifier.graphicsLayer { alpha = fan }.widthIn(max = maxWidth * 0.92f).hudPanel(u).pointerInput(Unit) { detectTapGestures { } }.padding(u * 6),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(u * 4),
                ) {
                    when {
                        fly != null -> FlyDestinations(state, fly, m)
                        repels != null -> RepelKinds(state, repels, snapshot, m)
                        items.isEmpty() -> GbaText(tr("Nothing to show: pick actions in SETTINGS > QUICK MENU"), ink.text, ink.shadow, m, maxLines = 2)
                        else -> {
                            TileGrid(items.size, gap = u * 5, tile)
                            if (hint != null) GbaText(hint, ink.text, ink.shadow, rememberGbaTextMetrics(0.75f), maxLines = 2)
                        }
                    }
                }
            } else {
                // The highlighted one's name (and why it's dimmed) in the middle, like a game's weapon wheel.
                TileRing(state, items.size, gap = u * 10, maxW = maxWidth * 0.96f, maxH = maxHeight * 0.96f, { fan }, tile) {
                    // With names on the tiles, the middle only says why the highlighted one is dimmed (or WILD's state).
                    val name = if (state.labels) null else quickLabel(current ?: QuickAction.PC, actions)
                    val note = why ?: if (current == QuickAction.WILD) quickHint(current, actions) else null
                    if (current != null && (name != null || note != null)) {
                        Column(
                            Modifier.hudPanel(u).padding(horizontal = u * 5, vertical = u * 4),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(u * 2),
                        ) {
                            if (name != null) GbaText(name, ink.text, ink.shadow, m, maxLines = 2, bold = gameBoldLabels())
                            if (note != null) GbaText(note, if (name != null) ink.muted else ink.text, ink.shadow, rememberGbaTextMetrics(0.75f), maxLines = 3)
                        }
                    }
                }
            }
        }
    }
}

/** Measures every tile at its own size, then gives all of them the largest one's: every tile the same size. */
@Composable
private fun UniformTiles(
    n: Int,
    modifier: Modifier,
    tile: @Composable (Int) -> Unit,
    extra: @Composable () -> Unit = {},
    place: Placeable.PlacementScope.(tiles: List<Placeable>, w: Int, h: Int, extra: List<Placeable>) -> Unit,
    size: (w: Int, h: Int, c: Constraints) -> Pair<Int, Int>,
    /** How wide [extra] may be, given the tile size. */
    extraWidth: (w: Int, h: Int) -> Int = { _, _ -> Constraints.Infinity },
) {
    SubcomposeLayout(modifier) { c ->
        val loose = Constraints()
        val probe = subcompose("probe") { for (i in 0 until n) tile(i) }.map { it.measure(loose) }
        val w = probe.maxOfOrNull { it.width } ?: 0
        val h = probe.maxOfOrNull { it.height } ?: 0
        val tiles = subcompose("tiles") { for (i in 0 until n) tile(i) }.map { it.measure(Constraints.fixed(w, h)) }
        val (lw, lh) = size(w, h, c)
        val more = subcompose("extra", extra).map { it.measure(Constraints(maxWidth = minOf(lw, extraWidth(w, h)).coerceAtLeast(0), maxHeight = lh)) }
        layout(lw, lh) { place(tiles, w, h, more) }
    }
}

/** GRID: as many tiles a row as the width takes, the rows evened out (9 = 5 + 4, not 8 + 1) and each centred. */
@Composable
private fun TileGrid(n: Int, gap: Dp, tile: @Composable (Int) -> Unit) {
    val g = with(LocalDensity.current) { gap.roundToPx() }
    var cols = 1
    UniformTiles(
        n, Modifier, tile,
        size = { w, h, c ->
            val fit = ((c.maxWidth + g) / (w + g)).coerceIn(1, n.coerceAtLeast(1))
            val rows = ceil(n / fit.toFloat()).toInt().coerceAtLeast(1)
            cols = ceil(n / rows.toFloat()).toInt().coerceAtLeast(1)
            (cols * w + (cols - 1) * g) to (rows * h + (rows - 1) * g)
        },
        place = { tiles, w, h, _ ->
            val full = cols * w + (cols - 1) * g
            tiles.chunked(cols).forEachIndexed { r, row ->
                val rowW = row.size * w + (row.size - 1) * g
                var x = (full - rowW) / 2
                row.forEach { it.place(x, r * (h + g)); x += w + g }
            }
        },
    )
}

/**
 * RING: the tiles round a circle, the first at the top, clockwise; [center] (the hint) in the middle. The tiles are
 * rectangles, so equal angles would leave them closer where corners meet than where sides do: [ringPlaces] puts each the
 * same gap from the last instead. Too tall for the screen, the circle becomes an oval, wider than it is tall.
 */
@Composable
private fun TileRing(state: QuickMenuState, n: Int, gap: Dp, maxW: Dp, maxH: Dp, fan: () -> Float, tile: @Composable (Int) -> Unit, center: @Composable () -> Unit) {
    val density = LocalDensity.current
    val (gapPx, limW, limH) = with(density) { Triple(gap.toPx(), maxW.toPx(), maxH.toPx()) }
    var places = RingPlaces(0f, 0f, FloatArray(0), FloatArray(0))
    UniformTiles(
        n, Modifier, tile, extra = center,
        size = { w, h, _ ->
            places = ringPlaces(n, w.toFloat(), h.toFloat(), gapPx, limW, limH)
            state.ringTurns = places.turns
            (2 * places.rx + w).roundToInt() to (2 * places.ry + h).roundToInt()
        },
        place = { tiles, w, h, extra ->
            val cx = places.rx + w / 2f
            val cy = places.ry + h / 2f
            // Read here, in placement: the tiles move without being composed again.
            val open = fan()
            tiles.forEachIndexed { i, p ->
                val out = tileOut(open, i, n)
                p.placeWithLayer((cx + places.x[i] * out - w / 2f).roundToInt(), (cy + places.y[i] * out - h / 2f).roundToInt()) {
                    alpha = (out * 3f).coerceAtMost(1f)
                    scaleX = TILE_START_SCALE + (1f - TILE_START_SCALE) * out
                    scaleY = scaleX
                }
            }
            extra.forEach { e -> e.placeWithLayer((cx - e.width / 2f).roundToInt(), (cy - e.height / 2f).roundToInt()) { alpha = open } }
        },
        // The hint inside the ring, clear of the tiles at its sides.
        extraWidth = { w, _ -> (2 * places.rx - w - 2 * gapPx).roundToInt() },
    )
}

/** How long the ring takes to open and to close, how small a tile starts, and how much of that time one tile's trip takes. */
private const val RING_OPEN_MS = 300
private const val RING_CLOSE_MS = 220
private const val TILE_START_SCALE = 0.3f
private const val TILE_TRIP = 0.4f

/**
 * How far tile [i] of [n] is on its way from the ring's middle to its place when the ring is [open] (0..1), 0 to 1: each
 * leaves a little after the one before it and slows as it arrives, so closing, the same run backwards, takes the last one in
 * first.
 */
internal fun tileOut(open: Float, i: Int, n: Int): Float {
    val start = if (n > 1) i * (1f - TILE_TRIP) / (n - 1) else 0f
    val t = ((open - start) / TILE_TRIP).coerceIn(0f, 1f)
    return 1f - (1f - t) * (1f - t) * (1f - t)
}

/** Tile centres round the ring (from its middle, px), its radii, and each tile's turn from the top (0..1). */
internal class RingPlaces(val rx: Float, val ry: Float, val x: FloatArray, val y: FloatArray) {
    val turns = FloatArray(x.size) { i -> (atan2(x[i], -y[i]) / (2 * PI)).toFloat().let { if (it < 0) it + 1 else it } }
}

/**
 * [n] tiles of [w] x [h] round an oval from the top, clockwise, each [gap] from the last (the space between their facing
 * edges) and mirrored left to right: the right half is walked down from the top tile one [gap] at a time, the left half
 * is its mirror image, and the radius is found by bisection so the halves meet with the same gap (an even count: the
 * walk ends at the bottom; odd: the two bottom tiles are [gap] apart). A circle when it fits [limH], else as tall as that
 * and wider (up to [limW]); never tighter than room for the hint.
 */
internal fun ringPlaces(n: Int, w: Float, h: Float, gap: Float, limW: Float, limH: Float): RingPlaces {
    if (n == 0) return RingPlaces(0f, 0f, FloatArray(0), FloatArray(0))
    fun px(rx: Float, t: Double) = (rx * sin(t)).toFloat()
    fun py(ry: Float, t: Double) = (-ry * cos(t)).toFloat()
    // The space between two tiles' facing edges (negative = they overlap).
    fun between(rx: Float, ry: Float, a: Double, b: Double) =
        maxOf(abs(px(rx, b) - px(rx, a)) - w, abs(py(ry, b) - py(ry, a)) - h)
    val half = n / 2
    // The right half's angles, one gap [g] after another from the top.
    fun walk(rx: Float, ry: Float, g: Float): DoubleArray {
        val t = DoubleArray(half + 1)
        for (i in 1..half) {
            var lo = t[i - 1]
            var hi = t[i - 1] + PI
            repeat(40) {
                val mid = (lo + hi) / 2
                if (between(rx, ry, t[i - 1], mid) < g) lo = mid else hi = mid
            }
            t[i] = hi
        }
        return t
    }
    // Overrun: the halves run past each other (true = a bigger ring, or a smaller gap).
    fun overrun(rx: Float, ry: Float, g: Float): Boolean {
        val t = walk(rx, ry, g)
        // Odd: past the bottom is an overrun too (the pair's gap, measured across the wrap, would look wide).
        return if (n % 2 == 0) t[half] > PI else t[half] >= PI || between(rx, ry, t[half], -t[half]) < g
    }
    fun bisect(lo0: Float, hi0: Float, grow: (Float) -> Boolean): Float {
        var lo = lo0
        var hi = hi0
        repeat(40) {
            val mid = (lo + hi) / 2
            if (grow(mid)) lo = mid else hi = mid
        }
        return hi
    }
    val minR = maxOf(w, h) * 1.6f
    var g = gap
    var ry = if (n == 1) minR else bisect(maxOf(w, h) / 2f, (w + h + gap) * n) { overrun(it, it, gap) }
    if (ry < minR) {
        // Held at the size the hint needs: the gaps grow (all alike) until the halves meet.
        ry = minR
        if (n > 1) g = bisect(gap, 2 * PI.toFloat() * minR) { !overrun(minR, minR, it) }
    }
    var rx = ry
    if (2 * ry + h > limH) {
        // Too tall: as tall as the screen allows, the oval as wide as it then needs.
        ry = ((limH - h) / 2).coerceAtLeast(h)
        rx = minOf(ry * bisect(1f, 8f) { overrun(ry * it, ry, g) }, ((limW - w) / 2).coerceAtLeast(ry))
    }
    val t = walk(rx, ry, g)
    // The right half as walked (a clamped oval squeezed to end at the bottom), then the left as its mirror.
    val squeeze = if (n % 2 == 0 && t[half] > PI) PI / t[half] else 1.0
    val turn = DoubleArray(n) { i -> if (i <= half) t[i] * squeeze else 2 * PI - t[n - i] * squeeze }
    return RingPlaces(rx, ry, FloatArray(n) { px(rx, turn[it]) }, FloatArray(n) { py(ry, turn[it]) })
}

/** FLY's places, as the MAP tab's FLY list names them: UP / DOWN and A, or a tap. */
@Composable
private fun FlyDestinations(state: QuickMenuState, targets: FlyTargets, m: GbaTextMetrics) {
    val ids = targets.destinations
    LaunchedEffect(targets) { state.startFly(targets, ids) }
    // A DS game's destinations are its fly spots (Gen 4: spawn ids; Gen 5: the towns' places), not map sections.
    val name: (Int) -> String? = if (activeGame.isDsGame) ({ dsFlySpotName(activeGame, it) }) else ({ activeMapSecData[it]?.name })
    PickList(tr("FLY"), ids.map { id -> gameCase(name(id) ?: tr("AREA {0}", id)) }, state, m)
}

/** REPEL's kinds in the bag: each one's name, how many and how many steps it lasts. */
@Composable
private fun RepelKinds(state: QuickMenuState, ids: List<Int>, s: SnapshotView, m: GbaTextMetrics) {
    val rows = ids.map { id -> "${itemName(id)} x${s.items.filter { it.itemId == id }.sumOf { it.quantity }}" }
    val steps = ids.map { id -> tr("{0} steps", REPEL_KINDS.first { it.first == id }.second) }
    PickList(tr("REPEL"), rows, state, m, icons = ids, notes = steps)
}

/** One of the menu's lists: UP / DOWN and A, or a tap. [icons]: an item icon per row. */
@Composable
private fun PickList(
    title: String, rows: List<String>, state: QuickMenuState, m: GbaTextMetrics, icons: List<Int>? = null,
    /** A smaller grey note at each row's end. */
    notes: List<String>? = null,
) {
    val u = m.u
    val list = rememberLazyListState()
    LaunchedEffect(state.pickIndex) { if (rows.isNotEmpty()) list.animateScrollToItem(state.pickIndex.coerceIn(0, rows.lastIndex)) }
    GbaText(title, ink.text, ink.shadow, m)
    LazyColumn(Modifier.width(u * if (notes != null) 190 else 150).heightIn(max = u * 110), state = list) {
        itemsIndexed(rows) { i, text ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (i == state.pickIndex) ink.text.copy(alpha = 0.18f) else Color.Transparent)
                    .gbaFocusRing(i == state.pickIndex, PixelRoundedShape(u * 2))
                    .soundClickable { state.tapPick(i) }
                    .padding(horizontal = u * 6, vertical = u * 3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u * 3),
            ) {
                icons?.getOrNull(i)?.let { ItemIcon(spriteAsset("items", it), u * 16) }
                GbaText(text, ink.text, ink.shadow, m, modifier = Modifier.weight(1f))
                notes?.getOrNull(i)?.let { GbaText(it, ink.muted, ink.shadow, rememberGbaTextMetrics(0.75f)) }
            }
        }
    }
}

/** One action: its icon over its label, ringed while highlighted, dimmed when it can't run now; fills the size it's given
 * ([UniformTiles]: every tile as big as the biggest). */
@Composable
private fun QuickTile(
    action: QuickAction, s: SnapshotView, actions: QuickActions, selected: Boolean, blocked: Boolean, m: GbaTextMetrics,
    /** Its name under the icon (QUICK MENU > LABELS; without, the highlighted one is named on the hint line / in the ring's middle). */
    label: Boolean = true,
    onClick: () -> Unit,
) {
    val u = m.u
    val small = rememberGbaTextMetrics(0.75f)
    val noRipple = remember { MutableInteractionSource() }
    val off = action == QuickAction.WILD && actions.wildEncounters() == 0
    Box(
        Modifier.fillMaxSize().hudButtonFace(u, selected, solid = false, shadow = true)
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // Icons alone: a bigger icon and more room round it, so the tiles stay easy to see and tap without names.
            // With names, taller than they were (5u a side): about square, the user's call with the HP box look.
            Modifier.widthIn(min = if (label) u * 38 else 0.dp).padding(horizontal = u * if (label) 5 else 8, vertical = u * 8).alpha(if (blocked) 0.45f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val icon = u * if (label) 20 else 24
            Box(Modifier.size(icon).alpha(if (off) 0.45f else 1f), contentAlignment = Alignment.Center) { QuickIcon(action, s, actions, icon) }
            if (label) {
                Spacer(Modifier.height(u))
                GbaText(quickLabel(action, actions), ink.text, ink.shadow, small, bold = gameBoldLabels())
            }
        }
        // WILD switched off: an OFF tag on its corner; icons alone have no room for one, so a red slash over the icon.
        if (off && label) {
            Box(Modifier.align(Alignment.TopEnd).padding(u).background(ink.accent, PixelRoundedShape(u * 2)).padding(horizontal = u * 2)) {
                GbaText(tr("OFF"), ink.onAccent, Color.Transparent, rememberGbaTextMetrics(0.6f))
            }
        }
        if (off && !label) PixelArt(SLASH_ICON, SLASH_COLORS, Modifier.size(u * 24))
    }
}

/** A red slash with a dark edge, over an icon that's switched off. */
private val SLASH_ICON = listOf(
    "..........KK",
    ".........KRK",
    "........KRK.",
    ".......KRK..",
    "......KRK...",
    ".....KRK....",
    "....KRK.....",
    "...KRK......",
    "..KRK.......",
    ".KRK........",
    "KRK.........",
    "KK..........",
)

private val SLASH_COLORS = mapOf('K' to Color(0xFF301010), 'R' to Color(0xFFE83838))

/** The game's own art where there is some (the bag's item icons, the Pokémon that would FLY), pixel art otherwise. */
@Composable
private fun QuickIcon(action: QuickAction, s: SnapshotView, actions: QuickActions, size: Dp) {
    @Composable
    fun item(ids: List<Int>) = ItemIcon(spriteAsset("items", KeyItems.firstIn(s.items, ids) ?: ids.first()), size)
    when (action) {
        QuickAction.PC -> PixelArt(PC_ICON, PC_COLORS, Modifier.size(size * 0.8f))
        QuickAction.FLY -> {
            val flyer = s.party.firstOrNull { !it.isEgg && it.moves.any { mv -> mv.id == FLY_MOVE } } ?: s.party.firstOrNull()
            SpeciesIcon(flyer?.iconAsset, size * 1.2f)
        }
        // The wings: up (TAKE OFF) or down (LAND).
        QuickAction.FLIGHT -> PixelArt(if (actions.flying()) LAND_ICON else TAKE_OFF_ICON, WING_COLORS, Modifier.size(size * 0.8f))
        // The strongest one held.
        QuickAction.REPEL -> item(REPEL_KINDS.map { it.first }.reversed())
        QuickAction.BIKE -> item(KeyItems.BIKES)
        QuickAction.ROD -> item(KeyItems.RODS)
        QuickAction.WILD -> PixelArt(GRASS_ICON, GRASS_COLORS, Modifier.size(size * 0.8f))
        QuickAction.FLASH -> PixelArt(FLASH_ICON, FLASH_COLORS, Modifier.size(size * 0.8f))
        QuickAction.SAVE_STATE -> PixelArt(SAVE_STATE_ICON, STATE_COLORS, Modifier.size(size * 0.8f))
        QuickAction.LOAD_STATE -> PixelArt(LOAD_STATE_ICON, STATE_COLORS, Modifier.size(size * 0.8f))
        QuickAction.FF -> PixelIcon(FF_ICON, ink.text, Modifier.size(size * 0.8f))
        QuickAction.COMPANION -> PixelIcon(COMPANION_ICON, ink.text, Modifier.size(size * 0.6f))
    }
}

/**
 * SETTINGS > QUICK MENU's page, the same on both screens: what opens it ([binding], set in HOTKEYS), its STYLE and LABELS,
 * then each action.
 */
fun quickMenuRows(
    shown: Set<QuickAction>, binding: String, style: QuickMenuStyle, labels: Boolean,
    setShown: (Set<QuickAction>) -> Unit, setStyle: (QuickMenuStyle) -> Unit, setLabels: (Boolean) -> Unit, onHotkey: () -> Unit,
): List<SettingRow> {
    fun onOff(b: Boolean) = if (b) tk("ON") else tk("OFF")
    return listOf(
        SettingRow(tk("BUTTON"), binding.ifEmpty { tk("NONE") }, subtitle = "Press it for the menu; change it in HOTKEYS", opensPage = true) { onHotkey() },
        // Two values: a tap flips it.
        SettingRow(tk("STYLE"), style.label) {
            setStyle(if (style == QuickMenuStyle.RING) QuickMenuStyle.GRID else QuickMenuStyle.RING)
        },
        SettingRow(tk("LABELS"), onOff(labels), subtitle = "Each action's name under its icon") { setLabels(!labels) },
        groupTitle(tk("SHOW")),
    ) + QuickAction.entries.map { a ->
        val badge = when (a) {
            QuickAction.FLASH -> NewFeatures.badge(NewFeatures.QUICK_FLASH)
            QuickAction.FLIGHT -> NewFeatures.badge(NewFeatures.FREE_FLIGHT)
            else -> null
        }
        SettingRow(a.label, onOff(a in shown), labelBadge = badge) {
            setShown(if (a in shown) shown - a else shown + a)
        }
    }
}

/** SETTINGS' row for the page. */
fun quickMenuBadge(): String? = NewFeatures.badge(NewFeatures.QUICK_MENU) ?: NewFeatures.badge(NewFeatures.QUICK_FLASH)
    ?: NewFeatures.badge(NewFeatures.FREE_FLIGHT)

/** MOVE_FLASH (the quick menu's FLASH tile, the party check). */
internal const val FLASH_MOVE = 148

/** FLASH: a burst of light, K outline, Y its glow, W the white middle. */
private val FLASH_ICON = listOf(
    ".....KK.....",
    ".K...KK...K.",
    "..K.KYYK.K..",
    "...KYWWYK...",
    "..KYWWWWYK..",
    "KKYWWWWWWYKK",
    "KKYWWWWWWYKK",
    "..KYWWWWYK..",
    "...KYWWYK...",
    "..K.KYYK.K..",
    ".K...KK...K.",
    ".....KK.....",
)

/** FREE FLIGHT: a pair of wings, the arrow over them going up (TAKE OFF) or under them going down (LAND). */
private val TAKE_OFF_ICON = listOf(
    ".....GG.....",
    "....GGGG....",
    "...G.GG.G...",
    ".....GG.....",
    "K....KK....K",
    "KB..KBBK..BK",
    "KBBKBBBBKBBK",
    "KBWBBBBBBWBK",
    ".KBWBBBBWBK.",
    "..KBBKKBBK..",
    "...KK..KK...",
    "............",
)

private val LAND_ICON = listOf(
    "K....KK....K",
    "KB..KBBK..BK",
    "KBBKBBBBKBBK",
    "KBWBBBBBBWBK",
    ".KBWBBBBWBK.",
    "..KBBKKBBK..",
    "...KK..KK...",
    "............",
    ".....GG.....",
    "...G.GG.G...",
    "....GGGG....",
    ".....GG.....",
)

private val WING_COLORS = mapOf('K' to Color(0xFF202850), 'B' to Color(0xFF5068C8), 'W' to Color(0xFFB8C8F8), 'G' to Color(0xFF58C858))

private val FLASH_COLORS = mapOf('K' to Color(0xFF584818), 'Y' to Color(0xFFF8D030), 'W' to Color(0xFFFFFFF0))

/** A disk with an arrow into it: K outline, D the disk, L its label, A the arrow. */
private val SAVE_STATE_ICON = listOf(
    "....AAAA....",
    "....AAAA....",
    "..AAAAAAAA..",
    "...AAAAAA...",
    "....AAAA....",
    ".....AA.....",
    "KKKKKKKKKKKK",
    "KDDLLLLLLDDK",
    "KDDLLLLLLDDK",
    "KDDDDDDDDDDK",
    "KDDDDDDDDDDK",
    "KKKKKKKKKKKK",
)

/** The disk with an arrow out of it. */
private val LOAD_STATE_ICON = listOf(
    ".....AA.....",
    "....AAAA....",
    "...AAAAAA...",
    "..AAAAAAAA..",
    "....AAAA....",
    "....AAAA....",
    "KKKKKKKKKKKK",
    "KDDLLLLLLDDK",
    "KDDLLLLLLDDK",
    "KDDDDDDDDDDK",
    "KDDDDDDDDDDK",
    "KKKKKKKKKKKK",
)

private val STATE_COLORS = mapOf(
    'K' to Color(0xFF202838), 'D' to Color(0xFF4870C0), 'L' to Color(0xFFE8ECF4), 'A' to Color(0xFFF8C030),
)

/** Two arrows pointing right: fast forward. */
private val FF_ICON = listOf(
    "1.....1.....",
    "11....11....",
    "111...111...",
    "1111..1111..",
    "11111.11111.",
    "111111111111",
    "11111.11111.",
    "1111..1111..",
    "111...111...",
    "11....11....",
    "1.....1.....",
)
