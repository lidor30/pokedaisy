package com.pokedaisy.app

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.util.DisplayMetrics
import android.view.WindowManager
import com.pokedaisy.app.companion.ui.TouchGeometry
import com.pokedaisy.app.companion.ui.TouchHitModel
import com.pokedaisy.app.companion.ui.TouchHits
import com.pokedaisy.app.companion.ui.TouchPressTracker
import com.pokedaisy.app.companion.ui.touchHits
import kotlin.math.roundToInt

/**
 * The touch controls' fingers, read off the UI thread. Android hands a touch to the thread of the window it lands in,
 * and the UI thread also draws the companion: a tab switch held it 150-850 ms on a weak tablet (Galaxy Tab A, 2026-11-08),
 * and every press on the pad waited behind it while the game ran on. So the buttons' touch areas are small windows of
 * their own, see-through and never drawn, added from [thread]: their fingers go through the same hit test as the drawn
 * pad's ([touchHits]) straight into [tracker] - the game's buttons at once, the drawn pad's pressed look whenever the UI
 * thread gets to it.
 *
 * One window per cluster of buttons ([clusters]: A and B together, so a thumb between them presses both, as on the
 * pad), each no bigger than what a touch there would press: what's around them stays the HUD's or the game's. The
 * windows sit over everything in the activity's window, so they let every touch through ([on] false) while anything
 * is open over the game ([TouchControlsView.inputAllowed]: a HUD panel, the QUICK MENU, the companion window...); the
 * drawn pad then takes the fingers itself, as it always did. Anything that goes wrong here (a window refused) leaves it
 * that way.
 *
 * Never on Samsung's Android ([supported]): after a configuration change (a rotation, dark mode, a controller
 * connecting) it updates every window of the activity from the UI thread, its sub-windows too
 * (ActivityThread.handleActivityConfigurationChanged -> WindowManagerGlobal.getRootViews(token) -> updateConfiguration,
 * read from an S9's framework.jar; stock Android updates the activity's own window alone), and a window only takes calls
 * on the thread that added it: 1.5.0 crashed on rotations on Galaxies (a player's report and Crashlytics, 2026-11-10: S26
 * on Android 17, S9 on Android 10; turning an S9 a few times in a game does it). Resources that nothing updates
 * ([FrozenContext]) make that call do nothing, which turned the crash from every rotation into about one in fifty; the
 * rest is the window's own update forcing one through on its thread at the same moment, which nothing here can rule out
 * (Samsung also runs that loop where the activity hears of no change, and Android refuses a sub-window of a sub-window,
 * which would have kept these out of it). So on Samsung the drawn pad takes the fingers, as before 1.5.0. Elsewhere the
 * frozen resources stay, in case another maker's Android does the same without the race.
 */
