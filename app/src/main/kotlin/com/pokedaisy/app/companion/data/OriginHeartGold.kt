package com.pokedaisy.app.companion.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Pokémon Origin HeartGold (v4.0.3, English patch 1.0.0): Yannanfei TB's hack, rebuilt by Alex on hg-engine over the
 * Japanese HeartGold and patched to English. Its header is a real Japanese HeartGold's (IPKJ rev 0), so it's told apart
 * by its code: SaveData_Get's literal pool ([SIG_OFFSET], the ARM9 is uncompressed on the cart) holds the save's static
 * block. Every address and table here is that build's (nds/pk_nds_field.cpp's ORIGIN_HEARTGOLD has the native ones), so
 * another build of the hack fails the check and reads as not supported rather than wrong.
 *
 * It runs as [GameKind.HEARTGOLD] on HeartGold's reader with its own [GEN4_ORIGIN_HEARTGOLD] and, while [active], its
 * own tables, all read from the player's cart by [NdsArt.readOrigin] ([data]): the National Dex's 1025 species and 415
 * forms (species ids 1026-1440, picked through [Data.forms]), 920 moves, Fairy as type 9 and its Gen 6 type chart.
 */
object OriginHeartGold {
    const val CODE = "IPKJ"
    /** The build mapped: the English patch 1.0.0-rc8 applied to the US HeartGold (its patch names that ROM). */
    const val V4_0_3_EN_RC8_SHA1 = "a54a647599d59ed0060dfc4375c289d6e041318a"

    /** SaveData_Get's pool word (0x0202773C in the ARM9, which the cart keeps at 0x4000) and what it holds. */
    const val SIG_OFFSET = 0x4000L + 0x2773C
    const val SIG = 0x021D11B0L

    /** Cart offsets of what [NdsArt.readOrigin] reads past the file system (the build is pinned by [matches]). */
    /** Its overlay 14's type chart: 18 x 18 bytes, attacker by defender, 4 = 1x. */
    const val TYPE_CHART_OFFSET = 0x228860L
    /** The ARM9's icon palette index per species / form id (GetMonIconPaletteEx's table, 0x020FF7FC). */
    const val ICON_PALETTES_OFFSET = 0x4000L + 0xFF7FC
    /** The ARM9's form table (0x020FEDC0): [FORM_COUNT] entries of { u16 species, u16 form species id, u8 form, pad }. */
    const val FORMS_OFFSET = 0x4000L + 0xFEDC0
    const val FORM_COUNT = 0x19F
    /** The ARM9's machine moves: TM93-TM130 (0x020FFFAC), then TM01-TM92 and HM01-HM08 (0x020FFFF8). */
    const val TM93_MOVES_OFFSET = 0x4000L + 0xFFFAC
    const val TM_MOVES_OFFSET = 0x4000L + 0xFFFF8

    /** The ARM9's map headers (sMapHeaders, 0x020F37C4: HeartGold's 540, 24 bytes each, the encounter bank first). */
    const val MAP_HEADERS_OFFSET = 0x4000L + 0xF37C4
    const val MAP_HEADER_COUNT = 540

    /** Species ids: the National Dex to 1025, its forms to 1440 (personal records 0-1440). */
    const val LAST_NATIONAL = 1025
    const val LAST_SPECIES = 1440

    /** The bag's machines by item id: TM01 328 .. TM92 419, HM01 420 .. HM08 427, TM93 537 .. TM130 574. */
    const val TM01_ITEM = 328
    const val TM93_ITEM = 537

    /** Whether [read] (cart offset, length) is this build's cart. */
    fun matches(read: (Long, Int) -> ByteArray?): Boolean {
        val code = read(0x0C, 4)?.takeIf { it.size == 4 } ?: return false
        if (String(code, Charsets.US_ASCII) != CODE) return false
        if (read(0x1E, 1)?.firstOrNull()?.toInt() != 0) return false
        val w = read(SIG_OFFSET, 4)?.takeIf { it.size == 4 } ?: return false
        return u32le(w, 0) == SIG
    }

    /** [matches] on a plain ROM file (an archive's goes through RomArchive's reader: CompanionSupport). */
    fun matches(rom: File): Boolean = runCatching {
        RandomAccessFile(rom, "r").use { f ->
            matches { off, len -> if (off + len > f.length()) null else ByteArray(len).also { f.seek(off); f.readFully(it) } }
        }
    }.getOrDefault(false)

    /** Its tables from the player's cart ([NdsArt.readOrigin]); null until read, or for another game. */
    class Data(
        /** By species / form id. */
        val speciesTypes: Map<Int, SpeciesTypes>,
        /** By move id: the cart's names, types and powers. */
        val moveData: Map<Int, MoveInfo>,
        /** atkType * 100 + defType -> percent (the app's charts' key), pairs at 100 left out. */
        val typeChart: Map<Int, Int>,
        /** (species, form) -> its form's species id. */
        val forms: Map<Long, Int>,
        /** TM01 .. TM92, HM01 .. HM08, TM93 .. TM130: each machine's move. */
        val machines: IntArray,
        /** WILD ENCOUNTERS' cart rates: map header, walking rate, surfing rate, ... (MgbaCore.pkNdsWildRates). */
        val wildRates: IntArray,
    ) {
        fun formSpecies(species: Int, form: Int): Int =
            if (form == 0) species else forms[(species.toLong() shl 8) or form.toLong()] ?: species
    }

    @Volatile var data: Data? = null

    /** A machine in the bag ([TM01_ITEM] ...): its move, whether it's an HM and its number, or null. */
    fun machine(itemId: Int): Triple<Int, Boolean, Int>? {
        val m = data?.machines ?: return null
        return when (itemId) {
            in TM01_ITEM until TM01_ITEM + 92 -> Triple(m.getOrElse(itemId - TM01_ITEM) { 0 }, false, itemId - TM01_ITEM + 1)
            in TM01_ITEM + 92 until TM01_ITEM + 100 -> Triple(m.getOrElse(itemId - TM01_ITEM) { 0 }, true, itemId - TM01_ITEM - 91)
            in TM93_ITEM until TM93_ITEM + 38 -> Triple(m.getOrElse(100 + itemId - TM93_ITEM) { 0 }, false, itemId - TM93_ITEM + 93)
            else -> null
        }?.takeIf { it.first != 0 }
    }

    /** [Data.machines] as the DEX lists them ([Gen4DexTables]: the TMs first, then the HMs): TM01 .. TM92, TM93 .. TM130,
     * HM01 .. HM08. */
    fun dexMachines(machines: IntArray): IntArray =
        if (machines.size < 138) IntArray(0) else machines.copyOfRange(0, 92) + machines.copyOfRange(100, 138) + machines.copyOfRange(92, 100)

    /** English type names by the hack's ids: Gen 4's, Fairy in "???"'s place (its own text calls 9 Fairy). */
    val typeNames: Map<Int, String> = typeNamesGlazed
}
