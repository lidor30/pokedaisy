package com.pokedaisy.app.companion.data

/**
 * PC anywhere (GitHub #85): the game's own PC from the BAG tab. On the retail games its whole menu ([PcMenuScript]: the
 * storage, the player's own PC with its items, and the rest); elsewhere Bill's / Someone's alone (WITHDRAW, DEPOSIT, MOVE
 * POKéMON, MOVE ITEMS), the way a PC's script opens it - a five-command script run through the game's own script engine:
 * `lockall; special ShowPokemonStorageSystemPC; waitstate; releaseall; end`. The special hands the script context
 * back once the player leaves the PC, so the field comes back as a real PC leaves it. FieldItems.kt's idea: the
 * game's code, called between frames, only where it's found where these tables say.
 *
 * The script sits in [script] (EWRAM no build symbol reaches, past FieldItems' scratch word) while the PC is open.
 * Checked headless on FireRed rev 1 and Emerald: the PC's menu over the field, its boxes, and the field's controls
 * free again after backing out. The other languages, FireRed rev 0 and Ruby / Sapphire are mapped by
 * scripts/port_field_items.py (each entry checked against its gSpecials).
 */
data class PcFns(
    /** ScriptContext_SetupScript(script). */
    val setupScript: Long,
    /** ShowPokemonStorageSystemPC's index in gSpecials (FireRed / LeafGreen 60, Emerald 63). */
    val special: Int,
    /** gSpecials: [special]'s entry must be [pcFn] (thumb bit set). */
    val specials: Long,
    val pcFn: Long,
    /** CRC32 of [setupScript]'s and [pcFn]'s first 32 bytes. */
    val codeCrcs: List<Long>,
    val script: Long = 0x0203FFF4L,
)

// PC_FIRERED_REV1 / _LEAFGREEN_REV0 / _REV1 / _EMERALD are records in gamedata/gen3/records/ (GameDataRecordsGen.kt).

/** Ruby / Sapphire rev 1 and 2 (the same in both revisions): ShowPokemonStorageSystem, special 60, the same five
 * commands (pokeruby's opcodes are Emerald's). The other languages' come with FieldItemsPortsGen.kt. */
val PC_RUBY = PcFns(0x080655D8L, 60, 0x0814B1B4L, 0x08096121L, listOf(0xBC36478CL, 0x62C153A4L))
val PC_SAPPHIRE = PcFns(0x080655DCL, 60, 0x0814B1B4L, 0x08096121L, listOf(0xBC36478CL, 0xA79046B8L))

/** Emerald Legacy v1.1.4 (its own build's ELF): ShowPokemonStorageSystemPC is still special 63 in its gSpecials. */
val PC_EMERALD_LEGACY = PcFns(0x0809A294L, 63, 0x081E1168L, 0x080CACA1L, listOf(0xEF3698BAL, 0x3BE7A304L))
/** Whether the running ROM has [pc]'s code and its specials entry where expected. */
fun pcCodeMatches(pc: PcFns, read: (addr: Long, len: Int) -> ByteArray): Boolean {
    fun crc(a: Long) = java.util.zip.CRC32().apply { update(read(a and 1L.inv(), 32)) }.value
    val entry = read(pc.specials + 4L * pc.special, 4).let { b -> (0..3).fold(0L) { v, i -> v or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
    return entry == pc.pcFn && listOf(crc(pc.setupScript), crc(pc.pcFn)) == pc.codeCrcs
}

/** The retail games' code and RAM: a hack may use the EWRAM the menu's copy goes in. */
private val pcMenuRamFree: Boolean get() =
    (activeGame == GameKind.FIRERED || activeGame == GameKind.EMERALD) && runningFireRedHack == null &&
        runningEmeraldHack == null && !emeraldLegacy

/** The menu's script if it can go in now: empty RAM, or its own copy from the last time. */
private fun pcMenuScript(pc: PcFns, g: GameCalls): ByteArray? {
    if (!pcMenuRamFree) return null
    val menu = PcMenuScript.forGame(pc, g::read) ?: return null
    val there = g.read(PcMenuScript.RAM, menu.size)
    return menu.takeIf { there.contentEquals(menu) || there.all { it.toInt() == 0 } }
}

/** Opens the PC now, on the player's free field; NOT_NOW in a menu, a battle or a script. Emu thread. */
fun openPc(fns: FieldItemFns, busy: Boolean, g: GameCalls): ItemUseResult {
    val pc = fns.pc ?: return ItemUseResult.FAILED
    return runCatching {
        if (!fieldItemCodeMatches(fns, g::read) || !pcCodeMatches(pc, g::read)) return ItemUseResult.FAILED
        if (busy || g.call(fns.fieldControlsLocked) != 0L) return ItemUseResult.NOT_NOW
        // The game's whole PC menu (PcMenuScript), where its RAM is known to be free; else the storage alone.
        pcMenuScript(pc, g)?.let { menu ->
            if (!g.write(PcMenuScript.RAM, menu)) return ItemUseResult.FAILED
            g.call(pc.setupScript, PcMenuScript.RAM.toInt())
            return ItemUseResult.USED
        }
        // lockall; special N; waitstate; releaseall; end (Gen 3's script opcodes, the same in both engines).
        val script = byteArrayOf(0x69, 0x25, pc.special.toByte(), (pc.special shr 8).toByte(), 0x27, 0x6B, 0x02)
        if (!g.write(pc.script, script)) return ItemUseResult.FAILED
        g.call(pc.setupScript, pc.script.toInt())
        ItemUseResult.USED
    }.getOrElse { ItemUseResult.FAILED }
}