class TouchInputWindows(private val anchor: View, private val tracker: TouchPressTracker) {
    private val thread = HandlerThread("pokedaisy-touch", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val wm = anchor.context.getSystemService(WindowManager::class.java)

    /** The windows' views' context: the activity's with resources of its own, frozen as they were when last set, so an
     * update of the activity's configuration never reads as a change to these windows (see the class doc). */
    @Volatile private var padContext: Context = FrozenContext.of(anchor.context)

    // UI thread: what [sync] last handed over.
    private var sentToken: IBinder? = null
    private var sentRects: List<Rect> = emptyList()
    private var sentX = 0
    private var sentY = 0
    private var sentOn = false
    private var sentModel: TouchHitModel? = null
    private var released = false

    // [thread] only from here down.
    private val pads = ArrayList<Pad>()
    private var token: IBinder? = null
    private var model: TouchHitModel? = null
    private var on = false
    private val xs = FloatArray(MAX_FINGERS)
    private val ys = FloatArray(MAX_FINGERS)

    /** One window: its place in the controls' own pixels ([rect]) and the fingers on it now (the controls' pixels). */
    private inner class Pad(val rect: Rect) : View(padContext) {
        val fx = FloatArray(MAX_FINGERS)
        val fy = FloatArray(MAX_FINGERS)
        var n = 0

        init { setWillNotDraw(true) }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            n = 0
            val action = e.actionMasked
            // Debug builds: how long a press waited before it reached us (the UI thread's waits shouldn't show here).
            if (BuildConfig.DEBUG && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)) {
                Log.d("pokedaisy/touch", "press after ${android.os.SystemClock.uptimeMillis() - e.eventTime} ms")
            }
            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
                val lifted = if (action == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
                for (i in 0 until e.pointerCount) {
                    if (i == lifted || n == MAX_FINGERS) continue
                    fx[n] = e.getX(i) + rect.left
                    fy[n] = e.getY(i) + rect.top
                    n++
                }
            }
            fingersChanged()
            return true
        }
    }

    /** UI thread: the windows for [model] (null: none), letting touches through unless [allowed]. Cheap when nothing changed. */
    fun sync(model: TouchHitModel?, allowed: Boolean) {
        if (released) return
        val token = if (anchor.isAttachedToWindow) anchor.windowToken else null
        val loc = IntArray(2)
        if (token != null) anchor.getLocationInWindow(loc)
        val rects = if (token != null && model != null) clusters(model, anchor.width, anchor.height) else emptyList()
        val on = allowed && token != null && rects.isNotEmpty() && anchor.isShown
        if (token == sentToken && rects == sentRects && loc[0] == sentX && loc[1] == sentY && on == sentOn && model === sentModel) return
        sentToken = token; sentRects = rects; sentX = loc[0]; sentY = loc[1]; sentOn = on; sentModel = model
        val x = loc[0]
        val y = loc[1]
        handler.post { apply(token, model, rects, x, y, on) }
    }

    /** UI thread: every window gone, the thread ended (the activity's going). */
    fun release() {
        if (released) return
        released = true
        handler.post {
            removeAll()
            thread.quitSafely()
        }
    }

    /** UI thread, from the activity's onConfigurationChanged: windows added from now on start from the new
     * configuration, frozen in turn; the ones up keep theirs (see the class doc). */
    fun configurationChanged() {
        if (!released) padContext = FrozenContext.of(anchor.context)
    }

    /** A context whose resources are its own and never updated: a copy of [base]'s, configuration and all. */
    private class FrozenContext(base: Context) : ContextWrapper(base) {
        @Suppress("DEPRECATION") // The one way to resources that ResourcesManager doesn't keep up to date.
        private val frozen = Resources(
            base.resources.assets,
            DisplayMetrics().apply { setTo(base.resources.displayMetrics) },
            Configuration(base.resources.configuration),
        )

        override fun getResources(): Resources = frozen

        companion object {
            fun of(c: Context): Context = runCatching { FrozenContext(c) }.getOrDefault(c)
        }
    }

    private fun apply(token: IBinder?, model: TouchHitModel?, rects: List<Rect>, x: Int, y: Int, on: Boolean) {
        this.model = model
        if (token != this.token || rects != pads.map { it.rect }) {
            removeAll()
            this.token = token
            if (token != null) for (r in rects) add(Pad(Rect(r)), token, x, y, on)
        } else if (token != null) {
            for (pad in pads) runCatching { wm.updateViewLayout(pad, params(pad.rect, token, x, y, on)) }
        }
        if (this.on != on) {
            this.on = on
            // Letting go of the game's buttons when the drawn pad takes over (a finger still down was ours).
            if (!on) { pads.forEach { it.n = 0 }; tracker.update(TouchPressTracker.SOURCE_WINDOWS, TouchHits.NONE) }
        }
    }

    private fun add(pad: Pad, token: IBinder, x: Int, y: Int, on: Boolean) {
        runCatching { wm.addView(pad, params(pad.rect, token, x, y, on)) }
            .onSuccess { pads += pad }
            .onFailure { Log.w("pokedaisy", "touch input window refused: the drawn pad keeps the fingers", it) }
    }

    private fun removeAll() {
        for (pad in pads) runCatching { wm.removeViewImmediate(pad) }
        pads.clear()
        tracker.update(TouchPressTracker.SOURCE_WINDOWS, TouchHits.NONE)
    }

    private fun params(r: Rect, token: IBinder, x: Int, y: Int, on: Boolean) = WindowManager.LayoutParams(
        r.width(), r.height(), WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
            (if (on) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        this.x = x + r.left
        this.y = y + r.top
        this.token = token
        title = "PokeDaisy touch"
        windowAnimations = 0
        if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /** A pad's fingers changed: every window's fingers through the hit test, into [tracker]. */
    private fun fingersChanged() {
        val m = model ?: return
        if (!on) return
        var n = 0
        for (pad in pads) for (i in 0 until pad.n) if (n < MAX_FINGERS) { xs[n] = pad.fx[i]; ys[n] = pad.fy[i]; n++ }
        tracker.update(TouchPressTracker.SOURCE_WINDOWS, touchHits(m, xs, ys, n))
    }

    companion object {
        private const val MAX_FINGERS = 10

        /** Whether this device can have them: not on Samsung's Android (see the class doc). */
        val supported: Boolean = !Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        /** The touch areas of [model]'s buttons in a [w]x[h] area: each button's reach (what [touchHits] would press
         * there), those within [MERGE_DP] of each other in one, clipped to the area. */
        fun clusters(model: TouchHitModel, w: Int, h: Int): List<Rect> {
            val p = model.placed
            val slop = 10 * p.dp
            val reach = ArrayList<Rect>()
            val fit = model.fit
            if (fit != null) {
                for (it in fit.items) reach += Rect(it.touch.left.toInt(), it.touch.top.toInt(), it.touch.right.roundToInt(), it.touch.bottom.roundToInt())
            } else for (item in p.items) {
                val (rx, ry) = when (item.shape) {
                    TouchGeometry.Shape.CROSS -> item.w / 2 * 1.4f to item.w / 2 * 1.4f
                    TouchGeometry.Shape.ROUND -> (item.w / 2 * 1.25f + slop * 0.25f).let { it to it }
                    TouchGeometry.Shape.PILL -> item.w / 2 + slop to item.h / 2 + slop
                }
                reach += Rect((item.cx - rx).toInt(), (item.cy - ry).toInt(), (item.cx + rx).roundToInt(), (item.cy + ry).roundToInt())
            }
            val gap = (MERGE_DP * p.dp).roundToInt()
            var merged = reach.toMutableList()
            var again = true
            while (again) {
                again = false
                val out = ArrayList<Rect>()
                for (r in merged) {
                    val near = out.firstOrNull { o -> Rect.intersects(Rect(o.left - gap, o.top - gap, o.right + gap, o.bottom + gap), r) }
                    if (near != null) { near.union(r); again = true } else out += Rect(r)
                }
                merged = out
            }
            return merged.mapNotNull { r -> Rect(r).takeIf { it.intersect(0, 0, w, h) && it.width() > 0 && it.height() > 0 } }
                .sortedWith(compareBy({ it.top }, { it.left }))
        }

        private const val MERGE_DP = 8f
    }
}
