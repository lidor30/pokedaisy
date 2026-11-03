package com.pokedaisy.app.companion.coop

/** Reconnect delays: 1 s, doubling to 60 s, each with up to 20% jitter either way. */
class CoopBackoff(private val random: java.util.Random = java.util.Random()) {
    private var base = START_MS

    fun next(): Long {
        val jitter = 1.0 + (random.nextDouble() * 2 - 1) * JITTER
        val delay = (base * jitter).toLong()
        base = minOf(base * 2, CAP_MS)
        return delay
    }

    fun reset() {
        base = START_MS
    }

    private companion object {
        const val START_MS = 1_000L
        const val CAP_MS = 60_000L
        const val JITTER = 0.2
    }
}
