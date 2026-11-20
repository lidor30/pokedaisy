package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession

/** Party order and PC transfers over the same loaded game as its bag actions. */
class FieldPartyActions(
    private val items: FieldItemUse,
    private val session: () -> GameSession?,
    private val snapshot: () -> SnapshotView,
    private val reorderConfig: () -> NativeConfig?,
    private val fieldState: () -> Int,
    private val deliver: (() -> Unit) -> Unit,
) : ItemUse by items {
    override fun canReorderParty() = session() != null && reorderConfig() != null

    override fun reorderParty(a: Int, b: Int, done: (PartySwapResult) -> Unit) {
        val game = session() ?: return done(PartySwapResult.FAILED)
        val config = reorderConfig() ?: return done(PartySwapResult.FAILED)
        val party = snapshot()
        if (party.inBattle) return done(PartySwapResult.IN_BATTLE)
        if (a == b || a !in party.party.indices || b !in party.party.indices) return done(PartySwapResult.FAILED)
        val expected = listOf(a, b).associateWith { party.party[it].personality }
        game.requestField(false) { calls, busy ->
            if (session() !== game) return@requestField
            val field = fieldState()
            val locked = field and (GameField.KNOWN or GameField.LOCKED) == (GameField.KNOWN or GameField.LOCKED)
            val result = runCatching { when {
                snapshot().inBattle || field and GameField.BATTLE != 0 -> PartySwapResult.IN_BATTLE
                busy || locked -> PartySwapResult.NOT_NOW
                reorderConfig() != config -> PartySwapResult.FAILED
                (calls.read(config.playerPartyCount, 1).singleOrNull()?.toInt()?.and(0xff)) != party.party.size -> PartySwapResult.FAILED
                expected.any { (slot, identity) ->
                    val bytes = calls.read(config.playerParty + slot * config.monStride.toLong(), 4)
                    bytes.size != 4 || bytes.indices.fold(0L) { value, i -> value or ((bytes[i].toLong() and 0xff) shl (8 * i)) } != identity
                } -> PartySwapResult.FAILED
                else -> swapPartySlots(calls, config.playerParty, config.monStride, party.party.size, a, b)
            } }.getOrElse { Logs.w("party reorder failed", it); PartySwapResult.FAILED }
            deliver { if (session() === game) done(result) }
        }
    }

    override fun canSwapBox() = boxSwapFor(items.supportedFields(), activeNativeConfig) != null

    override fun swapBox(box: Int, slot: Int, boxPersonality: Long, partySlot: Int, partyPersonality: Long,
                         done: (BoxSwapResult) -> Unit) = onBoxes(done) { fields, boxes, calls, busy ->
        swapWithBox(fields, boxes, box, slot, boxPersonality, partySlot, partyPersonality, busy, calls)
    }
    override fun depositToBox(partySlot: Int, partyPersonality: Long, box: Int, done: (BoxSwapResult) -> Unit) =
        onBoxes(done) { fields, boxes, calls, busy -> depositToBox(fields, boxes, partySlot, partyPersonality, box, busy, calls) }
    override fun withdrawFromBox(box: Int, slot: Int, boxPersonality: Long, done: (BoxSwapResult) -> Unit) =
        onBoxes(done) { fields, boxes, calls, busy -> withdrawFromBox(fields, boxes, box, slot, boxPersonality, busy, calls) }

    private fun onBoxes(done: (BoxSwapResult) -> Unit,
                        move: (FieldItemFns, BoxSwapFns, GameCalls, Boolean) -> BoxSwapResult) =
        items.onGame(BoxSwapResult.FAILED, done) { fields, calls, busy ->
            val boxes = boxSwapFor(fields.fns, activeNativeConfig) ?: return@onGame BoxSwapResult.FAILED
            move(fields.fns, boxes, calls, busy)
        }
}
