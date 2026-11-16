package com.pokedaisy.app

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import com.pokedaisy.app.companion.DsArrangement
import com.pokedaisy.app.companion.ui.fittedGamePicture

/**
 * Native renderer adapter: fits the GL surface/status strip or the DS/skin pictures inside the viewport assigned
 * by shared Compose. It owns no companion or control placement. Each native/Compose leaf is measured once per pass;
 * measuring a nested ComposeView first full-size and then fitted repeatedly invalidates the outer Compose layout.
 */
class GameViewportView(context: Context, private val game: View, private val bar: View) : FrameLayout(context) {
    var aspect = 1.5f
        set(v) { if (v > 0 && v != field) { field = v; requestLayout() } }
    var stretch = false
        set(v) { if (v != field) { field = v; requestLayout() } }
    var expand = false
        set(v) { if (v != field) { field = v; requestLayout() } }
    var topAligned = false
        set(v) { if (v != field) { field = v; requestLayout() } }
    var dsArrange: ((Int, Int) -> DsArrangement)? = null
        set(v) { if (v !== field) { field = v; requestLayout() } }
    var skinGame: ((Int, Int) -> androidx.compose.ui.geometry.Rect?)? = null
        set(v) { if (v !== field) { field = v; requestLayout() } }
    var skinHost: View? = null
    var controlsArea: androidx.compose.ui.geometry.Rect? = null
    var gameCover: View? = null
    var gameDim: View? = null
    var dsTouch: View? = null
    var dsBackdrop: View? = null
    var dsTouchFrame = 0
    private var ds: DsArrangement? = null
    private var picture = Rect()
    private var backdrop = Rect()
    private var barHeight = 0
    private var barRect = Rect()

    fun gameHeightFor(width: Int) = (width / aspect).toInt() + if (bar.visibility != GONE) bar.measuredHeight else 0
    val dsGameOffset get() = ds?.let { it.top.left to it.top.top + barHeight } ?: (0 to 0)
    val dsTouchOverGame: Float get() {
        val a = ds ?: return 0f
        val t = a.touch ?: return 0f
        val g = a.top
        if (g.height <= 0 || t.left >= g.right || t.right <= g.left || t.top >= g.bottom || t.bottom <= g.top) return 0f
        return ((g.bottom - t.top + dsTouchFrame * 3).toFloat() / g.height).coerceIn(0f, 1f)
    }
    fun overlayBounds() = Rect(if (ds != null) backdrop else picture)
    val hudTop get() = if (ds != null) backdrop.top + barHeight else picture.top

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val lp = game.layoutParams as LayoutParams
        val aw = (w - lp.leftMargin - lp.rightMargin).coerceAtLeast(0)
        val ah = (h - lp.topMargin - lp.bottomMargin).coerceAtLeast(0)
        val originX = lp.leftMargin
        val originY = lp.topMargin
        val oldBar = barHeight
        val arranger = dsArrange.takeUnless { topAligned }
        val skin = if (arranger == null && !topAligned && skinHost?.visibility != GONE) controlsArea?.let { area ->
            skinGame?.invoke(area.width.toInt(), area.height.toInt())?.translate(area.left, area.top)
        } else null
        val estimate = arranger?.invoke(aw, (ah - oldBar).coerceAtLeast(0))
        val barW = when {
            estimate != null -> estimate.top.width
            skin != null -> skin.width.toInt()
            stretch && !topAligned || expand && topAligned -> aw
            bar.visibility != GONE || topAligned -> fittedGamePicture(aw.toFloat(), (ah - oldBar).toFloat(), aspect).width.toInt()
            else -> aw
        }
        barHeight = if (bar.visibility == GONE) 0 else {
            bar.measure(exact(barW), MeasureSpec.makeMeasureSpec(ah, MeasureSpec.AT_MOST))
            bar.measuredHeight
        }
        ds = arranger?.invoke(aw, (ah - barHeight).coerceAtLeast(0))
        val a = ds
        when {
            a != null -> {
                backdrop.set(originX, originY, originX + aw, originY + ah)
                picture.set(originX + a.top.left, originY + barHeight + a.top.top, originX + a.top.right, originY + barHeight + a.top.bottom)
                barRect.set(picture.left, picture.top - barHeight, picture.right, picture.top)
                dsBackdrop?.takeIf { it.visibility != GONE }?.measure(exact(aw), exact(ah))
                a.touch?.let { t -> dsTouch?.takeIf { it.visibility != GONE }?.measure(exact(t.width + dsTouchFrame * 2), exact(t.height + dsTouchFrame * 2)) }
            }
            skin != null && skin.width > 0 && skin.height > 0 -> {
                picture.set(skin.left.toInt(), skin.top.toInt() + barHeight, skin.right.toInt(), skin.bottom.toInt())
                barRect.set(picture.left, skin.top.toInt(), picture.right, picture.top)
            }
            else -> {
                val fit = (bar.visibility != GONE || topAligned) && !((stretch && !topAligned) || (expand && topAligned))
                val gw = if (fit) fittedGamePicture(aw.toFloat(), (ah - barHeight).toFloat(), aspect).width.toInt() else aw
                val gh = if (fit) (gw / aspect).toInt() else (ah - barHeight).coerceAtLeast(0)
                val x = originX + (aw - gw) / 2
                val y = originY + if (topAligned) 0 else (ah - barHeight - gh) / 2
                barRect.set(x, y, x + gw, y + barHeight)
                picture.set(x, y + barHeight, x + gw, y + barHeight + gh)
            }
        }
        game.measure(exact(picture.width()), exact(picture.height()))
        gameDim?.measure(exact(picture.width()), exact(picture.height()))
        gameCover?.takeIf { it.visibility != GONE }?.measure(exact(picture.width()), exact(picture.height()))
        // A new strip height changes the fitted width. Correct its width in the next pass, without measuring a
        // nested Compose view twice in this one. The height is stable once the width has settled.
        if (barHeight != oldBar || bar.visibility != GONE && bar.measuredWidth != barRect.width()) post { requestLayout() }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        game.layout(picture.left, picture.top, picture.right, picture.bottom)
        gameDim?.layout(picture.left, picture.top, picture.right, picture.bottom)
        gameCover?.takeIf { it.visibility != GONE }?.layout(picture.left, picture.top, picture.right, picture.bottom)
        if (bar.visibility != GONE) bar.layout(barRect.left, barRect.top, barRect.right, barRect.bottom)
        ds?.let { a ->
            dsBackdrop?.takeIf { it.visibility != GONE }?.layout(backdrop.left, backdrop.top, backdrop.right, backdrop.bottom)
            a.touch?.let { t ->
                val f = dsTouchFrame
                dsTouch?.takeIf { it.visibility != GONE }?.layout(backdrop.left + t.left - f,
                    backdrop.top + barHeight + t.top - f, backdrop.left + t.right + f, backdrop.top + barHeight + t.bottom + f)
            }
        }
    }
    private fun exact(n: Int) = MeasureSpec.makeMeasureSpec(n.coerceAtLeast(0), MeasureSpec.EXACTLY)
}
