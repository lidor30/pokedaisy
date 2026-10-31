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
 * read back by the game's own GetBoxMonData (no Bad Egg). Retail Emerald and both LeafGreens the same way, on their own
 * saves.
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

/** Retail LeafGreen rev 1: FireRed rev 1's addresses (the same pokefirered build, masked bytes found once each), its own
 * BoxMonToMon bytes (a call elsewhere). Checked headless on the user's save the same way. */
val BOX_SWAP_LEAFGREEN_REV1 = BoxSwapFns(
    boxMonToMon = 0x0803E788L, boxMonRestorePP = 0x080442F8L, getBoxMonData = 0x0803FD58L, storagePtr = 0x03005010L,
    codeCrcs = listOf(0x3F859618L, 0x05F9FBC2L, 0xF3E3F4DDL),
)

/** Retail LeafGreen rev 0 (its first cartridge): 0x14 bytes before rev 1's, the same way. */
val BOX_SWAP_LEAFGREEN_REV0 = BoxSwapFns(
    boxMonToMon = 0x0803E774L, boxMonRestorePP = 0x080442E4L, getBoxMonData = 0x0803FD44L, storagePtr = 0x03005010L,
    codeCrcs = listOf(0x9435DDECL, 0x05F9FBC2L, 0xF3E3F4DDL),
)

/**
 * The swap's table for the game whose bag runs on [fields] (the same build) and whose boxes [cfg] reads, or null. Only
 * where the BOXES tab reads vanilla's boxes at the table's own storage pointer: hacks built on these games' code (their
 * bag tables are the same objects) keep their boxes their own way (CFRU's compressed ones), and the game's BoxMonToMon
 * on one of those would wreck it.
 */
fun boxSwapFor(fields: FieldItemFns?, cfg: NativeConfig?): BoxSwapFns? {
    if (fields == null || fields.party == null || cfg == null || cfg.pcStorage != VANILLA_PC_STORAGE) return null
    val fns = when {
        fields === FIELD_ITEMS_FIRERED_REV1 -> BOX_SWAP_FIRERED_REV1
        fields === FIELD_ITEMS_LEAFGREEN_REV1 -> BOX_SWAP_LEAFGREEN_REV1
        fields === FIELD_ITEMS_LEAFGREEN_REV0 -> BOX_SWAP_LEAFGREEN_REV0
        fields === FIELD_ITEMS_EMERALD -> BOX_SWAP_EMERALD
        else -> null
    }
    return fns?.takeIf { cfg.pokemonStoragePtr == it.storagePtr }
}

/** What a SWAP, DEPOSIT or WITHDRAW did. */
enum class BoxSwapResult {
    /** Done: swapped, deposited or withdrawn. */
    SWAPPED,
    /** Not on the field with the controls free (a battle, a menu, a text box, the game's own PC). */
    NOT_NOW,
    IN_BATTLE,
    /** The party or the box isn't what the companion showed any more: nothing changed. */
    CHANGED,
    /** The party Pokémon holds Mail. */
    HOLDS_MAIL,
    /** An egg would take the place of the last Pokémon that can fight, or (DEPOSIT) it's the last one that can. */
    LAST_MON,
    /** WITHDRAW with six in the party. */
    PARTY_FULL,
    /** DEPOSIT into a box with no free slot. */
    BOX_FULL,
    FAILED,
}

private const val MON_SIZE = 100
private const val BOX_MON = 80
private const val HP_OFF = 0x56
private const val BOXES = 14
private const val SLOTS = 30
private const val PARTY_MAX = 6
private const val FIELD_PERSONALITY = 0
private const val FIELD_SPECIES = 11
private const val FIELD_HELD_ITEM = 12
private const val FIELD_IS_EGG = 45

/**
 * What the three moves share: the game's party and storage as its own functions see them, and the PC's rules. Made by
 * [boxMove] once the code checks out and the field is free.
 */
