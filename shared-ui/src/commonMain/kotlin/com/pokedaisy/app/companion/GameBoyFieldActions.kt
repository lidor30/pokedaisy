package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlinx.atomicfu.atomic
import kotlin.concurrent.Volatile

/** ROM verification is scoped to the loaded game; language/config changes select a different helper. */
internal class GameBoyFieldSupport {
    @Volatile private var checked: Pair<GbField, GbField?>? = null
    fun get(game: GameKind?, rom: (Long, Int) -> ByteArray?): GbField? {
        val candidate = GbField.forGame(game) ?: return null
        checked?.takeIf { it.first === candidate }?.let { return it.second }
        val verified = candidate.takeIf { runCatching { it.codeMatches(rom) }.getOrDefault(false) }
        checked = candidate to verified
        return verified
    }
}

/** Shared PC / classic FLY coordination. The existing GB helpers own the game's memory layout and gates;
 * this owner queues work, waits for acceptance, withdraws unaccepted actions and retires stale deliveries. */
class GameBoyFieldActions(
    private val session: GameSession,
    private val support: () -> GbField?,
    private val inBattle: () -> Boolean,
    private val deliver: (() -> Unit) -> Unit,
    private val handedOver: () -> Unit,
) {
    private val generation = atomic(0)
    private val closed = atomic(false)
    private class Pending(val field: GbField, val calls: GameCalls)
    private var pending: Pending? = null
    private var encounterField: GbField? = null
    private var encountersHeld: Boolean? = null
    val field get() = if (closed.value) null else support()
    val available get() = field != null

    /** Once per advancing frame. Gen 1 restores the current map's ROM rates only on a free field;
     * Crystal's helper holds its own encounter cooldown. Each helper protects the RAM it owns. */
    fun keepEncounters(calls: GameCalls, off: Boolean, onField: Boolean = true) {
        val fields = field
        if (fields !== encounterField) { encounterField = fields; encountersHeld = null }
        if (fields == null) return
        runCatching {
            if (off) { fields.holdWild(calls); encountersHeld = true }
            else if (encountersHeld != false && onField && fields.releaseWild(calls)) encountersHeld = false
        }.onFailure { Logs.w("Game Boy wild encounters failed", it) }
    }

    private fun send(visit: Int, result: () -> Unit) = deliver {
        if (!closed.value && generation.value == visit) result()
    }

    fun targets(done: (FlyTargets) -> Unit) {
        val visit = generation.value
        val battling = inBattle()
        session.requestField(battling) { calls, busy ->
            if (closed.value || visit != generation.value) return@requestField
            val fields = field
            val out = when {
                fields == null -> FlyTargets(FlyResult.FAILED)
                busy -> FlyTargets(if (battling) FlyResult.IN_BATTLE else FlyResult.NOT_NOW)
                else -> runCatching { fields.flyTargets(calls) }.getOrElse { FlyTargets(FlyResult.FAILED) }
            }
            send(visit) { done(out) }
        }
    }

    fun openPc(done: (ItemUseOutcome) -> Unit) {
        val battling = inBattle()
        val notNow = ItemUseOutcome(if (battling) ItemUseResult.IN_BATTLE else ItemUseResult.NOT_NOW)
        action(battling, ItemUseOutcome(ItemUseResult.FAILED), notNow, done,
            start = { fields, calls -> ItemUseOutcome(fields.openPc(calls)) },
            started = { it.result == ItemUseResult.USED }, accepted = { if (it) ItemUseOutcome(ItemUseResult.USED) else notNow })
    }

    fun fly(mapsec: Int, targets: FlyTargets, done: (FlyResult) -> Unit) {
        val battling = inBattle()
        action(battling, FlyResult.FAILED, if (battling) FlyResult.IN_BATTLE else FlyResult.NOT_NOW, done,
            start = { fields, calls -> fields.fly(mapsec, targets, calls) },
            started = { it == FlyResult.OK }, accepted = { if (it) FlyResult.OK else FlyResult.NOT_NOW })
    }

    private fun <T> action(battling: Boolean, failed: T, busyResult: T, done: (T) -> Unit,
                           start: (GbField, GameCalls) -> T, started: (T) -> Boolean, accepted: (Boolean) -> T) {
        val visit = generation.value
        session.requestField(battling) { calls, busy ->
            if (closed.value || visit != generation.value) return@requestField
            val fields = field
            val result = when {
                fields == null -> failed
                busy || pending != null -> busyResult
                else -> runCatching { start(fields, calls) }.getOrElse { failed }
            }
            if (fields == null || !started(result)) { send(visit) { done(result) }; return@requestField }
            val owner = Pending(fields, calls)
            pending = owner
            session.requestField(false, GbField.TAKE_FRAMES) acceptance@ { next, _ ->
                if (closed.value || visit != generation.value || pending !== owner) return@acceptance
                pending = null
                val took = runCatching { fields.taken(next) }.getOrDefault(false)
                send(visit) { if (took) handedOver(); done(accepted(took)) }
            }
        }
    }

    /** Between frames, before replacing the core state. taken() withdraws a script/warp not yet accepted. */
    fun cancel() {
        generation.incrementAndGet()
        encountersHeld = null // A loaded/rewound state can carry zero rates even while the preference is ON.
        val owner = pending
        pending = null
        if (owner != null) runCatching { owner.field.taken(owner.calls) }
            .onFailure { Logs.w("Game Boy field cancellation failed", it) }
    }

    fun close() { if (closed.compareAndSet(false, true)) cancel() }
}
