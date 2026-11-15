package com.pokedaisy.app.n3ds

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * ONE SCREEN's floating touch screen frame for a 3DS game, drawn smooth like the game's own screens (the user's call: a
 * 3DS game's look, not the app's pixel windows). From the outside in: a soft shadow, a dark edge, a silver rim light at
 * the top (the battle buttons' silver, XyBattleMenu.kt), a dark line and a charcoal band up to the screen, like a
 * console's bezel. The touch surface covers the middle, [FRAME_DP] in from every side; its corners stay square (a
 * SurfaceView can't be rounded), the frame's outer ones are round.
 */
class N3dsWindowFrame(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val d = density
        val b = bounds
        // The shadow: rings fading out, a little lower than the window.
        val shadow = SHADOW_DP * d
        paint.shader = null
        paint.style = Paint.Style.FILL
        for (i in 0 until SHADOW_STEPS) {
            val k = i / SHADOW_STEPS.toFloat()
            val inset = shadow * k
            rect.set(b.left + inset, b.top + inset + SHADOW_DROP_DP * d * (1 - k), b.right - inset, b.bottom - inset + SHADOW_DROP_DP * d * (1 - k))
            paint.color = 0x0E000000
            canvas.drawRoundRect(rect, RADIUS_DP * d + shadow - inset, RADIUS_DP * d + shadow - inset, paint)
        }
        var r = RADIUS_DP * d
        rect.set(b.left + shadow, b.top + shadow, b.right - shadow, b.bottom - shadow)
        fun layer(width: Float, top: Int, bottom: Int) {
            paint.shader = if (top == bottom) null else LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
            paint.color = top
            canvas.drawRoundRect(rect, r, r, paint)
            rect.inset(width, width)
            r = (r - width).coerceAtLeast(INNER_RADIUS_DP * d)
        }
        layer(EDGE_DP * d, EDGE, EDGE)
        layer(SILVER_DP * d, SILVER_TOP, SILVER_BOTTOM)
        layer(LINE_DP * d, EDGE, EDGE)
        layer(BAND_DP * d, BAND_TOP, BAND_BOTTOM)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private const val SHADOW_DP = 6f
        private const val SHADOW_DROP_DP = 2f
        private const val SHADOW_STEPS = 6
        private const val EDGE_DP = 1f
        private const val SILVER_DP = 4f
        private const val LINE_DP = 1f
        private const val BAND_DP = 3f
        private const val RADIUS_DP = 12f
        private const val INNER_RADIUS_DP = 2f

        /** The frame's width on each side (shadow included), in dp: the stage's dsTouchFrame. */
        const val FRAME_DP = SHADOW_DP + EDGE_DP + SILVER_DP + LINE_DP + BAND_DP

        private const val EDGE = 0xFF3A3A3A.toInt()
        private const val SILVER_TOP = 0xFFFDFDFD.toInt()
        private const val SILVER_BOTTOM = 0xFFB4B4B4.toInt()
        private const val BAND_TOP = 0xFF4A4A4A.toInt()
        private const val BAND_BOTTOM = 0xFF222222.toInt()
    }
}
