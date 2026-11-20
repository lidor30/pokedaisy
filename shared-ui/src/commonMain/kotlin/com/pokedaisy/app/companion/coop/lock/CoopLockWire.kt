package com.pokedaisy.app.companion.coop.lock

import com.pokedaisy.app.companion.data.coop.CoopStory

sealed interface LockMsg {
    data class Propose(val partyCount: Int) : LockMsg
    data class Answer(val accepted: Boolean, val partyCount: Int) : LockMsg
    data object Off : LockMsg
    data class Status(val locked: Boolean) : LockMsg
    data class Hold(val id: Int) : LockMsg
}

/** The lock's messages (types 0x0300-0x03FF), little-endian, byte 0 = [VERSION]. Anything off is dropped whole. */
object CoopLockWire {
    const val VERSION = 1
    const val PROPOSE = 0x0301
    const val ANSWER = 0x0302
    const val OFF = 0x0303
    const val STATUS = 0x0304
    const val HOLD = 0x0310

    fun encode(m: LockMsg): Pair<Int, ByteArray> = when (m) {
        is LockMsg.Propose -> PROPOSE to byteArrayOf(VERSION.toByte(), m.partyCount.toByte())
        is LockMsg.Answer -> ANSWER to byteArrayOf(VERSION.toByte(), if (m.accepted) 1 else 0, m.partyCount.toByte())
        LockMsg.Off -> OFF to byteArrayOf(VERSION.toByte())
        is LockMsg.Status -> STATUS to byteArrayOf(VERSION.toByte(), if (m.locked) 1 else 0)
        is LockMsg.Hold -> HOLD to byteArrayOf(VERSION.toByte(), (m.id and 0xFF).toByte(), ((m.id shr 8) and 0xFF).toByte())
    }

    fun decode(type: Int, payload: ByteArray, knownId: (Int) -> Boolean = { CoopStory.byId(it) != null }): LockMsg? {
        val expected = when (type) {
            PROPOSE -> 2
            ANSWER -> 3
            OFF -> 1
            STATUS -> 2
            HOLD -> 3
            else -> return null
        }
        if (payload.size != expected || u8(payload, 0) != VERSION) return null
        return when (type) {
            PROPOSE -> LockMsg.Propose(u8(payload, 1))
            ANSWER -> flag(payload, 1)?.let { LockMsg.Answer(it, u8(payload, 2)) }
            OFF -> LockMsg.Off
            STATUS -> flag(payload, 1)?.let { LockMsg.Status(it) }
            else -> {
                val id = u8(payload, 1) or (u8(payload, 2) shl 8)
                if (id == 0 || knownId(id)) LockMsg.Hold(id) else null
            }
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun flag(b: ByteArray, i: Int): Boolean? = when (u8(b, i)) { 0 -> false; 1 -> true; else -> null }
}
