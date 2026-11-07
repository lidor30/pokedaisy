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
    /** The field move's badge flag (FireRed FLAG_BADGE01_GET 0x820, Emerald FLAG_BADGE02_GET 0x868); [NO_BADGE] where a hack
     * took the party menu's badge check out ([flashBadgeRule]). */
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
    /** gMapHeader: its cave byte at [caveOff], read by the quick menu to dim FLASH where it's light already. */
    val mapHeader: Long,
    /** CRC32 of each function's first 32 bytes: flagGet, setUpFieldMoveFlash, then each of [fieldCallbacks]. */
    val codeCrcs: List<Long>,
    /** The party menu's badge check for field moves (CursorCB_FieldMove's `cmp rN, #6; bhi; FLAG_BADGE01_GET + the move;
     * bl FlagGet`) and the CRC32 of its 16 retail bytes, on the code hacks are edited into (FireRed rev 0); 0 = not read. */
    val badgeCheck: Long = 0,
    val badgeCheckCrc: Long = 0,
    /** gMapHeader.cave's offset: 0x15, SoulGold 0x16 (its map sections are 16 bits). */
    val caveOff: Int = 0x15,
    /** A CFRU hack's own badge rule (`bool fn(u8 fieldMove)`, the one its party menu jumps to from [badgeCheck]), found by
     * [flashBadgeRule]; 0 = the flag check. */
    val badgeFn: Long = 0,
    /** FIELD_MOVE_FLASH: what [badgeFn] is asked about (0 in FireRed and every CFRU hack seen). */
    val fieldMove: Int = 0,
    /** MOVE_FLASH. */
    val flashMove: Int = 148,
    /** GetMonData's MON_DATA_MOVE1 / MON_DATA_IS_EGG. */
    val monMoveField: Int = 13,
    val monEggField: Int = 45,
) {
    val functions get() = listOf(flagGet, setUpFieldMoveFlash) + fieldCallbacks
}

// FLASH_FIRERED_REV1 and FLASH_EMERALD are records in gamedata/gen3/records/ (GameDataRecordsGen.kt).

/** [FlashFns.badgeFlag] where the party menu asks for no badge. */
const val NO_BADGE = -1

/** FLASH for a ROM, by what the companion detected it as: retail FireRed rev 0 / 1, LeafGreen rev 0 / 1, Emerald, Ruby and
 * Sapphire, English from the records and every other language from [FLASH_PORTS] (scripts/port_field_items.py); and the
 * hacks edited into retail's code in place ([hack] false for them: a rebuild like Recharged Emerald is true; Emerald
 * Legacy, a rebuild too, has records of its own), FireRed rev 0's (Ultra Violet's PUVV and the CFRU hacks' kinds included; [flashBadgeRule] reads each one's badge rule)
 * and Emerald's (Glazed, Kaizo, the randomized cart). The code CRCs still decide. */
fun flashFnsFor(kind: GameKind?, gameCode: String, revision: Int, hack: Boolean): FlashFns? = when {
    hack -> null
    kind in FIRERED_REV0_HACKS || (kind == GameKind.FIRERED && gameCode == "PUVV") -> FLASH_FIRERED_REV0
    kind == GameKind.GLAZED -> FLASH_EMERALD.takeIf { gameCode == "BPEE" }
    // The gcc-built Emerald hacks FLY knows (scripts/find_flash_expansion.py); SoulGold's builds: flashCandidatesFor.
    kind == GameKind.LAZARUS -> FLASH_LAZARUS.takeIf { gameCode == "BPEE" }
    kind == GameKind.IMPERIUM -> FLASH_IMPERIUM.takeIf { gameCode == "BPEE" }
    kind == GameKind.HEART_AND_SOUL -> FLASH_HEART_AND_SOUL.takeIf { gameCode == "BPEE" }
    kind == GameKind.FIRERED && gameCode == "BPRE" -> when (revision) { 0 -> FLASH_FIRERED_REV0; 1 -> FLASH_FIRERED_REV1; else -> null }
    kind == GameKind.FIRERED && gameCode == "BPGE" -> when (revision) { 0 -> FLASH_LEAFGREEN_REV0; 1 -> FLASH_LEAFGREEN_REV1; else -> null }
    kind == GameKind.EMERALD && gameCode == "BPEE" -> if (emeraldLegacy) FLASH_EMERALD_LEGACY else FLASH_EMERALD
    kind == GameKind.EMERALD && gameCode == "AXVE" -> when (revision) { 0 -> FLASH_RUBY_REV0; 1, 2 -> FLASH_RUBY; else -> null }
    kind == GameKind.EMERALD && gameCode == "AXPE" -> when (revision) { 0 -> FLASH_SAPPHIRE_REV0; 1, 2 -> FLASH_SAPPHIRE; else -> null }
    kind == GameKind.FIRERED || kind == GameKind.EMERALD -> FLASH_PORTS["$gameCode$revision"]
    else -> null
}

/** The kinds of the hacks edited into FireRed rev 0's code (the bag's tables say the same: fieldItemsForGame); FireRed's own
 * kind covers Ultra Violet, Rocket Edition, Ash Gray, Ghost Grey and Let's Go. */
