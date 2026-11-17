package com.pokedaisy.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.pokedaisy.app.companion.Smoothing
import com.pokedaisy.app.overlay.BuiltInFrames
import com.pokedaisy.app.overlay.Box
import com.pokedaisy.app.overlay.OverlayBitmaps
import com.pokedaisy.app.overlay.OverlayGeometry
import com.pokedaisy.app.overlay.OverlayImage
import com.pokedaisy.app.overlay.StoredOverlay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * Draws the mGBA RGBA framebuffer as an aspect-fit (or, with [stretch], view-filling)
 * nearest-filtered quad, optionally through [gbaColors] and a screen effect, and an [overlay]
 * around it (the game then sits in the overlay's window instead of the whole view). With [wide]
 * (SETTINGS > ASPECT > WIDE) it draws the wide frame instead whenever the core made one: the
 * window [camera] picked around the game (ZOOM, the look-around), placed as it says, with no overlay.
 * Continuous render mode. The GL thread never reads the core's own buffer, which the emu
 * thread draws into line by line: each finished frame is copied out ([publishFrame]) and
 * that copy is what's uploaded. Uploading the live buffer showed a frame half drawn - the
 * top of the new one over the bottom of the old - a tear across the screen while scrolling.
 */
class EmulatorView(context: Context) : GLSurfaceView(context) {

    private val renderer = FrameRenderer()

    /** Polled by the GL thread each frame: while true the screen keeps its last frame. */
    var holdFrame: () -> Boolean
        get() = renderer.holdFrame
        set(v) { renderer.holdFrame = v }

    /** Fill the whole view instead of keeping the GBA's 3:2 (ASPECT > STRETCH, [Prefs.gameAspect]). */
    var stretch: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            renderer.setStretch(v)
            reportGamePixel()
        }

    /**
     * WIDE ([WideScreen]): the core draws the window [camera] picks for this view each frame (the engine asks it,
     * [EmulatorEngine.wideCamera]); frames drawn wide are shown where the window says.
     */
    var wide: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            camera.reset()
            reportGamePixel()
        }

    /** ZOOM and the look-around: which window of the scene this view shows. */
    val camera = WideCamera()

    /**
     * A DS game (DS RESOLUTION, nds/pk_nds_gl.h): the view hands the core a SurfaceTexture for each GL context it gets,
     * and frames the core put out on the GPU ([MgbaCore.pkNdsGlPresented]) are taken from there at their own
     * resolution - several texels a DS pixel - instead of uploaded from the frame buffer.
     */
    var gpuFrames: Boolean
        get() = renderer.gpuFrames
        set(v) { renderer.gpuFrames = v }

    /** Called (UI thread) with one GBA pixel's height on screen, in view px, whenever it changes -
     * SHADERS on the companion draw their grid at that size ([CompanionColors]). */
    var onGamePixel: ((Float) -> Unit)? = null

    /** Called (UI thread) with where the game's picture is in this view (px), whenever it changes - the FIELD HUD
     * stays inside it; null = the whole view (EXPAND, or STRETCH with no overlay). */
    var onGameRect: ((android.graphics.RectF?) -> Unit)? = null
    /** Where the core's own picture (the GBA's 240x160, a Game Boy's 160x144) is in this view: [onGameRect]'s, but
     * EXPAND's middle at the view's height (its battles and field stay centred at 1x) and STRETCH's whole view. */
    var onCoreRect: ((android.graphics.RectF) -> Unit)? = null
    private var frameW = 0
    private var frameH = 0

    /** Tells [onGamePixel] / [onGameRect] / [onCoreRect] again (the view moved in its parent: DS LAYOUT). */
    fun reportGamePixel() {
        if (frameW == 0 || width == 0 || height == 0) return
        // As FrameRenderer.recomputeQuad: the game's height on screen, letterboxed or stretched, or in the overlay
        // (WIDE's frame is never wider than the view and has no overlay, so it's always the view's height).
        val img = overlayImage
        val h = when {
            wide || stretch && img == null -> height.toFloat()
            img != null -> placement(img, width, height, frameW, frameH, stretch).game.h
            else -> minOf(height.toFloat(), width * frameH.toFloat() / frameW)
        }
        onGamePixel?.invoke(h / frameH)
        val game = when {
            wide || stretch && img == null -> null
            img != null -> placement(img, width, height, frameW, frameH, stretch).game.let {
                android.graphics.RectF(it.x, it.y, it.x + it.w, it.y + it.h)
            }
            else -> {
                val w = minOf(width.toFloat(), height * frameW.toFloat() / frameH)
                val hh = w * frameH / frameW
                android.graphics.RectF((width - w) / 2, (height - hh) / 2, (width + w) / 2, (height + hh) / 2)
            }
        }
        onGameRect?.invoke(game)
        onCoreRect?.invoke(
            game ?: if (wide) {
                val w = minOf(width.toFloat(), height * frameW.toFloat() / frameH)
                android.graphics.RectF((width - w) / 2, 0f, (width + w) / 2, height.toFloat())
            } else android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        camera.viewW = w
        camera.viewH = h
        refreshOverlay()
        reportGamePixel()
    }

    /** What SCREEN > OVERLAY draws around the game ([com.pokedaisy.app.Prefs.overlay]). */
    sealed class OverlaySource {
        data class BuiltIn(val style: BuiltInFrames.Style) : OverlaySource()
        class Imported(val overlay: StoredOverlay) : OverlaySource() {
            /** The same files as before: nothing to load again. */
            private val key = overlay.layers.joinToString { "${it.file.path}:${it.file.lastModified()}" }
            override fun equals(other: Any?) = other is Imported && other.key == key
            override fun hashCode() = key.hashCode()
        }
    }

    /** The overlay around the game; null = none. A built-in frame is drawn for the view's size on the spot, an
     * imported one decoded off the UI thread (the last one stays up meanwhile). */
    var overlay: OverlaySource? = null
        set(v) {
            if (field == v) return
            field = v
            refreshOverlay()
        }

    /** What the renderer has now (UI thread's copy, for [reportGamePixel]). */
    private var overlayImage: OverlayImage? = null
    /** Bumped by every [refreshOverlay]: a decode that finishes after a newer one started is dropped. */
    private var overlayGen = 0
    /** The imported image decoded last, by file + size, so a relayout at the same size doesn't decode it again. */
    private var decodedKey: String? = null
    private var decoded: OverlayImage? = null

    private fun refreshOverlay() {
        val src = overlay
        val gen = ++overlayGen
        if (src == null) return applyOverlay(null)
        val w = width
        val h = height
        val fw = frameW
        val fh = frameH
        if (w == 0 || h == 0 || fw == 0) return  // again from onSizeChanged / bindCore
        when (src) {
            is OverlaySource.BuiltIn -> applyOverlay(OverlayBitmaps.builtIn(src.style, w, h, fw, fh))
            is OverlaySource.Imported -> {
                val layer = src.overlay.layerFor(w.toFloat() / h) ?: return applyOverlay(null)
                val key = "${layer.file.path}:${layer.file.lastModified()}:${w}x$h"
                if (key == decodedKey) return applyOverlay(decoded)
                Thread {
                    val img = runCatching { OverlayBitmaps.imported(layer, w, h) }
                        .onFailure { Log.w("pokedaisy", "overlay: can't load ${layer.file}", it) }.getOrNull()
                    post {
                        if (gen != overlayGen) return@post
                        decodedKey = key
                        decoded = img
                        applyOverlay(img)
                    }
                }.start()
            }
        }
    }

    private fun applyOverlay(img: OverlayImage?) {
        overlayImage = img
        renderer.setOverlay(img)
        reportGamePixel()
    }

    /** The GBA LCD's colours ([Prefs.gbaColors], [ScreenShaders.GBA_COLOR]). */
    var gbaColors: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            renderer.setGbaColors(v)
        }

    /** SHADERS > UNLIT SCREEN ([Prefs.unlitScreen], [ScreenShaders.UNLIT]): drawn between SMOOTHING and FILTER. */
    var unlit: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            renderer.setUnlit(v)
        }

    /** SHADERS > FILTER's effect ([Prefs.screenFilter], [ScreenShaders.effectFor]); null = none. */
    internal var screenEffect: ScreenShaders.Effect? = null
        set(v) {
            if (field == v) return
            field = v
            renderer.setEffect(v)
        }

    /** The effect's `uGrid` (SHADERS > GRID, [ScreenShaders.gridFor]): a uniform, so no rebuild. */
    var screenGrid: FloatArray = floatArrayOf(0f, 0f)
        set(v) {
            field = v
            renderer.setGrid(v[0], v[1])
        }

    /** SHADERS > SMOOTHING ([com.pokedaisy.app.Prefs.smoothing]): xBRZ / xBR / ScaleFX before FILTER. */
    var smoothing: Smoothing = Smoothing.OFF
        set(v) {
            if (field == v) return
            field = v
            renderer.setSmoothing(ScreenShaders.smoothingPasses(v)?.map { pass ->
                LoadedPass(pass, shaderAssets.getOrPut(pass.asset) {
                    context.assets.open("shaders/" + pass.asset).use { it.readBytes().decodeToString() }
                })
            })
        }
    private val shaderAssets = HashMap<String, String>()

    /** A smoothing pass with its file's text (read on the UI thread; compiled on the GL thread). */
    private class LoadedPass(val pass: ScreenShaders.SmoothPass, val source: String)

    init {
        // ES 3 where the GPU has it (SMOOTHING's shaders are GLSL ES 3.00, ScaleFX draws into half
        // floats); every other pass is GLSL ES 1.00, which an ES 3 context runs as well.
        setEGLContextClientVersion(if (GlCaps.gles3(context)) 3 else 2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
        super.surfaceCreated(holder)
        // The game makes 60 frames a second: ask for a 60 Hz (or a multiple) refresh for this surface. A
        // 120 Hz screen then can drop to 60 while the game is up - half the draws and composition (heat) -
        // and every refresh shows a new frame. A hint: the system may keep its rate.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching { holder.surface.setFrameRate(60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
        }
    }

    // The window reports its first draw (and drops Android 12's splash screen) only once every
    // surfaceRedrawNeededAsync it asked for has called back. GLSurfaceView keeps just the latest
    // finishDrawing: a view resized twice before its first frame (STATUS BAR over a portrait
    // game: laid out before the bar had a height, then again with it) lost the first one, and the
    // splash stayed over the game. Every pending one runs with the next frame instead.
    private val pendingRedraws = ArrayList<Runnable>()

    override fun surfaceRedrawNeededAsync(holder: android.view.SurfaceHolder, finishDrawing: Runnable) {
        synchronized(pendingRedraws) { pendingRedraws += finishDrawing }
        super.surfaceRedrawNeededAsync(holder) {
            val all = synchronized(pendingRedraws) { pendingRedraws.toList().also { pendingRedraws.clear() } }
            all.forEach(Runnable::run)
        }
    }

    // GLSurfaceView composites via its own hardware layer, "punching a hole"
    // in the window - the existing plain-View overlays (touch controls, HUD)
    // coexist with that fine, but a ComposeView sibling (PokeDaisyActivity's
    // debug-only companion-UI mirror) disrupted it, rendering this view
    // blank. setZOrderMediaOverlay is the standard fix for "SurfaceView with
    // overlay UI on top of it" - called from there (only when the mirror is
    // actually enabled) rather than unconditionally here.

    // The frame buffer is handed to the renderer under its lock, not through
    // queueEvent: SWAP SCREENS moves this view between the activity's window and
    // the second screen's, and a detached GLSurfaceView's queue belongs to a GL
    // thread that has already exited - a bind or unbind sent then was lost (and
    // the next GL thread could read a freed buffer). See PokeDaisyActivity.arrangeScreens.

    /**
     * Emu thread, once after the core is up and before its first frame; [wideBuffer] is
     * [MgbaCore.pkWideBuffer] (null: no WIDE for this core). The buffers go to the renderer right
     * here, never through a post: a bind still queued when the core stopped (a quick pause, SWAP
     * SCREENS, a restart) landed after the unbind and left the freed buffers bound, and the next
     * core's [publishFrame] copied out of them (a SIGSEGV in libc's memmove, Crashlytics).
     */
    fun bindCore(buffer: ByteBuffer, wideBuffer: ByteBuffer?, width: Int, height: Int) {
        renderer.bind(buffer, wideBuffer, width, height)
        // ZOOM / the look-around work in the game's own pixels: the GBA's 240x160, a Game Boy's 160x144.
        camera.frameW = width
        camera.frameH = height
        post {
            frameW = width
            frameH = height
            // A built-in frame is drawn around the game's own size (a Game Boy's is smaller).
            refreshOverlay()
            reportGamePixel()
        }
    }

    /** Emu thread, right after a frame has run: hands the finished frame to the GL thread - the
     * wide one when the core drew it ([MgbaCore.pkWideWidth]), with the window it shows. */
    fun publishFrame() {
        try {
            // A fast-forward frame the window wasn't drawn for: the screen keeps the last one (never a plain frame between).
            if (wide && MgbaCore.pkWideSkipped()) return
            if (gpuFrames && MgbaCore.pkNdsGlPresented()) {
                // The core put it into the SurfaceTexture: the window it shows (EXPAND's), or the plain screen.
                renderer.publishGpu(if (wide) camera.current else null)
                return
            }
            val w = MgbaCore.pkWideWidth()
            val win = camera.current
            if (w > 0 && win != null) renderer.publish(w, MgbaCore.pkWideHeight(), win) else renderer.publish(0, 0, null)
        } catch (t: Exception) {
            // Whatever went wrong with the window, the game's own frame still goes up.
            Log.e("pokedaisy", "WIDE frame not published", t)
            runCatching { renderer.publish(0, 0, null) }
        }
    }

    /**
     * GL thread, once per draw: in RENDERMODE_CONTINUOUSLY the previous swap has just
     * returned, so this ticks with the refresh of the display the game is on - what
     * EmulatorEngine paces 1x frames to ([EmulatorEngine.onVsync]).
     */
    var onVsync: (() -> Long)?
        get() = renderer.onVsync
        set(v) { renderer.onVsync = v }

    /** New game frames put on screen so far, and refreshes whose due frame came too late to wait for
     * (it showed a refresh later) - for FPS / CPU's performance panel. */
    val framesShown: Long get() = renderer.framesShown
    val lateFrames: Long get() = renderer.lateFrames
    val repeatedDraws: Long get() = renderer.repeatedDraws
    val unshownFrames: Long get() = renderer.unshownFrames

    /**
     * Drops the renderer's reference to the current framebuffer and blocks
     * (briefly, bounded) until the GL thread has actually applied that —
     * call this BEFORE tearing down/restarting the emulator core. Render
     * mode is continuous, so the GL thread reads [buffer] on its own clock;
     * freeing the native memory it points at (core restart tears down and
     * re-`pkInit`s) without this first is a use-after-free race — the GL
     * thread can still be mid-`glTexSubImage2D` on the old, now-freed
     * buffer, which crashes the whole process (seen as the app abruptly
     * closing back to ROM selection when "Restart Game" was added).
     */
    fun unbindCoreBlocking() {
        // Takes the lock a frame's upload holds: once this returns, no upload is reading it.
        renderer.unbind()
    }

    /**
     * Draws the frame in up to four passes: the GBA colour correction ([gbaColors]) at the
     * GBA's own size, UNLIT SCREEN ([setUnlit]) at the size the next pass reads, a prescaled
     * screen effect ([setEffect]) at the largest whole multiple of it that fits, then the result onto the view - nearest-filtered, or smoothly once an
     * effect has scaled it up (from a whole multiple, so its grid stays even). An effect that
     * isn't prescaled is that last pass itself. With neither, it's the one plain pass it always was.
     *
     * Every texture keeps the frame's own row order (top row first), so `vUv.y` runs down the
     * game in every shader; only the pass onto the view flips it to GL's bottom-up.
     *
     * An overlay is drawn over the game (blended: its window is see-through) or, with no window,
     * behind it; the game's quad is then its place in the overlay ([OverlayGeometry.layout]).
     */
    private class FrameRenderer : Renderer {
        /** The core's own frame buffer, read only by [publish] (the emu thread). */
        private var buffer: ByteBuffer? = null
        /** The core's WIDE frame buffer ([MgbaCore.pkWideBuffer]), read only by [publish]; null without WIDE. */
        private var wideBuffer: ByteBuffer? = null
        /** The last finished frame, copied out of [buffer] or [wideBuffer]; what the GL thread uploads. */
        private var ready: ByteBuffer? = null
        /** [ready] holds a frame the texture doesn't have yet. */
        private var fresh = false
        /** The core's frame size, and [ready]'s (a WIDE window's, with [readyWin]). Under [frameLock]. */
        private var frameW = 0
        private var frameH = 0
        private var readyW = 0
        private var readyH = 0
        private var readyWin: WideCamera.Window? = null
        /** The window the texture holds (null: the plain frame, placed by [recomputeQuad]). */
        private var shownWin: WideCamera.Window? = null
        private var texW = 0
        private var texH = 0
        private var surfaceW = 0
        private var surfaceH = 0
        private var dirtyGeometry = true
        private var stretch = false
        private var gbaColors = false
        private var unlit = false
        private var effect: ScreenShaders.Effect? = null
        /** SMOOTHING's passes; [smoothPrograms] are built from them on the GL thread. */
        private var smoothPasses: List<LoadedPass>? = null
        private var smoothPrograms: List<RetroProgram>? = null
        private var smoothBuiltFor: List<LoadedPass>? = null
        /** The passes failed here (a compile error, no half-float framebuffer): skipped until they change. */
        private var smoothFailed = false
        private val smoothTargets = ArrayList<RenderTarget>()
        private var gridX = 0f
        private var gridY = 0f
        private var overlay: OverlayImage? = null
        private var overlayProgram: Program? = null
        private var overlayTex = 0
        /** [overlay]'s pixels are in [overlayTex] (a new overlay or a new GL context uploads them again). */
        private var overlayUploaded = false
        private var overlayPos: FloatBuffer? = null
        private var overlayOnTop = true

        private var plain: Program? = null
        private var pixels: Program? = null
        private var colorProgram: Program? = null
        private var unlitProgram: Program? = null
        private var effectProgram: Program? = null
        private var effectProgramFor: ScreenShaders.Effect? = null
        private var texId = 0
        private var texAllocated = false
        private var colorTarget = RenderTarget()
        private var unlitTarget = RenderTarget()
        private var effectTarget = RenderTarget()
        @Volatile var holdFrame: () -> Boolean = { false }

        // DS RESOLUTION's frames (EmulatorView.gpuFrames): the core draws them into [gpuTexture]'s Surface on its own
        // GL context; each is copied into [gpuTarget] at its own size and goes on from there like an uploaded frame.
        @Volatile var gpuFrames = false
        private var gpuTexture: SurfaceTexture? = null
        /** A core was bound since the window was handed over: hand it over again (GL thread, [draw]). */
        @Volatile private var gpuRehand = false
        private var gpuSurface: android.view.Surface? = null
        private var oesTex = 0
        private var oesProgram: Program? = null
        private var gpuTarget = RenderTarget()
        /** [ready] stands for a frame in [gpuTexture] (under [frameLock]). */
        private var readyGpu = false
        /** The texture shown is [gpuTarget]'s (its texels finer than [texW] x [texH], the frame's DS pixels). */
        private var gpuShown = false
        private val gpuInfo = IntArray(6)

        @Volatile var framesShown = 0L
        @Volatile var lateFrames = 0L
        /** Refreshes that drew no new game frame, and game frames replaced before any refresh drew them: together the
         * judder a log line reads (a steady 1x shows neither). */
        @Volatile var repeatedDraws = 0L
        @Volatile var unshownFrames = 0L

        /** Ticks the engine; returns how long this draw may wait for the game frame that tick starts (0 = none due). */
        @Volatile var onVsync: (() -> Long)? = null

        /** Onto the view: textures start with the game's top row, the view with its bottom one. */
        private val viewUv: FloatBuffer = floats(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        )
        /** Into a pass's texture: kept in the input's order. */
        private val passUv: FloatBuffer = floats(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )
        private val fullQuad: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private var pos: FloatBuffer = fullQuad
        /** The game's size on the view, in pixels. */
        private var quadW = 0f
        private var quadH = 0f

        /** Guards [buffer] and its size: held by [bind] / [unbind] and by a frame's draw. */
        private val lock = Any()
        /** Guards [ready] / [fresh] only, so a copy never waits on a whole draw (taken after [lock]). */
        private val frameLock = Any()

        fun bind(buf: ByteBuffer, wide: ByteBuffer?, w: Int, h: Int) = synchronized(lock) {
            synchronized(frameLock) {
                buffer = buf.also { it.order(ByteOrder.nativeOrder()) }
                wideBuffer = wide?.also { it.order(ByteOrder.nativeOrder()) }
                // A new one each time: a restarted core mustn't show the last game's frame first.
                ready = ByteBuffer.allocateDirect(maxOf(buf.capacity(), wide?.capacity() ?: 0)).order(ByteOrder.nativeOrder())
                fresh = false
                frameW = w
                frameH = h
                readyW = w
                readyH = h
                readyWin = null
            }
            shownWin = null
            texW = w
            texH = h
            // A DS core lets go of the window it was handed when it ends (pk_nds_gl_end): a new one (RESTART GAME, which
            // keeps this GL context) gets it again, else its frames - EXPAND's, ZOOM's - had nowhere to go.
            gpuRehand = true
            texAllocated = false
            dirtyGeometry = true
        }

        /** Emu thread: the frame the core just put into [gpuTexture] (with [win], EXPAND's window) is the next to show. */
        fun publishGpu(win: WideCamera.Window?) = synchronized(frameLock) {
            if (fresh) unshownFrames++
            readyGpu = true
            readyWin = win
            fresh = true
            (frameLock as Object).notifyAll()
        }

        /** Emu thread, between frames (so [buffer] is whole): copies it - or, with [win], the WIDE window
         * [wideW] x [wideH] - into [ready]. */
        fun publish(wideW: Int, wideH: Int, win: WideCamera.Window?) = synchronized(frameLock) {
            readyGpu = false
            val wideSrc = wideBuffer?.takeIf { wideW > 0 && wideH > 0 && win != null }
            val src = wideSrc ?: buffer ?: return
            val dst = ready ?: return
            val w = if (wideSrc != null) wideW else frameW
            val h = if (wideSrc != null) wideH else frameH
            src.position(0)
            src.limit(minOf(src.capacity(), dst.capacity(), w * h * 4))
            dst.position(0)
            dst.put(src)
            src.limit(src.capacity())
            readyW = w
            readyH = h
            readyWin = if (wideSrc != null) win else null
            if (fresh) unshownFrames++
            fresh = true
            (frameLock as Object).notifyAll()
        }

        /** GL thread: the frame this refresh started is on its way - wait for it (at most [nanos]), so it
         * shows on this refresh. Drawn without waiting, a frame finishing just after the draw showed one
         * refresh late and the next one on time: two frames on one refresh, then one held for two. */
        private fun awaitFresh(nanos: Long) = synchronized(frameLock) {
            val end = System.nanoTime() + nanos
            while (!fresh) {
                val left = end - System.nanoTime()
                if (left <= 0) { lateFrames++; break }
                (frameLock as Object).wait(left / 1_000_000L, (left % 1_000_000L).toInt())
            }
        }

        fun setStretch(on: Boolean) = synchronized(lock) {
            stretch = on
            dirtyGeometry = true
        }

        fun setGbaColors(on: Boolean) = synchronized(lock) { gbaColors = on }

        fun setUnlit(on: Boolean) = synchronized(lock) { unlit = on }

        fun setEffect(e: ScreenShaders.Effect?) = synchronized(lock) { effect = e }

        fun setGrid(x: Float, y: Float) = synchronized(lock) { gridX = x; gridY = y }

        fun setSmoothing(passes: List<LoadedPass>?) = synchronized(lock) { smoothPasses = passes }

        fun setOverlay(img: OverlayImage?) = synchronized(lock) {
            overlay = img
            overlayUploaded = false
            dirtyGeometry = true
        }

        /** Drops the buffer references; [onDrawFrame] just clears until the next [bind]. */
        fun unbind() = synchronized(lock) {
            synchronized(frameLock) {
                buffer = null
                wideBuffer = null   // pkDeinit frees it too: the next core's wide frames read it until its bind
                fresh = false
                readyGpu = false
            }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = synchronized(lock) {
            // A new context: everything made in the old one is gone with it.
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            plain = Program(ScreenShaders.PLAIN)
            pixels = Program(ScreenShaders.PIXELS)
            colorProgram = Program(ScreenShaders.GBA_COLOR)
            unlitProgram = Program(ScreenShaders.UNLIT)
            overlayProgram = Program(ScreenShaders.OVERLAY)
            effectProgram = null
            effectProgramFor = null
            colorTarget = RenderTarget()
            unlitTarget = RenderTarget()
            effectTarget = RenderTarget()
            smoothPrograms = null
            smoothBuiltFor = null
            smoothFailed = false
            smoothTargets.clear()
            val ids = IntArray(3)
            GLES20.glGenTextures(3, ids, 0)
            texId = ids[0]
            overlayTex = ids[1]
            for (t in ids.take(2)) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            texAllocated = false
            overlayUploaded = false
            // The SurfaceTexture belonged to the old context: a new one for this context, handed to the core in draw().
            oesTex = ids[2]
            oesProgram = null
            gpuTarget = RenderTarget()
            dropGpuTexture()
            gpuShown = false
        }

        /** GL thread: a SurfaceTexture on this context for the core's frames, its Surface handed to the core. */
        private fun makeGpuTexture() {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(oesTex, false)
            val surface = android.view.Surface(st)
            gpuTexture = st
            gpuSurface = surface
            MgbaCore.pkNdsGlSetWindow(surface)
        }

        private fun dropGpuTexture() {
            if (gpuTexture == null) return
            MgbaCore.pkNdsGlSetWindow(null)
            gpuSurface?.release()
            gpuTexture?.release()
            gpuSurface = null
            gpuTexture = null
        }

        /** GL thread: the newest frame in [gpuTexture] into [gpuTarget] (top row first, like an upload); its DS
         * window size into [texW] x [texH]. False when there's none to take. */
        private fun takeGpuFrame(): Boolean {
            val st = gpuTexture ?: return false
            try {
                st.updateTexImage()
            } catch (e: RuntimeException) {
                Log.w("pokedaisy", "DS frame not taken", e)
                return false
            }
            if (!MgbaCore.pkNdsGlFrameInfo(st.timestamp, gpuInfo)) return false
            val w = gpuInfo[2]
            val h = gpuInfo[3]
            if (w <= 0 || h <= 0 || gpuInfo[4] <= 0 || gpuInfo[5] <= 0) return false
            if (!gpuTarget.ensure(gpuInfo[4], gpuInfo[5])) return false
            val program = oesProgram ?: Program(OES_COPY).also { oesProgram = it }
            val matrix = FloatArray(16)
            st.getTransformMatrix(matrix)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, gpuTarget.fbo)
            GLES20.glViewport(0, 0, gpuTarget.w, gpuTarget.h)
            GLES20.glUseProgram(program.id)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glUniform1i(program.uTex, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program.id, "uSt"), 1, false, matrix, 0)
            GLES20.glEnableVertexAttribArray(program.aPos)
            GLES20.glVertexAttribPointer(program.aPos, 2, GLES20.GL_FLOAT, false, 0, fullQuad)
            GLES20.glEnableVertexAttribArray(program.aUv)
            GLES20.glVertexAttribPointer(program.aUv, 2, GLES20.GL_FLOAT, false, 0, passUv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(program.aPos)
            GLES20.glDisableVertexAttribArray(program.aUv)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            if (w != texW || h != texH) {
                texW = w
                texH = h
                dirtyGeometry = true
            }
            return true
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = synchronized(lock) {
            surfaceW = width
            surfaceH = height
            dirtyGeometry = true
        }

        override fun onDrawFrame(gl: GL10?) {
            val wait = onVsync?.invoke() ?: 0L
            if (wait > 0) awaitFresh(wait)
            synchronized(lock) { draw() }
        }

        private fun draw() {
            if (gpuRehand) {
                gpuRehand = false
                dropGpuTexture()
            }
            if (gpuFrames && gpuTexture == null && oesTex != 0) makeGpuTexture()
            else if (!gpuFrames && gpuTexture != null) dropGpuTexture()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (buffer == null) return
            val plain = plain ?: return
            if (texW == 0 || surfaceW == 0) return

            // glTex(Sub)Image2D has taken the pixels by the time it returns, so frameLock
            // is held only for the copy into GL, not the passes below.
            synchronized(frameLock) {
                val buf = ready ?: return  // set with buffer by bind
                if (!fresh) repeatedDraws++
                if (readyGpu) {
                    // A held frame keeps what's up, as below; without one taken yet the last upload stays.
                    if (fresh && !holdFrame() && takeGpuFrame()) {
                        fresh = false
                        shownWin = readyWin
                        framesShown++
                        gpuShown = true
                        texAllocated = false   // a frame uploaded after this is a texture of its own size again
                    }
                    return@synchronized
                }
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                if (fresh && !holdFrame() && (gpuShown || readyW != texW || readyH != texH)) {
                    // WIDE came on or off, or ZOOM / the look-around changed the window's size: a texture of the new size.
                    texW = readyW
                    texH = readyH
                    texAllocated = false
                }
                if (!texAllocated) {
                    // The last frame published (a new GL context after SWAP SCREENS has none yet).
                    buf.position(0)
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, texW, texH, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
                    )
                    texAllocated = true
                    fresh = false
                    shownWin = readyWin
                    framesShown += 1
                    gpuShown = false
                } else if (fresh && !holdFrame()) {
                    // A held frame keeps the texture as is: the last frame shown stays up.
                    buf.position(0)
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D, 0, 0, 0, texW, texH,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
                    )
                    fresh = false
                    shownWin = readyWin
                    framesShown++
                }
            }
            if (dirtyGeometry) {
                recomputeQuad()
                dirtyGeometry = false
            }
            val win = shownWin
            try {
                if (win != null) windowQuad(win)
                else if (placedWin != null) recomputeQuad()
            } catch (t: Exception) {
                Log.e("pokedaisy", "WIDE window not placed", t)
                recomputeQuad()
            }

            // No window: the overlay is a background, under the game. A WIDE window has none (it fills the view).
            if (!overlayOnTop && win == null) drawOverlay()

            // A DS frame from the GPU: its texels finer than the DS's pixels, read smoothly from here on (SMOOTHING and
            // UNLIT SCREEN work on the game's own pixels: not on these).
            val gpu = gpuShown
            var input = if (gpu) gpuTarget.tex else texId
            var last = plain
            var smooth = gpu
            val color = colorProgram
            if (gbaColors && color != null &&
                (if (gpu) colorTarget.ensure(gpuTarget.w, gpuTarget.h) else colorTarget.ensure(texW, texH))) {
                pass(color, input, colorTarget, sample = gpu)
                input = colorTarget.tex
            }
            // The largest whole multiple of the game that fits, per axis (STRETCH scales them apart).
            val kx = maxOf(1, (quadW / texW).toInt())
            val ky = maxOf(1, (quadH / texH).toInt())
            val fx = effectProgram()
            val prescaled = fx != null && effect?.prescale == true
            // SMOOTHING: upscales the game; FILTER then draws over its picture (`uSmoothed`).
            val smoothed = if (gpu) 0 else runSmoothing(input, kx, ky, prescaled)
            if (smoothed != 0) {
                input = smoothed
                smooth = true
            }
            // UNLIT SCREEN: at the size the next pass reads (FILTER's whole multiple, else the view's own pixels),
            // since its shadow falls between GBA pixels; that pass then reads it where it is, like SMOOTHING's.
            val shade = unlitProgram
            var unlitDrawn = false
            if (unlit && shade != null && !gpu) {
                val (w, h) = if (prescaled) texW * kx to texH * ky
                    else maxOf(1, quadW.roundToInt()) to maxOf(1, quadH.roundToInt())
                if (unlitTarget.ensure(w, h)) {
                    // Read smoothly: the shadow's taps sit between pixels; its own pixel is read at its centre.
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, unlitTarget.fbo)
                    GLES20.glViewport(0, 0, w, h)
                    drawQuad(shade, input, passUv, fullQuad, smooth = true, smoothed = smoothed != 0)
                    input = unlitTarget.tex
                    smooth = true
                    unlitDrawn = true
                }
            }
            val readsPicture = smoothed != 0 || unlitDrawn || gpu
            if (prescaled) {
                if (effectTarget.ensure(texW * kx, texH * ky)) {
                    pass(fx!!, input, effectTarget, sample = readsPicture)
                    input = effectTarget.tex
                    smooth = true
                }
            } else if (fx != null) {
                last = fx
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            // The game's own pixels straight onto the view: each read at its centre (ScreenShaders.PIXELS).
            if (last === plain && !smooth) pixels?.let { last = it }
            drawQuad(last, input, viewUv, pos, smooth, smoothed = readsPicture && !prescaled)
            if (overlayOnTop && win == null) drawOverlay()
        }

        /** The overlay, if any, over what's drawn (premultiplied alpha, as Android's bitmaps are). */
        private fun drawOverlay() {
            val img = overlay ?: return
            val program = overlayProgram ?: return
            val quad = overlayPos ?: return
            if (!overlayUploaded) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, img.bitmap, 0)
                overlayUploaded = true
            }
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            drawQuad(program, overlayTex, viewUv, quad, img.smooth)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        /** Draws [input] through [program] into all of [target]; [sample] = [input] is SMOOTHING's picture
         * (read smoothly, at whatever size it came out). */
        private fun pass(program: Program, input: Int, target: RenderTarget, sample: Boolean = false) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.fbo)
            GLES20.glViewport(0, 0, target.w, target.h)
            drawQuad(program, input, passUv, fullQuad, smooth = sample, smoothed = sample)
        }

        /**
         * SMOOTHING's passes over [input] (the game, after GBA COLORS); returns the texture they drew,
         * or 0 with none (off, or failed here). Each pass reads the one before at its own size (and
         * PassPrevN the one N back; one before the first is [input]), drawn as RetroArch draws a pass.
         */
        private fun runSmoothing(input: Int, kx: Int, ky: Int, prescaled: Boolean): Int {
            val programs = smoothPrograms() ?: return 0
            val passes = smoothBuiltFor ?: return 0
            var src = input
            var srcW = texW
            var srcH = texH
            for ((i, program) in programs.withIndex()) {
                val (w, h) = when (val size = passes[i].pass.size) {
                    is ScreenShaders.SmoothSize.Source -> srcW * size.scale to srcH * size.scale
                    ScreenShaders.SmoothSize.Whole -> texW * kx to texH * ky
                    ScreenShaders.SmoothSize.View ->
                        if (prescaled) texW * kx to texH * ky
                        else maxOf(1, quadW.roundToInt()) to maxOf(1, quadH.roundToInt())
                }
                while (smoothTargets.size <= i) smoothTargets += RenderTarget()
                val target = smoothTargets[i]
                if (!target.ensure(w, h, half = passes[i].pass.float)) {
                    Log.w("pokedaisy", "smoothing: no ${if (passes[i].pass.float) "half-float " else ""}framebuffer, off")
                    smoothFailed = true
                    return 0
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.fbo)
                GLES20.glViewport(0, 0, w, h)
                program.draw(src, srcW, srcH, w, h) { n ->
                    // PassPrevN: N passes back; one before the first pass is the input.
                    when (val k = i - n) {
                        -1 -> Triple(input, texW, texH)
                        in 0 until i -> smoothTargets[k].let { Triple(it.tex, it.w, it.h) }
                        else -> null
                    }
                }
                src = target.tex
                srcW = w
                srcH = h
            }
            return src
        }

        /** SMOOTHING's programs, (re)built on the GL thread when [setSmoothing] changed the passes. */
        private fun smoothPrograms(): List<RetroProgram>? {
            val passes = smoothPasses
            if (passes !== smoothBuiltFor) {
                smoothPrograms?.forEach { GLES20.glDeleteProgram(it.id) }
                smoothPrograms = null
                smoothBuiltFor = passes
                smoothFailed = false
                if (passes != null) {
                    smoothPrograms = runCatching { passes.map { RetroProgram(it.source) } }
                        .onFailure {
                            Log.w("pokedaisy", "smoothing: shader failed to build, off", it)
                            smoothFailed = true
                        }
                        .getOrNull()
                }
            }
            return if (smoothFailed) null else smoothPrograms
        }

        private fun drawQuad(
            program: Program, input: Int, uv: FloatBuffer, quad: FloatBuffer, smooth: Boolean,
            smoothed: Boolean = false,
        ) {
            val filter = if (smooth) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glUseProgram(program.id)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            GLES20.glUniform1i(program.uTex, 0)
            GLES20.glUniform2f(program.uTexSize, texW.toFloat(), texH.toFloat())
            GLES20.glUniform2f(program.uGrid, gridX, gridY)
            GLES20.glUniform1f(program.uSmoothed, if (smoothed) 1f else 0f)

            GLES20.glEnableVertexAttribArray(program.aPos)
            GLES20.glVertexAttribPointer(program.aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(program.aUv)
            GLES20.glVertexAttribPointer(program.aUv, 2, GLES20.GL_FLOAT, false, 0, uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(program.aPos)
            GLES20.glDisableVertexAttribArray(program.aUv)
        }

        /** The effect's program, (re)built on the GL thread when [setEffect] changed it. */
        private fun effectProgram(): Program? {
            if (effect !== effectProgramFor) {
                effectProgram?.let { GLES20.glDeleteProgram(it.id) }
                effectProgram = effect?.let { Program(it.frag) }
                effectProgramFor = effect
            }
            return effectProgram
        }

        private var placedWin: WideCamera.Window? = null
        private val winQuad = FloatArray(8)
        private val winPos: FloatBuffer = floats(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

        /** A WIDE window's quad: [WideCamera.Window.offX] / [offY] from the view's top-left, its pixels [scale] each. */
        private fun windowQuad(win: WideCamera.Window) {
            if (win === placedWin) return
            placedWin = win
            val x0 = -1f + 2f * win.offX / surfaceW
            val x1 = x0 + 2f * win.w * win.scale / surfaceW
            val y1 = 1f - 2f * win.offY / surfaceH
            val y0 = y1 - 2f * win.h * win.scale / surfaceH
            winQuad[0] = x0; winQuad[1] = y0; winQuad[2] = x1; winQuad[3] = y0
            winQuad[4] = x0; winQuad[5] = y1; winQuad[6] = x1; winQuad[7] = y1
            winPos.position(0)
            winPos.put(winQuad)
            winPos.position(0)
            pos = winPos
            quadW = win.w * win.scale
            quadH = win.h * win.scale
        }

        private fun recomputeQuad() {
            placedWin = null
            val img = overlay
            if (img != null) {
                val p = placement(img, surfaceW, surfaceH, texW, texH, stretch)
                pos = ndc(p.game)
                overlayPos = ndc(p.overlay)
                overlayOnTop = p.onTop
                quadW = p.game.w
                quadH = p.game.h
                return
            }
            overlayOnTop = true
            val texAspect = texW.toFloat() / texH
            val surfAspect = surfaceW.toFloat() / surfaceH
            var sx = 1f
            var sy = 1f
            if (stretch) {
                // Fill the view; the layout already sized it.
            } else if (surfAspect > texAspect) {
                sx = texAspect / surfAspect   // pillarbox
            } else {
                sy = surfAspect / texAspect   // letterbox
            }
            pos = floats(-sx, -sy, sx, -sy, -sx, sy, sx, sy)
            quadW = sx * surfaceW
            quadH = sy * surfaceH
        }

        /** [b] (view pixels from the top-left) as the quad's corners in GL's clip space (bottom-up). */
        private fun ndc(b: Box): FloatBuffer {
            val l = b.x / surfaceW * 2f - 1f
            val r = (b.x + b.w) / surfaceW * 2f - 1f
            val t = 1f - b.y / surfaceH * 2f
            val bt = 1f - (b.y + b.h) / surfaceH * 2f
            return floats(l, bt, r, bt, l, t, r, t)
        }

        /** A linked shader program over the shared quad ([VERT] + a [ScreenShaders] fragment shader). */
        private class Program(frag: String) {
            val id = buildProgram(VERT, frag)
            val aPos = GLES20.glGetAttribLocation(id, "aPos")
            val aUv = GLES20.glGetAttribLocation(id, "aUv")
            val uTex = GLES20.glGetUniformLocation(id, "uTex")
            val uTexSize = GLES20.glGetUniformLocation(id, "uTexSize")
            val uGrid = GLES20.glGetUniformLocation(id, "uGrid")
            val uSmoothed = GLES20.glGetUniformLocation(id, "uSmoothed")
        }

        /**
         * A RetroArch-format pass (one .glsl file, VERTEX / FRAGMENT by define): its attributes
         * (VertexCoord, TexCoord, COLOR), MVPMatrix and the size uniforms RetroArch gives a pass.
         * Throws if it doesn't build (caught by [smoothPrograms]).
         */
        private class RetroProgram(source: String) {
            val id = buildProgram(
                ScreenShaders.smoothingSource(source, "VERTEX"),
                ScreenShaders.smoothingSource(source, "FRAGMENT"),
            ) { p ->
                GLES20.glBindAttribLocation(p, 0, "VertexCoord")
                GLES20.glBindAttribLocation(p, 1, "TexCoord")
                GLES20.glBindAttribLocation(p, 2, "COLOR")
            }
            private fun u(name: String) = GLES20.glGetUniformLocation(id, name)
            private val mvp = u("MVPMatrix")
            private val frameDirection = u("FrameDirection")
            private val frameCount = u("FrameCount")
            private val outputSize = u("OutputSize")
            private val textureSize = u("TextureSize")
            private val inputSize = u("InputSize")
            private val texture = u("Texture")
            /** PassPrev1..6: (sampler, TextureSize, InputSize), for the ones this pass declares. */
            private val prev = (1..6).mapNotNull { n ->
                val t = u("PassPrev${n}Texture")
                if (t < 0) null else n to intArrayOf(t, u("PassPrev${n}TextureSize"), u("PassPrev${n}InputSize"))
            }

            /** Draws [src] ([srcW] x [srcH]) into the bound [outW] x [outH] target; [prevOf] gives PassPrevN. */
            fun draw(src: Int, srcW: Int, srcH: Int, outW: Int, outH: Int, prevOf: (Int) -> Triple<Int, Int, Int>?) {
                GLES20.glUseProgram(id)
                GLES20.glUniformMatrix4fv(mvp, 1, false, MVP, 0)
                GLES20.glUniform1i(frameDirection, 1)
                GLES20.glUniform1i(frameCount, 0)
                GLES20.glUniform2f(outputSize, outW.toFloat(), outH.toFloat())
                GLES20.glUniform2f(textureSize, srcW.toFloat(), srcH.toFloat())
                GLES20.glUniform2f(inputSize, srcW.toFloat(), srcH.toFloat())
                bindNearest(0, src)
                GLES20.glUniform1i(texture, 0)
                for ((i, entry) in prev.withIndex()) {
                    val (n, loc) = entry
                    val (tex, w, h) = prevOf(n) ?: continue
                    bindNearest(1 + i, tex)
                    GLES20.glUniform1i(loc[0], 1 + i)
                    GLES20.glUniform2f(loc[1], w.toFloat(), h.toFloat())
                    GLES20.glUniform2f(loc[2], w.toFloat(), h.toFloat())
                }
                GLES20.glEnableVertexAttribArray(0)
                GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, VERTEX_COORDS)
                GLES20.glEnableVertexAttribArray(1)
                GLES20.glVertexAttribPointer(1, 4, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
                GLES20.glDisableVertexAttribArray(2)
                GLES20.glVertexAttrib4f(2, 1f, 1f, 1f, 1f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(0)
                GLES20.glDisableVertexAttribArray(1)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            }

            private fun bindNearest(unit: Int, tex: Int) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            }

            companion object {
                /** VertexCoord in [0, 1] and this ortho matrix to clip space, as RetroArch draws; TexCoord y 0
                 * lands on the target's row 0, so every pass keeps the game's top-row-first order. */
                private val MVP = floatArrayOf(2f, 0f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, -1f, 0f, 1f)
                private val VERTEX_COORDS = floats(0f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 0f, 1f)
                private val TEX_COORDS = floats(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f)
            }
        }

        /** An offscreen texture a pass draws into; lives and dies with the GL context. */
        private class RenderTarget {
            var fbo = 0
            var tex = 0
            var w = 0
            var h = 0
            private var unsupported = false
            private var isHalf = false

            /** Sizes it to [width] x [height] ([half] = RGBA16F, ES 3 only); false if the GPU can't draw
             * into it (the pass is then skipped). */
            fun ensure(width: Int, height: Int, half: Boolean = false): Boolean {
                if (unsupported) return false
                if (w == width && h == height && isHalf == half) return true
                if (fbo == 0) {
                    val ids = IntArray(1)
                    GLES20.glGenTextures(1, ids, 0)
                    tex = ids[0]
                    GLES20.glGenFramebuffers(1, ids, 0)
                    fbo = ids[0]
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                if (half) {
                    GLES30.glTexImage2D(
                        GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0,
                        GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null,
                    )
                } else {
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
                    )
                }
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0,
                )
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    Log.w("pokedaisy", "screen filter: framebuffer incomplete (0x${status.toString(16)}), filter off")
                    unsupported = true
                    return false
                }
                w = width
                h = height
                isHalf = half
                return true
            }
        }

        companion object {
            /** A frame from the SurfaceTexture into a texture of its own size, top row first (vUv.y 0): the image's
             * top is the texture's t = 1 once its transform is applied. */
            private val OES_COPY = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES uTex;
                uniform mat4 uSt;
                varying vec2 vUv;
                void main() {
                    gl_FragColor = texture2D(uTex, (uSt * vec4(vUv.x, 1.0 - vUv.y, 0.0, 1.0)).xy);
                }
            """.trimIndent()

            private val VERT = """
                attribute vec2 aPos;
                attribute vec2 aUv;
                varying vec2 vUv;
                void main() {
                    vUv = aUv;
                    gl_Position = vec4(aPos, 0.0, 1.0);
                }
            """.trimIndent()

            private fun floats(vararg v: Float): FloatBuffer =
                ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder())
                    .asFloatBuffer().apply { put(v); position(0) }

            private fun buildProgram(vsrc: String, fsrc: String, beforeLink: (Int) -> Unit = {}): Int {
                val vs = compile(GLES20.GL_VERTEX_SHADER, vsrc)
                val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsrc)
                val p = GLES20.glCreateProgram()
                GLES20.glAttachShader(p, vs)
                GLES20.glAttachShader(p, fs)
                beforeLink(p)
                GLES20.glLinkProgram(p)
                val status = IntArray(1)
                GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
                check(status[0] != 0) { "program link failed: " + GLES20.glGetProgramInfoLog(p) }
                return p
            }

            private fun compile(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                val status = IntArray(1)
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { "shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
                return s
            }
        }
    }
}

/** Where [img] and the game go on a [viewW] x [viewH] view - shared by the GL thread's quads and the UI thread's game-pixel size. */
private fun placement(img: OverlayImage, viewW: Int, viewH: Int, gameW: Int, gameH: Int, stretch: Boolean) =
    OverlayGeometry.layout(viewW, viewH, img.bitmap.width, img.bitmap.height, img.window, gameW, gameH, stretch, img.scale)
