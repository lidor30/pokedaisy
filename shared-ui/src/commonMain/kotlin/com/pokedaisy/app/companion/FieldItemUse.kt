package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession

/** Shared item actions over the loaded game's queue, discovered field/center metadata and UI delivery. */
class FieldItemUse(
    private val support: () -> FieldItemSupport?,
    private val session: () -> GameSession?,
    private val inBattle: () -> Boolean,
    private val centerMetadata: () -> PokemonCenter.Found? = { null },
    private val tmMetadata: () -> TmFns? = { null },
    private val handedOver: () -> Unit = {},
    private val deliver: (() -> Unit) -> Unit,
) : ItemUse {
    private val failed = ItemUseOutcome(ItemUseResult.FAILED)
    /** Verified field layout for other actions sharing this loaded game. */
    fun supportedFields(): FieldItemFns? = support()?.fns
    override val repelVar get() = support()?.fns?.repelVar?.takeIf { it != 0 }
    override fun repelSteps(v: Int) = support()?.fns?.repelSteps(v) ?: v
    /** Whether verified native TM teaching is available for this bag item. */
    fun canTeach(itemId: Int) = tmMetadata()?.teaches(itemId) == true &&
        support()?.fns?.let { it.party != null && it.bagActions } == true
    override fun canUse(itemId: Int) = canTeach(itemId) || support()?.canUse(itemId) == true
    override fun canGive(itemId: Int) = support()?.canGive(itemId) == true
    override fun marked(itemId: Int) = canTeach(itemId) || support()?.marked(itemId) == true
    override fun picksMon(itemId: Int) = support()?.picksMon(itemId) == true
    override fun picksMove(itemId: Int) = support()?.picksMove(itemId) == true
    override fun canTake() = support()?.canTake == true
    override fun canPc() = support()?.canPc == true
    override fun center(): CenterSupport? {
        val center = centerMetadata() ?: return null
        val fields = support()?.fns ?: return null
        if (fields.pc == null || fields.party == null) return null
        return CenterSupport(heal = true, nickname = center.nickname != null, forget = center.forget != null,
            relearn = center.relearn != null && tmMetadata() != null || center.relearnSpecial != null)
    }

    override fun centerHeal(done: (CenterResult) -> Unit) {
        val center = centerMetadata() ?: return done(CenterResult.FAILED)
        onGame(CenterResult.FAILED, done) { fields, calls, busy -> centerHeal(fields.fns, center, busy, calls) }
    }

    override fun centerNickname(slot: Int, personality: Long, done: (CenterResult) -> Unit) =
        onCenter(done, screen = true) { fns, center, calls, busy -> centerNickname(fns, center, slot, personality, busy, calls) }

    override fun centerForget(slot: Int, personality: Long, moveIndex: Int, move: Int, done: (CenterResult) -> Unit) =
        onCenter(done) { fns, center, calls, busy -> centerForget(fns, center, slot, personality, moveIndex, move, busy, calls) }

    /** Center flows share verified metadata, battle results and the current-game screen handoff. */
    private fun onCenter(done: (CenterResult) -> Unit, screen: Boolean = false,
                 work: (FieldItemFns, PokemonCenter.Found, GameCalls, Boolean) -> CenterResult) {
        val center = centerMetadata() ?: return done(CenterResult.FAILED)
        onGame(CenterResult.FAILED, { result ->
            if (screen && result == CenterResult.OK) handedOver()
            done(result)
        }) { fields, calls, busy -> work(fields.fns, center, calls, busy) }
    }

    /** Extra game actions (Android's TM / box operations) use the same queued-work and result policy. */
    fun <T> onGame(failed: T, done: (T) -> Unit, work: (FieldItemSupport, GameCalls, Boolean) -> T) {
        val game = session()
        val fields = if (game != null) support() else null
        if (game == null || fields == null) { done(failed); return }
        val battling = inBattle()
        game.requestField(battling) { calls, busy ->
            val out = runCatching { work(fields, calls, busy) }.getOrElse { Logs.w("item use failed", it); failed }
            Logs.i("item use: $out")
            deliverIfCurrent(game) { done(if (battling) fieldActionBattleResult(out) else out) }
        }
    }

    internal fun deliverIfCurrent(game: GameSession, result: () -> Unit) =
        deliver { if (session() === game) result() }

    override fun use(itemId: Int, done: (ItemUseOutcome) -> Unit) {
        if (!canTeach(itemId)) { onGame(failed, done) { s, calls, busy -> useFieldItem(s.fns, itemId, busy, calls) }; return }
        val tm = tmMetadata() ?: return done(failed)
        val owner = session() ?: return done(failed)
        onGame(failed, { result ->
            if (result.result == ItemUseResult.IN_GAME) handedOver()
            done(result)
        }) { fields, calls, busy ->
            if (session() !== owner) return@onGame failed
            val result = tmStart(fields.fns, tm, itemId, busy, calls)
            if (result != ItemUseResult.USED) ItemUseOutcome(result)
            else {
                owner.requestField(false, TM_FADE_FRAMES) { next, leftField ->
                    val opened = tmFinish(tm, itemId, next, leftField)
                    if (opened && tm.bagReturn != 0L) watchTmBagReturn(owner, tm, 20 * 60 * 60)
                    Logs.i("TM screen for $itemId: $opened")
                }
                ItemUseOutcome(ItemUseResult.IN_GAME)
            }
        }
    }

    private fun watchTmBagReturn(owner: GameSession, tm: TmFns, framesLeft: Int) {
        if (framesLeft == 0) return
        owner.requestField(false, 1) { calls, _ ->
            if (runCatching { tmBagReturnWatch(tm, calls) }.getOrDefault(false)) {
                watchTmBagReturn(owner, tm, framesLeft - 1)
            }
        }
    }

    override fun centerRelearn(slot: Int, personality: Long, done: (CenterResult) -> Unit) {
        val tm = tmMetadata()
        val owner = session() ?: return done(CenterResult.FAILED)
        onCenter(done, screen = true) { fields, center, calls, busy ->
            if (session() !== owner) return@onCenter CenterResult.FAILED
            if ((tm == null || tm.bagReturn != 0L) && center.relearnSpecial != null) {
                return@onCenter centerRelearnScript(fields, center, slot, personality, busy, calls)
            }
            if (tm == null) return@onCenter CenterResult.FAILED
            val result = centerRelearnStart(fields, center, tm, slot, personality, busy, calls)
            if (result == CenterResult.OK) owner.requestField(false, TM_FADE_FRAMES) { next, leftField ->
                Logs.i("move relearner for slot $slot: ${centerRelearnFinish(fields, center, tm, slot, personality, next, leftField)}")
            }
            result
        }
    }
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
    out is BoxSwapResult && out == BoxSwapResult.NOT_NOW -> BoxSwapResult.IN_BATTLE as T
    out is FlyResult && out == FlyResult.NOT_NOW -> FlyResult.IN_BATTLE as T
    out is CenterResult && out == CenterResult.NOT_NOW -> CenterResult.IN_BATTLE as T
    out is FlashResult && out == FlashResult.NOT_NOW -> FlashResult.IN_BATTLE as T
    out is MoveOrderResult && out == MoveOrderResult.NOT_NOW -> MoveOrderResult.IN_BATTLE as T
    else -> out
}
