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
import java.io.File
import kotlin.math.abs

/**
 * A 3DS game, first version (docs/3DS_GAMES_PLAN.md, milestone 1): Azahar on this activity's surface and, with a second
 * display, the touch screen alone on a [Presentation] there (the Thor's bottom screen); one display shows both screens.
 * Buttons and the left stick from a controller, touches on either surface. Not the game stage the GBA / DS games use yet:
 * no companion, HUD or settings; test keys: L3 saves a state, R3 loads it, R2 held runs fast.
 */
class N3dsActivity : Activity() {
    companion object {
        const val EXTRA_ROM = "rom"
        private const val TAG = "pokedaisy/n3ds"
        private const val FF_PERCENT = 300
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rom = File(intent.getStringExtra(EXTRA_ROM) ?: run { finish(); return })
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        main = SurfaceView(this)
        frame.addView(main, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
        when (val ready = N3dsSaves.prepare(this, header.titleId)) {
            is N3dsSaves.Ready.Refused -> return showNotice(ready.reason)
            N3dsSaves.Ready.Ok -> Unit
        }

        main.holder.addCallback(surfaceCallback(0) { mainReady = it })
        main.setOnTouchListener { _, e -> touch(0, e); true }
        secondDisplay()?.let { showOnSecond(it) }
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
                setContentView(view)
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
            val result = N3dsNative.run(path)
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
            KeyEvent.KEYCODE_BACK -> return super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> { if (down && event.repeatCount == 0) state(save = true); return true }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> { if (down && event.repeatCount == 0) state(save = false); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { fastForward(down); return true }
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
        val zl = if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f) N3dsNative.Button.ZL else 0
        val dpad = N3dsNative.Button.LEFT or N3dsNative.Button.RIGHT or N3dsNative.Button.UP or N3dsNative.Button.DOWN
        buttons = (buttons and (dpad or N3dsNative.Button.ZL).inv()) or hat or zl
        N3dsNative.setButtons(buttons)
        fastForward(event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f)
        return true
    }

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
