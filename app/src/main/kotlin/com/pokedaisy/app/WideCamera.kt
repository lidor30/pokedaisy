package com.pokedaisy.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * ZOOM and the look-around, over WIDE ([WideScreen]): which window of the scene the game view shows each frame.
 * pk_wide.c draws any window around the game's own frame - the GBA's 240x160, a Game Boy's 160x144 ([frameW] /
 * [frameH], pk_wide_gb.c) - ([MgbaCore.pkWideSetView]); this picks it.
 *
 * - **Zoom steps** ([level]): 1x is the game as large as the view shows all of it - its full height on a wide screen,
 *   its full width on a tall one (a phone upright: the map then goes on above and below); each step out is a whole number of screen
 *   pixels per GBA pixel, so the pixel art stays crisp (the Thor's 1080 px: 6.75 -> 5 / 4 / 3, about 1.35x /
 *   1.7x / 2.25x the map). Only steps showing at least 1.2x as much, inside pk_wide.h's largest window.
 * - **Close up when it matters**: the game wanting it ([MgbaCore.pkWideScene]: a battle starting, a menu screen, a
 *   text box, a picture over the field) pulls in to 1x and the player - one timed, eased motion per event, begun the
 *   frame the game decides (a battle's flashes, a sign's text box) so it's over before anything slides; the chosen
 *   step comes back, as gently, once that's been over for [RETURN_DELAY_NANOS].
 * - **Look-around** (the right stick, [stick]): standing, it moves the view, which stays where it's left; walking
 *   ([walking]) glides it back to the player - or, with the stick held, follows the stick but always keeps the
 *   player on screen.
 *
 * Inputs come from any thread; [frame] runs on the emu thread before each frame, its result is [current].
 */
class WideCamera {
    /** The game view's size, in screen pixels. */
    @Volatile var viewW = 0
    @Volatile var viewH = 0

    /** The zoom step the player picked: 0 = 1x, up to [levels]' last. */
    @Volatile var level = 0

    /** The game frame's size ([PK_W] x [PK_H] on a GBA, a Game Boy's 160 x 144, 256 x 192 on a DS) and how far past it the window may go
     * ([marginX] / [marginY] a side, -1 = as far as [MAX_W] x [MAX_H] allow): a DS game's EXPAND draws at most
     * pk_nds_wide.h's margins ([ndsMode]). */
    @Volatile var frameW = PK_W
    @Volatile var frameH = PK_H
    @Volatile var marginX = -1
    @Volatile var marginY = -1

    /** A DS game's frame and limits ([frameW], [frameH], [marginX], [marginY]); [on] false is a GBA / Game Boy game's. */
    fun ndsMode(on: Boolean) {
        frameW = if (on) NDS_W else PK_W
        frameH = if (on) NDS_H else PK_H
        marginX = if (on) NDS_MARGIN_X else -1
        marginY = if (on) NDS_MARGIN_Y else -1
        reset()
    }

    private fun maxW() = if (marginX < 0) MAX_W else frameW + 2 * marginX
    private fun maxH() = if (marginY < 0) MAX_H else frameH + 2 * marginY

    /** The right stick, -1..1 per axis (right / down positive). */
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f

    /** A direction is held: the player is walking (or trying to). */
    @Volatile var walking = false

    /** SETTINGS > AUTO ZOOM IN: whether a text box pulls in to 1x ([SCENE_TEXT]); battles, the game's menu screens
     * ([SCENE_MENU]: a zoomed-out one sat small in the middle) and anything else off the map always do, a picture over
     * the field never. */
    @Volatile var zoomOnText = true
    /** The scenic FLY is in the air (EmulatorEngine.scenicFlight): the widest step, whatever the player picked, and the
     * map's name sign over the field (the controls are locked, so it reads as text) leaves it out there. */
    @Volatile var flightZoom = false
    /** Any flight is up (FREE FLIGHT too): the map's name sign crossing into a new area (the controls are locked, so it
     * reads as a text box) never pulls the zoom in. */
    @Volatile var flying = false
    /** LOOK AROUND off: the right stick does nothing. */
    @Volatile var lookAround = true

    /** What the screen shows: the window pk_wide drew ([x], [y], [w], [h] in the frame's GBA pixels), placed on the
     * view at ([offX], [offY]) screen pixels from its top-left (<= 0: the window's whole pixels reach past the
     * view by the fraction the look-around / zoom has moved), [scale] screen pixels per GBA pixel. */
    data class Window(val x: Int, val y: Int, val w: Int, val h: Int, val offX: Float, val offY: Float, val scale: Float)

    @Volatile var current: Window? = null
        private set

