package com.pokedaisy.app

import android.os.Handler
import android.os.Looper

/**
 * A D-pad direction held on a joystick's hat (or its stick) while the buttons steer the companion: Android repeats a
 * held key, but never a motion event's axes, so holding UP or DOWN on such a D-pad moved the cursor once (GitHub
 * discussion #922). Like a key's repeat: the first after [delayMs], then one every [everyMs], until the direction is let
 * go or another comes on. Directions only (bits as [com.pokedaisy.app.companion.data.CoreKey]'s); UI thread.
 */
class HatRepeat(
    private val delayMs: Long = DELAY_MS,
    private val everyMs: Long = EVERY_MS,
    private val post: (Runnable, Long) -> Unit = { r, ms -> main.postDelayed(r, ms) },
    private val cancel: (Runnable) -> Unit = { r -> main.removeCallbacks(r) },
) {
    /** The direction bit repeating now, 0 = none. */
    var held = 0
        private set
    private var send: ((Int) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            val bit = held.takeIf { it != 0 } ?: return
            send?.invoke(bit)
            post(this, everyMs)
        }
    }

    /**
     * The hat's directions now ([bits]): a direction that just came on ([pressed]) starts repeating through [onRepeat];
     * the one repeating stops once it's no longer held.
     */
    fun update(bits: Int, pressed: Int, onRepeat: (Int) -> Unit) {
        val dir = pressed and DIRECTIONS
        if (dir != 0) {
            stop()
            // The one a press steps by (PokeDaisyActivity's menuKeyFor: UP, DOWN, LEFT, RIGHT first).
            held = ORDER.first { it and dir != 0 }
            send = onRepeat
            post(tick, delayMs)
        } else if (held != 0 && bits and held == 0) stop()
    }

    fun stop() {
        held = 0
        send = null
        cancel(tick)
    }

    companion object {
        const val DELAY_MS = 400L
        const val EVERY_MS = 90L
        private val ORDER = listOf(com.pokedaisy.app.companion.data.CoreKey.UP, com.pokedaisy.app.companion.data.CoreKey.DOWN, com.pokedaisy.app.companion.data.CoreKey.LEFT, com.pokedaisy.app.companion.data.CoreKey.RIGHT)
        private val DIRECTIONS = ORDER.reduce(Int::or)
        private val main by lazy { Handler(Looper.getMainLooper()) }
    }
}
