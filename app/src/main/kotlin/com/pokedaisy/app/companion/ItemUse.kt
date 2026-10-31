package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.ItemUseOutcome
import com.pokedaisy.app.companion.data.ItemUseResult
import com.pokedaisy.app.companion.data.PartyTarget
import com.pokedaisy.app.companion.data.PartyTargets

/** Using a bag item from the ITEMS tab (FieldItems.kt); null where the game has no such support.
 * The `can*` / [marked] answers are the ROM's (they don't change in a battle or a menu, where a
 * tap gets NOT NOW); every [done] runs on the main thread. */
interface ItemUse {
    /** The game's VAR_REPEL_STEP_COUNT (FireRed 0x4020, Emerald 0x4021): the HUD's REPEL steps; null = not known here. */
    val repelVar: Int? get() = null

    /** A Repel's steps in [repelVar]'s value [v]: without a Lure's bit (SoulGold keeps one there), 0 while a Lure works. */
    fun repelSteps(v: Int): Int = v

    /** Whether [itemId] gets a USE button in this game. */
    fun canUse(itemId: Int): Boolean

    /** Whether [itemId] gets a GIVE button (an item a Pokémon can hold). */
    fun canGive(itemId: Int): Boolean = false

    /** The bag row's mark: USE or GIVE does something with [itemId] here. */
    fun marked(itemId: Int): Boolean = false

    /** USE first picks a party Pokémon (medicine; a Repel is used on the spot)... */
    fun picksMon(itemId: Int): Boolean = false

    /** ... and then one of its moves (Ether, PP Up). */
    fun picksMove(itemId: Int): Boolean = false

    /** Uses one [itemId] in the game, on the spot (a Repel). */
    fun use(itemId: Int, done: (ItemUseOutcome) -> Unit)

    /** Who USE ([give] false) or GIVE of [itemId] could go to, slot by slot. */
    fun targets(itemId: Int, give: Boolean, done: (PartyTargets) -> Unit) = done(PartyTargets(ItemUseResult.FAILED))

    /** Uses one [itemId] on [target]'s Pokémon ([moveIndex] for a one-move PP item). */
    fun useOn(itemId: Int, target: PartyTarget, moveIndex: Int, done: (ItemUseOutcome) -> Unit) = done(ItemUseOutcome(ItemUseResult.FAILED))

    /** Gives one [itemId] to [target]'s Pokémon to hold (its old item back into the bag). */
    fun give(itemId: Int, target: PartyTarget, done: (ItemUseOutcome) -> Unit) = done(ItemUseOutcome(ItemUseResult.FAILED))

    /** Whether the summary has TAKE here. */
    fun canTake(): Boolean = false

    /** What each party Pokémon holds ([PartyTarget.heldItem]; OK = it can be taken). */
    fun heldItems(done: (PartyTargets) -> Unit) = done(PartyTargets(ItemUseResult.FAILED))

    /** Takes the item [target]'s Pokémon holds into the bag. */
    fun take(target: PartyTarget, done: (ItemUseOutcome) -> Unit) = done(ItemUseOutcome(ItemUseResult.FAILED))

    /** Whether the BAG has a PC button here (PC anywhere, GitHub #85). */
    fun canPc(): Boolean = false

    /** Opens the game's own PC on the field (USED = it's open; NOT_NOW / IN_BATTLE: not from here, now). */
    fun openPc(done: (ItemUseOutcome) -> Unit) = done(ItemUseOutcome(ItemUseResult.FAILED))

    /** Whether the PC's moves work here (BoxSwap.kt): a box Pokémon's WITHDRAW / SWAP, a party one's DEPOSIT. */
    fun canSwapBox(): Boolean = false

    /** Swaps box [box]'s slot [slot] ([boxPersonality]) with party slot [partySlot] ([partyPersonality]) in the game. */
    fun swapBox(
        box: Int, slot: Int, boxPersonality: Long, partySlot: Int, partyPersonality: Long,
        done: (com.pokedaisy.app.companion.data.BoxSwapResult) -> Unit,
    ) = done(com.pokedaisy.app.companion.data.BoxSwapResult.FAILED)

    /** DEPOSIT: party slot [partySlot] ([partyPersonality]) into box [box]'s first free slot in the game. */
    fun depositToBox(
        partySlot: Int, partyPersonality: Long, box: Int,
        done: (com.pokedaisy.app.companion.data.BoxSwapResult) -> Unit,
    ) = done(com.pokedaisy.app.companion.data.BoxSwapResult.FAILED)

    /** WITHDRAW: box [box]'s slot [slot] ([boxPersonality]) into the party in the game. */
    fun withdrawFromBox(
        box: Int, slot: Int, boxPersonality: Long,
        done: (com.pokedaisy.app.companion.data.BoxSwapResult) -> Unit,
    ) = done(com.pokedaisy.app.companion.data.BoxSwapResult.FAILED)
}
