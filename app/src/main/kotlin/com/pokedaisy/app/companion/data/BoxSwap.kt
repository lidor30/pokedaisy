package com.pokedaisy.app.companion.data

/**
 * The BOXES tab's SWAP: a box Pokémon and a party Pokémon trade places in the running game, as the game's own PC does
 * when one is dropped on the other (pokefirered's pokemon_storage_system: SetPlacedMonData) - the box Pokémon comes out
 * through the game's BoxMonToMon (its stats worked out again, full HP, no status, as any Pokémon taken out of the PC),
 * the party one goes in as its 80-byte BoxPokemon (SetBoxMonAt is a struct copy) and gets its PP back
 * (BoxMonRestorePP). The party count never changes, so nothing else in the game moves.
 *
 * Only where the PC itself could be used and its rules hold: on the field with the controls free (the bag's gate,
 * [FieldItemFns.fieldControlsLocked]), the party slot still holding the Pokémon the pick showed and the box slot the one
 * the summary showed (by personality), the party one not holding Mail (the game: "Please remove the MAIL"), and an egg
 * never coming out in place of the last Pokémon that can fight. Checked headless on retail FireRed rev 1 with the
 * user's save: the swap, an in-game save, a reboot from the save file - both Pokémon where they were put, their data
 * read back by the game's own GetBoxMonData (no Bad Egg). Retail Emerald the same way, on its own save.
 */
data class BoxSwapFns(
    val boxMonToMon: Long,
    val boxMonRestorePP: Long,
    /** GetBoxMonData(boxMon, field) (GetBoxMonData3: the 2-argument one is the same code). */
    val getBoxMonData: Long,
    /** gPokemonStoragePtr: struct PokemonStorage (u8 currentBox + padding, then boxes[14][30] of 80 bytes). */
    val storagePtr: Long,
    /** CRC32 of each function's first 32 bytes (boxMonToMon, boxMonRestorePP, getBoxMonData). */
    val codeCrcs: List<Long>,
)

/** Retail FireRed rev 1: each function found byte for byte (BLs and pool words aside) from the pinned pokefirered
 * build, once each in the retail ROM; gPokemonStoragePtr = gSaveBlock2Ptr + 4 (0x03005010). */
val BOX_SWAP_FIRERED_REV1 = BoxSwapFns(
    boxMonToMon = 0x0803E788L, boxMonRestorePP = 0x080442F8L, getBoxMonData = 0x0803FD58L, storagePtr = 0x03005010L,
    codeCrcs = listOf(0xC49105FCL, 0x05F9FBC2L, 0xF3E3F4DDL),
)

/** Retail Emerald: the pinned pokeemerald build's addresses (its code is retail's; BoxMonToMon differs only in its call to
 * the library's memcpy, which sits elsewhere in that build); gPokemonStoragePtr = gSaveBlock2Ptr + 4 (0x03005D94). */
val BOX_SWAP_EMERALD = BoxSwapFns(
    boxMonToMon = 0x08069004L, boxMonRestorePP = 0x0806E930L, getBoxMonData = 0x0806A674L, storagePtr = 0x03005D94L,
    codeCrcs = listOf(0xD6140AE3L, 0x04CE5AB8L, 0xF3E3F4DDL),
)

/** The swap's table for the game whose bag runs on [fields] (the same build), or null. */
fun boxSwapFor(fields: FieldItemFns?): BoxSwapFns? = when {
    fields == null || fields.party == null -> null
    fields === FIELD_ITEMS_FIRERED_REV1 -> BOX_SWAP_FIRERED_REV1
    fields === FIELD_ITEMS_EMERALD -> BOX_SWAP_EMERALD
    else -> null
}

enum class BoxSwapResult {
    SWAPPED,
    /** Not on the field with the controls free (a battle, a menu, a text box, the game's own PC). */
    NOT_NOW,
    IN_BATTLE,
    /** The party or the box isn't what the companion showed any more: nothing changed. */
    CHANGED,
    /** The party Pokémon holds Mail. */
    HOLDS_MAIL,
    /** An egg would take the place of the last Pokémon that can fight. */
    LAST_MON,
    FAILED,
}

private const val MON_SIZE = 100
private const val BOX_MON = 80
private const val HP_OFF = 0x56
private const val BOXES = 14
private const val SLOTS = 30
private const val FIELD_PERSONALITY = 0
private const val FIELD_SPECIES = 11
private const val FIELD_HELD_ITEM = 12
private const val FIELD_IS_EGG = 45

