package com.pokedaisy.app.companion.data.coop

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A name that says nothing: eight 0xFF bytes, the game's string terminator. */
val COOP_NO_NAME: ByteArray get() = ByteArray(8) { 0xFF.toByte() }

/** Why a joined battle did not start or ended early. The ordinal is the wire value. */
enum class CoopCancel { TIMEOUT, DECLINED, ENGINE, LINK, BUSY, CLOSED }

/** One battle message. Every one but [Status] carries the host's non-zero battle id. */
sealed interface CoopBattleMsg {
    val battleId: Int

    /** [story]: the story event id the sender stands at (or waits parked at) with its trainer flag unset, else 0. */
    data class Status(val free: Boolean, val ready: Boolean, val story: Int = 0) : CoopBattleMsg { override val battleId get() = 0 }
    /** [hostName] / [hostGender] / [hostOtId]: who offers (the partner's game needs them to enter the link battle). */
    data class Offer(
        override val battleId: Int, val kind: Int, val trainerA: Int, val trainerB: Int, val species: Int,
        val hostName: ByteArray = COOP_NO_NAME, val hostGender: Int = 0, val hostOtId: Int = 0,
    ) : CoopBattleMsg {
        override fun equals(other: Any?) = other is Offer && battleId == other.battleId && kind == other.kind && trainerA == other.trainerA &&
            trainerB == other.trainerB && species == other.species && hostName.contentEquals(other.hostName) &&
            hostGender == other.hostGender && hostOtId == other.hostOtId
        override fun hashCode() = listOf(battleId, kind, trainerA, trainerB, species, hostName.contentHashCode(), hostGender, hostOtId).hashCode()
        override fun toString() = "Offer(battleId=$battleId, kind=$kind, trainerA=$trainerA, trainerB=$trainerB, species=$species)"
    }

    class Answer(
        override val battleId: Int, val accept: Boolean, val team: ByteArray = ByteArray(0), val count: Int = 0,
        val name: ByteArray = ByteArray(0), val gender: Int = 0, val otId: Int = 0, val partySlots: IntArray = IntArray(0),
    ) : CoopBattleMsg {
        override fun equals(other: Any?) = other is Answer && battleId == other.battleId && accept == other.accept &&
            team.contentEquals(other.team) && count == other.count && name.contentEquals(other.name) &&
            gender == other.gender && otId == other.otId && partySlots.contentEquals(other.partySlots)
        override fun hashCode() = listOf(battleId, accept, team.contentHashCode(), count, name.contentHashCode(), gender, otId, partySlots.contentHashCode()).hashCode()
        override fun toString() = "Answer(battleId=$battleId, accept=$accept, count=$count)"
    }

    data class Cancel(override val battleId: Int, val reason: CoopCancel) : CoopBattleMsg

    /** The catch the host named in [CaughtMon] is in the receiving game (after its save): the host may forget it. */
    data class ResultAck(override val battleId: Int) : CoopBattleMsg

    /** One link block of the bridge: [bytes] = u8 kind, u8 seq, u16 size LE, payload; [seq] equals bytes[1]. */
    class LinkBlock(override val battleId: Int, val seq: Int, val bytes: ByteArray) : CoopBattleMsg {
        override fun equals(other: Any?) = other is LinkBlock && battleId == other.battleId && seq == other.seq && bytes.contentEquals(other.bytes)
        override fun hashCode() = listOf(battleId, seq, bytes.contentHashCode()).hashCode()
        override fun toString() = "LinkBlock(battleId=$battleId, seq=$seq, size=${bytes.size})"
    }

    /** The partner's game is in the link battle's setup (it started first): the host's game may begin now. */
    data class LinkReady(override val battleId: Int) : CoopBattleMsg

    /** What the battle caught, from the host once it is over: [by] 1 = the host's own ball (nothing to send), 2 = the partner's, with the Pokemon's 100 bytes. */
    class CaughtMon(override val battleId: Int, val by: Int, val mon: ByteArray = ByteArray(0)) : CoopBattleMsg {
        override fun equals(other: Any?) = other is CaughtMon && battleId == other.battleId && by == other.by && mon.contentEquals(other.mon)
        override fun hashCode() = listOf(battleId, by, mon.contentHashCode()).hashCode()
        override fun toString() = "CaughtMon(battleId=$battleId, by=$by)"
    }

    /** A story join's progress: the sender's `pk_coop_story_*` state (0-5), or [STORY_GO] (6) from the host: both are ready, begin. */
    data class StoryState(override val battleId: Int, val state: Int) : CoopBattleMsg
}

