package com.pokedaisy.app.game

/** The existing GBA vehicle renderer, called between game frames. */
interface RideCore {
    fun setRide(frames: ByteArray?, palette: ByteArray?, width: Int)
    fun showRide(on: Boolean)
}