/**
 * Swaps box [box]'s slot [slot] (holding [boxPersonality]) with party slot [partySlot] (holding [partyPersonality]);
 * [busy] = a battle or a menu screen is up. Puts both back if the game's call didn't take. Emu thread.
 */
fun swapWithBox(
    fields: FieldItemFns, fns: BoxSwapFns, box: Int, slot: Int, boxPersonality: Long, partySlot: Int, partyPersonality: Long,
    busy: Boolean, g: GameCalls,
): BoxSwapResult {
    val p = fields.party ?: return BoxSwapResult.FAILED
    // r0 comes back unsigned; the companion's reads may hold a personality either way.
    @Suppress("NAME_SHADOWING") val boxPersonality = boxPersonality and 0xFFFFFFFFL
    @Suppress("NAME_SHADOWING") val partyPersonality = partyPersonality and 0xFFFFFFFFL
    if (box !in 0 until BOXES || slot !in 0 until SLOTS) return BoxSwapResult.FAILED
    val crcs = fns.codeCrcs.zip(listOf(fns.boxMonToMon, fns.boxMonRestorePP, fns.getBoxMonData))
    if (!fieldItemCodeMatches(fields, g::read) ||
        crcs.any { (crc, fn) -> java.util.zip.CRC32().apply { update(g.read(fn, 32)) }.value != crc }) return BoxSwapResult.FAILED
    if (busy || g.call(fields.fieldControlsLocked) != 0L) return BoxSwapResult.NOT_NOW

    fun u32(addr: Long) = g.read(addr, 4).let { b -> b.indices.fold(0L) { acc, i -> acc or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
    fun partyMon(i: Int) = p.party + i.toLong() * MON_SIZE
    fun mon(i: Int, field: Int) = g.call(p.getMonData, partyMon(i).toInt(), field).also { if (it < 0) throw IllegalStateException("GetMonData didn't return") }
    fun boxed(addr: Long, field: Int) = g.call(fns.getBoxMonData, addr.toInt(), field).also { if (it < 0) throw IllegalStateException("GetBoxMonData didn't return") }

    return runCatching {
        val storage = u32(fns.storagePtr)
        if (storage !in 0x02000000L until 0x02040000L) return BoxSwapResult.FAILED
        val boxMon = storage + 4 + (box * SLOTS + slot).toLong() * BOX_MON
        val count = (g.read(p.partyCount, 1)[0].toInt() and 0xFF).coerceAtMost(6)
        if (partySlot !in 0 until count || mon(partySlot, FIELD_PERSONALITY) != partyPersonality ||
            boxed(boxMon, FIELD_SPECIES) == 0L || boxed(boxMon, FIELD_PERSONALITY) != boxPersonality) return BoxSwapResult.CHANGED
        if (mon(partySlot, FIELD_HELD_ITEM).toInt() in p.mail) return BoxSwapResult.HOLDS_MAIL
        // The party must keep a Pokémon that can fight: only an egg coming out can break that (a Pokémon out of a box has
        // full HP).
        if (boxed(boxMon, FIELD_IS_EGG) != 0L) {
            val fighter = (0 until count).any { i ->
                i != partySlot && mon(i, FIELD_SPECIES) != 0L && mon(i, FIELD_IS_EGG) == 0L &&
                    g.read(partyMon(i) + HP_OFF, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) } > 0
            }
            if (!fighter) return BoxSwapResult.LAST_MON
        }

        val partyBefore = g.read(partyMon(partySlot), MON_SIZE)
        val boxBefore = g.read(boxMon, BOX_MON)
        if (partyBefore.size != MON_SIZE || boxBefore.size != BOX_MON) return BoxSwapResult.FAILED
        fun putBack(): BoxSwapResult {
            g.write(partyMon(partySlot), partyBefore)
            g.write(boxMon, boxBefore)
            return BoxSwapResult.FAILED
        }
        // Out first (BoxMonToMon reads the box slot), then the party Pokémon's box half into the slot it left.
        if (g.call(fns.boxMonToMon, boxMon.toInt(), partyMon(partySlot).toInt()) < 0) return putBack()
        if (!g.write(boxMon, partyBefore.copyOfRange(0, BOX_MON))) return putBack()
        if (g.call(fns.boxMonRestorePP, boxMon.toInt()) < 0) return putBack()
        if (mon(partySlot, FIELD_PERSONALITY) != boxPersonality || boxed(boxMon, FIELD_PERSONALITY) != partyPersonality) return putBack()
        BoxSwapResult.SWAPPED
    }.getOrElse { BoxSwapResult.FAILED }
}
