package com.pokedaisy.app.companion.data.coop

import kotlin.math.abs
import kotlin.math.max

/** Where and how to draw the partner this frame (fine = object-event coords x 16 + sub-tile pixels). */
data class PartnerPlacement(
    val fineX: Int, val fineY: Int,
    val frame: Int,       // PartnerArt frame, PartnerFrames.index
    val hflip: Boolean,   // facing east: the west frame flipped
    val blobFrame: Int,   // the surf blob's frame, -1 = none
    val bob: Int,         // px up while surfing
    val female: Boolean,
    val lookId: Int,
)

/**
 * The partner's frame in PartnerArt: each bike / foot sheet is 9 frames (stand S / N / W, then S A, S B, N A, N B, W A,
 * W B), surfing 36-38 (no step frames), the blob 39-41. East is west flipped.
 */
object PartnerFrames {
    private const val SHEET = 9
    private const val SURF_BASE = 36
    private const val BLOB_BASE = 39

    // DIR_SOUTH 1 / NORTH 2 / WEST 3 / EAST 4 -> 0 / 1 / 2 / 2.
    private fun dir(facing: Int) = when (facing) { 2 -> 1; 3, 4 -> 2; else -> 0 }

    fun index(mode: Int, facing: Int, stepping: Boolean, foot: Int): Int {
        val d = dir(facing)
        if (mode == PresenceMode.SURF) return SURF_BASE + d
        val base = mode * SHEET
        return if (stepping) base + 3 + d * 2 + (foot and 1) else base + d
    }

    fun blob(mode: Int, facing: Int): Int = if (mode == PresenceMode.SURF) BLOB_BASE + dir(facing) else -1
}

/**
 * Buffers the partner's presence updates and gives one placement per frame, [delayMs] behind the sender's clock.
 * Updates are timed in the sender's wall-clock ms (not game frames), so a sender at 4x or 0.25x moves at its real pace.
 * The sender's clock is mapped onto ours by the smallest arrival - send time seen lately (the least-delayed update), and
 * the render time never goes backwards, nor does an update older than the newest get in: the partner never steps back.
 * Not thread-safe: push and at from one thread (the emu thread).
 */
class PresenceSmoother(val delayMs: Long = 150, val staleMs: Long = 3000) {
    private class Entry(val u: PresenceUpdate, val arrivedMs: Long)

    private val buf = ArrayList<Entry>(RING + 1)   // by timeMs, oldest first; everything from the shown segment on
    private val offsets = IntArray(OFFSETS)
    private var offsetCount = 0
    private var offsetNext = 0
    private var lastT = 0L
    private var lastNow = 0L
    private var hasLastT = false

    fun push(u: PresenceUpdate, arrivedMs: Long) {
        // A sender silent past staleMs may come back on a restarted clock: start over rather than drop it forever.
        buf.lastOrNull()?.let { if (arrivedMs - it.arrivedMs > staleMs) reset() }
        offsets[offsetNext] = (arrivedMs - u.timeMs).toInt()
        offsetNext = (offsetNext + 1) % OFFSETS
        if (offsetCount < OFFSETS) offsetCount++
        val newest = buf.lastOrNull()
        if (newest != null && diff(u.timeMs, newest.u.timeMs) <= 0) return   // late or a duplicate
        buf.add(Entry(u, arrivedMs))
        if (buf.size > RING) buf.removeAt(0)   // a path longer than RING updates behind the shown one is dropped
    }

