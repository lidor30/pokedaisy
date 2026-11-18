package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession

/** Android's item adapter shared with iOS. The hosts supply game lifetime and result delivery only. */
class FieldItemUse(
    private val support: () -> FieldItemSupport?,
    private val session: () -> GameSession?,
    private val inBattle: () -> Boolean,
    private val deliver: (() -> Unit) -> Unit,
) : ItemUse {
    private val failed = ItemUseOutcome(ItemUseResult.FAILED)
    override val repelVar get() = support()?.fns?.repelVar?.takeIf { it != 0 }
    override fun repelSteps(v: Int) = support()?.fns?.repelSteps(v) ?: v
    override fun canUse(itemId: Int) = support()?.canUse(itemId) == true
    override fun canGive(itemId: Int) = support()?.canGive(itemId) == true
    override fun marked(itemId: Int) = support()?.marked(itemId) == true
    override fun picksMon(itemId: Int) = support()?.picksMon(itemId) == true
    override fun picksMove(itemId: Int) = support()?.picksMove(itemId) == true
    override fun canTake() = support()?.canTake == true
    override fun canPc() = support()?.canPc == true

    /** Extra game actions (Android's TM / box operations) use the same queued-work and result policy. */
    fun <T> onGame(failed: T, done: (T) -> Unit, work: (FieldItemSupport, GameCalls, Boolean) -> T) {
        val game = session()
        val fields = if (game != null) support() else null
        if (game == null || fields == null) { done(failed); return }
        val battling = inBattle()
        game.requestField(battling) { calls, busy ->
            val out = runCatching { work(fields, calls, busy) }.getOrElse { Logs.w("item use failed", it); failed }
            Logs.i("item use: $out")
            deliver { if (session() === game) done(if (battling) fieldActionBattleResult(out) else out) }
        }
    }

    override fun use(itemId: Int, done: (ItemUseOutcome) -> Unit) =
        onGame(failed, done) { s, calls, busy -> useFieldItem(s.fns, itemId, busy, calls) }
    override fun targets(itemId: Int, give: Boolean, done: (PartyTargets) -> Unit) =
        onGame(PartyTargets(ItemUseResult.FAILED), done) { s, calls, busy -> partyTargets(s, itemId, give, busy, calls) }
    override fun useOn(itemId: Int, target: PartyTarget, moveIndex: Int, done: (ItemUseOutcome) -> Unit) =
        onGame(failed, done) { s, calls, busy -> useOnMon(s, itemId, target, moveIndex, busy, calls) }
    override fun give(itemId: Int, target: PartyTarget, done: (ItemUseOutcome) -> Unit) =
        onGame(failed, done) { s, calls, busy -> giveToMon(s, itemId, target, busy, calls) }
    override fun heldItems(done: (PartyTargets) -> Unit) =
        onGame(PartyTargets(ItemUseResult.FAILED), done) { s, calls, _ -> partyHeldItems(s, calls) }
    override fun take(target: PartyTarget, done: (ItemUseOutcome) -> Unit) =
        onGame(failed, done) { s, calls, busy -> takeFromMon(s, target, busy, calls) }
    override fun openPc(done: (ItemUseOutcome) -> Unit) =
        onGame(failed, done) { s, calls, busy -> ItemUseOutcome(openPc(s.fns, busy, calls)) }
}

/** Report a busy field action as a battle refusal when the request came from a battle. */
@Suppress("UNCHECKED_CAST")
fun <T> fieldActionBattleResult(out: T): T = when {
    out is ItemUseOutcome && out.result == ItemUseResult.NOT_NOW -> out.copy(result = ItemUseResult.IN_BATTLE) as T
    out is PartyTargets && out.result == ItemUseResult.NOT_NOW -> out.copy(result = ItemUseResult.IN_BATTLE) as T
    out is FlyTargets && out.result == FlyResult.NOT_NOW -> out.copy(result = FlyResult.IN_BATTLE) as T
    out is FlyResult && out == FlyResult.NOT_NOW -> FlyResult.IN_BATTLE as T
    else -> out
}
