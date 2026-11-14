package com.pokedaisy.app.n3ds

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.pokedaisy.app.GbaInput
import com.pokedaisy.app.MgbaCore
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.ui.OwnItemNames
import java.io.File

/**
 * A 3DS game in the game screen (PokeDaisyActivity, docs/3DS_GAMES_PLAN.md): Azahar on a thread of its own, drawing the
 * top screen into [topView] and the touch screen into [touchView] (the stage places both: DS LAYOUT's arrangement, the
 * floating window, the second display), the app's own input ([GbaInput]: the player's buttons, the touch controls) and
 * the circle pad, speed, states, and the companion's snapshot once a second (PARTY / BAG, [Gen6Reader]).
 *
 * Azahar keeps one game per process and is set up once ([N3dsNative.init]); this boots it when both surfaces are there
 * and stops it in [stop]. A pause holds it between two frames ([N3dsNative.setPaused]), the surfaces may come and go
 * meanwhile.
 */
class N3dsSession(
    context: Context,
    val rom: File,
    val titleId: Long,
    private val input: GbaInput,
    /** The companion's snapshot, on the UI thread. */
    private val onSnapshot: (SnapshotView) -> Unit,
    /** The game stopped by itself (an error status) or after [stop], on the UI thread: Azahar's status, 0 = fine. */
    private val onEnded: (Int) -> Unit,
    /** The stage lays [touchView] out now; false = boot with the top surface alone (both screens on it). */
    private val wantsTouchSurface: () -> Boolean = { true },
    /** The game's scene changed ([scene]), on the UI thread. */
    private val onScene: (Gen6Reader.Scene) -> Unit = {},
    /** The top screen in the field, about once a second (the backdrop of the game's own screens), on the UI thread. */
    private val onFieldShot: (android.graphics.Bitmap) -> Unit = {},
) {
    val topView = SurfaceView(context)
    /** Over the top screen when it floats in (ONE SCREEN): drawn above the other surfaces. */
    val touchView = SurfaceView(context).apply { setZOrderMediaOverlay(true) }

    private val ui = Handler(Looper.getMainLooper())
    private val userDir = N3dsSaves.root(context).absolutePath
    private val appFiles = context.filesDir
    private var topReady = false
    private var touchReady = false
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false

    val started get() = thread != null

    init {
        topView.holder.addCallback(surfaceCallback(0) { topReady = it })
        touchView.holder.addCallback(surfaceCallback(1) { touchReady = it })
        touchView.setOnTouchListener { _, e -> touch(e); true }
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

    /** Asked for by the activity once the game should play; it starts when both surfaces are there. */
    @Volatile private var wanted = false

    fun start() {
        wanted = true
        // A pause before the game thread started (the activity paused while it booted) must not outlive the start:
        // the sampler and the scene reader skip their work while [paused].
        if (started) setPaused(false) else { paused = false; maybeStart() }
    }

    /** The stage placed or dropped [touchView]: a game waiting on its surfaces may start now. */
    fun surfacesChanged() = maybeStart()

    private fun maybeStart() {
        if (!wanted || thread != null || !topReady || (wantsTouchSurface() && !touchReady)) return
        running = true
        thread = Thread({
            N3dsNative.init(userDir)
            N3dsNative.setResolution(RESOLUTION)
            val result = N3dsNative.run(rom.absolutePath, ONE_SURFACE_LAYOUT)
            running = false
            Log.i(TAG, "game ended: $result")
            ui.post { onEnded(result) }
        }, "pokedaisy-n3ds").also { it.start() }
        pump.start()
        sampler.start()
        scenes.start()
    }

    fun setPaused(p: Boolean) {
        paused = p
        if (started) N3dsNative.setPaused(p)
    }

    /** Stops the game for good (the activity leaves it); blocks until Azahar has shut down, a few seconds at most. */
    fun stop() {
        wanted = false
        running = false
        OwnItemNames.lookup = null
        if (thread == null) return
        N3dsNative.stop()
        runCatching { thread?.join(5000) }
        thread = null
    }

    // ---- input ---------------------------------------------------------------------------------------------------

    /** The hat / D-pad from joystick motion (the app's own input turns the stick into the D-pad; here it's the circle
     * pad's, [setStick]). */
    @Volatile private var hatBits = 0

    /** The left stick as the circle pad, and the hat as the D-pad. */
    fun onMotion(event: MotionEvent) {
        fun dead(v: Float) = if (kotlin.math.abs(v) < STICK_DEAD) 0f else v
        N3dsNative.setStick(dead(event.getAxisValue(MotionEvent.AXIS_X)), -dead(event.getAxisValue(MotionEvent.AXIS_Y)))
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        hatBits = (if (hx < -0.5f) MgbaCore.Key.LEFT else 0) or (if (hx > 0.5f) MgbaCore.Key.RIGHT else 0) or
            (if (hy < -0.5f) MgbaCore.Key.UP else 0) or (if (hy > 0.5f) MgbaCore.Key.DOWN else 0)
    }

    /** The app's key mask ([GbaInput.mask]: GBA bits, the DS's X / Y) as the 3DS's buttons. */
    private fun buttons(mask: Int): Int {
        var b = 0
        if (mask and MgbaCore.Key.A != 0) b = b or N3dsNative.Button.A
        if (mask and MgbaCore.Key.B != 0) b = b or N3dsNative.Button.B
        if (mask and MgbaCore.Key.X != 0) b = b or N3dsNative.Button.X
        if (mask and MgbaCore.Key.Y != 0) b = b or N3dsNative.Button.Y
        if (mask and MgbaCore.Key.UP != 0) b = b or N3dsNative.Button.UP
        if (mask and MgbaCore.Key.DOWN != 0) b = b or N3dsNative.Button.DOWN
        if (mask and MgbaCore.Key.LEFT != 0) b = b or N3dsNative.Button.LEFT
        if (mask and MgbaCore.Key.RIGHT != 0) b = b or N3dsNative.Button.RIGHT
        if (mask and MgbaCore.Key.L != 0) b = b or N3dsNative.Button.L
        if (mask and MgbaCore.Key.R != 0) b = b or N3dsNative.Button.R
        if (mask and MgbaCore.Key.START != 0) b = b or N3dsNative.Button.START
        if (mask and MgbaCore.Key.SELECT != 0) b = b or N3dsNative.Button.SELECT
        return b
    }

    // The input every 4 ms: Azahar's HID reads our bits once a frame, the app's input has no listener of its own. A
    // press the hotkeys held back and let go (START / SELECT of a chord: GbaInput.tapMask) lasts a number of game frames,
    // so it's ticked once a 60 Hz frame here (every 4th pass), as the GBA / DS engine ticks it once a frame.
    private val pump = Thread({
        var last = -1
        var pass = 0
        var tap = 0
        while (running) {
            if (pass++ % 4 == 0) tap = input.tapMask()
            val now = buttons(input.mask or hatBits or tap)
            if (now != last) { N3dsNative.setButtons(now); last = now }
            try { Thread.sleep(4) } catch (_: InterruptedException) { break }
        }
    }, "pokedaisy-n3ds-input")

    private fun touch(e: MotionEvent) {
        if (!started) return
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> N3dsNative.TOUCH_DOWN
            MotionEvent.ACTION_MOVE -> N3dsNative.TOUCH_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> N3dsNative.TOUCH_UP
            else -> return
        }
        N3dsNative.touch(1, e.x.toInt(), e.y.toInt(), action)
    }

    /** A finger left down on the touch screen when it went away would stay down in the game. */
    fun releaseTouch() {
        if (started) N3dsNative.touch(1, 0, 0, N3dsNative.TOUCH_UP)
    }

    // ---- speed and states ----------------------------------------------------------------------------------------

    /** Fast-forward at [percent] of real time (100 = normal, 0 = flat out). */
    fun setSpeed(percent: Int) {
        if (started) N3dsNative.setSpeed(percent)
    }

    /** Off the UI thread: Azahar takes / loads it between two frames. [done] on the UI thread. */
    fun saveState(file: File, done: (Boolean) -> Unit) {
        if (!started) return done(false)
        file.parentFile?.mkdirs()
        Thread { val ok = N3dsNative.saveState(file.absolutePath); ui.post { done(ok) } }.start()
    }

    /** The top screen as it shows now, [width] px wide, into [dest] (a state's thumbnail, as the GBA games' are):
     * Azahar's own screenshot of the next frame, off the UI thread. (A PixelCopy of the surface came out see-through:
     * Azahar's frames carry alpha 0.) */
    fun writeThumb(dest: File, width: Int = 160) {
        if (!started) return
        Thread {
            val h = width * 3 / 5
            val px = N3dsNative.screenshot(width, h) ?: return@Thread
            runCatching {
                val bmp = android.graphics.Bitmap.createBitmap(px, width, h, android.graphics.Bitmap.Config.ARGB_8888)
                dest.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                bmp.recycle()
            }.onFailure { Log.w(TAG, "thumb write failed", it) }
        }.start()
    }

    fun loadState(file: File, done: (Boolean) -> Unit) {
        if (!started || !file.isFile) return done(false)
        Thread { val ok = N3dsNative.loadState(file.absolutePath); ui.post { done(ok) } }.start()
    }

    // ---- the game's scene (what its touch screen shows) -----------------------------------------------------------

    /** What the game's screens show now ([Gen6Reader.sceneOf]), read ten times a second; [onScene] hears a change. */
    @Volatile var scene: Gen6Reader.Scene = Gen6Reader.Scene.FIELD
        private set

    private val scenes = Thread({
        val config = Gen6Reader.configFor(titleId)
        var lastModules: String? = null
        // A change counts once two reads in a row agree: the field's flags pass through the menu's while it loads.
        var seen: Gen6Reader.Scene? = null
        var pass = 0
        while (running && config != null && config.staticModule != 0) {
            if (!paused) {
                val modules = N3dsNative.modules(config.staticModule)
                if (modules != null) {
                    if (modules != lastModules) { Log.d(TAG, "modules: ${modules.replace('\n', ',')}"); lastModules = modules }
                    val obj = if ("DllField" in modules) N3dsNative.readMemory(intArrayOf(config.fieldObject, 0x20)) else null
                    val now = Gen6Reader.sceneOf(modules, obj)
                    if (now != null && now != scene && now == seen) {
                        scene = now
                        Log.i(TAG, "scene: $now")
                        ui.post { onScene(now) }
                    }
                    seen = now
                }
                if (pass++ % FIELD_SHOT_EVERY == 0 && scene == Gen6Reader.Scene.FIELD) fieldShot()
                if (com.pokedaisy.app.BuildConfig.DEBUG) debugCommands(pass % FIELD_SHOT_EVERY == 1)
            }
            try { Thread.sleep(SCENE_MS) } catch (_: InterruptedException) { break }
        }
    }, "pokedaisy-n3ds-scene")

    /** Debug builds: `files/debug/n3ds_dump` there = the game's heap (0x08000000, 15 MB) and data (0x005A6000) written
     * to `files/debug/n3ds-<time>-<base>.bin`, the marker deleted (finding addresses on a device: diff two dumps). */
    /**
     * Debug builds: also `files/debug/n3ds_cmd`, lines of `poke <hex address> <hex bytes>` (written into the game's
     * memory) and `shot <name>` (the touch screen at 320 x 240 into `files/debug/<name>.png`), run in order, then
     * `n3ds_cmd.done` written (what adb waits for). How Pokemon X's party icons were read off the game.
     */
    private fun debugCommands(dumpTurn: Boolean) {
        val dir = File(userDir).parentFile?.let { File(it, "debug") } ?: return
        val cmd = File(dir, "n3ds_cmd")
        if (cmd.isFile) {
            val lines = runCatching { cmd.readLines() }.getOrDefault(emptyList())
            cmd.delete()
            for (line in lines) {
                val p = line.trim().split(Regex("\\s+"))
                when (p.getOrNull(0)) {
                    "poke" -> if (p.size == 3) {
                        val data = p[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        N3dsNative.writeMemory(p[1].toLong(16).toInt(), data)
                    }
                    "shot" -> if (p.size == 2) {
                        val px = N3dsNative.screenshot(320, 240, bottom = true)
                        if (px != null) runCatching {
                            val bmp = android.graphics.Bitmap.createBitmap(px, 320, 240, android.graphics.Bitmap.Config.ARGB_8888)
                            File(dir, p[1] + ".png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    }
                }
            }
            File(dir, "n3ds_cmd.done").writeText(lines.size.toString())
        }
        if (dumpTurn) debugDump(dir)
    }

    private fun debugDump(dir: File) {
        val marker = File(dir, "n3ds_dump")
        if (!marker.isFile) return
        marker.delete()
        val stamp = System.currentTimeMillis()
        for ((base, len) in listOf(0x005A6000 to 0x260000, 0x08000000 to 0xF00000)) {
            val ranges = IntArray(len / 0x100000 * 2 + 2).also { r ->
                var i = 0
                var a = 0
                while (a < len) { r[i++] = base + a; r[i++] = minOf(0x100000, len - a); a += 0x100000 }
            }.let { r -> r.copyOf(r.indexOfLast { it != 0 } + 1) }
            val bytes = N3dsNative.readMemory(ranges) ?: continue
            File(dir, "n3ds-$stamp-%08x.bin".format(base)).writeBytes(bytes)
        }
        Log.i(TAG, "dumped $stamp")
    }

    private fun fieldShot() {
        val px = N3dsNative.screenshot(FIELD_SHOT_W, FIELD_SHOT_H) ?: return
        val bmp = runCatching { android.graphics.Bitmap.createBitmap(px, FIELD_SHOT_W, FIELD_SHOT_H, android.graphics.Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        ui.post { onFieldShot(bmp) }
    }

    // ---- the companion's snapshot -------------------------------------------------------------------------------

    private val sampler = Thread({
        // The cart's icons into rom-art once (a few hundred small files), before the first sample needs them.
        runCatching {
            if (Gen6Icons.extractTo(rom, com.pokedaisy.app.companion.data.RomArt.dir(appFiles))) com.pokedaisy.app.companion.data.RomArt.announce()
        }.onFailure { Log.w(TAG, "icons failed", it) }
        val config = Gen6Reader.configFor(titleId)
        var text: Gen6Text? = null
        var textLanguage = -1
        val zones = Gen6Zones.load(rom)
        while (running) {
            if (config != null && !paused) {
                val sample = N3dsNative.readMemory(Gen6Reader.ranges(config))?.let { Gen6Reader.read(config, it) }
                if (sample != null) {
                    // Reloaded when the language changes: a read before the save is loaded (the title) has none yet.
                    if (text == null || sample.language != textLanguage) text = Gen6Text.load(rom, sample.language)?.also { t ->
                        textLanguage = sample.language
                        Gen6Text.languageLetter(sample.language)?.let { l -> ui.post { com.pokedaisy.app.companion.i18n.L10n.applyGameLanguage(l) } }
                        val names = t.items
                        OwnItemNames.lookup = { id -> names.getOrNull(id) }
                    }
                    val view = SnapshotView(
                        connected = true,
                        game = com.pokedaisy.app.companion.data.GameKind.POKEMON_X,
                        frameCounter = N3dsNative.frames(),
                        party = N3dsCompanionData.party(sample, text),
                        items = N3dsCompanionData.bag(sample, text),
                        location = N3dsCompanionData.location(sample, text, zones),
                    )
                    ui.post { onSnapshot(view) }
                }
            }
            try { Thread.sleep(SAMPLE_MS) } catch (_: InterruptedException) { break }
        }
    }, "pokedaisy-n3ds-sample")

    companion object {
        private const val TAG = "pokedaisy/n3ds"
        /** The 3D picture at 3x the 3DS's (the Thor / RP6 show the top screen at about 4x); a setting later. */
        private const val RESOLUTION = 3
        /** Azahar's Default layout: only used if the touch screen's surface were missing at boot (it never is here). */
        private const val ONE_SURFACE_LAYOUT = 0
        private const val STICK_DEAD = 0.12f
        private const val SAMPLE_MS = 1000L
        private const val SCENE_MS = 100L
        /** The field's picture every this many scene reads (a second), at the 3DS's own top-screen size. */
        private const val FIELD_SHOT_EVERY = 10
        private const val FIELD_SHOT_W = 400
        private const val FIELD_SHOT_H = 240
    }
}
