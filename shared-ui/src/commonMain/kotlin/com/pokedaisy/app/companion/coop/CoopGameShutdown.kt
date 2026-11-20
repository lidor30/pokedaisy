package com.pokedaisy.app.companion.coop

/** Orders co-op cleanup on the host's UI thread. The host owns core shutdown and resume persistence. */
class CoopGameShutdown(
    private val restoreBattle: () -> Boolean,
    private val resumeBattles: () -> Unit,
    private val stopEndpoint: () -> Unit,
    private val detachGame: () -> Unit,
) {
    private var restored: Boolean? = null
    var detached = false
        private set

    /** Pause the battle before any resume write, keeping the game-side attachments for a possible wake. */
    fun stopBattle(): Boolean = restored ?: restoreBattle().also { restored = it }

    fun saveIfRestored(save: () -> Boolean): Boolean = stopBattle() && save()

    fun resume() {
        if (detached) return
        restored = null
        resumeBattles()
    }

    /** Discard the attachments even when the battle could not be put back. */
    fun terminate(): Boolean {
        val safe = stopBattle()
        if (!detached) {
            try {
                stopEndpoint()
            } finally {
                detachAfterEndpointStop()
            }
        }
        return safe
    }

    /** A successful unpair has already stopped the endpoint, and the game may keep playing alone. */
    fun detachAfterEndpointStop() {
        if (detached) return
        detached = true
        detachGame()
    }
}
