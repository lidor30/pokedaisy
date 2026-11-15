package com.pokedaisy.app.companion.data.save

/**
 * What the SAVE EDITOR's screens need from any game's save, so one editor serves Gen 3 ([Gen3SaveEditor]) and the DS games
 * ([Gen4SaveEditor]): a Pokémon ([SaveMon]), the party and boxes ([MonSave]), the edits ([MonSaveEditor]) and the game's
 * own data a save doesn't hold ([SaveGameData]: names, base stats, learnsets). Each generation keeps its own rules behind it.
 */
interface SaveMon {
    val isEmpty: Boolean
    val isEgg: Boolean
    /** A record the game would show as a Bad Egg (its checksum wrong): never changed or moved. */
    val isBadEgg: Boolean
    val species: Int
    val personality: Long
    val otTid: Int
    val otSid: Int
    val heldItem: Int
    val friendship: Int
    val exp: Long
    val nature: Int
    /** Which of the species' abilities (0 / 1). */
    val abilityNum: Int
    /** The ability's id. */
    val ability: Int
    val gender: Gen3Gender
    val isShiny: Boolean
    val moves: IntArray
    val pp: IntArray
    /** PP Ups per move, 2 bits each (move 0 lowest). */
    val ppBonuses: Int
    /** Six values in the game's stat order: HP, Attack, Defense, Speed, Sp. Atk, Sp. Def. */
    val ivs: IntArray
    val evs: IntArray
    val pokerus: Int
    /** 0 = hatched from an egg. */
    val metLevel: Int
    /** Level from its EXP (a box record keeps no level). */
    val level: Int
    /** Its nickname as the game shows it, or null (unreadable here: the species' name stands in). */
    val nickname: String?
    val otName: String
    /** The game it was met in, as a label. */
    val metGameLabel: String
    /** The game data its names and numbers come from. */
    val game: SaveGameData
}

/** A party slot: the Pokémon and what only party records keep. */
class PartyEntry(val mon: SaveMon, val level: Int, val hp: Int, val stats: IntArray)

/** A save's trainer, party and boxes, read. */
interface MonSave {
    val game: SaveGameData
    val trainerName: String
    val trainerId: Int
    val secretId: Int
    val partyCount: Int
    val partyMons: List<PartyEntry>
    val boxCount: Int
    /** The box the PC opens on. */
    val currentBox: Int
    fun boxName(box: Int): String
    fun boxMons(box: Int): List<SaveMon>
    /** The Pokémon at [at] (null when there's none) and its level. */
    fun mon(at: MonRef): Pair<SaveMon, Int>?
}

/** How a box is sorted ([MonSaveEditor.sortBox]). */
enum class BoxSort { DEX, LEVEL, NAME }

/**
 * A save being edited: every change only what that game could hold, refused with the game's reason ([SaveEditRefused])
 * or an [IllegalArgumentException] for a value the screens never offer. [build] gives the whole file to write.
 */
interface MonSaveEditor {
    val data: MonSave
    val changed: Boolean
    /** A new editor over the same file (UNDO ALL). */
    fun fresh(): MonSaveEditor
    fun build(): ByteArray

    fun monAt(at: MonRef): SaveMon?
    fun move(from: MonRef, to: MonRef)
    fun release(at: BoxSlot)
    fun sortBox(box: Int, by: BoxSort, name: (SaveMon) -> String)

    /** Box names and nicknames can be written ([renameBox], [setNickname]). */
    val canWriteNames: Boolean
    /** The most characters a nickname of the Pokémon at [at] takes, and a box name. */
    fun nicknameMax(at: MonRef): Int
    val boxNameMax: Int
    /** Whether every character of [text] can be written in this game's names. */
    fun canWrite(text: String): Boolean
    fun renameBox(box: Int, name: String)
    fun isOwn(mon: SaveMon): Boolean
    fun setNickname(at: MonRef, name: String)

    fun levelAsRead(at: MonRef): Int
    fun setLevel(at: MonRef, level: Int)
    fun setIvs(at: MonRef, ivs: IntArray)
    fun setEvs(at: MonRef, evs: IntArray)
    fun setFriendship(at: MonRef, value: Int)
    fun reroll(at: MonRef, nature: Int? = null, shiny: Boolean? = null, gender: Gen3Gender? = null, abilityNum: Int? = null)
    fun learnable(at: MonRef): Set<Int>
    fun setMoves(at: MonRef, moves: List<Int>)
    /** Held items can be changed ([holdable], [setHeldItem]). */
    val canEditItems: Boolean
    fun holdable(): List<Int>
    fun setHeldItem(at: MonRef, item: Int)
}

