package com.pokedaisy.app

import com.pokedaisy.app.companion.data.coop.CoopCore
import com.pokedaisy.app.companion.data.coop.CoopLinkEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

val CoopBattles = CoopBattleSession(android.os.SystemClock::elapsedRealtime, ::MgbaLinkEngine)

internal fun awaitCoopFrame(waitMs: Long, post: (() -> Unit) -> Unit, run: () -> Unit): Boolean {
    val done = CountDownLatch(1)
    post {
        try { run() } finally { done.countDown() }
    }
    return done.await(waitMs, TimeUnit.MILLISECONDS)
}

/** [MgbaCore]'s link-battle externals as a [com.pokedaisy.app.companion.data.coop.CoopLinkEngine]: straight to the native bridge, emu thread. */
class MgbaLinkEngine : com.pokedaisy.app.companion.data.coop.CoopLinkEngine {
    override fun take(): ByteArray? = MgbaCore.pkCoopLinkTake()
    override fun give(block: ByteArray) = MgbaCore.pkCoopLinkGive(block)
    override fun state() = MgbaCore.pkCoopLinkState()
    override fun badBlocks(): ByteArray? = MgbaCore.pkCoopLinkBadBlocks()
    override fun leave(blackout: Boolean) = MgbaCore.pkCoopLinkLeave(blackout)
    override fun result(): ByteArray? = MgbaCore.pkCoopLinkResult()
    override fun caught(): ByteArray? = MgbaCore.pkCoopLinkCaught()
    override fun resetBridge() = MgbaCore.pkCoopLinkReset()
    override fun settled() = MgbaCore.pkCoopOnFreeField()
    override fun install() = MgbaCore.pkCoopInstall() && MgbaCore.pkCoopLinkInstall()
    override fun refused(): String = listOf("check: " + MgbaCore.pkCoopRefused(), "bridge: " + MgbaCore.pkCoopLinkRefused()).joinToString("; ")
    override fun hostHold() = MgbaCore.pkCoopLinkHostHold()
    override fun hostGo(name: ByteArray, gender: Int, otId: Int) = MgbaCore.pkCoopLinkHostGo(name, gender, otId)
    override fun partnerGo(kind: Int, trainerA: Int, trainerB: Int, name: ByteArray, gender: Int, otId: Int) =
        MgbaCore.pkCoopLinkPartnerGo(kind, trainerA, trainerB, name, gender, otId)
    override fun storyArm(trainerId: Int) = MgbaCore.pkCoopStoryArm(trainerId)
    override fun storyState() = MgbaCore.pkCoopStoryState()
    override fun storyGo(master: Boolean, name: ByteArray, gender: Int, otId: Int) = MgbaCore.pkCoopStoryGo(master, name, gender, otId)
    override fun storyCancel() = MgbaCore.pkCoopStoryCancel()
    override fun storyDisarm() = MgbaCore.pkCoopStoryDisarm()
    override fun releaseScript() = MgbaCore.pkCoopReleaseScript()
}

/** [MgbaCore] as a [CoopCore]: every call goes straight to its external, on the emu thread. */
class MgbaCoopCore : CoopCore {
    override fun joinKind() = MgbaCore.pkCoopJoinKind()
    override fun joinTrainers(): IntArray = MgbaCore.pkCoopJoinTrainers()
    override fun partnerFree() = MgbaCore.pkCoopPartnerFree()
    override fun giveMon(mon: ByteArray) = MgbaCore.pkCoopGiveMon(mon)
    override fun removeItem(item: Int, count: Int) = MgbaCore.pkCoopRemoveItem(item, count)
    override fun setTrainerFlag(trainerId: Int) = MgbaCore.pkCoopSetTrainerFlag(trainerId)
    override fun prize(trainerId: Int, single: Boolean) = MgbaCore.pkCoopPrize(trainerId, single)
    override fun addMoney(amount: Int) = MgbaCore.pkCoopAddMoney(amount)
    override fun writePartyMon(slot: Int, mon: ByteArray) = MgbaCore.pkCoopWritePartyMon(slot, mon)
    override fun whiteOut() = MgbaCore.pkCoopWhiteOut()
    override fun evolve(slot: Int) = MgbaCore.pkCoopEvolve(slot)
    override fun saveGame() = MgbaCore.pkCoopSaveGame()
    override fun onFreeField() = MgbaCore.pkCoopOnFreeField()
}
