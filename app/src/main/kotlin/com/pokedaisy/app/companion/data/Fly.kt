package com.pokedaisy.app.companion.data

/**
 * FLY from the MAP tab, the way the game does it (FieldItems.kt's idea: the game's own functions, called between
 * frames). pokefirered's party menu: FIELD_MOVE_FLY needs FLAG_BADGE01_GET + its index (the Thunder Badge) and
 * SetUpFieldMove_Fly (the map type allows flying), then the Fly map - a town whose GetMapsecType is VISITED (its
 * FLAG_WORLD_MAP_* set) - and its choice: SetFlyWarpDestination(mapsec), which sets the warp and hands the game
 * CB2_ReturnToField with FieldCallback_UseFly. The game then plays its own Fly: the cut-in with the Pokémon under the
 * party cursor (gPartyMenu.slotId), the flight out, the warp and the landing.
 *
 * The companion does the menus' part: LockPlayerFieldControls and FadeScreen(FADE_TO_BLACK) - what closing the
 * Fly map left - then, once the screen is black, the cursor on the Pokémon that knows Fly and SetFlyWarpDestination.
 * Checked headless on the user's FireRed save: the cut-in, the flight and the landing outside the chosen town's
 * Pokémon Center, controls free after.
 */
data class FlyFns(
    val flagGet: Long,
    /** FLAG_BADGE03_GET (FireRed / LeafGreen: the Thunder Badge). */
    val badgeFlag: Int,
    /** SetUpFieldMove_Fly: TRUE where the map type allows flying (outdoors). With [mapHeader], the check it makes:
     * Overworld_MapTypeAllowsTeleportAndFly(gMapHeader.mapType) (Ruby / Sapphire's sets the party menu's callbacks too). */
    val setUpFieldMoveFly: Long,
    /** GetMapsecType(mapsec): [visited] for a town the player has been to. */
    val getMapsecType: Long,
    /** FadeScreen(mode, delay): FADE_TO_BLACK is 1. */
    val fadeScreen: Long,
    val lockControls: Long,
    /** UnlockPlayerFieldControls: only to hand the field back if the second step can't go on. */
    val unlockControls: Long,
    /** SetFlyWarpDestination(mapsec): the Fly map's choice. */
    val setFlyWarpDestination: Long,
    /** gPartyMenu.slotId: the Pokémon the Fly cut-in shows (GetCursorSelectionMonId). */
    val partyMenuSlotId: Long,
    /** sMapFlyDestinations: one {map group, map num, heal location} per map section from [firstMapsec]. */
    val destinations: Long,
    val firstMapsec: Int,
    val count: Int,
    /** CRC32 of each function's first 32 bytes: flagGet, setUpFieldMoveFly, getMapsecType, fadeScreen,
     * lockControls, unlockControls, setFlyWarpDestination. */
    val codeCrcs: List<Long>,
    /** The GetMapsecType answers the Fly map takes (FireRed: VISITED; Emerald: CITY_CANFLY, BATTLE_FRONTIER). */
    val flyTypes: Set<Int> = setOf(2),
    /** Map sections past [destinations]' table that can still be flown to (Emerald's Battle Frontier). */
    val extraMapsecs: List<Int> = emptyList(),
    /** Emerald's way of ending the Fly map, in place of [setFlyWarpDestination]. */
    val healWarp: HealWarp? = null,
    val flyMove: Int = 19,
    /** Another CRC32 [getMapsecType] may have: the FireRed hacks' 16-bit map section patch ([GET_MAPSEC_TYPE_U16]). */
    val getMapsecTypeAltCrc: Long? = null,
    /** GetMonData's MON_DATA_MOVE1 / MON_DATA_IS_EGG (the expansion renumbered them: 20 / 54). */
    val monMoveField: Int = 13,
    val monEggField: Int = 45,
    /** gMapHeader, when [setUpFieldMoveFly] takes its mapType (+0x17); 0 = it takes nothing. */
    val mapHeader: Long = 0,
    /** The game's own list of fly spots, when it has one in place of [destinations]' per-section table (Unbound). */
    val spots: FlySpots? = null,
    /** The Fly map's state the warp reads, pinned for the call (Unbound). */
    val regionMap: RegionMapPin? = null,
) {
    val functions get() = listOf(flagGet, setUpFieldMoveFly, getMapsecType, fadeScreen, lockControls, unlockControls) +
        (healWarp?.let { listOf(it.toHealLocation, it.toMapWarp, it.returnToField) } ?: listOf(setFlyWarpDestination))
}

