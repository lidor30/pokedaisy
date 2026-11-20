@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.core

import com.pokedaisy.app.companion.data.coop.CoopCore
import com.pokedaisy.app.companion.data.coop.CoopLinkEngine
import com.pokedaisy.app.core.pkapi.*
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned

/** iOS bindings for the same Emerald link bridge and game operations as Android JNI. */
object PkCoopCore : CoopCore, CoopLinkEngine {
    override fun install(): Boolean = pk_api_coop_enable() && pk_api_coop_link_enable()
    fun disable() = pk_api_coop_disable()

    override fun refused(): String {
        fun reason(link: Boolean): String = ByteArray(256).usePinned {
            pk_api_coop_refusal(link, it.addressOf(0), 256u)
            it.addressOf(0).toKString()
        }
        return "check: ${reason(false)}; bridge: ${reason(true)}"
    }

    override fun joinKind() = pk_api_coop_join_kind()
    override fun joinTrainers(): IntArray = IntArray(2).also { ids ->
        ids.usePinned { pk_api_coop_join_trainers(it.addressOf(0)) }
    }
    override fun partnerFree() = pk_api_coop_partner_free()
    override fun giveMon(mon: ByteArray): Int =
        mon.withData { bytes, size -> pk_api_coop_give_mon(bytes, size) }
    override fun removeItem(item: Int, count: Int) = pk_api_coop_remove_item(item, count)
    override fun setTrainerFlag(trainerId: Int) = pk_api_coop_set_trainer_flag(trainerId)
    override fun prize(trainerId: Int, single: Boolean) = pk_api_coop_prize(trainerId, single)
    override fun addMoney(amount: Int) = pk_api_coop_add_money(amount)
    override fun writePartyMon(slot: Int, mon: ByteArray): Int =
        mon.withData { bytes, size -> pk_api_coop_write_party_mon(slot, bytes, size) }
    override fun whiteOut() = pk_api_coop_white_out()
    override fun evolve(slot: Int) = pk_api_coop_evolve(slot)
    override fun saveGame() = pk_api_coop_save_game()
    override fun onFreeField() = pk_api_coop_on_free_field()

    override fun take(): ByteArray? = readData(PK_API_COOP_LINK_PACKET_SIZE.toInt(), ::pk_api_coop_link_take)
    override fun give(block: ByteArray): Boolean = block.withData(::pk_api_coop_link_give)
    override fun state() = pk_api_coop_link_state()
    override fun badBlocks(): ByteArray? = readData(PK_API_COOP_LINK_DEBUG_SIZE.toInt(), ::pk_api_coop_link_bad_blocks)
    override fun leave(blackout: Boolean) = pk_api_coop_link_leave(blackout)
    override fun result(): ByteArray? = readData(PK_API_COOP_LINK_RESULT_SIZE.toInt(), ::pk_api_coop_link_result)
    override fun caught(): ByteArray? = readData(PK_API_COOP_LINK_CATCH_SIZE.toInt(), ::pk_api_coop_link_caught)
    override fun resetBridge() = pk_api_coop_link_reset()
    override fun settled() = onFreeField()
    override fun hostHold() = pk_api_coop_link_host_hold()
    override fun hostGo(name: ByteArray, gender: Int, otId: Int): Boolean =
        name.withData { bytes, size -> pk_api_coop_link_host_go(bytes, size, gender, otId.toUInt()) }
    override fun partnerGo(kind: Int, trainerA: Int, trainerB: Int, name: ByteArray, gender: Int, otId: Int): Int =
        name.withData { bytes, size ->
            pk_api_coop_link_partner_go(kind, trainerA, trainerB, bytes, size, gender, otId.toUInt())
        }
    override fun storyArm(trainerId: Int) = pk_api_coop_story_arm(trainerId)
    override fun storyState() = pk_api_coop_story_state()
    override fun storyGo(master: Boolean, name: ByteArray, gender: Int, otId: Int): Boolean =
        name.withData { bytes, size -> pk_api_coop_story_go(master, bytes, size, gender, otId.toUInt()) }
    override fun storyCancel() = pk_api_coop_story_cancel()
    override fun storyDisarm() = pk_api_coop_story_disarm()
    override fun releaseScript() = pk_api_coop_release_script()

    fun partnerPosition(): IntArray? {
        val values = IntArray(PK_API_PARTNER_SELF_SIZE.toInt())
        return values.takeIf { it.usePinned { pinned -> pk_api_partner_self(pinned.addressOf(0), values.size.toULong()) } }
    }

    fun partnerArt(bytes: ByteArray?) {
        if (bytes == null) pk_api_partner_set(null, 0u)
        else bytes.withData(::pk_api_partner_set)
    }

    fun placePartner(placement: com.pokedaisy.app.companion.data.coop.PartnerPlacement?) {
        if (placement == null) pk_api_partner_place(false, 0, 0, 0, false, -1, 0)
        else with(placement) { pk_api_partner_place(true, fineX, fineY, frame, hflip, blobFrame, bob) }
    }

    fun installStoryLock() = pk_api_coop_lock_install()
    fun uninstallStoryLock() = pk_api_coop_lock_uninstall()
    fun activateStoryLock(on: Boolean) = pk_api_coop_lock_set_active(on)
    fun heldStory(): IntArray = IntArray(2).also { ids ->
        ids.usePinned { pk_api_coop_lock_held(it.addressOf(0)) }
    }
    fun continueStory(id: Int) = pk_api_coop_lock_go(id)
    fun cancelStory() = pk_api_coop_lock_cancel()

    private inline fun <T> ByteArray.withData(run: (CPointer<UByteVar>?, ULong) -> T): T =
        if (isEmpty()) run(null, 0u) else usePinned { run(it.addressOf(0).reinterpret(), size.toULong()) }

    private inline fun readData(capacity: Int, read: (CPointer<UByteVar>?, ULong) -> Int): ByteArray? {
        val buffer = ByteArray(capacity)
        val size = buffer.withData(read)
        return if (size in 1..capacity) buffer.copyOf(size) else null
    }
}
