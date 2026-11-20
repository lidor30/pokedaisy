package com.pokedaisy.app

import com.pokedaisy.app.companion.data.Gen1MusicTables
import com.pokedaisy.app.companion.data.MemoryReader

/**
 * Where a Gen 1 song loops (the Game Boy twin of [M4aLoopWatch]), read from the sound
 * engine while it plays on the render core: the music channels' state - command
 * pointers, call return addresses, note delay and loop counters - fully decides what
 * plays next, so the first frame it repeats an earlier frame's state, the song has come
 * round. (The tempo's fractional carry is left out: it only nudges a note by a frame.)
 * A state that stops changing altogether means the song ended (a fanfare).
 */
class Gen1LoopWatch(private val reader: MemoryReader, private val t: Gen1MusicTables) {
    private val seen = HashMap<String, Int>()
    private var last: String? = null
    private var still = 0

    /** The frame whose audio starts the loop, and the one that starts it again; -1 until [found]. */
    var startFrame = -1
        private set
    var endFrame = -1
        private set
    val found: Boolean get() = endFrame >= 0

    /** The channels haven't moved for a while: the song is over. */
    val ended: Boolean get() = still >= STILL_FRAMES

    /** Call once after each rendered frame, in order from the first. */
    fun onFrame() {
        val f = frames++
        if (found) return
        val state = runCatching {
            buildString {
                val hex = "0123456789abcdef"
                t.channelState.forEach { (address, length) ->
                    reader.readCoreMemory(address, length).forEach { byte ->
                        val value = byte.toInt() and 0xFF
                        append(hex[value shr 4])
                        append(hex[value and 0xF])
                    }
                }
            }
        }.getOrNull() ?: return
        still = if (state == last) still + 1 else 0
        last = state
        val first = seen[state]
        if (first == null) { seen[state] = f; return }
        // The state after frame `first` came back after frame f: from the next frames on, the audio repeats.
        if (f - first >= MIN_PERIOD_FRAMES && !ended) {
            startFrame = first + 1
            endFrame = f + 1
        }
    }

    private var frames = 0

    private companion object {
        const val MIN_PERIOD_FRAMES = 120   // 2 s: shorter is a held note or a stuck channel, not a song
        const val STILL_FRAMES = 90
    }
}