/** A list of [count] fly spots of [stride] bytes from [addr], each starting with its map section. */
data class FlySpots(val addr: Long, val count: Int, val stride: Int)

/**
 * FireRed's sRegionMap (a pointer, set only while a region map screen is up) for a warp that reads the selected region
 * from it - byte [regionOffset] of the struct (GetSelectedRegionMap). For the call the pointer is aimed so that byte is
 * [scratch], holding 0 (the first region), then put back.
 */
data class RegionMapPin(val pointer: Long, val regionOffset: Int, val scratch: Long)

/**
 * Emerald's CB_ExitFlyMap: SetWarpDestinationToHealLocation(the section's heal location, [special] ones first;
 * Littleroot by the player's gender), else SetWarpDestinationToMapWarp(group, num, WARP_ID_NONE) - then
 * ReturnToFieldFromFlyMapSelect (CB2_ReturnToField + FieldCallback_UseFly).
 */
data class HealWarp(
    val toHealLocation: Long,
    val toMapWarp: Long,
    val returnToField: Long,
    /** gSaveBlock2Ptr; playerGender at +8. With [saveBlock2Static], gSaveBlock2 itself (Ruby / Sapphire's). */
    val saveBlock2Ptr: Long,
    val special: Map<Int, Int>,
    val genderMapsec: Int,
    val maleHeal: Int,
    val femaleHeal: Int,
    val saveBlock2Static: Boolean = false,
)

// FLY_FIRERED_REV1, FLY_LEAFGREEN_REV0 / _REV1 and FLY_EMERALD (+ HEAL_WARP_EMERALD) are records in
// gamedata/gen3/records/ (GameDataRecordsGen.kt), shared with the 3DS / Linux companion.

/**
 * Emerald Legacy v1.1.4: retail Emerald's FLY at its own build's addresses (its ELF), FLAG_BADGE06_GET 0x60 later (its
 * 960 trainer flags push the system flags). Its SetUpFieldMove_Fly, SetWarpDestinationToHealLocation and
 * ReturnToFieldFromFlyMapSelect are retail's source (only their calls / pools moved); sMapHealLocations and the heal
 * location ids are retail's.
 */
val FLY_EMERALD_LEGACY = FLY_EMERALD.copy(
    flagGet = 0x0809EBFCL, badgeFlag = 0x8CC, setUpFieldMoveFly = 0x081BA1C8L, getMapsecType = 0x081275ECL,
    fadeScreen = 0x080AD118L, lockControls = 0x0809A1F0L, unlockControls = 0x0809A1FCL,
    partyMenuSlotId = 0x0203D1F9L, destinations = 0x085D46F0L,
    codeCrcs = listOf(0xBCF97BF3L, 0x26C08427L, 0x4B625BC4L, 0xF9959006L, 0xB9FB85A7L, 0xB037C8AFL, 0xE35529C1L, 0x2F1D18CAL, 0x404426C9L),
    healWarp = FLY_EMERALD.healWarp!!.copy(
        toHealLocation = 0x08085D74L, toMapWarp = 0x08085CD0L, returnToField = 0x080B850CL, saveBlock2Ptr = 0x03005F70L,
    ),
)

/**
 * GetMapsecType as the FireRed hacks that widened map sections to 16 bits have it (Amethyst, Sword and Shield, Gaia,
 * Odyssey, Orange Islands: the same bytes in each, `lsls r0, #16` where retail takes a u8) - still the game's own
 * function, in place. Flown headless on each with FireRed rev 0's table. Unbound's jumps into its own code instead (and
 * flies to its own towns): not taken.
 */
const val GET_MAPSEC_TYPE_U16 = 0x7AFF168AL

/**
 * Retail Ruby rev 1 / rev 2 (pret's pokeruby built clean as ruby_rev1, byte for byte retail): Emerald's way - the heal
 * location (sub_8053538) or map warp (warp1_set_2), then sub_80865BC (CB2_ReturnToField + the Fly landing) - with its
 * own Fly map type per section (static sub_80FB758, 0x080FB778: VISITED towns 2, the Battle Tower 4), the cut-in's
 * Pokémon in gLastFieldPokeMenuOpened and gSaveBlock2 in place. FLAG_BADGE06_GET, its sMapHealLocations as Emerald's.
 */
