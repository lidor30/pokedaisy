package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.i18n.tr

/**
 * A Gen 3 save file (Ruby / Sapphire / Emerald / FireRed / LeafGreen's 128 KB flash), read and
 * written the way the games' own save.c does it:
 *
 * - Two save slots of 14 sectors (0x0000 and 0xE000), each sector 4 KB: 0xF80 bytes of data, then
 *   a footer with the sector's id (which chunk of which block it holds), a checksum, the
 *   signature 0x08012025 and the save counter. Sectors 28+ (Hall of Fame, Trainer Hill, recorded
 *   battle) and mGBA's RTC footer past 128 KB are never touched.
 * - The ids are rotated inside a slot (id 0 moves one sector on per save).
 * - The game loads the slot whose 14 sectors are all valid and whose counter is newer
 *   (GetSaveValidStatus), so an edit is written like a save: into the *other* slot with the
 *   counter + 1 and the rotation + 1, the loaded slot left as it was. If anything were wrong with
 *   the new slot, the game would fall back to the old one by itself.
 *
 * Pure Kotlin (no Android): the library's save editor, the tests and ui-preview share it.
 */
enum class Gen3SaveFormat(
    val label: String,
    /** sizeof(SaveBlock2) / sizeof(SaveBlock1) / sizeof(PokemonStorage): each sector's checksum covers its chunk of these. */
    val saveBlock2Size: Int,
    val saveBlock1Size: Int,
    val storageSize: Int = 0x83D0,
    /** SaveBlock1.playerPartyCount / playerParty. */
    val partyCountOff: Int,
    val partyOff: Int,
    /** SaveBlock1.money; XORed with SaveBlock2.encryptionKey ([encryptionKeyOff], -1 = none) like the game. */
    val moneyOff: Int,
    val encryptionKeyOff: Int,
    /** SaveBlock1.flags / .vars (vars[id - 0x4000]). */
    val flagsOff: Int,
    val varsOff: Int,
    /** SaveBlock1's two copies of the Pokédex "seen" bits (seen1 / seen2; R/S's dexSeen2 / dexSeen3), kept equal to
     * SaveBlock2's: GetSetPokedexFlag clears a flag whose copies disagree. */
    val seen1Off: Int,
    val seen2Off: Int,
    /** IsNationalPokedexEnabled: pokedex.nationalMagic (SaveBlock2) == [nationalMagic], VAR_NATIONAL_DEX == [nationalVarValue]
     * and FLAG_SYS_NATIONAL_DEX. */
    val nationalMagicOff: Int,
    val nationalMagic: Int,
    val nationalVar: Int,
    val nationalVarValue: Int,
    val nationalFlag: Int,
    /** FireRed / LeafGreen's FLAG_SYS_CAN_LINK_WITH_RS (the Sevii Islands done): needed to link with R/S/E. -1 elsewhere. */
    val linkRsFlag: Int,
    /** The regional dex a game keeps to before its National Dex. */
    val kanto: Boolean,
    /** SaveBlock1.coins (XORed with the key's low half, like the money). */
    val coinsOff: Int,
    /** SaveBlock1's bag pockets, in the order the struct has them. */
    val pockets: List<PocketLayout>,
    /** The most of one item a stack holds (AddBagItem's slotCapacity): FR / LG 999 everywhere, R/S / E 99 but berries. */
    val stackMax: Int,
) {
    // Offsets from the decomps' global.h (pokeruby / pokeemerald / pokefirered), flag / var ids from their constants.
    RUBY_SAPPHIRE(
        "RUBY / SAPPHIRE", 0x890, 0x3AC0, partyCountOff = 0x234, partyOff = 0x238, moneyOff = 0x490, encryptionKeyOff = -1,
        flagsOff = 0x1220, varsOff = 0x1340, seen1Off = 0x938, seen2Off = 0x3A8C,
        nationalMagicOff = 0x1A, nationalMagic = 0xDA, nationalVar = 0x4046, nationalVarValue = 0x302, nationalFlag = 0x836,
        linkRsFlag = -1, kanto = false, coinsOff = 0x494,
        pockets = pockets(0x560, items = 20, key = 20, balls = 16, tmhm = 64, berries = 46), stackMax = 99,
    ),
    EMERALD(
        "EMERALD", 0xF2C, 0x3D88, partyCountOff = 0x234, partyOff = 0x238, moneyOff = 0x490, encryptionKeyOff = 0xAC,
        flagsOff = 0x1270, varsOff = 0x139C, seen1Off = 0x988, seen2Off = 0x3B24,
        nationalMagicOff = 0x1A, nationalMagic = 0xDA, nationalVar = 0x4046, nationalVarValue = 0x302, nationalFlag = 0x896,
        linkRsFlag = -1, kanto = false, coinsOff = 0x494,
        pockets = pockets(0x560, items = 30, key = 30, balls = 16, tmhm = 64, berries = 46), stackMax = 99,
    ),
    FIRERED_LEAFGREEN(
        "FIRERED / LEAFGREEN", 0xF24, 0x3D68, partyCountOff = 0x34, partyOff = 0x38, moneyOff = 0x290, encryptionKeyOff = 0xF20,
        flagsOff = 0xEE0, varsOff = 0x1000, seen1Off = 0x5F8, seen2Off = 0x3A18,
        nationalMagicOff = 0x1B, nationalMagic = 0xB9, nationalVar = 0x404E, nationalVarValue = 0x6258, nationalFlag = 0x840,
        linkRsFlag = 0x844, kanto = true, coinsOff = 0x294,
        pockets = pockets(0x310, items = 42, key = 30, balls = 13, tmhm = 58, berries = 43), stackMax = 999,
    ),
    ;

    /** How many of [item] one stack in [pocket] may hold: berries 999, an HM 1 (the game hands each out once). */
    fun stackMax(pocket: BagPocket, item: Int): Int = when {
        pocket == BagPocket.TMHM && item >= FIRST_HM -> 1
        pocket == BagPocket.BERRIES -> 999
        else -> stackMax
    }

    fun pocket(p: BagPocket): PocketLayout = pockets.first { it.pocket == p }

    /** SaveBlock2.pokedex: order, mode, ..., owned[] at +0x10, seen[] at +0x44 (the same in all three). */
    val pokedexOff: Int get() = 0x18

    /** sSaveSlotLayout's size for sector [id]: SaveBlock2 in 0, SaveBlock1 in 1-4, PokemonStorage in 5-13. */
    fun chunkSize(id: Int): Int = when (id) {
        0 -> saveBlock2Size
        in 1..4 -> chunk(saveBlock1Size, id - 1)
        else -> chunk(storageSize, id - 5)
    }

    private fun chunk(size: Int, n: Int) = (size - n * Gen3SaveFile.DATA).coerceIn(0, Gen3SaveFile.DATA)

    companion object {
        /** ITEM_HM01: TMs are 289..338, HMs 339..346. */
        const val FIRST_HM = 339

        /**
         * The format a ROM's header game code (AXVE, BPRE, ...) saves in. Japanese builds (a `J` last)
         * have shorter names in their structs and aren't covered yet, so they get none.
         */
        fun forGameCode(code: String?): Gen3SaveFormat? {
            if (code == null || code.length < 4 || code[3] == 'J') return null
            return when (code.take(3)) {
                "AXV", "AXP" -> RUBY_SAPPHIRE
                "BPE" -> EMERALD
                "BPR", "BPG" -> FIRERED_LEAFGREEN
                else -> null
            }
        }
    }
}

