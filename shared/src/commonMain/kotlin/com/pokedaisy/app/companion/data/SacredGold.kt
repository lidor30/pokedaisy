package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile
import okio.Path

/**
 * Pokémon Sacred Gold and Storm Silver (v1.05): Drayano's hacks, the US HeartGold / SoulSilver edited in place (his 2012
 * xdelta 1 patches, "Complete" and "Classic"; the Classic one leaves the species alone). Their headers are the retail carts'
 * (IPKE / IPGE rev 0) and so are their RAM, save and field: the player's HeartGold save boots in Sacred Gold and reads as
 * HeartGold's ([GEN4_HEARTGOLD_US]'s addresses, checked headless). They're told apart by the cart's layout ([matches]): their
 * ARM9 was packed again (0xBA148 bytes, retail's 0xBA314 / 0xBA318) and the file system rebuilt (the name table at 0xE6A00,
 * retail's 0x31EC00), the same in every build.
 *
 * They run as [GameKind.HEARTGOLD] / [GameKind.SOULSILVER] with, while [sacredGold], their own tables read from the player's cart
 * by [NdsArt.readSacredGold] ([data]): each species' types (Complete changed many), each move's type, power and category, and
 * their maps' wild encounter rates (the map headers in the unpacked ARM9, the encounter archive's sets). Gen 4's type chart is
 * kept (v1.05 has no Fairy). No GUIDE: their wild Pokémon and trainers are their own.
 */
object SacredGold {
    const val CODE_SACRED_GOLD = "IPKE"
    const val CODE_STORM_SILVER = "IPGE"
    const val REV = 0
    const val SACRED_GOLD_V1_05_SHA1 = "ddb83b937571e5967f0882b926b7792cd6b570b5"
    const val SACRED_GOLD_V1_05_CLASSIC_SHA1 = "2f26ad811a4e1c65d4d064e82101103de6916a71"
    const val STORM_SILVER_V1_05_SHA1 = "a226e7265da21ab17b3a0f27b992becd5435033e"
    val SACRED_GOLD_SHA1S = listOf(SACRED_GOLD_V1_05_SHA1, SACRED_GOLD_V1_05_CLASSIC_SHA1)
    val STORM_SILVER_SHA1S = listOf(STORM_SILVER_V1_05_SHA1)

    /** The header's ARM9 size (0x2C): Drayano's builds 0xBA148. */
    const val SIG_OFFSET = 0x2CL
    const val SIG = 0xBA148L
    /** The header's file name table offset (0x40): his builds 0xE6A00. */
    const val FNT_OFFSET = 0xE6A00L

    /** The map headers in the unpacked ARM9 (retail's gMapHeaders 0x020F6BE0, 540 of 24 bytes): each map's encounter bank in
     * its first byte (0xFF none). */
    const val MAP_HEADERS_ARM9 = 0xF6BE0
    const val MAP_HEADER_COUNT = 540
    /** The encounter archives: HeartGold's and SoulSilver's own. */
    const val ENCOUNTERS_HG = "/a/0/3/7"
    const val ENCOUNTERS_SS = "/a/1/3/6"

    /** Whether [read] (cart offset, length) is one of Drayano's builds. */
    fun matches(read: (Long, Int) -> ByteArray?): Boolean {
        val h = read(0, 0x60)?.takeIf { it.size == 0x60 } ?: return false
        val code = asciiString(h, 0x0C, 4)
        if (code != CODE_SACRED_GOLD && code != CODE_STORM_SILVER || (h[0x1E].toInt() and 0xFF) != REV) return false
        return u32le(h, SIG_OFFSET.toInt()) == SIG && u32le(h, 0x40) == FNT_OFFSET
    }

    /** [matches] on a plain ROM file. */
    fun matches(rom: Path): Boolean = runCatching { withRomFile(rom) { matches(it.cartReader()) } }.getOrDefault(false)

    /** Their tables from the player's cart ([NdsArt.readSacredGold]); null until read, or for another game. */
    class Data(
        /** By species id (their personal records). */
        val speciesTypes: Map<Int, SpeciesTypes>,
        /** By move id: the cart's names, types and powers. */
        val moveData: Map<Int, MoveInfo>,
        /** By move id: 0 physical, 1 special, 2 status. */
        val moveCategories: Map<Int, Int>,
        /** (map header, walking rate, surfing rate) triples: WILD ENCOUNTERS puts these back. */
        val wildRates: IntArray = IntArray(0),
    )

    @Volatile var data: Data? = null
}
