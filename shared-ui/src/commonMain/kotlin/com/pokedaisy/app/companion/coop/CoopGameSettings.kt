package com.pokedaisy.app.companion.coop

/** The running game's policy for requests from either game or library settings. UI thread only. */
class CoopGameSettings(
    internal val startup: CoopGameStartup,
    internal val ownSave: () -> CoopSaveId?,
    internal val refuseBattle: () -> Boolean,
    internal val pauseBattles: () -> Boolean,
    internal val resumeBattles: () -> Unit,
    internal val detach: () -> Unit,
) {
    internal fun canRemove(save: CoopSaveId): Boolean = ownSave() != save || !refuseBattle()
    internal fun canChangeRelay(): Boolean = !refuseBattle()
    internal var removingCurrent = false
}
