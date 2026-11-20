package com.pokedaisy.app.companion.coop.lock

object CoopLockRules {
    const val LINK_GRACE_MS = 3000L

    fun refusal(paired: Boolean, connected: Boolean, ownParty: Int, partnerParty: Int?): LockRefusal? = when {
        !paired -> LockRefusal.NOT_PAIRED
        !connected -> LockRefusal.NOT_CONNECTED
        ownParty != 0 -> LockRefusal.OWN_NOT_NEW
        partnerParty != 0 -> LockRefusal.PARTNER_NOT_NEW   // null = unknown counts as not new
        else -> null
    }

    fun holdForLink(locked: Boolean, connected: Boolean, battleActive: Boolean, lostForMs: Long): Boolean =
        locked && !battleActive && !connected && lostForMs >= LINK_GRACE_MS
}