/** The battle messages' bytes (little-endian). Everything decoded is untrusted: [decode] gives null for anything malformed. */
object CoopBattleWire {
    const val STATUS = 0x0200
    const val OFFER = 0x0201
    const val ANSWER = 0x0202
    const val CANCEL = 0x0203
    // 0x0204-0x020E were the lockstep replica's messages (gone with it): never reused, [decode] refuses them.
    const val RESULT_ACK = 0x020F
    const val LINK_BLOCK = 0x0210
    const val LINK_READY = 0x0211
    const val CAUGHT_MON = 0x0212
    const val STORY_STATE = 0x0213
    /** [CoopBattleMsg.StoryState.state] from the host: begin the story battle. */
    const val STORY_GO = 6
    /** The most payload bytes one link block may carry. */
    const val MAX_LINK_PAYLOAD = 0x4000

    private const val TRAINER_COUNT = 855
    private const val MAX_STORY_ID = 0x3FF
    private const val NAME_SIZE = 8

    /** The type and payload of [m]; throws when a field couldn't survive [decode]. */
    fun encode(m: CoopBattleMsg): Pair<Int, ByteArray> {
        val type = typeOf(m)
        val out = ByteBuffer.allocate(payloadSize(m)).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(m.battleId)
        when (m) {
            is CoopBattleMsg.Status -> { out.put(((if (m.free) 1 else 0) or (if (m.ready) 2 else 0)).toByte()); out.putShort(m.story.toShort()) }
            is CoopBattleMsg.Offer -> {
                require(m.hostName.size == NAME_SIZE) { "name is ${m.hostName.size} bytes" }
                out.put(m.kind.toByte()); out.putShort(m.trainerA.toShort()); out.putShort(m.trainerB.toShort()); out.putShort(m.species.toShort())
                out.put(m.hostName); out.put(m.hostGender.toByte()); out.putInt(m.hostOtId)
            }
            is CoopBattleMsg.Answer -> {
                out.put(if (m.accept) 1 else 0)
                if (m.accept) {
                    require(m.count in 1..3 && m.team.size == m.count * CoopBattle.MON_SIZE && m.name.size == NAME_SIZE && m.partySlots.size == m.count) { "bad answer" }
                    out.put(m.count.toByte()); out.put(m.team); out.put(m.name); out.put(m.gender.toByte()); out.putInt(m.otId)
                    m.partySlots.forEach { out.put(it.toByte()) }
                }
            }
            is CoopBattleMsg.Cancel -> out.put(m.reason.ordinal.toByte())
            is CoopBattleMsg.ResultAck -> {}
            is CoopBattleMsg.LinkReady -> {}
            is CoopBattleMsg.CaughtMon -> {
                require(m.by == 1 && m.mon.isEmpty() || m.by == 2 && m.mon.size == CoopBattle.MON_SIZE) { "bad caught mon" }
                out.put(m.by.toByte()); out.put(m.mon)
            }
            is CoopBattleMsg.StoryState -> out.put(m.state.toByte())
            is CoopBattleMsg.LinkBlock -> { require(m.bytes.size >= 4 && (m.bytes[1].toInt() and 0xFF) == m.seq) { "bad link block" }; out.put(m.bytes) }
        }
        val payload = out.array()
        require(decode(type, payload) != null) { "$m would not decode" }
        return type to payload
    }

    private fun typeOf(m: CoopBattleMsg) = when (m) {
        is CoopBattleMsg.Status -> STATUS
        is CoopBattleMsg.Offer -> OFFER
        is CoopBattleMsg.Answer -> ANSWER
        is CoopBattleMsg.Cancel -> CANCEL
        is CoopBattleMsg.ResultAck -> RESULT_ACK
        is CoopBattleMsg.LinkBlock -> LINK_BLOCK
        is CoopBattleMsg.LinkReady -> LINK_READY
        is CoopBattleMsg.CaughtMon -> CAUGHT_MON
        is CoopBattleMsg.StoryState -> STORY_STATE
    }

    private fun payloadSize(m: CoopBattleMsg): Int = 4 + when (m) {
        is CoopBattleMsg.Cancel -> 1
        is CoopBattleMsg.Status -> 3
        is CoopBattleMsg.Offer -> 7 + NAME_SIZE + 1 + 4
        is CoopBattleMsg.Answer -> if (m.accept) 1 + 1 + m.team.size + NAME_SIZE + 1 + 4 + m.partySlots.size else 1
        is CoopBattleMsg.ResultAck -> 0
        is CoopBattleMsg.LinkBlock -> m.bytes.size
        is CoopBattleMsg.LinkReady -> 0
        is CoopBattleMsg.CaughtMon -> 1 + m.mon.size
        is CoopBattleMsg.StoryState -> 1
    }

    /** The message, or null for an unknown type, a wrong length, a field out of range or a Pokémon [CoopBattle.check] refuses. Never throws. */
    fun decode(type: Int, payload: ByteArray): CoopBattleMsg? = try {
        decodeChecked(type, payload)
    } catch (e: RuntimeException) {
        null
    }

