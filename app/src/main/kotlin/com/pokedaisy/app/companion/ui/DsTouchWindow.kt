package com.pokedaisy.app.companion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.pokedaisy.app.companion.i18n.tr

/*
 * DS LAYOUT's floating touch screen (ONE SCREEN and PICTURE IN PICTURE): a DS game's touch screen in a window over the
 * game, which dims behind it like a web page's modal - the companion window's frame and drop shadow, as large as fits
 * at the DS's 4:3. It opens by itself while the game wants its touch screen (the X menu, a menu screen, a battle's bag,
 * party or prompt: EmulatorEngine.dsTouchWanted) and goes when the game is done with it; the TOUCH SCREEN hotkey and the
 * HUD's TOUCH open it by hand, with an X to close. The game keeps running behind it, and taps outside the window fall
 * through to the touch controls.
 */

/**
 * The window over the whole stage. [open]: shown (it scales in, the dim fading in with it; it goes at once - a window
 * shrinking away showed its empty frame a moment after the game's screen had gone); [onClose] non-null = opened by hand,
 * so it has an X. A tap on the dim beside it is [onDismiss]'s. [screen] draws the touch screen itself (the activity's
 * NdsTouchScreenView) into the box it's given. [loading]: the game is between two touch screens (black): the window
 * stays where it is with a loader in it ([DsLoader]), so the next screen comes up in it rather than in a new window.
 */
@Composable
fun DsTouchWindow(
    open: Boolean, onClose: (() -> Unit)?,
    /** The touch controls over the columns [left, right) px: how far down from the top and up from the bottom (px) they
     * reach there. The window fits between the ones across its own width. */
    clearFor: (left: Float, right: Float) -> Pair<Int, Int> = { _, _ -> 0 to 0 },
    onDismiss: () -> Unit = {},
    /** Drawn under the dim in place of the game (a menu screen's own top screen): the overworld it was opened from. */
    behind: androidx.compose.ui.graphics.ImageBitmap? = null,
    loading: Boolean = false,
    /** Its own dim and [behind]: false when something under it already is that (the activity's cover over the black). */
    backdrop: Boolean = true,
    /** A menu transition held (EmulatorEngine.dsHold): the touch screen keeps its last picture, shaded, with the dots,
     * and taps go nowhere; as it ends, [frozen] (that picture) fades out over the new screen. */
    held: Boolean = false,
    frozen: androidx.compose.ui.graphics.ImageBitmap? = null,
    screen: @Composable (Modifier) -> Unit,
) {
    // (Animated before the window may go: a hold that ends with it closed starts the next one from nothing.)
    val freeze by androidx.compose.animation.core.animateFloatAsState(
        if (held) 1f else 0f, if (held) androidx.compose.animation.core.snap() else tween(HELD_OUT_MS), label = "dsFrozen",
    )
    val shade by androidx.compose.animation.core.animateFloatAsState(
        if (held) 1f else 0f, tween(if (held) HELD_SHADE_IN_MS else HELD_OUT_MS), label = "dsShade",
    )
    if (!open) return
    val inspection = LocalInspectionMode.current
    val anim = remember { Animatable(if (inspection) 1f else 0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(OPEN_MS, easing = FastOutSlowInEasing)) }
    val shown = anim.value
    // The window's box (px), for telling a tap on the dim from one on the window.
    val box = remember { FloatArray(4) }
    val dismiss by rememberUpdatedState(onDismiss)
    val holding by rememberUpdatedState(held)
    val m = rememberGbaTextMetrics()
    val u = m.u
    if (behind != null && backdrop) {
        androidx.compose.foundation.Image(
            behind, null, Modifier.fillMaxSize().graphicsLayer { alpha = shown },
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
    // A tap on the dim (the touch controls above take theirs first) is [onDismiss]'s; the window's own go to the game.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (backdrop) DIM * shown else 0f)).pointerInput(Unit) {
            detectTapGestures { p -> if (!holding && (p.x < box[0] || p.y < box[1] || p.x > box[2] || p.y > box[3])) dismiss() }
        },
    ) {
        // The frame's band round the screen, then the largest 4:3 in what's left of the stage's share.
        val frame = u * FRAME
        val density = androidx.compose.ui.platform.LocalDensity.current
        // The window's width decides which buttons it sits under, and they its height: from the whole height, twice.
        var top = 0.dp
        var bottom = 0.dp
        var fit = windowSize(maxWidth * WIDTH_SHARE - frame * 2, maxHeight * HEIGHT_SHARE - frame * 2)
        repeat(2) {
            val half = with(density) { (fit.first / 2 + frame).toPx() }
            val mid = with(density) { (maxWidth / 2).toPx() }
            val (t, b) = clearFor(mid - half, mid + half)
            top = with(density) { t.toDp() }
            bottom = with(density) { b.toDp() }
            fit = windowSize(maxWidth * WIDTH_SHARE - frame * 2, (maxHeight - top - bottom) * HEIGHT_SHARE - frame * 2)
        }
        val (w, h) = fit
        val band = maxHeight - top - bottom
        with(density) {
            box[0] = ((maxWidth - w) / 2 - frame).toPx()
            box[1] = (top + (band - h - frame * 2) / 2).toPx()
            box[2] = box[0] + (w + frame * 2).toPx()
            box[3] = box[1] + (h + frame * 2).toPx()
        }
        // Centred in the band between the touch controls' rows (the whole height without them).
        val windowTop = top + (band - h - frame * 2) / 2
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = windowTop).size(w + frame * 2, h + frame * 2)
                .graphicsLayer {
                    val k = START_SCALE + (1f - START_SCALE) * shown
                    scaleX = k
                    scaleY = k
                    alpha = shown
                }
                .drawBehind {
                    val px = u.toPx()
                    drawPixelRoundRect(Color.Black.copy(alpha = 0.35f), Offset(0f, 3 * px), size, radius = 4 * px, step = px)
                }
                .clip(PixelRoundedShape(u * 4))
                .background(OptionColors.titleFill)
                .drawWithContent {
                    drawContent()
                    drawLayeredFrame(OptionColors.titleLayers.inPx(u.toPx()), radius = 4 * u.toPx())
                },
        ) {
            screen(Modifier.padding(frame).size(w, h))
            // (Over the screen, which stays: taps still reach the game, and nothing is made again when it's back.)
            if (loading) Box(Modifier.padding(frame).size(w, h).background(LOADING_FILL)) { DsLoader(ball = false) }
            if (frozen != null && freeze > 0f) {
                androidx.compose.foundation.Image(
                    frozen, null, Modifier.padding(frame).size(w, h).graphicsLayer { alpha = freeze },
                    contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                )
            }
            if (shade > 0f) Box(Modifier.padding(frame).size(w, h).background(Color.Black.copy(alpha = HELD_SHADE * shade))) {
                if (held) DsLoader(ball = false)
            }
        }
        if (onClose != null) {
            // Beside the window's top right corner (on it where there's no room), as the companion window's.
            val x = (maxWidth + w) / 2 + frame
            val room = maxWidth - x >= u * 30
            Box(
                Modifier.align(Alignment.TopStart)
                    .offset(x = if (room) x + u * 4 else x - u * 26, y = windowTop + if (room) u * 0 else u * 2)
                    .graphicsLayer { alpha = shown },
            ) { WindowCloseButton(onClose, description = tr("Close the touch screen")) }
        }
    }
}

