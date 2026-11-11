package com.pokedaisy.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.view.Gravity
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A DS game's bottom (touch) screen on the bottom display, where the companion goes for a GBA game: the DS's own
 * picture and its stylus from this screen's touches. The companion goes on the game's screen instead, as on a
 * one-screen device (PokeDaisyActivity.syncPresentation). The frame is copied between frames on the emu thread
 * ([NdsBottomFrame.publish]), like the top screen's ([EmulatorView.publishFrame]).
 */
object NdsBottomFrame {
    const val W = 256
    const val H = 192

    private val lock = Any()
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(W * H * 4)
    /** The core's bottom-screen buffer while a DS core runs ([bind] / [unbind]). */
    private var source: ByteBuffer? = null
    private val views = CopyOnWriteArrayList<NdsTouchScreenView>()

    /** Emu thread, once the DS core is up. */
    fun bind(buffer: ByteBuffer?) = synchronized(lock) { source = buffer; held = false }

    /** Emu thread, before the core frees its buffer. */
    fun unbind() = synchronized(lock) { source = null; held = false }

    /** DS LAYOUT's transition hold (EmulatorEngine.dsHold): the last frame stays, new ones aren't taken, and taps don't
     * reach the game (it's behind the held picture). Emu thread. */
    @Volatile var held = false

    /** Emu thread, after each frame the screen shows. */
    fun publish() {
        if (held) return
        synchronized(lock) {
            val src = source ?: return
            src.rewind()
            pixels.clear()
            pixels.put(src)
            blank = isOneColour()
        }
        for (v in views) v.postInvalidateOnAnimation()
    }

    /** The last frame is one colour (a fade, an evolution's blank touch screen): nothing there to touch. */
    @Volatile var blank = false
        private set

    // A 16 x 12 grid of the frame's pixels all alike.
    private fun isOneColour(): Boolean {
        val first = pixels.getInt(0)
        for (gy in 0 until 12) for (gx in 0 until 16) {
            val x = gx * W / 16 + W / 32
            val y = gy * H / 12 + H / 24
            if (pixels.getInt((y * W + x) * 4) != first) return false
        }
        return true
    }

    /** A copy of the frame shown now (the held one while [held]). */
    fun snapshot(): Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { copyInto(it) }

    internal fun copyInto(bitmap: Bitmap) = synchronized(lock) {
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
    }

    internal fun attach(v: NdsTouchScreenView) = views.add(v)
    internal fun detach(v: NdsTouchScreenView) = views.remove(v)

