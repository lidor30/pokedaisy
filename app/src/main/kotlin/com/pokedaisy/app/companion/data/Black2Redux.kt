package com.pokedaisy.app.companion.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Pokémon Blaze Black 2 Redux / Volt White 2 Redux (v1.4.1): Drayano and AphexCubed's hacks, US / Europe Black 2 and
 * White 2 (IREO / IRDO rev 0) edited in place. Their headers are the retail carts' and so are their RAM, save and field
 * code: the save, party, bag, field and battle read as Black 2's ([GEN5_BLACK2] / [GEN5_WHITE2]'s addresses, the player's
 * retail saves loaded in them headless), and PC anywhere / FLY call the retail functions (their code checks pass). They're
 * told apart by battle overlay 167, which they load 0x180 lower to put an 18 x 18 type chart (Fairy added) at its start:
 * the overlay table's entry for it ([SIG_OFFSET], the table moved to cart 0x9EE00) holds [SIG_BLACK2] / [SIG_WHITE2]
 * (retail's 0x021998C0 / 0x02199900 sit in a table at 0x77800), so another build fails the check and reads as plain Black 2.
 *
 * They run as [GameKind.BLACK2] / [GameKind.WHITE2] on Black 2's reader with [GEN5_BLACK2_REDUX] / [GEN5_WHITE2_REDUX]
 * and, while [black2Redux], their own tables, all read from the player's cart by [NdsArt.readRedux] ([data]): the
 * species' types (Fairy is Gen 5's id 17, the app's 9: [type]), the moves' types, powers and categories (a dozen new moves
 * in old ones' slots), Gen 6's type chart, the machines (15 TMs teach other moves), the party icons' palettes, the item
 * icons' table and the zones' wild encounter rates. Their Classic and EVless patches change only the species' records
 * (`/a/0/1/6`, plus `/a/0/1/9` and `/a/0/9/2` in Classic EVless), so all four builds of each match and read their own.
 */
object Black2Redux {
    const val CODE_BLACK2 = "IREO"
    const val CODE_WHITE2 = "IRDO"
    const val REV = 0

    const val BLAZE_BLACK2_V1_4_1_SHA1 = "3a51c56e46280edc9bac1c30d0d1ab899b760076"
    const val BLAZE_BLACK2_V1_4_1_CLASSIC_SHA1 = "44ad04c37b38ab7c4867b5e5c448ba898b8c36be"
    const val BLAZE_BLACK2_V1_4_1_EVLESS_SHA1 = "4e7401bc9cc5564f7d7d3d9e6da184b54601fc6d"
    const val BLAZE_BLACK2_V1_4_1_CLASSIC_EVLESS_SHA1 = "74b2d4bda1820a7b6ee69bf8397b4eae93e5c07e"
    const val VOLT_WHITE2_V1_4_1_SHA1 = "5019476ed4241cfaa66306424a9ba5fd5bd1b053"
    const val VOLT_WHITE2_V1_4_1_CLASSIC_SHA1 = "7e00ac0c387f1db1af8c32ce6a130171457ccb76"
    const val VOLT_WHITE2_V1_4_1_EVLESS_SHA1 = "9cb100024fc19c0e932eac4a4b67b7beccdabb46"
    const val VOLT_WHITE2_V1_4_1_CLASSIC_EVLESS_SHA1 = "a11a10002df36ac48de98887c8f53490ef47c4ca"

    val BLAZE_BLACK2_SHA1S = listOf(BLAZE_BLACK2_V1_4_1_SHA1, BLAZE_BLACK2_V1_4_1_CLASSIC_SHA1,
        BLAZE_BLACK2_V1_4_1_EVLESS_SHA1, BLAZE_BLACK2_V1_4_1_CLASSIC_EVLESS_SHA1)
    val VOLT_WHITE2_SHA1S = listOf(VOLT_WHITE2_V1_4_1_SHA1, VOLT_WHITE2_V1_4_1_CLASSIC_SHA1,
        VOLT_WHITE2_V1_4_1_EVLESS_SHA1, VOLT_WHITE2_V1_4_1_CLASSIC_EVLESS_SHA1)

    /** The ARM9 overlay table's entry for overlay 167 (the table at 0x9EE00, 32 bytes an entry): its RAM address. */
    const val SIG_OFFSET = 0x9EE00L + 167 * 32 + 4
    const val SIG_BLACK2 = 0x02199740L
    const val SIG_WHITE2 = 0x02199780L

    /** Overlay 167 starts with the chart: 18 x 18 bytes by Gen 5's type ids (Fairy 17), 4 = 1x (0, 2, 4, 8). */
    const val CHART_TYPES = 18

    /** The ARM9 is stored unpacked at cart 0x4000 (retail's is packed); where its tables sit (White 2's 0x2C later). */
    private const val ARM9 = 0x4000L
    /** sTMHMMoves: TM01 .. TM95, HM01 .. HM06 (u16 moves). */
    private const val TM_MOVES = 0x8CC84L
    const val TM_MOVE_COUNT = 101
    /** The party icons' palette bytes, one an icon entry (low nibble its own, high its female icon's). */
    private const val ICON_PALETTES = 0x8C54CL
    const val ICON_PALETTES_SIZE = 756
    /** The item icons: (graphics, palette) u16 pairs in a/0/2/5, by item id. */
    private const val ITEM_ICONS = 0x8CD50L
    private const val WHITE2_SHIFT = 0x2CL

    fun tmMovesOffset(white2: Boolean) = ARM9 + TM_MOVES + if (white2) WHITE2_SHIFT else 0
    fun iconPalettesOffset(white2: Boolean) = ARM9 + ICON_PALETTES + if (white2) WHITE2_SHIFT else 0
    fun itemIconsOffset(white2: Boolean) = ARM9 + ITEM_ICONS + if (white2) WHITE2_SHIFT else 0

    /** Whether [read] (cart offset, length) is one of these builds. */
    fun matches(read: (Long, Int) -> ByteArray?): Boolean = which(read) != null

    /** Which of the two [read] is: [CODE_BLACK2] / [CODE_WHITE2], or null for anything else. */
    fun which(read: (Long, Int) -> ByteArray?): String? {
        val code = read(0x0C, 4)?.takeIf { it.size == 4 }?.let { String(it, Charsets.US_ASCII) } ?: return null
        val sig = when (code) {
            CODE_BLACK2 -> SIG_BLACK2
            CODE_WHITE2 -> SIG_WHITE2
            else -> return null
        }
        if (read(0x1E, 1)?.firstOrNull()?.toInt() != REV) return null
        val w = read(SIG_OFFSET, 4)?.takeIf { it.size == 4 } ?: return null
        return code.takeIf { u32le(w, 0) == sig }
    }

    /** [matches] on a plain ROM file. */
    fun matches(rom: File): Boolean = runCatching {
        RandomAccessFile(rom, "r").use { f ->
            matches { off, len -> if (off + len > f.length()) null else ByteArray(len).also { f.seek(off); f.readFully(it) } }
        }
    }.getOrDefault(false)

    /** A type id of these carts' -> the app's (Gen 3's): Gen 5's ([gen5Type]), Fairy (17) in "???"'s slot, 9. */
    fun type(t: Int): Int = gen5Type(t)

    /** Their tables from the player's cart ([NdsArt.readRedux]); null until read, or for another game. */
    class Data(
        /** By species id, the app's type ids (Fairy is 9). */
        val speciesTypes: Map<Int, SpeciesTypes>,
        /** By move id: the cart's names, types (the app's ids) and powers. */
        val moveData: Map<Int, MoveInfo>,
        /** By move id: 0 physical, 1 special, 2 status (the move's own). */
        val moveCategories: Map<Int, Int>,
        /** atkType * 100 + defType -> percent (the app's ids), pairs at 100 left out. */
        val typeChart: Map<Int, Int>,
        /** TM01 .. TM95, HM01 .. HM06: each machine's move. */
        val machines: IntArray,
        /** Each icon entry's palette (0-2; [ICON_PALETTES_SIZE] of them), as [iconPalettesBlack2]. */
        val iconPalettes: ByteArray,
        /** Each item's icon: graphics shl 16 or palette, as [itemIconsBlack2]. */
        val itemIcons: Map<Int, Int>,
        /** WILD ENCOUNTERS' cart rates by season (0 spring .. 3 winter): zone, rates 0-3, rates 4-6, ... as
         * [Gen5World.wildRateWords]. */
        val wildRates: List<IntArray>,
    ) {
        /** The rates for the device's season, as Black 2's world gives its own. */
        fun wildRateWords(): IntArray = wildRates.getOrNull((java.time.LocalDate.now().monthValue - 1) % 4) ?: IntArray(0)
    }

    @Volatile var data: Data? = null

    /** English type names by the app's ids: Gen 3's, Fairy in "???"'s place (the hacks' own type list never got its name). */
    val typeNames: Map<Int, String> = typeNamesGlazed
}
