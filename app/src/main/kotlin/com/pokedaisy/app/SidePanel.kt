package com.pokedaisy.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.pokedaisy.app.companion.ui.CompanionBack
import com.pokedaisy.app.companion.ui.CompanionKeys
import com.pokedaisy.app.companion.ui.LocalClickSound
import com.pokedaisy.app.companion.ui.MenuKey
import com.pokedaisy.app.companion.ui.SidePanelCloseTab
import com.pokedaisy.app.companion.ui.SidePanelHandle
import com.pokedaisy.app.companion.ui.SidePanelHost
import com.pokedaisy.app.companion.ui.SidePanelOpenTab
import com.pokedaisy.app.companion.ui.COMPACT_TEXT_INCHES
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The companion on a device with one screen (no second display for
 * [DualScreenPresentation]), held sideways, two ways (SETTINGS > COMPANION VIEW, [Prefs.companionWindow]):
 *
 * **A window** (the default): a BACK tap opens the companion in the middle of the screen, over the game (which
 * keeps running under a dim), with an X at its top right. While it's open the game's buttons drive it
 * ([menuKey]: L / R step tabs, B is the companion's back and closes it with nothing left to go back from, START
 * closes it) and the game gets none; closed, they're the game's again. A tap on the dim closes it too.
 *
 * **The side panel**: a panel on the right of the game's screen. A BACK
 * tap slides it in over the game; the tab on its edge locks it beside the game
 * instead (the game then fits in what's left - [game]'s right margin, which
 * [GameStageLayout] honours) and unlocks it again. Dragging that tab sideways
 * resizes the panel (the width is a fraction of the screen, snapped so the game
 * lands on a whole-number scale when it's close). Locked or not and the width
 * are remembered ([Prefs.sidePanelDocked], [Prefs.sidePanelWidth]); a locked
 * panel comes back with the game.
 *
 * BACK with the panel open is the companion's back ([back]); with nothing left
 * to go back from, it closes an unlocked panel (a locked one stays). Two more
 * tabs for touch: one on the screen's right edge, bottom, opens a closed panel;
 * one outside the panel's bottom-left corner closes it, locked or not. While
 * locked, both are see-through ([LOCKED_TAB_ALPHA]) - they sit over the letterbox
 * then. The touch controls stay on the game's side whenever the panel is open.
 * Open and unlocked, a tap on the game (or the FIELD HUD over it) closes it and goes on to the HUD, unless the touch
 * pad is up.
 * While the FIELD HUD is on screen it carries the open button itself, beside BAG and WILD ([tabInHud]): the edge
 * tab steps aside then.
 *
 * **Book mode** ([book], SETTINGS > FOLD LAYOUT): a foldable half open like a book. The side panel is the right page,
 * locked from the hinge to the screen's edge whatever COMPANION VIEW, the lock or the width say, the game on the left
 * page; no tabs, and BACK is only ever the companion's own.
 */
class SidePanel(
    private val root: FrameLayout,
    private val game: View,
    private val touchControls: View,
    private val prefs: Prefs,
    private val back: CompanionBack,
    /** The window's keys ([menuKey]); the companion inside registers on them. */
    private val keys: CompanionKeys,
    private val clickSound: () -> Unit,
    private val companion: @Composable () -> Unit,
    /** The panel and its tabs go right above this view (the FIELD HUD, itself above the touch controls). */
    private val under: View = touchControls,
    /** The window opened (true: the game's buttons are the companion's now) or closed. */
    private val onWindow: (Boolean) -> Unit = {},
) {
    private val context: Context get() = root.context

    /** No second screen: the companion lives here. */
    var enabled = false
        private set
    private var open = false
    private val docked = mutableStateOf(prefs.sidePanelDocked)
    /** The window on a tall screen ([COMPACT_TEXT_INCHES]): the companion's main text a step smaller (Compose state, set
     * in [apply]). Not the side panel: its [sidePanelDensity] already sets its text smaller than the window's. */
    private val compactText = mutableStateOf(false)

    /** A window in the middle rather than the side panel (Compose state: the panel's content draws it). */
    private val window = mutableStateOf(prefs.companionWindow)
    private var fraction = prefs.sidePanelWidth.coerceIn(MIN_FRACTION, MAX_FRACTION)

    /** On and closed: a tap could open it (the FIELD HUD's button for it shows then). Compose state. */
    val closed = mutableStateOf(false)

    /** SETTINGS > COMPANION VIEW is WINDOW (Compose state): the HUD keeps its COMPANION button while the window is up. */
    val windowMode: Boolean get() = isWindow

    /**
     * The companion fills the screen (Compose state): the game's screen of a two-screen device whose companion was closed
     * while swapped - the companion's own screen-sized look, as a window (its keys, B / START / the X close it) whatever
     * COMPANION VIEW says.
     */
    private val fullScreen = mutableStateOf(false)

    /**
     * Book mode: where a foldable's hinge runs down the screen (px, left to right of it, in the root's coordinates), or
     * null. See the class comment.
     */
    var book: IntRange?
        // Compose state: the panel's content reads [isWindow].
        get() = bookState.value
        set(v) {
            if (bookState.value == v) return
            bookState.value = v
            if (enabled) {
                open = v != null || (!isWindow && isDocked)
                apply()
            }
        }

    private val bookState = mutableStateOf<IntRange?>(null)

    /** Opens as a window: COMPANION VIEW's WINDOW, or [fullScreen]; never in book mode. */
    private val isWindow: Boolean get() = (window.value || fullScreen.value) && book == null

    /** Locked beside the game (the lock tab's, or book mode's). */
    private val isDocked: Boolean get() = docked.value || book != null

    fun setFullScreen(on: Boolean) {
        if (fullScreen.value == on) return
        fullScreen.value = on
        if (enabled) { open = book != null || (!isWindow && isDocked); apply() }
    }

    /** Over the game where a touch could land on it: the window, or a panel slid in unlocked (a locked one sits beside
     * the game, the touch controls moved clear of it). TouchInputWindows step aside meanwhile. */
    val overGame: Boolean get() = enabled && open && (isWindow || !isDocked)

    /** The window is up: the game's buttons are the companion's ([menuKey]). */
    var windowOpen = false
        private set

    /** SETTINGS > COMPANION BUTTON: the edge tab that opens it shows at all ([Prefs.companionTab]). */
    var openTabShown = prefs.companionTab
        set(v) {
            if (field == v) return
            field = v
            apply()
        }

    /** The FIELD HUD has the open button on screen: the edge tab steps aside. */
    var tabInHud = false
        set(v) {
            if (field == v) return
            field = v
            apply()
        }

    /** A DS battle's menu sits in the edge tab's corner: the tab goes to the top right corner meanwhile, so the
     *  companion can still be opened with a tap (GitHub #595: hidden, it left no way to tap it open). */
    var tabRaised = false
        set(v) {
            if (field == v) return
            field = v
            apply()
        }

    private var panel: View? = null
    /** Over the game and the HUD while the panel is open and unlocked: a tap there closes it ([catchOutside]). */
    private var outside: View? = null
    private var handle: View? = null
    private var openTab: View? = null
    private var closeTab: View? = null
    private var lastRootWidth = 0

    private val rootWidth get() = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
    private val rootHeight get() = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
    private val panelWidth get() = book?.let { (rootWidth - it.last).coerceAtLeast(0) } ?: (rootWidth * fraction).roundToInt()

    private val relayout = View.OnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
        if (r - l != lastRootWidth) {
            lastRootWidth = r - l
            root.post { apply() }
        }
    }

    /** On with no second screen, off when one shows up (the companion moves there). */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            docked.value = prefs.sidePanelDocked
            window.value = prefs.companionWindow
            open = book != null || (!isWindow && isDocked)
            outside = View(context).apply { setOnTouchListener { _, e -> catchOutside(e) } }
                .also { root.addView(it, root.indexOfChild(under) + 1, FrameLayout.LayoutParams(-1, -1)) }
            val at = root.indexOfChild(under) + 2
            panel = CompanionColors.track(ComposeView(context)).apply {
                setContent {
                    CompositionLocalProvider(LocalClickSound provides clickSound) {
                        SidePanelHost(
                            isWindow, fullScreen = fullScreen.value, onClose = ::hide, keys = keys, focusKeys = ::focusKey, compactText = compactText.value,
                            content = companion,
                        )
                    }
                }
            }.also { root.addView(it, at, FrameLayout.LayoutParams(panelWidth, -1, Gravity.END)) }
            handle = DragFrame(context, vertical = false, ::onDragStart, ::onDrag, ::onDragEnd).apply {
                addView(CompanionColors.track(ComposeView(context)).apply {
                    setContent {
                        CompositionLocalProvider(LocalClickSound provides clickSound) {
                            SidePanelHandle(docked.value, ::toggleDock)
                        }
                    }
                })
            }.also {
                root.addView(it, at + 1, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    topMargin = (HANDLE_TOP_DP * context.resources.displayMetrics.density).roundToInt()
                })
            }
            val bottom = (TAB_MARGIN_DP * context.resources.displayMetrics.density).roundToInt()
            closeTab = tabView { SidePanelCloseTab(::hide) }.also {
                root.addView(it, at + 2, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = bottom })
            }
            openTab = tabView { SidePanelOpenTab(::show) }.also {
                root.addView(it, at + 3, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = bottom })
            }
            // Laid out again at the root's next size: turned on mid-rotation (sideways after upright), the root still has
            // the old width here, and a width remembered from an earlier time on would skip the relayout that fixes it.
            lastRootWidth = 0
            root.addOnLayoutChangeListener(relayout)
        } else {
            root.removeOnLayoutChangeListener(relayout)
            for (v in listOfNotNull(outside, panel, handle, closeTab, openTab)) root.removeView(v)
            outside = null
            panel = null
            handle = null
            closeTab = null
            openTab = null
            open = false
        }
        apply()
    }

    private fun tabView(content: @Composable () -> Unit) = CompanionColors.track(ComposeView(context)).apply {
        setContent { CompositionLocalProvider(LocalClickSound provides clickSound, content = content) }
    }

    /** A BACK tap; false = not ours (no side panel - the second screen's companion takes it). */
    fun onBack(): Boolean {
        if (!enabled) return false
        if (book != null) {
            // The right page is always there: BACK is the companion's alone.
            back.back()
            return true
        }
        when {
            !open -> show()
            back.back() -> Unit
            isWindow || !isDocked -> hide()
        }
        return true
    }

    /**
     * One of the game's buttons while the window is open (a press, or a held direction repeating): B is BACK, START
     * closes it, the rest go to the companion ([CompanionKeys]: L / R step tabs).
     */
    fun menuKey(key: MenuKey, repeat: Boolean = false) {
        if (!windowOpen) return
        when (key) {
            MenuKey.B -> if (!repeat) onBack()
            MenuKey.START -> if (!repeat) hide()
            else -> keys.key(key, repeat)
        }
    }

    /** The left stick while the window is open, for a screen that steers with it ([CompanionKeys.stick]). */
    fun menuStick(x: Float, y: Float): Boolean = windowOpen && keys.stick(x, y)

    /** Closes the window (a battle pick, FLY: the game's to see); the side panel stays. */
    fun closeWindow() {
        if (windowOpen) hide()
    }

    /** SETTINGS > COMPANION VIEW: the window or the side panel, at once (the companion asking stays open). */
    fun setWindow(on: Boolean) {
        if (window.value == on) return
        window.value = on
        apply()
    }

    /**
     * A touch on the game (or its HUD) while the panel is open and unlocked: closes it, and the touch goes on to what's
     * under it - a HUD item (the map, a party member...) opens right after. Not with the touch controls showing - its
     * buttons are how the game is played then.
     */
    private fun catchOutside(e: android.view.MotionEvent): Boolean {
        // The panel's own empty spots (nothing to tap there) let the touch through to here: inside it, it stays open.
        val onPanel = panel?.let { e.x >= it.left + it.translationX } == true
        if (!onPanel && touchControls.visibility != View.VISIBLE && e.actionMasked == android.view.MotionEvent.ACTION_DOWN) hide()
        return false
    }

    /** A D-pad direction or A that nothing in the window's companion took: to Compose's focus in its view
     * ([sendFocusKey]). */
    private fun focusKey(key: MenuKey, repeat: Boolean, start: () -> Unit): Boolean {
        val view = panel ?: return false
        return sendFocusKey(view, key, repeat, start)
    }

    /** Opens it (the FIELD HUD's button). */
    fun openPanel() = show()

    private fun show() {
        if (open) return
        open = true
        apply()
        if (isWindow) {
            panel?.let { v ->
                // Out of touch mode at once: the first D-pad press moves the cursor (Android would eat it to leave
                // touch mode), and the rows can take the focus.
                v.requestFocusFromTouch()
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(FADE_MS).setUpdateListener { punchThrough() }.withEndAction(null).start()
            }
            return
        }
        val w = panelWidth.toFloat()
        for (v in listOfNotNull(panel, handle, closeTab)) {
            v.translationX = w
            v.animate().translationX(0f).setDuration(SLIDE_MS).setUpdateListener { punchThrough() }.withEndAction(null).start()
        }
    }

    private fun hide() {
        if (!open || book != null) return
        open = false
        if (isWindow) {
            // The buttons are the game's again now; the window fades out over it. Its focus goes: the next opening
            // starts from the cursor, and nothing in it stays lit.
            syncWindow()
            panel?.findFocus()?.clearFocus()
            panel?.animate()?.alpha(0f)?.setDuration(FADE_MS)?.setUpdateListener { punchThrough() }
                ?.withEndAction { if (!open) apply() }?.start()
            return
        }
        // The game and the touch controls take the whole screen back now; the panel slides out over them.
        outside?.visibility = View.GONE
        setRightMargin(game, 0)
        setRightMargin(touchControls, 0)
        val w = panelWidth.toFloat() + (handle?.width ?: 0)
        for (v in listOfNotNull(panel, handle, closeTab)) {
            v.animate().translationX(w).setDuration(SLIDE_MS).setUpdateListener { punchThrough() }
                .withEndAction { if (!open) apply() }.start()
        }
    }

    /**
     * The game is a SurfaceView: the window leaves a hole over it, minus the
     * views drawn on top - measured where they were at the last layout. A
     * translation alone doesn't lay out again, so a sliding panel (and its tab)
     * stayed hidden over the game where it hadn't been yet.
     */
    private fun punchThrough() {
        panel?.let { root.requestTransparentRegion(it) }
    }

    private fun toggleDock() {
        docked.value = !docked.value
        prefs.sidePanelDocked = docked.value
        apply()
    }

    /** Tells [onWindow] when the window comes up or goes away. */
    private fun syncWindow() {
        val up = enabled && open && isWindow
        if (up == windowOpen) return
        windowOpen = up
        onWindow(up)
    }

    /** The screen's height (its short side) in inches, from the root's size (the whole screen: the game is full screen)
     * and its dpi. */
    private fun screenHeightInches(): Float {
        val dm = context.resources.displayMetrics
        return minOf(rootWidth / dm.xdpi, rootHeight / dm.ydpi)
    }

    /** Lays everything out for the current state. */
    private fun apply() {
        syncWindow()
        compactText.value = isWindow && screenHeightInches() >= COMPACT_TEXT_INCHES
        val w = panelWidth
        val shown = enabled && open
        val win = isWindow
        // The window: the panel's view is the whole screen (its dim), the companion drawn in the middle, no tabs.
        val side = shown && !win
        val tabs = side && book == null
        outside?.visibility = if (side && !isDocked) View.VISIBLE else View.GONE
        panel?.apply {
            visibility = if (shown) View.VISIBLE else View.GONE
            translationX = 0f
            alpha = 1f
            layoutParams = layoutParams.apply { width = if (win) FrameLayout.LayoutParams.MATCH_PARENT else w }
        }
        handle?.apply {
            visibility = if (tabs) View.VISIBLE else View.GONE
            translationX = 0f
            setRightMargin(this, w)
        }
        closeTab?.apply {
            visibility = if (tabs) View.VISIBLE else View.GONE
            translationX = 0f
            alpha = if (docked.value) LOCKED_TAB_ALPHA else 1f
            setRightMargin(this, w)
        }
        closed.value = enabled && !open
        openTab?.apply {
            visibility = if (enabled && !open && !tabInHud && openTabShown && book == null) View.VISIBLE else View.GONE
            alpha = if (docked.value && !win) LOCKED_TAB_ALPHA else 1f
            val lp = layoutParams as FrameLayout.LayoutParams
            val gravity = Gravity.END or if (tabRaised) Gravity.TOP else Gravity.BOTTOM
            if (lp.gravity != gravity) {
                val margin = ((if (tabRaised) HANDLE_TOP_DP else TAB_MARGIN_DP) * context.resources.displayMetrics.density).roundToInt()
                lp.gravity = gravity
                lp.topMargin = if (tabRaised) margin else 0
                lp.bottomMargin = if (tabRaised) 0 else margin
                layoutParams = lp
            }
        }
        // Book mode: the game's page ends at the hinge, not at the companion's edge.
        val gameEdge = book?.let { (rootWidth - it.first).coerceAtLeast(0) } ?: w
        setRightMargin(game, if (side && isDocked) gameEdge else 0)
        setRightMargin(touchControls, if (side) gameEdge else 0)
    }

    private fun setRightMargin(v: View, margin: Int) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.rightMargin == margin) return
        lp.rightMargin = margin
        v.layoutParams = lp
    }

    // --- resizing by the tab -------------------------------------------------

    private var dragStartWidth = 0

    private fun onDragStart() {
        dragStartWidth = panelWidth
    }

    private fun onDrag(dx: Float) {
        if (book != null) return
        fraction = ((dragStartWidth - dx) / rootWidth).coerceIn(MIN_FRACTION, MAX_FRACTION)
        apply()
    }

    private fun onDragEnd() {
        if (book != null) return
        // A game area within a few pixels of a whole-number scale snaps to it (half a 1080p
        // screen is exactly 4x already).
        val area = rootWidth - panelWidth
        val n = (area / GBA_W.toFloat()).roundToInt()
        if (n >= 1 && GBA_H * n <= rootHeight && abs(GBA_W * n - area) <= SNAP_PX) {
            fraction = ((rootWidth - GBA_W * n).toFloat() / rootWidth).coerceIn(MIN_FRACTION, MAX_FRACTION)
        }
        prefs.sidePanelWidth = fraction
        apply()
    }

    private companion object {
        const val MIN_FRACTION = 0.3f
        const val MAX_FRACTION = 0.75f
        const val HANDLE_TOP_DP = 12
        const val TAB_MARGIN_DP = 12
        /** The open / close tabs while the panel is locked: there, but out of the way. */
        const val LOCKED_TAB_ALPHA = 0.35f
        const val SLIDE_MS = 160L
        const val FADE_MS = 120L
        const val GBA_W = 240
        const val GBA_H = 160
        const val SNAP_PX = 48
    }
}
