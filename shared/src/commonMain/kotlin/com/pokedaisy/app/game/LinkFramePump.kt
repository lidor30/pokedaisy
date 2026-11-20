package com.pokedaisy.app.game

/** Keeps manual input and sound with one peer while the core advances every peer together. */
class LinkFramePump(
    private val peers: Int,
    private val keys: (Int, Int) -> Unit,
    private val read: (Int, ShortArray) -> Int,
) {
    val audio = ShortArray(8192)
    private var focused = -1

    fun prepare(peer: Int, mask: Int) {
        if (peer != focused) {
            for (slot in 0 until peers) keys(slot, 0)
            read(peer, audio)
            focused = peer
        }
        keys(peer, mask)
    }

    /** Stereo frames written to [audio] after a frame, including when the host is muting fast-forward. */
    fun readFocusedAudio(): Int = read(focused, audio)
}
