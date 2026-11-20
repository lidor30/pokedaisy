package com.pokedaisy.app.companion.data

/** What [reorderMoves] did. */
enum class MoveOrderResult { REORDERED, NOT_NOW, IN_BATTLE, CHANGED, FAILED }

private const val MON_DATA_PERSONALITY = 0
private const val MON_DATA_MOVE1 = 13
private const val MON_DATA_PP1 = MON_DATA_MOVE1 + 4
private const val MON_DATA_PP_BONUSES = MON_DATA_MOVE1 + 8
private const val PARTY_MAX = 6

/**
 * [slot]'s move at [from] moves to [to], the moves between shifting along: its PP and PP Up bits go with it, as the
 * game's own summary reorder does (SwapMonMoves: MON_DATA_MOVE1..4, PP1..4 and PP_BONUSES). Through the game's
 * SetMonData, so the party record is re-encrypted and its checksum fixed. [personality] checks the Pokémon is still the
 * one the summary showed. Emu thread.
 */
fun reorderMoves(
    fns: FieldItemFns,
    slot: Int,
    personality: Long,
    from: Int,
    to: Int,
    busy: Boolean,
    g: GameCalls,
): MoveOrderResult {
    val p = fns.party ?: return MoveOrderResult.FAILED
    if (busy || g.call(fns.fieldControlsLocked) != 0L) return MoveOrderResult.NOT_NOW
    val count = (g.read(p.partyCount, 1).firstOrNull()?.toInt() ?: return MoveOrderResult.FAILED) and 0xFF
    if (slot !in 0 until count.coerceAtMost(PARTY_MAX)) return MoveOrderResult.CHANGED
    val mon = (p.party + slot.toLong() * p.monStride).toInt()
    if (personality != 0L && g.call(p.getMonData, mon, MON_DATA_PERSONALITY) and 0xFFFFFFFFL != personality) {
        return MoveOrderResult.CHANGED
    }

    val moveField = p.move1Field
    val moves = IntArray(4)
    val pp = IntArray(4)
    for (i in 0 until 4) {
        val m = g.call(p.getMonData, mon, moveField + i)
        val v = g.call(p.getMonData, mon, MON_DATA_PP1 + p.monFieldShift + i)
        if (m < 0 || v < 0) return MoveOrderResult.FAILED
        moves[i] = (m.toInt() and 0xFFFF)
        pp[i] = (v.toInt() and 0xFF)
    }
    // Gen 3 keeps a Pokémon's moves in the slots from 0 with no gaps: an empty one ends the list.
    val known = moves.indexOfFirst { it == 0 }.let { if (it < 0) 4 else it }
    if (from !in 0 until known || to !in 0 until known || from == to) return MoveOrderResult.FAILED

    val bonusCall = g.call(p.getMonData, mon, MON_DATA_PP_BONUSES + p.monFieldShift)
    if (bonusCall < 0) return MoveOrderResult.FAILED
    val bonuses = bonusCall.toInt() and 0xFF
    val order = (0 until 4).toMutableList().apply { add(to, removeAt(from)) }
    val newMoves = IntArray(4) { moves[order[it]] }
    val newPp = IntArray(4) { pp[order[it]] }
    val newBonuses = run {
        var out = 0
        for (i in 0 until 4) out = out or (((bonuses shr (2 * order[i])) and 3) shl (2 * i))
        out
    }

    fun set(field: Int, value: Int): Boolean {
        val before = g.read(p.scratch, 2)
        if (!g.write(p.scratch, byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))) return false
        val called = g.call(p.setMonData, mon, field, p.scratch.toInt())
        g.write(p.scratch, before)
        return called >= 0
    }
    fun restore(): Boolean {
        var ok = true
        for (i in 0 until 4) ok = set(moveField + i, moves[i]) && ok
        for (i in 0 until 4) ok = set(MON_DATA_PP1 + p.monFieldShift + i, pp[i]) && ok
        ok = set(MON_DATA_PP_BONUSES + p.monFieldShift, bonuses) && ok
        return ok
    }

    var ok = true
    for (i in 0 until 4) {
        if (newMoves[i] != moves[i]) ok = set(moveField + i, newMoves[i]) && ok
        if (newPp[i] != pp[i]) ok = set(MON_DATA_PP1 + p.monFieldShift + i, newPp[i]) && ok
    }
    if (ok && newBonuses != bonuses) ok = set(MON_DATA_PP_BONUSES + p.monFieldShift, newBonuses)
    if (!ok) {
        restore()
        return MoveOrderResult.FAILED
    }
    val took = (0 until 4).all { i ->
        (g.call(p.getMonData, mon, moveField + i).toInt() and 0xFFFF) == newMoves[i] &&
            (g.call(p.getMonData, mon, MON_DATA_PP1 + p.monFieldShift + i).toInt() and 0xFF) == newPp[i]
    } && (g.call(p.getMonData, mon, MON_DATA_PP_BONUSES + p.monFieldShift).toInt() and 0xFF) == newBonuses
    if (!took) {
        restore()
        return MoveOrderResult.FAILED
    }
    return MoveOrderResult.REORDERED
}