    /** The partner at [nowMs] (our clock) for a player on map ([localGroup], [localNum]); null = hidden. */
    fun at(nowMs: Long, localGroup: Int, localNum: Int): PartnerPlacement? {
        val newest = buf.lastOrNull() ?: return null
        if (nowMs - newest.arrivedMs > staleMs) return null
        val target = (nowMs - minOffset() - delayMs) and MASK
        var t = target
        if (hasLastT) {
            // Never backwards; behind the target it runs at 2x until it rejoins it (a burst after a stall is walked
            // through, not jumped over); past the newest update it holds there (starved).
            val dt = max(0L, nowMs - lastNow)
            val ahead = diff(target, lastT)
            t = if (ahead > 0) (lastT + minOf(2 * dt, ahead.toLong())) and MASK else lastT
        }
        if (diff(t, newest.u.timeMs) > 0) t = newest.u.timeMs and MASK
        lastT = t
        lastNow = nowMs
        hasLastT = true

        val bi = buf.indexOfFirst { diff(it.u.timeMs, t) >= 0 }
        val shown: PresenceUpdate
        var x: Int
        var y: Int
        var dx = 0
        var dy = 0
        when {
            bi < 0 -> newest.u.let { shown = it; x = it.fineX; y = it.fineY }          // past the newest: held
            bi == 0 -> buf[0].u.let { shown = it; x = it.fineX; y = it.fineY }         // before the oldest
            else -> {
                val a = buf[bi - 1].u
                val b = buf[bi].u
                if (snaps(a, b)) {
                    // A jump or a map change: no lerp, the old place until the new one is due.
                    val at = if (diff(b.timeMs, t) == 0) b else a
                    shown = at; x = at.fineX; y = at.fineY
                } else {
                    val span = diff(b.timeMs, a.timeMs).toLong()
                    val into = diff(t, a.timeMs).toLong()
                    dx = b.fineX - a.fineX
                    dy = b.fineY - a.fineY
                    x = a.fineX + (dx * into / span).toInt()
                    y = a.fineY + (dy * into / span).toInt()
                    shown = b
                }
            }
        }
        // Nothing behind the segment being shown is needed again.
        val keepFrom = if (bi < 0) buf.size - 1 else max(0, bi - 1)
        if (keepFrom > 0) buf.subList(0, keepFrom).clear()
        if (!shown.valid || shown.mapGroup != localGroup || shown.mapNum != localNum) return null
        val stepping = firstHalf(dx, dy, x, y)
        val surfing = shown.mode == PresenceMode.SURF
        return PartnerPlacement(
            fineX = x, fineY = y,
            frame = PartnerFrames.index(shown.mode, shown.facing, stepping, shown.step and 1),
            hflip = shown.facing == EAST,
            blobFrame = PartnerFrames.blob(shown.mode, shown.facing),
            bob = if (surfing) BOB[Math.floorMod(nowMs / BOB_MS, BOB.size.toLong()).toInt()] else 0,
            female = shown.female,
            lookId = shown.lookId,
        )
    }

    fun reset() {
        buf.clear()
        offsetCount = 0
        offsetNext = 0
        hasLastT = false
    }

    private fun minOffset(): Int {
        var m = offsets[0]
        for (i in 1 until offsetCount) if (offsets[i] < m) m = offsets[i]
        return m
    }

    private fun snaps(a: PresenceUpdate, b: PresenceUpdate) =
        a.valid != b.valid || a.mapGroup != b.mapGroup || a.mapNum != b.mapNum ||
            max(abs(b.fineX - a.fineX), abs(b.fineY - a.fineY)) > MAX_LERP_PX

    // Moving and in the first half of the tile along the way it goes: 1..8 east / south, 8..15 west / north.
    private fun firstHalf(dx: Int, dy: Int, x: Int, y: Int): Boolean = when {
        dx > 0 -> x % 16 in 1..8
        dx < 0 -> x % 16 in 8..15
        dy > 0 -> y % 16 in 1..8
        dy < 0 -> y % 16 in 8..15
        else -> false
    }

    private companion object {
        const val RING = 64
        const val OFFSETS = 64
        const val MASK = 0xFFFFFFFFL
        const val MAX_LERP_PX = 48   // two tiles plus: further than that is a warp, not a walk
        const val EAST = 4
        const val BOB_MS = 266L
        val BOB = intArrayOf(0, 1, 2, 1)

        /** a - b on the sender's 32-bit clock, wrap included. */
        fun diff(a: Long, b: Long): Int = (a - b).toInt()
    }
}