/** A game's data that a save doesn't carry: its names and the numbers its rules use. */
interface SaveGameData {
    /** The species' name, or null for an id this game hasn't. */
    fun speciesName(species: Int): String?
    fun moveName(move: Int): String?
    fun itemName(item: Int): String?
    fun abilityName(ability: Int): String
    fun nationalDex(species: Int): Int?
    /** The species' abilities: one or two ids. */
    fun abilities(species: Int): List<Int>
    /** 0 always male, 254 always female, 255 genderless, else the female threshold. */
    fun genderRatio(species: Int): Int
    /** HP, Attack, Defense, Speed, Sp. Atk, Sp. Def. */
    fun baseStats(species: Int): IntArray
    fun expForLevel(species: Int, level: Int): Long
    fun basePp(move: Int): Int
    fun isMail(item: Int): Boolean
    /** Species ids whose personality picks a form or branch that must stay: Unown, Wurmple's line. */
    val unown: Int
    val wurmpleLine: IntRange
    val shedinja: Int

    fun hasBothGenders(species: Int): Boolean = genderRatio(species) !in setOf(0, 254, 255)
    fun ppWithBonus(move: Int, ups: Int): Int = basePp(move).let { it + it * 20 * ups / 100 }
    /** CalculateMonStats (the same in Gen 3 and 4). */
    fun stats(species: Int, level: Int, ivs: IntArray, evs: IntArray, nature: Int): IntArray {
        val base = baseStats(species)
        val out = IntArray(6)
        out[0] = if (species == shedinja) 1 else (2 * base[0] + ivs[0] + evs[0] / 4) * level / 100 + level + 10
        for (i in 1 until 6) {
            val n = (2 * base[i] + ivs[i] + evs[i] / 4) * level / 100 + 5
            out[i] = when {
                nature / 5 == nature % 5 -> n
                i - 1 == nature / 5 -> n * 110 / 100
                i - 1 == nature % 5 -> n * 90 / 100
                else -> n
            }
        }
        return out
    }
    fun stats(mon: SaveMon, level: Int): IntArray = stats(mon.species, level, mon.ivs, mon.evs, mon.nature)
    fun levelFor(species: Int, exp: Long): Int {
        var level = 1
        while (level < 100 && exp >= expForLevel(species, level + 1)) level++
        return level
    }
}

/** A bag pocket as the editor shows it: every generation's pockets, named by what they hold. */
enum class PocketKind { ITEMS, MEDICINE, BALLS, TMHM, BERRIES, MAIL, BATTLE, KEY }

/** One pocket: its stacks in the save's order and how many slots it has. */
class SavePocket(val kind: PocketKind, val slots: Int, val stacks: List<ItemStack>)

/**
 * The Pokédex in numbers: the regional dex's ([regionLabel], null when the game has none here) and the National one.
 * [national]: the save has the National Pokédex, so marking every Pokémon reaches past the regional ones.
 */
class DexSummary(
    val regionLabel: String?, val regionalSeen: Int, val regionalCaught: Int,
    val seen: Int, val caught: Int, val national: Boolean,
)

/**
 * The SAVE EDITOR's BAG and TRAINER pages on any generation's save: the money, the bag's pockets and the Pokédex, read
 * and changed only to what the game itself could hold (an [IllegalArgumentException] for a value the screens never offer).
 */
interface TrainerBagEditor {
    val money: Long
    val maxMoney: Long
    /** "H:MM" played, or null where it isn't read. */
    val playTime: String?
    /** The pockets in the order the game's bag shows them. */
    val pockets: List<SavePocket>
    fun pocket(kind: PocketKind): SavePocket? = pockets.firstOrNull { it.kind == kind }
    /** The most one stack of [item] holds. */
    fun stackMax(kind: PocketKind, item: Int): Int
    /** A stack the player can't change: key items and HMs are handed out once and the game needs them. */
    fun fixedStack(kind: PocketKind, item: Int): Boolean
    /** Every item the game files in [kind] that the player could have (ADD ITEM's list, before the ones already there). */
    fun addable(kind: PocketKind): List<Int>
    /** The move a TM / HM teaches, for its label. */
    fun tmMove(item: Int): Int?
    val dex: DexSummary

    fun setMoney(amount: Long)
    /** Stack [index] of [kind] to [quantity] (1 up to [stackMax]); 0 takes it out (never a [fixedStack]). */
    fun setQuantity(kind: PocketKind, index: Int, quantity: Int)
    fun addItem(kind: PocketKind, item: Int, quantity: Int)
    /** Every Pokémon in the party and the PC registered as caught, the way the game registers one. */
    fun registerOwned()
    /** Every Pokémon the dex lists (the regional ones until the save has the National Pokédex) seen, or caught. */
    fun markAllDex(caught: Boolean)
}