val FLY_RUBY = FlyFns(
    flagGet = 0x08069360L, badgeFlag = 0x80C, setUpFieldMoveFly = 0x08054244L, getMapsecType = 0x080FB778L,
    fadeScreen = 0x0807D664L, lockControls = 0x08065550L, unlockControls = 0x0806555CL, setFlyWarpDestination = 0,
    partyMenuSlotId = 0x03005CE0L, destinations = 0x083E793CL, firstMapsec = 0, count = 50,
    codeCrcs = listOf(0xAC4D17B2L, 0x6F375BAAL, 0x0190971BL, 0xF9959006L, 0xA003C21AL, 0x9807B468L, 0x5BF43808L, 0x2F1D18CAL, 0xB2A1E0F7L),
    flyTypes = setOf(2, 4), extraMapsecs = listOf(58), mapHeader = 0x0202E828L,
    healWarp = HealWarp(
        toHealLocation = 0x08053558L, toMapWarp = 0x080534B0L, returnToField = 0x080865DCL, saveBlock2Ptr = 0x02024EA4L,
        // MAPSEC_EVER_GRANDE_CITY, MAPSEC_BATTLE_TOWER (HEAL_LOCATION_EVER_GRANDE_CITY, _BATTLE_TOWER_OUTSIDE)
        special = mapOf(15 to 11, 58 to 21), genderMapsec = 0, maleHeal = 12, femaleHeal = 13, saveBlock2Static = true,
    ),
)

/** Retail Sapphire rev 1 / rev 2: Ruby's code a few bytes on (mapped from Ruby's by scripts/port_field_items.py). */
val FLY_SAPPHIRE = FLY_RUBY.copy(
    flagGet = 0x08069364L, setUpFieldMoveFly = 0x08054248L, lockControls = 0x08065554L, unlockControls = 0x08065560L,
    destinations = 0x083E7998L,
    codeCrcs = listOf(0xAC4D17B2L, 0x6F375BAAL, 0x0190971BL, 0xF9959006L, 0xA003C21AL, 0x9807B468L, 0x5BF43808L, 0x2F1D18CAL, 0xB7ABAD76L),
)

/**
 * Unbound v2.1.1.1 (English and French): CFRU sends GetMapsecType and SetFlyWarpDestination to its own code (a jump
 * at FireRed rev 0's entry - the CRCs are those stubs, which carry the target). Its GetMapsecType reads a visited
 * flag per section (from 0x57), and its SetFlyWarpDestination looks the section up in the selected region's list of
 * fly spots (10 bytes: section, map, x, y; 27 of them for Borrius at 0x09FE6694) - the region read from sRegionMap,
 * NULL on the field, so the call pins it ([RegionMapPin]). Flown headless to Frozen Heights; without the pin the
 * region read garbage and the warp landed in an indoor battle room. FLY's badge is FireRed's (0x822), not checked
 * against Unbound's own party menu.
 */
fun flyUnbound(rev0: FlyFns) = rev0.copy(
    codeCrcs = listOf(0x07BEBC3AL, 0x950F2C97L, 0x31F88DE4L, 0xF9959006L, 0x0111A86EL, 0xBAB654BAL, 0xE6E6C0C1L),
    spots = FlySpots(0x09FE6694L, 27, 10),
    regionMap = RegionMapPin(pointer = 0x020399D4L, regionOffset = 0x479B, scratch = 0x0203FFF0L),
)

/** The map sections FLY may offer: [FlyFns.spots]' sections, else the per-section table's filled entries. */
private fun flySections(fly: FlyFns, g: GameCalls): List<Int> {
    fly.spots?.let { s ->
        val t = g.read(s.addr, s.count * s.stride)
        return (0 until s.count).map { t[it * s.stride].toInt() and 0xFF }.distinct()
    }
    val table = g.read(fly.destinations, fly.count * 3)
    return (0 until fly.count).filter { i ->
        (table[i * 3].toInt() or table[i * 3 + 1].toInt() or table[i * 3 + 2].toInt()) != 0
    }.map { fly.firstMapsec + it } + fly.extraMapsecs
}

/** The map sections FLY could ever offer (visited or not): FLIGHT AREA's towns ([FlightArea]). */
fun flyTownSections(fly: FlyFns, g: GameCalls): List<Int> = flySections(fly, g)

/** The towns the game's own Fly map would list now ([FlyFns.flyTypes]), with no party or badge gate: FLIGHT AREA's start. */
fun flyVisitedTowns(fly: FlyFns, g: GameCalls): List<Int> =
    flySections(fly, g).filter { g.call(fly.getMapsecType, it).toInt() in fly.flyTypes }

