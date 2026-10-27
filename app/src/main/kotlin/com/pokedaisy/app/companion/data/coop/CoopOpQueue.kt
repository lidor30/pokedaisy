package com.pokedaisy.app.companion.data.coop

/**
 * The after-battle changes ([CoopOp]) run through the core, one per free-field frame, each -1 retried. A save goes only
 * after [beforeSave] (the SaveBackups copy) said true; a refused one is logged and skipped, never retried blind.
 */
internal class CoopOpQueue(
    private val core: CoopCore, private val beforeSave: () -> Boolean,
    private val notice: (String) -> Unit, private val log: (String) -> Unit, private val now: () -> Long,
    /** Each op that is done (not retried) and the code it answered with: a Save that answered 1 is a catch's acknowledgement. */
    private val onDone: (CoopOp, Int) -> Unit = { _, _ -> },
) {
    private val ops = ArrayDeque<CoopOp>()
    private var backedUp = false
    private var money: Int? = null
    private var progressAt = 0L
    private val beaten = HashSet<Int>()      // trainers this game already had flagged: no second prize
    private var changed = false              // anything written yet (a save with nothing new is skipped)

    val empty: Boolean get() = ops.isEmpty()

    fun load(list: List<CoopOp>) { ops.clear(); ops.addAll(list); backedUp = false; money = null; progressAt = now(); beaten.clear(); changed = list.none { it is CoopOp.TrainerFlag } }   // the host's own list (no flags) always saves
    /** One more change after those queued (a catch the other game named after the battle ended). */
    fun append(op: CoopOp) { ops.addLast(op); progressAt = now() }
    fun clear() { ops.clear(); money = null }

    /** At most one op, and only on a free field frame. */
    fun step() {
        val op = ops.firstOrNull() ?: return
        if (now() - progressAt > PATIENCE_MS) {      // a permanent retry (or a field that never frees) must not keep the battle busy
            log("gave up on ${ops.size} changes after ${PATIENCE_MS / 1000} s")
            notice(CoopNotices.CHANGES_DROPPED); clear(); return
        }
        if (!core.onFreeField()) return
        val r = run(op)
        if (r != RETRY) { ops.removeFirst(); money = null; progressAt = now(); onDone(op, r) }
    }

    private fun run(op: CoopOp): Int = when (op) {
        is CoopOp.WriteMon -> core.writePartyMon(op.slot, op.mon).also { if (it == 0) log("write slot ${op.slot} refused") else if (it > 0) changed = true }
        is CoopOp.GiveMon -> core.giveMon(op.mon).also { if (it == 2) notice(CoopNotices.NO_ROOM) else if (it >= 0) changed = true }
        is CoopOp.RemoveItem -> core.removeItem(op.item, op.count).also { if (it == 1) changed = true }
        is CoopOp.TrainerFlag -> core.setTrainerFlag(op.trainerId).also { if (it == ALREADY || it == 0) beaten += op.trainerId else if (it == 1) changed = true }
        is CoopOp.Prize -> if (op.trainerId in beaten) { log("trainer ${op.trainerId} already beaten here: no prize"); 1 } else prize(op.trainerId, op.single).also { if (it == 1) changed = true }
        CoopOp.WhiteOut -> core.whiteOut().also { if (it > 0) changed = true }
        is CoopOp.Evolve -> core.evolve(op.slot).also { if (it == 1) changed = true }
        CoopOp.Save -> if (!changed) { log("nothing changed: no save"); 1 } else save()
    }

    /** The base prize is read once (-1 = a bad id, skipped), then added; the add alone is retried. */
    private fun prize(trainerId: Int, single: Boolean): Int {
        val amount = money ?: core.prize(trainerId, single).also { money = it }
        if (amount < 0) { log("prize for trainer $trainerId refused"); return 0 }
        return core.addMoney(amount)
    }

    private fun save(): Int {
        if (!backedUp) {
            backedUp = beforeSave()
            if (!backedUp) { notice(CoopNotices.SAVE_FAILED); return 0 }
        }
        val r = core.saveGame()
        if (r == 0 || r == -2) notice(CoopNotices.SAVE_FAILED)
        return r
    }

    private companion object { const val RETRY = -1; const val ALREADY = 2; const val PATIENCE_MS = 60_000L }
}
