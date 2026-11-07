package com.pokedaisy.app

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.ui.TouchControls
import com.pokedaisy.app.companion.ui.TouchGeometry
import com.pokedaisy.app.companion.ui.TouchInput

/**
 * The on-screen controls over the game (SETTINGS > TOUCH CONTROLS): companion.ui.TouchControls in a view the game's
 * stage can lay out - over the whole screen, in an upright phone's gap ([PortraitPanel]), beside an open side panel
 * ([SidePanel]). Shown per [TouchControlsStyle.show] (see PokeDaisyActivity.syncTouchControls).
 */
class TouchControlsView(context: Context) : FrameLayout(context) {

    /** The GBA buttons held and TURBO A / B held, on the UI thread, whenever either changes. */
    var onMask: ((gba: Int, turbo: Int) -> Unit)? = null

    /** FF, QUICK MENU, SAVE / LOAD STATE pressed (true) and let go (false). */
    var onAction: ((TouchButton, Boolean) -> Unit)? = null

    /** Called after the buttons were placed (a new size or layout): [clearTop] / [clearBottom] may have changed. */
    var onPlaced: (() -> Unit)? = null

    var style by mutableStateOf(TouchControlsStyle())

    /** A phone held upright: its own layout ([TouchControlsStyle.portrait]). */
    var portrait by mutableStateOf(false)

    /** The game's shape (3:2, or a Game Boy's 10:9): SIDES measures the margins beside it. */
    var gameAspect by mutableFloatStateOf(1.5f)

    /** FF is on: the FF button's word lit. */
    var ffOn by mutableStateOf(false)

    /** The L / R buttons: off for a Game Boy / Color game, which has none. */
    var shoulders by mutableStateOf(true)
    /** A DS game: X and Y on screen too. */
    var dsButtons by mutableStateOf(false)

    /** SKIN's pick for this game's system (null: the drawn buttons). */
    var skin by mutableStateOf<com.pokedaisy.app.companion.InstalledSkin?>(null)

    /** Laid over a DS game's touch screen (one screen held sideways: DS LAYOUT's, PokeDaisyActivity.syncDsStage): a touch that starts on no
     * button goes on to whatever is under this view. Android splits fingers between views, so another finger can still
     * land on a button meanwhile. */
    var passMisses = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (passMisses && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val p = placed
            if (p == null || TouchGeometry.hits(p, ev.x, ev.y, 10 * p.dp).isEmpty()) return false
        }
        return super.dispatchTouchEvent(ev)
    }

    private var placed: TouchGeometry.Placed? = null

    /** The FIELD HUD's room to keep clear while this shows (px): down to the top row's bottom (L / R, extras), and up
     * from the bottom to the top of the controls along it - only those over the game, not in the margins beside it. Both
     * 0 while hidden. */
    val clearTop: Int get() = clear().first
    val clearBottom: Int get() = clear().second

    /** VIBRATION: LIGHT is the phone's own tick, which follows its touch vibration setting (too faint on some phones);
     *  MEDIUM and STRONG drive the motor themselves. */
    private fun buzz(level: Int) {
        when {
            level <= 0 -> {}
            level == 1 -> performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            else -> context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(if (level == 2) 20L else 40L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun clear(): Pair<Int, Int> {
        val p = placed ?: return 0 to 0
        // The game's columns: the middle 3:2 (or 10:9) at the area's height, the whole width upright.
        val gameW = if (portrait) p.w else minOf(p.w, p.h * gameAspect)
        val l = (p.w - gameW) / 2
        return clearAcross(l, l + gameW)
    }

    /** As [clearTop] / [clearBottom], for the controls across the columns [l, r) (px): a DS game's floating touch
     * screen keeps clear of only the ones it would sit under. */
    fun clearAcross(l: Float, r: Float): Pair<Int, Int> {
        val p = placed ?: return 0 to 0
        if (visibility != VISIBLE) return 0 to 0
        val over = p.items.filter { it.right > l && it.left < r }
        val top = over.filter { it.cy < p.h / 2 }.maxOfOrNull { it.bottom } ?: 0f
        val bottom = over.filter { it.cy >= p.h / 2 }.minOfOrNull { it.top }?.let { p.h - it } ?: 0f
        return top.toInt() to bottom.toInt().coerceAtLeast(0)
    }

    init {
        addView(ComposeView(context).apply {
            setContent {
                TouchControls(
                    style, portrait,
                    input = TouchInput(
                        onKeys = { gba, turbo -> onMask?.invoke(gba, turbo) },
                        onAction = { b, down -> onAction?.invoke(b, down) },
                        onPress = { buzz(style.vibration) },
                    ),
                    gameAspect = gameAspect, gameBoy = !shoulders, ffOn = ffOn, ds = dsButtons, skin = skin,
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    onPlaced = { placed = it; onPlaced?.invoke() },
                )
            }
        }, LayoutParams(-1, -1))
    }
}
