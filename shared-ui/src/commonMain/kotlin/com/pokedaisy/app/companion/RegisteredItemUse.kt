package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlinx.atomicfu.atomic

/** BIKE / ROD use the game's own registered-item handler. Owns the temporary register and synthetic
 * presses until restoration, including cancellation before a save, load, rewind or game close. */
class RegisteredItemUse(
    private val session: GameSession,
    private val support: () -> FieldItemSupport?,
    private val config: () -> NativeConfig?,
    private val game: () -> GameKind?,
    private val inBattle: () -> Boolean,
    private val riding: () -> Boolean,
    private val movesKnown: () -> Boolean,
    private val press: (Int) -> Unit,
    private val rejected: () -> Unit,
) {
    private val generation = atomic(0)
    // Only touched between game frames, as are cancel() and the queued field callbacks.
    private var transaction: Transaction? = null
    private class Transaction(val calls: GameCalls, val address: Long, val old: Int, val item: Int)

    fun supported(): Boolean {
        val cfg = config() ?: return false
        return (game() == GameKind.SOULGOLD || (game() == GameKind.FIRERED || game() == GameKind.EMERALD) &&
            (cfg.moneyOff == 0x290L || cfg.moneyOff == 0x490L)) && support() != null
    }

    fun use(itemId: Int) {
        val visit = generation.value
        val battling = inBattle()
        session.requestField(battling) { calls, busy ->
            if (generation.value != visit) return@requestField
            if (transaction != null) { rejected(); return@requestField }
            var started: Transaction? = null
            try {
                val cfg = config()
                val fns = support()?.fns
                if (!supported() || cfg == null || fns == null || busy ||
                    !fieldItemCodeMatches(fns, calls::read) || calls.call(fns.fieldControlsLocked) != 0L) {
                    rejected(); return@requestField
                }
                val address = KeyItems.registerAddr(cfg, calls::read)
                if (address == null) { rejected(); return@requestField }
                val old = readRegister(calls, address)
                if (old > 0x3FF || !calls.write(address, le16(itemId))) { rejected(); return@requestField }
                val tx = Transaction(calls, address, old, itemId)
                started = tx
                transaction = tx
                if (KeyItems.selectWheel) {
                    press(CoreKey.SELECT)
                    later(tx, 2) { press(0) }
                    later(tx, KeyItems.WHEEL_DOWN_AFTER) { press(CoreKey.DOWN) }
                    later(tx, KeyItems.WHEEL_DOWN_AFTER + 2) { press(0) }
                    later(tx, KeyItems.WHEEL_DOWN_AFTER + 20) { finish(tx) }
                } else select(tx, fns.fieldControlsLocked, TRIES)
            } catch (error: Throwable) {
                started?.let(::finish)
                Logs.w("registered item failed", error)
                rejected()
            }
        }
    }

    private fun later(tx: Transaction, frames: Int, work: (GameCalls) -> Unit) {
        session.requestField(false, frames) { calls, _ ->
            if (transaction !== tx) return@requestField
            try { work(calls) }
            catch (error: Throwable) {
                finish(tx)
                Logs.w("registered item failed", error)
                rejected()
            }
        }
    }

    /** Observe every frame: mounting can lock and unlock in one frame; a blind retry gets straight off. */
    private fun select(tx: Transaction, locked: Long, tries: Int) {
        val wasRiding = riding()
        val sure = movesKnown()
        var took = false
        press(CoreKey.SELECT)
        for (frame in 1..WAIT) later(tx, frame) { calls ->
            if (riding() != wasRiding || calls.call(locked) != 0L) took = true
        }
        later(tx, 2) { press(0) }
        later(tx, WAIT + 1) {
            if (took || !sure || tries <= 1) later(tx, 20) { finish(tx) }
            else select(tx, locked, tries - 1)
        }
    }

    private fun finish(tx: Transaction) {
        if (transaction !== tx) return
        try {
            press(0)
            // A later registration belongs to the player, not this transaction.
            if (readRegister(tx.calls, tx.address) == tx.item) tx.calls.write(tx.address, le16(tx.old))
        } finally { transaction = null }
    }

    /** Between frames, while the original core is still available. Retires queued requests as well as presses. */
    fun cancel() {
        generation.incrementAndGet()
        transaction?.let(::finish)
    }

    private fun readRegister(calls: GameCalls, address: Long): Int {
        val bytes = calls.read(address, 2)
        require(bytes.size == 2)
        return (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
    }
    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())
    private companion object { const val TRIES = 15; const val WAIT = 10 }
}
