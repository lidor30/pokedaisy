package com.pokedaisy.app.companion

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.companion.data.AutoRunPolicy
import com.pokedaisy.app.companion.data.POCKET_TM_HM
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.hasData
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Observes the game events that make contextual tips relevant for a running cart. */
class GameTipMoments {
    private val walkingLock = SynchronizedObject()
    private var walkFrames = 0
    private var runFrames = 0
    private var inBattle = false
    private var trainer = false
    private var tms: Map<Int, Int>? = null
    private var mapWasOpen = false
    private val wildEnds = ArrayDeque<Long>()

    /** Called with the player's keys, before any automated input. */
    fun movement(keys: Int, fieldState: Int, onField: Boolean, autoRun: Boolean) {
        if (autoRun || !AutoRunPolicy.walking(keys, fieldState, onField)) return
        synchronized(walkingLock) {
            walkFrames++
            if (keys and GbaControls.Btn.B.bit != 0) runFrames++
        }
    }

    fun observe(snapshot: SnapshotView, mapOpen: Boolean, tips: TipScheduler) {
        val ran = synchronized(walkingLock) {
            if (walkFrames < 60 * 60) false else {
                val often = runFrames * 10 >= walkFrames * 6
                walkFrames = 0
                runFrames = 0
                often
            }
        }
        if (ran) tips.moment(TipId.AUTO_RUN)
        if (snapshot.inBattle) {
            if (!inBattle) trainer = false
            inBattle = true
            if (snapshot.enemyParty.isNotEmpty()) trainer = true
        } else if (inBattle) {
            inBattle = false
            if (!trainer) {
                wildEnds.addLast(tips.playedMillis)
                if (wildEnds.size > 3) wildEnds.removeFirst()
                if (wildEnds.size == 3 && wildEnds.last() - wildEnds.first() <= 3 * TipScheduler.MINUTE) {
                    tips.moment(TipId.WILD)
                    wildEnds.clear()
                }
            }
        }
        if (snapshot.hasData && !snapshot.inBattle) {
            val now = snapshot.items.filter { it.pocket == POCKET_TM_HM }.associate { it.itemId to it.quantity }
            val before = tms
            if (before != null && before.isNotEmpty() && now.keys.all { it in before } &&
                before.entries.sumOf { (id, count) -> count - (now[id] ?: 0) } == 1 &&
                before.all { (id, count) -> (now[id] ?: 0) <= count }
            ) tips.moment(TipId.REUSABLE_TMS)
            tms = now
        }
        if (mapWasOpen && !mapOpen) tips.moment(TipId.FLY)
        mapWasOpen = mapOpen
    }
}