    private var scale = 0f
    private var lookX = 0f
    private var lookY = 0f
    private var lastNanos = 0L
    private var closeOverSince = 0L   // when the game last stopped wanting a close view (0 = it still does)
    private var tweenFrom = 0f        // the zoom's current motion: from this scale to [tweenTarget] ...
    private var tweenTarget = 0f
    private var tweenStart = 0L       // ... begun then, over [tweenNanos]
    private var tweenNanos = 0L

    /** Screen pixels per GBA pixel for each zoom step, 1x first. */
    fun levels(): FloatArray = levelsFor(viewW, viewH, frameW, frameH, maxW(), maxH())

    /** How much more of the map step [i] shows than 1x, for the HUD ("1.7x"). */
    fun factor(i: Int): Float {
        val l = levels()
        return if (i in l.indices) l[0] / l[i] else 1f
    }

    /** The next step out, back to 1x after the last; returns the new step. */
    fun cycle(): Int {
        val n = levels().size
        level = if (n <= 1) 0 else (level + 1) % n
        return level
    }

    /** Back to 1x on the player at once (a new game, a state load, WIDE switched on); the [level] picked comes back
     * [RETURN_DELAY_NANOS] after the field shows, eased out like any return from a close view (a game opened with a
     * saved ZOOM: once it's loaded, not over its title). */
    fun reset() {
        closeOverSince = 0L
        scale = 0f
        lookX = 0f
        lookY = 0f
        lastNanos = 0L
        tweenTarget = 0f
    }

    /** Emu thread, before a frame: the window to draw ([MgbaCore.pkWideSetView]) given what the last frame was
     * ([SCENE_FIELD] ... [SCENE_OFF], [MgbaCore.pkWideScene]). Null without a view yet. */
    fun frame(scene: Int, now: Long = System.nanoTime()): Window? {
        val vw = viewW
        val vh = viewH
        if (vw <= 0 || vh <= 0 || frameW <= 0 || frameH <= 0) return null.also { current = null }
        val steps = levels()
        val fit = steps[0]
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = now

        val wantsClose = when (scene) {
            SCENE_FIELD -> false
            SCENE_TEXT -> zoomOnText && !flying && !flightZoom
            else -> true
        }
        // A fade decides nothing; nor does a picture over the field (FireRed's map preview, ~2 s): a pull-in and a
        // zoom back out around it was motion for nothing - the card shows over the dimmed world at whatever zoom.
        if (scene == SCENE_FADE || scene == SCENE_OVERLAY) Unit
        else if (wantsClose) closeOverSince = 0L
        else if (closeOverSince == 0L) closeOverSince = now
        val close = closeOverSince == 0L || now - closeOverSince < RETURN_DELAY_NANOS
        val target = if (close) fit else if (flightZoom) steps.last() else steps[level.coerceIn(0, steps.size - 1)]
        // The zoom is a timed ease-in-out in log space (the same feel at every step): a new target starts a new
        // motion from wherever the last one is - a pull-in at the pace of what's coming, the way back out slower.
        if (target != tweenTarget) {
            tweenFrom = if (scale <= 0f) target else scale
            tweenTarget = target
            tweenStart = now
            tweenNanos = when {
                scale <= 0f -> 0L
                target > tweenFrom && scene == SCENE_BATTLE_START -> PULL_IN_BATTLE_NANOS
                target > tweenFrom -> PULL_IN_NANOS
                else -> ZOOM_OUT_NANOS
            }
        }
        val p = if (tweenNanos <= 0L) 1f else ((now - tweenStart).toFloat() / tweenNanos).coerceIn(0f, 1f)
        scale = tweenFrom * (tweenTarget / tweenFrom).pow(smoothstep(p))
        if (abs(scale - target) < 0.002f) scale = target

        val fw = vw / scale
        val fh = vh / scale
        val sx = if (lookAround) deadZone(stickX) else 0f
        val sy = if (lookAround) deadZone(stickY) else 0f
        val stick = sx != 0f || sy != 0f
        when {
            close -> spring(0f, 0f, dt, LOOK_TAU)
            walking && stick -> {
                // Follow the stick, the player kept on screen: the view's centre at most this far off them.
                val maxX = max(0f, fw / 2 - PLAYER_KEEP)
                val maxY = max(0f, fh / 2 - PLAYER_KEEP)
                spring(sx * maxX, sy * maxY, dt, LOOK_TAU)
            }
            walking -> spring(0f, 0f, dt, RETURN_TAU)
            stick -> {
                val speed = LOOK_SPEED * max(1f, fit / scale)   // further out, the view travels faster
                lookX += sx * speed * dt
                lookY += sy * speed * dt
            }
        }
        // As far as a screen's worth of map past the game's own view each way - a DS game's, only as far as its
        // margins go.
        val limX = if (marginX < 0) max(fw, frameW.toFloat()) else max(0f, frameW / 2f + marginX - fw / 2)
        val limY = if (marginY < 0) max(fh, frameH.toFloat()) else max(0f, frameH / 2f + marginY - fh / 2)
        lookX = lookX.coerceIn(-limX, limX)
        lookY = lookY.coerceIn(-limY, limY)

        val fx = frameW / 2f + lookX - fw / 2
        val fy = frameH / 2f + lookY - fh / 2
        val x = floor(fx).toInt()
        val y = floor(fy).toInt()
        val w = min(maxW(), ceil(fx + fw).toInt() - x)
        val h = min(maxH(), ceil(fy + fh).toInt() - y)
        return Window(x, y, w, h, (x - fx) * scale, (y - fy) * scale, scale).also { current = it }
    }

