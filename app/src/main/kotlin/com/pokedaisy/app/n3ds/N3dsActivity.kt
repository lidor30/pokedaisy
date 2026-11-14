package com.pokedaisy.app.n3ds

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.io.File
import kotlin.math.abs

/**
 * A 3DS game, first version (docs/3DS_GAMES_PLAN.md, milestone 1): Azahar on this activity's surface and, with a second
 * display, the touch screen alone on a [Presentation] there (the Thor's bottom screen). One display follows DS LAYOUT:
 * ONE SCREEN (and PICTURE IN PICTURE, for now) the top screen alone with the touch screen floating in on L2 / TOUCH, the
 * others Azahar's own layouts of both screens.
 * Buttons and the left stick from a controller, touches on either surface. Not the game stage the GBA / DS games use yet:
 * a first companion (PARTY / BAG, BACK swaps it in and out, a held BACK leaves), no HUD or settings; test keys: L3 saves a
 * state, R3 loads it, R2 held runs fast.
 */
class N3dsActivity : Activity() {
    companion object {
        const val EXTRA_ROM = "rom"
        private const val TAG = "pokedaisy/n3ds"
        private const val FF_PERCENT = 300
        private const val RESOLUTION = 3
    }

    private lateinit var rom: File
    private lateinit var main: SurfaceView
    private lateinit var notice: TextView
    private var presentation: Presentation? = null
    private var mainReady = false
    private var secondReady = false
    private var wantsSecond = false
    private var gameThread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())
    private var buttons = 0
    private var ffHeld = false
    private lateinit var layout: com.pokedaisy.app.companion.DsLayout
    // ONE SCREEN (and PICTURE IN PICTURE, for now): the touch screen as a second surface in a window over the game.
    private var floatFrame: FrameLayout? = null
    private var dim: View? = null
    private var touchButton: TextView? = null
    private var floating = false

    // The companion (PARTY / BAG, N3dsCompanion): over the touch screen on a second display, else a window over the game.
    private var titleId = 0L
    private var companionView: androidx.compose.ui.platform.ComposeView? = null
    private var companionShown = false
    private val party = androidx.compose.runtime.mutableStateOf(emptyList<com.pokedaisy.app.companion.data.MonView>())
    private val bag = androidx.compose.runtime.mutableStateOf(emptyList<com.pokedaisy.app.companion.data.ItemView>())
    private val itemNames = androidx.compose.runtime.mutableStateOf(emptyList<String>())
    @Volatile private var sampling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rom = File(intent.getStringExtra(EXTRA_ROM) ?: run { finish(); return })
        layout = com.pokedaisy.app.Prefs(this).dsLayout
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        main = SurfaceView(this)
        frame.addView(main, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root = frame
        notice = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        frame.addView(notice, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        hideBars()

        if (!N3dsNative.available) return showNotice("3DS games aren't in this build.")
        val header = N3dsCart.header(rom) ?: return showNotice("This isn't a 3DS cart image.")
        if (!header.decrypted) return showNotice("This cart is encrypted. PokeDaisy plays decrypted 3DS carts only.")
        titleId = header.titleId
        when (val ready = N3dsSaves.prepare(this, header.titleId)) {
            is N3dsSaves.Ready.Refused -> return showNotice(ready.reason)
            N3dsSaves.Ready.Ok -> Unit
        }

        main.holder.addCallback(surfaceCallback(0) { mainReady = it })
        main.setOnTouchListener { _, e -> touch(0, e); true }
        val second = secondDisplay()
        if (second != null) showOnSecond(second) else if (layout.floats) floatingTouchWindow()
        if (second == null) companionWindow()
    }

    private fun companionCompose(context: Context) = androidx.compose.ui.platform.ComposeView(context).apply {
        setContent {
            com.pokedaisy.app.companion.ui.N3dsCompanion(party.value, bag.value, { id -> itemNames.value.getOrNull(id) })
        }
        visibility = View.GONE
    }

    private fun hostCompose(root: View) {
        val owner = com.pokedaisy.app.ComposeHostOwner().apply { create(); resume() }
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
    }

    /** One display: the companion as a window over the game, the Thor's bottom screen's shape at 88% of the height. */
    private fun companionWindow() {
        hostCompose(root)
        val view = companionCompose(this)
        root.addView(view, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val h = ((b - t) * 0.88f).toInt()
            val w = minOf(h * 1240 / 1080, ((r - l) * 0.95f).toInt())
            (view.layoutParams as FrameLayout.LayoutParams).let { lp ->
                if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; view.layoutParams = lp }
            }
        }
        companionView = view
    }

    /** BACK: the companion in and out (a hold leaves the game, see onKeyLongPress). */
    private fun toggleCompanion() {
        val view = companionView ?: return
        companionShown = !companionShown
        view.visibility = if (companionShown) View.VISIBLE else View.GONE
        if (companionShown) startSampling() else sampling = false
    }

    // The game's party and bag once a second while the companion shows, read between two frames off the UI thread.
    private fun startSampling() {
        if (sampling || gameThread == null) return
        val config = Gen6Reader.configFor(titleId) ?: return
        sampling = true
        Thread({
            var text: Gen6Text? = null
            while (sampling && !isFinishing) {
                val bytes = N3dsNative.readMemory(Gen6Reader.ranges(config))
                val sample = bytes?.let { Gen6Reader.read(config, it) }
                if (sample != null) {
                    if (text == null) text = Gen6Text.load(rom, sample.language)?.also { t -> val names = t.items; ui.post { itemNames.value = names } }
                    val p = N3dsCompanionData.party(sample, text)
                    val b = N3dsCompanionData.bag(sample, text)
                    ui.post { party.value = p; bag.value = b }
                }
                Thread.sleep(1000)
            }
        }, "pokedaisy-n3ds-sample").start()
    }

    private lateinit var root: FrameLayout

    /**
     * ONE SCREEN's touch screen: a window of its own over a dim of the game, 4:3 at 82% of the height. Hidden, it's moved
     * off the screen, not removed: Azahar draws into its surface every frame, and a surface that goes away under it
     * would have to be rebuilt. L2, the TOUCH button or a tap on the dim open / close it.
     */
    private fun floatingTouchWindow() {
        wantsSecond = true
        val dimView = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            setOnClickListener { setFloating(false) }
        }
        root.addView(dimView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val view = SurfaceView(this).apply { setZOrderMediaOverlay(true) }
        view.holder.addCallback(surfaceCallback(1) { secondReady = it })
        view.setOnTouchListener { _, e -> touch(1, e); true }
        val box = FrameLayout(this).apply { addView(view) }
        root.addView(box, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        val button = TextView(this).apply {
            text = "TOUCH"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(0x88000000.toInt())
            setPadding(28, 14, 28, 14)
            setOnClickListener { setFloating(!floating) }
        }
        root.addView(button, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 24, 24) })
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val h = ((b - t) * 0.82f).toInt()
            val w = h * 4 / 3
            (box.layoutParams as FrameLayout.LayoutParams).let { lp ->
                if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; box.layoutParams = lp }
            }
            box.translationX = if (floating) 0f else (r - l).toFloat() * 2
        }
        floatFrame = box
        dim = dimView
        touchButton = button
        box.translationX = 100_000f
    }

    private fun setFloating(on: Boolean) {
        val box = floatFrame ?: return
        floating = on
        dim?.visibility = if (on) View.VISIBLE else View.GONE
        box.translationX = if (on) 0f else root.width.toFloat() * 2
        touchButton?.text = if (on) "CLOSE" else "TOUCH"
        // A finger down on the touch screen when it goes away would stay down in the game.
        if (!on) N3dsNative.touch(1, 0, 0, N3dsNative.TOUCH_UP)
    }

    /** Azahar's layout of both screens on the one surface, for a layout that doesn't float the touch screen. */
    private fun azaharLayout(): Int = when (layout) {
        com.pokedaisy.app.companion.DsLayout.SIDE_BY_SIDE -> 3   // Settings::LayoutOption::SideScreen
        com.pokedaisy.app.companion.DsLayout.STACKED -> 0        // Default: the touch screen under the game
        else -> 2                                                // LargeScreen
    }

    private fun secondDisplay(): Display? =
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != display?.displayId }

    private fun showOnSecond(display: Display) {
        wantsSecond = true
        val p = object : Presentation(this, display) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                val view = SurfaceView(context)
                view.holder.addCallback(surfaceCallback(1) { secondReady = it })
                view.setOnTouchListener { _, e -> touch(1, e); true }
                val box = FrameLayout(context)
                box.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                val companion = companionCompose(context)
                box.addView(companion, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                hostCompose(box)
                companionView = companion
                setContentView(box)
                window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
        }
        runCatching { p.show() }.onFailure { wantsSecond = false; Log.w(TAG, "no second screen", it) }
        presentation = p
    }

    private fun surfaceCallback(which: Int, ready: (Boolean) -> Unit) = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            N3dsNative.setSurface(which, holder.surface)
            ready(true)
            maybeStart()
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            ready(false)
            N3dsNative.setSurface(which, null)
        }
    }

    // The game boots once its surfaces are there: both with a second screen, else the main one.
    private fun maybeStart() {
        if (gameThread != null || !mainReady || (wantsSecond && !secondReady)) return
        val path = rom.absolutePath
        val user = N3dsSaves.root(this).absolutePath
        gameThread = Thread({
            N3dsNative.init(user)
            // 3x for now (the RP6 / Thor draw the top screen at 4x): sharp, and cheap on a Snapdragon 8 Gen 2. A setting
            // later, like DS RESOLUTION.
            N3dsNative.setResolution(RESOLUTION)
            val result = N3dsNative.run(path, azaharLayout())
            Log.i(TAG, "game ended: $result")
            ui.post {
                if (result != 0 && !isFinishing) showNotice("The game stopped (Azahar status $result).") else finish()
            }
        }, "pokedaisy-n3ds").also { it.start() }
    }

    override fun onResume() {
        super.onResume()
        if (N3dsNative.available) N3dsNative.setPaused(false)
    }

    override fun onPause() {
        if (N3dsNative.available) N3dsNative.setPaused(true)
        super.onPause()
    }

    override fun onDestroy() {
        sampling = false
        if (N3dsNative.available && gameThread != null) {
            N3dsNative.stop()
            runCatching { gameThread?.join(5000) }
        }
        presentation?.dismiss()
        super.onDestroy()
    }

    // ---- input -----------------------------------------------------------------------------------------------

    private fun buttonFor(keyCode: Int): Int = when (keyCode) {
        // By Android's names, as the GBA games' defaults (GbaControls): BUTTON_A is A whatever the device prints.
        KeyEvent.KEYCODE_BUTTON_A -> N3dsNative.Button.A
        KeyEvent.KEYCODE_BUTTON_B -> N3dsNative.Button.B
        KeyEvent.KEYCODE_BUTTON_X -> N3dsNative.Button.X
        KeyEvent.KEYCODE_BUTTON_Y -> N3dsNative.Button.Y
        KeyEvent.KEYCODE_DPAD_UP -> N3dsNative.Button.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> N3dsNative.Button.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> N3dsNative.Button.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> N3dsNative.Button.RIGHT
        KeyEvent.KEYCODE_BUTTON_L1 -> N3dsNative.Button.L
        KeyEvent.KEYCODE_BUTTON_R1 -> N3dsNative.Button.R
        KeyEvent.KEYCODE_BUTTON_START -> N3dsNative.Button.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> N3dsNative.Button.SELECT
        KeyEvent.KEYCODE_BUTTON_L2 -> N3dsNative.Button.ZL
        else -> 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!N3dsNative.available || gameThread == null) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // A tap swaps the companion in / out, a hold (half a second) leaves the game.
                if (down && event.repeatCount == 0) { backDownAt = event.eventTime; backLeft = false }
                else if (down && !backLeft && event.eventTime - backDownAt > 500) { backLeft = true; finish() }
                else if (!down && !backLeft) toggleCompanion()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> { if (down && event.repeatCount == 0) state(save = true); return true }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> { if (down && event.repeatCount == 0) state(save = false); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { fastForward(down); return true }
            // One screen: L2 brings the touch screen in and sends it away (the DS games' TOUCH SCREEN key).
            KeyEvent.KEYCODE_BUTTON_L2 -> if (floatFrame != null) {
                if (down && event.repeatCount == 0) setFloating(!floating)
                return true
            }
        }
        val bit = buttonFor(event.keyCode)
        if (bit == 0) return super.dispatchKeyEvent(event)
        buttons = if (down) buttons or bit else buttons and bit.inv()
        N3dsNative.setButtons(buttons)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!N3dsNative.available || gameThread == null ||
            event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return super.dispatchGenericMotionEvent(event)
        fun dead(v: Float) = if (abs(v) < 0.12f) 0f else v
        N3dsNative.setStick(dead(event.getAxisValue(MotionEvent.AXIS_X)), -dead(event.getAxisValue(MotionEvent.AXIS_Y)))
        // The hat as the D-pad, the triggers as ZL / FF (Retroid's send no keys).
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hat = (if (hx < -0.5f) N3dsNative.Button.LEFT else 0) or (if (hx > 0.5f) N3dsNative.Button.RIGHT else 0) or
            (if (hy < -0.5f) N3dsNative.Button.UP else 0) or (if (hy > 0.5f) N3dsNative.Button.DOWN else 0)
        val lTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f
        val zl = if (lTrigger && floatFrame == null) N3dsNative.Button.ZL else 0
        if (floatFrame != null && lTrigger != lTriggerHeld) {
            lTriggerHeld = lTrigger
            if (lTrigger) setFloating(!floating)
        }
        val dpad = N3dsNative.Button.LEFT or N3dsNative.Button.RIGHT or N3dsNative.Button.UP or N3dsNative.Button.DOWN
        buttons = (buttons and (dpad or N3dsNative.Button.ZL).inv()) or hat or zl
        N3dsNative.setButtons(buttons)
        fastForward(event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f)
        return true
    }

    private var lTriggerHeld = false
    private var backDownAt = 0L
    private var backLeft = false

    private fun fastForward(on: Boolean) {
        if (on == ffHeld) return
        ffHeld = on
        N3dsNative.setSpeed(if (on) FF_PERCENT else 100)
    }

    private fun touch(which: Int, e: MotionEvent) {
        if (gameThread == null) return
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> N3dsNative.TOUCH_DOWN
            MotionEvent.ACTION_MOVE -> N3dsNative.TOUCH_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> N3dsNative.TOUCH_UP
            else -> return
        }
        N3dsNative.touch(which, e.x.toInt(), e.y.toInt(), action)
    }

    // One state per cart, beside Azahar's folder (`states/<rom name>.state`), for testing.
    private fun state(save: Boolean) {
        val file = File(N3dsSaves.root(this), "states/${rom.nameWithoutExtension}.state")
        file.parentFile?.mkdirs()
        Thread {
            val ok = if (save) N3dsNative.saveState(file.absolutePath) else file.isFile && N3dsNative.loadState(file.absolutePath)
            ui.post { flash(if (save) (if (ok) "State saved" else "State not saved") else (if (ok) "State loaded" else "No state loaded")) }
        }.start()
    }

    // ---- notices ---------------------------------------------------------------------------------------------

    private fun showNotice(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
    }

    private fun flash(text: String) {
        showNotice(text)
        ui.removeCallbacksAndMessages("flash")
        ui.postAtTime({ notice.visibility = View.GONE }, "flash", android.os.SystemClock.uptimeMillis() + 1500)
    }

    private fun hideBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
