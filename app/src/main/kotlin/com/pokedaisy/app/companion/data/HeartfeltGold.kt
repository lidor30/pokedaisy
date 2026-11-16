package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile
import okio.Path

/**
 * Pokémon Heartfelt Gold (v1.0.15, and its "Zoomed In" camera patch): an hg-engine build over the US HeartGold, its header
 * the retail cart's (IPKE rev 0). Unlike Origin HeartGold (hg-engine over the Japanese cart) it keeps the US cart's RAM,
 * overlays 0-128 at their addresses and HeartGold's battle system, so the native side runs it as HeartGold and the PC /
 * field reads hold (checked headless on the player's save). What differs is its data and its save:
 *
 * - species to 1534 in HeartGold's own archives grown (`/a/0/0/2`, the 44-byte personal records: Fairy is type 9; the
 *   names from HeartGold's text banks, which kept their numbers), 923 moves (`/a/0/1/1`, 16 bytes: category 2, power 3,
 *   type 4), its type chart as HeartGold's triples with Fairy's rows ([TYPE_CHART_OFFSET]), party icons in `/a/0/2/0`
 *   (7 + species) with its own palette index table ([ICON_PALETTES_OFFSET]);
 * - the save (GEN4_HEARTFELT_GOLD): hg-engine's bag (Items 245, Key Items 92, Balls 26) moved everything after it 0x1F0.
 *
 * It's told apart by its header's ARM9 size and overlay table size ([matches]): an hg-engine ARM9 is uncompressed and
 * grown, and its overlay table runs to 152; another build reads as HeartGold until it's added.
 */
object HeartfeltGold {
    const val CODE = "IPKE"
    const val REV = 0
    const val V1_0_15_SHA1 = "b2b27aeb2cfbadaaf577083344492cdf2b63ea71"
    /** v1.0.15's "Zoomed In" patch: the same build, its field camera closer. */
    const val V1_0_15_ZOOMED_IN_SHA1 = "2eaf49cc09ce2f0be221271830507c493ed9d69c"
    val SHA1S = listOf(V1_0_15_SHA1, V1_0_15_ZOOMED_IN_SHA1)

    /** The header's ARM9 size (0x2C): retail's 0xBA314 packed, this build's 0x111EF8. */
    const val SIG_OFFSET = 0x2CL
    const val SIG = 0x111EF8L
    /** The header's overlay table size (0x54): 152 overlays of 32 bytes (retail's 129). */
    const val OVERLAY_TABLE_SIZE = 152 * 32

    /** Its type chart: HeartGold's { attacker, defender, x10 } triples, Fairy's (9) rows added, 0xFE / 0xFF as retail's. */
    const val TYPE_CHART_OFFSET = 0x3C4680L
    /** The ARM9's party icon palette index per species (0x020FFC10, uncompressed on the cart at 0x4000). */
    const val ICON_PALETTES_OFFSET = 0x4000L + 0xFFC10
    /** The last species its icon palette table covers (retail HeartGold's ends at the egg, 494; this one runs on through its forms): past it its bytes
     * are other data, and no other table on the cart holds the newer species' palettes, so they get no party icon. */
    const val LAST_ICON_PALETTE = 543
    /** The last personal record (1535 of them: the National Dex's species, its forms after). */
    const val LAST_SPECIES = 1534

    /** Whether [read] (cart offset, length) is this build's cart. */
    fun matches(read: (Long, Int) -> ByteArray?): Boolean {
        val h = read(0, 0x60)?.takeIf { it.size == 0x60 } ?: return false
        if (asciiString(h, 0x0C, 4) != CODE || (h[0x1E].toInt() and 0xFF) != REV) return false
        return u32le(h, SIG_OFFSET.toInt()) == SIG && u32le(h, 0x54) == OVERLAY_TABLE_SIZE.toLong()
    }

    /** [matches] on a plain ROM file. */
    fun matches(rom: Path): Boolean = runCatching { withRomFile(rom) { matches(it.cartReader()) } }.getOrDefault(false)

    /** Its tables from the player's cart ([NdsArt.readHeartfelt]); null until read, or for another game. */
    class Data(
        /** By species id (its personal records: Fairy is 9). */
        val speciesTypes: Map<Int, SpeciesTypes>,
        /** By move id: the cart's names, types and powers. */
        val moveData: Map<Int, MoveInfo>,
        /** By move id: 0 physical, 1 special, 2 status. */
        val moveCategories: Map<Int, Int>,
        /** atkType * 100 + defType -> percent, pairs at 100 left out. */
        val typeChart: Map<Int, Int>,
    )

    @Volatile var data: Data? = null

    /** English type names by its ids: Gen 4's, Fairy in "???"'s place. */
    val typeNames: Map<Int, String> = typeNamesGlazed
}