    private fun spring(tx: Float, ty: Float, dt: Float, tau: Float) {
        val k = ease(dt, tau)
        lookX += (tx - lookX) * k
        lookY += (ty - lookY) * k
        if (hypot(lookX - tx, lookY - ty) < 0.05f) { lookX = tx; lookY = ty }
    }

    companion object {
        /** What the last frame was (pk_wide.h's PK_WIDE_SCENE_*): the plain overworld; the field with a text box or
         * a line effect up; a picture over the field (FireRed's map preview); a battle's transition starting; off the
         * map altogether (a battle, a menu screen); a black / white frame between two maps or screens, which changes
         * nothing - the zoom holds through a door's fade instead of pulling in and back out. */
        const val SCENE_FIELD = 0
        const val SCENE_TEXT = 1
        const val SCENE_OVERLAY = 2
        const val SCENE_BATTLE_START = 3
        const val SCENE_OFF = 4
        const val SCENE_FADE = 5
        const val SCENE_MENU = 6

        /** The GBA's frame (a Game Boy game's is [GB_W] x [GB_H]) and pk_wide.h's largest window (PK_WIDE_MAX_VIEW_W / _H). */
        const val PK_W = 240
        const val PK_H = 160
        const val GB_W = 160
        const val GB_H = 144
        /** GBA px per side drawn if the camera ever fails: plain strips, as before ZOOM. */
        const val MARGIN_FALLBACK = 22
        const val MAX_W = 800
        const val MAX_H = 800
        /** A DS game's top screen and pk_nds_wide.h's margins (PK_NDS_WIDE_MAX_MARGIN_X / _Y). */
        const val NDS_W = 256
        const val NDS_H = 192
        const val NDS_MARGIN_X = 248
        const val NDS_MARGIN_Y = 96

        /** A step out must show at least this much more of the map than the one before it. */
        private const val MIN_STEP = 1.2f
        /** The pull-in to 1x: a text box or a menu in under half a second; a battle over its first flashes (they last
         * ~0.8 s, the wipe follows) - still before anything slides. The way back out, slower. */
        private const val PULL_IN_NANOS = 420_000_000L
        private const val PULL_IN_BATTLE_NANOS = 550_000_000L
        private const val ZOOM_OUT_NANOS = 650_000_000L
        private const val LOOK_TAU = 0.12f      // s: the view following the stick / going back for a close-up
        private const val RETURN_TAU = 0.18f    // s: the view gliding back to a player who starts walking
        private const val LOOK_SPEED = 150f     // GBA px per s at full stick, at 1x
        private const val PLAYER_KEEP = 28f     // GBA px: the player's sprite stays at least this far inside
        private const val DEAD_ZONE = 0.2f
        val RETURN_DELAY_NANOS = 500_000_000L   // the close view is over for this long before the zoom goes back out

        /** Screen pixels per game pixel for each step on a [w] x [h] view: 1x (the whole [frameW] x [frameH] frame as
         * large as it fits), then the whole numbers below it that show at least [MIN_STEP] more each and fit the largest
         * window ([maxW] x [maxH]: pk_wide's, or a DS game's frame and margins). */
        fun levelsFor(w: Int, h: Int, frameW: Int = PK_W, frameH: Int = PK_H, maxW: Int = MAX_W, maxH: Int = MAX_H): FloatArray {
            if (w <= 0 || h <= 0) return floatArrayOf(1f)
            val fit = min(w / frameW.toFloat(), h / frameH.toFloat())
            val out = arrayListOf(fit)
            var k = floor(fit).toInt()
            if (k.toFloat() == fit) k--
            while (k >= 1) {
                if (w / k.toFloat() > maxW || h / k.toFloat() > maxH) break
                if (out.last() / k >= MIN_STEP) out += k.toFloat()
                k--
            }
            return out.toFloatArray()
        }

        private fun ease(dt: Float, tau: Float) = 1f - exp(-dt / tau)

        private fun smoothstep(p: Float) = p * p * (3f - 2f * p)

        private fun deadZone(v: Float): Float {
            val a = abs(v)
            if (a < DEAD_ZONE) return 0f
            return (a - DEAD_ZONE) / (1f - DEAD_ZONE) * if (v < 0) -1f else 1f
        }
    }
}
