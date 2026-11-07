package com.pokedaisy.app.companion.data

/**
 * FLASH from the QUICK MENU, the way the party menu's field move does it (Fly.kt's idea: the game's own functions,
 * called between frames). party_menu.c's CursorCb_FieldMove: FIELD_MOVE_FLASH needs its badge (FLAG_BADGE01_GET +
 * the field move's index: FireRed's Boulder Badge, Emerald's Knuckle Badge), then SetUpFieldMove_Flash says whether it
 * works here - a cave (gMapHeader.cave) not lit yet (FireRed's FLAG_SYS_FLASH_ACTIVE, Emerald's FLAG_SYS_USE_FLASH);
 * in Emerald also the Ancient Tomb's sealed wall, whose braille asks for FLASH (ShouldDoBrailleRegisteelEffect). It
 * answers by setting gFieldCallback2 = FieldCallback_PrepareFadeInFromMenu and gPostMenuFieldCallback = what runs once
 * the field has faded back in from the party menu: FieldCallback_Flash (the cut-in of the Pokémon under the party cursor,
 * GetCursorSelectionMonId = gPartyMenu.slotId, then FldEff_UseFlash: the flag, the sound, the light opening up), or
 * Emerald's SetUpPuzzleEffectRegisteel.
 *
 * There's no menu to fade back from here, so the companion takes the answer and runs it on the field as it is: the
 * cursor on the Pokémon that knows FLASH, SetUpFieldMove_Flash, then both callbacks put back as they were and the post-menu
 * one called straight away - only if it's one of [fieldCallbacks] (never a pointer the companion doesn't know).
 */
data class FlashFns(
    val flagGet: Long,
    /** The field move's badge flag (FireRed FLAG_BADGE01_GET 0x820, Emerald FLAG_BADGE02_GET 0x868). */
    val badgeFlag: Int,
    /** FLAG_SYS_FLASH_ACTIVE / FLAG_SYS_USE_FLASH: set once the cave is lit (also by the game on map load). */
    val litFlag: Int,
    /** SetUpFieldMove_Flash: TRUE where FLASH does something here, the callbacks set. */
    val setUpFieldMoveFlash: Long,
    /** The field callback the party menu sets for its way back (FireRed / Emerald gFieldCallback2, Ruby / Sapphire
     * gFieldCallback) and gPostMenuFieldCallback: pointers, both put back after the check. */
    val menuFieldCallback: Long,
    val postMenuFieldCallback: Long,
    /** The post-menu callbacks SetUpFieldMove_Flash may set: FieldCallback_Flash (+ Emerald's SetUpPuzzleEffectRegisteel). */
    val fieldCallbacks: List<Long>,
    /** gPartyMenu.slotId: the Pokémon the cut-in shows. */
    val partyMenuSlotId: Long,
    /** gMapHeader: its cave byte at +0x15, read by the quick menu to dim FLASH where it's light already. */
    val mapHeader: Long,
    /** CRC32 of each function's first 32 bytes: flagGet, setUpFieldMoveFlash, then each of [fieldCallbacks]. */
    val codeCrcs: List<Long>,
    /** MOVE_FLASH. */
    val flashMove: Int = 148,
    /** GetMonData's MON_DATA_MOVE1 / MON_DATA_IS_EGG. */
    val monMoveField: Int = 13,
    val monEggField: Int = 45,
) {
    val functions get() = listOf(flagGet, setUpFieldMoveFlash) + fieldCallbacks
}

// FLASH_FIRERED_REV1 and FLASH_EMERALD are records in gamedata/gen3/records/ (GameDataRecordsGen.kt).

/** FLASH for a ROM, by what the companion detected it as: retail FireRed rev 0 / 1, LeafGreen rev 0 / 1, Emerald, Ruby and
 * Sapphire, English from the records and every other language from [FLASH_PORTS] (scripts/port_field_items.py). */
fun flashFnsFor(kind: GameKind?, gameCode: String, revision: Int, hack: Boolean): FlashFns? = when {
    hack -> null
    kind == GameKind.FIRERED && gameCode == "BPRE" -> when (revision) { 0 -> FLASH_FIRERED_REV0; 1 -> FLASH_FIRERED_REV1; else -> null }
    kind == GameKind.FIRERED && gameCode == "BPGE" -> when (revision) { 0 -> FLASH_LEAFGREEN_REV0; 1 -> FLASH_LEAFGREEN_REV1; else -> null }
    kind == GameKind.EMERALD && gameCode == "BPEE" -> FLASH_EMERALD
    kind == GameKind.EMERALD && gameCode == "AXVE" -> when (revision) { 0 -> FLASH_RUBY_REV0; 1, 2 -> FLASH_RUBY; else -> null }
    kind == GameKind.EMERALD && gameCode == "AXPE" -> when (revision) { 0 -> FLASH_SAPPHIRE_REV0; 1, 2 -> FLASH_SAPPHIRE; else -> null }
    kind == GameKind.FIRERED || kind == GameKind.EMERALD -> FLASH_PORTS["$gameCode$revision"]
    else -> null
}

