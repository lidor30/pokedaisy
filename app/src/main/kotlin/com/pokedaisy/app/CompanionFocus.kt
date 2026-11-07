package com.pokedaisy.app

// Android views (LocalView, KeyEvent): app-side, outside the companion/** sources ui-preview compiles.

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.CompanionKeys
import com.pokedaisy.app.companion.ui.FocusStarts
import com.pokedaisy.app.companion.ui.LocalFocusStarts
import com.pokedaisy.app.companion.ui.LocalOverlayStack
import com.pokedaisy.app.companion.ui.LocalShoulderHints
import com.pokedaisy.app.companion.ui.MenuKey
import com.pokedaisy.app.companion.ui.OverlayStack
import com.pokedaisy.app.companion.ui.soundClickable
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A D-pad direction or A that nothing in the companion took, handed to Compose's focus in [view]: the key event a
 * D-pad sends, after focusing the view out of touch mode so its rows and buttons can take the focus ([start] then puts
 * it on the cursor when nothing has it). Compose moves the focus (scrolling it into view) and presses what has it. The
 * view needn't be in a focused window: the companion on a two-screen device's other display never is.
 */
fun sendFocusKey(view: View, key: MenuKey, repeat: Boolean, start: () -> Unit): Boolean {
    val code = when (key) {
        MenuKey.UP -> KeyEvent.KEYCODE_DPAD_UP
        MenuKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        MenuKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        MenuKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        MenuKey.A -> KeyEvent.KEYCODE_DPAD_CENTER
        else -> return false
    }
    if (key == MenuKey.A && repeat) return true
    if (view.isInTouchMode) view.requestFocusFromTouch() else if (!view.hasFocus()) view.requestFocus()
    start()
    val now = SystemClock.uptimeMillis()
    view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, if (repeat) 1 else 0))
    view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    return true
}

/**
 * The companion on a two-screen device's other display, which the game's buttons can steer while [focused] (X on the
 * field, PokeDaisyActivity's bottom focus): [keys] get them (L / R step the tabs), and the D-pad and A that nothing
 * took move Compose's focus as in the one-screen window ([SidePanelHost]) - pop-ups keep it, the cursor row is where
 * it starts - and L / R hints slide in at the tab bar's ends ([LocalShoulderHints]). Not focused, it's the touch
 * companion it always was, and nothing in it stays lit.
 */
@Composable
fun CompanionFocusHost(focused: Boolean, keys: CompanionKeys, content: @Composable () -> Unit) {
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val starts = remember { FocusStarts() }
    val overlays = remember { OverlayStack() }
    var hasFocus by remember { mutableStateOf(false) }
    // Registered before the companion's own handlers, so it gets only what they leave.
    keys.Handler(enabled = focused) { key, repeat ->
        sendFocusKey(view, key, repeat) { if (!hasFocus) starts.focusNewest() }
    }
    LaunchedEffect(focused) { if (!focused) focusManager.clearFocus(force = true) }
    CompositionLocalProvider(
        LocalOverlayStack provides overlays.takeIf { focused },
        LocalFocusStarts provides starts.takeIf { focused },
        LocalShoulderHints provides focused,
    ) {
        Box(Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }) { content() }
    }
}

/** The stripe's height in the game's own pixels. */
private const val STRIPE_ROWS = 22

/** The arrow's rows, widest first (a pixel triangle pointing down). */
private val ARROW_ROWS = intArrayOf(17, 15, 13, 11, 9, 7, 5, 3, 1)

/** Where the arrow's top row sits in the stripe at rest, and how far it bobs down (game pixels). */
private const val ARROW_TOP = 5
private const val ARROW_BOB = 3

private val STRIPE_EDGE = Color(0xFF6C7080)
private val STRIPE_SHINE = Color(0xFFFAFAFE)
private val STRIPE_TOP = Color(0xFFE4E6EC)
private val STRIPE_BOTTOM = Color(0xFFA6AAB6)
private val STRIPE_FOOT = Color(0xFF878B98)
private val ARROW = Color(0xFF454957)
private val ARROW_SHADOW = Color(0xE6FFFFFF)

/**
 * While the game's buttons are on the companion's screen (X on the field of a two-screen device): a silver strip along
 * the bottom of the game's picture with an arrow bobbing down at it, like a DS game pointing at its touch screen. It
 * slides up out of the picture's bottom edge as the buttons go down there and back as they return; a tap on it brings
 * them back ([onClose]). Sized in the game's own pixels ([gameRows] tall: [frame] is the game's picture in this view,
 * null = all of it, as with EXPAND).
 */
@Composable
fun CompanionFocusStripe(shown: Boolean, frame: Rect?, gameRows: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val left = frame?.left ?: 0f
        val top = frame?.top ?: 0f
        val w = frame?.width ?: constraints.maxWidth.toFloat()
        val h = frame?.height ?: constraints.maxHeight.toFloat()
        if (w <= 0f || h <= 0f) return@BoxWithConstraints
        val px = h / gameRows
        val stripeH = STRIPE_ROWS * px
        val density = LocalDensity.current
        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), (top + h - stripeH).roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { stripeH.toDp() })
                // Slides out of the picture's edge, not over the touch controls / letterbox under it.
                .clipToBounds(),
        ) {
            AnimatedVisibility(
                shown,
                enter = slideInVertically(tween(STRIPE_MS)) { it } + fadeIn(tween(STRIPE_MS)),
                exit = slideOutVertically(tween(STRIPE_MS)) { it } + fadeOut(tween(STRIPE_MS)),
                label = "companionFocusStripe",
            ) { Stripe(px, onClose) }
        }
    }
}

private const val STRIPE_MS = 180

/** One bob: down a pixel at a time to [ARROW_BOB], a pause, back up, then a rest. */
private const val BOB_MS = 960

@Composable
private fun Stripe(px: Float, onClose: () -> Unit) {
    val bob by rememberInfiniteTransition(label = "stripeArrow").animateFloat(
        0f, 0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = BOB_MS
                0f at 0
                1f at 90
                2f at 180
                ARROW_BOB.toFloat() at 270
                ARROW_BOB.toFloat() at 450
                2f at 540
                1f at 630
                0f at 720
            },
            initialStartOffset = StartOffset(0),
        ),
        label = "bob",
    )
    val noRipple = remember { MutableInteractionSource() }
    val description = tr("Back to the game")
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
            // Touch only: X (or B / START) brings the buttons back from the pad.
            .focusProperties { canFocus = false }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClose),
    ) {
        drawStripe(px, floor(bob).toInt())
    }
}

/** The strip in whole game pixels: a dark edge, a shine, a silver fall-off, a foot line; the arrow over its shadow. */
private fun DrawScope.drawStripe(px: Float, bob: Int) {
    fun row(r: Int, color: Color, x: Float = 0f, width: Float = size.width) =
        drawRect(color, Offset(x, r * px), Size(width, px))
    row(0, STRIPE_EDGE)
    row(1, STRIPE_SHINE)
    val body = STRIPE_ROWS - 3
    for (i in 0 until body) {
        row(2 + i, androidx.compose.ui.graphics.lerp(STRIPE_TOP, STRIPE_BOTTOM, i / (body - 1f)))
    }
    row(STRIPE_ROWS - 1, STRIPE_FOOT)
    val center = size.width / 2
    for ((shadow, color) in listOf(1 to ARROW_SHADOW, 0 to ARROW)) {
        ARROW_ROWS.forEachIndexed { i, cells ->
            val x = center - cells * px / 2
            row(ARROW_TOP + bob + i + shadow, color, x, cells * px)
        }
    }
}