val FIRERED_REV0_HACKS = setOf(
    GameKind.ORANGE_ISLANDS, GameKind.UNBOUND, GameKind.RADICAL_RED, GameKind.ODYSSEY, GameKind.GAIA, GameKind.AMETHYST,
    GameKind.SWORD_SHIELD, GameKind.GS_CHRONICLES,
)

/**
 * The party menu's badge rule in the running ROM, read at [FlashFns.badgeCheck]:
 * - retail's bytes: [flash] as it is (the badge flag);
 * - the check patched to "yes" (`movs r0, #1` and nops where the flag sum and FlagGet were: Gaia, Orange Islands, Rocket
 *   Edition): [NO_BADGE];
 * - CFRU's hook (`ldr r0, =hook; bx r0` there, the hook `adds r0, r4, #0; bl rule; cmp r0, #0; beq; ldr r0, =ok; bx r0;
 *   ldr r0, =no; bx r0`, ok / no being retail's own two ways on: Unbound, Radical Red, GS Chronicles, Sword and Shield,
 *   Amethyst): the hack's rule as [FlashFns.badgeFn], asked about FIELD_MOVE_FLASH as its party menu does;
 * - anything else: null, no FLASH.
 */
fun flashBadgeRule(flash: FlashFns, read: (addr: Long, len: Int) -> ByteArray): FlashFns? {
    if (flash.badgeCheck == 0L) return flash
    val b = read(flash.badgeCheck, 16)
    if (java.util.zip.CRC32().apply { update(b) }.value == flash.badgeCheckCrc) return flash
    val alwaysYes = byteArrayOf(0x01, 0x20) + ByteArray(8)
    if (b.copyOfRange(4, 14).contentEquals(alwaysYes)) return flash.copy(badgeFlag = NO_BADGE)
    return cfruBadgeFn(flash.badgeCheck, b, read)?.let { flash.copy(badgeFn = it) }
}

private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

/** The rule CFRU's hook at [check] calls ([b]: the 16 bytes there), or null if it isn't that hook. */
private fun cfruBadgeFn(check: Long, b: ByteArray, read: (addr: Long, len: Int) -> ByteArray): Long? {
    if (u16(b, 0) != 0x4800 || u16(b, 2) != 0x4700) return null             // ldr r0, [pc, #0]; bx r0
    val hook = u32(b.copyOfRange(4, 8)) and 0xFFFFFFFEL
    if (hook !in 0x08000000L until 0x0A000000L) return null
    val h = read(hook, 18)
    if (u16(h, 0) != 0x1C20 || u16(h, 6) != 0x2800 || u16(h, 8) and 0xFF00 != 0xD000) return null   // adds r0, r4, #0; ...; cmp r0, #0; beq
    val (hi, lo) = u16(h, 2) to u16(h, 4)
    if (hi and 0xF800 != 0xF000 || lo and 0xF800 != 0xF800) return null      // bl
    val off = (((hi and 0x7FF) shl 12) or ((lo and 0x7FF) shl 1)).let { if (it and 0x400000 != 0) it - 0x800000 else it }
    val rule = hook + 2 + 4 + off
    // ldr r0, =ok; bx r0; ldr r0, =no; bx r0: retail's own ways on past the check (FlagGet TRUE / the badge message).
    fun target(at: Int): Long? {
        val insn = u16(h, at)
        if (insn and 0xFF00 != 0x4800 || u16(h, at + 2) != 0x4700) return null
        val pool = ((hook + at + 4) and 0xFFFFFFFCL) + (insn and 0xFF) * 4
        return u32(read(pool, 4)) and 0xFFFFFFFEL
    }
    return rule.takeIf { target(10) == check + 0x3C && target(14) == check + 0x16 && rule in 0x08000000L until 0x0A000000L }
}

/** Every table that may fit: [flashFnsFor]'s, or for games whose builds share a kind and game code all of their builds'
 * (Recharged Emerald's three, [rebuild]; SoulGold's five) - the code CRCs pick. */
fun flashCandidatesFor(kind: GameKind?, gameCode: String, revision: Int, rebuild: Boolean): List<FlashFns> = when {
    rebuild -> if (kind == GameKind.EMERALD && gameCode == "BPEE")
        listOf(FLASH_RECHARGED_EMERALD_V2_2_5, FLASH_RECHARGED_EMERALD_V2_2_8, FLASH_RECHARGED_EMERALD_REBALANCED_V2_2_8) else emptyList()
    kind == GameKind.SOULGOLD -> if (gameCode == "BPEE") listOf(
        FLASH_SOULGOLD, FLASH_SOULGOLD_V1_2, FLASH_SOULGOLD_V1_2B, FLASH_SOULGOLD_V1_2C, FLASH_SOULGOLD_V1_2_1,
    ) else emptyList()
    else -> listOfNotNull(flashFnsFor(kind, gameCode, revision, hack = false))
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
        val badge = when {
            flash.badgeFn != 0L -> g.call(flash.badgeFn, flash.fieldMove) != 0L
            flash.badgeFlag == NO_BADGE -> true
            else -> g.call(flash.flagGet, flash.badgeFlag) == 1L
        }
        if (!badge) return FlashResult.NO_BADGE
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
    val cave = runCatching { read(flash.mapHeader + flash.caveOff, 1).getOrNull(0)?.toInt() }.getOrNull() ?: return null
    val lit = flag(flash.litFlag) ?: return null
    return cave == 1 && !lit
}
