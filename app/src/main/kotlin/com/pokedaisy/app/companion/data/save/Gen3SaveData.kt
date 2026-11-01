package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.Gen3Text

/** A PC box position: box 0..13, slot 0..29 (rows of 6, as the game lays them out). */
data class BoxSlot(val box: Int, val slot: Int)

/** Where a Pokémon sits in a save: a party slot or a PC box slot. */
sealed class MonRef {
    data class Party(val index: Int) : MonRef()
    data class Pc(val at: BoxSlot) : MonRef()
}

/** One bag slot: an item and how many. */
data class ItemStack(val item: Int, val quantity: Int)

/**
 * What a save holds, read from its blocks: the trainer, the party and the 14 PC boxes. Built from a
 * [Gen3SaveFile] or from an editor's working copies of its blocks.
 */
class Gen3SaveData(
    val format: Gen3SaveFormat,
    private val saveBlock2: ByteArray,
    private val saveBlock1: ByteArray,
    private val storage: ByteArray,
) {
    constructor(file: Gen3SaveFile) : this(file.format, file.saveBlock2, file.saveBlock1, file.storage)

    class Box(val name: String, val mons: List<Gen3BoxMon>)

    class PartyMon(val mon: Gen3BoxMon, val level: Int)

    val trainerName: String get() = Gen3Text.decode(saveBlock2, 0, 7)

    /** The 8 raw name bytes at the start of SaveBlock2 (the game's charset, 0xFF-terminated when short). */
    val trainerNameBytes: ByteArray get() = saveBlock2.copyOfRange(0, 8)
    val isFemale: Boolean get() = saveBlock2[8].toInt() != 0
    val trainerId: Int get() = u16(saveBlock2, 0x0A)
    val secretId: Int get() = u16(saveBlock2, 0x0C)
    val playTime: String get() = "%d:%02d".format(u16(saveBlock2, 0x0E), saveBlock2[0x10].toInt() and 0xFF)

    val money: Long get() {
        val key = if (format.encryptionKeyOff >= 0) u32(saveBlock2, format.encryptionKeyOff) else 0L
        return u32(saveBlock1, format.moneyOff) xor key
    }

    /** The low half of SaveBlock2.encryptionKey: what coins and bag quantities are XORed with (0 on R/S). */
    private val key16: Int get() = if (format.encryptionKeyOff >= 0) u16(saveBlock2, format.encryptionKeyOff) else 0

    val coins: Int get() = u16(saveBlock1, format.coinsOff) xor key16

    /** A pocket's filled slots, in the save's order (the game keeps them packed). */
    fun pocket(p: BagPocket): List<ItemStack> {
        val l = format.pocket(p)
        return (0 until l.slots).mapNotNull { i ->
            val o = l.off + i * 4
            u16(saveBlock1, o).takeIf { it != 0 }?.let { ItemStack(it, u16(saveBlock1, o + 2) xor key16) }
        }
    }

    val partyCount: Int get() = saveBlock1[format.partyCountOff].toInt() and 0xFF

    val party: List<PartyMon> get() = (0 until partyCount.coerceAtMost(PARTY_SIZE)).map {
        val off = format.partyOff + it * PARTY_MON_SIZE
        PartyMon(Gen3BoxMon.read(saveBlock1, off), saveBlock1[off + 0x54].toInt() and 0xFF)
    }

    /** The box the PC opens on. */
    val currentBox: Int get() = storage[0].toInt() and 0xFF

    val boxes: List<Box> get() = List(BOXES) { b ->
        Box(Gen3Text.decode(storage, BOX_NAMES_OFF + b * BOX_NAME_SIZE, BOX_NAME_SIZE), List(BOX_SLOTS) { s -> mon(BoxSlot(b, s)) })
    }

    fun mon(at: BoxSlot): Gen3BoxMon = Gen3BoxMon.read(storage, recordOff(at))

    /** The Pokémon at [at] (null past the party), and its level: the party's level byte, a box record's from its EXP. */
    fun mon(at: MonRef): Pair<Gen3BoxMon, Int>? = when (at) {
        is MonRef.Party -> party.getOrNull(at.index)?.let { it.mon to it.level }
        is MonRef.Pc -> mon(at.at).takeIf { !it.isEmpty }?.let { it to it.level }
    }

    /**
     * Why these blocks don't look like a [format] save (a hack's own layout, a wrong game), or null.
     * The editor offers nothing for a save this returns a reason for.
     */
    fun mismatch(): String? {
        val count = partyCount
        // 0 before the first Pokémon (a save made before the starter).
        if (count !in 0..PARTY_SIZE) return "party count $count"
        party.forEachIndexed { i, p ->
            if (!p.mon.checksumOk) return "party Pokémon ${i + 1} doesn't decode"
            if (p.mon.species !in 1..MAX_SPECIES) return "party Pokémon ${i + 1} has species ${p.mon.species}"
            if (!p.mon.isEgg && p.level !in 1..100) return "party Pokémon ${i + 1} is level ${p.level}"
        }
        // The game zeroes the slots past the count (ZeroMonData): anything there is another layout's data.
        for (i in count until PARTY_SIZE) {
            val off = format.partyOff + i * PARTY_MON_SIZE
            if ((off until off + PARTY_MON_SIZE).any { saveBlock1[it].toInt() != 0 }) return "party slot ${i + 1} isn't empty"
        }
        if (currentBox >= BOXES) return "current box ${currentBox + 1}"
        val records = (0 until BOXES * BOX_SLOTS).map { mon(BoxSlot(it / BOX_SLOTS, it % BOX_SLOTS)) }
        val used = records.count { !it.isEmpty }
        val unreadable = records.count { !it.isEmpty && (it.isBadEgg || it.species !in 1..MAX_SPECIES) }
        // A save of another layout decodes as noise in nearly every slot; a real one has a Bad Egg at most.
        if (used >= 4 && unreadable * 2 > used) return "$unreadable of $used PC records don't decode"
        return null
    }

    companion object {
        const val PARTY_SIZE = 6
        const val PARTY_MON_SIZE = 100
        const val BOXES = 14
        const val BOX_SLOTS = 30
        const val BOX_NAMES_OFF = 4 + BOXES * BOX_SLOTS * Gen3BoxMon.SIZE   // 0x8344
        const val BOX_NAME_SIZE = 9
        /** The last internal species id the Gen 3 games know (Chimecho, 411; 412 is the egg). */
        const val MAX_SPECIES = 412
        /** money.c's MAX_MONEY, coins.h's MAX_COINS. */
        const val MAX_MONEY = 999_999L
        const val MAX_COINS = 9_999

        fun recordOff(at: BoxSlot): Int {
            require(at.box in 0 until BOXES && at.slot in 0 until BOX_SLOTS) { "no box slot $at" }
            return 4 + (at.box * BOX_SLOTS + at.slot) * Gen3BoxMon.SIZE
        }
    }
}

