package com.pokedaisy.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.pokedaisy.app.companion.ui.CompanionBack
import com.pokedaisy.app.companion.ui.LocalClickSound
import com.pokedaisy.app.companion.ui.LocalCompanionTopInset
import com.pokedaisy.app.companion.ui.PortraitGrip
import com.pokedaisy.app.companion.ui.PortraitLayout
import com.pokedaisy.app.companion.ui.SidePanelCompanion

/**
 * The companion on a single-screen device held upright (a phone in portrait):
 * the game across the top of the screen ([GameStageLayout.topAligned]), the
 * companion under it, always open - along the screen's bottom, or right under
 * the game with the touch controls below it ([Prefs.portraitCompanionUnderGame]; see
 * [PortraitLayout.arrange]). Its height is the player's
 * ([Prefs.portraitCompanionRatio]): drag the grip on its edge, or tap it to step
 * through small / the Thor's shape / everything under the game. The gap left
 * takes the touch controls when it's shown and fits; else the pad lies over the game
 * as usual. The grip has a strip of its own and never covers the game; only a
 * screen too short for anything else puts it inside the companion's top. BACK is the companion's back
 * ([back]).
 *
 * Held sideways the same device gets [SidePanel] instead (PokeDaisyActivity
 * switches between them on rotation) - but a foldable half open like a laptop gets this one either way, in tabletop
 * mode ([hinge]): the game above the fold, the companion filling everything below it, no grip.
 */