    /** The bottom display's content for a DS game: its touch screen, on black. */
    fun content(context: Context): View = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(NdsTouchScreenView(context), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
}

/**
 * The bottom display for a DS game on two screens: the DS touch screen, or the companion in its place (the player's
 * pick, Prefs.dsBottomCompanion). One button switches them, in the same place for both: centred in the bar the touch
 * screen's 4:3 leaves under it (COMPANION, or GAME SCREEN back), the companion taking the touch screen's area above it.
 * BACK with nothing left to go back from also returns to the touch screen (PokeDaisyActivity.companionBackTap).
 * DS TOUCH SCREEN > FILL ([fill], Prefs.dsTouchFill): the touch screen stretched over the whole display, the companion
 * too, and the switch a small see-through tab on the right edge halfway down - DS touch screens keep their buttons in
 * the corners and along the bottom, and a tab running off the edge is the side panel's way of opening the companion.
 */
@SuppressLint("ViewConstructor")
class NdsBottomHost(
    context: Context,
    companion: () -> View,
    showCompanion: Boolean,
    /** The player switched: true = the companion now. */
    private val onSwitch: (Boolean) -> Unit,
    fill: Boolean = false,
) : FrameLayout(context) {
    private val touchView = NdsTouchScreenView(context)
    private val touch = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(touchView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    private val companionView = companion()
    private var label by mutableStateOf(showCompanion)
    private var tabbed by mutableStateOf(fill)
    private val button = CompanionColors.track(ComposeView(context)).apply {
        setContent {
            if (tabbed) {
                // See-through, so the touch screen's own picture under it still shows.
                Box(Modifier.alpha(TAB_ALPHA)) { com.pokedaisy.app.companion.ui.DsScreenSwitchTab(label) { show(!showingCompanion) } }
            } else {
                val m = rememberGbaTextMetrics()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OptionButton(if (label) tr("GAME SCREEN") else tr("COMPANION"), m, onClick = { show(!showingCompanion) })
                }
            }
        }
    }
    var showingCompanion = showCompanion
        private set

    /** DS TOUCH SCREEN > FILL: the touch screen and the companion over the whole display, the switch a tab. */
    var fill = fill
        set(v) {
            if (field == v) return
            field = v
            tabbed = v
            touchView.stretch = v
            relayout(width, height)
        }

    init {
        setBackgroundColor(Color.BLACK)
        touchView.stretch = fill
        addView(touch, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(companionView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.TOP))
        addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        apply(showCompanion)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout(w, h)
    }

    private fun relayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        post {
            if (fill) {
                companionView.layoutParams = (companionView.layoutParams as LayoutParams).apply { height = LayoutParams.MATCH_PARENT }
                button.layoutParams = (button.layoutParams as LayoutParams).apply {
                    width = LayoutParams.WRAP_CONTENT
                    height = LayoutParams.WRAP_CONTENT
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
            } else {
                // The touch screen's height at its own 4:3 (NdsTouchScreenView), the button's bar the rest; a display with
                // no room under it keeps a bar of a button's height, the companion above it.
                val screenH = minOf(h, (minOf(w.toFloat() / NdsBottomFrame.W, h.toFloat() / NdsBottomFrame.H) * NdsBottomFrame.H).toInt())
                val minBar = (resources.displayMetrics.density * 56).toInt()
                val bar = maxOf(h - screenH, minBar)
                companionView.layoutParams = (companionView.layoutParams as LayoutParams).apply { height = h - bar }
                button.layoutParams = (button.layoutParams as LayoutParams).apply {
                    width = LayoutParams.MATCH_PARENT
                    height = bar
                    gravity = Gravity.BOTTOM
                }
            }
            requestLayout()
        }
    }

    private companion object {
        /** The tab over the touch screen: there, but not in the way. */
        const val TAB_ALPHA = 0.7f
    }

    /** Shows the companion ([on]) or the touch screen, and tells [onSwitch] when that's a change. */
    fun show(on: Boolean) {
        if (on == showingCompanion) return
        apply(on)
        onSwitch(on)
    }

    private fun apply(on: Boolean) {
        showingCompanion = on
        label = on
        companionView.visibility = if (on) VISIBLE else GONE
        touch.visibility = if (on) GONE else VISIBLE
        // No stylus left down on a screen that's gone.
        if (on) MgbaCore.pkNdsTouch(false, 0, 0)
    }
}

/** The DS's bottom screen, as large as fits at its own 4:3, top-centred ([stretch]: over the whole view); touches on it
 * are the stylus. */
class NdsTouchScreenView(context: Context) : View(context) {
    private val bitmap = Bitmap.createBitmap(NdsBottomFrame.W, NdsBottomFrame.H, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val dst = Rect()

    /** DS TOUCH SCREEN > FILL: the picture stretched over the view (taps follow it). */
    var stretch = false
        set(v) {
            if (field == v) return
            field = v
            place(width, height)
            invalidate()
        }

    init { CompanionColors.trackGame(this) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NdsBottomFrame.attach(this)
    }

    override fun onDetachedFromWindow() {
        NdsBottomFrame.detach(this)
        MgbaCore.pkNdsTouch(false, 0, 0)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place(w, h)

    private fun place(w: Int, h: Int) {
        if (stretch) { dst.set(0, 0, w, h); return }
        val scale = minOf(w.toFloat() / NdsBottomFrame.W, h.toFloat() / NdsBottomFrame.H)
        val dw = (NdsBottomFrame.W * scale).toInt()
        val dh = (NdsBottomFrame.H * scale).toInt()
        val left = (w - dw) / 2
        dst.set(left, 0, left + dw, dh)
    }

    override fun onDraw(canvas: Canvas) {
        NdsBottomFrame.copyInto(bitmap)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dst.isEmpty) return false
        // A held picture (DS LAYOUT's transition hold): the game behind it isn't what's shown.
        if (NdsBottomFrame.held) {
            MgbaCore.pkNdsTouch(false, 0, 0)
            return true
        }
        val x = ((event.x - dst.left) * NdsBottomFrame.W / dst.width()).toInt()
        val y = ((event.y - dst.top) * NdsBottomFrame.H / dst.height()).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val inside = x in 0 until NdsBottomFrame.W && y in 0 until NdsBottomFrame.H
                MgbaCore.pkNdsTouch(inside, x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> MgbaCore.pkNdsTouch(false, 0, 0)
        }
        return true
    }
}

/**
 * A DS game's touch screen on a one-screen device held upright (GitHub #244): the dock under the game, in the companion's
 * place (held sideways DS LAYOUT places it: PokeDaisyActivity.syncDsStage). The same switch as [NdsBottomHost]'s: the
 * touch screen at its 4:3, as large as fits, or the [companion] (Prefs.dsBottomCompanion), with COMPANION / GAME SCREEN
 * under it.
 */
@Composable
fun DsTouchPane(showCompanion: Boolean, onSwitch: (Boolean) -> Unit, companion: @Composable () -> Unit) {
    var on by remember { mutableStateOf(showCompanion) }
    val m = rememberGbaTextMetrics()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().then(if (on) Modifier else Modifier.background(ComposeColor.Black))) {
        // The switch's bar; the touch screen at full width, centred on the pane's middle (level with the game beside it)
        // as far as the bar still fits under it - at the top in the dock under an upright game. Held sideways the bar
        // goes above it: the touch controls run along the bottom there (SELECT / START), over where it was.
        val sideways = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val bar = androidx.compose.ui.unit.max(56.dp, m.lineHeight + m.u * 12)
        val screenH = minOf(maxWidth * NdsBottomFrame.H / NdsBottomFrame.W, maxHeight - bar)
        val top = if (sideways) maxOf((maxHeight - screenH) / 2, bar)
            else minOf((maxHeight - screenH) / 2, maxHeight - bar - screenH).coerceAtLeast(0.dp)
        val switch = @Composable {
            Box(Modifier.fillMaxWidth().height(bar), contentAlignment = Alignment.Center) {
                OptionButton(if (on) tr("GAME SCREEN") else tr("COMPANION"), m, onClick = {
                    on = !on
                    // No stylus left down on a screen that's gone.
                    if (on) MgbaCore.pkNdsTouch(false, 0, 0)
                    onSwitch(on)
                })
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (on) {
                Box(Modifier.weight(1f).fillMaxWidth()) { companion() }
                switch()
            } else if (sideways) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(top - bar))
                switch()
                Box(Modifier.fillMaxWidth().height(screenH)) {
                    AndroidView({ NdsTouchScreenView(it) }, Modifier.fillMaxSize())
                }
            } else {
                if (top > 0.dp) androidx.compose.foundation.layout.Spacer(Modifier.height(top))
                Box(Modifier.fillMaxWidth().height(screenH)) {
                    AndroidView({ NdsTouchScreenView(it) }, Modifier.fillMaxSize())
                }
                switch()
            }
        }
    }
}
