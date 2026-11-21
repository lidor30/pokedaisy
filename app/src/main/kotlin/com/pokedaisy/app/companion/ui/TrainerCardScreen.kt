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
import com.pokedaisy.app.companion.data.CardArt
import com.pokedaisy.app.companion.data.CardStyle
import com.pokedaisy.app.companion.data.Gen2TrainerCard
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
import kotlinx.coroutines.IO

/** The art generation each style's [TrainerCardArt.cached] was loaded at (main thread). */
private val cardArtGeneration = HashMap<CardStyle, Int>()

/** [style]'s card art from the player's ROM ([RomArt]), once a scan has cached it; null until then. */
@Composable
fun rememberTrainerCardArt(style: CardStyle?): CardArt? {
    val generation = rememberArtGeneration()
    val start: CardArt? = when (style) {
        null -> null
        CardStyle.CRYSTAL -> Gen2TrainerCard.cached()
        else -> TrainerCardArt.cached(style)
    }
    return produceKeyedState(start, style, generation) {
        // Every CompanionScreen composition used to re-read every blob, and the new Art
        // (another identity) re-rendered the card: load only what this generation hasn't.
        val s = style ?: run { value = null; return@produceKeyedState }
        if (value != null && cardArtGeneration[s] == generation) return@produceKeyedState
        value = withContext(Dispatchers.IO) {
            runCatching { if (s == CardStyle.CRYSTAL) Gen2TrainerCard.load(RomArt.dir()) else TrainerCardArt.load(RomArt.dir(), s) }.getOrNull()
        }
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
fun TrainerCardScreen(card: TrainerCardInfo, art: CardArt, modifier: Modifier = Modifier, initialBack: Boolean = false) {
    var back by remember { mutableStateOf(initialBack) }
    if (art is Gen2TrainerCard.Art) {
        CrystalCard(card, art, back, modifier) { back = !back }
        return
    }
    art as TrainerCardArt.Art
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
    FlippingCard(image, back, modifier) { back = !back }
}

/**
 * Crystal's card ([Gen2TrainerCard]): the whole 160x144 screen the game draws, its two pages flipped like the GBA
 * cards' sides. The game's 64-frame cycle in eighths: the badges spin a step each, the play time's colon blinks.
 */
@Composable
private fun CrystalCard(card: TrainerCardInfo, art: Gen2TrainerCard.Art, badges: Boolean, modifier: Modifier, flip: () -> Unit) {
    val cycle = rememberInfiniteTransition(label = "crystal-card")
    val t = cycle.animateFloat(0f, 8f, infiniteRepeatable(tween(CRYSTAL_CYCLE_MS, easing = LinearEasing)), label = "crystal-card-phase")
    // Page 1 changes only with the colon; page 2 only with the badges (none: one picture).
    val phase by remember(badges, card.badges) {
        derivedStateOf {
            val p = t.value.toInt().coerceIn(0, 7)
            when {
                !badges -> if (p >= 4) 4 else 0
                card.badges == 0 -> 0
                else -> p
            }
        }
    }
    val renders = remember(art, card, badges) { arrayOfNulls<ImageBitmap>(8) }
    val image = renders[phase] ?: run {
        val img = Gen2TrainerCard.render(art, card, badges, phase)
        imageBitmapOf(img.argb, img.width, img.height).also { renders[phase] = it }
    }
    FlippingCard(image, badges, modifier, flip)
}

/** [image] at the largest whole scale that fits; a tap squashes it shut, [flip]s and opens it again. */
@Composable
private fun FlippingCard(image: ImageBitmap, back: Boolean, modifier: Modifier, flip: () -> Unit) {
    val squash = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var flipping by remember { mutableStateOf(false) }
    val m = rememberGbaTextMetrics(1f)
    val cardFocus = remember { MutableInteractionSource() }
    val focused by cardFocus.collectIsFocusedAsState()
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        // Whole screen pixels per game pixel, so the card stays crisp. The hint under it goes when leaving it out gives
        // the card a whole step more (Crystal's 160x144 on the Thor's bottom screen: 6x instead of 5x).
        val hint = with(density) { (m.lineHeight + m.u * 4).toPx() }
        fun fit(height: Float) = max(1, min(w / image.width, height / image.height).toInt())
        val showHint = fit(h - hint) >= fit(h)
        val scale = if (showHint) fit(h - hint) else fit(h)
        val size = with(density) { DpSize((image.width * scale).toDp(), (image.height * scale).toDp()) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.u * 4, Alignment.CenterVertically),
        ) {
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
                            flip()
                            squash.animateTo(1f, tween(FLIP_MS, easing = LinearEasing))
                            flipping = false
                        }
                    },
            ) {
                drawImage(image, dstSize = IntSize(image.width * scale, image.height * scale), dstOffset = IntOffset.Zero, filterQuality = FilterQuality.None)
            }
            if (showHint) GbaText(
                if (back) tr("TAP TO SEE THE FRONT") else tr("TAP TO FLIP THE CARD"),
                OptionColors.titleText, OptionColors.titleShadow, m,
            )
        }
    }
}

private const val FLIP_MS = 150
private const val CRYSTAL_CYCLE_MS = 64 * 1000 / 60