class PortraitPanel(
    private val root: GameStageLayout,
    private val game: View,
    private val touchControls: View,
    private val prefs: Prefs,
    private val back: CompanionBack,
    private val clickSound: () -> Unit,
    private val companion: @Composable () -> Unit,
) {
    private val context: Context get() = root.context

    var enabled = false
        private set
    private var ratio = prefs.portraitCompanionRatio

    /** The companion put away under its grip (the COMPANION hotkey, a step of the grip's taps). A DS game's touch screen never is. */
    var hidden = prefs.portraitCompanionHidden
        private set
    private val hiddenNow get() = hidden && !ds

    /** Hides the companion, or brings it back at its size. */
    fun toggleHidden() = setHidden(!hidden)

    /** The buttons are going to the companion: it has to be there. */
    fun reveal() = setHidden(false)

    private fun setHidden(on: Boolean) {
        if (hidden == on) return
        hidden = on
        prefs.portraitCompanionHidden = on
        apply()
    }

    /** A DS game: its touch screen fills the companion's place ([DsTouchPane]), with a height of its own
     * ([Prefs.portraitDsRatio]). */
    var ds = false
        set(v) {
            if (field == v) return
            field = v
            ratio = storedRatio()
            apply()
        }

    private fun storedRatio() = if (ds) prefs.portraitDsRatio else prefs.portraitCompanionRatio

    /** ASPECT > WIDE: the game fills everything above the companion ([GameStageLayout.expand]). */
    var expand = false
        set(v) {
            if (field == v) return
            field = v
            apply()
        }
    private var underGame = prefs.portraitCompanionUnderGame

    /** The touch controls' own strip along the bottom (px) for UNDER GAME; 0 while they're hidden. */
    var padStrip = 0
        set(v) {
            if (field == v) return
            field = v
            apply()
        }

    /**
     * SETTINGS > FOLD LAYOUT, a foldable half open with its hinge across the screen (TABLETOP): where the hinge runs
     * (px, top to bottom of it, in the stage's coordinates). The game and the touch controls get everything above it,
     * the companion everything below; null = the usual upright layout.
     */
    var hinge: IntRange? = null
        set(v) {
            if (field == v) return
            field = v
            apply()
        }

    /** In tabletop mode ([hinge]). */
    val tabletop: Boolean get() = enabled && hinge != null

    private var panel: View? = null
    private var gripView: View? = null
    private val grip = mutableStateOf(PortraitLayout.Grip.ABOVE)
    private val topInset = mutableIntStateOf(0)
    private var laidOutFor = Triple(0, 0, 0)

    private val screenW get() = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
    private val screenH get() = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
    private val gameH get() = root.gameHeightFor(screenW)
    private val gripH get() = gripView?.height ?: 0
    /** The grip's strip, kept free in either placement ([PortraitLayout.arrange]). */
    private val reserve get() = gripH + if (underGame && padStrip > 0) minOf(padStrip, screenH / 3) else 0
    private fun arrangement() = PortraitLayout.arrange(screenW, screenH, gameH, gripH, ratio, underGame, expand, padStrip, hiddenNow)

    // A rotation, the status bar coming or going, a Game Boy game's 10:9: lay out again.
    private val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val now = Triple(screenW, screenH, gameH)
        if (now != laidOutFor) root.post { apply() }
    }

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            ratio = storedRatio()
            underGame = prefs.portraitCompanionUnderGame
            val at = root.indexOfChild(touchControls) + 1
            panel = CompanionColors.track(ComposeView(context)).apply {
                setContent {
                    CompositionLocalProvider(LocalCompanionTopInset provides topInset.intValue) { SidePanelCompanion(content = companion) }
                }
            }.also { root.addView(it, at, FrameLayout.LayoutParams(-1, 0, Gravity.TOP)) }
            gripView = DragFrame(context, vertical = true, ::onDragStart, ::onDrag, ::onDragEnd).apply {
                addView(CompanionColors.track(ComposeView(context)).apply {
                    setContent {
                        CompositionLocalProvider(LocalClickSound provides clickSound) { PortraitGrip(grip.value, ::stepSize) }
                    }
                })
            }.also {
                root.addView(it, at + 1, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }
            // The grip's own height decides where things go: place them again once it's measured.
            gripView?.addOnLayoutChangeListener { v, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) v.post { apply() } }
            root.addOnLayoutChangeListener(relayout)
            root.topAligned = true
        } else {
            root.removeOnLayoutChangeListener(relayout)
            for (v in listOfNotNull(panel, gripView)) root.removeView(v)
            panel = null
            gripView = null
            root.topAligned = false
            hinge = null
            setMargins(game, 0, 0)
            setMargins(touchControls, 0, 0)
        }
        apply()
    }

    /** Picks up SETTINGS' COMPANION (along the bottom / under the game) and the size from [prefs]. */
    fun refresh() {
        if (!enabled) return
        ratio = storedRatio()
        underGame = prefs.portraitCompanionUnderGame
        apply()
    }

    /** Where the companion is on the screen (px), for TOUCH CONTROLS' preview; null while off. */
    fun companionBounds(): androidx.compose.ui.geometry.Rect? {
        val v = panel ?: return null
        val lp = v.layoutParams as FrameLayout.LayoutParams
        return androidx.compose.ui.geometry.Rect(0f, lp.topMargin.toFloat(), screenW.toFloat(), (lp.topMargin + lp.height).toFloat())
    }

    /** A BACK tap; false = not ours (held sideways, or a second screen has the companion). */
    fun onBack(): Boolean {
        if (!enabled) return false
        back.back()
        return true
    }

    private fun apply() {
        if (!enabled) return
        val fold = hinge
        if (fold != null) {
            // TABLETOP: the hinge is the split; the grip has nothing to size.
            val h = screenH
            val top = fold.first.coerceIn(0, h)
            val bottom = fold.last.coerceIn(top, h)
            laidOutFor = Triple(screenW, h, gameH)
            grip.value = PortraitLayout.Grip.ABOVE
            topInset.intValue = 0
            gripView?.visibility = View.GONE
            // Below the fold is the companion's whole half: shown even when it was put away upright.
            panel?.let { setFrame(it, bottom, h - bottom); it.visibility = View.VISIBLE }
            setMargins(game, 0, h - top)
            setMargins(touchControls, 0, h - top)
            return
        }
        gripView?.visibility = View.VISIBLE
        val h = screenH
        val a = arrangement()
        laidOutFor = Triple(screenW, h, gameH)
        grip.value = a.grip
        topInset.intValue = a.topInset(gripH)
        panel?.let {
            setFrame(it, a.companionTop, a.companionHeight)
            it.visibility = if (hiddenNow) View.GONE else View.VISIBLE
        }
        gripView?.let { setMargins(it, a.gripTop, 0) }
        setMargins(game, 0, h - a.gameAreaBottom)
        setMargins(touchControls, a.padTop, h - a.padBottom)
    }

    private fun setFrame(v: View, top: Int, height: Int) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin == top && lp.height == height) return
        lp.topMargin = top
        lp.height = height
        v.layoutParams = lp
    }

    private fun setMargins(v: View, top: Int, bottom: Int) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin == top && lp.bottomMargin == bottom) return
        lp.topMargin = top
        lp.bottomMargin = bottom
        v.layoutParams = lp
    }

    private fun save() {
        if (ds) prefs.portraitDsRatio = ratio else prefs.portraitCompanionRatio = ratio
        apply()
    }

    private val currentRatio get() = arrangement().companionHeight.toFloat() / screenW

    /** Round the sizes; past the largest it hides (a DS game's touch screen doesn't), and the next tap is the smallest. */
    private fun stepSize() {
        if (hinge != null) return
        if (hiddenNow) return setHidden(false)
        val now = currentRatio
        ratio = PortraitLayout.next(screenW, screenH, gameH, now, reserve)
        if (!ds && ratio < now) { hidden = true; prefs.portraitCompanionHidden = true }
        save()
    }

    // --- resizing by the grip --------------------------------------------------

    private var dragStartHeight = 0
    private var dragGrows = -1

    private fun onDragStart() {
        dragStartHeight = arrangement().companionHeight
        if (hiddenNow) setHidden(false)
        // Along the bottom the grip is on the companion's top edge: up = taller. Under the game it hangs below: down.
        dragGrows = if (underGame && !expand) 1 else -1
    }

    private fun onDrag(dy: Float) {
        if (hinge != null) return
        ratio = ((dragStartHeight + dragGrows * dy) / screenW).coerceAtLeast(0f)
        apply()
    }

    private fun onDragEnd() {
        if (hinge != null) return
        ratio = PortraitLayout.snap(screenW, screenH, gameH, currentRatio, reserve)
        save()
    }
}