/**
 * Edits a save's working copies and hands back the file to write. A PC record moves (or two swap) byte for byte; the
 * values that can be edited - money, coins, the bag, a Pokémon's held item and moves - only take what [Gen3Legal] says
 * that game allows, and anything else throws before a byte changes.
 */
class Gen3SaveEditor(val file: Gen3SaveFile) {
    private val saveBlock2 = file.saveBlock2.copyOf()
    private val saveBlock1 = file.saveBlock1.copyOf()
    private val storage = file.storage.copyOf()

    val data: Gen3SaveData get() = Gen3SaveData(file.format, saveBlock2, saveBlock1, storage)

    val changed: Boolean get() =
        !saveBlock2.contentEquals(file.saveBlock2) || !saveBlock1.contentEquals(file.saveBlock1) || !storage.contentEquals(file.storage)

    /** Moves the Pokémon at [from] to [to], swapping with whatever is there. Both must be readable records. */
    fun move(from: BoxSlot, to: BoxSlot) {
        if (from == to) return
        val a = data.mon(from)
        val b = data.mon(to)
        require(!a.isEmpty && !a.isBadEgg) { "nothing movable at $from" }
        require(b.isEmpty || !b.isBadEgg) { "a Bad Egg sits at $to" }
        val ao = Gen3SaveData.recordOff(from)
        val bo = Gen3SaveData.recordOff(to)
        val tmp = storage.copyOfRange(ao, ao + Gen3BoxMon.SIZE)
        System.arraycopy(storage, bo, storage, ao, Gen3BoxMon.SIZE)
        System.arraycopy(tmp, 0, storage, bo, Gen3BoxMon.SIZE)
    }

    /** The 80 bytes at [at], as stored. */
    fun record(at: BoxSlot): ByteArray = Gen3SaveData.recordOff(at).let { storage.copyOfRange(it, it + Gen3BoxMon.SIZE) }

    /** Puts a record at [at]: [bytes] must decode (a trade's), or be all zero (the slot emptied, like ZeroBoxMonData). */
    fun putRecord(at: BoxSlot, bytes: ByteArray) {
        require(bytes.size == Gen3BoxMon.SIZE)
        require(bytes.all { it.toInt() == 0 } || Gen3BoxMon.read(bytes, 0).let { it.checksumOk && !it.isEmpty }) { "not a readable record" }
        bytes.copyInto(storage, Gen3SaveData.recordOff(at))
    }

    private val f get() = file.format

    fun flag(id: Int): Boolean = (saveBlock1[f.flagsOff + id / 8].toInt() shr (id % 8)) and 1 == 1

