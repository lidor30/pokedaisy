package com.pokedaisy.app.companion.data

/**
 * The PC's moves on Crystal (English rev 0 / rev 1 / Australia, German Kristall), the current box only: the game's own
 * SendGetMonIntoFromBox and RemoveMonFromPartyOrBox (pokecrystal's engine/pokemon/move_mon.asm, both bank 3), the pair the
 * PC's DEPOSIT / WITHDRAW run, far-called from the overworld loop (HandleMap) with [GameCalls.gbCallAt] like Gen 1's PC.
 * Only the current box: the game keeps it in sBox (SRAM bank 1), the others only on a box change, which saves the game.
 *
 * Each call is injected at HandleMap, its arguments written into wCurPartyMon / wCurPartySpecies /
 * wPokemonWithdrawDepositParameter before it runs; the next call waits for the loop to come by again, so the one before
 * has returned. A `ret` far-called there ([SETTLE]) lets a call finish before its result is read. A move is the copy,
 * its count checked, then the removal, its count checked: refused before either if the party or the box isn't what the
 * companion showed. Like the game's PC, the box is in the battery RAM at once and the party once the game is saved.
 * Checked headless on both carts (mgba_dump's `gbcallat` / `gbsram`), then saved in the game and rebooted.
 */
class Gen2BoxCalls(
    /** SendGetMonIntoFromBox's address, bank 3. */
    val send: Int = 0x5B3F,
    /** RemoveMonFromPartyOrBox's address, bank 3. */
    val remove: Int,
    /** Cart offsets and the bytes the code there must have. */
    val signatures: List<Pair<Long, String>>,
    /** HandleMap's first instruction, as (bank << 16) | addr: the overworld loop, where a call is injected. */
    val at: Int = 0x256773,
    val curPartySpecies: Long = wramx(1, 0xD108),
    val curPartyMon: Long = wramx(1, 0xD109),
    val withdrawDeposit: Long = wramx(1, 0xD10B),
    val box: Long = GEN2_CRYSTAL_BOXES.activeBox,
    /** SaveBox / LoadBox / SaveGameData, bank 5: what the PC's CHANGE BOX runs around its save (ChangeBoxSaveGame). */
    val saveBox: Int = 0x4E0C,
    val loadBox: Int = 0x5021,
    val saveGame: Int = 0x4B85,
    val curBox: Long = GEN2_CRYSTAL_BOXES.curBox,
    /** wPlayerID and the save's copy of it (sPlayerData's first word), and sCheckValue1 (99 once the game was saved). */
    val playerId: Long = wramx(1, 0xD47B),
    val savedPlayerId: Long = cartRam(1, 0xA009),
    val saveCheck: Long = cartRam(1, 0xA008),
) {
    fun codeMatches(rom: (Long, Int) -> ByteArray?): Boolean = signatures.all { (off, hex) ->
        val want = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        rom(off, want.size)?.contentEquals(want) == true
    }

    private companion object {
        const val FARCALL = 0x0008   // FarCall_hl: a = bank, hl = address
        const val SETTLE = 0x0015    // rst $10's (Bankswitch's) closing `ret`: a call that does nothing
        const val BANK = 3
        const val PARTY_MON_SIZE = 0x30
        const val BOX_MON_SIZE = 32
        const val BOX_MONS = 0x16
        const val BOX_SLOTS = 20
        const val PARTY_MAX = 6
        const val EGG = 0xFD
        const val HP = 0x22
        const val ITEM = 1
        // wPokemonWithdrawDepositParameter: Send's PC_WITHDRAW 0 / PC_DEPOSIT 1, Remove's REMOVE_PARTY 0 / REMOVE_BOX 1.
        const val TO_PARTY = 0
        const val TO_BOX = 1
        const val FROM_PARTY = 0
        const val FROM_BOX = 1
        const val SAVE_BANK = 5
        const val NUM_BOXES = 14
        const val SAVE_CHECK_VALUE = 99
        /** The save takes about a dozen frames: the loop comes by again only then. */
        const val SAVE_SETTLE_TRIES = 40
        const val SAVE_TRIES = 5
    }

    private fun u8(g: GameCalls, addr: Long) = g.read(addr, 1)[0].toInt() and 0xFF

    private class State(val partyCount: Int, val partySpecies: ByteArray, val party: ByteArray, val boxCount: Int, val box: ByteArray)

    private fun state(g: GameCalls, cfg: Gen2Config): State {
        val count = u8(g, cfg.partyCount).coerceAtMost(PARTY_MAX)
        val box = g.read(this.box, BOX_MONS + BOX_SLOTS * BOX_MON_SIZE)
        return State(count, g.read(cfg.partyCount + 1, PARTY_MAX), g.read(cfg.partyMons, PARTY_MAX * PARTY_MON_SIZE),
            (box[0].toInt() and 0xFF).coerceAtMost(BOX_SLOTS), box)
    }

    private fun partyKey(s: State, slot: Int) = gen2MonKey(s.party, slot * PARTY_MON_SIZE)
    private fun boxKey(s: State, slot: Int) = gen2MonKey(s.box, BOX_MONS + slot * BOX_MON_SIZE)
    private fun boxSpecies(s: State, slot: Int) = s.box[1 + slot].toInt() and 0xFF

    /** A party Pokémon that could fight: not an egg, HP left. */
    private fun fighter(s: State, slot: Int) = (s.partySpecies[slot].toInt() and 0xFF) != EGG &&
        ((s.party[slot * PARTY_MON_SIZE + HP].toInt() and 0xFF) shl 8 or (s.party[slot * PARTY_MON_SIZE + HP + 1].toInt() and 0xFF)) > 0

    /** Far-calls [fn] with its arguments: false = the loop didn't come by (not on the field). */
    private fun call(g: GameCalls, fn: Int, slot: Int, species: Int, param: Int): Boolean {
        if (!g.gbCallAt(at, FARCALL, BANK, 0, fn)) return false
        g.write(curPartyMon, byteArrayOf(slot.toByte()))
        g.write(curPartySpecies, byteArrayOf(species.toByte()))
        g.write(withdrawDeposit, byteArrayOf(param.toByte()))
        return true
    }

    /** Lets the call before finish: the loop coming by again means it has returned. */
    private fun settle(g: GameCalls, tries: Int = 6): Boolean = (0 until tries).any { g.gbCallAt(at, SETTLE, 0, 0, 0) }

    /**
     * CHANGE BOX: box [to] becomes the current one, the game's way (ChangeBoxSaveGame): the current box into its place
     * (SaveBox), wCurBox, the new one into sBox (LoadBox), then the whole game saved (SaveGameData), party and all. Refused
     * like AskOverwriteSaveFile would: with no save, or another player's on the cart ([BoxChangeResult.OTHER_SAVE]).
     */
    fun changeBox(g: GameCalls, to: Int): BoxChangeResult {
        val current = u8(g, curBox)
        if (to !in 0 until NUM_BOXES || current !in 0 until NUM_BOXES) return BoxChangeResult.FAILED
        if (to == current) return BoxChangeResult.CHANGED
        if (u8(g, saveCheck) != SAVE_CHECK_VALUE || !g.read(playerId, 2).contentEquals(g.read(savedPlayerId, 2))) return BoxChangeResult.OTHER_SAVE
        if (!g.gbCallAt(at, FARCALL, SAVE_BANK, 0, saveBox)) return BoxChangeResult.NOT_NOW
        if (!settle(g)) return BoxChangeResult.FAILED
        g.write(curBox, byteArrayOf(to.toByte()))
        if (!g.gbCallAt(at, FARCALL, SAVE_BANK, 0, loadBox) || !settle(g)) return BoxChangeResult.FAILED.also { loadBack(g, current) }
        // The save has to follow: until it does, the cart's sBox is the new box under a save that names the old one.
        if (!(0 until SAVE_TRIES).any { g.gbCallAt(at, FARCALL, SAVE_BANK, 0, saveGame) }) {
            if (g.gbCallAt(at, FARCALL, SAVE_BANK, 0, saveBox)) settle(g)
            return BoxChangeResult.FAILED.also { loadBack(g, current) }
        }
        if (!settle(g, SAVE_SETTLE_TRIES)) return BoxChangeResult.FAILED
        return if (u8(g, curBox) == to) BoxChangeResult.CHANGED else BoxChangeResult.FAILED
    }

    /** A box change that stopped half way: wCurBox back to [current] and that box into sBox again, as the save has them. */
    private fun loadBack(g: GameCalls, current: Int) {
        g.write(curBox, byteArrayOf(current.toByte()))
        if ((0 until SAVE_TRIES).any { g.gbCallAt(at, FARCALL, SAVE_BANK, 0, loadBox) }) settle(g)
    }

    /** WITHDRAW: the current box's slot [slot] ([key]) into the party. */
    fun withdraw(g: GameCalls, cfg: Gen2Config, slot: Int, key: Long): BoxSwapResult {
        val s = state(g, cfg)
        if (slot !in 0 until s.boxCount || boxKey(s, slot) != key) return BoxSwapResult.CHANGED
        if (s.partyCount >= PARTY_MAX) return BoxSwapResult.PARTY_FULL
        return moveIn(g, cfg, s, slot)
    }

    private fun moveIn(g: GameCalls, cfg: Gen2Config, s: State, slot: Int): BoxSwapResult {
        if (!call(g, send, slot, boxSpecies(s, slot), TO_PARTY)) return BoxSwapResult.NOT_NOW
        if (!settle(g) || u8(g, cfg.partyCount) != s.partyCount + 1) return BoxSwapResult.FAILED
        if (!call(g, remove, slot, boxSpecies(s, slot), FROM_BOX) || !settle(g)) return BoxSwapResult.FAILED
        return if ((g.read(box, 1)[0].toInt() and 0xFF) == s.boxCount - 1) BoxSwapResult.SWAPPED else BoxSwapResult.FAILED
    }

    /** DEPOSIT: party slot [partySlot] ([key]) into the current box. */
    fun deposit(g: GameCalls, cfg: Gen2Config, partySlot: Int, key: Long): BoxSwapResult {
        val s = state(g, cfg)
        refuseOut(cfg, s, partySlot, key, incomingFights = false)?.let { return it }
        return moveOut(g, cfg, s, partySlot)
    }

    /** What keeps party slot [partySlot] in the party; [incomingFights]: a box Pokémon that can fight takes its place. */
    private fun refuseOut(cfg: Gen2Config, s: State, partySlot: Int, key: Long, incomingFights: Boolean): BoxSwapResult? = when {
        partySlot !in 0 until s.partyCount || partyKey(s, partySlot) != key -> BoxSwapResult.CHANGED
        (s.party[partySlot * PARTY_MON_SIZE + ITEM].toInt() and 0xFF) in cfg.mailItems -> BoxSwapResult.HOLDS_MAIL
        !incomingFights && (0 until s.partyCount).none { it != partySlot && fighter(s, it) } -> BoxSwapResult.LAST_MON
        s.boxCount >= BOX_SLOTS -> BoxSwapResult.BOX_FULL
        else -> null
    }

    private fun moveOut(g: GameCalls, cfg: Gen2Config, s: State, partySlot: Int): BoxSwapResult {
        val species = s.partySpecies[partySlot].toInt() and 0xFF
        if (!call(g, send, partySlot, species, TO_BOX)) return BoxSwapResult.NOT_NOW
        if (!settle(g) || (g.read(box, 1)[0].toInt() and 0xFF) != s.boxCount + 1) return BoxSwapResult.FAILED
        if (!call(g, remove, partySlot, species, FROM_PARTY) || !settle(g)) return BoxSwapResult.FAILED
        return if (u8(g, cfg.partyCount) == s.partyCount - 1) BoxSwapResult.SWAPPED else BoxSwapResult.FAILED
    }

    /** SWAP: party slot [partySlot] ([partyKey]) into the box, then box slot [slot] ([boxKey]) out (it keeps its slot:
     * a deposit goes to the box's end). */
    fun swap(g: GameCalls, cfg: Gen2Config, slot: Int, boxKey: Long, partySlot: Int, partyKey: Long): BoxSwapResult {
        val s = state(g, cfg)
        if (slot !in 0 until s.boxCount || boxKey(s, slot) != boxKey) return BoxSwapResult.CHANGED
        refuseOut(cfg, s, partySlot, partyKey, incomingFights = boxSpecies(s, slot) != EGG)?.let { return it }
        val out = moveOut(g, cfg, s, partySlot)
        if (out != BoxSwapResult.SWAPPED) return out
        return moveIn(g, cfg, state(g, cfg), slot)
    }
}

