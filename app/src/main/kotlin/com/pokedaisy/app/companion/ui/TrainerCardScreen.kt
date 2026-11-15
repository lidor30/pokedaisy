package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pokedaisy.app.companion.data.CardStyle
import com.pokedaisy.app.companion.data.LookArt
import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.TrainerCardArt
import com.pokedaisy.app.companion.data.TrainerCardInfo
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import okio.Path.Companion.toOkioPath

/** The art generation each style's [TrainerCardArt.cached] was loaded at (main thread). */
private val cardArtGeneration = HashMap<CardStyle, Int>()

/** [style]'s card art from the player's ROM ([RomArt]), once a scan has cached it; null until then. */
@Composable
fun rememberTrainerCardArt(style: CardStyle?): TrainerCardArt.Art? {
    val generation = rememberArtGeneration()
    return produceKeyedState(style?.let(TrainerCardArt::cached), style, generation) {
        // Every CompanionScreen composition used to re-read every blob, and the new Art
        // (another identity) re-rendered the card: load only what this generation hasn't.
        val s = style ?: run { value = null; return@produceKeyedState }
        if (value != null && cardArtGeneration[s] == generation) return@produceKeyedState
        value = withContext(Dispatchers.IO) { runCatching { TrainerCardArt.load(RomArt.dir(), s) }.getOrNull() }
        if (value != null) cardArtGeneration[s] = generation
    }.value
}

/**
 * The CARD tab: the game's own TRAINER CARD, drawn from the ROM pixel for
 * pixel ([TrainerCardArt]) at the largest whole scale that fits, with its
 * play-time colon blinking like the game's. A tap flips it over the way the
 * game does - squashed shut top and bottom, opened on the other side.
 */
@Composable
fun TrainerCardScreen(card: TrainerCardInfo, art: TrainerCardArt.Art, modifier: Modifier = Modifier, initialBack: Boolean = false) {
    var back by remember { mutableStateOf(initialBack) }
    // BlinkTimeColon: the colon toggles every 60 frames. An infinite
    // transition (not a delay loop), so screenshot tests can still go idle.
    val blink = rememberInfiniteTransition(label = "card-colon")
    val phase = blink.animateFloat(0f, 2f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "card-colon-phase")
    val colon by remember { derivedStateOf { phase.value < 1f } }
    // Just the card, over the companion's own backdrop: the game's screen
    // behind it would draw a box of other stripes (and cost a scale step).
    // Both colon states, each rendered once per card: the blink used to re-render the
    // whole card (and a new bitmap) every second on the main thread.
    // PLAYER LOOK: the worn hero's front pic, as the game's own card now shows it.
    val look = PlayerLook.worn.value
    val lookPic = produceKeyedState<Pair<ByteArray, ByteArray>?>(null, look) {
        value = look?.let { c -> withContext(Dispatchers.IO) { runCatching { LookArt.frontPic(RomArt.filesDir, c) }.getOrNull() } }
    }.value
    val renders = remember(art, card, back, lookPic) { arrayOfNulls<ImageBitmap>(2) }
    val image = renders[if (colon) 1 else 0] ?: run {
        val img = TrainerCardArt.render(art, card, back, colon, backdrop = false, pic = lookPic)
        val (l, t, r, b) = TrainerCardArt.cardBounds(img).toList()
        val px = IntArray((r - l) * (b - t)) { img.argb[(t + it / (r - l)) * img.width + l + it % (r - l)] }
        imageBitmapOf(px, r - l, b - t).also { renders[if (colon) 1 else 0] = it }
    }
    val squash = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var flipping by remember { mutableStateOf(false) }
    val m = rememberGbaTextMetrics(1f)
    val cardFocus = remember { MutableInteractionSource() }
    val focused by cardFocus.collectIsFocusedAsState()
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.u * 4, Alignment.CenterVertically),
    ) {
        BoxWithConstraints(Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }
            // Whole screen pixels per GBA pixel, so the card stays crisp.
            val scale = max(1, min(w / image.width, h / image.height).toInt())
            val size = with(density) { DpSize((image.width * scale).toDp(), (image.height * scale).toDp()) }
            Canvas(
                Modifier
                    .size(size)
                    .graphicsLayer { scaleY = squash.value }
                    // The companion's window: the card is the tab's one D-pad stop, A flips it. Never disabled while it
                    // flips (a disabled clickable drops the focus): a press mid-flip is ignored instead.
                    .focusStart(true)
                    .gbaFocusRing(focused, RectangleShape)
                    .soundClickable(interactionSource = cardFocus, indication = null) {
                        if (flipping) return@soundClickable
                        flipping = true
                        scope.launch {
                            squash.animateTo(0f, tween(FLIP_MS, easing = LinearEasing))
                            back = !back
                            squash.animateTo(1f, tween(FLIP_MS, easing = LinearEasing))
                            flipping = false
                        }
                    },
            ) {
                drawImage(image, dstSize = IntSize(image.width * scale, image.height * scale), dstOffset = IntOffset.Zero, filterQuality = FilterQuality.None)
            }
        }
        GbaText(
            if (back) tr("TAP TO SEE THE FRONT") else tr("TAP TO FLIP THE CARD"),
            OptionColors.titleText, OptionColors.titleShadow, m,
        )
    }
}

private const val FLIP_MS = 150
