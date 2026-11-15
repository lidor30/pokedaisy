package com.pokedaisy.app.n3ds

import okio.Path.Companion.toOkioPath
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
    /** The battle's state changed ([battle]: its menu, the battlers), on the UI thread. */
    private val onBattle: (Gen6Reader.Battle?) -> Unit = {},
    /** The top screen in the field, about once a second (the backdrop of the game's own screens), on the UI thread. */
    private val onFieldShot: (android.graphics.Bitmap) -> Unit = {},
    /** [fieldHeld] changed (the UI thread). */
    private val onFieldHeld: (Boolean) -> Unit = {},
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
        camera.start()
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
        val sx = dead(event.getAxisValue(MotionEvent.AXIS_X))
        val sy = dead(event.getAxisValue(MotionEvent.AXIS_Y))
        // The circle pad goes to the game from the input pump, turned with the camera ([stickTurn]).
        stickX = sx
        stickY = -sy
        // The right stick (most pads Z / RZ, some RX / RY): the field camera's turn and tilt.
        fun axis(a: Int, b: Int): Float {
            val va = event.getAxisValue(a)
            val vb = event.getAxisValue(b)
            return dead(if (kotlin.math.abs(va) >= kotlin.math.abs(vb)) va else vb)
        }
        lookX = axis(MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)
        lookY = -axis(MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)
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
    @Volatile private var stickX = 0f
    @Volatile private var stickY = 0f
    private var lastPadX = Float.NaN
    private var lastPadY = Float.NaN

    private val pump = Thread({
        var last = -1
        var pass = 0
        var tap = 0
        while (running) {
            if (pass++ % 4 == 0) tap = input.tapMask()
            val now = buttons(input.mask or hatBits or tap)
            if (now != last) { N3dsNative.setButtons(now); last = now }
            // The circle pad turned as the camera is ([stickTurn]): up walks away from it, as in a modern game.
            val turn = Math.toRadians((stickTurn * STICK_TURN_SIGN).toDouble())
            val c = kotlin.math.cos(turn).toFloat()
            val sn = kotlin.math.sin(turn).toFloat()
            val px = stickX * c - stickY * sn
            val py = stickX * sn + stickY * c
            if (px != lastPadX || py != lastPadY) { N3dsNative.setStick(px, py); lastPadX = px; lastPadY = py }
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
        Thread {
            // A state keeps the game's own camera: the pulled-back distance comes off for the save and goes back after.
            val ok = synchronized(cameraLock) {
                cameraRestore()
                N3dsNative.saveState(file.absolutePath)
            }
            ui.post { done(ok) }
        }.start()
    }

    /** The top screen as it shows now, [width] px wide, into [dest] (a state's thumbnail, as the GBA games' are):
     * Azahar's own screenshot of the next frame, off the UI thread. (A PixelCopy of the surface came out see-through:
     * Azahar's frames carry alpha 0.) */
    fun writeThumb(dest: File, width: Int = 160) {
        if (!started) return
        Thread {
            val h = width * 3 / 5
            val px = screenshot(width, h) ?: return@Thread
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
    private var sceneTold = false

    /** The field camera may be pulled back (the app's ZOOM): the game is on its field (its module loaded, not the
     * title's default FIELD), the X menu over it included. */
    @Volatile var zoomable = false
        private set

    /** The game holds the player on the field ([Gen6Reader.Config.fieldHold]): its text box may be up; [onFieldHeld]. */
    @Volatile var fieldHeld = false
        private set
    private var freeReads = 0

    private val scenes = Thread({
        val config = Gen6Reader.configFor(titleId)
        var lastModules: String? = null
        // A change counts once two reads in a row agree (between two screens the modules come and go). Out of one of
        // the game's screens, the field counts only after a second of it: on the way back to the X menu the field's
        // module loads about a second before the menu is set again (the window went away and came back).
        var seen: Gen6Reader.Scene? = null
        var streak = 0
        var pass = 0
        while (running && config != null && config.staticModule != 0) {
            if (!paused) {
                val modules = N3dsNative.modules(config.staticModule)
                if (modules != null) {
                    if (modules != lastModules) { Log.d(TAG, "modules: ${modules.replace('\n', ',')}"); lastModules = modules }
                    val menu = if ("DllField" in modules) fieldMenu(config) else null
                    val now = Gen6Reader.sceneOf(modules, menu)
                    streak = if (now == seen) streak + 1 else 1
                    val needed = when {
                        scene == Gen6Reader.Scene.SCREEN && now == Gen6Reader.Scene.FIELD -> SCREEN_TO_FIELD_READS
                        // Save's question going (back to the menu) leaves the menu unset for a read or two.
                        scene == Gen6Reader.Scene.MENU && now == Gen6Reader.Scene.FIELD -> 3
                        else -> 2
                    }
                    // (The first scene read is told too: the field is the default, so it wouldn't be a change.)
                    if (now != null && (now != scene || !sceneTold) && streak >= needed) {
                        scene = now
                        sceneTold = true
                        Log.i(TAG, "scene: $now")
                        ui.post { onScene(now) }
                    }
                    seen = now
                    // The game holding the player (talking, a scene, a door): the HUD's bottom row steps away at once and
                    // comes back after a few free reads (no blink between two lines or through a door).
                    val heldNow = "DllField" in modules && config.fieldHold != 0 &&
                        (N3dsNative.readMemory(intArrayOf(config.fieldHold, 4))?.takeIf { it.size == 4 }?.let { it[0].toInt() and Gen6Reader.FIELD_HELD != 0 } == true)
                    freeReads = if (heldNow) 0 else freeReads + 1
                    val held = heldNow || freeReads < FIELD_FREE_READS
                    if (held != fieldHeld) { fieldHeld = held; ui.post { onFieldHeld(held) } }
                    // ZOOM's say: the field's module up and the scene the field (or the X menu over it).
                    val canZoom = "DllField" in modules &&
                        (scene == Gen6Reader.Scene.FIELD || scene == Gen6Reader.Scene.MENU)
                    zoomable = canZoom
                    readBattle(config, if ("DllBattle" in modules) modules else null)
                }
                if (pass++ % FIELD_SHOT_EVERY == 0 && scene == Gen6Reader.Scene.FIELD) fieldShot()
                if (com.pokedaisy.app.BuildConfig.DEBUG) debugCommands(pass % FIELD_SHOT_EVERY == 1)
            }
            try { Thread.sleep(SCENE_MS) } catch (_: InterruptedException) { break }
        }
    }, "pokedaisy-n3ds-scene")

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
                        val px = screenshot(320, 240, bottom = true)
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

    /** Debug builds: `files/debug/n3ds_dump` there = the game's heap (0x08000000, 15 MB) and data (0x005A6000) written
     * to `files/debug/n3ds-<time>-<base>.bin`, the marker deleted (finding addresses on a device: diff two dumps). */
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

    // ---- the field camera (ZOOM pulls it back, the right stick turns and tilts it) -----------------------------

    /** How far the field camera stands back, as a factor of the game's own distance (1 = the game's): ZOOM's steps,
     * set by the activity. Applied on the field only ([zoomable]), eased by [camera]. */
    @Volatile var cameraZoom = 1f

    /** The right stick, -1..1 (right and up positive): turns and tilts the field camera ([camera]). */
    @Volatile private var lookX = 0f
    @Volatile private var lookY = 0f

    /** The camera's turn now (degrees, the stick's, 0 off the field): the circle pad is turned by it in the pump. */
    @Volatile private var stickTurn = 0f

    private val cameraLock = Any()
    private var camSetup = 0                 // the camera setup's address (Gen6Reader.CAMERA_PATH) last seen
    private var camResolved = 0L             // when the path was last followed
    // Per CAM_FIELDS (pitch, yaw, distance): the game's own value, and the last one written (another value there = the
    // game set a new one, which becomes the base).
    private val camBase = FloatArray(CAM_FIELDS.size) { Float.NaN }
    private val camWritten = FloatArray(CAM_FIELDS.size) { Float.NaN }
    private var camShown = 1f                // the distance factor, eased toward [cameraZoom]
    private var yawOff = 0f                  // the stick's turn and tilt, degrees
    private var pitchOff = 0f
    private var camTick = 0L
    private var camZone = -1                 // the map the turn and tilt were set on (another map: back to the game's)

    /**
     * The camera's setup, rewritten from the game's own values: the distance times [camShown], the turn and tilt plus
     * the stick's (within [CAMERA_YAW_MAX], [CAMERA_PITCH_UP] / [CAMERA_PITCH_DOWN]: past them the map's edge and its unbuilt
     * sides show; Santalune Forest's top went black at 15° flatter).
     * Let go, the camera keeps its angle, walking too (the user's call: keep seeing round with it), and the circle pad is
     * turned with it ([stickTurn]); another map (the zone changes) starts again from the game's, which that map may need.
     * A battle or the game's own screens leave it as it was. Every 100 ms on the field, every frame while it moves.
     */
    private val camera = Thread({
        val config = Gen6Reader.configFor(titleId)
        while (running && config != null && config.fieldMenuRoot != 0) {
            var active = false
            if (!paused) synchronized(cameraLock) { active = cameraStep(config) }
            try { Thread.sleep(if (active) CAMERA_FRAME_MS else SCENE_MS) } catch (_: InterruptedException) { break }
        }
    }, "pokedaisy-n3ds-camera")

    private fun cameraStep(config: Gen6Reader.Config): Boolean {
        val now = System.nanoTime()
        val dt = if (camTick == 0L) 0.016f else ((now - camTick) / 1e9f).coerceIn(0f, 0.1f)
        camTick = now
        val field = zoomable
        // The path is followed again twice a second (and off the field): reading it costs a frame a step.
        if (camSetup == 0 || !field || now - camResolved > CAMERA_RESOLVE_NS) {
            val setup = if (field) cameraSetup(config) else 0
            camResolved = now
            if (setup == 0) { camSetup = 0; stickTurn = 0f; return false }
            // (A new setup keeps the bases: a value equal to the one written is still ours, another is the game's.)
            camSetup = setup
            val zone = N3dsNative.readMemory(intArrayOf(config.zone, 2))?.takeIf { it.size == 2 }
                ?.let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
            if (zone != null && zone != camZone) {
                if (camZone != -1) { yawOff = 0f; pitchOff = 0f }
                camZone = zone
            }
        }
        val setup = camSetup
        val block = N3dsNative.readMemory(intArrayOf(setup, CAM_BLOCK))?.takeIf { it.size == CAM_BLOCK } ?: return false
        val buf = java.nio.ByteBuffer.wrap(block).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val values = FloatArray(CAM_FIELDS.size) { buf.getFloat(CAM_FIELDS[it]) }
        // A distance of 0 or less, or a tilt not looking down: not the setup (memory reused), read again next time.
        if (!(values[CAM_DISTANCE] > 0f) || !(values[CAM_PITCH] < 0f)) { camResolved = 0L; camSetup = 0; return false }
        for (k in values.indices) if (camBase[k].isNaN() || values[k] != camWritten[k]) camBase[k] = values[k]

        val want = if (field) cameraZoom else 1f
        val zoomStep = CAMERA_ZOOM_RATE * dt
        camShown = if (kotlin.math.abs(want - camShown) <= zoomStep) want else camShown + kotlin.math.sign(want - camShown) * zoomStep
        val lx = if (field) lookX else 0f
        val ly = if (field) lookY else 0f
        val looking = lx != 0f || ly != 0f
        if (looking) {
            yawOff = (yawOff + lx * CAMERA_YAW_SPEED * dt).coerceIn(-CAMERA_YAW_MAX, CAMERA_YAW_MAX)
            pitchOff = (pitchOff + ly * CAMERA_PITCH_SPEED * dt).coerceIn(-CAMERA_PITCH_DOWN, CAMERA_PITCH_UP)
        }
        stickTurn = yawOff
        val targets = floatArrayOf(
            (camBase[CAM_PITCH] + pitchOff).coerceIn(CAMERA_PITCH_LOWEST, CAMERA_PITCH_HIGHEST),
            camBase[CAM_YAW] + yawOff,
            camBase[CAM_DISTANCE] * camShown,
        )
        val untouched = yawOff == 0f && pitchOff == 0f && camShown == 1f
        // The game's own camera and nothing of ours left in it: leave it alone.
        if (untouched && camWritten.all { it.isNaN() }) return false
        if (targets.indices.any { targets[it] != values[it] }) {
            for (k in targets.indices) buf.putFloat(CAM_FIELDS[k], targets[k])
            // One write for the three (pitch at +4 to the distance at +0x1C), the fields between as read.
            N3dsNative.writeMemory(setup + CAM_FIELDS[CAM_PITCH], block.copyOfRange(CAM_FIELDS[CAM_PITCH], CAM_BLOCK))
            for (k in targets.indices) camWritten[k] = targets[k]
        }
        if (untouched) camWritten.fill(Float.NaN)
        return looking || camShown != want
    }

    /** The game's own camera written back (before a state is saved); the loop puts the zoom and the turn back after. */
    private fun cameraRestore() {
        val setup = camSetup
        if (setup == 0 || camWritten.all { it.isNaN() } || camBase.any { it.isNaN() }) return
        val block = N3dsNative.readMemory(intArrayOf(setup, CAM_BLOCK))?.takeIf { it.size == CAM_BLOCK } ?: return
        val buf = java.nio.ByteBuffer.wrap(block).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (k in CAM_FIELDS.indices) buf.putFloat(CAM_FIELDS[k], camBase[k])
        N3dsNative.writeMemory(setup + CAM_FIELDS[CAM_PITCH], block.copyOfRange(CAM_FIELDS[CAM_PITCH], CAM_BLOCK))
        for (k in CAM_FIELDS.indices) camWritten[k] = camBase[k]
    }

    /** The field camera's setup ([Gen6Reader.cameraSetup]): the field object's pointers followed, 0 off the field. */
    private fun cameraSetup(config: Gen6Reader.Config): Int {
        var a = readInt(config.fieldMenuRoot) ?: return 0
        for (off in Gen6Reader.CAMERA_PATH) {
            if (!Gen6Reader.inHeap(a)) return 0
            a = readInt(a + off) ?: return 0
        }
        return if (Gen6Reader.inHeap(a)) a + Gen6Reader.CAMERA_SETUP else 0
    }

    private fun readInt(addr: Int): Int? = N3dsNative.readMemory(intArrayOf(addr, 4))?.takeIf { it.size == 4 }
        ?.let { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0) }

    private fun floatBytes(v: Float): ByteArray =
        java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(v).array()

    // ---- battles -------------------------------------------------------------------------------------------------

    /** The battle as last read ([Gen6Reader.Battle]); null out of battle. */
    @Volatile var battle: Gen6Reader.Battle? = null
        private set

    /** The battle strip's picks: the game's touch-screen buttons pressed ([N3dsBattleTaps]). */
    val battleTaps = N3dsBattleTaps({ battle?.input ?: com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE }, ::touchScreen)

    /** The scene thread's battle read: its controller, then (with a command panel) the two battlers' records. */
    private fun readBattle(config: Gen6Reader.Config, battleModules: String?) {
        val now = if (battleModules == null || config.battleUi == 0) null else {
            val ui = N3dsNative.readMemory(intArrayOf(config.battleUi, Gen6Reader.BATTLE_UI_BYTES))
            val panel = Gen6Reader.commandPanel(ui)
            var player: Gen6Reader.BattleMon? = battle?.player
            var foe: Gen6Reader.BattleMon? = battle?.foe
            if (panel != 0) {
                val ptrs = N3dsNative.readMemory(intArrayOf(panel + config.panelPlayer, 4, panel + config.panelFoe, 4))
                if (ptrs != null && ptrs.size >= 8) {
                    val b = java.nio.ByteBuffer.wrap(ptrs).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val p = b.getInt(0).takeIf(Gen6Reader::inHeap)
                    val f = b.getInt(4).takeIf(Gen6Reader::inHeap)
                    val ranges = listOfNotNull(p, f).flatMap { listOf(it, Gen6Reader.BATTLE_MON_BYTES) }.toIntArray()
                    val recs = if (ranges.isEmpty()) null else N3dsNative.readMemory(ranges)
                    if (recs != null && recs.size >= ranges.size / 2 * Gen6Reader.BATTLE_MON_BYTES) {
                        var at = 0
                        fun next() = recs.copyOfRange(at, at + Gen6Reader.BATTLE_MON_BYTES).also { at += Gen6Reader.BATTLE_MON_BYTES }
                        if (p != null) player = Gen6Reader.battleMon(next())
                        if (f != null) foe = Gen6Reader.battleMon(next())
                    }
                }
            }
            Gen6Reader.Battle(Gen6Reader.battleInput(ui), player, foe)
        }
        if (now == battle) return
        // The cart's move and species tables, read here on the scene thread, never by the UI's first battle snapshot.
        if (now != null) { moveData; personalData }
        battle = now
        ui.post { onBattle(now) }
        publishBattle()
    }

    /** The last snapshot with [battle]'s fields: the strip follows the battle at once, not at the next sample. */
    private fun publishBattle() {
        val view = lastView ?: return
        ui.post { onSnapshot(withBattle(view)) }
    }

    @Volatile private var lastView: SnapshotView? = null

    /** The wild Pokemon of the zone the player is in, for the HUD's WILD ([Gen6Wild]); empty where there are none. */
    @Volatile var wildHere: List<com.pokedaisy.app.companion.ui.XyWildRow> = emptyList()
        private set
    private var wildZone = -1

    /** The player's place on the Town Map ([Gen6TownMap]), for the HUD's MAP; null off the map (or unread). */
    @Volatile var mapHere: com.pokedaisy.app.companion.ui.XyMapSpot? = null
        private set
    private var wildLanguage = -1
    @Volatile private var text: Gen6Text? = null
    private val moveData by lazy { Gen6Moves.load(rom) }
    private val personalData by lazy { Gen6Personal.load(rom) }

    private fun withBattle(view: SnapshotView): SnapshotView {
        val b = battle ?: return view.copy(inBattle = false, battlePlayer = emptyList(), battleOpponent = emptyList(),
            battleInputState = com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE)
        val t = text
        return view.copy(
            inBattle = true,
            battlePlayer = listOfNotNull(b.player?.let { N3dsCompanionData.battleMon(it, t, moveData, personalData, b.foe) }),
            battleOpponent = listOfNotNull(b.foe?.let { N3dsCompanionData.battleMon(it, t, moveData, personalData) }),
            battleInputState = b.input,
        )
    }

    /** The touch screen pressed at ([x], [y]) of its 320 x 240 (or let go), wherever its surface is laid out. */
    private fun touchScreen(down: Boolean, x: Int, y: Int) {
        if (!started) return
        if (!down) { N3dsNative.touch(1, 0, 0, N3dsNative.TOUCH_UP); return }
        val w = touchView.width.takeIf { it > 0 } ?: return
        val h = touchView.height.takeIf { it > 0 } ?: return
        N3dsNative.touch(1, x * w / 320, y * h / 240, N3dsNative.TOUCH_DOWN)
    }

    /** Azahar takes one screenshot at a time (a second one while one is pending gets nothing, and they'd share its
     * buffer): every taker here goes through this, one after the other, trying again a moment later if the game's
     * renderer still had one pending. A state's thumbnail once lost to the field picture and was never written. */
    private val shotLock = Any()

    /** The field menu's bytes ([Gen6Reader.sceneOf]): the field's object from its root, then the pointer and flag in it. */
    private fun fieldMenu(config: Gen6Reader.Config): ByteArray? {
        if (config.fieldMenuRoot == 0) return null
        val root = N3dsNative.readMemory(intArrayOf(config.fieldMenuRoot, 4))?.takeIf { it.size == 4 } ?: return null
        val obj = java.nio.ByteBuffer.wrap(root).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0)
        if (obj !in 0x08000000 until 0x0A000000) return null
        return N3dsNative.readMemory(intArrayOf(obj + config.fieldMenuOffset, Gen6Reader.FIELD_MENU_BYTES))
    }
    private fun screenshot(w: Int, h: Int, bottom: Boolean = false): IntArray? = synchronized(shotLock) {
        repeat(SHOT_TRIES) { i ->
            N3dsNative.screenshot(w, h, bottom)?.let { return it }
            if (!running) return null
            if (i < SHOT_TRIES - 1) Thread.sleep(SHOT_RETRY_MS)
        }
        null
    }

    private fun fieldShot() {
        val px = screenshot(FIELD_SHOT_W, FIELD_SHOT_H) ?: return
        // Not a fade's frame (a door, a screen opening): the backdrop would be black. Every 7th pixel's brightness.
        var sum = 0L
        var n = 0
        for (i in px.indices step 7) { val c = px[i]; sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF); n++ }
        if (n == 0 || sum / (3 * n) < FIELD_SHOT_DARK) return
        val bmp = runCatching { android.graphics.Bitmap.createBitmap(px, FIELD_SHOT_W, FIELD_SHOT_H, android.graphics.Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        ui.post { onFieldShot(bmp) }
    }

    // ---- the companion's snapshot -------------------------------------------------------------------------------

    private val sampler = Thread({
        // The cart's icons into rom-art once (a few hundred small files), before the first sample needs them.
        runCatching {
            if (Gen6Icons.extractTo(rom, com.pokedaisy.app.companion.data.RomArt.dir(appFiles.toOkioPath()).toFile())) com.pokedaisy.app.companion.data.RomArt.announce()
        }.onFailure { Log.w(TAG, "icons failed", it) }
        // The Town Map's picture and pin (once per cart), and every zone's place on it.
        val townSpots = runCatching {
            if (Gen6TownMap.extractTo(rom, com.pokedaisy.app.companion.data.RomArt.dir(appFiles.toOkioPath()).toFile())) com.pokedaisy.app.companion.data.RomArt.announce()
            Gen6TownMap.spots(rom)
        }.onFailure { Log.w(TAG, "town map failed", it) }.getOrDefault(emptyMap())
        val config = Gen6Reader.configFor(titleId)
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
                    // The HUD's WILD list follows the zone (built here, off the UI thread: the cart's zone archive).
                    if (sample.zone != wildZone || wildLanguage != textLanguage) {
                        wildZone = sample.zone
                        wildLanguage = textLanguage
                        wildHere = N3dsCompanionData.wild(Gen6Wild.load(rom)?.getOrNull(sample.zone).orEmpty(), text)
                        mapHere = townSpots[sample.zone]?.let {
                            com.pokedaisy.app.companion.ui.XyMapSpot(
                                Gen6TownMap.MAP, Gen6TownMap.PIN, Gen6TownMap.WIDTH, Gen6TownMap.HEIGHT, it.x, it.y, it.w, it.h,
                            )
                        }
                    }
                    val view = SnapshotView(
                        connected = true,
                        game = com.pokedaisy.app.companion.data.GameKind.POKEMON_X,
                        frameCounter = N3dsNative.frames(),
                        party = N3dsCompanionData.party(sample, text, moveData, personalData),
                        items = N3dsCompanionData.bag(sample, text),
                        location = N3dsCompanionData.location(sample, text, zones),
                    )
                    lastView = view
                    val shown = withBattle(view)
                    ui.post { onSnapshot(shown) }
                }
            }
            try { Thread.sleep(SAMPLE_MS) } catch (_: InterruptedException) { break }
        }
    }, "pokedaisy-n3ds-sample")

    companion object {
        private const val TAG = "pokedaisy/n3ds"
        /** Free reads (100 ms each) before the HUD's bottom row comes back. */
        private const val FIELD_FREE_READS = 3
        /** The camera's ease: this much of the factor a frame (about a quarter second from 1x to the farthest step). */
        private const val CAMERA_FRAME_MS = 16L
        private const val CAMERA_RESOLVE_NS = 500_000_000L
        /** The setup's fields written ([Gen6Reader.CAMERA_SETUP]): pitch and yaw in degrees, the distance; read as one
         * block of [CAM_BLOCK] bytes from the setup's start. */
        private val CAM_FIELDS = intArrayOf(0x4, 0x8, Gen6Reader.CAMERA_DISTANCE)
        private const val CAM_PITCH = 0
        private const val CAM_YAW = 1
        private const val CAM_DISTANCE = 2
        private const val CAM_BLOCK = 0x20
        /** ZOOM's factor eases this much a second. */
        private const val CAMERA_ZOOM_RATE = 3f
        /** The right stick, fully pushed: degrees a second; how far it turns / tilts from the game's angle; and the tilt's
         * bounds (degrees, negative = looking down). */
        private const val CAMERA_YAW_SPEED = 90f
        private const val CAMERA_PITCH_SPEED = 45f
        private const val CAMERA_YAW_MAX = 40f
        private const val CAMERA_PITCH_UP = 8f
        private const val CAMERA_PITCH_DOWN = 15f
        private const val CAMERA_PITCH_LOWEST = -75f
        private const val CAMERA_PITCH_HIGHEST = -12f
        /** Which way the circle pad turns with a positive camera turn (checked on the RP6). */
        private const val STICK_TURN_SIGN = -1f
        /** The 3D picture at 3x the 3DS's (the Thor / RP6 show the top screen at about 4x); a setting later. */
        private const val RESOLUTION = 3
        /** Azahar's Default layout: only used if the touch screen's surface were missing at boot (it never is here). */
        private const val ONE_SURFACE_LAYOUT = 0
        private const val STICK_DEAD = 0.12f
        private const val SAMPLE_MS = 1000L
        private const val SCENE_MS = 100L
        /** The field's picture every this many scene reads (a second), at the 3DS's own top-screen size. */
        private const val FIELD_SHOT_EVERY = 10
        /** Scene reads the field must hold coming out of one of the game's screens (see the scene thread). */
        private const val SCREEN_TO_FIELD_READS = 11
        /** [screenshot]'s tries, a frame or so apart. */
        private const val SHOT_TRIES = 3
        private const val SHOT_RETRY_MS = 20L
        /** A field picture darker than this on average (0-255) is a fade's, not kept. */
        private const val FIELD_SHOT_DARK = 24
        private const val FIELD_SHOT_W = 400
        private const val FIELD_SHOT_H = 240
    }
}