/** Whether the running ROM has [fly]'s functions where they're expected. */
fun flyCodeMatches(fly: FlyFns, read: (addr: Long, len: Int) -> ByteArray): Boolean =
    fly.functions.zip(fly.codeCrcs).all { (fn, crc) ->
        val got = java.util.zip.CRC32().apply { update(read(fn, 32)) }.value
        got == crc || fn == fly.getMapsecType && got == fly.getMapsecTypeAltCrc
    }

enum class FlyResult {
    /** Ready ([FlyTargets.destinations]) / on its way. */
    OK,
    NOT_NOW, NO_BADGE,
    /** NOT_NOW in a battle (the app's answer, like [ItemUseResult.IN_BATTLE]). */
    IN_BATTLE,
    /** Not from this map (indoors, a cave). */
    CANT_FLY_HERE,
    /** Someone walks with the player (Platinum's partner flag): the game itself won't fly then. */
    PARTNER,
    /** Nobody in the party knows Fly (any more). */
    NO_FLYER,
    /** No way over the map to the town, in a game without the classic FLY ([FieldItemFns.classicFly]). */
    NO_ROUTE,
    FAILED,
}

/** Where FLY can go now: the visited towns' map sections, and the Pokémon that would carry the player. */
data class FlyTargets(
    val result: FlyResult,
    val destinations: List<Int> = emptyList(),
    val flyerSlot: Int = -1,
    val flyerPersonality: Long = 0,
)

private const val GET_MON_PERSONALITY = 0

/** The first party Pokémon that knows Fly (not an egg): its slot and personality, or null. */
private fun flyer(fns: FieldItemFns, fly: FlyFns, g: GameCalls): Pair<Int, Long>? {
    val p = fns.party ?: return null
    val count = (g.read(p.partyCount, 1)[0].toInt() and 0xFF).coerceAtMost(6)
    for (slot in 0 until count) {
        val mon = (p.party + slot * p.monStride).toInt()
        if (g.call(p.getMonData, mon, fly.monEggField) != 0L) continue
        if ((0 until 4).any { g.call(p.getMonData, mon, fly.monMoveField + it) == fly.flyMove.toLong() }) {
            return slot to g.call(p.getMonData, mon, GET_MON_PERSONALITY)
        }
    }
    return null
}

/** Why FLY can't start now, or null: the party menu's own checks, in its order. */
private fun flyGate(fns: FieldItemFns, fly: FlyFns, busy: Boolean, g: GameCalls): FlyResult? = when {
    !fieldItemCodeMatches(fns, g::read) || !flyCodeMatches(fly, g::read) -> FlyResult.FAILED
    busy || g.call(fns.fieldControlsLocked) != 0L -> FlyResult.NOT_NOW
    g.call(fly.flagGet, fly.badgeFlag) != 1L -> FlyResult.NO_BADGE
    mayFlyHere(fly, g) != 1L -> FlyResult.CANT_FLY_HERE
    else -> null
}

private fun mayFlyHere(fly: FlyFns, g: GameCalls): Long =
    if (fly.mapHeader != 0L) g.call(fly.setUpFieldMoveFly, g.read(fly.mapHeader + 0x17, 1)[0].toInt() and 0xFF)
    else g.call(fly.setUpFieldMoveFly)

/** FREE FLIGHT's checks (Flight.kt): FLY's own - the field free, the badge, a map FLY may leave from - and a Pokémon
 * that knows Fly. OK, or why not. Emu thread. */
fun flightCheck(fns: FieldItemFns, busy: Boolean, g: GameCalls): FlyResult {
    val fly = fns.fly ?: return FlyResult.FAILED
    return runCatching {
        flyGate(fns, fly, busy, g)?.let { return it }
        if (flyer(fns, fly, g) == null) FlyResult.NO_FLYER else FlyResult.OK
    }.getOrElse { FlyResult.FAILED }
}

/** FLY's destinations right now (the Fly map's visited towns) and its Pokémon. Emu thread. */
fun flyTargets(fns: FieldItemFns, busy: Boolean, g: GameCalls): FlyTargets {
    val fly = fns.fly ?: return FlyTargets(FlyResult.FAILED)
    return runCatching {
        flyGate(fns, fly, busy, g)?.let { return FlyTargets(it) }
        val (slot, personality) = flyer(fns, fly, g) ?: return FlyTargets(FlyResult.NO_FLYER)
        val towns = flySections(fly, g).filter { g.call(fly.getMapsecType, it).toInt() in fly.flyTypes }
        FlyTargets(FlyResult.OK, towns, slot, personality)
    }.getOrElse { FlyTargets(FlyResult.FAILED) }
}

