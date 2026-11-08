package com.pokedaisy.app.companion.data

/**
 * USE on a TM / HM from the ITEMS tab opens the game's own TM screen, as using it from the game's bag does (FireRed's TM
 * case, Emerald's bag: gItemUseCB = ItemUseCB_TMHM, then CB2_ShowPartyMenuForItemUse) - its "Teach which POKéMON?" party
 * menu with ABLE / NOT ABLE / LEARNED, its "forget a move" summary, its fanfare, its rules for a TM used up (REUSABLE TMS'
 * patch included). The companion only starts it: everything after is the game's.
 *
 * The bag's way into it, from the field: what the START menu does before its party screen (LockPlayerFieldControls,
 * FadeScreen(FADE_TO_BLACK), then once black CleanupOverworldWindowsAndTilemaps), gSpecialVar_ItemId = the TM, the party
 * cursor on the first slot, CB2_ShowPartyMenuForItemUse. That one hands the party menu the bag (or TM case) to go back to
 * when it closes - gPartyMenu.exitCallback, set before the screen starts - which the companion points at CB2_ReturnToField
 * instead (the game's own way back from a menu: the field fades in, the controls come back). Emerald's InitPartyMenu is
 * static and takes seven arguments, so the public wrapper it is, in both games.
 *
 * Checked headless on the user's saves: HM05 taught through the game's screens (FireRed: JOLTEON, Emerald: KADABRA, a move
 * forgotten on the game's summary), the field back after with the controls free, the HM still in the bag.
 */
data class TmFns(
    /** gSpecialVar_ItemId (u16): the item the party menu uses. */
    val specialVarItemId: Long,
    /** gItemUseCB and ItemUseCB_TMHM, the party menu's action for the picked Pokémon. */
    val itemUseCb: Long,
    val itemUseCbTmhm: Long,
    /** CB2_ShowPartyMenuForItemUse: InitPartyMenu(FIELD, SINGLE, USE_ITEM, keep the cursor, TEACH_WHICH_MON, ...). */
    val showPartyMenuForItemUse: Long,
    /** gPartyMenu: exitCallback at +0, the cursor (slotId) at [partyMenuSlotOff]. */
    val partyMenu: Long,
    val partyMenuSlotOff: Int,
    /** CB2_InitPartyMenu: gMain.callback2 once the party menu is on its way. */
    val initPartyMenu: Long,
    /** gMain.callback2 (gMain + 4). */
    val mainCallback2: Long,
    val setMainCallback2: Long,
    val returnToField: Long,
    val cleanupOverworld: Long,
    /** FadeScreen(mode, delay): FADE_TO_BLACK 1, FADE_FROM_BLACK 0. */
    val fadeScreen: Long,
    val lockControls: Long,
    val unlockControls: Long,
    /** CRC32 of each function's first 32 bytes: itemUseCbTmhm, showPartyMenuForItemUse, initPartyMenu, setMainCallback2,
     * returnToField, cleanupOverworld, fadeScreen, lockControls, unlockControls. */
    val codeCrcs: List<Long>,
    /** ITEM_TM01 and HM08: the same in every Gen 3 game. */
    val firstTm: Int = 289,
    val lastHm: Int = 346,
) {
    val functions get() = listOf(
        itemUseCbTmhm, showPartyMenuForItemUse, initPartyMenu, setMainCallback2, returnToField, cleanupOverworld, fadeScreen,
        lockControls, unlockControls,
    )

    /** Whether [itemId] is a TM or an HM. */
    fun teaches(itemId: Int) = itemId in firstTm..lastHm
}

// TM_FIRERED_REV1 and TM_EMERALD are records in gamedata/gen3/records/ (GameDataRecordsGen.kt).

/** The TM screen's calls for a ROM, by what the companion detected it as: retail FireRed rev 0 / 1, LeafGreen rev 0 / 1 and
 * Emerald, English from the records and every other language from [TM_PORTS] (scripts/port_field_items.py), and the hacks
 * edited into retail's code in place ([hack] false for them; true for a rebuild like Recharged Emerald; Emerald Legacy, a
 * rebuild too, has records of its own):
 * FireRed rev 0's ([FIRERED_REV0_HACKS], Ultra Violet's PUVV, the FireRed-kind ones) and Emerald's (Glazed, Kaizo, the
 * randomized cart). Their own TM rules (reusable, CFRU's TM51+ in the HMs' ids) are the game's. Not Ruby / Sapphire: their
 * party menu goes back to the bag by a fixed call (pokemon_menu.c's sub_808B25C), with no exit to point at the field. The
 * code CRCs still decide. */