/** What [Gen2BoxCalls.changeBox] did. */
enum class BoxChangeResult {
    /** The box is the current one now and the game is saved. */
    CHANGED,
    /** Not on the field with the controls free. */
    NOT_NOW,
    IN_BATTLE,
    /** The game was never saved, or the save on the cart is another player's: the game would ask first. */
    OTHER_SAVE,
    /** The save couldn't be backed up first: nothing was changed. */
    NO_BACKUP,
    FAILED,
}

/** SaveBox, LoadBox and SaveGameData: the same bytes in every Crystal cart these calls are for. */
private val SAVE_SIGNATURES = listOf(
    0x05 * 0x4000L + (0x4E0C - 0x4000) to "cdd850cdf950c9",   // SaveBox
    0x05 * 0x4000L + (0x5021 - 0x4000) to "cdd850cd7d51c9",   // LoadBox
    0x05 * 0x4000L + (0x4B85 - 0x4000) to "cd104cc9",         // SaveGameData
)

/** Crystal rev 0 / rev 1 / Australia: pret/pokecrystal's symbols, the same in all three. */
val GEN2_BOX_CALLS_CRYSTAL = Gen2BoxCalls(
    remove = 0x6039,
    signatures = listOf(
        0x03 * 0x4000L + (0x5B3F - 0x4000) to "3e01cdcb2ffa0bd1a7",   // SendGetMonIntoFromBox: OpenSRAM, the parameter
        0x03 * 0x4000L + (0x6039 - 0x4000) to "21d7dcfa0bd1a72808",   // RemoveMonFromPartyOrBox: wPartyCount, the parameter
        0x25 * 0x4000L + (0x6773 - 0x4000) to "cdb067cdc1673e2521087e", // HandleMap
    ) + SAVE_SIGNATURES,
)

/** German Kristall: Crystal's, RemoveMonFromPartyOrBox a byte earlier. */
val GEN2_BOX_CALLS_CRYSTAL_DE = Gen2BoxCalls(
    remove = 0x6038,
    signatures = listOf(
        0x03 * 0x4000L + (0x5B3F - 0x4000) to "3e01cdb52ffa0bd1a7",
        0x03 * 0x4000L + (0x6038 - 0x4000) to "21d7dcfa0bd1a72808",
        0x25 * 0x4000L + (0x6773 - 0x4000) to "cdb067cdc1673e2521097e",
    ) + SAVE_SIGNATURES,
)