/**
 * The same shade and dots as [DsTouchWindow]'s while a menu transition is held with no window up (a page side by side on
 * its way back to the field): over the whole stage, whose pictures are held meanwhile.
 */
@Composable
fun DsHeldShade(held: Boolean) {
    val shade by androidx.compose.animation.core.animateFloatAsState(
        if (held) 1f else 0f, tween(if (held) HELD_SHADE_IN_MS else HELD_OUT_MS), label = "dsHeldShade",
    )
    if (shade <= 0f) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = HELD_SHADE * shade))) {
        if (held) DsLoader(ball = false)
    }
}

/** A held transition's shade over the held picture, how fast it comes (a tap's answer, at once), and how fast it and the
 * held picture go once the next screen is up (a crossfade into it). */
private const val HELD_SHADE = 0.3f
private const val HELD_SHADE_IN_MS = 90
private const val HELD_OUT_MS = 140

/** The largest 4:3 that fits [maxW] x [maxH]. */
private fun windowSize(maxW: Dp, maxH: Dp): Pair<Dp, Dp> {
    val w = min(maxW, maxH * 4f / 3f)
    return w to w * 3f / 4f
}

/** How far the game dims behind the window. */
private const val DIM = 0.55f

/** The window's share of the stage: tall, with room for the dim round it. */
private const val WIDTH_SHARE = 0.92f
private const val HEIGHT_SHARE = 0.88f

/** The frame round the screen, in GBA pixels. */
private const val FRAME = 3

/** It grows from this scale as it opens. */
private const val START_SCALE = 0.92f
private const val OPEN_MS = 130

/** The window's screen while [DsTouchWindow]'s loading: the black the game shows, a shade lifted. */
private val LOADING_FILL = Color(0xFF101418)

/**
 * Behind DS LAYOUT's screens where they leave room: [shot], a picture of the game (the overworld last seen, or the game a
 * moment ago with [blurred]), covering the box and dimmed like a modal's page; nothing yet = a plain dark. [modal] (px, in
 * this box): the screens a menu page shows side by side - a frame and a shadow round them, as the floating window has.
 */
