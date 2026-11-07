package com.pokedaisy.app.companion.data.coop

/**
 * The native calls a joined battle's after-battle changes and join rules make, one per device, **emu thread only** (the flow is
 * called there too: every pk_call runs between frames). MgbaCoopCore forwards to MgbaCore. Return codes: 1 done / 0 refused /
 * -1 retry on a later frame, unless a call says otherwise. The link battle itself is [CoopLinkEngine]'s.
 */
interface CoopCore {
    /** 0 not joinable, 1 wild, 2 two trainers, 3 one trainer with two or more Pokémon (call while the battle starts). */
    fun joinKind(): Int
    /** [trainerA, trainerB], 0 = none (kind 3: [trainerA, 0]). */
    fun joinTrainers(): IntArray
    fun partnerFree(): Boolean

    fun giveMon(mon: ByteArray): Int            // 0 party, 1 PC, 2 no room, -1 retry
    fun removeItem(item: Int, count: Int): Int
    /** 1 set now, 2 this game already had it (nothing written), 0 bad id, -1 retry. */
    fun setTrainerFlag(trainerId: Int): Int
    /** Base prize money from the ROM tables, -1 bad id (not a retry). [single] = what the game pays for this trainer alone (kind 3: x2 for a double-battle trainer); else a two-trainer battle's share (no x2). */
    fun prize(trainerId: Int, single: Boolean = false): Int
    fun addMoney(amount: Int): Int
    fun writePartyMon(slot: Int, mon: ByteArray): Int   // 0 unless the slot's personality and OT ID match the mon
    fun whiteOut(): Int
    fun evolve(slot: Int): Int                  // 1 scene started, 0 nothing to evolve into, -1 retry
    /** 1 ok, 0 failed, -1 retry later, -2 started but never returned (never retry). */
    fun saveGame(): Int
    fun onFreeField(): Boolean
}

/** What the partner sends the host about itself (ANSWER's fields). */
class CoopIdentity(val name: ByteArray, val gender: Int, val otId: Int)

/** English texts the flow hands to the UI's notice(); the glue translates them with tr(). */
object CoopNotices {
    const val UNDONE = "THE LINK DROPPED. THE BATTLE WAS UNDONE."
    const val PARTNER_LEFT = "YOUR PARTNER LEFT. THE BATTLE WAS UNDONE."
    const val SAVE_FAILED = "THE GAME COULD NOT BE SAVED."
    const val NO_ROOM = "NO ROOM FOR THE CAUGHT POKEMON."
    const val CHANGES_DROPPED = "SOME CHANGES COULD NOT BE APPLIED."
    const val START_FAILED = "THE BATTLE COULD NOT START TOGETHER."
    const val STORY_ARMED = "YOU JOINED. TALK TO THE TRAINER."
    const val STORY_CALLED_OFF = "THE TEAM BATTLE WAS CALLED OFF."
}
