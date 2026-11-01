package com.pokedaisy.app.companion.data.coop

import com.pokedaisy.app.companion.data.LookChar

/** How the sender is moving: picks the partner's sheet (walking, running, Mach / Acro Bike, surfing). */
object PresenceMode {
    const val WALK = 0
    const val RUN = 1
    const val MACH = 2
    const val ACRO = 3
    const val SURF = 4
}

/** One co-op presence update: where the sender's hero is on their map and how it looks / moves. */
data class PresenceUpdate(
    val timeMs: Long,        // sender's SystemClock.elapsedRealtime() & 0xFFFFFFFF
    val valid: Boolean,      // the sender is on a map (not title / intro)
    val female: Boolean,
    val mapGroup: Int, val mapNum: Int,   // 0..255 each
    val fineX: Int, val fineY: Int,       // object-event coords (MAP_OFFSET included) x 16 + sub-tile pixels, 0..32767
    val facing: Int,         // the game's DIR_SOUTH 1 / NORTH 2 / WEST 3 / EAST 4
    val mode: Int,           // PresenceMode.*
    val step: Int,           // 0..255, +1 each tile the sender steps onto (which foot: step & 1)
    val lookId: Int,         // PresenceLook id, 0 = the gender's own hero
)

/**
 * The presence wire format, little-endian, [SIZE] bytes: 0 version, 1 flags (bit 0 valid, bit 1 female, other bits 0),
 * 2 mapGroup, 3 mapNum, 4 u16 fineX, 6 u16 fineY, 8 facing, 9 mode, 10 step, 11 lookId, 12 u32 timeMs, 16 u32 reserved (0).
 * [decode] drops a message whole when anything is outside those ranges: nothing is clamped.
 */
object PresenceCodec {
    const val VERSION = 1
    const val SIZE = 20

    private const val FLAG_VALID = 1
    private const val FLAG_FEMALE = 2
    private const val MAX_FINE = 32767

    fun encode(u: PresenceUpdate): ByteArray {
        require(inRange(u)) { "presence update out of range: $u" }
        val b = ByteArray(SIZE)
        b[0] = VERSION.toByte()
        b[1] = ((if (u.valid) FLAG_VALID else 0) or (if (u.female) FLAG_FEMALE else 0)).toByte()
        b[2] = u.mapGroup.toByte()
        b[3] = u.mapNum.toByte()
        putU16(b, 4, u.fineX)
        putU16(b, 6, u.fineY)
        b[8] = u.facing.toByte()
        b[9] = u.mode.toByte()
        b[10] = u.step.toByte()
        b[11] = u.lookId.toByte()
        putU32(b, 12, u.timeMs)
        return b
    }

    fun decode(b: ByteArray): PresenceUpdate? {
        if (b.size != SIZE || u8(b, 0) != VERSION) return null
        val flags = u8(b, 1)
        if (flags and (FLAG_VALID or FLAG_FEMALE).inv() != 0) return null
        if (u32(b, 16) != 0L) return null
        val u = PresenceUpdate(
            timeMs = u32(b, 12),
            valid = flags and FLAG_VALID != 0,
            female = flags and FLAG_FEMALE != 0,
            mapGroup = u8(b, 2), mapNum = u8(b, 3),
            fineX = u16(b, 4), fineY = u16(b, 6),
            facing = u8(b, 8), mode = u8(b, 9), step = u8(b, 10), lookId = u8(b, 11),
        )
        return u.takeIf(::inRange)
    }

    // A lookId past the pinned ones is a hostile update, refused whole (PresenceLook.charOf still reads it as null).
    private fun inRange(u: PresenceUpdate) =
        u.timeMs in 0L..0xFFFFFFFFL && u.mapGroup in 0..255 && u.mapNum in 0..255 &&
            u.fineX in 0..MAX_FINE && u.fineY in 0..MAX_FINE && u.facing in 1..4 &&
            u.mode in PresenceMode.WALK..PresenceMode.SURF && u.step in 0..255 && u.lookId in 0..PresenceLook.MAX_ID

    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF
    private fun u16(b: ByteArray, at: Int) = u8(b, at) or (u8(b, at + 1) shl 8)
    private fun u32(b: ByteArray, at: Int) = (u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16))

    private fun putU16(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte()
        b[at + 1] = (v shr 8).toByte()
    }

    private fun putU32(b: ByteArray, at: Int, v: Long) {
        putU16(b, at, (v and 0xFFFF).toInt())
        putU16(b, at + 2, ((v shr 16) and 0xFFFF).toInt())
    }
}

/**
 * The look ids sent in [PresenceUpdate.lookId]. Pinned: never renumber (the other device may run another build).
 * 0 = the gender's own hero, also what a PLAYER LOOK Pokémon sends. On receiving, [PresenceCodec.decode] refuses an id past
 * [MAX_ID] (the whole update); [charOf] gives null for 0 and for an id with no hero.
 */
object PresenceLook {
    private val CHARS = LookChar.entries.associateBy { idOf(it) }

    /** The highest pinned id: [PresenceCodec.decode] refuses anything past it. */
    const val MAX_ID = 8

    fun idOf(c: LookChar?): Int = when (c) {   // exhaustive: a new LookChar fails the build until it has an id
        null -> 0
        LookChar.RED -> 1
        LookChar.LEAF -> 2
        LookChar.BRENDAN -> 3
        LookChar.MAY -> 4
        LookChar.RS_BRENDAN -> 5
        LookChar.RS_MAY -> 6
        LookChar.GOLD -> 7
        LookChar.KRIS -> 8
    }

    fun charOf(id: Int): LookChar? = CHARS[id]
}
