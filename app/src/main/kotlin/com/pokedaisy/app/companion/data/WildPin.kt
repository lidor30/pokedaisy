package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.i18n.tk
import java.util.zip.CRC32

/**
 * WILD PIN (a PIN on each GUIDE > HERE grass / cave row and in the HUD's WILD; GAMEPLAY > WILD PIN hides them): the next
 * grass or cave encounter on this map is the pinned species. The game still makes the Pokémon (CreateWildMon: level from
 * the slot's range, its own personality and IVs); only which slot it can roll changes. While a pin is on, the map's 12
 * land slots ({u8 minLevel, u8 maxLevel, u16 species}, 48 bytes, the WildPokemonInfo's `wildPokemon` array) are put over
 * the running ROM as one block ([com.pokedaisy.app.MgbaCore.pkRomBlocks], like PLAYER LOOK's - the file is never
 * written, the Poller and RetroAchievements still read the file's bytes), every slot = the species at the lowest and
 * highest level its own slots on this map have. gEnemyParty is never touched.
 *
 * One encounter: the pin ends when a wild battle starts (gMain.inBattle rising with gBattleTypeFlags' TRAINER clear -
 * the foe is made by then), when the player is on another map, or on UNPIN; then the blocks go back in without it.
 * RetroAchievements idle while one is on.
 *
 * First slice: retail English FireRed rev 1, LeafGreen rev 0 / 1 and Emerald ([offered], by their GUIDE tables, which the
 * Poller keeps only where the ROM matches), grass and caves. A headless check puts [Pin.toText]'s line in a `romblocks`
 * file for mgba_dump.
 */
object WildPin {
    const val LAND_SLOTS = 12
    const val SLOT_BYTES = 4
    const val BLOCK_BYTES = LAND_SLOTS * SLOT_BYTES

    /** gBattleTypeFlags' BATTLE_TYPE_TRAINER (every Gen 3 game). */
    private const val BATTLE_TYPE_TRAINER = 0x0008

    /** The reason PIN is greyed with the NUZLOCKE tracker on (one catch per area: a chosen encounter isn't one). */
    val NUZLOCKE_REASON = tk("OFF IN A NUZLOCKE RUN")
    /** The reason PIN is greyed in a battle (it waits for the field). */
    val BATTLE_REASON = tk("NOT IN A BATTLE")

    /** A map's land slot array as the file has it: [addr] (0x08xxxxxx) and its [BLOCK_BYTES] bytes. */
    class LandSlots(val addr: Long, bytes: ByteArray) {
        private val raw = bytes.copyOf()
        val bytes: ByteArray get() = raw.copyOf()
    }

    /** One block of ROM: [data] at [addr] (0x08xxxxxx), only where the file's bytes there have CRC32 [crc]. */
    class RomBlock(val addr: Int, val crc: Int, data: ByteArray) {
        private val raw = data.copyOf()
        val data: ByteArray get() = raw.copyOf()
        val size: Int get() = raw.size
    }

    /** A pin: [species] on map [group].[num] (its wild set [set]), every slot at [minLevel]..[maxLevel]. */
    class Pin internal constructor(
        val species: Int, val group: Int, val num: Int, val set: Int,
        val slots: LandSlots, val minLevel: Int, val maxLevel: Int,
    ) {
        /** The block that puts it in: the 12 slots rewritten, guarded by the file's 48 bytes' CRC32. */
        val block: RomBlock = RomBlock(slots.addr.toInt(), crc(slots.bytes), blockBytes(species, minLevel, maxLevel))

        fun onMap(group: Int, num: Int): Boolean = group == this.group && num == this.num

        /** mgba_dump's `romblocks` line (headless checks of exactly what the app puts in). */
        fun toText(): String = "block %08X %08X %s\n".format(block.addr, block.crc, block.data.joinToString("") { "%02X".format(it.toInt() and 0xFF) })
    }

    /** The species' level range over its own slots in [bytes] (slots that can't be real are skipped, as HERE does), or
     * null when it has none. */
    fun levelRange(bytes: ByteArray, species: Int): IntRange? {
        if (bytes.size != BLOCK_BYTES || species <= 0) return null
        val mine = (0 until LAND_SLOTS).map { i -> slot(bytes, i) }.filter { it.valid && it.species == species }
        if (mine.isEmpty()) return null
        return mine.minOf { it.min }..mine.maxOf { it.max }
    }

