package com.pokedaisy.app.companion.coop.lock

/** Why the lock can't be turned on right now. */
enum class LockRefusal { NOT_PAIRED, NOT_CONNECTED, OWN_NOT_NEW, PARTNER_NOT_NEW }

/** What the overlay over the game is waiting for. */
sealed interface LockWait {
    data object Partner : LockWait
    data class AtStory(val eventId: Int) : LockWait
}

data class CoopLockUi(
    val locked: Boolean = false,
    val refusal: LockRefusal? = LockRefusal.NOT_PAIRED, // why it can't be turned on now (null = it can)
    val partnerProposed: Boolean = false,               // the partner asked; show ACCEPT / NO
    val proposalSent: Boolean = false,                  // we asked; waiting for their answer
    val waiting: LockWait? = null,                      // the overlay over the game
)

interface CoopLockActions { fun propose(); fun accept(); fun decline(); fun turnOff() }