/**
 * FLY's first step, to [mapsec]: the checks again (the destination still visited, the Pokémon still there), then
 * what closing the Fly map leaves - controls locked, the screen fading to black. [flyFinish] follows once it's
 * black. Emu thread.
 */
fun flyStart(fns: FieldItemFns, mapsec: Int, target: FlyTargets, busy: Boolean, g: GameCalls): FlyResult {
    val fly = fns.fly ?: return FlyResult.FAILED
    return runCatching {
        flyGate(fns, fly, busy, g)?.let { return it }
        val known = if (fly.spots != null) mapsec in flySections(fly, g)
        else mapsec - fly.firstMapsec in 0 until fly.count || mapsec in fly.extraMapsecs
        if (!known || g.call(fly.getMapsecType, mapsec).toInt() !in fly.flyTypes) return FlyResult.FAILED
        val (slot, personality) = flyer(fns, fly, g) ?: return FlyResult.NO_FLYER
        if (slot != target.flyerSlot || personality != target.flyerPersonality) return FlyResult.NO_FLYER
        g.call(fly.lockControls)
        g.call(fly.fadeScreen, 1, 0)
        FlyResult.OK
    }.getOrElse { FlyResult.FAILED }
}

/** FLY's second step: the cut-in's Pokémon under the party cursor, then the Fly map's choice; the game flies.
 * If that can't happen, the field comes back as it was (faded in, controls free). [leftField]: the game's main
 * callback isn't the field's any more (a menu, a script's screen, a battle, a map load started during the fade) -
 * then nothing at all: SetFlyWarpDestination hands the game CB2_ReturnToField, and on top of another screen or a map
 * load that crashed FireRed (2026-10-20: a black screen, its save blocks zeroed, after a quick-menu FLY on the Thor).
 * Whatever took the game off the field brings it back, faded in. */
fun flyFinish(fns: FieldItemFns, mapsec: Int, target: FlyTargets, g: GameCalls, leftField: Boolean = false): Boolean {
    val fly = fns.fly ?: return false
    if (leftField) return false
    val ok = runCatching {
        g.write(fly.partyMenuSlotId, byteArrayOf(target.flyerSlot.toByte())) && warp(fly, mapsec, g)
    }.getOrDefault(false)
    if (!ok) runCatching { g.call(fly.fadeScreen, 0, 0); g.call(fly.unlockControls) }
    return ok
}

private fun warp(fly: FlyFns, mapsec: Int, g: GameCalls): Boolean {
    val h = fly.healWarp ?: return pinned(fly.regionMap, g) { g.call(fly.setFlyWarpDestination, mapsec) >= 0 }
    fun u32(a: Long) = g.read(a, 4).let { b -> (0..3).fold(0L) { v, i -> v or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
    val i = mapsec - fly.firstMapsec
    val entry = if (i in 0 until fly.count) g.read(fly.destinations + i * 3, 3).map { it.toInt() and 0xFF } else listOf(0, 0, 0)
    val heal = h.special[mapsec] ?: if (mapsec == h.genderMapsec) {
        val block = if (h.saveBlock2Static) h.saveBlock2Ptr else u32(h.saveBlock2Ptr)
        if (g.read(block + 8, 1)[0].toInt() == 0) h.maleHeal else h.femaleHeal
    } else entry[2]
    val ok = if (heal != 0) g.call(h.toHealLocation, heal) >= 0
    else entry[0] or entry[1] != 0 && g.call(h.toMapWarp, entry[0], entry[1], -1) >= 0
    return ok && g.call(h.returnToField) >= 0
}

/** [block] with [pin]'s pointer aimed at its zero byte, then put back as it was. */
private fun <T> pinned(pin: RegionMapPin?, g: GameCalls, block: () -> T): T {
    if (pin == null) return block()
    fun word(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }
    val old = g.read(pin.pointer, 4)
    g.write(pin.scratch, byteArrayOf(0))
    g.write(pin.pointer, word(pin.scratch - pin.regionOffset))
    try {
        return block()
    } finally {
        g.write(pin.pointer, old)
    }
}

/** Frames [flyStart]'s fade takes to reach black before [flyFinish]: FadeScreen at delay 0, with room to spare. */
const val FLY_FADE_FRAMES = 30