private class BoxMove(val fields: FieldItemFns, val p: PartyItemFns, val fns: BoxSwapFns, val g: GameCalls) {
    fun u32(addr: Long) = g.read(addr, 4).let { b -> b.indices.fold(0L) { acc, i -> acc or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
    fun partyMon(i: Int) = p.party + i.toLong() * MON_SIZE
    fun mon(i: Int, field: Int) = g.call(p.getMonData, partyMon(i).toInt(), field).also { if (it < 0) throw IllegalStateException("GetMonData didn't return") }
    fun boxed(addr: Long, field: Int) = g.call(fns.getBoxMonData, addr.toInt(), field).also { if (it < 0) throw IllegalStateException("GetBoxMonData didn't return") }
    val count get() = (g.read(p.partyCount, 1)[0].toInt() and 0xFF).coerceAtMost(PARTY_MAX)

    /** Box [box]'s slot [slot] in the game's storage; null = the storage pointer isn't set (not on the field yet). */
    fun boxMon(box: Int, slot: Int): Long? {
        val storage = u32(fns.storagePtr)
        if (storage !in 0x02000000L until 0x02040000L) return null
        return storage + 4 + (box * SLOTS + slot).toLong() * BOX_MON
    }

    fun holdsMail(i: Int) = mon(i, FIELD_HELD_ITEM).toInt() in p.mail

    /** Whether the party keeps a Pokémon that can fight without slot [without] (the PC's "That's your last POKéMON!"). */
    fun fighterLeft(without: Int) = (0 until count).any { i ->
        i != without && mon(i, FIELD_SPECIES) != 0L && mon(i, FIELD_IS_EGG) == 0L &&
            g.read(partyMon(i) + HP_OFF, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) } > 0
    }
}

/**
 * Runs [move] where the PC itself could be used: the functions' code checked first (the bag's and the swap's own), on
 * the field with the controls free ([busy] = a battle or a menu screen is up). A throw (a call that didn't return) is
 * FAILED.
 */
private fun boxMove(fields: FieldItemFns, fns: BoxSwapFns, busy: Boolean, g: GameCalls, move: BoxMove.() -> BoxSwapResult): BoxSwapResult {
    val p = fields.party ?: return BoxSwapResult.FAILED
    val crcs = fns.codeCrcs.zip(listOf(fns.boxMonToMon, fns.boxMonRestorePP, fns.getBoxMonData))
    if (!fieldItemCodeMatches(fields, g::read) ||
        crcs.any { (crc, fn) -> java.util.zip.CRC32().apply { update(g.read(fn, 32)) }.value != crc }) return BoxSwapResult.FAILED
    if (busy || g.call(fields.fieldControlsLocked) != 0L) return BoxSwapResult.NOT_NOW
    return runCatching { BoxMove(fields, p, fns, g).move() }.getOrElse { BoxSwapResult.FAILED }
}

/**
 * Swaps box [box]'s slot [slot] (holding [boxPersonality]) with party slot [partySlot] (holding [partyPersonality]);
 * [busy] = a battle or a menu screen is up. Puts both back if the game's call didn't take. Emu thread.
 */
fun swapWithBox(
    fields: FieldItemFns, fns: BoxSwapFns, box: Int, slot: Int, boxPersonality: Long, partySlot: Int, partyPersonality: Long,
    busy: Boolean, g: GameCalls,
): BoxSwapResult {
    // r0 comes back unsigned; the companion's reads may hold a personality either way.
    @Suppress("NAME_SHADOWING") val boxPersonality = boxPersonality and 0xFFFFFFFFL
    @Suppress("NAME_SHADOWING") val partyPersonality = partyPersonality and 0xFFFFFFFFL
    if (box !in 0 until BOXES || slot !in 0 until SLOTS) return BoxSwapResult.FAILED
    return boxMove(fields, fns, busy, g) {
        val boxMon = boxMon(box, slot) ?: return@boxMove BoxSwapResult.FAILED
        val count = count
        if (partySlot !in 0 until count || mon(partySlot, FIELD_PERSONALITY) != partyPersonality ||
            boxed(boxMon, FIELD_SPECIES) == 0L || boxed(boxMon, FIELD_PERSONALITY) != boxPersonality) return@boxMove BoxSwapResult.CHANGED
        if (holdsMail(partySlot)) return@boxMove BoxSwapResult.HOLDS_MAIL
        // The party must keep a Pokémon that can fight: only an egg coming out can break that (a Pokémon out of a box has
        // full HP).
        if (boxed(boxMon, FIELD_IS_EGG) != 0L && !fighterLeft(partySlot)) return@boxMove BoxSwapResult.LAST_MON

        val partyBefore = g.read(partyMon(partySlot), MON_SIZE)
        val boxBefore = g.read(boxMon, BOX_MON)
        if (partyBefore.size != MON_SIZE || boxBefore.size != BOX_MON) return@boxMove BoxSwapResult.FAILED
        fun putBack(): BoxSwapResult {
            g.write(partyMon(partySlot), partyBefore)
            g.write(boxMon, boxBefore)
            return BoxSwapResult.FAILED
        }
        // Out first (BoxMonToMon reads the box slot), then the party Pokémon's box half into the slot it left.
        if (g.call(fns.boxMonToMon, boxMon.toInt(), partyMon(partySlot).toInt()) < 0) return@boxMove putBack()
        if (!g.write(boxMon, partyBefore.copyOfRange(0, BOX_MON))) return@boxMove putBack()
        if (g.call(fns.boxMonRestorePP, boxMon.toInt()) < 0) return@boxMove putBack()
        if (mon(partySlot, FIELD_PERSONALITY) != boxPersonality || boxed(boxMon, FIELD_PERSONALITY) != partyPersonality) return@boxMove putBack()
        BoxSwapResult.SWAPPED
    }
}

/**
 * DEPOSIT: party slot [partySlot] (holding [partyPersonality]) into box [box]'s first free slot, as the game's PC does
 * (pokefirered / pokeemerald's pokemon_storage_system: the Pokémon's 80-byte BoxPokemon into the slot, BoxMonRestorePP,
 * its party slot zeroed and the ones after it moved up - CompactPartySlots - and gPlayerPartyCount one less). Refused
 * like the game: the last Pokémon that can fight ("That's your last POKéMON!"), Mail ("Please remove the MAIL"), a full
 * box. Puts the party and the slot back if anything didn't take. Emu thread.
 */
fun depositToBox(
    fields: FieldItemFns, fns: BoxSwapFns, partySlot: Int, partyPersonality: Long, box: Int, busy: Boolean, g: GameCalls,
): BoxSwapResult {
    @Suppress("NAME_SHADOWING") val partyPersonality = partyPersonality and 0xFFFFFFFFL
    if (box !in 0 until BOXES) return BoxSwapResult.FAILED
    return boxMove(fields, fns, busy, g) {
        val count = count
        if (partySlot !in 0 until count || mon(partySlot, FIELD_PERSONALITY) != partyPersonality) return@boxMove BoxSwapResult.CHANGED
        if (count <= 1 || !fighterLeft(partySlot)) return@boxMove BoxSwapResult.LAST_MON
        if (holdsMail(partySlot)) return@boxMove BoxSwapResult.HOLDS_MAIL
        boxMon(box, 0) ?: return@boxMove BoxSwapResult.FAILED
        // The box's first free slot, as the PC fills it.
        val slot = (0 until SLOTS).firstOrNull { s -> boxed(boxMon(box, s)!!, FIELD_SPECIES) == 0L } ?: return@boxMove BoxSwapResult.BOX_FULL
        val boxMon = boxMon(box, slot)!!

        val partyBefore = g.read(p.party, count * MON_SIZE)
        val boxBefore = g.read(boxMon, BOX_MON)
        if (partyBefore.size != count * MON_SIZE || boxBefore.size != BOX_MON) return@boxMove BoxSwapResult.FAILED
        fun putBack(): BoxSwapResult {
            g.write(p.party, partyBefore)
            g.write(boxMon, boxBefore)
            g.write(p.partyCount, byteArrayOf(count.toByte()))
            return BoxSwapResult.FAILED
        }
        val leaving = partyBefore.copyOfRange(partySlot * MON_SIZE, (partySlot + 1) * MON_SIZE)
        // The party with the slot taken out and the others moved up, the last slot zeroed (ZeroMonData).
        val compacted = partyBefore.copyOfRange(0, partySlot * MON_SIZE) +
            partyBefore.copyOfRange((partySlot + 1) * MON_SIZE, count * MON_SIZE) + ByteArray(MON_SIZE)
        if (!g.write(boxMon, leaving.copyOfRange(0, BOX_MON))) return@boxMove putBack()
        if (g.call(fns.boxMonRestorePP, boxMon.toInt()) < 0) return@boxMove putBack()
        if (boxed(boxMon, FIELD_PERSONALITY) != partyPersonality) return@boxMove putBack()
        if (!g.write(p.party, compacted) || !g.write(p.partyCount, byteArrayOf((count - 1).toByte()))) return@boxMove putBack()
        if (this.count != count - 1 || mon(count - 1, FIELD_SPECIES) != 0L) return@boxMove putBack()
        BoxSwapResult.SWAPPED
    }
}

/**
 * WITHDRAW: box [box]'s slot [slot] (holding [boxPersonality]) into the party's first free slot, as the game's PC does
 * (BoxMonToMon into gPlayerParty[count] - its stats worked out, full HP - the box slot zeroed, gPlayerPartyCount one
 * more). PARTY_FULL with six in the party (SWAP is the way then). Puts both back if anything didn't take. Emu thread.
 */
fun withdrawFromBox(
    fields: FieldItemFns, fns: BoxSwapFns, box: Int, slot: Int, boxPersonality: Long, busy: Boolean, g: GameCalls,
): BoxSwapResult {
    @Suppress("NAME_SHADOWING") val boxPersonality = boxPersonality and 0xFFFFFFFFL
    if (box !in 0 until BOXES || slot !in 0 until SLOTS) return BoxSwapResult.FAILED
    return boxMove(fields, fns, busy, g) {
        val boxMon = boxMon(box, slot) ?: return@boxMove BoxSwapResult.FAILED
        val count = count
        if (boxed(boxMon, FIELD_SPECIES) == 0L || boxed(boxMon, FIELD_PERSONALITY) != boxPersonality) return@boxMove BoxSwapResult.CHANGED
        if (count >= PARTY_MAX) return@boxMove BoxSwapResult.PARTY_FULL

        val partyBefore = g.read(partyMon(count), MON_SIZE)
        val boxBefore = g.read(boxMon, BOX_MON)
        if (partyBefore.size != MON_SIZE || boxBefore.size != BOX_MON) return@boxMove BoxSwapResult.FAILED
        fun putBack(): BoxSwapResult {
            g.write(partyMon(count), partyBefore)
            g.write(boxMon, boxBefore)
            g.write(p.partyCount, byteArrayOf(count.toByte()))
            return BoxSwapResult.FAILED
        }
        if (g.call(fns.boxMonToMon, boxMon.toInt(), partyMon(count).toInt()) < 0) return@boxMove putBack()
        if (mon(count, FIELD_PERSONALITY) != boxPersonality) return@boxMove putBack()
        // The slot emptied (ZeroBoxMonData), the party one longer.
        if (!g.write(boxMon, ByteArray(BOX_MON)) || !g.write(p.partyCount, byteArrayOf((count + 1).toByte()))) return@boxMove putBack()
        if (this.count != count + 1 || boxed(boxMon, FIELD_SPECIES) != 0L) return@boxMove putBack()
        BoxSwapResult.SWAPPED
    }
}
