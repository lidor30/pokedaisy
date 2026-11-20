package com.pokedaisy.app.game

/**
 * How many game frames each display refresh runs, so the game keeps its own speed ([FPS], a GBA's and a Game Boy's
 * 59.73 a second) whatever the display's refresh is: the time since the last refresh adds up, and each whole frame's
 * worth runs. A long gap (the app was away, a stall) owes at most [MAX_CATCH_UP] real-time frames, scaled by the requested speed, and drops the rest.
 */
class FramePacer {
    private var lastSeconds = -1.0
    private var owed = 0.0

    /** Frames to run at a refresh shown at [nowSeconds] (any clock that counts seconds). */
    fun framesAt(nowSeconds: Double, speed: Double = 1.0): Int {
        require(speed.isFinite() && speed > 0.0 && speed <= 64.0)
        if (lastSeconds < 0) {
            lastSeconds = nowSeconds
            return 1
        }
        val elapsed = (nowSeconds - lastSeconds).coerceAtLeast(0.0)
        lastSeconds = nowSeconds
        // Bound a stall in real time before scaling, so fast-forward keeps its requested speed.
        owed += elapsed.coerceAtMost(MAX_CATCH_UP / FPS) * FPS * speed
        // Running a refresh's frame a little early keeps a 60 Hz display at one frame a refresh.
        val limit = kotlin.math.ceil(MAX_CATCH_UP * speed).toInt().coerceAtLeast(1)
        val n = (owed + EARLY).toInt().coerceIn(0, limit)
        owed = if (elapsed > MAX_CATCH_UP / FPS) 0.0 else owed - n
        return n
    }

    /** Starts over: after a pause, the time away isn't owed. */
    fun reset() {
        lastSeconds = -1.0
        owed = 0.0
    }

    companion object {
        /** 2^24 cycles a second over 280,896 a frame. */
        const val FPS = 16777216.0 / 280896.0
        const val MAX_CATCH_UP = 3
        private const val EARLY = 0.25
    }
}
