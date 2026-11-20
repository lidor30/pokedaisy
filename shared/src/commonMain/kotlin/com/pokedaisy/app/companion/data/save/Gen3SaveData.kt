package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.Gen3Text
import com.pokedaisy.app.companion.data.pad
import com.pokedaisy.app.companion.i18n.tr

/** A PC box position: box 0..13, slot 0..29 (rows of 6, as the game lays them out). */
data class BoxSlot(val box: Int, val slot: Int)

/** Where a Pokémon sits in a save: a party slot or a PC box slot. */
sealed class MonRef {
    data class Party(val index: Int) : MonRef()
    data class Pc(val at: BoxSlot) : MonRef()
}

/** An edit the game itself wouldn't make, with the reason to show. */
open class SaveEditRefused(message: String) : Exception(message)

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
) : MonSave {
    constructor(file: Gen3SaveFile) : this(file.format, file.saveBlock2, file.saveBlock1, file.storage)

    class Box(val name: String, val mons: List<Gen3BoxMon>)

    class PartyMon(val mon: Gen3BoxMon, val level: Int, val hp: Int = 0, val stats: IntArray = IntArray(6)) {
        /** Can fight: not an egg, HP left (CountPartyAliveNonEggMons). */
        val alive: Boolean get() = !mon.isEgg && !mon.isBadEgg && hp > 0
    }

    override val game: SaveGameData get() = Gen3GameData
    override val trainerName: String get() = Gen3Text.decode(saveBlock2, 0, 7)

    /** The 8 raw name bytes at the start of SaveBlock2 (the game's charset, 0xFF-terminated when short). */
    val trainerNameBytes: ByteArray get() = saveBlock2.copyOfRange(0, 8)
    val isFemale: Boolean get() = saveBlock2[8].toInt() != 0
    override val trainerId: Int get() = u16(saveBlock2, 0x0A)
    override val secretId: Int get() = u16(saveBlock2, 0x0C)
    val playTime: String get() = "${u16(saveBlock2, 0x0E)}:${(saveBlock2[0x10].toInt() and 0xFF).pad(2)}"

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

    override val partyCount: Int get() = saveBlock1[format.partyCountOff].toInt() and 0xFF

    val party: List<PartyMon> get() = (0 until partyCount.coerceAtMost(PARTY_SIZE)).map {
        val off = format.partyOff + it * PARTY_MON_SIZE
        PartyMon(
            Gen3BoxMon.read(saveBlock1, off), saveBlock1[off + 0x54].toInt() and 0xFF, u16(saveBlock1, off + PARTY_HP),
            IntArray(6) { s -> u16(saveBlock1, off + PARTY_STATS + s * 2) },
        )
    }

    /** The Pokédex: seen / caught counts, national (all 386) and the game's regional dex. */
    class DexCounts(val seen: Int, val caught: Int, val regionalSeen: Int, val regionalCaught: Int, val regionalSize: Int)

    val dexCounts: DexCounts get() {
        val owned = format.pokedexOff + 0x10
        val seenOff = format.pokedexOff + 0x44
        fun bit(base: ByteArray, off: Int, n: Int) = (base[off + (n - 1) / 8].toInt() shr ((n - 1) % 8)) and 1 == 1
        var seen = 0; var caught = 0; var rSeen = 0; var rCaught = 0
        val regional = HashSet<Int>()
        for (species in 1 until MAX_SPECIES) {
            val n = speciesNationalDex.getOrNull(species) ?: continue
            if (n !in 1..386) continue
            if (Gen3Trade.inRegionalDex(species, format)) regional += n
        }
        for (n in 1..386) {
            val s = bit(saveBlock2, seenOff, n)
            val c = bit(saveBlock2, owned, n)
            if (s) seen++
            if (c) caught++
            if (n in regional) { if (s) rSeen++; if (c) rCaught++ }
        }
        return DexCounts(seen, caught, rSeen, rCaught, regional.size)
    }

    /** Box [box]'s name as stored (up to 8 characters). */
    override fun boxName(box: Int): String = Gen3Text.decode(storage, BOX_NAMES_OFF + box * BOX_NAME_SIZE, BOX_NAME_SIZE)

    fun flag(id: Int): Boolean = (saveBlock1[format.flagsOff + id / 8].toInt() shr (id % 8)) and 1 == 1

    /** How many of [item] the key items pocket holds. */
    fun hasKeyItem(item: Int): Boolean = pocket(BagPocket.KEY).any { it.item == item }

    /** The box the PC opens on. */
    override val currentBox: Int get() = storage[0].toInt() and 0xFF
    override val boxCount: Int get() = BOXES
    override val partyMons: List<PartyEntry> get() = party.map { PartyEntry(it.mon, it.level, it.hp, it.stats) }
    override fun boxMons(box: Int): List<SaveMon> = List(BOX_SLOTS) { s -> mon(BoxSlot(box, s)) }

    val boxes: List<Box> get() = List(BOXES) { b ->
        Box(Gen3Text.decode(storage, BOX_NAMES_OFF + b * BOX_NAME_SIZE, BOX_NAME_SIZE), List(BOX_SLOTS) { s -> mon(BoxSlot(b, s)) })
    }

    fun mon(at: BoxSlot): Gen3BoxMon = Gen3BoxMon.read(storage, recordOff(at))

    /** The Pokémon at [at] (null past the party), and its level: the party's level byte, a box record's from its EXP. */
    override fun mon(at: MonRef): Pair<Gen3BoxMon, Int>? = when (at) {
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
        // The game empties the slots past the count (ZeroMonData): zeroes, but the mail field (+0x55) MAIL_NONE, 0xFF -
        // every slot a party ever shrank from (the check wanted all zeroes and refused a player's Emerald save, Discord
        // 2026-11-11). Anything else there is another layout's data.
        for (i in count until PARTY_SIZE) {
            val off = format.partyOff + i * PARTY_MON_SIZE
            val empty = (0 until PARTY_MON_SIZE).all { b ->
                val v = saveBlock1[off + b].toInt() and 0xFF
                v == 0 || b == MAIL_OFF && v == MAIL_NONE
            }
            // A whole Pokémon left there (a party some tool shrank without clearing) is still this layout's data.
            if (!empty && !Gen3BoxMon.read(saveBlock1, off).let { it.checksumOk && it.species in 1 until MAX_SPECIES }) {
                return "party slot ${i + 1} holds data past the party"
            }
        }
        if (currentBox >= BOXES) return "current box ${currentBox + 1}"
        val records = (0 until BOXES * BOX_SLOTS).map { mon(BoxSlot(it / BOX_SLOTS, it % BOX_SLOTS)) }
        val used = records.count { !it.isEmpty }
        val unreadable = records.count { !it.isEmpty && (it.isBadEgg || it.species !in 1..MAX_SPECIES) }
        // A save of another layout decodes as noise in nearly every slot; a real one has a Bad Egg at most.
        if (used >= 4 && unreadable * 2 > used) return "$unreadable of $used PC records don't decode"
        // No growth rate goes past 1,640,000 at level 100, and the games stop there: more is another engine's record
        // (pokeemerald-expansion keeps 21 bits of experience with a nickname's 11th letter above them, GitHub #1167).
        val tooMuch = (party.map { it.mon } + records).count { !it.isEmpty && !it.isBadEgg && it.exp > MAX_EXP }
        if (tooMuch > 0) return "$tooMuch Pokémon with more experience than level 100's"
        return null
    }

    companion object {
        const val PARTY_SIZE = 6
        const val PARTY_MON_SIZE = 100
        /** struct Pokemon past the BoxPokemon: status (u32), level, mail, HP, then the six stats. */
        const val PARTY_STATUS = 0x50
        const val PARTY_LEVEL = 0x54
        const val PARTY_HP = 0x56
        const val PARTY_STATS = 0x58
        /** struct Pokemon's mail (pokemon.h), and MAIL_NONE: what ZeroMonData leaves there. */
        const val MAIL_OFF = 0x55
        const val MAIL_NONE = 0xFF
        const val BOXES = 14
        const val BOX_SLOTS = 30
        const val BOX_NAMES_OFF = 4 + BOXES * BOX_SLOTS * Gen3BoxMon.SIZE   // 0x8344
        const val BOX_NAME_SIZE = 9
        /** The last internal species id the Gen 3 games know (Chimecho, 411; 412 is the egg). */
        const val MAX_SPECIES = 412
        /** The most experience any Gen 3 growth rate reaches (fluctuating, at level 100). */
        val MAX_EXP: Long = com.pokedaisy.app.companion.data.gen3ExperienceTables.maxOf { it.last() }.toLong()
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
/** Gen 3's five pockets in the order Emerald's bag shows them, as the shared pages name them. */
private val POCKETS = listOf(
    PocketKind.ITEMS to BagPocket.ITEMS, PocketKind.BALLS to BagPocket.BALLS, PocketKind.TMHM to BagPocket.TMHM,
    PocketKind.BERRIES to BagPocket.BERRIES, PocketKind.KEY to BagPocket.KEY,
)

class Gen3SaveEditor(val file: Gen3SaveFile) : MonSaveEditor, TrainerBagEditor {
    private fun refuseIf(condition: Boolean, why: () -> String) { if (condition) throw SaveEditRefused(why()) }
    private fun refuse(why: String): Nothing = throw SaveEditRefused(why)

    private val saveBlock2 = file.saveBlock2.copyOf()
    private val saveBlock1 = file.saveBlock1.copyOf()
    private val storage = file.storage.copyOf()

    override val data: Gen3SaveData get() = Gen3SaveData(file.format, saveBlock2, saveBlock1, storage)
    override fun fresh(): MonSaveEditor = Gen3SaveEditor(file)
    override fun snapshot() = EditorSnapshot(listOf(saveBlock2.copyOf(), saveBlock1.copyOf(), storage.copyOf()))
    override fun restore(snapshot: EditorSnapshot) {
        snapshot.parts.zip(listOf(saveBlock2, saveBlock1, storage)).forEach { (from, to) -> from.copyInto(to) }
    }
    override val canWriteNames: Boolean get() = true
    override fun nicknameMax(at: MonRef): Int = Gen3Names.NICKNAME_MAX
    override val boxNameMax: Int get() = Gen3Names.BOX_NAME_MAX
    override fun canWrite(text: String): Boolean = Gen3Names.encodable(text)
    override val canEditItems: Boolean get() = true
    override fun holdable(): List<Int> = Gen3Legal.holdable(file.format)
    override fun learnable(at: MonRef): Set<Int> = data.mon(at)?.let { (mon, level) -> Gen3Legal.learnable(file.format, mon.species, level) } ?: emptySet()

    override val changed: Boolean get() =
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
        storage.copyInto(storage, ao, bo, bo + Gen3BoxMon.SIZE)
        tmp.copyInto(storage, bo, 0, Gen3BoxMon.SIZE)
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
    override fun setMoney(amount: Long) {
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

    /** Writes a changed record back; a party Pokémon's level byte and stats follow it (CalculateMonStats). */
    private fun put(at: MonRef, bytes: ByteArray) {
        val off = recordAt(at)
        bytes.copyInto(if (at is MonRef.Party) saveBlock1 else storage, off)
        if (at is MonRef.Party) refreshPartyStats(off)
    }

    /**
     * CalculateMonStats for the party Pokémon at [off]: its level from its EXP and its stats, the HP it has moved by as
     * much as its max did (a fainted one stays at 0; Shedinja keeps 1).
     */
    private fun refreshPartyStats(off: Int, fullHp: Boolean = false) {
        val mon = Gen3BoxMon.read(saveBlock1, off)
        if (!mon.checksumOk || mon.isEgg && !fullHp) return
        val level = mon.level.coerceIn(1, 100)
        val stats = Gen3StatCalc.stats(mon, level)
        val oldMax = u16(saveBlock1, off + Gen3SaveData.PARTY_STATS)
        val hp = u16(saveBlock1, off + Gen3SaveData.PARTY_HP)
        val newHp = when {
            mon.species == Gen3Species.SHEDINJA -> if (hp != 0 || oldMax == 0 || fullHp) 1 else 0
            fullHp || (hp == 0 && oldMax == 0) -> stats[0]
            hp == 0 -> 0
            else -> (hp + stats[0] - oldMax).coerceIn(1, stats[0])
        }
        saveBlock1[off + Gen3SaveData.PARTY_LEVEL] = level.toByte()
        putU16(saveBlock1, off + Gen3SaveData.PARTY_HP, newHp)
        for (i in 0 until 6) putU16(saveBlock1, off + Gen3SaveData.PARTY_STATS + i * 2, stats[i])
    }

    // ---- The party and the PC (pokemon_storage_system.c's rules) ----

    private fun partyOff(i: Int) = f.partyOff + i * Gen3SaveData.PARTY_MON_SIZE
    private val partyCount: Int get() = saveBlock1[f.partyCountOff].toInt() and 0xFF

    /** CountPartyAliveNonEggMonsExcept: who'd be left to battle without party Pokémon [except]. */
    fun aliveExcept(except: Int): Int = data.party.withIndex().count { (i, p) -> i != except && p.alive }

    /** A box record as a party Pokémon (BoxMonToMon): no status, no mail, its level and stats, full HP. */
    private fun writePartyMon(index: Int, record: ByteArray, keep: ByteArray? = null) {
        val off = partyOff(index)
        record.copyInto(saveBlock1, off, 0, Gen3BoxMon.SIZE)
        if (keep != null) {
            // A party Pokémon's own status and HP, when it stays a party Pokémon (a trade from a party).
            keep.copyInto(saveBlock1, off + Gen3BoxMon.SIZE, Gen3BoxMon.SIZE, Gen3SaveData.PARTY_MON_SIZE)
            saveBlock1[off + MAIL_BYTE] = Gen3SaveData.MAIL_NONE.toByte()
            refreshPartyStats(off)
        } else {
            for (b in Gen3BoxMon.SIZE until Gen3SaveData.PARTY_MON_SIZE) saveBlock1[off + b] = 0
            saveBlock1[off + MAIL_BYTE] = Gen3SaveData.MAIL_NONE.toByte()
            refreshPartyStats(off, fullHp = true)
        }
    }

    /** Takes party Pokémon [index] out and closes the gap (CompactPartySlots, ZeroMonData on the last). */
    private fun removePartyMon(index: Int) {
        val count = partyCount
        for (i in index until count - 1) {
            saveBlock1.copyInto(saveBlock1, partyOff(i), partyOff(i + 1), partyOff(i + 1) + Gen3SaveData.PARTY_MON_SIZE)
        }
        val last = partyOff(count - 1)
        for (b in 0 until Gen3SaveData.PARTY_MON_SIZE) saveBlock1[last + b] = 0
        saveBlock1[last + MAIL_BYTE] = Gen3SaveData.MAIL_NONE.toByte()
        saveBlock1[f.partyCountOff] = (count - 1).toByte()
    }

    private fun partyBytes(index: Int): ByteArray = partyOff(index).let { saveBlock1.copyOfRange(it, it + Gen3SaveData.PARTY_MON_SIZE) }

    /**
     * Moves the Pokémon at [from] to [to], swapping with whatever is there - between PC slots, party slots, or the two,
     * as the PC's MOVE does. A party slot past the last Pokémon means "add to the party" (WITHDRAW). Refused, with the
     * game's reason, when the PC wouldn't: a party Pokémon holding mail, the last one able to battle, a full party.
     */
    override fun move(from: MonRef, to: MonRef) {
        if (from == to) return
        when {
            from is MonRef.Pc && to is MonRef.Pc -> move(from.at, to.at)
            from is MonRef.Party && to is MonRef.Party -> {
                val count = partyCount
                refuseIf(from.index >= count) { "nothing there" }
                val target = to.index.coerceAtMost(count - 1)
                if (target == from.index) return
                val a = partyBytes(from.index)
                partyBytes(target).copyInto(saveBlock1, partyOff(from.index))
                a.copyInto(saveBlock1, partyOff(target))
            }
            from is MonRef.Pc && to is MonRef.Party -> {
                val mon = data.mon(from.at)
                refuseIf(mon.isEmpty || mon.isBadEgg) { "nothing movable there" }
                val count = partyCount
                if (to.index >= count) {
                    refuseIf(count >= Gen3SaveData.PARTY_SIZE) { tr("Your party is full") }
                    writePartyMon(count, mon.encode())
                    saveBlock1[f.partyCountOff] = (count + 1).toByte()
                    putRecord(from.at, ByteArray(Gen3BoxMon.SIZE))
                } else {
                    val leaving = data.party[to.index]
                    refuseIf(Gen3Legal.isMail(leaving.mon.heldItem)) { tr("A Pokémon holding mail can't go in the PC") }
                    refuseIf(mon.isEgg && aliveExcept(to.index) == 0) { tr("That's your last Pokémon able to battle") }
                    val record = partyBytes(to.index).copyOf(Gen3BoxMon.SIZE)
                    writePartyMon(to.index, mon.encode())
                    putRecord(from.at, record)
                }
            }
            from is MonRef.Party && to is MonRef.Pc -> {
                val leaving = requireNotNull(data.party.getOrNull(from.index)) { "nothing there" }
                refuseIf(Gen3Legal.isMail(leaving.mon.heldItem)) { tr("A Pokémon holding mail can't go in the PC") }
                val target = data.mon(to.at)
                refuseIf(!target.isEmpty && target.isBadEgg) { "a Bad Egg sits there" }
                val incomingAlive = !target.isEmpty && !target.isEgg
                refuseIf(!incomingAlive && aliveExcept(from.index) == 0) { tr("That's your last Pokémon able to battle") }
                val record = partyBytes(from.index).copyOf(Gen3BoxMon.SIZE)
                if (target.isEmpty) removePartyMon(from.index) else writePartyMon(from.index, target.encode())
                putRecord(to.at, record)
            }
        }
    }

    // ---- What a trade puts in or takes out ----

    /** [at]'s Pokémon as stored, or null when there's none. */
    override fun monAt(at: MonRef): Gen3BoxMon? = when (at) {
        is MonRef.Party -> data.party.getOrNull(at.index)?.mon
        is MonRef.Pc -> data.mon(at.at).takeIf { !it.isEmpty }
    }

    /** Party Pokémon [index]'s 100 bytes (a trade from the party keeps its HP and status), or null. */
    fun partyRecord(index: Int): ByteArray? = if (index < partyCount) partyBytes(index) else null

    /** Puts a traded-in [record] at [at] (a party slot past the last = the end of the party); [partyMon] = its party bytes. */
    fun putIncoming(at: MonRef, record: ByteArray, partyMon: ByteArray? = null) {
        when (at) {
            is MonRef.Pc -> putRecord(at.at, record)
            is MonRef.Party -> {
                val count = partyCount
                if (at.index >= count) {
                    refuseIf(count >= Gen3SaveData.PARTY_SIZE) { tr("Your party is full") }
                    writePartyMon(count, record, partyMon)
                    saveBlock1[f.partyCountOff] = (count + 1).toByte()
                } else writePartyMon(at.index, record, partyMon)
            }
        }
    }

    /** Takes the Pokémon at [at] out (a trade sent it away): a PC slot emptied, a party closed up. */
    fun takeOut(at: MonRef) {
        when (at) {
            is MonRef.Pc -> putRecord(at.at, ByteArray(Gen3BoxMon.SIZE))
            is MonRef.Party -> removePartyMon(at.index)
        }
    }

    // ---- A Pokémon's page (each value one the game could give it) ----

    /** The level the Pokémon at [at] had when the save was read: the editor never takes one below it. */
    override fun levelAsRead(at: MonRef): Int {
        val mon = monAt(at) ?: return 1
        val read = Gen3SaveData(f, file.saveBlock2, file.saveBlock1, file.storage)
        val was = read.party.firstOrNull { it.mon.personality == mon.personality && it.mon.otId == mon.otId }?.mon
            ?: read.boxes.asSequence().flatMap { it.mons }.firstOrNull { !it.isEmpty && it.personality == mon.personality && it.otId == mon.otId }
        return was?.level ?: mon.level
    }

    /** Raises the Pokémon at [at] to [level] (its growth rate's EXP for it, like a Rare Candy), or back down to what it was. */
    override fun setLevel(at: MonRef, level: Int) {
        val (mon, _) = editable(at)
        require(level in levelAsRead(at)..100) { "level $level" }
        put(at, mon.withChanges(exp = Gen3Species.expForLevel(mon.species, level)))
    }

    /** IVs 0-31 each, in the game's stat order. */
    override fun setIvs(at: MonRef, ivs: IntArray) {
        val (mon, _) = editable(at)
        require(ivs.size == 6 && ivs.all { it in 0..Gen3StatLimits.IV_MAX }) { "IVs ${ivs.toList()}" }
        put(at, mon.withChanges(ivs = ivs))
    }

    /** EVs: up to 255 a stat, 510 in all (what battling and vitamins can give). */
    override fun setEvs(at: MonRef, evs: IntArray) {
        val (mon, _) = editable(at)
        require(evs.size == 6 && evs.all { it in 0..Gen3StatLimits.EV_MAX } && evs.sum() <= Gen3StatLimits.EV_TOTAL) { "EVs ${evs.toList()}" }
        put(at, mon.withChanges(evs = evs))
    }

    override fun setFriendship(at: MonRef, value: Int) {
        val (mon, _) = editable(at)
        require(value in 0..255) { "friendship $value" }
        put(at, mon.withChanges(friendship = value))
    }

    /** Whether the save's trainer is [mon]'s OT: only they can rename it (the Name Rater's rule). */
    override fun isOwn(mon: SaveMon): Boolean {
        val d = data
        fun name(b: ByteArray) = b.takeWhile { it != 0xFF.toByte() }
        return mon.otTid == d.trainerId && mon.otSid == d.secretId && name((mon as? Gen3BoxMon)?.otNameBytes ?: return false) == name(d.trainerNameBytes.copyOf(7))
    }

    /** A new nickname (1-10 characters the games can write) for a Pokémon this trainer caught. */
    override fun setNickname(at: MonRef, name: String) {
        val (mon, _) = editable(at)
        require(isOwn(mon)) { "only its OT renames it" }
        require((mon as Gen3BoxMon).language != 1) { "a Japanese Pokémon's name isn't edited" }
        require(name.isNotBlank() && name.length <= Gen3Names.NICKNAME_MAX && Gen3Names.encodable(name)) { "name $name" }
        put(at, mon.withChanges(nickname = Gen3Names.encode(name, Gen3BoxMon.NICKNAME_BYTES)))
    }

    /**
     * A new nature, shininess, gender and / or ability: another personality that gives those and keeps everything else it
     * decides (see [Gen3Personality.find]). The ability bit follows the personality, as when the game made the Pokémon.
     * Refused when no personality can (a one-gender species).
     */
    override fun reroll(at: MonRef, nature: Int?, shiny: Boolean?, gender: Gen3Gender?, abilityNum: Int?) {
        val (mon, _) = editable(at)
        require(nature == null || nature in 0..24) { "nature $nature" }
        require(abilityNum == null || abilityNum in Gen3Species.abilities(mon.species).indices) { "ability $abilityNum" }
        val p = Gen3Personality.find(mon, nature, shiny, gender, abilityNum) ?: refuse(tr("No Pokémon like that can exist"))
        val twoAbilities = Gen3Species.abilities(mon.species).size == 2
        val bit = if (twoAbilities) (p and 1L).toInt() else mon.abilityNum
        val changed = Gen3BoxMon.read(mon.withChanges(abilityNum = bit), 0).withPersonality(p)
        put(at, changed)
    }

    // ---- Boxes ----

    /** Renames box [box] (1-8 characters the games can write). */
    override fun renameBox(box: Int, name: String) {
        require(box in 0 until Gen3SaveData.BOXES)
        require(name.isNotBlank() && name.length <= Gen3Names.BOX_NAME_MAX && Gen3Names.encodable(name)) { "box name $name" }
        Gen3Names.encode(name, Gen3SaveData.BOX_NAME_SIZE).copyInto(storage, Gen3SaveData.BOX_NAMES_OFF + box * Gen3SaveData.BOX_NAME_SIZE)
    }

    /** Box [box]'s Pokémon packed from its first slot in [by]'s order (Bad Eggs last, untouched). */
    override fun sortBox(box: Int, by: BoxSort, name: (SaveMon) -> String) {
        val slots = (0 until Gen3SaveData.BOX_SLOTS).map { BoxSlot(box, it) }
        val mons = slots.map { record(it) to data.mon(it) }.filter { !it.second.isEmpty }
        val (good, bad) = mons.partition { !it.second.isBadEgg }
        val order = when (by) {
            BoxSort.DEX -> good.sortedWith(compareBy({ it.second.isEgg }, { speciesNationalDex.getOrElse(it.second.species) { 999 } }, { -it.second.level }))
            BoxSort.LEVEL -> good.sortedWith(compareBy({ it.second.isEgg }, { -it.second.level }, { speciesNationalDex.getOrElse(it.second.species) { 999 } }))
            BoxSort.NAME -> good.sortedWith(compareBy({ it.second.isEgg }, { name(it.second) }))
        } + bad
        slots.forEachIndexed { i, at ->
            val bytes = order.getOrNull(i)?.first ?: ByteArray(Gen3BoxMon.SIZE)
            Gen3SaveData.recordOff(at).let { bytes.copyInto(storage, it) }
        }
    }

    /** RELEASE: a box Pokémon let go (not an egg: the PC won't). */
    override fun release(at: BoxSlot) {
        val mon = data.mon(at)
        require(!mon.isEmpty && !mon.isBadEgg) { "nothing to release at $at" }
        refuseIf(mon.isEgg) { tr("An egg can't be released") }
        putRecord(at, ByteArray(Gen3BoxMon.SIZE))
    }

    // ---- Pokédex ----

    /** Marks every species [species] as seen (all three copies), and caught too when [caught]. */
    fun markDex(species: Iterable<Int>, caught: Boolean) {
        for (s in species) {
            val n = speciesNationalDex.getOrNull(s)?.takeIf { it in 1..NATIONAL_DEX_COUNT } ?: continue
            setDexBit(saveBlock2, seenOff, n)
            setDexBit(saveBlock1, f.seen1Off, n)
            setDexBit(saveBlock1, f.seen2Off, n)
            if (caught && !dexBit(saveBlock2, ownedOff, n)) setDexBit(saveBlock2, ownedOff, n)
        }
    }

    /** Every Pokémon the save holds (party and PC, not eggs) registered as caught, Unown / Spinda's first one kept. */
    override fun registerOwned() {
        val d = data
        for (mon in d.party.map { it.mon } + d.boxes.flatMap { it.mons }) {
            if (mon.isEmpty || mon.isEgg || mon.isBadEgg) continue
            markCaught(mon.species, mon.personality)
        }
    }

    override fun markAllDex(caught: Boolean) = markDex(allSpecies(regionalOnly = !nationalDex), caught)

    // ---- BAG / TRAINER for the shared pages ([TrainerBagEditor]) ----

    override val money: Long get() = data.money
    override val maxMoney: Long get() = Gen3SaveData.MAX_MONEY
    override val playTime: String get() = data.playTime
    override val pockets: List<SavePocket> get() {
        val d = data
        return POCKETS.map { (kind, p) -> SavePocket(kind, f.pocket(p).slots, d.pocket(p)) }
    }
    override fun stackMax(kind: PocketKind, item: Int): Int = f.stackMax(bagPocket(kind), item)
    override fun fixedStack(kind: PocketKind, item: Int): Boolean =
        kind == PocketKind.KEY || (kind == PocketKind.TMHM && item >= Gen3SaveFormat.FIRST_HM)
    override fun addable(kind: PocketKind): List<Int> = if (kind == PocketKind.KEY) emptyList() else Gen3Legal.bagItems(f, bagPocket(kind))
    override fun tmMove(item: Int): Int? = Gen3Legal.tmHmMove(item)
    override val dex: DexSummary get() {
        val c = data.dexCounts
        return DexSummary(
            if (f == Gen3SaveFormat.FIRERED_LEAFGREEN) "KANTO" else "HOENN", c.regionalSeen, c.regionalCaught, c.seen, c.caught, nationalDex,
        )
    }
    override fun setQuantity(kind: PocketKind, index: Int, quantity: Int) = setQuantity(bagPocket(kind), index, quantity)
    override fun addItem(kind: PocketKind, item: Int, quantity: Int) = addItem(bagPocket(kind), item, quantity)

    private fun bagPocket(kind: PocketKind): BagPocket =
        POCKETS.firstOrNull { it.first == kind }?.second ?: throw IllegalArgumentException("no $kind pocket in Gen 3")

    /** Every species of the games (1-386 in the National Dex), or the regional dex's. */
    fun allSpecies(regionalOnly: Boolean): List<Int> = (1 until Gen3SaveData.MAX_SPECIES).filter { s ->
        speciesNationalDex.getOrNull(s)?.let { it in 1..NATIONAL_DEX_COUNT } == true && (!regionalOnly || Gen3Trade.inRegionalDex(s, f))
    }

    // ---- Events ----

    /** Hands out [event] the way its Mystery Gift / e-Reader distribution did: the key item, then its flags. */
    fun giveEvent(event: Gen3Event) {
        require(f in event.formats) { "${event.name} isn't in ${f.label}" }
        refuseIf(eventState(event) != Gen3Event.State.AVAILABLE) { tr("This save has that already") }
        val keys = data.pocket(BagPocket.KEY)
        refuseIf(keys.size >= f.pocket(BagPocket.KEY).slots) { tr("The key items pocket is full") }
        writePocket(BagPocket.KEY, keys + ItemStack(event.item, 1))
        for (flag in event.setFlags(f)) setFlag(flag)
    }

    fun eventState(event: Gen3Event): Gen3Event.State = event.state(f, data)

    /** Gives the Pokémon at [at] [item] to hold (0 = nothing): any item [Gen3Legal.holdable] lists, or the one it has. */
    override fun setHeldItem(at: MonRef, item: Int) {
        val (mon, _) = editable(at)
        require(item == 0 || item == mon.heldItem || item in Gen3Legal.holdable(f)) { "item $item can't be held" }
        require(mon.heldItem !in MAIL_ITEMS) { "mail stays with its letter" }
        put(at, mon.withChanges(heldItem = item))
    }

    /**
     * The Pokémon at [at]'s moves: 1-4 different ones, each one it knows already or [Gen3Legal.learnable] at its level.
     * A move it keeps keeps its PP and PP Ups (wherever it moves to); a new one starts with full PP and none.
     */
    override fun setMoves(at: MonRef, moves: List<Int>) {
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
    override fun build(): ByteArray = file.withBlocks(saveBlock2, saveBlock1, storage)

    companion object {
        const val NATIONAL_DEX_COUNT = 386
        private const val MAIL_BYTE = 0x55
    }
}