/** Whether the running ROM has [flash]'s functions where they're expected. */
fun flashCodeMatches(flash: FlashFns, read: (addr: Long, len: Int) -> ByteArray): Boolean =
    flash.functions.zip(flash.codeCrcs).all { (fn, crc) -> java.util.zip.CRC32().apply { update(read(fn, 32)) }.value == crc }

enum class FlashResult {
    /** The game's FLASH is on its way (the cut-in, then the light). */
    OK,
    NOT_NOW, IN_BATTLE, NO_BADGE,
    /** Nobody in the party knows FLASH (any more). */
    NO_FLASHER,
    /** Not a dark place (or not the tomb's wall): the game's "Can't use that here." */
    CANT_USE_HERE,
    /** This cave is lit already. */
    ALREADY_LIT,
    FAILED,
}

/** The first party Pokémon that knows FLASH (not an egg): its slot, or null. */
private fun flasher(p: PartyItemFns, flash: FlashFns, g: GameCalls): Int? {
    val count = (g.read(p.partyCount, 1)[0].toInt() and 0xFF).coerceAtMost(6)
    return (0 until count).firstOrNull { slot ->
        val mon = (p.party + slot * p.monStride).toInt()
        g.call(p.getMonData, mon, flash.monEggField) == 0L &&
            (0 until 4).any { g.call(p.getMonData, mon, flash.monMoveField + it) == flash.flashMove.toLong() }
    }
}

private fun u32(b: ByteArray) = (0..3).fold(0L) { v, i -> v or ((b[i].toLong() and 0xFF) shl (8 * i)) }

/**
 * FLASH, the party menu's checks in its order and then the field move (see [FlashFns]). [busy]: a battle or a menu
 * screen is up. Emu thread.
 */
fun useFlash(fns: FieldItemFns, flash: FlashFns, busy: Boolean, g: GameCalls): FlashResult {
    val p = fns.party ?: return FlashResult.FAILED
    return runCatching {
        when {
            !fieldItemCodeMatches(fns, g::read) || !flashCodeMatches(flash, g::read) -> return FlashResult.FAILED
            busy || g.call(fns.fieldControlsLocked) != 0L -> return FlashResult.NOT_NOW
        }
        val slot = flasher(p, flash, g) ?: return FlashResult.NO_FLASHER
        if (g.call(flash.flagGet, flash.badgeFlag) != 1L) return FlashResult.NO_BADGE
        val oldCb2 = g.read(flash.menuFieldCallback, 4)
        val oldPost = g.read(flash.postMenuFieldCallback, 4)
        val oldSlot = g.read(flash.partyMenuSlotId, 1)
        if (!g.write(flash.partyMenuSlotId, byteArrayOf(slot.toByte()))) return FlashResult.FAILED
        val ok = g.call(flash.setUpFieldMoveFlash)
        val post = u32(g.read(flash.postMenuFieldCallback, 4))
        // Nothing of the party menu's stays behind: its callbacks are for a menu that never opened.
        g.write(flash.menuFieldCallback, oldCb2)
        g.write(flash.postMenuFieldCallback, oldPost)
        if (ok != 1L) {
            g.write(flash.partyMenuSlotId, oldSlot)
            return if (g.call(flash.flagGet, flash.litFlag) == 1L) FlashResult.ALREADY_LIT else FlashResult.CANT_USE_HERE
        }
        // The cut-in reads the slot a few frames on (GetCursorSelectionMonId): it stays the flasher's.
        if ((post and 0xFFFFFFFEL) !in flash.fieldCallbacks.map { it and 0xFFFFFFFEL }) return FlashResult.FAILED
        if (g.call(post) < 0) FlashResult.FAILED else FlashResult.OK
    }.getOrElse { FlashResult.FAILED }
}

/** Whether FLASH would light this place up, from RAM alone (the quick menu's dimming): a cave, the light not on.
 * [flag] reads a save flag (the snapshot's). Null = can't tell. */
fun flashDark(flash: FlashFns, read: (addr: Long, len: Int) -> ByteArray, flag: (Int) -> Boolean?): Boolean? {
    val cave = runCatching { read(flash.mapHeader + 0x15, 1).getOrNull(0)?.toInt() }.getOrNull() ?: return null
    val lit = flag(flash.litFlag) ?: return null
    return cave == 1 && !lit
}
