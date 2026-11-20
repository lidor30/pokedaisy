package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import kotlin.concurrent.Volatile

/** Android's existing trade-evolution selection and ROM verification, shared by both game hosts. */
class FieldTradeSupport(private val read: (Long, Int) -> ByteArray) {
    @Volatile private var cached: Pair<String, TradeEvoFns?>? = null

    fun get(snapshot: SnapshotView, code: String?, revision: Int): TradeEvoFns? {
        if (code == null || !snapshot.hasData) return null
        val hack = runningFireRedHack != null || runningEmeraldHack != null || emeraldLegacy || romGameCode == KAIZO_TEXT
        val functions = tradeEvoFor(snapshot.game, code, revision, hack) ?: return null
        val key = "$code/$revision/${snapshot.game}/$romGameCode"
        cached?.let { if (it.first == key) return it.second }
        val found = runCatching { functions.takeIf { tradeEvoCodeMatches(it, read) } }.getOrElse { return null }
        cached = key to found
        return found
    }
}

/** Trade target preview and native evolution scene over the same game queue as bag/party actions. */
class FieldTradeEvolution(
    delegate: ItemUse,
    private val items: FieldItemUse,
    private val metadata: () -> TradeEvoFns?,
    private val enabled: () -> Boolean,
    private val onField: () -> Boolean,
    private val handedOver: () -> Unit = {},
) : ItemUse by delegate {
    fun supported() = metadata() != null
    override fun canTradeEvolve() = enabled() && supported()

    override fun tradeEvolveTargets(done: (Map<Long, Int>) -> Unit) {
        if (!enabled()) return done(emptyMap())
        val evolution = metadata() ?: return done(emptyMap())
        items.onGame(emptyMap(), done) { fields, calls, _ ->
            if (enabled()) tradeEvolveTargets(fields.fns, evolution, calls) else emptyMap()
        }
    }

    override fun tradeEvolve(slot: Int, personality: Long, done: (TradeEvoResult) -> Unit) {
        if (!enabled()) return done(TradeEvoResult.FAILED)
        val evolution = metadata() ?: return done(TradeEvoResult.FAILED)
        if (!onField()) return done(TradeEvoResult.NOT_NOW)
        items.onGame(TradeEvoResult.FAILED, { result ->
            if (result == TradeEvoResult.EVOLVING) handedOver()
            done(result)
        }) { fields, calls, busy ->
            if (!enabled()) TradeEvoResult.FAILED
            else tradeEvolve(fields.fns, evolution, slot, personality, busy || !onField(), calls)
        }
    }
}