    /** 12 slots, each {min, max, species}. */
    fun blockBytes(species: Int, minLevel: Int, maxLevel: Int): ByteArray {
        require(species in 1..0xFFFF && minLevel in 1..100 && maxLevel in minLevel..100) { "bad pin $species $minLevel-$maxLevel" }
        val out = ByteArray(BLOCK_BYTES)
        for (i in 0 until LAND_SLOTS) {
            val o = i * SLOT_BYTES
            out[o] = minLevel.toByte()
            out[o + 1] = maxLevel.toByte()
            out[o + 2] = (species and 0xFF).toByte()
            out[o + 3] = (species shr 8).toByte()
        }
        return out
    }

    /** [species] pinned on map [group].[num] from its land [slots], or null when the map's slots don't hold it. */
    fun pin(species: Int, group: Int, num: Int, set: Int, slots: LandSlots): Pin? {
        if (!romAddr(slots.addr)) return null
        val range = levelRange(slots.bytes, species) ?: return null
        return Pin(species, group, num, set, slots, range.first, range.last)
    }

    /** The games so far: retail English FireRed rev 1, LeafGreen rev 0 / 1 and Emerald, and Monomyth v1.0 / v1.1 (vanilla's
     * 20-byte wild headers and 12 land slots; its ROUTE 1 slots end where the next WildPokemonInfo starts), by their GUIDE
     * tables (the Poller drops them where the ROM doesn't match; other hacks have their own). */
    fun offered(t: GuideTables?): Boolean = t != null && t.wildLayout == null && (
        t === GUIDE_TABLES_FIRERED_REV1 || t === GUIDE_TABLES_LEAFGREEN_REV0 || t === GUIDE_TABLES_LEAFGREEN_REV1 ||
            t === GUIDE_TABLES_EMERALD || t === GUIDE_TABLES_MONOMYTH || t === GUIDE_TABLES_MONOMYTH_V1_1
        )

    /** Why PIN can't be tapped now, or null: the NUZLOCKE tracker on, or a battle up. An English key (tr() draws it). */
    fun blocked(nuzlocke: Boolean, inBattle: Boolean): String? = when {
        nuzlocke -> NUZLOCKE_REASON
        inBattle -> BATTLE_REASON
        else -> null
    }

    /** Every block to put in at once ([com.pokedaisy.app.MgbaCore.pkRomBlocks] replaces whatever was in): PLAYER LOOK's
     * [look] blocks, then the pin's. */
    fun withPin(look: List<RomBlock>, pin: Pin?): List<RomBlock> = if (pin == null) look else look + pin.block

    /** A battle just started with [battleTypeFlags] (null = not readable): whether it ends the pin - a wild one does, a
     * trainer's doesn't (the land slots played no part); one that can't be read ends it (a pin is one encounter). */
    fun endsOnBattle(battleTypeFlags: Int?): Boolean = battleTypeFlags == null || battleTypeFlags and BATTLE_TYPE_TRAINER == 0

    /** One frame's check (emu thread): the pin ends on the frame a battle starts ([wasBattling] false, [battling] true) and
     * [endsOnBattle] says it's a wild one; [flags] reads gBattleTypeFlags only then. */
    fun endsNow(wasBattling: Boolean, battling: Boolean, flags: () -> Int?): Boolean =
        !wasBattling && battling && endsOnBattle(runCatching(flags).getOrNull())

    private class Slot(val min: Int, val max: Int, val species: Int) {
        val valid get() = species != 0 && min in 1..100 && max in min..100
    }

    private fun slot(b: ByteArray, i: Int): Slot {
        val o = i * SLOT_BYTES
        return Slot(b[o].toInt() and 0xFF, b[o + 1].toInt() and 0xFF, (b[o + 2].toInt() and 0xFF) or ((b[o + 3].toInt() and 0xFF) shl 8))
    }

    private fun romAddr(addr: Long) = addr in 0x08000000L..(0x0A000000L - BLOCK_BYTES)

    private fun crc(b: ByteArray): Int = CRC32().apply { update(b) }.value.toInt()
}
