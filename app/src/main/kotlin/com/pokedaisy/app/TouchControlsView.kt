package com.pokedaisy.app

import android.content.Context
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

    /** The GBA buttons held and TURBO A / B held, whenever either changes: on the UI thread from the drawn pad, on the
     * input windows' thread from theirs ([TouchInputWindows]). */
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

    /** Extras switched on that do nothing in this game now (ZOOM without EXPAND, REWIND off or a DS game): not drawn. */
    var unavailable by mutableStateOf<Set<TouchButton>>(emptySet())

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
     * from the bottom to the top of the controls along it - only those over the game, not in the margins beside it, and
     * only the rows along the edges ([TouchGeometry.hudClear]). Both 0 while hidden. */
    val clearTop: Int get() = clear().first
    val clearBottom: Int get() = clear().second

    private fun clear(): Pair<Int, Int> {
        val p = placed ?: return 0 to 0
        if (visibility != VISIBLE) return 0 to 0
        val (l, r) = columns(p)
        return TouchGeometry.hudClear(p, l, r)
    }

    /** The game's columns: the middle 3:2 (or 10:9) at the area's height, the whole width upright. */
    private fun columns(p: TouchGeometry.Placed): Pair<Float, Float> {
        val gameW = if (portrait) p.w else minOf(p.w, p.h * gameAspect)
        val l = (p.w - gameW) / 2
        return l to l + gameW
    }

    /** Held sideways while this shows: the columns (px, this view's) the FIELD HUD keeps to, the game's own, which
     * [clearTop] / [clearBottom] are measured over. EXPAND's HUD would otherwise spread under the buttons beside them. */
    fun hudColumns(): Pair<Float, Float>? {
        val p = placed ?: return null
        if (visibility != VISIBLE || portrait) return null
        return columns(p).takeIf { (l, r) -> r - l < p.w - 1f }
    }

    /** Every button's box while this shows (px, this view's): the FIELD HUD moves its corners' parts off them. */
    fun buttonRects(): List<android.graphics.RectF> {
        val p = placed ?: return emptyList()
        if (visibility != VISIBLE) return emptyList()
        return p.items.map { android.graphics.RectF(it.left, it.top, it.right, it.bottom) }
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

    /** The presses, from the drawn pad's fingers and the input windows' alike ([TouchInputWindows]). */
    private val tracker = com.pokedaisy.app.companion.ui.TouchPressTracker(com.pokedaisy.app.companion.ui.TouchPressed())
    private var hitModel: com.pokedaisy.app.companion.ui.TouchHitModel? = null
    private var inputWindows: TouchInputWindows? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Nothing is open over the game (UI thread, asked before every draw): the input windows may take the fingers. */
    var inputAllowed: () -> Boolean = { false }

    /** Before each draw of the window this is in: the input windows follow the buttons, and step aside under an overlay. */
    private val preDraw = android.view.ViewTreeObserver.OnPreDrawListener { syncInputWindows(); true }
    private var observer: android.view.ViewTreeObserver? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observer = viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
    }

    override fun onDetachedFromWindow() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
        super.onDetachedFromWindow()
        // Moved to another window (SWAP SCREENS) or gone: the windows follow at the next draw, none meanwhile.
        inputWindows?.sync(null, false)
    }

    /** UI thread: the input windows as the buttons and the screen are now. */
    fun syncInputWindows() {
        // Not on Samsung's Android: the drawn pad takes the fingers there (TouchInputWindows.supported).
        if (!TouchInputWindows.supported) return
        val show = visibility == VISIBLE && isAttachedToWindow
        if (!show && inputWindows == null) return
        val windows = inputWindows ?: TouchInputWindows(this, tracker).also { inputWindows = it }
        windows.sync(hitModel.takeIf { show }, show && inputAllowed())
    }

    /** UI thread, from the activity's onConfigurationChanged: new input windows start from the new configuration
     * (TouchInputWindows.configurationChanged). */
    fun inputWindowsConfigurationChanged() {
        inputWindows?.configurationChanged()
    }

    /** UI thread: the activity is going - its windows with it. */
    fun releaseInputWindows() {
        inputWindows?.release()
        inputWindows = null
    }

    /** [action] on the UI thread: at once there, else posted (an input window's finger). */
    private fun onMain(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    init {
        addView(ComposeView(context).apply {
            setContent {
                TouchControls(
                    style, portrait,
                    input = TouchInput(
                        // Any thread: the activity's onMask sets the game's buttons from where it's called.
                        onKeys = { gba, turbo -> onMask?.invoke(gba, turbo) },
                        onAction = { b, down -> onMain { onAction?.invoke(b, down) } },
                        onPress = { TouchHaptics.buzz(context, style.vibration) },
                    ),
                    gameAspect = gameAspect, gameBoy = !shoulders, ffOn = ffOn, ds = dsButtons, skin = skin,
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    onPlaced = { placed = it; onPlaced?.invoke() },
                    tracker = tracker.also { it.xyKeys = !dsButtons },
                    onHitModel = { hitModel = it; syncInputWindows() },
                    unavailable = unavailable,
                )
            }
        }, LayoutParams(-1, -1))
    }
}