    private fun setFlag(id: Int) {
        val o = f.flagsOff + id / 8
        saveBlock1[o] = (saveBlock1[o].toInt() or (1 shl (id % 8))).toByte()
    }

    fun variable(id: Int): Int = u16(saveBlock1, f.varsOff + (id - 0x4000) * 2)

    /** IsNationalPokedexEnabled, as each game checks it. */
    val nationalDex: Boolean get() =
        (saveBlock2[f.nationalMagicOff].toInt() and 0xFF) == f.nationalMagic &&
            variable(f.nationalVar) == f.nationalVarValue && flag(f.nationalFlag)

    /** Ruby / Sapphire's EnableNationalPokedex - what a trade with FireRed / LeafGreen / Emerald does to them. */
    fun enableNationalDex() {
        require(f == Gen3SaveFormat.RUBY_SAPPHIRE) { "only Ruby / Sapphire get the National Dex from a trade" }
        saveBlock2[f.nationalMagicOff] = f.nationalMagic.toByte()
        putU16(saveBlock1, f.varsOff + (f.nationalVar - 0x4000) * 2, f.nationalVarValue)
        setFlag(f.nationalFlag)
        saveBlock2[f.pokedexOff + 1] = 1   // mode: national
        saveBlock2[f.pokedexOff] = 0       // order: numerical
    }

    private fun dexBit(base: ByteArray, off: Int, national: Int) = (base[off + (national - 1) / 8].toInt() shr ((national - 1) % 8)) and 1 == 1

    private fun setDexBit(base: ByteArray, off: Int, national: Int) {
        val o = off + (national - 1) / 8
        base[o] = (base[o].toInt() or (1 shl ((national - 1) % 8))).toByte()
    }

    private val ownedOff get() = f.pokedexOff + 0x10
    private val seenOff get() = f.pokedexOff + 0x44

    /** GetSetPokedexFlag(FLAG_GET_CAUGHT): owned, and seen in all three copies. */
    fun caught(species: Int): Boolean {
        val n = speciesNationalDex.getOrNull(species)?.takeIf { it in 1..NATIONAL_DEX_COUNT } ?: return false
        return dexBit(saveBlock2, ownedOff, n) && dexBit(saveBlock2, seenOff, n) && dexBit(saveBlock1, f.seen1Off, n) && dexBit(saveBlock1, f.seen2Off, n)
    }

    /**
     * What a trade does for a received Pokémon (UpdatePokedexForReceivedMon): seen in all three copies, then caught -
     * with Unown's / Spinda's personality kept when it's the first one caught (HandleSetPokedexFlag).
     */
    fun markCaught(species: Int, personality: Long) {
        val n = speciesNationalDex.getOrNull(species)?.takeIf { it in 1..NATIONAL_DEX_COUNT } ?: return
        val first = !caught(species)
        setDexBit(saveBlock2, seenOff, n)
        setDexBit(saveBlock1, f.seen1Off, n)
        setDexBit(saveBlock1, f.seen2Off, n)
        if (first) {
            setDexBit(saveBlock2, ownedOff, n)
            when (species) {
                SPECIES_UNOWN -> putU32(saveBlock2, f.pokedexOff + 0x04, personality)
                SPECIES_SPINDA -> putU32(saveBlock2, f.pokedexOff + 0x08, personality)
            }
        }
    }

    private val key16: Int get() = if (f.encryptionKeyOff >= 0) u16(saveBlock2, f.encryptionKeyOff) else 0

    /** SetMoney: 0..999,999, stored XORed with the key. */
    fun setMoney(amount: Long) {
        require(amount in 0..Gen3SaveData.MAX_MONEY) { "money $amount" }
        val key = if (f.encryptionKeyOff >= 0) u32(saveBlock2, f.encryptionKeyOff) else 0L
        putU32(saveBlock1, f.moneyOff, amount xor key)
    }

    /** SetCoins: 0..9,999. */
    fun setCoins(amount: Int) {
        require(amount in 0..Gen3SaveData.MAX_COINS) { "coins $amount" }
        putU16(saveBlock1, f.coinsOff, amount xor key16)
    }

    /** Sets stack [index] of [pocket] to [quantity] (1 up to what a stack holds); 0 takes the stack out (never an HM's). */
    fun setQuantity(pocket: BagPocket, index: Int, quantity: Int) {
        val stacks = data.pocket(pocket).toMutableList()
        require(index in stacks.indices) { "no stack $index in $pocket" }
        val item = stacks[index].item
        if (quantity == 0) {
            // Key items and HMs are handed out once: without them the game can't go on.
            require(pocket != BagPocket.KEY && item < Gen3SaveFormat.FIRST_HM) { "item $item stays" }
            stacks.removeAt(index)
        } else {
            requireLegal(pocket, item, quantity)
            stacks[index] = ItemStack(item, quantity)
        }
        writePocket(pocket, stacks)
    }