/** The bag's pockets; [id] is the generated tables' pocket number (bagItems*), KEY's 5 isn't in them (never edited). */
enum class BagPocket(val id: Int) { ITEMS(1), BALLS(2), TMHM(3), BERRIES(4), KEY(5) }

/** A pocket's ItemSlot array ({u16 itemId; u16 quantity}) in SaveBlock1. */
class PocketLayout(val pocket: BagPocket, val off: Int, val slots: Int)

/** global.h's bagPocket_Items, _KeyItems, _PokeBalls, _TMHM, _Berries: back to back from [start], 4 bytes a slot.
 * (Top level: an enum's entries are built before its companion object.) */
private fun pockets(start: Int, items: Int, key: Int, balls: Int, tmhm: Int, berries: Int): List<PocketLayout> {
    var off = start
    return listOf(BagPocket.ITEMS to items, BagPocket.KEY to key, BagPocket.BALLS to balls, BagPocket.TMHM to tmhm, BagPocket.BERRIES to berries)
        .map { (p, n) -> PocketLayout(p, off, n).also { off += n * 4 } }
}

class Gen3SaveFile private constructor(
    val format: Gen3SaveFormat,
    /** The whole file as read (both slots, the extra sectors, any RTC footer). */
    val raw: ByteArray,
    /** The slot the game would load (0 or 1), its save counter and where its id 0 sits. */
    val slot: Int,
    val counter: Long,
    val rotation: Int,
    /** The other slot - the game's fallback - as the game would judge it. */
    val otherSlot: SlotStatus,
    val saveBlock2: ByteArray,
    val saveBlock1: ByteArray,
    val storage: ByteArray,
) {
    enum class SlotStatus { OK, EMPTY, CORRUPT }

    sealed class Parsed {
        class Ok(val save: Gen3SaveFile) : Parsed()
        class Failed(val reason: String) : Parsed()
    }

    /**
     * The file with these blocks saved over it the way the game saves: the other slot, counter + 1,
     * rotation + 1, each sector zeroed then filled, with its checksum over its chunk. Everything
     * outside that slot is copied byte for byte. Throws if the result doesn't read back as exactly
     * these blocks in that slot (see [verifyWritten]) - a write that can't be proven is never handed out.
     */
    fun withBlocks(saveBlock2: ByteArray, saveBlock1: ByteArray, storage: ByteArray): ByteArray {
        require(saveBlock2.size == format.saveBlock2Size && saveBlock1.size == format.saveBlock1Size && storage.size == format.storageSize) {
            "block sizes don't match ${format.label}"
        }
        val out = raw.copyOf()
        val target = 1 - slot
        val newCounter = (counter + 1) and 0xFFFFFFFFL
        val newRotation = (rotation + 1) % SECTORS_PER_SLOT
        for (id in 0 until SECTORS_PER_SLOT) {
            val base = (target * SECTORS_PER_SLOT + (id + newRotation) % SECTORS_PER_SLOT) * SECTOR
            out.fill(0, base, base + SECTOR)
            val size = format.chunkSize(id)
            val (block, start) = when (id) {
                0 -> saveBlock2 to 0
                in 1..4 -> saveBlock1 to (id - 1) * DATA
                else -> storage to (id - 5) * DATA
            }
            block.copyInto(out, base, start, start + size)
            putU16(out, base + ID_OFF, id)
            putU16(out, base + CHECKSUM_OFF, checksum(out, base, size))
            putU32(out, base + SIGNATURE_OFF, SIGNATURE)
            putU32(out, base + COUNTER_OFF, newCounter)
        }
        verifyWritten(out, saveBlock2, saveBlock1, storage)?.let { error("save write check failed: $it") }
        return out
    }

    /** Why [written] isn't exactly this save with the given blocks saved over it, or null if it is. */
    fun verifyWritten(written: ByteArray, saveBlock2: ByteArray, saveBlock1: ByteArray, storage: ByteArray): String? {
        if (written.size != raw.size) return "size changed"
        val again = (parse(written, format) as? Parsed.Ok)?.save ?: return "doesn't read back"
        if (again.slot == slot) return "the game would still load the old slot"
        if (again.counter != ((counter + 1) and 0xFFFFFFFFL)) return "wrong save counter"
        if (again.otherSlot != SlotStatus.OK) return "the previous save didn't survive"
        if (!again.saveBlock2.contentEquals(saveBlock2) || !again.saveBlock1.contentEquals(saveBlock1) || !again.storage.contentEquals(storage)) {
            return "blocks differ after reading back"
        }
        val slotStart = (1 - slot) * SECTORS_PER_SLOT * SECTOR
        val slotEnd = slotStart + SECTORS_PER_SLOT * SECTOR
        for (i in raw.indices) if ((i < slotStart || i >= slotEnd) && raw[i] != written[i]) return "a byte outside the new slot changed (0x${i.toString(16)})"
        return null
    }

    companion object {
        const val SECTOR = 0x1000
        const val DATA = 0xF80
        const val SECTORS_PER_SLOT = 14
        const val ID_OFF = 0xFF4
        const val CHECKSUM_OFF = 0xFF6
        const val SIGNATURE_OFF = 0xFF8
        const val COUNTER_OFF = 0xFFC
        const val SIGNATURE = 0x08012025L
        /** Both slots; Hall of Fame and the rest come after, and aren't needed. */
        const val MIN_SIZE = 2 * SECTORS_PER_SLOT * SECTOR

        /** save.c's CalculateChecksum: the u32 words of [size] bytes summed, both halves folded into a u16. */
        fun checksum(b: ByteArray, off: Int, size: Int): Int {
            var sum = 0L
            for (i in 0 until size / 4) sum += u32(b, off + i * 4)
            sum = sum and 0xFFFFFFFFL
            return ((sum shr 16) + sum).toInt() and 0xFFFF
        }

        fun parse(bytes: ByteArray, format: Gen3SaveFormat): Parsed {
            if (bytes.size < MIN_SIZE) return Parsed.Failed(tr("Too small for a Gen 3 save ({0} bytes)", bytes.size))
            val slots = (0..1).map { readSlot(bytes, it, format) }
            val ok = slots.indices.filter { slots[it].status == SlotStatus.OK }
            if (ok.isEmpty()) {
                return Parsed.Failed(
                    if (slots.all { it.status == SlotStatus.EMPTY }) tr("The save is empty - save once in the game first")
                    else tr("Neither save slot is intact - the game would call this save corrupted"),
                )
            }
            val pick = if (ok.size == 1) ok[0] else newer(slots[0].counter, slots[1].counter)
            val s = slots[pick]
            if (!s.sameCounter) return Parsed.Failed(tr("The save's sectors disagree on its save counter"))
            val other = slots[1 - pick].status
            val sb2 = ByteArray(format.saveBlock2Size)
            val sb1 = ByteArray(format.saveBlock1Size)
            val storage = ByteArray(format.storageSize)
            for (id in 0 until SECTORS_PER_SLOT) {
                val base = s.sectorBase[id]
                val size = format.chunkSize(id)
                when (id) {
                    0 -> bytes.copyInto(sb2, 0, base, base + size)
                    in 1..4 -> bytes.copyInto(sb1, (id - 1) * DATA, base, base + size)
                    else -> bytes.copyInto(storage, (id - 5) * DATA, base, base + size)
                }
            }
            val rotation = (s.sectorBase[0] / SECTOR) % SECTORS_PER_SLOT
            return Parsed.Ok(Gen3SaveFile(format, bytes.copyOf(), pick, s.counter, rotation, other, sb2, sb1, storage))
        }

        /** GetSaveValidStatus's pick between two good slots: the larger counter, with its 0xFFFFFFFF -> 0 wrap. */
        private fun newer(c0: Long, c1: Long): Int {
            val wrap = (c0 == 0xFFFFFFFFL && c1 == 0L) || (c0 == 0L && c1 == 0xFFFFFFFFL)
            return if (wrap) { if (c0 == 0xFFFFFFFFL) 1 else 0 } else if (c0 < c1) 1 else 0
        }

        private class Slot(val status: SlotStatus, val counter: Long, val sameCounter: Boolean, val sectorBase: IntArray)

        private fun readSlot(b: ByteArray, slot: Int, format: Gen3SaveFormat): Slot {
            var anySignature = false
            var valid = 0
            var counter = 0L
            val counters = HashSet<Long>()
            val base = IntArray(SECTORS_PER_SLOT) { -1 }
            for (i in 0 until SECTORS_PER_SLOT) {
                val off = (slot * SECTORS_PER_SLOT + i) * SECTOR
                if (u32(b, off + SIGNATURE_OFF) != SIGNATURE) continue
                anySignature = true
                val id = u16(b, off + ID_OFF)
                if (id >= SECTORS_PER_SLOT) continue
                if (u16(b, off + CHECKSUM_OFF) != checksum(b, off, format.chunkSize(id))) continue
                // Each id once: a slot holding one chunk twice isn't one the game wrote.
                if (base[id] >= 0) return Slot(SlotStatus.CORRUPT, 0, false, base)
                base[id] = off
                valid = valid or (1 shl id)
                counter = u32(b, off + COUNTER_OFF)
                counters += counter
            }
            val status = when {
                !anySignature -> SlotStatus.EMPTY
                valid == (1 shl SECTORS_PER_SLOT) - 1 -> SlotStatus.OK
                else -> SlotStatus.CORRUPT
            }
            return Slot(status, counter, counters.size == 1, base)
        }
    }
}

fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

fun u32(b: ByteArray, off: Int): Long =
    (u16(b, off).toLong() or (u16(b, off + 2).toLong() shl 16)) and 0xFFFFFFFFL

internal fun putU16(b: ByteArray, off: Int, v: Int) {
    b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte()
}

internal fun putU32(b: ByteArray, off: Int, v: Long) {
    putU16(b, off, (v and 0xFFFF).toInt()); putU16(b, off + 2, ((v shr 16) and 0xFFFF).toInt())
}
