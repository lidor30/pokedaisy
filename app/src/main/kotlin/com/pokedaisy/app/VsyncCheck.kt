package com.pokedaisy.app

/**
 * Whether the GL thread's ticks ([EmulatorEngine.onVsync]) really follow the screen's refresh (GitHub #350). They come
 * once per draw, and a draw waits on the swap, which waits on the refresh - on most devices. The Anbernic RG DS
 * (GammaOS, Android 14) swaps without waiting: ~400 ticks a second on a 60 Hz screen, its measured "refresh" anywhere
 * from 70 to 205 Hz, vsync pacing switched on and off on a made-up rate (180 / 3 = 60) and 1x ran at 25-60 fps.
 *
 * Counted in windows of [WINDOW_NANOS]: a window strikes when its ticks per second
 * - outrun the screen: over [FAST] x the fastest mode the display reports (or [UNKNOWN_MAX_HZ] when it reports none)
 *   and not steady (within [STEADY] of the last window's), or over [FAR] x that mode whatever it does - a screen that
 *   runs steadily faster than the modes it reports still ticks with its refresh, which vsync pacing measures; or
 * - jump more than [JUMP] from the last window's while one of the two is past [OVER] x that mode (unknown: both over
 *   [STARVED_HZ]), a rate that never settles. A screen going 60 -> 120 Hz isn't over its own fastest mode, and a GL
 *   thread too busy to keep up only ticks slower, so neither strikes.
 * [STRIKES] of the last [RECENT] windows and it's [unreliable]. It isn't for good (1.5.0 kept it for the process, and a
 * false strike left a smooth device on the timer, choppy at 60 fps): [recoverWindows] clean windows in a row and it's
 * reliable again, the next stretch twice as long ([RECOVER_WINDOWS] doubling up to [MAX_RECOVER_WINDOWS]), so a device
 * that strikes now and then doesn't flip every few seconds. A gap of over [GAP_NANOS] (the view paused, the screen off)
 * starts the window over and isn't counted.
 *
 * GL thread only (the engine reads [unreliable] from elsewhere through its own volatile copy). One per process (the
 * engine's companion), so a game started again keeps the finding.
 */
internal class VsyncCheck {
    var unreliable = false
        private set
    /** Why, for the log; null while it's reliable. */
    var reason: String? = null
        private set
    /** The last whole window's ticks per second (0 = none yet), for the fps line. */
    @Volatile var lastRate = 0.0
        private set
    /** Clean windows in a row it takes to be reliable again after a finding. */
    var recoverWindows = RECOVER_WINDOWS
        private set

    private var windowStart = 0L
    private var lastTick = 0L
    private var ticks = 0
    private var prevRate = 0.0
    private val recent = ArrayDeque<Boolean>()
    private var clean = 0

    /** One tick at [now] (System.nanoTime); [displayMaxHz] = the display's fastest mode (0 = unknown), asked once a
     * window. Returns [unreliable]. */
    fun tick(now: Long, displayMaxHz: () -> Float): Boolean {
        if (lastTick == 0L || now - lastTick > GAP_NANOS) {
            lastTick = now
            windowStart = now
            ticks = 0
            prevRate = 0.0   // a rate from before a pause isn't this one's neighbour
            return unreliable
        }
        lastTick = now
        ticks++
        val elapsed = now - windowStart
        if (elapsed < WINDOW_NANOS) return unreliable
        val rate = ticks * 1e9 / elapsed
        windowStart = now
        ticks = 0
        lastRate = rate
        val max = displayMaxHz().toDouble()
        val steady = prevRate > 0 && kotlin.math.abs(rate / prevRate - 1.0) <= STEADY
        val fast = if (max > 0) rate > max * FAR || (rate > max * FAST && !steady) else rate > UNKNOWN_MAX_HZ
        val jumpy = prevRate > 0 && kotlin.math.abs(rate / prevRate - 1.0) > JUMP &&
            (if (max > 0) maxOf(rate, prevRate) > max * OVER else minOf(rate, prevRate) > STARVED_HZ)
        prevRate = rate
        val strike = fast || jumpy
        if (unreliable) {
            clean = if (strike) 0 else clean + 1
            if (clean >= recoverWindows) {
                unreliable = false
                reason = null
                recent.clear()
                clean = 0
                recoverWindows = (recoverWindows * 2).coerceAtMost(MAX_RECOVER_WINDOWS)
            }
            return unreliable
        }
        recent.addLast(strike)
        if (recent.size > RECENT) recent.removeFirst()
        if (recent.count { it } >= STRIKES) {
            unreliable = true
            clean = 0
            val screen = if (max > 0) "%.0f Hz screen".format(java.util.Locale.ROOT, max) else "screen of unknown rate"
            reason = "%.0f ticks/s %s on a %s".format(java.util.Locale.ROOT, rate, if (fast) "(faster than it)" else "(never settling)", screen)
        }
        return unreliable
    }

    companion object {
        const val WINDOW_NANOS = 1_000_000_000L
        const val GAP_NANOS = 100_000_000L
        const val FAST = 1.25
        const val FAR = 2.1
        const val STEADY = 0.05
        const val OVER = 1.1
        const val JUMP = 0.25
        const val UNKNOWN_MAX_HZ = 250.0
        const val STARVED_HZ = 50.0
        const val STRIKES = 2
        const val RECENT = 4
        const val RECOVER_WINDOWS = 10
        const val MAX_RECOVER_WINDOWS = 160
    }
}