    /** Puts [quantity] of [item] in [pocket]: an item the bag doesn't hold yet, while the pocket has room. */
    fun addItem(pocket: BagPocket, item: Int, quantity: Int) {
        requireLegal(pocket, item, quantity)
        val stacks = data.pocket(pocket)
        require(stacks.none { it.item == item }) { "$item is in the bag already" }
        require(stacks.size < f.pocket(pocket).slots) { "$pocket is full" }
        writePocket(pocket, stacks + ItemStack(item, quantity))
    }

    private fun requireLegal(pocket: BagPocket, item: Int, quantity: Int) {
        require(pocket != BagPocket.KEY) { "key items aren't edited" }
        require(Gen3Legal.pocketOf(f, item) == pocket) { "item $item doesn't go in $pocket in ${f.label}" }
        require(quantity in 1..f.stackMax(pocket, item)) { "$quantity of item $item" }
    }

    /** The pocket packed from its first slot, the rest zeroed (CompactItemsInBagPocket); TMs and berries by id, as the
     * game sorts them. */
    private fun writePocket(pocket: BagPocket, stacks: List<ItemStack>) {
        val l = f.pocket(pocket)
        val sorted = if (pocket == BagPocket.TMHM || pocket == BagPocket.BERRIES) stacks.sortedBy { it.item } else stacks
        require(sorted.size <= l.slots)
        for (i in 0 until l.slots) {
            val s = sorted.getOrNull(i)
            putU16(saveBlock1, l.off + i * 4, s?.item ?: 0)
            putU16(saveBlock1, l.off + i * 4 + 2, if (s == null) 0 else s.quantity xor key16)
        }
    }

    private fun recordAt(at: MonRef): Int = when (at) {
        is MonRef.Party -> {
            require(at.index in 0 until data.partyCount) { "no party Pokémon ${at.index}" }
            f.partyOff + at.index * Gen3SaveData.PARTY_MON_SIZE
        }
        is MonRef.Pc -> Gen3SaveData.recordOff(at.at)
    }

    private fun editable(at: MonRef): Pair<Gen3BoxMon, Int> {
        val (mon, level) = requireNotNull(data.mon(at)) { "nothing at $at" }
        require(!mon.isBadEgg && !mon.isEgg) { "eggs and Bad Eggs aren't edited" }
        return mon to level
    }

    private fun put(at: MonRef, bytes: ByteArray) {
        val off = recordAt(at)
        bytes.copyInto(if (at is MonRef.Party) saveBlock1 else storage, off)
    }

    /** Gives the Pokémon at [at] [item] to hold (0 = nothing): any item [Gen3Legal.holdable] lists, or the one it has. */
    fun setHeldItem(at: MonRef, item: Int) {
        val (mon, _) = editable(at)
        require(item == 0 || item == mon.heldItem || item in Gen3Legal.holdable(f)) { "item $item can't be held" }
        require(mon.heldItem !in MAIL_ITEMS) { "mail stays with its letter" }
        put(at, mon.withChanges(heldItem = item))
    }

    /**
     * The Pokémon at [at]'s moves: 1-4 different ones, each one it knows already or [Gen3Legal.learnable] at its level.
     * A move it keeps keeps its PP and PP Ups (wherever it moves to); a new one starts with full PP and none.
     */
    fun setMoves(at: MonRef, moves: List<Int>) {
        val (mon, level) = editable(at)
        require(moves.size in 1..4 && moves.all { it in 1..Gen3Legal.LAST_MOVE } && moves.toSet().size == moves.size) { "moves $moves" }
        val known = mon.moves
        val learnable by lazy { Gen3Legal.learnable(f, mon.species, level) }
        require(moves.all { it in known || it in learnable }) { "a move ${mon.species} can't learn" }
        val ids = IntArray(4)
        val pp = IntArray(4)
        var bonuses = 0
        moves.forEachIndexed { i, m ->
            ids[i] = m
            val was = known.indexOf(m)
            if (was >= 0) {
                pp[i] = mon.pp[was]
                bonuses = bonuses or (((mon.ppBonuses shr (was * 2)) and 3) shl (i * 2))
            } else {
                pp[i] = Gen3Legal.basePp(m)
            }
        }
        put(at, mon.withChanges(moves = ids, pp = pp, ppBonuses = bonuses))
    }

    /** The whole file with the edits saved into its other slot ([Gen3SaveFile.withBlocks]). */
    fun build(): ByteArray = file.withBlocks(saveBlock2, saveBlock1, storage)

    companion object {
        const val NATIONAL_DEX_COUNT = 386
    }
}
