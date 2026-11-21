package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.i18n.tk

/**
 * WILD PIN (a PIN on each GUIDE > HERE GRASS / CAVE, SURFING, ROCK SMASH and rod row and in the HUD's WILD; GAMEPLAY >
 * WILD PIN hides them): the next encounter of that [Method] on this map is the pinned species. The game still makes the
 * Pokémon (CreateWildMon: level from the slot's range, its own personality and IVs); only which slot it can roll changes.
 * While a pin is on, the method's slot array on this map ({u8 minLevel, u8 maxLevel, u16 species} each, the
 * WildPokemonInfo's `wildPokemon` array: 12 land, 5 water, 5 rock smash, 10 fishing) is put over the running ROM as one
 * block ([com.pokedaisy.app.MgbaCore.pkRomBlocks], like PLAYER LOOK's - the file is never written, the Poller and
 * RetroAchievements still read the file's bytes), every slot = the species at the lowest and highest level its own slots
 * of that method have. Fishing's 10 slots are the three rods' (old 0-1, good 2-4, super 5-9): all 10 go in, so any rod
 * catches it, at the levels its own rods' slots have. gEnemyParty is never touched.
 *
 * One pin at a time, one encounter: the pin ends when a wild battle starts (any method's) (gMain.inBattle rising with gBattleTypeFlags' TRAINER clear -
 * the foe is made by then), when the player is on another map, or on UNPIN; then the blocks go back in without it.
 * RetroAchievements idle while one is on.
 *
 * First slice: retail English FireRed rev 1, LeafGreen rev 0 / 1 and Emerald ([offered], by their GUIDE tables, which the
 * Poller keeps only where the ROM matches). A headless check puts [Pin.toText]'s line in a `romblocks`
 * file for mgba_dump.
 */
object WildPin {
    const val SLOT_BYTES = 4
    const val LAND_SLOTS = 12
    /** The land block's size (the largest of the four). */
    const val BLOCK_BYTES = LAND_SLOTS * SLOT_BYTES

    /** An encounter method: its pointer's place in the wild header ([index]: land, water, rock smash, fishing) and its
     * slot count. [sections] are HERE's / the HUD's headings (English keys) whose rows it pins. */
    enum class Method(val index: Int, val slots: Int, val sections: List<String>) {
        LAND(0, 12, listOf("GRASS / CAVE")),
        WATER(1, 5, listOf("SURFING")),
        ROCK_SMASH(2, 5, listOf("ROCK SMASH")),
        FISHING(3, 10, listOf("OLD ROD", "GOOD ROD", "SUPER ROD"));

        val blockBytes: Int get() = slots * SLOT_BYTES

        companion object {
            /** The method a HERE / HUD heading's rows pin, or null (HEADBUTT, AT NIGHT ...: no PIN). */
            fun forSection(name: String): Method? = entries.firstOrNull { name in it.sections }
        }
    }

    /** gBattleTypeFlags' BATTLE_TYPE_TRAINER (every Gen 3 game). */
    private const val BATTLE_TYPE_TRAINER = 0x0008

    /** The reason PIN is greyed with the NUZLOCKE tracker on (one catch per area: a chosen encounter isn't one). */
    val NUZLOCKE_REASON = tk("OFF IN A NUZLOCKE RUN")
    /** The reason PIN is greyed in a battle (it waits for the field). */
    val BATTLE_REASON = tk("NOT IN A BATTLE")

    /** A map's slot array for [method] as the file has it: [addr] (0x08xxxxxx) and its [Method.blockBytes] bytes. */
    class Slots(val addr: Long, bytes: ByteArray, val method: Method = Method.LAND) {
        private val raw = bytes.copyOf()
        val bytes: ByteArray get() = raw.copyOf()
    }

    /** One block of ROM: [data] at [addr] (0x08xxxxxx), only where the file's bytes there have CRC32 [crc]. */
    class RomBlock(val addr: Int, val crc: Int, data: ByteArray) {
        private val raw = data.copyOf()
        val data: ByteArray get() = raw.copyOf()
        val size: Int get() = raw.size
    }

    /** A pin: [species] on map [group].[num] (its wild set [set]) for [slots]' method, every slot at [minLevel]..[maxLevel]. */
    class Pin internal constructor(
        val species: Int, val group: Int, val num: Int, val set: Int,
        val slots: Slots, val minLevel: Int, val maxLevel: Int,
    ) {
        val method: Method get() = slots.method

        /** The block that puts it in: the method's slots rewritten, guarded by the file's bytes' CRC32. */
        val block: RomBlock = RomBlock(slots.addr.toInt(), crc(slots.bytes), blockBytes(species, minLevel, maxLevel, slots.method.slots))

        fun onMap(group: Int, num: Int): Boolean = group == this.group && num == this.num

        /** mgba_dump's `romblocks` line (headless checks of exactly what the app puts in). */
        fun toText(): String = "block ${hex8(block.addr)} ${hex8(block.crc)} " +
            block.data.joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') } + "\n"
    }

    /** The species' level range over its own slots in [bytes] (slots that can't be real are skipped, as HERE does), or
     * null when it has none. */
    fun levelRange(bytes: ByteArray, species: Int, method: Method = Method.LAND): IntRange? {
        if (bytes.size != method.blockBytes || species <= 0) return null
        val mine = (0 until method.slots).map { i -> slot(bytes, i) }.filter { it.valid && it.species == species }
        if (mine.isEmpty()) return null
        return mine.minOf { it.min }..mine.maxOf { it.max }
    }

    /** [count] slots (12 = land), each {min, max, species}. */
    fun blockBytes(species: Int, minLevel: Int, maxLevel: Int, count: Int = LAND_SLOTS): ByteArray {
        require(species in 1..0xFFFF && minLevel in 1..100 && maxLevel in minLevel..100) { "bad pin $species $minLevel-$maxLevel" }
        val out = ByteArray(count * SLOT_BYTES)
        for (i in 0 until count) {
            val o = i * SLOT_BYTES
            out[o] = minLevel.toByte()
            out[o + 1] = maxLevel.toByte()
            out[o + 2] = (species and 0xFF).toByte()
            out[o + 3] = (species shr 8).toByte()
        }
        return out
    }

    /** [species] pinned on map [group].[num] from its [slots] (one method's), or null when they don't hold it. */
    fun pin(species: Int, group: Int, num: Int, set: Int, slots: Slots): Pin? {
        if (!romAddr(slots.addr, slots.method.blockBytes)) return null
        val range = levelRange(slots.bytes, species, slots.method) ?: return null
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

    private fun romAddr(addr: Long, size: Int) = addr in 0x08000000L..(0x0A000000L - size)

    private fun crc(b: ByteArray): Int = crc32(b).toInt()

    private fun hex8(v: Int): String = (v.toLong() and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')
}
