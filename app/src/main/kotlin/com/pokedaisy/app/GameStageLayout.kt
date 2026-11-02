package com.pokedaisy.app

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

/**
 * The top screen. While [bar] (the status bar) is shown, [game] is fitted at
 * the GBA's 3:2 in the space left under it, [bar] exactly as wide as the game
 * right above it, the pair centred; [huds] move down under the bar. With the
 * bar gone it's a plain FrameLayout ([game] fills its area and letterboxes
 * itself), as before. [game]'s margins bound its area either way. With
 * [stretch] the game takes all the space under the bar (and stretches itself
 * to fill it, see [EmulatorView.stretch]). With [topAligned] (a phone held
 * upright, see [PortraitPanel]) the game (and bar) sit at the top of their area
 * at the game's own shape, whatever [stretch] says, leaving the rest below -
 * unless [expand] (WIDE), which fills that area too: the game draws the map above
 * and below itself there, as it does beside itself on a wide screen.
 */
class GameStageLayout(
    context: Context,
    private val game: View,
    private val bar: View,
    /** The HUD's messages and the FPS / CPU readout: kept under the bar. */
    private val huds: List<View>,
    /** Laid out exactly over [game], wherever it ends up (the FIELD HUD, the companion's rebind popup). */
    private val overGame: List<View> = emptyList(),
) : FrameLayout(context) {

    private var gameW = 0
    private var gameH = 0
    private var barH = 0

    private val barShown get() = bar.visibility != GONE

    /** The game screen's width / height: the GBA's 3:2, a Game Boy's 10:9 (set from the core). */
    var aspect = GBA_ASPECT
        set(v) {
            if (field == v || v <= 0f) return
            field = v
            requestLayout()
        }

    /** Fill the area instead of fitting [aspect] in it: ASPECT > STRETCH or WIDE ([Prefs.gameAspect]). */
    var stretch = false
        set(v) {
            if (field == v) return
            field = v
            requestLayout()
        }

    /** ASPECT > WIDE: the game takes its whole area even when [topAligned] (it fills it with the rebuilt map). */
    var expand = false
        set(v) {
            if (field == v) return
            field = v
            requestLayout()
        }

    /** Game and bar at the top of their area, at the game's shape (see the class comment). */
    var topAligned = false
        set(v) {
            if (field == v) return
            field = v
            requestLayout()
        }

    /**
     * A DS game on one screen held sideways (SETTINGS > DS LAYOUT): where its two screens go in the area under the bar,
     * or null for the usual. [game] goes in the arrangement's top box, [dsTouch] (its frame [dsTouchFrame] px round it)
     * in the touch box - or GONE when there's none - and [dsBackdrop] behind both fills the area.
     */
    var dsArrange: ((Int, Int) -> com.pokedaisy.app.companion.DsArrangement)? = null
        set(v) {
            if (field === v) return
            field = v
            requestLayout()
        }

    /**
     * TOUCH CONTROLS > SKIN held sideways: where the skin's screen puts the game, in [skinHost]'s area (the touch
     * controls' view, its margins taken off) of the given size; null = the usual place. The game's view is laid out
     * there exactly (it fits its picture in itself), the status bar, when shown, over its top.
     */
    var skinGame: ((Int, Int) -> androidx.compose.ui.geometry.Rect?)? = null
        set(v) {
            if (field === v) return
            field = v
            requestLayout()
        }

    /** The view [skinGame]'s area is: the touch controls. */
    var skinHost: View? = null

    private var skinLaid: android.graphics.Rect? = null

    /** The touch screen's view for [dsArrange] (a child of this layout, over [game]). */
    var dsTouch: View? = null

    /** The game's backdrop for [dsArrange] (a child of this layout, under [game]). */
    var dsBackdrop: View? = null

    /** The frame round [dsTouch] (PICTURE IN PICTURE's edge), px each side. */
    var dsTouchFrame = 0

    private var dsLaid: com.pokedaisy.app.companion.DsArrangement? = null

    /** How tall the game (and the bar over it, when shown) comes out at [width] wide, fitted. A bar not
     * attached to a window yet counts as 0: measuring a ComposeView then throws ("Cannot locate
     * windowRecomposer" - issue #31, PortraitPanel enabled from onResume); its first layout corrects it. */
    fun gameHeightFor(width: Int): Int {
        val b = if (!barShown || !bar.isAttachedToWindow) 0 else bar.measuredHeight.takeIf { it > 0 } ?: run {
            bar.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            bar.measuredHeight
        }
        return (width / aspect).toInt() + b
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureStage(widthMeasureSpec, heightMeasureSpec)
        // DS LAYOUT: over the whole area (BATTLE BUTTONS go beside the screens, in the backdrop's room).
        val ds = dsLaid != null
        val w = if (ds) dsBackdrop?.measuredWidth ?: game.measuredWidth else game.measuredWidth
        val h = if (ds) dsBackdrop?.measuredHeight ?: game.measuredHeight else game.measuredHeight
        overGame.forEach { if (it.visibility != GONE) it.measure(exactly(w), exactly(h)) }
    }

    /** DS LAYOUT: where the game's box starts in the area [overGame] covers (px), to move the game's rects into it. */
    val dsGameOffset: Pair<Int, Int>
        get() = dsLaid?.let { it.top.left to it.top.top + barH } ?: (0 to 0)

    /** DS LAYOUT: how much of the game's box, from its bottom edge, the touch screen covers where it sits over the game
     * (PICTURE IN PICTURE's inset, its frame and a little room), as a share of the box's height; 0 where it's beside it. */
    val dsTouchOverGame: Float
        get() {
            val a = dsLaid ?: return 0f
            val t = a.touch ?: return 0f
            val g = a.top
            if (g.height <= 0 || t.left >= g.right || t.right <= g.left || t.top >= g.bottom || t.bottom <= g.top) return 0f
            return ((g.bottom - t.top + dsTouchFrame * 3).toFloat() / g.height).coerceIn(0f, 1f)
        }

    private fun measureStage(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        dsLaid = null
        skinLaid = null
        val ds = dsArrange
        if (ds != null && !topAligned) { measureDs(ds); return }
        if (!topAligned && measureSkin()) return
        if (!barShown && !topAligned) return
        val lp = game.layoutParams as LayoutParams
        val areaW = measuredWidth - lp.leftMargin - lp.rightMargin
        val areaH = measuredHeight - lp.topMargin - lp.bottomMargin
        barH = if (barShown) {
            bar.measure(
                MeasureSpec.makeMeasureSpec(areaW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(areaH, MeasureSpec.AT_MOST),
            )
            bar.measuredHeight
        } else 0
        if ((stretch && !topAligned) || (expand && topAligned)) {
            gameW = areaW.coerceAtLeast(0)
            gameH = (areaH - barH).coerceAtLeast(0)
        } else {
            gameW = min(areaW.toFloat(), (areaH - barH) * aspect).toInt().coerceAtLeast(0)
            gameH = (gameW / aspect).toInt()
        }
        game.measure(exactly(gameW), exactly(gameH))
        if (barShown) bar.measure(exactly(gameW), exactly(barH))
    }

    /** SKIN sideways: the game in the skin's screen ([skinGame]); false = no skin (or no screen in it). */
    private fun measureSkin(): Boolean {
        val place = skinGame ?: return false
        val host = skinHost?.takeIf { it.visibility != GONE } ?: return false
        val lp = host.layoutParams as? MarginLayoutParams ?: return false
        val areaW = measuredWidth - lp.leftMargin - lp.rightMargin
        val areaH = measuredHeight - lp.topMargin - lp.bottomMargin
        if (areaW <= 0 || areaH <= 0) return false
        val r = place(areaW, areaH) ?: return false
        val rect = android.graphics.Rect(
            lp.leftMargin + r.left.toInt(), lp.topMargin + r.top.toInt(),
            lp.leftMargin + r.right.toInt(), lp.topMargin + r.bottom.toInt(),
        )
        if (rect.width() <= 0 || rect.height() <= 0) return false
        barH = if (barShown) {
            bar.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.AT_MOST))
            bar.measuredHeight
        } else 0
        gameW = rect.width()
        gameH = (rect.height() - barH).coerceAtLeast(0)
        game.measure(exactly(gameW), exactly(gameH))
        skinLaid = rect
        return true
    }

    private fun measureDs(ds: (Int, Int) -> com.pokedaisy.app.companion.DsArrangement) {
        val lp = game.layoutParams as LayoutParams
        val areaW = (measuredWidth - lp.leftMargin - lp.rightMargin).coerceAtLeast(0)
        val areaH = (measuredHeight - lp.topMargin - lp.bottomMargin).coerceAtLeast(0)
        barH = if (barShown) {
            bar.measure(MeasureSpec.makeMeasureSpec(areaW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(areaH, MeasureSpec.AT_MOST))
            bar.measuredHeight
        } else 0
        val a = ds(areaW, (areaH - barH).coerceAtLeast(0))
        dsLaid = a
        gameW = a.top.width
        gameH = a.top.height
        game.measure(exactly(gameW), exactly(gameH))
        if (barShown) bar.measure(exactly(gameW), exactly(barH))
        dsBackdrop?.measure(exactly(areaW), exactly(areaH))
        val t = a.touch
        dsTouch?.let { v ->
            if (t != null && v.visibility != GONE) v.measure(exactly(t.width + dsTouchFrame * 2), exactly(t.height + dsTouchFrame * 2))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        layoutStage(changed, left, top, right, bottom)
        val area = dsBackdrop?.takeIf { dsLaid != null }
        overGame.forEach {
            if (it.visibility == GONE) return@forEach
            if (area != null) it.layout(area.left, area.top, area.right, area.bottom)
            else it.layout(game.left, game.top, game.right, game.bottom)
        }
    }

    private fun layoutStage(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        dsLaid?.let { a ->
            val lp = game.layoutParams as LayoutParams
            val x = lp.leftMargin
            val y = lp.topMargin
            dsBackdrop?.layout(x, y, x + (dsBackdrop?.measuredWidth ?: 0), y + (dsBackdrop?.measuredHeight ?: 0))
            if (barShown) bar.layout(x + a.top.left, y + a.top.top, x + a.top.right, y + a.top.top + barH)
            game.layout(x + a.top.left, y + barH + a.top.top, x + a.top.right, y + barH + a.top.bottom)
            a.touch?.let { t ->
                val f = dsTouchFrame
                dsTouch?.layout(x + t.left - f, y + barH + t.top - f, x + t.right + f, y + barH + t.bottom + f)
            }
            if (barH > 0) huds.forEach { it.offsetTopAndBottom(y + barH) }
            return
        }
        skinLaid?.let { r ->
            if (barShown) bar.layout(r.left, r.top, r.right, r.top + barH)
            game.layout(r.left, r.top + barH, r.right, r.top + barH + gameH)
            huds.forEach { it.offsetTopAndBottom(r.top + barH) }
            return
        }
        if (!barShown && !topAligned) return
        val lp = game.layoutParams as LayoutParams
        val areaW = width - lp.leftMargin - lp.rightMargin
        val areaH = height - lp.topMargin - lp.bottomMargin
        val x = lp.leftMargin + (areaW - gameW) / 2
        val y = lp.topMargin + if (topAligned) 0 else (areaH - barH - gameH) / 2
        if (barShown) bar.layout(x, y, x + gameW, y + barH)
        game.layout(x, y + barH, x + gameW, y + barH + gameH)
        huds.forEach { it.offsetTopAndBottom(y + barH) }
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    private companion object {
        const val GBA_ASPECT = 240f / 160f
    }
}
