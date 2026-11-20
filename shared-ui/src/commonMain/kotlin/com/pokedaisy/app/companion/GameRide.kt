package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.RideOn
import com.pokedaisy.app.game.RideCore

/** Sends the installed vehicle sprite to the game. The renderer checks whether the player is on a bike. */
class GameRide(private val core: RideCore) {
    private var sent: RideOn.Sprite? = null
    private var shown = false
    private var frames = 0

    fun step(field: Boolean, sprite: RideOn.Sprite?) {
        if (sprite !== sent) {
            core.setRide(sprite?.frames, sprite?.pal, sprite?.width ?: 32)
            sent = sprite
        }
        val show = field && sprite != null
        if (show != shown || show && ++frames % 60 == 0) {
            core.showRide(show)
            shown = show
        }
    }

    fun reset() {
        sent = null
        shown = false
        frames = 0
    }
}
