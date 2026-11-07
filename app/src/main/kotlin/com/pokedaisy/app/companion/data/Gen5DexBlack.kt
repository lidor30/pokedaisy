package com.pokedaisy.app.companion.data

/**
 * Each machine's move in the order Black's personal records keep their bits: TM01..TM95, then HM01..HM06. The cart's
 * own table (ARM9 0x0209EAA0, US / European rev 0) lists TM01..TM92, the six HMs, then TM93..TM95; NdsArtBlackTest
 * checks this against it.
 */
val tmMovesBlack: IntArray = intArrayOf(
    468, 337, 473, 347, 46, 92, 258, 339, 474, 237, 241, 269, 58, 59, 63, 113,
    182, 240, 477, 219, 218, 76, 479, 85, 87, 89, 216, 91, 94, 247, 280, 104,
    115, 482, 53, 188, 201, 126, 317, 332, 259, 263, 488, 156, 213, 168, 490, 496,
    497, 315, 502, 411, 412, 206, 503, 374, 451, 507, 510, 511, 261, 512, 373, 153,
    421, 371, 514, 416, 397, 148, 444, 521, 86, 360, 14, 522, 244, 523, 524, 157,
    404, 525, 526, 398, 138, 447, 207, 365, 369, 164, 430, 433, 528, 249, 555, 15,
    19, 57, 70, 127, 291,
)

/** Black's DEX pages and front pictures (NdsArt's [NdsArt.BLACK_DEX], [NdsArt.BLACK_FRONT]): 649 species, Gen 5's
 * evolution methods, 95 TMs. */
val GEN5_DEX_BLACK = Gen4DexTables(NdsArt.BLACK_DEX, NdsArt.BLACK_FRONT, tmMovesBlack, tmCount = 95, lastSpecies = 649, gen5 = true)

/** Black's dex: Unova's 156 (VICTINI first; the cart's order, [Gen4Dex.regionalOrder]) and the National Dex's 649,
 * in feet and pounds as the US game prints its pages. The list numbers its entries from 1, the game from 000. */
val POKEDEX_BLACK = PokedexTables(
    entries = 0, frontPics = 0, palettes = 0, speciesInfo = 0, speciesToNational = 0, abilityNames = 0, footprints = 0,
    speciesCount = 650, nationalCount = 649, regionalCount = 156, regionName = "UNOVA",
    gen4 = GEN5_DEX_BLACK, categorySuffix = "Pokémon",
)

/** White's: Black's in every way but the pages' descriptions ([NdsArt.WHITE_DEX]). */
val GEN5_DEX_WHITE = Gen4DexTables(NdsArt.WHITE_DEX, NdsArt.BLACK_FRONT, tmMovesBlack, tmCount = 95, lastSpecies = 649, gen5 = true)
val POKEDEX_WHITE = POKEDEX_BLACK.copy(gen4 = GEN5_DEX_WHITE)

/** Black 2's: its own pages and pictures from its cart ([NdsArt.BLACK2_DEX], [NdsArt.BLACK2_FRONT]: Black's machines and
 * evolution methods) and its Unova Dex of 301 (VICTINI first, older POKéMON among them: [unovaDexBlack2]). */
val GEN5_DEX_BLACK2 = Gen4DexTables(NdsArt.BLACK2_DEX, NdsArt.BLACK2_FRONT, tmMovesBlack, tmCount = 95, lastSpecies = 649, gen5 = true)
val POKEDEX_BLACK2 = POKEDEX_BLACK.copy(regionalCount = 301, gen4 = GEN5_DEX_BLACK2)

/** White 2's: Black 2's (the two carts' text, pictures and personal records are the same files). */
val POKEDEX_WHITE2 = POKEDEX_BLACK2
