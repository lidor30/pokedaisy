package com.pokedaisy.app.companion.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path

/**
 * LOW HP BEEP and FIELD POISON (SETTINGS > GAMEPLAY): two of the games' habits turned off by one small patch each, game
 * options like INSTANT TEXT (the engine's applyTmPatch, all or none each).
 *
 *  - [beep]: battle_gfx_sfx_util.c's HandleLowHpMusicChange plays SE_LOW_HEALTH when the player's battler drops into the
 *    red (`movs r0, #SE_LOW_HEALTH; bl PlaySE`); its `bl` becomes two `nop`s. Every other use of the sound is
 *    `m4aSongNumStop` (stopping it), left alone, so nothing starts the loop.
 *  - [poison]: field_control_avatar.c's UpdatePoisonStepCounter calls DoPoisonFieldEffect every 4 (Emerald) / 5 (FireRed)
 *    steps; that call becomes `movs r0, #0` (FLDPSN_NONE), so no HP is lost on the field, no flash, no faint. In battle
 *    poison works as ever.
 *
 * Found by agbcc's code (FireRed / LeafGreen / Emerald / Ruby / Sapphire and the hacks on it); played headless on FireRed
 * rev 1 and Emerald.
 */
object QuietPatches {
    class Found(val beep: InstantText.Patch?, val poison: InstantText.Patch?)

    private const val ROM_BASE = 0x08000000L
    /** SE_LOW_HEALTH in each song table: FireRed / LeafGreen's, Emerald / Ruby / Sapphire's. */
    private val LOW_HEALTH_SE = intArrayOf(83, 90)
    private val NOP2 = byteArrayOf(0xC0.toByte(), 0x46, 0xC0.toByte(), 0x46)
    /** `movs r0, #0; nop`: DoPoisonFieldEffect's FLDPSN_NONE. */
    private val NO_POISON = byteArrayOf(0x00, 0x20, 0xC0.toByte(), 0x46)

    private val cache = HashMap<String, Found?>()
    private val lock = SynchronizedObject()

    /** What [rom] has (either can be null), or null for neither. Reads the file (a window at a time): off the UI thread;
     * cached per file. */
    fun find(rom: Path): Found? {
        val key = fileKey(rom)
        synchronized(lock) { if (key in cache) return cache[key] }
        val found = runCatching { withRomFile(rom) { find(FileLittleEndian(it)) } }.getOrNull()
        synchronized(lock) { cache[key] = found }
        return found
    }

    fun find(rom: LittleEndianReader): Found? {
        val beep = beep(rom)
        val poison = poison(rom)
        return if (beep == null && poison == null) null else Found(beep, poison)
    }

    /** The one `movs r0, #SE_LOW_HEALTH; bl X` whose X no other such call shares (the rest: m4aSongNumStop, 4 or more). */
    private fun beep(rom: LittleEndianReader): InstantText.Patch? {
        for (se in LOW_HEALTH_SE) {
            val sites = ArrayList<Pair<Int, Int>>()
            var i = 0
            val end = rom.limit() - 6
            while (i < end) {
                if (half(rom, i) == (0x2000 or se)) blTarget(rom, i + 2)?.let { sites += i + 2 to it }
                i += 2
            }
            val byTarget = sites.groupBy { it.second }
            val stops = byTarget.values.maxOfOrNull { it.size } ?: 0
            val lone = byTarget.values.filter { it.size == 1 }
            if (stops >= 4 && lone.size == 1) {
                val at = lone[0][0].first
                return InstantText.Patch(listOf(InstantText.Write(ROM_BASE + at, ByteArray(4) { rom.get(at + it) }, NOP2)))
            }
        }
        return null
    }

    /** UpdatePoisonStepCounter's DoPoisonFieldEffect call: after `ldrb r0, [r0, #0x17]; cmp r0, #MAP_TYPE_SECRET_BASE; beq`,
     * the first `bl` followed by its switch (`cmp r0, #1; beq; cmp r0, #1; ble; cmp r0, #2`). Exactly one in the ROM. */
    private fun poison(rom: LittleEndianReader): InstantText.Patch? {
        var at = -1
        var i = 0
        val end = rom.limit() - 0x50
        while (i < end) {
            if (half(rom, i) == 0x7DC0 && half(rom, i + 2) == 0x2809 && (half(rom, i + 4) and 0xFF00) == 0xD000) {
                for (o in i + 6 until i + 0x40 step 2) {
                    if (blTarget(rom, o) == null) continue
                    if (half(rom, o + 4) == 0x2801 && (half(rom, o + 6) and 0xFF00) == 0xD000 && half(rom, o + 8) == 0x2801 &&
                        (half(rom, o + 10) and 0xFF00) == 0xDD00 && half(rom, o + 12) == 0x2802
                    ) {
                        if (at >= 0) return null
                        at = o
                        break
                    }
                }
            }
            i += 2
        }
        if (at < 0) return null
        return InstantText.Patch(listOf(InstantText.Write(ROM_BASE + at, ByteArray(4) { rom.get(at + it) }, NO_POISON)))
    }

    private fun half(rom: LittleEndianReader, at: Int) = rom.getShort(at).toInt() and 0xFFFF

    private fun blTarget(rom: LittleEndianReader, at: Int): Int? {
        val h1 = half(rom, at)
        val h2 = half(rom, at + 2)
        if (h1 and 0xF800 != 0xF000 || h2 and 0xF800 != 0xF800) return null
        var hi = h1 and 0x7FF
        if (hi and 0x400 != 0) hi -= 0x800
        return at + 4 + (hi shl 12) + ((h2 and 0x7FF) shl 1)
    }
}