    private fun decodeChecked(type: Int, payload: ByteArray): CoopBattleMsg? {
        if (type !in STATUS..STORY_STATE || payload.size < 4) return null   // the removed replica types in between fall to the `else` below
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val id = b.int
        if ((id == 0) != (type == STATUS)) return null
        val msg: CoopBattleMsg = when (type) {
            STATUS -> {
                val f = u8(b)
                val story = u16(b)
                if (f > 3 || story > MAX_STORY_ID) return null
                CoopBattleMsg.Status(f and 1 != 0, f and 2 != 0, story)
            }
            OFFER -> {
                val kind = u8(b); val ta = u16(b); val tb = u16(b); val sp = u16(b)
                if (kind !in 1..4 || ta >= TRAINER_COUNT || tb >= TRAINER_COUNT) return null
                if (kind == 1 && (ta != 0 || tb != 0)) return null
                if (kind == 2 && (ta == 0 || tb == 0)) return null      // two trainers: both in 1..854
                if (kind == 3 && (ta == 0 || tb != 0)) return null      // one trainer: A in 1..854, no B
                if (kind == 4 && ta == 0) return null                   // story: the trainer at the spot (a double has B too)
                val name = bytes(b, NAME_SIZE); val gender = u8(b); val otId = b.int
                if (gender > 1 || !validName(name)) return null
                CoopBattleMsg.Offer(id, kind, ta, tb, sp, name, gender, otId)
            }
            ANSWER -> when (u8(b)) {
                0 -> CoopBattleMsg.Answer(id, false)
                1 -> {
                    val count = u8(b)
                    if (count !in 1..3) return null
                    val team = bytes(b, count * CoopBattle.MON_SIZE)
                    for (i in 0 until count) {
                        if (CoopBattle.check(team.copyOfRange(i * CoopBattle.MON_SIZE, (i + 1) * CoopBattle.MON_SIZE)) != CoopMonCheck.Ok) return null
                    }
                    val name = bytes(b, NAME_SIZE); val gender = u8(b); val otId = b.int
                    val slots = IntArray(count) { u8(b) }
                    if (gender > 1 || !validName(name) || slots.any { it !in 0..5 } || slots.toSet().size != count) return null
                    CoopBattleMsg.Answer(id, true, team, count, name, gender, otId, slots)
                }
                else -> return null
            }
            CANCEL -> CoopBattleMsg.Cancel(id, CoopCancel.entries.getOrNull(u8(b)) ?: return null)
            LINK_BLOCK -> {
                val blk = bytes(b, b.remaining())
                if (!validLinkBlock(blk)) return null
                CoopBattleMsg.LinkBlock(id, blk[1].toInt() and 0xFF, blk)
            }
            LINK_READY -> CoopBattleMsg.LinkReady(id)
            CAUGHT_MON -> {
                val by = u8(b)
                when (by) {
                    1 -> CoopBattleMsg.CaughtMon(id, 1)
                    2 -> {
                        val mon = bytes(b, CoopBattle.MON_SIZE)
                        if (CoopBattle.check(mon) != CoopMonCheck.Ok) return null
                        CoopBattleMsg.CaughtMon(id, 2, mon)
                    }
                    else -> return null
                }
            }
            STORY_STATE -> {
                val st = u8(b)
                if (st > STORY_GO) return null
                CoopBattleMsg.StoryState(id, st)
            }
            RESULT_ACK -> CoopBattleMsg.ResultAck(id)
            else -> return null
        }
        return if (b.hasRemaining()) null else msg
    }

    /** A player name from the network: a 0xFF terminator within the first 8 bytes, only the game's own characters (0x00-0xF4) before it. */
    fun validName(name: ByteArray): Boolean {
        if (name.size != NAME_SIZE) return false
        val end = name.indexOfFirst { it == 0xFF.toByte() }
        if (end !in 0 until NAME_SIZE) return false
        for (i in 0 until end) if ((name[i].toInt() and 0xFF) > 0xF4) return false
        return true
    }

    /** The generic shape every link block has: 4-byte header whose size field is the payload length, within the cap. Per-kind checks are the bridge's. */
    fun validLinkBlock(blk: ByteArray): Boolean {
        if (blk.size < 4) return false
        val size = (blk[2].toInt() and 0xFF) or ((blk[3].toInt() and 0xFF) shl 8)
        return size == blk.size - 4 && size <= MAX_LINK_PAYLOAD
    }

    private fun u8(b: ByteBuffer) = b.get().toInt() and 0xFF
    private fun u16(b: ByteBuffer) = b.short.toInt() and 0xFFFF
    private fun bytes(b: ByteBuffer, n: Int): ByteArray = ByteArray(n).also { b.get(it) }
}

