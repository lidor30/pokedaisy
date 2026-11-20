package com.pokedaisy.app.companion.data.coop

import kotlin.math.abs

/** Who may be offered a joined battle, and the lengths of the join prompt. */
object CoopJoinRules {
    const val DEFAULT_RANGE = 8
    val RANGES = listOf(2, 4, 8, 16, 32)
    const val PROMPT_MS = 30_000L
    const val LINK_GRACE_MS = 60_000L

    /** A player's place; tiles = PresenceUpdate.fine / 16. */
    data class Spot(val mapGroup: Int, val mapNum: Int, val tileX: Int, val tileY: Int)

    /** Same map and at most [range] tiles apart, counted as the Manhattan distance. */
    fun nearby(me: Spot?, partner: Spot?, range: Int): Boolean {
        if (me == null || partner == null) return false
        if (me.mapGroup != partner.mapGroup || me.mapNum != partner.mapNum) return false
        return abs(me.tileX - partner.tileX) + abs(me.tileY - partner.tileY) <= range
    }

    /** [kind] 0 = not joinable (see CoopCore.joinKind: 1 wild, 2 two trainers, 3 one trainer, 4 story trainer). Every other condition must hold too. */
    fun mayOffer(kind: Int, connected: Boolean, partnerReady: Boolean, partnerFree: Boolean, nearby: Boolean, selfReady: Boolean): Boolean =
        kind != 0 && connected && partnerReady && partnerFree && nearby && selfReady

    /** A story spot as one player sees it: the event id (CoopStory's, 0 = none), its trainer and whether that trainer's flag is set. */
    data class StoryAt(val eventId: Int, val trainerId: Int, val beaten: Boolean) {
        companion object { val NONE = StoryAt(0, 0, false) }
    }

    /** A story trainer battle is offered only when both stand at the same spot, neither has beaten the trainer, and the partner is free there. */
    fun storyOffer(host: StoryAt, partner: StoryAt, partnerFree: Boolean): Boolean =
        host.eventId != 0 && host.eventId == partner.eventId && host.trainerId == partner.trainerId &&
            !host.beaten && !partner.beaten && partnerFree
}

/** Story battles: the same rule under the plan's name. */
object CoopJoin {
    fun storyOffer(host: CoopJoinRules.StoryAt, partner: CoopJoinRules.StoryAt, partnerFree: Boolean) =
        CoopJoinRules.storyOffer(host, partner, partnerFree)
}

/** The host's side: a battle is starting; decides offer / wait / arm / single. */
class CoopHostJoin(private val now: () -> Long) {
    sealed interface State
    object Idle : State
    data class Asking(val battleId: Int, val since: Long) : State
    data class Accepted(val answer: CoopBattleMsg.Answer) : State
    object Single : State

    var state: State = Idle
        private set

    /** The game waits while the partner is being asked. */
    val holdGame: Boolean get() = state is Asking

    fun battleStarting(battleId: Int, offer: Boolean) {
        state = if (offer) Asking(battleId, now()) else Single
    }

    fun onAnswer(a: CoopBattleMsg.Answer) {
        val s = state as? Asking ?: return
        if (a.battleId != s.battleId) return
        state = if (a.accept) Accepted(a) else Single
    }

    fun onCancel(battleId: Int) {
        val s = state as? Asking ?: return
        if (battleId == s.battleId) state = Single
    }

    /** PROMPT_MS passed -> Single. Sends nothing: the caller sends CANCEL TIMEOUT. */
    fun tick() {
        val s = state as? Asking ?: return
        if (now() - s.since >= CoopJoinRules.PROMPT_MS) state = Single
    }

    fun linkLost() {
        if (state is Asking) state = Single
    }

    /** A partner's OFFER arrived: true = decline it (we are Asking or Accepted, so BUSY). */
    fun incomingOffer(): Boolean = state is Asking || state is Accepted
}

/** The partner's side. */
class CoopPartnerJoin(private val now: () -> Long) {
    sealed interface State
    object Idle : State
    data class Prompt(val offer: CoopBattleMsg.Offer, val since: Long) : State
    data class Joining(val battleId: Int) : State
    object Declined : State

    var state: State = Idle
        private set

    /** Busy (not free or ready, or already prompted / joining) -> a Cancel(BUSY) to send; else Prompt and null. */
    fun onOffer(o: CoopBattleMsg.Offer, free: Boolean, ready: Boolean): CoopBattleMsg? {
        if (!free || !ready || state is Prompt || state is Joining) return CoopBattleMsg.Cancel(o.battleId, CoopCancel.BUSY)
        state = Prompt(o, now())
        return null
    }

    /** The battle id to answer, null if no prompt. */
    fun accept(): Int? {
        val s = state as? Prompt ?: return null
        state = Joining(s.offer.battleId)
        return s.offer.battleId
    }

    fun decline(): Int? {
        val s = state as? Prompt ?: return null
        state = Declined
        return s.offer.battleId
    }

    fun onCancel(battleId: Int) {
        when (val s = state) {
            is Prompt -> if (s.offer.battleId == battleId) state = Declined
            is Joining -> if (s.battleId == battleId) state = Declined
            else -> {}
        }
    }

    /** PROMPT_MS -> Declined (the host times out on its own). */
    fun tick() {
        val s = state as? Prompt ?: return
        if (now() - s.since >= CoopJoinRules.PROMPT_MS) state = Declined
    }

    val secondsLeft: Int
        get() {
            val s = state as? Prompt ?: return 0
            val left = CoopJoinRules.PROMPT_MS - (now() - s.since)
            return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
        }
}
