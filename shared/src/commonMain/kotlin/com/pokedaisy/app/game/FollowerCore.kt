package com.pokedaisy.app.game

/** Existing renderer transport; calls belong between frames on the game thread. */
interface FollowerCore {
    fun setFollower(frames: ByteArray?, palette: ByteArray?)
    fun showFollower(on: Boolean)
}