fun tmFnsFor(kind: GameKind?, gameCode: String, revision: Int, hack: Boolean): TmFns? = when {
    hack -> null
    // Amethyst keeps every function here as retail's, but CleanupOverworldWindowsAndTilemaps never came back there (headless:
    // the game restarted at its title screen); something it calls is CFRU's own.
    kind == GameKind.AMETHYST -> null
    kind in FIRERED_REV0_HACKS || (kind == GameKind.FIRERED && gameCode == "PUVV") -> TM_FIRERED_REV0
    kind == GameKind.GLAZED -> TM_EMERALD.takeIf { gameCode == "BPEE" }
    kind == GameKind.FIRERED && gameCode == "BPRE" -> when (revision) { 0 -> TM_FIRERED_REV0; 1 -> TM_FIRERED_REV1; else -> null }
    kind == GameKind.FIRERED && gameCode == "BPGE" -> when (revision) { 0 -> TM_LEAFGREEN_REV0; 1 -> TM_LEAFGREEN_REV1; else -> null }
    kind == GameKind.EMERALD && gameCode == "BPEE" -> if (emeraldLegacy) TM_EMERALD_LEGACY else TM_EMERALD
    kind == GameKind.FIRERED || kind == GameKind.EMERALD -> TM_PORTS["$gameCode$revision"]
    else -> null
}

/** Every table that may fit: [tmFnsFor]'s, or for a rebuild sharing retail's kind and game code ([rebuild]: Recharged
 * Emerald's three builds) all of its builds' - the code CRCs pick the one running. */
fun tmCandidatesFor(kind: GameKind?, gameCode: String, revision: Int, rebuild: Boolean): List<TmFns> = when {
    rebuild -> if (kind == GameKind.EMERALD && gameCode == "BPEE")
        listOf(TM_RECHARGED_EMERALD_V2_2_5, TM_RECHARGED_EMERALD_V2_2_8, TM_RECHARGED_EMERALD_REBALANCED_V2_2_8) else emptyList()
    else -> listOfNotNull(tmFnsFor(kind, gameCode, revision, hack = false))
}

/** Whether the running ROM has [tm]'s functions where they're expected. */
fun tmCodeMatches(tm: TmFns, read: (addr: Long, len: Int) -> ByteArray): Boolean =
    tm.functions.zip(tm.codeCrcs).all { (fn, crc) -> java.util.zip.CRC32().apply { update(read(fn, 32)) }.value == crc }

/** Frames [tmStart]'s fade takes to reach black before [tmFinish]: FadeScreen at delay 0, with room to spare. */
const val TM_FADE_FRAMES = 30

private fun word(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }
private fun u32(b: ByteArray) = (0..3).fold(0L) { v, i -> v or ((b[i].toLong() and 0xFF) shl (8 * i)) }

/**
 * The TM screen's first step for [itemId]: the bag's checks (on the free field, the TM in the bag, a party), then what the
 * START menu leaves before a screen of its own - the controls locked, the screen fading to black. USED = [tmFinish] follows
 * once it's black. Emu thread.
 */
fun tmStart(fns: FieldItemFns, tm: TmFns, itemId: Int, busy: Boolean, g: GameCalls): ItemUseResult {
    val p = fns.party ?: return ItemUseResult.FAILED
    return runCatching {
        when {
            !tm.teaches(itemId) || !fieldItemCodeMatches(fns, g::read) || !tmCodeMatches(tm, g::read) -> ItemUseResult.FAILED
            busy || g.call(fns.fieldControlsLocked) != 0L -> ItemUseResult.NOT_NOW
            g.call(fns.checkBagHasItem, itemId, 1) != 1L -> ItemUseResult.NONE_LEFT
            (g.read(p.partyCount, 1)[0].toInt() and 0xFF) == 0 -> ItemUseResult.FAILED
            else -> {
                g.call(tm.lockControls)
                g.call(tm.fadeScreen, 1, 0)
                ItemUseResult.USED
            }
        }
    }.getOrElse { ItemUseResult.FAILED }
}

/**
 * The second step, the screen black: the game's TM party menu for [itemId], going back to the field when it closes. If it
 * can't start, the field comes back as it was (faded in, controls free). [leftField]: something else took the game off the
 * field during the fade (a script's screen, a battle) - then nothing at all, as FLY does. Emu thread.
 */
fun tmFinish(tm: TmFns, itemId: Int, g: GameCalls, leftField: Boolean = false): Boolean {
    if (leftField) return false
    val ok = runCatching {
        g.call(tm.cleanupOverworld)
        if (!g.write(tm.specialVarItemId, byteArrayOf(itemId.toByte(), (itemId shr 8).toByte())) ||
            !g.write(tm.itemUseCb, word(tm.itemUseCbTmhm or 1)) ||
            !g.write(tm.partyMenu + tm.partyMenuSlotOff, byteArrayOf(0))
        ) return@runCatching false
        g.call(tm.showPartyMenuForItemUse)
        if ((u32(g.read(tm.mainCallback2, 4)) and 0xFFFFFFFEL) == (tm.initPartyMenu and 0xFFFFFFFEL)) {
            // The party menu is on its way: back to the field when it closes, not to a bag that never opened.
            g.write(tm.partyMenu, word(tm.returnToField or 1))
        } else {
            // No room for the menu (InitPartyMenu went straight to its exit, the bag): the field instead.
            g.call(tm.setMainCallback2, (tm.returnToField or 1).toInt())
            true
        }
    }.getOrDefault(false)
    if (!ok) runCatching { g.call(tm.fadeScreen, 0, 0); g.call(tm.unlockControls) }
    return ok
}
