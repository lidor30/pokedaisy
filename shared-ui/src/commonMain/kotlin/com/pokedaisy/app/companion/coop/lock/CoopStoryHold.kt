package com.pokedaisy.app.companion.coop.lock

/** The story-hold protocol: both players hold the same event, then both start it. Not thread-safe: one owner thread. */
class CoopStoryHold(private val clock: () -> Long) {
    data class Step(val send: Int?, val go: Int?)

    private var partner = 0
    private var partnerAt = 0L
    private var lastSent = 0
    private var lastSentAt = 0L
    private var fired = 0

    fun onPartner(id: Int) { partner = id; partnerAt = clock() }

    fun step(ownHeld: Int, connected: Boolean): Step {
        val now = clock()
        var send: Int? = null
        if (connected && (ownHeld != lastSent || (ownHeld != 0 && now - lastSentAt >= SEND_EVERY_MS))) {
            send = ownHeld; lastSent = ownHeld; lastSentAt = now
        }
        if (ownHeld == 0 || ownHeld != fired) fired = 0
        val partnerHeld = if (partner != 0 && now - partnerAt <= STALE_MS) partner else 0
        var go: Int? = null
        if (connected && ownHeld != 0 && ownHeld == partnerHeld && fired == 0) { go = ownHeld; fired = ownHeld }
        return Step(send, go)
    }

    companion object { const val SEND_EVERY_MS = 1000L; const val STALE_MS = 3000L }
}
