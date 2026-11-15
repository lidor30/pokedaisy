@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.companion.data.RewindPolicy
import kotlinx.cinterop.CValue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Metal.MTLClearColor
import platform.MetalKit.MTKView
import platform.MetalKit.MTKViewDelegateProtocol
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol

/** Up to 5 s of frames for a save the game is writing (pk_api_finish_save); top level, as a Kotlin subclass of an
 * Objective-C class can't have a companion with fields. */
private const val FINISH_SAVE_FRAMES = 300

/**
 * A running game for the iPad app: the game drawn with Metal ([GameRenderer]), heard ([GameAudio]) and played with
 * controllers, a keyboard or [touchKeys] ([GameInput]). The app shell embeds its view wherever the game goes (full
 * screen, or beside the companion) and puts its own controls over it; nothing else of the app is in here.
 *
 * The display's refreshes drive it, on the main thread, which is the game's (pk_api.h's one thread): each refresh runs
 * the frames [FramePacer] says are due, then draws the last. It opens [romPath] with its battery save at [savePath]
 * when its view loads and pauses while the app isn't active; [close] writes the save and closes it (call it before
 * letting go of the controller). [onFailed] hears it if the game won't open.
 */
class GameViewController(
    private val romPath: String,
    private val savePath: String?,
    private val onFailed: (() -> Unit)? = null,
) : UIViewController(nibName = null, bundle = null) {
    private val renderer = GameRenderer.create()
    private val queue = renderer?.device?.newCommandQueue()
    private val audio = GameAudio()
    private val input = GameInput()
    val keyCapture get() = input.capture
    fun reloadBindings() = input.reloadBindings()
    fun setHotkeys(model: com.pokedaisy.app.Hotkeys?) = input.setHotkeys(model)
    var onHotkey: (com.pokedaisy.app.Hotkeys.Event) -> Unit
        get() = input.onHotkey
        set(value) { input.onHotkey = value }
    private val pacer = FramePacer()
    private val observers = mutableListOf<NSObjectProtocol>()
    private var running = false
    private var active = false
    private var stateChanging = false
    private lateinit var gameView: MTKView

    /** The keys the app's on-screen controls hold ([PkApiCore.KEY_A] ...), main thread. */
    var touchKeys: Int
        get() = input.touchKeys
        set(value) {
            input.touchKeys = value
        }

    var touchTurbo: Int
        get() = input.touchTurbo
        set(value) { input.touchTurbo = value }

    var touchButtonKeys: Set<Int>
        get() = input.touchButtonKeys
        set(value) { input.touchButtonKeys = value }

    /** A modal editor is open: foreground notifications must not start frames underneath it. */
    var uiPaused: Boolean = false
        set(value) { field = value; input.editorCaptured(value) }

    /** A companion window owns manual input while emulation and scripted battle input continue. */
    var companionInputCaptured: Boolean
        get() = input.companionCaptured
        set(value) { input.companionCaptured = value }
    var onCompanionButtons: (Int) -> Unit
        get() = input.onCompanionButtons
        set(value) { input.onCompanionButtons = value }
    val controllerButtons: Int get() = input.controllerKeys()
    var keyboardInputCaptured: Boolean
        get() = input.keyboardCaptured
        set(value) { input.keyboardCaptured = value }

    /** Per-game pace, or zero for unlimited FF (bounded work per display refresh). Main thread. */
    var speed: Double = 1.0
    var volume: Float = 1f
    var muted: Boolean = false
    /** The same diff-ring capacity as Android; configuring it releases any current hold. */
    var rewindEnabled: Boolean = false
        set(value) {
            if (field == value) return
            holdRewind(false)
            field = value
            if (running) PkApiCore.setRewind(RewindPolicy.capacity(value))
        }
    private var rewinding = false
    /** Cancel queued battle commands on either transport edge. No state operation or touch release here. */
    var beforeRewind: () -> Unit = {}

    fun holdRewind(on: Boolean) {
        val next = on && rewindEnabled
        if (next == rewinding) return
        rewinding = next
        beforeRewind()
        if (!running) return
        audio.stop()
        PkApiCore.setRewinding(next)
        pacer.reset()
        audioSpeed = -1.0
        if (active && !stateChanging && !uiPaused && !gameView.paused) audio.start()
    }
    /** Shared battle automation runs once per emulated frame, on every input source. */
    var frameKeys: (Int) -> Int = { it }
    var frameSpeed: () -> Double = { speed }
    var beforeFrame: () -> Unit = {}
    var afterFrame: () -> Unit = {}
    var holdPicture: () -> Boolean = { false }
    var quietPress: () -> Boolean = { false }
    private var audioSpeed = -1.0

    /** Releases scripted input before a state changes the running game. */
    var beforeStateChange: () -> Unit = {}
    /** Initial open only; a normal foreground return keeps the paused core in memory. */
    var onCoreStarted: () -> Unit = {}
    /** Main thread, after pausing frames/audio but while state operations can still use the core. */
    var beforeSuspend: () -> Boolean = { true }

    /** Whether a game is open and running in this controller. */
    val isRunning: Boolean get() = running

    // Kept by the controller: MTKView holds its delegate weakly.
    private val drawer = object : NSObject(), MTKViewDelegateProtocol {
        override fun drawInMTKView(view: MTKView) = refresh(view)

        override fun mtkView(view: MTKView, drawableSizeWillChange: CValue<CGSize>) {}
    }

    override fun loadView() {
        val v = MTKView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), device = renderer?.device)
        v.preferredFramesPerSecond = 60
        v.clearColor = cValue<MTLClearColor> {
            red = GameRenderer.CLEAR_RED
            green = GameRenderer.CLEAR_GREEN
            blue = GameRenderer.CLEAR_BLUE
            alpha = 1.0
        }
        v.delegate = drawer
        gameView = v
        view = v
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        if (renderer == null) NSLog("PokeDaisy: no Metal device, the game won't show")
        if (!PkApiCore.start(romPath, savePath)) {
            NSLog("PokeDaisy: the game didn't open: %@", romPath)
            gameView.paused = true
            onFailed?.invoke()
            return
        }
        running = true
        PkApiCore.setRewind(RewindPolicy.capacity(rewindEnabled))
        onCoreStarted()
        active = true
        audio.start()
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
            _ -> pause()
        }
        observers += center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            _ -> resume()
        }
    }

    /** Stops running (the app went to the background, or the shell asks); the game stays open. */
    fun pause(): Boolean {
        if (!running || !active) return true
        gameView.paused = true
        input.capture.cancel()
        input.backgroundCaptured(true)
        active = false
        audio.stop()
        return beforeSuspend()
    }

    /** Runs again after [pause]. */
    fun resume() {
        if (!running || uiPaused) return
        input.backgroundCaptured(false)
        active = true
        pacer.reset()
        audio.start()
        gameView.paused = false
    }

    /** Main/game thread, between display callbacks: no frames or queued sound cross a state operation. */
    fun withState(operation: () -> Boolean): Boolean {
        if (!running) return false
        stateChanging = true
        audio.stop()
        return try {
            beforeStateChange()
            input.touchKeys = 0
            input.touchTurbo = 0
            PkApiCore.setKeys(0)
            if (PkApiCore.finishSave(600) >= 600) false else operation()
        } finally {
            stateChanging = false
            pacer.reset()
            audioSpeed = -1.0
            if (active && running) audio.start()
        }
    }

    /** Writes the game's save and closes it; the controller then shows nothing. */
    fun close() {
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
        if (running) {
            pause()
            running = false
            audio.release()
            PkApiCore.finishSave(FINISH_SAVE_FRAMES)
            PkApiCore.stop()
        }
        onHotkey = {}
        beforeRewind = {}
        beforeFrame = {}
        afterFrame = {}
    }

    private fun refresh(view: MTKView) {
        if (!running || !active || uiPaused) return
        // Poll once per display refresh: capture stays responsive at low speeds, and FF batches avoid repeated
        // GameController queries. A controller action can close/restart the game or open the editor here.
        input.poll()
        if (!running || !active || uiPaused) return
        val rate = effectiveSpeed()
        val now = CACurrentMediaTime()
        if (rate != audioSpeed) {
            pacer.reset()
            // Fewer samples per emulated frame give pitched, sped-up sound at the device's fixed 48 kHz.
            PkApiCore.setAudioRate(PkApiCore.SAMPLE_RATE / rate.coerceAtLeast(0.25))
            audioSpeed = rate
        }
        val frames = if (rate == 0.0) 64 else pacer.framesAt(now, rate)
        var ran = false
        for (frame in 0 until frames) {
            // Unlimited FF still leaves time for the companion and touch events on the main thread.
            if (rate == 0.0 && ran && CACurrentMediaTime() - now >= 0.008) break
            if (!rewinding) beforeFrame()
            if (!running || !active || uiPaused) break
            val held = input.keys()
            if (!running || !active || uiPaused) break
            val keys = if (rewinding) 0 else frameKeys(held)
            if (!running || !active || uiPaused) break
            PkApiCore.setKeys(keys)
            PkApiCore.runFrame()
            afterFrame()
            audio.volume = if (rewinding || muted || rate == 0.0 || quietPress()) 0f else volume
            audio.push()
            ran = true
            // A menu may open on the first uncapped frame. Do not spend the remaining fast batch inside it.
            if (effectiveSpeed() != rate) break
        }
        if (!running || !active || uiPaused) return
        if (ran && (rewinding || !holdPicture())) renderer?.upload()
        draw(view)
    }

    private fun effectiveSpeed(): Double = if (rewinding) 1.0 else
        frameSpeed().takeIf { it.isFinite() && it in 0.0..64.0 } ?: 1.0

    private fun draw(view: MTKView) {
        val renderer = renderer ?: return
        val pass = view.currentRenderPassDescriptor ?: return
        val drawable = view.currentDrawable ?: return
        val buffer = queue?.commandBuffer() ?: return
        val encoder = buffer.renderCommandEncoderWithDescriptor(pass) ?: return
        view.drawableSize.useContents { renderer.encode(encoder, width, height) }
        encoder.endEncoding()
        buffer.presentDrawable(drawable)
        buffer.commit()
    }
}
