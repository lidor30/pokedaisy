package com.pokedaisy.app.companion.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.LookArt
import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.MonArt
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import okio.Path.Companion.toOkioPath

/** Rows of tiles the list shows at once: two and a half, so a cut row says it scrolls. */
private const val ROWS_IN_VIEW = 2.5f

/** One step of the walk on the picked tile: the game's 8 frames a step. */
private const val STEP_MS = 133

/**
 * PLAYER LOOK's pick: a tile per hero this game can wear ([looks], [LookChar] names), each showing them on the overworld
 * (their own sprite and colours, from [LookArt]) over their name and game, after an OFF tile with the player's own hero
 * ([own]). The pick ([current], null = OFF) is the white tile and walks in place; a tap picks ([onPick], null = OFF).
 */
@Composable
internal fun PlayerLookPicker(
    title: String,
    own: String?,
    looks: List<String>,
    current: String?,
    m: GbaTextMetrics,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** A pick's name on its tile (a hero's, or a Pokémon's in the game: "MON:<national number>"). */
    label: (String) -> String = { LookChar.byName(it)?.label ?: it },
) {
    // The tiles' two lines in the denser text (the user's call: smaller tiles, more of them on screen).
    val small = rememberGbaTextMetrics(0.75f)
    val context = LocalContext.current
    val tiles = listOf(own) + looks
    // Each hero's three frames, read once the picker opens (a few KB a hero).
    // A Pokémon's tile says which hack its sprite came from (MonArt.Source), as a hero's says its game.
    val monSources = produceState<Map<Int, String>>(emptyMap(), tiles) {
        value = withContext(Dispatchers.IO) {
            tiles.mapNotNull { MonArt.monOf(it) }.mapNotNull { n -> runCatching { MonArt.load(context.filesDir.toOkioPath(), n) }.getOrNull()?.let { n to it.source.tag } }.toMap()
        }
    }.value
    val sprites = produceState<Map<String, List<ImageBitmap>>>(emptyMap(), tiles) {
        value = withContext(Dispatchers.IO) {
            tiles.filterNotNull().mapNotNull { name ->
                val mon = MonArt.monOf(name)
                val frames = if (mon != null) runCatching { MonArt.walk(context.filesDir.toOkioPath(), mon) }.getOrNull() ?: return@mapNotNull null
                else {
                    val c = LookChar.byName(name) ?: return@mapNotNull null
                    runCatching { LookArt.walk(context.filesDir.toOkioPath(), c) }.getOrNull() ?: return@mapNotNull null
                }
                name to frames.map { Bitmap.createBitmap(it.argb, it.width, it.height, Bitmap.Config.ARGB_8888).asImageBitmap() }
            }.toMap()
        }
    }.value
    // The step the white tile shows (0 standing, 1 / 2 the steps): stand, step, stand, other step.
    val walking = CompanionTweaks[CompanionTweaks.Tweak.PARTY_ICONS_MOVE]
    val phase = if (walking) {
        rememberInfiniteTransition(label = "look-walk").animateFloat(
            0f, 4f, infiniteRepeatable(tween(STEP_MS * 4, easing = LinearEasing), RepeatMode.Restart), label = "look-step",
        ).value
    } else 0f
    val frame = intArrayOf(1, 0, 2, 0)[phase.toInt().coerceIn(0, 3)]
    // One scale for every tile, from the largest hero (crops differ: Brendan's hat point is taller).
    val box = sprites.values.flatten().let { all -> IntSize(all.maxOfOrNull { it.width } ?: 16, all.maxOfOrNull { it.height } ?: 32) }
    // Five across: small tiles (the user's call), fewer only when there are fewer heroes.
    val columns = minOf(tiles.size, 5)
    // The D-pad's tile (index): while it's on another one, the pick isn't white, so one tile is lit.
    var focusedTile by remember { mutableStateOf(-1) }
    val picked = tiles.indexOf(current).coerceAtLeast(0)

    // As tall as the screen allows; the tiles scroll, about two and a half rows in view (a half row says there's more).
    OptionOverlay(onDismiss, Modifier.widthIn(max = (150 * columns).dp).fillMaxHeight()) {
        Column {
            OptionTitleWindow(title, m, onBack = onDismiss)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val u = m.u
                    val gap = u * 6
                    val tileHeight = if (maxHeight == Dp.Infinity) u * 60 else ((maxHeight - u * 4 - gap * 2) / ROWS_IN_VIEW)
                        .coerceAtLeast(small.lineHeight * 2 + u * 24)
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(u * 2),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        tiles.withIndex().chunked(columns).forEach { line ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                line.forEach { (i, name) ->
                                    val c = name?.let { LookChar.byName(it) }
                                    val lit = if (focusedTile >= 0) focusedTile == i else picked == i
                                    LookTile(
                                        name = if (i == 0) tk("OFF") else name?.let(label).orEmpty(),
                                        game = if (i == 0) c?.label.orEmpty() else c?.game ?: MonArt.monOf(name)?.let { monSources[it] }.orEmpty(),
                                        sprite = name?.let { sprites[it] }?.let { it[if (lit) frame else 0] }, box = box,
                                        lit = lit, current = picked == i, m = m, small = small,
                                        onFocus = { focusedTile = if (it) i else if (focusedTile == i) -1 else focusedTile },
                                        modifier = Modifier.weight(1f).height(tileHeight),
                                    ) { onPick(if (i == 0) null else name) }
                                }
                                repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One hero: their sprite at the largest whole scale the tile has room for, over their name and game. */
@Composable
private fun LookTile(
    name: String,
    game: String,
    sprite: ImageBitmap?,
    box: IntSize,
    lit: Boolean,
    current: Boolean,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val u = m.u
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val self = remember { FocusRequester() }
    LocalOverlayStart.current?.register(self, current = current)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .gbaFocusRing(focused, PixelRoundedShape(u * 3))
            .drawBehind {
                val px = u.toPx()
                drawLayeredBox(OptionColors.chipLayers.inPx(px), if (lit) OptionColors.tabSelectedFill else OptionColors.tabIdleFill, radius = 3 * px)
            }
            .focusRequester(self)
            .onFocusChanged { onFocus(it.isFocused) }
            .soundClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "$name $game" }
            .padding(u * 2),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = u, bottom = u), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val img = sprite ?: return@Canvas
                // Whole screen pixels per sprite pixel (crisp), one step under what the room allows (the user's call: a
                // bit smaller), at most four GBA pixels' worth.
                val k = (floor(minOf(size.width / box.width, size.height / box.height, 4 * u.toPx())) - 1f).coerceAtLeast(1f).toInt()
                val w = img.width * k
                val h = img.height * k
                drawImage(
                    img, dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                    dstSize = IntSize(w, h), filterQuality = FilterQuality.None,
                )
            }
        }
        GbaText(name, if (current) OptionColors.value else OptionColors.label, if (current) OptionColors.valueShadow else OptionColors.labelShadow, small, maxLines = 1)
        // A long game name narrows its letters to fit rather than being cut.
        // Its line keeps one height, so every tile's sprite gets the same room (and scale).
        Box(Modifier.height(small.lineHeight), contentAlignment = Alignment.Center) {
            FittedGbaText(game, OptionColors.label, OptionColors.labelShadow, small)
        }
        Spacer(Modifier.height(u * 3))
    }
}