@Composable
fun DsShotBackdrop(shot: androidx.compose.ui.graphics.ImageBitmap?, blurred: Boolean, modal: androidx.compose.ui.geometry.Rect?) {
    val u = rememberGbaTextMetrics().u
    Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        if (shot != null) {
            androidx.compose.foundation.Image(
                shot, null,
                Modifier.fillMaxSize().then(
                    if (blurred && android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(u * 6, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle)
                    else Modifier,
                ),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                filterQuality = if (blurred) androidx.compose.ui.graphics.FilterQuality.Low else androidx.compose.ui.graphics.FilterQuality.None,
            )
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = DIM))
            if (modal == null) return@Canvas
            val px = u.toPx()
            val f = FRAME * px
            val topLeft = Offset(modal.left - f, modal.top - f)
            val size = androidx.compose.ui.geometry.Size(modal.width + 2 * f, modal.height + 2 * f)
            drawPixelRoundRect(Color.Black.copy(alpha = 0.35f), topLeft + Offset(0f, 3 * px), size, radius = 4 * px, step = px)
            drawPixelRoundRect(OptionColors.titleFill, topLeft, size, radius = 4 * px, step = px)
            translate(topLeft.x, topLeft.y) {
                drawLayeredFrameSized(OptionColors.titleLayers.inPx(px), 4 * px, size)
            }
        }
    }
}

/** [drawLayeredFrame] over a box of [size] at this scope's origin. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLayeredFrameSized(
    layers: List<Pair<Color, Float>>, radius: Float, size: androidx.compose.ui.geometry.Size,
) {
    var inset = 0f
    for ((color, width) in layers) {
        drawPixelRoundFrame(color, Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset), (radius - inset).coerceAtLeast(0f), width)
        inset += width
    }
}

/**
 * Over the greyed-out game while it loads between two screens (the DS window's cover): the app's Poké Ball rocking like a
 * capture shake ([RockingPokeBall]) over its shadow, three dots under it bouncing in turn ([ball] false: the dots alone, as
 * in the floating window's). It fades in only once the wait has lasted [LOADER_DELAY_MS] (a quick fade never flashes it).
 */
@Composable
fun DsLoader(modifier: Modifier = Modifier, ball: Boolean = true) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(LOADER_DELAY_MS)
        shown.animateTo(1f, tween(LOADER_FADE_MS))
    }
    val t by androidx.compose.animation.core.rememberInfiniteTransition(label = "dsLoader").animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(tween(LOADER_CYCLE_MS, easing = androidx.compose.animation.core.LinearEasing)),
        label = "dsLoaderT",
    )
    // Three GBA pixels a ball pixel: big enough to read on a handheld's screen.
    val u = rememberGbaTextMetrics().u * LOADER_SCALE
    Box(modifier.fillMaxSize().graphicsLayer { alpha = shown.value }, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (ball) Box(Modifier.size(u * 12, u * 13)) {
                // Its shadow, then the ball rocking on it.
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val px = kotlin.math.floor(u.toPx()).coerceAtLeast(1f)
                    drawPixelRoundRect(
                        Color.Black.copy(alpha = 0.35f), Offset(2 * px, size.height - 2 * px), androidx.compose.ui.geometry.Size(size.width - 4 * px, 2 * px),
                        radius = px, step = px,
                    )
                }
                RockingPokeBall(Modifier.size(u * 12))
            }
            androidx.compose.foundation.Canvas(Modifier.padding(top = if (ball) u * 3 else 0.dp).size(u * 15, u * 6)) {
                val px = kotlin.math.floor(u.toPx() / 1.5f).coerceAtLeast(1f)
                // Three dots, each up a pixel or two in its turn.
                for (i in 0..2) {
                    val phase = ((t * 3 - i) % 3 + 3) % 3
                    val lift = if (phase < 1f) kotlin.math.floor(kotlin.math.sin(phase * Math.PI).toFloat() * 3f) * px else 0f
                    val x = kotlin.math.floor(size.width / 2f + (i - 1) * 6 * px - 1.5f * px)
                    val y = size.height - 4 * px - lift
                    drawRect(Color.Black.copy(alpha = 0.4f), Offset(x + px, y + px), androidx.compose.ui.geometry.Size(3 * px, 3 * px))
                    drawRect(Color.White, Offset(x, y), androidx.compose.ui.geometry.Size(3 * px, 3 * px))
                }
            }
        }
    }
}

/** [DsLoader]: shown once a wait has lasted this, fading in over [LOADER_FADE_MS]; one hop every [LOADER_CYCLE_MS]. */
private const val LOADER_DELAY_MS = 160L
private const val LOADER_FADE_MS = 180
private const val LOADER_CYCLE_MS = 1050
/** A ball pixel in GBA pixels. */
private const val LOADER_SCALE = 3
