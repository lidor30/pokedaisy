package com.pokedaisy.app.companion.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Pokémon Renegade Platinum (v1.3.0): Drayano's hack, retail Platinum (USA rev 1) edited in place. Its header is the
 * retail cart's (CPUE rev 1) and so are its ARM9 (but the shiny check's one word), its field overlays and its RAM: the
 * save, party, bag, field and battle structs read as Platinum's ([GEN4_PLATINUM_US]'s addresses, checked headless on a
 * new game). It's told apart by its code: overlay 16 (the battle's) points its type-matchup lookups at a chart of its own
 * in overlay 21 ([SIG_OFFSET] holds [SIG]; retail's is 0x0226ECD5), the overlays uncompressed at fixed cart offsets, so
 * another build fails the check and reads as plain Platinum.
 *
 * It runs as [GameKind.PLATINUM] on Platinum's reader with [GEN4_RENEGADE_PLATINUM] and, while [renegadePlatinum], its own tables,
 * all read from the player's cart by [NdsArt.readRenegade] ([data]): its species' types (Fairy as type 9, "???"'s slot),
 * its moves' types, powers and categories, Gen 6's type chart, its machines (five TMs teach other moves), its icon palettes
 * and its maps' wild encounter rates. Its "Classic" patch shares every one of these places (only its species' types and
 * stats differ), so it matches too and reads its own.
 */
object RenegadePlatinum {
    const val CODE = "CPUE"
    const val REV = 1
    /** The build mapped. */
    const val V1_3_0_SHA1 = "d4ee068b7e0a19a848436369f145e3154afe13ca"
    /** v1.3.0 with its "Classic" patch: only the species' data differs (pl_personal.narc). */
    const val V1_3_0_CLASSIC_SHA1 = "8b9d933ed88dd5bffed1e96e229db568b5dad237"
    /** v1.2.0: v1.3.0's ARM9 and every place below byte for byte, one data byte apart in overlay 16 and 12 data files
     * (species, moves, trainers, encounters...) of its own, which [NdsArt.readRenegade] reads through the file system. */
    const val V1_2_0_SHA1 = "381f9eef44564602a5f6469ee8c390fbb8ebac3a"

    /** Overlay 16 (cart 0x207200, RAM 0x0223B140) at RAM 0x02254FA0: the first of its type chart's pointers, Thumb-tagged. */
    const val SIG_OFFSET = 0x221060L
    const val SIG = 0x021E9F81L

    /** Cart offsets of what [NdsArt.readRenegade] reads past the file system (the build is pinned by [matches]). */
    /** Its type chart in overlay 21 (cart 0x296A00, RAM 0x021D0D80) at RAM 0x021E9F80: { attacker, defender, x10 } triples,
     * all 18 x 18 of them, the foresight pair after 0xFE, 0xFF at the end. */
    const val TYPE_CHART_OFFSET = 0x2AFC00L
    /** The ARM9 (uncompressed at 0x4000): sPokemonIconPaletteIndex (0x020F0780), by species / form id. */
    const val ICON_PALETTES_OFFSET = 0x4000L + 0xF0780
    const val ICON_PALETTES_SIZE = 0x21C
    /** sTMHMMoves (0x020F0BFC): TM01 .. TM92, then HM01 .. HM08. */
    const val TM_MOVES_OFFSET = 0x4000L + 0xF0BFC
    /** sMapHeaders (0x020E601C): 593 of 24 bytes, the wild encounter set a u16 at +0x0E (0xFFFF none). */
    const val MAP_HEADERS_OFFSET = 0x4000L + 0xE601C
    const val MAP_HEADER_COUNT = 593

    /** Whether [read] (cart offset, length) is this build's cart. */
    fun matches(read: (Long, Int) -> ByteArray?): Boolean {
        val code = read(0x0C, 4)?.takeIf { it.size == 4 } ?: return false
        if (String(code, Charsets.US_ASCII) != CODE) return false
        if (read(0x1E, 1)?.firstOrNull()?.toInt() != REV) return false
        val w = read(SIG_OFFSET, 4)?.takeIf { it.size == 4 } ?: return false
        return u32le(w, 0) == SIG
    }

    /** [matches] on a plain ROM file. */
    fun matches(rom: File): Boolean = runCatching {
        RandomAccessFile(rom, "r").use { f ->
            matches { off, len -> if (off + len > f.length()) null else ByteArray(len).also { f.seek(off); f.readFully(it) } }
        }
    }.getOrDefault(false)

    /** Its tables from the player's cart ([NdsArt.readRenegade]); null until read, or for another game. */
    class Data(
        /** By species id (its personal records: Fairy is 9). */
        val speciesTypes: Map<Int, SpeciesTypes>,
        /** By move id: the cart's names, types and powers. */
        val moveData: Map<Int, MoveInfo>,
        /** By move id: 0 physical, 1 special, 2 status (the move's own, as Gen 4 keeps it). */
        val moveCategories: Map<Int, Int>,
        /** atkType * 100 + defType -> percent (the app's charts' key), pairs at 100 left out. */
        val typeChart: Map<Int, Int>,
        /** TM01 .. TM92, HM01 .. HM08: each machine's move. */
        val machines: IntArray,
        /** Each species / form id's icon palette (sPokemonIconPaletteIndex). */
        val iconPalettes: ByteArray,
        /** WILD ENCOUNTERS' cart rates: map header, walking rate, surfing rate, ... (MgbaCore.pkNdsWildRates). */
        val wildRates: IntArray,
    )

    @Volatile var data: Data? = null

    /** English type names by the hack's ids: Gen 4's, Fairy in "???"'s place (its own text calls 9 Fairy). */
    val typeNames: Map<Int, String> = typeNamesGlazed
}
