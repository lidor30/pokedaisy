@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.SteadyMusicClip
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
import kotlinx.coroutines.*

/** Up to 5 s of frames for a save the game is writing (pk_api_finish_save); top level, as a Kotlin subclass of an
 * Objective-C class can't have a companion with fields. */
private const val FINISH_SAVE_FRAMES = 300

/** Reports main-thread frame work and new pictures submitted to Metal while diagnostics are visible. */
interface GamePerformanceObserver {
    fun frame(workNanos: Long)
    fun refresh(submittedPicture: Boolean)
}

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
    var onGamePixel: ((Float) -> Unit)? = null
    private val effectsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var effectsJob: Job? = null
    var pictureEffects = PictureEffects()
        set(value) {
            if (field == value) return
            field = value
            effectsJob?.cancel()
            effectsJob = effectsScope.launch { renderer?.configureEffects(value) }
        }
    private val audio = GameAudio()
    private val input = GameInput()
    val keyCapture get() = input.capture
    fun reloadBindings() = input.reloadBindings()
    fun releaseManualInput() = input.releaseManualInput()
    fun setPress(bits: Int) = input.setPress(bits)
    fun setHotkeys(model: com.pokedaisy.app.Hotkeys?) = input.setHotkeys(model)
    var onHotkey: (com.pokedaisy.app.Hotkeys.Event) -> Unit
        get() = input.onHotkey
        set(value) { input.onHotkey = value }
    /** Shared camera policy; this adapter only supplies native drawable/input/frame signals. */
    val camera = com.pokedaisy.app.WideCamera()
    private var wideTables = 0L
    val wideSupported: Boolean get() = wideTables != 0L
    var onPictureChanged: () -> Unit = {}
    var expanded = false
        set(value) {
            if (field == value) return
            field = value
            camera.reset()
            if (running) PkApiCore.configureWide(if (value) wideTables else 0)
        }
    var stretch = false
        set(value) { field = value; renderer?.stretch = value }
    private var pictureWindow: com.pokedaisy.app.WideCamera.Window? = null

    /** Only the shared ROM verifier's table CRC may enable EXPAND. Main/game thread after core start. */
    fun setExpandedTables(crc: Long) {
        wideTables = crc.takeIf(PkApiCore::wideKnows) ?: 0
        camera.frameW = PkApiCore.videoWidth
        camera.frameH = PkApiCore.videoHeight
        camera.reset()
        PkApiCore.configureWide(if (expanded) wideTables else 0)
        onPictureChanged()
    }

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
        set(value) {
            if (value && !field) revokeRewind()
            field = value
            input.editorCaptured(value)
        }

    /** A companion window owns manual input while emulation and scripted battle input continue. */
    var companionInputCaptured: Boolean
        get() = input.companionCaptured
        set(value) {
            if (value && !input.companionCaptured) revokeRewind()
            input.companionCaptured = value
        }
    var onCompanionButtons: (Int) -> Unit
        get() = input.onCompanionButtons
        set(value) { input.onCompanionButtons = value }
    val controllerButtons: Int get() = input.controllerKeys()
    var overlayMenuInputCaptured: Boolean
        get() = input.overlayMenuCaptured
        set(value) {
            if (value && !input.overlayMenuCaptured) revokeRewind()
            input.overlayMenuCaptured = value
        }
    var quickMenuInputCaptured: Boolean
        get() = input.quickMenuCaptured
        set(value) {
            if (value && !input.quickMenuCaptured) revokeRewind()
            input.quickMenuCaptured = value
        }
    var onCompanionAim: (Float, Float) -> Boolean
        get() = input.onCompanionAim
        set(value) { input.onCompanionAim = value }
    var keyboardInputCaptured: Boolean
        get() = input.keyboardCaptured
        set(value) {
            if (value && !input.keyboardCaptured) revokeRewind()
            input.keyboardCaptured = value
        }

    /** Per-game pace, or zero for unlimited FF (bounded work per display refresh). Main thread. */
    var speed: Double = 1.0
    var volume: Float = 1f
    var muted: Boolean = false
    var steadyClip: SteadyMusicClip? = null
    var steadyAwaiting = false
    var onMusicPause: (Boolean) -> Unit = {}
    var muteAudioAtSpeed: (Double) -> Boolean = { false }
    val audioSilent: Boolean get() = shouldMuteAudio(effectiveSpeed())

    private fun shouldMuteAudio(rate: Double) = rewinding || muted || volume == 0f || rate == 0.0 ||
        muteAudioAtSpeed(rate) || audio.hasSteadyMusic || steadyAwaiting && rate > 1.0
    /** The same diff-ring capacity as Android; configuring it releases any current hold. */
    val rewindHold = RewindHold()
    val canRewind get() = running && active && !uiPaused && !stateChanging &&
        !input.companionCaptured && !input.overlayMenuCaptured && !input.quickMenuCaptured &&
        !input.keyboardCaptured && !input.capture.active && rewindEnabled && rewindAllowed()

    fun applyRewindHold() = holdRewind(rewindHold.active)

    fun revokeRewind() {
        rewindHold.revoke()
        holdRewind(false)
    }

    var rewindEnabled: Boolean = false
        set(value) {
            if (field == value) return
            revokeRewind()
            field = value
            if (running) updateRewindCapacity()
        }
    private var rewindFramesEnabled = false
    private var rewinding = false
    /** Cancel queued battle commands on either transport edge. No state operation or touch release here. */
    var beforeRewind: () -> Unit = {}
    var rewindAllowed: () -> Boolean = { true }

    private fun updateRewindCapacity() {
        val enabled = rewindEnabled && rewindAllowed()
        if (enabled == rewindFramesEnabled) return
        if (!enabled) revokeRewind()
        PkApiCore.setRewind(RewindPolicy.capacity(enabled))
        rewindFramesEnabled = enabled
    }

    private fun holdRewind(on: Boolean) {
        val next = on && rewindEnabled && rewindAllowed()
        if (next == rewinding) return
        rewinding = next
        camera.reset()
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
    var beforeFrame: (GameSession.FrameWork) -> Unit = {}
    /** May replace input or hold the core while the main-thread game loop continues polling. */
    var frameGate: ((Int) -> Int?)? = null
    var afterFrame: () -> Unit = {}
    var holdPicture: () -> Boolean = { false }
    var quietPress: () -> Boolean = { false }
    var performanceObserver: GamePerformanceObserver? = null
    val performanceSpeed: Float get() = effectiveSpeed().let { if (it == 0.0) Float.POSITIVE_INFINITY else it.toFloat() }
    private var audioSpeed = -1.0

    /** Releases scripted input before a state changes the running game. */
    var beforeStateChange: () -> Unit = {}
    /** Initial open only; a normal foreground return keeps the paused core in memory. */
    var onCoreStarted: () -> Unit = {}
    /** Main thread, after pausing frames/audio but while state operations can still use the core. */
    var beforeSuspend: () -> Boolean = { true }
    var onForeground: () -> Unit = {}

    /** Whether a game is open and running in this controller. */
    val isRunning: Boolean get() = running
    /** Frame phase for effects attached to the native core callback. Held frames never reach afterFrame. */
    val completedFrameWork: GameSession.FrameWork get() = when {
        !running || !active || uiPaused || stateChanging -> GameSession.FrameWork.HELD
        rewinding -> GameSession.FrameWork.REWINDING
        else -> GameSession.FrameWork.ADVANCING
    }
    val playingForward: Boolean get() = completedFrameWork == GameSession.FrameWork.ADVANCING

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
        updateRewindCapacity()
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
        revokeRewind()
        onMusicPause(true)
        audio.stop()
        return beforeSuspend()
    }

    /** Runs again after [pause]. */
    fun resume() {
        if (!running || uiPaused) return
        input.backgroundCaptured(false)
        onForeground()
        onMusicPause(false)
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
            camera.reset()
            pacer.reset()
            audioSpeed = -1.0
            if (active && running) audio.start()
        }
    }

    /** Writes the game's save and closes it; the controller then shows nothing. */
    fun close() {
        effectsScope.cancel()
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
        if (running) {
            pause()
            running = false
            audio.release()
            PkApiCore.finishSave(FINISH_SAVE_FRAMES)
            PkApiCore.stop()
        }
        onGamePixel = null
        onPictureChanged = {}
        onHotkey = {}
        beforeRewind = {}
        beforeFrame = {}
        frameGate = null
        rewindFramesEnabled = false
        onForeground = {}
        rewindAllowed = { true }
        afterFrame = {}
        onMusicPause = {}
        steadyClip = null
        performanceObserver = null
    }

    private fun refresh(view: MTKView) {
        if (!running || !active || uiPaused) return
        // Poll once per display refresh: capture stays responsive at low speeds, and FF batches avoid repeated
        // GameController queries. A controller action can close/restart the game or open the editor here.
        input.poll()
        if (!running || !active || uiPaused) return
        if (rewindHold.active && !canRewind) revokeRewind()
        view.drawableSize.useContents {
            val w = width.toInt(); val h = height.toInt()
            if (camera.viewW != w || camera.viewH != h) {
                camera.viewW = w; camera.viewH = h; camera.reset()
                pictureWindow = null
                renderer?.upload()
            }
        }
        val rate = effectiveSpeed()
        val now = CACurrentMediaTime()
        if (rate != audioSpeed) {
            pacer.reset()
            // Fewer samples per emulated frame give pitched, sped-up sound at the device's fixed 48 kHz.
            PkApiCore.setAudioRate(PkApiCore.SAMPLE_RATE / rate.coerceAtLeast(0.25))
            audioSpeed = rate
        }
        val frames = if (rate == 0.0) 64 else pacer.framesAt(now, rate)
        val performance = performanceObserver
        var ran = false
        for (frame in 0 until frames) {
            // Unlimited FF still leaves time for the companion and touch events on the main thread.
            if (rate == 0.0 && ran && CACurrentMediaTime() - now >= 0.008) break
            val workStarted = if (performance != null) CACurrentMediaTime() else 0.0
            if (!running || !active || uiPaused) break
            val gate = frameGate
            val held = if (gate == null) {
                if (rewindFramesEnabled != rewindEnabled) updateRewindCapacity()
                beforeFrame(if (rewinding) GameSession.FrameWork.REWINDING else GameSession.FrameWork.ADVANCING)
                input.keys()
            } else {
                val allowed = gate(input.keys())
                // Do not retain frames from a linked battle or a story-locked save in the rewind ring.
                updateRewindCapacity()
                if (allowed == null) {
                    beforeFrame(GameSession.FrameWork.HELD)
                    break
                }
                beforeFrame(if (rewinding) GameSession.FrameWork.REWINDING else GameSession.FrameWork.ADVANCING)
                allowed
            }
            if (!running || !active || uiPaused) break
            val keys = if (rewinding) 0 else frameKeys(held)
            if (!running || !active || uiPaused) break
            val window = if (expanded && wideSupported) runCatching {
                camera.stickX = input.cameraX; camera.stickY = input.cameraY
                camera.walking = held and 0xF0 != 0
                camera.frame(PkApiCore.wideScene)
            }.getOrElse {
                // Keep this run on the ordinary picture; a settings refresh must not re-enable a failing camera.
                wideTables = 0
                expanded = false
                onPictureChanged()
                null
            } else null
            if (window != null) PkApiCore.setWideView(window.x, window.y, window.w, window.h)
            else PkApiCore.setWideView(0, 0, 0, 0)
            // Capped FF draws its final frame; uncapped FF always draws one even if its time budget expires early.
            PkApiCore.setWideDraw(frame == frames - 1 || rate == 0.0 && frame == 0)
            PkApiCore.setKeys(keys)
            PkApiCore.runFrame()
            if (!PkApiCore.wideSkipped) pictureWindow = window.takeIf { PkApiCore.wideWidth > 0 }
            if (wideTables != 0L && PkApiCore.wideFault != 0) {
                wideTables = 0; expanded = false; pictureWindow = null; onPictureChanged()
            }
            afterFrame()
            audio.volume = if (muted) 0f else volume
            audio.steadyClip(steadyClip.takeUnless { rewinding })
            audio.liveMuted = shouldMuteAudio(rate) || quietPress()
            audio.push()
            performance?.frame(((CACurrentMediaTime() - workStarted) * 1e9).toLong())
            ran = true
            // A menu may open on the first uncapped frame. Do not spend the remaining fast batch inside it.
            if (effectiveSpeed() != rate) break
        }
        if (!running || !active || uiPaused) return
        val newPicture = ran && (rewinding || !holdPicture()) &&
            renderer?.upload(if (expanded) pictureWindow else null) == true
        val submitted = draw(view)
        performance?.refresh(newPicture && submitted)
    }

    private fun effectiveSpeed(): Double = if (rewinding) 1.0 else
        frameSpeed().takeIf { it.isFinite() && it in 0.0..64.0 } ?: 1.0

    private fun draw(view: MTKView): Boolean {
        val renderer = renderer ?: return false
        val pass = view.currentRenderPassDescriptor ?: return false
        val drawable = view.currentDrawable ?: return false
        val buffer = queue?.commandBuffer() ?: return false
        view.drawableSize.useContents {
            renderer.prepare(buffer, width, height)
            onGamePixel?.invoke(renderer.gamePixelHeight(width, height))
        }
        val encoder = buffer.renderCommandEncoderWithDescriptor(pass) ?: return false
        view.drawableSize.useContents { renderer.encode(encoder, width, height) }
        encoder.endEncoding()
        buffer.presentDrawable(drawable)
        buffer.commit()
        return true
    }
}
