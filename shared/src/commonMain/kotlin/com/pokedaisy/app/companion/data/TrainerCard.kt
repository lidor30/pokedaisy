package com.pokedaisy.app.companion.data

/** Which card a game draws: FireRed / LeafGreen's Kanto card, Emerald's Hoenn one, or the Kanto
 * card in Unbound's colours (see [TrainerCardArt]). */
/** Which card a game draws: FireRed's (Kanto, Unbound's purple one) or Emerald's (Hoenn and its hacks'). */
/** [lang]: a European cart's card (its own tiles, font and words, TrainerCardArt's localized styles); 'E' = English. */
enum class CardStyle(val hoenn: Boolean, val lang: Char = 'E') { KANTO(false), HOENN(true), UNBOUND(false), GLAZED(true), IMPERIUM(true), LEGACY(true), LAZARUS(true), SOULGOLD(true),
    RECHARGED_YELLOW(false), RECHARGED_YELLOW_V1_9(false),
    KANTO_DE(false, 'D'), KANTO_FR(false, 'F'), KANTO_IT(false, 'I'), KANTO_ES(false, 'S'),
    /** Italian / Spanish LeafGreen: their own back tilemap. */
    LEAFGREEN_IT(false, 'I'), LEAFGREEN_ES(false, 'S'),
    HOENN_DE(true, 'D'), HOENN_FR(true, 'F'), HOENN_IT(true, 'I'), HOENN_ES(true, 'S') }

/**
 * Where a game keeps what its TRAINER CARD shows - both decomps'
 * src/trainer_card.c (SetPlayerCardData) and include/global.h. Game stats
 * are u32s XOR'd with SaveBlock2.encryptionKey, like money.
 */
data class TrainerCardSave(
    val style: CardStyle,
    /** SaveBlock1.gameStats. */
    val gameStatsOff: Long,
    /** SaveBlock1.flags / .vars. */
    val flagsOff: Long,
    val varsOff: Long,
    val badgeFlag: Int,
    /** FLAG_SYS_POKEDEX_GET: the POKéDEX line only shows once the player has one. */
    val pokedexFlag: Int,
    /** IsNationalPokedexEnabled: SaveBlock2.pokedex.nationalMagic, VAR_NATIONAL_DEX and FLAG_SYS_NATIONAL_DEX all set. */
    val nationalMagicOff: Long,
    val nationalMagic: Int,
    val nationalVar: Int,
    val nationalVarValue: Int,
    val nationalFlag: Int,
    val dexFlags: DexFlags,
    /** The National Dex is always there (Unbound), counted up to [nationalCount]. */
    val nationalAlways: Boolean = false,
    val nationalCount: Int = 386,
    /** Before the National Dex: Kanto counts No. 1-151, Hoenn its own 202 in sHoennToNationalOrder. */
    val regionalDex: IntArray,
    /** Emerald's extra stars: the Battle Frontier symbols (FLAG_SYS_TOWER_SILVER, gold right after,
     * a pair per facility) and the museum's five contest paintings (SaveBlock1.contestWinners). */
    val frontierSymbolFlag: Int = -1,
    val museumWinnersOff: Long = -1,
    /** SaveBlock2.frontier.cardBattlePoints (Emerald). */
    val frontierBpOff: Long = -1,
    /** VAR_HOF_BRAG_STATE, the egg and link-win ones right after (FireRed's stickers). */
    val stickerVar: Int = -1,
    /** A flag worth one more star (Emerald Legacy's FLAG_CAUGHT_MEW: its fifth, on a black card); -1 = none. */
    val extraStarFlag: Int = -1,
    /** GetSetPokedexFlag reads the caught bitmap alone (no seen copies to agree with): Lazarus. */
    val caughtOnly: Boolean = false,
    /** The regional count and "all caught" check loop one entry past the dex: that entry is national 0, whose caught
     * bit is (0 - 1) & 0xFFFF - bit 7 of caught + 0x1FFF, out in the PC boxes (Lazarus). */
    val regionalStrayBit: Boolean = false,
    /** How many of the regional dex's last entries the "all caught" star leaves out (retail: JIRACHI and DEOXYS). */
    val allRegionalDrop: Int = 2,
    /** Stars drawn by a rule of the game's own, while the card's colour keeps retail's (Lazarus: entered the
     * HALL OF FAME + each of these flags + the frontier symbols); null = the stars drawn are the colour's. */
    val drawnStarFlags: List<Int>? = null,
    /** How many bytes of flags to read (enough for the highest flag used). */
    val flagBytes: Int = 0x12C,
    /** Regional entries the "all caught" star leaves out (SoulGold's legendaries). */
    val allRegionalSkip: Set<Int> = emptySet(),
    /** SoulGold's star for a Battle Frontier record: any of these SaveBlock2 u16s past 49. */
    val frontierRecordOffs: List<Long> = emptyList(),
    /** SoulGold's star for its achievements: at least [achievementsNeeded] of [achievementIds]' bits set at SaveBlock1
     * + [achievementBitsOff], counted only while the u16 at + [achievementMagicOff] reads 0xACE1; -1 = none. */
    val achievementBitsOff: Long = -1,
    val achievementMagicOff: Long = -1,
    val achievementIds: IntArray = IntArray(0),
    val achievementsNeeded: Int = 80,
    /** The National Dex is on with [nationalFlag] alone (Recharged Yellow: no magic, no var). */
    val nationalFlagOnly: Boolean = false,
    /** One more star for having caught every one of these (Recharged Yellow: 1-248 and 252-384). */
    val allCaughtStar: IntArray? = null,
    /** FireRed's UNION ROOM / BERRY CRUSH lines: only on a link partner's card in Recharged Yellow. */
    val linkLines: Boolean = true,
    /** Recharged Yellow's back: six Pokémon icons from vars [iconVar].. (mail species), tinted by [tintVar], their
     * tiles from [monIconTable] (the first 0x200 bytes) and colours from [monPaletteTable] (8-byte LZ77 records). */
    val iconVar: Int = -1,
    val tintVar: Int = -1,
    val monIconTable: Long = 0,
    val monPaletteTable: Long = 0,
)

/** pokeemerald's sHoennToNationalOrder: the Hoenn dex's national numbers, in its order. */
val HOENN_TO_NATIONAL = intArrayOf(
    252, 253, 254, 255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 267, 268, 269, 270, 271,
    272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282, 283, 284, 285, 286, 287, 288, 289, 63, 64,
    65, 290, 291, 292, 293, 294, 295, 296, 297, 118, 119, 129, 130, 298, 183, 184, 74, 75, 76, 299,
    300, 301, 41, 42, 169, 72, 73, 302, 303, 304, 305, 306, 66, 67, 68, 307, 308, 309, 310, 311,
    312, 81, 82, 100, 101, 313, 314, 43, 44, 45, 182, 84, 85, 315, 316, 317, 318, 319, 320, 321,
    322, 323, 218, 219, 324, 88, 89, 109, 110, 325, 326, 27, 28, 327, 227, 328, 329, 330, 331, 332,
    333, 334, 335, 336, 337, 338, 339, 340, 341, 342, 343, 344, 345, 346, 347, 348, 174, 39, 40, 349,
    350, 351, 120, 121, 352, 353, 354, 355, 356, 357, 358, 359, 37, 38, 172, 25, 26, 54, 55, 360,
    202, 177, 178, 203, 231, 232, 127, 214, 111, 112, 361, 362, 363, 364, 365, 366, 367, 368, 369, 222,
    170, 171, 370, 116, 117, 230, 371, 372, 373, 374, 375, 376, 377, 378, 379, 380, 381, 382, 383, 384,
    385, 386,
)

/**
 * Pokémon Unbound v2.1.1.1 (CFRU): FireRed's SaveBlock layout for everything the
 * card reads (name, ID, time, money, flags, game stats - checked on the
 * fixture save against Unbound's own card: Lidor, 06174, 0:25, 1 caught), with
 * CFRU's dex flags in SaveBlock1 and the National Dex always on.
 */
val TRAINER_CARD_UNBOUND = TRAINER_CARD_FIRERED.copy(
    style = CardStyle.UNBOUND,
    dexFlags = CFRU_DEX_FLAGS,
    nationalAlways = true,
    nationalCount = 905,
    stickerVar = -1,
)

/** Glazed's regional dex (its "HOENN": Tunod), national numbers in its order (ROM 0x0831DFB8). */
val GLAZED_REGIONAL_TO_NATIONAL = intArrayOf(
    247, 248, 249, 250, 251, 252, 253, 254, 255, 256, 257, 258, 259, 357, 358, 359, 146, 200, 319, 320,
    321, 322, 323, 324, 260, 261, 262, 263, 264, 265, 266, 380, 381, 268, 269, 360, 361, 362, 62, 63,
    64, 270, 271, 272, 346, 329, 330, 354, 147, 120, 121, 136, 137, 344, 190, 191, 73, 74, 75, 368,
    337, 338, 44, 45, 46, 71, 72, 273, 385, 275, 276, 277, 65, 66, 67, 214, 228, 278, 279, 384,
    383, 81, 82, 350, 348, 204, 221, 377, 378, 379, 372, 364, 365, 345, 341, 342, 334, 335, 382, 386,
    130, 133, 224, 225, 369, 86, 87, 107, 108, 328, 347, 28, 29, 280, 231, 336, 351, 353, 327, 343,
    150, 267, 284, 285, 356, 97, 106, 111, 333, 83, 198, 186, 286, 287, 288, 289, 41, 42, 43, 290,
    291, 292, 122, 123, 293, 294, 295, 349, 299, 115, 318, 296, 39, 40, 25, 26, 27, 53, 54, 208,
    209, 373, 374, 274, 234, 235, 134, 219, 109, 110, 297, 298, 98, 325, 326, 300, 301, 302, 206, 281,
    182, 183, 332, 117, 118, 119, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 314, 315, 316,
    317, 355,
)

/** Imperium's Hoenn dex (ROM 0x08715890, 214), national numbers in its order. */
val IMPERIUM_HOENN_TO_NATIONAL = intArrayOf(
    252, 253, 254, 255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 862, 265, 266, 267, 268, 269, 270,
    271, 272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282, 475, 283, 284, 285, 286, 287, 288, 289,
    63, 64, 65, 290, 291, 292, 293, 294, 295, 296, 297, 118, 119, 129, 130, 298, 183, 184, 74, 75,
    76, 299, 476, 300, 301, 41, 42, 169, 72, 73, 302, 303, 304, 305, 306, 66, 67, 68, 307, 308,
    309, 310, 311, 312, 81, 82, 462, 100, 101, 313, 314, 43, 44, 45, 182, 84, 85, 406, 315, 407,
    316, 317, 318, 319, 320, 321, 322, 323, 218, 219, 324, 88, 89, 109, 110, 325, 326, 27, 28, 327,
    227, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339, 340, 341, 342, 343, 344, 345, 346,
    347, 348, 174, 39, 40, 349, 350, 351, 120, 121, 352, 353, 354, 355, 356, 477, 357, 433, 358, 359,
    37, 38, 172, 25, 26, 54, 55, 360, 202, 177, 178, 203, 981, 231, 232, 127, 214, 111, 112, 464,
    361, 362, 478, 363, 364, 365, 366, 367, 368, 369, 222, 864, 170, 171, 370, 116, 117, 230, 371, 372,
    373, 374, 375, 376, 377, 378, 379, 380, 381, 382, 383, 384, 385, 386,
)

/**
 * Pokémon Glazed: retail Emerald's SetPlayerCardData byte for byte, so retail's offsets, with its
 * own regional order (ROM 0x0831DFB8, rewritten in place) and its own card art (CardStyle.GLAZED).
 */
val TRAINER_CARD_GLAZED = TRAINER_CARD_EMERALD.copy(style = CardStyle.GLAZED, regionalDex = GLAZED_REGIONAL_TO_NATIONAL)

/**
 * The European FireRed / LeafGreen / Emerald carts (German, French, Italian, Spanish): English's save, so English's offsets, and
 * their own card (CardStyle.KANTO_DE ...: its tiles, font and words from the cart, checked against the games' own cards).
 */
val TRAINER_CARD_FIRERED_DE = TRAINER_CARD_FIRERED.copy(style = CardStyle.KANTO_DE)
val TRAINER_CARD_FIRERED_FR = TRAINER_CARD_FIRERED.copy(style = CardStyle.KANTO_FR)
val TRAINER_CARD_FIRERED_IT = TRAINER_CARD_FIRERED.copy(style = CardStyle.KANTO_IT)
val TRAINER_CARD_FIRERED_ES = TRAINER_CARD_FIRERED.copy(style = CardStyle.KANTO_ES)
val TRAINER_CARD_LEAFGREEN_IT = TRAINER_CARD_FIRERED.copy(style = CardStyle.LEAFGREEN_IT)
val TRAINER_CARD_LEAFGREEN_ES = TRAINER_CARD_FIRERED.copy(style = CardStyle.LEAFGREEN_ES)
val TRAINER_CARD_EMERALD_DE = TRAINER_CARD_EMERALD.copy(style = CardStyle.HOENN_DE)
val TRAINER_CARD_EMERALD_FR = TRAINER_CARD_EMERALD.copy(style = CardStyle.HOENN_FR)
val TRAINER_CARD_EMERALD_IT = TRAINER_CARD_EMERALD.copy(style = CardStyle.HOENN_IT)
val TRAINER_CARD_EMERALD_ES = TRAINER_CARD_EMERALD.copy(style = CardStyle.HOENN_ES)

/** Emerald Legacy's Hoenn dex (ROM 0x083465CC, HOENN_DEX_COUNT 210): retail's 202, then eight it added. */
val LEGACY_HOENN_TO_NATIONAL = HOENN_TO_NATIONAL + intArrayOf(77, 78, 198, 215, 220, 221, 228, 229)

/**
 * Emerald Legacy v1.1.4: retail's card with SaveBlock1 grown (a 120-slot ITEMS pocket, 960 trainer flags: flags +0x13D8,
 * vars +0x1510, gameStats +0x1714, contestWinners +0x3008), the system flags 0x60 later (badges 0x8C7, POKéDEX 0x8C1,
 * National Dex 0x8F6, the frontier symbols 0x924) and SaveBlock2's frontier 0x14 longer (its Pyramid bag holds 13:
 * cardBattlePoints SB2 +0xECE) - offsetof / constants compiled with agbcc against its headers. Its HasAllHoennMons
 * leaves out its dex's last two (HOUNDOUR / HOUNDOOM, as the card's rule does), and CaughtMew adds a fifth star.
 */
val TRAINER_CARD_EMERALD_LEGACY = TRAINER_CARD_EMERALD.copy(
    style = CardStyle.LEGACY,
    gameStatsOff = 0x1714, flagsOff = 0x13D8, varsOff = 0x1510,
    badgeFlag = 0x8C7, pokedexFlag = 0x8C1, nationalFlag = 0x8F6,
    dexFlags = EMERALD_LEGACY_DEX_FLAGS,
    regionalDex = LEGACY_HOENN_TO_NATIONAL,
    frontierSymbolFlag = 0x924,
    museumWinnersOff = 0x3008 + 8 * 32,
    frontierBpOff = 0xECE,
    extraStarFlag = 0x1CA,
)

/**
 * Emerald Imperium (expansion 1.10): the GF header names SaveBlock1's flags (+0x19C4) and vars
 * (+0x1AF0); gameStats follow the vars (+0x1CF0: steps and battles counted up in the fixtures);
 * the card code's literals give the frontier symbols (0x8C4), FLAG_SYS_POKEDEX_GET (0x861) and
 * the badges (0x867); the museum's winners sit at +0x2AA4 (the default winners' data), battle
 * points at SB2+0x98E. Each checked headless by poking it and opening the game's card.
 */
val TRAINER_CARD_IMPERIUM = TrainerCardSave(
    style = CardStyle.IMPERIUM,
    gameStatsOff = 0x1CF0, flagsOff = 0x19C4, varsOff = 0x1AF0,
    badgeFlag = 0x867, pokedexFlag = 0x861,
    nationalMagicOff = 0x18 + 2, nationalMagic = 0xDA, nationalVar = 0x4046, nationalVarValue = 0x302, nationalFlag = 0x896,
    dexFlags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x2F58, 0x2FD9, 129),
    nationalCount = 1025,
    regionalDex = IMPERIUM_HOENN_TO_NATIONAL,
    frontierSymbolFlag = 0x8C4,
    museumWinnersOff = 0x2AA4 + 8 * 32,
    frontierBpOff = 0x98E,
)

/** Lazarus's ILIOS dex (ROM 0x088DFC5C, 430), national numbers in its order; none is skipped as mythical. */
val LAZARUS_REGIONAL_TO_NATIONAL = intArrayOf(
    723, 724, 725, 726, 727, 728, 729, 730, 731, 650, 651, 652, 653, 654, 655, 656, 657, 658, 907, 908,
    909, 910, 911, 912, 913, 914, 915, 511, 512, 513, 514, 515, 516, 506, 507, 508, 732, 733, 734, 580,
    581, 187, 188, 189, 290, 291, 292, 737, 738, 739, 46, 47, 231, 232, 873, 874, 104, 105, 750, 751,
    179, 180, 181, 841, 842, 843, 1012, 1020, 743, 744, 88, 89, 265, 266, 267, 268, 269, 357, 280, 281,
    282, 475, 133, 134, 135, 136, 196, 197, 470, 471, 701, 673, 674, 198, 430, 742, 27, 28, 551, 552,
    553, 299, 476, 303, 745, 746, 629, 630, 527, 528, 304, 305, 306, 532, 533, 534, 951, 60, 61, 62,
    186, 711, 712, 285, 286, 214, 353, 354, 190, 424, 762, 763, 764, 194, 195, 981, 949, 950, 522, 523,
    163, 164, 619, 620, 956, 957, 37, 38, 680, 681, 682, 557, 558, 775, 92, 93, 94, 343, 344, 758,
    759, 964, 965, 175, 176, 468, 420, 421, 228, 229, 138, 139, 140, 141, 451, 452, 167, 168, 95, 208,
    776, 278, 279, 234, 900, 361, 362, 478, 363, 364, 365, 86, 87, 436, 437, 879, 880, 570, 571, 215,
    461, 904, 697, 698, 699, 700, 52, 53, 864, 417, 152, 153, 154, 155, 156, 157, 158, 159, 160, 783,
    784, 785, 702, 851, 852, 408, 409, 410, 411, 442, 836, 837, 922, 923, 924, 945, 946, 418, 419, 434,
    435, 941, 942, 128, 968, 768, 769, 172, 25, 26, 779, 77, 78, 222, 865, 456, 457, 320, 321, 72,
    73, 693, 694, 123, 212, 901, 760, 761, 886, 887, 888, 778, 740, 741, 345, 346, 347, 348, 703, 441,
    307, 308, 96, 97, 23, 24, 550, 903, 675, 676, 691, 692, 574, 575, 576, 577, 578, 579, 607, 608,
    609, 936, 937, 938, 170, 171, 90, 91, 366, 367, 368, 670, 671, 672, 174, 39, 40, 695, 696, 871,
    328, 329, 330, 84, 85, 958, 959, 960, 559, 560, 207, 472, 324, 322, 323, 369, 339, 340, 517, 518,
    352, 129, 130, 754, 755, 766, 767, 263, 264, 863, 705, 706, 707, 780, 860, 861, 862, 333, 334, 56,
    57, 980, 100, 101, 131, 203, 982, 69, 70, 71, 81, 82, 462, 613, 614, 58, 59, 54, 55, 193,
    469, 142, 223, 224, 627, 628, 667, 668, 669, 177, 178, 459, 460, 355, 356, 477, 220, 221, 473, 341,
    342, 765, 201, 564, 565, 566, 567, 881, 882, 883, 884, 773, 774, 246, 247, 248, 782, 147, 148, 149,
    243, 1022, 244, 1021, 245, 1010, 382, 383, 384, 1018,
)

/**
 * Pokémon Lazarus v2.0: Emerald's Hoenn card code with its own art (CardStyle.LAZARUS) - found from headless dumps of the
 * open card and its code (the card-data function 0x08228928): gameStats SaveBlock1 +0x1618, flags +0x12E8, vars +0x1414,
 * the museum's paintings +0x3014, caught flags +0x3329 (read alone), the National Dex magic at SaveBlock2 +0x1E, battle
 * points SaveBlock2 +0xEBE. Its colour follows retail's stars; the stars drawn are its own count (0x082288B0): entered
 * the HALL OF FAME, flags 0x29 and 0x4A6, the frontier symbols. Each checked by poking it and opening the game's card.
 */
val TRAINER_CARD_LAZARUS = TrainerCardSave(
    style = CardStyle.LAZARUS,
    gameStatsOff = 0x1618, flagsOff = 0x12E8, varsOff = 0x1414,
    badgeFlag = 0x867, pokedexFlag = 0x861,
    nationalMagicOff = 0x1E, nationalMagic = 0xDA, nationalVar = 0x4046, nationalVarValue = 0x302, nationalFlag = 0x896,
    dexFlags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x32A8, 0x3329, 0x81),
    nationalCount = 1028,
    regionalDex = LAZARUS_REGIONAL_TO_NATIONAL,
    frontierSymbolFlag = 0x8C4,
    museumWinnersOff = 0x3014 - 8,
    frontierBpOff = 0xEBE,
    caughtOnly = true,
    regionalStrayBit = true,
    allRegionalDrop = 0,
    drawnStarFlags = listOf(0x29, 0x4A6),
)

/** SoulGold's regional dex (ROM 0x08F03DD8 in v1.1.4, u32s; the same 702 in every build), national numbers in its order. */
val SOULGOLD_REGIONAL_TO_NATIONAL = intArrayOf(
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
    23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42,
    43, 44, 45, 50, 51, 52, 53, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68,
    69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 86, 87, 88, 89, 90,
    91, 92, 93, 94, 95, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114,
    115, 116, 117, 120, 121, 122, 123, 125, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137,
    140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159,
    160, 163, 164, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183,
    184, 185, 186, 187, 188, 189, 190, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 206, 207,
    208, 211, 212, 214, 215, 216, 217, 220, 221, 222, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234,
    236, 237, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255, 256,
    257, 258, 259, 260, 263, 264, 278, 279, 280, 281, 282, 285, 286, 290, 291, 292, 296, 297, 298, 299,
    302, 303, 304, 305, 306, 307, 308, 309, 310, 315, 318, 319, 322, 323, 324, 328, 329, 330, 331, 332,
    333, 334, 339, 340, 343, 344, 345, 346, 349, 350, 353, 354, 355, 356, 357, 358, 359, 360, 361, 362,
    363, 364, 365, 371, 372, 373, 374, 375, 376, 377, 378, 379, 380, 381, 382, 383, 384, 385, 386, 387,
    388, 389, 390, 391, 392, 393, 394, 395, 396, 397, 398, 403, 404, 405, 406, 407, 417, 418, 419, 422,
    423, 424, 425, 426, 427, 428, 429, 430, 433, 438, 439, 440, 442, 443, 444, 445, 446, 447, 448, 449,
    450, 451, 452, 456, 457, 458, 459, 460, 461, 462, 463, 464, 465, 466, 467, 468, 469, 470, 471, 472,
    473, 474, 475, 476, 477, 478, 479, 480, 481, 482, 483, 484, 485, 486, 487, 488, 489, 490, 491, 492,
    493, 494, 495, 496, 497, 498, 499, 500, 501, 502, 503, 529, 530, 531, 540, 541, 542, 543, 544, 545,
    546, 547, 548, 549, 550, 551, 552, 553, 554, 555, 556, 559, 560, 562, 563, 566, 567, 570, 571, 572,
    573, 577, 578, 579, 587, 595, 596, 597, 598, 602, 603, 604, 607, 608, 609, 610, 611, 612, 618, 619,
    620, 622, 623, 624, 625, 627, 628, 629, 630, 633, 634, 635, 636, 637, 638, 639, 640, 641, 642, 643,
    644, 645, 646, 647, 648, 649, 650, 651, 652, 653, 654, 655, 656, 657, 658, 661, 662, 663, 667, 668,
    669, 670, 671, 672, 673, 677, 678, 679, 680, 681, 686, 687, 688, 689, 690, 691, 692, 693, 696, 697,
    698, 699, 700, 701, 704, 705, 706, 707, 708, 709, 712, 713, 714, 715, 716, 717, 718, 719, 720, 721,
    722, 723, 724, 725, 726, 727, 728, 729, 730, 736, 737, 738, 739, 740, 741, 742, 743, 744, 745, 747,
    748, 767, 768, 771, 778, 780, 781, 782, 783, 784, 785, 786, 787, 788, 789, 790, 791, 792, 800, 801,
    802, 803, 804, 807, 808, 809, 810, 811, 812, 813, 814, 815, 816, 817, 818, 821, 822, 823, 840, 841,
    842, 843, 844, 848, 849, 850, 851, 854, 855, 859, 860, 861, 862, 863, 864, 865, 866, 867, 870, 876,
    884, 885, 886, 887, 888, 889, 890, 891, 892, 893, 894, 895, 896, 897, 898, 899, 900, 901, 902, 903,
    904, 905, 906, 907, 908, 909, 910, 911, 912, 913, 914, 921, 922, 923, 924, 925, 932, 933, 934, 935,
    936, 937, 940, 941, 951, 952, 955, 956, 957, 958, 959, 963, 964, 967, 969, 970, 973, 977, 978, 979,
    980, 981, 982, 983, 984, 985, 986, 987, 988, 989, 990, 991, 992, 993, 994, 995, 996, 997, 998, 999,
    1000, 1002, 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1016, 1017, 1018, 1019, 1020, 1021, 1022, 1023,
    1024, 1025,
)

/** The regional entries its "all caught" star leaves out: gSpeciesInfo +0x9D bits 0-2 (legendary, mythical, ultra
 * beast) in v1.1.4. */
val SOULGOLD_CARD_SKIPPED = intArrayOf(
    144, 145, 146, 150, 151, 243, 244, 245, 249, 250, 251, 377, 378, 379, 380, 381, 382, 383, 384, 385,
    386, 480, 481, 482, 483, 484, 485, 486, 487, 488, 489, 490, 491, 492, 493, 494, 638, 639, 640, 641,
    642, 643, 644, 645, 646, 647, 648, 649, 716, 717, 718, 719, 720, 721, 785, 786, 787, 788, 789, 790,
    791, 792, 800, 801, 802, 807, 808, 809, 888, 889, 890, 891, 892, 893, 894, 895, 896, 897, 898, 905,
    1002, 1004, 1007, 1008, 1016, 1017, 1024, 1025,
)

/** Its 120 achievements' bit ids, in its table's order (ROM 0x083F0578 in v1.1.4, the same in every build). */
val SOULGOLD_ACHIEVEMENT_IDS = intArrayOf(
    0, 1, 2, 6, 49, 13, 33, 34, 35, 36, 37, 38, 39, 40, 51, 102, 103, 104, 68, 3,
    4, 5, 7, 8, 15, 16, 17, 18, 19, 20, 21, 12, 107, 98, 97, 100, 71, 92, 72, 74,
    75, 93, 94, 95, 96, 9, 10, 11, 22, 23, 24, 25, 26, 27, 28, 29, 30, 119, 120, 121,
    122, 41, 42, 43, 44, 45, 46, 47, 48, 50, 56, 70, 53, 55, 54, 76, 77, 80, 79, 78,
    31, 32, 52, 59, 57, 58, 105, 115, 116, 61, 83, 66, 65, 67, 85, 86, 118, 63, 60, 87,
    73, 88, 89, 69, 109, 110, 106, 111, 117, 108, 113, 99, 112, 123, 101, 114, 62, 64, 124, 90,
)

/**
 * SoulGold v1.1.4 (and its v1.2 builds: the same save, the card code moved): expansion's trainer_card.c on Emerald's
 * Hoenn layout with Johto art (CardStyle.SOULGOLD), from headless dumps of the open card and its code (SetPlayerCardData
 * 0x0826A9FC, the stars 0x0826A934): gameStats SaveBlock1 +0x2144, flags +0x1898, vars +0x1B42, caught flags +0x3279
 * (read alone), the National Dex magic at SaveBlock2 +0x22, battle points SaveBlock2 +0xA96. Its stars (and card
 * colour): entered the HALL OF FAME, every regional entry but the legendaries caught, a Battle Frontier record over 49,
 * 80 of its 120 achievements - no museum or frontier symbols. Each checked by poking it and reopening the game's card.
 */
val TRAINER_CARD_SOULGOLD = TrainerCardSave(
    style = CardStyle.SOULGOLD,
    gameStatsOff = 0x2144, flagsOff = 0x1898, varsOff = 0x1B42,
    badgeFlag = 0x993, pokedexFlag = 0x98D,
    nationalMagicOff = 0x22, nationalMagic = 0xDA, nationalVar = 0x4046, nationalVarValue = 0x302, nationalFlag = 0x9C2,
    dexFlags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x31F8, 0x3279, 0x81),
    nationalCount = 1025,
    regionalDex = SOULGOLD_REGIONAL_TO_NATIONAL,
    frontierBpOff = 0xA96,
    caughtOnly = true,
    allRegionalDrop = 0,
    allRegionalSkip = SOULGOLD_CARD_SKIPPED.toSet(),
    flagBytes = 0x2AA,
    frontierRecordOffs = List(16) { 0x8B4L + 2 * it } + List(8) { 0x9B6L + 2 * it },
    achievementBitsOff = 0x346C, achievementMagicOff = 0x3482, achievementIds = SOULGOLD_ACHIEVEMENT_IDS,
)

/** The v1.2 builds: their gSpeciesInfo marks six more of the regional dex legendary (1009, 1010, 1020-1023). */
val TRAINER_CARD_SOULGOLD_V1_2 = TRAINER_CARD_SOULGOLD.copy(
    allRegionalSkip = SOULGOLD_CARD_SKIPPED.toSet() + setOf(1009, 1010, 1020, 1021, 1022, 1023),
)

/**
 * Recharged Yellow v1.8.0: FireRed's card art under card code of its own (CardStyle.RECHARGED_YELLOW), from headless dumps
 * of the open card and its code (SetPlayerCardData 0x081C4D9C): game stats SaveBlock1 +0xB50 and money +0x29C, neither
 * encrypted, flags +0xEFB, vars +0x1028, badges from flag 0x880, the National Dex on with flag 0x8A0 alone, caught flags
 * SaveBlock2 +0x28 (read alone). Its stars: the HALL OF FAME, all 37 of its achievements (bits at SaveBlock1 +0x1284),
 * every Kanto Pokémon (1-151), and 1-248 + 252-384. The back shows FireRed's stickers (vars 0x4049..) and six Pokémon
 * icons (vars 0x4043.., tint 0x4042). Each checked by poking it and opening the game's card.
 */
val TRAINER_CARD_RECHARGED_YELLOW_V1_8_0 = TrainerCardSave(
    style = CardStyle.RECHARGED_YELLOW,
    gameStatsOff = 0xB50, flagsOff = 0xEFB, varsOff = 0x1028,
    badgeFlag = 0x880, pokedexFlag = 0x861,
    nationalMagicOff = 0, nationalMagic = 0, nationalVar = 0x4000, nationalVarValue = 0, nationalFlag = 0x8A0,
    nationalFlagOnly = true,
    dexFlags = DexFlags(DexFlagBlock.SAVE_BLOCK_2, 0x5C, 0x28, 0x34),
    caughtOnly = true,
    regionalDex = IntArray(151) { it + 1 },
    allRegionalDrop = 0,
    allCaughtStar = ((1..248) + (252..384)).toIntArray(),
    achievementBitsOff = 0x1284, achievementIds = IntArray(37) { it }, achievementsNeeded = 37,
    stickerVar = 0x4049,
    linkLines = false,
    iconVar = 0x4043, tintVar = 0x4042, monIconTable = 0x0886B110L, monPaletteTable = 0x0864D848L,
)

/** v1.9.0: the same card code and save; its icon tables moved, and its text palette's red is lighter. */
val TRAINER_CARD_RECHARGED_YELLOW_V1_9_0 = TRAINER_CARD_RECHARGED_YELLOW_V1_8_0.copy(
    style = CardStyle.RECHARGED_YELLOW_V1_9, monIconTable = 0x0886ABD0L, monPaletteTable = 0x0864A95CL,
)
val TRAINER_CARD_RECHARGED_YELLOW_V1_9_4 = TRAINER_CARD_RECHARGED_YELLOW_V1_9_0.copy(monIconTable = 0x0894ABD4L, monPaletteTable = 0x0872A8D8L)
val TRAINER_CARD_RECHARGED_YELLOW_V1_9_7 = TRAINER_CARD_RECHARGED_YELLOW_V1_9_0.copy(monIconTable = 0x089739E4L, monPaletteTable = 0x08750978L)

/** A Pokémon icon on Recharged Yellow's card back: its first frame's 16 tiles and its 16 colours (ARGB). */
class CardIcon(val tiles: ByteArray, val palette: IntArray)

/** What the card prints, as the game's SetPlayerCardData gathers it. */
data class TrainerCardInfo(
    val style: CardStyle,
    /** SaveBlock2.playerName in the game's own encoding (up to 7 bytes). */
    val name: List<Int>,
    val female: Boolean,
    val trainerId: Int,
    val hours: Int,
    val minutes: Int,
    val money: Long,
    /** Caught count (regional until the National Dex); null = no POKéDEX yet. */
    val dexCaught: Int?,
    /** Bit i = badge i+1. */
    val badges: Int,
    val stars: Int,
    /** h << 16 | m << 8 | s of the first Hall of Fame entry; 0 = none. */
    val hofDebut: Int,
    val linkWins: Int = 0,
    val linkLosses: Int = 0,
    val trades: Int = 0,
    val unionRoom: Int = 0,
    val berryCrush: Int = 0,
    val linkContests: Int = 0,
    val linkPokeblocks: Int = 0,
    val battlePoints: Int = 0,
    /** FireRed's Sticker Man stickers (VAR_HOF / EGG / LINK_WIN_BRAG_STATE): a level 1-4 each, 0 = none. */
    val stickers: List<Int> = emptyList(),
    /** The stars drawn, where a game counts them its own way ([TrainerCardSave.drawnStarFlags]); [stars] picks the colour. */
    val drawnStars: Int = stars,
    /** Recharged Yellow's six icons on the back (null = an empty spot). */
    val backIcons: List<CardIcon?> = emptyList(),
) {
    val nameText: String get() = Gen3Text.decode(ByteArray(name.size) { name[it].toByte() })
}

private const val STAT_FIRST_HOF_PLAY_TIME = 1
private const val STAT_ENTERED_HOF = 10
private const val STAT_POKEMON_TRADES = 21
private const val STAT_LINK_BATTLE_WINS = 23
private const val STAT_LINK_BATTLE_LOSSES = 24
private const val STAT_POKEBLOCKS_WITH_FRIENDS = 34
private const val STAT_WON_LINK_CONTEST = 35
private const val STAT_UNION_ROOM_BATTLES = 50
private const val STAT_BERRY_CRUSH_POINTS = 51

/** The card for the save [cfg] points at, or null without a [NativeConfig.trainerCard] layout or a loaded save. */
fun readTrainerCard(c: MemoryReader, cfg: NativeConfig): TrainerCardInfo? = runCatching {
    val s = cfg.trainerCard ?: return null
    val sb1 = saveBlock1(c, cfg)
    val sb2 = saveBlock2(c, cfg)
    if (sb1 !in 0x02000000L until 0x04000000L || sb2 !in 0x02000000L until 0x04000000L) return null
    val head = c.readCoreMemory(sb2, 0x12)
    // No encryption key (Recharged Yellow): the stats are kept as they are.
    val key = if (cfg.encryptionKeyOff < 0) 0L else u32le(c.readCoreMemory(sb2 + cfg.encryptionKeyOff, 4), 0)
    val statBytes = c.readCoreMemory(sb1 + s.gameStatsOff, 4 * (STAT_BERRY_CRUSH_POINTS + 1))
    fun stat(id: Int) = (u32le(statBytes, id * 4) xor key)
    fun capped(id: Int, max: Long) = minOf(stat(id), max).toInt()
    val flags = c.readCoreMemory(sb1 + s.flagsOff, s.flagBytes)
    fun flag(n: Int) = (flags[n / 8].toInt() shr (n % 8)) and 1 != 0
    fun variable(id: Int) = u16le(c.readCoreMemory(sb1 + s.varsOff + (id - 0x4000) * 2, 2), 0)

    val caught = readCaught(c, sb1, sb2, s.dexFlags, s.caughtOnly, s.regionalStrayBit)
    val national = s.nationalAlways || (s.nationalFlagOnly && flag(s.nationalFlag)) ||
        (!s.nationalFlagOnly && (c.readCoreMemory(sb2 + s.nationalMagicOff, 1)[0].toInt() and 0xFF) == s.nationalMagic &&
            variable(s.nationalVar) == s.nationalVarValue && flag(s.nationalFlag))
    val stray = if (s.regionalStrayBit && caught(0)) 1 else 0
    val dexCaught = if (national) (1..s.nationalCount).count { caught(it) } else s.regionalDex.count { caught(it) } + stray

    // GAME_STAT_FIRST_HOF_PLAY_TIME counts only once ENTERED_HOF is set; hours cap at 999:59:59.
    var hof = if (stat(STAT_ENTERED_HOF) != 0L) stat(STAT_FIRST_HOF_PLAY_TIME).toInt() else 0
    if ((hof ushr 16) > 999) hof = (999 shl 16) or (59 shl 8) or 59
    // HasAllHoennMons: the game's own Hoenn dex minus its last two (Jirachi and Deoxys in retail).
    val allHoenn = s.regionalDex.take(s.regionalDex.size - s.allRegionalDrop).all { it in s.allRegionalSkip || caught(it) } &&
        (!s.regionalStrayBit || caught(0))
    var stars = (if (hof != 0) 1 else 0) + (if (allHoenn) 1 else 0)
    if (s.museumWinnersOff >= 0) {
        val winners = c.readCoreMemory(sb1 + s.museumWinnersOff, 5 * 32)
        if ((0 until 5).all { u16le(winners, it * 32 + 8) != 0 }) stars++
    }
    val symbols = s.frontierSymbolFlag >= 0 && (0 until 7).all { flag(s.frontierSymbolFlag + 2 * it) && flag(s.frontierSymbolFlag + 2 * it + 1) }
    if (symbols) stars++
    if (s.extraStarFlag >= 0 && flag(s.extraStarFlag)) stars++
    if (s.frontierRecordOffs.isNotEmpty()) {
        val first = s.frontierRecordOffs.min()
        val records = c.readCoreMemory(sb2 + first, (s.frontierRecordOffs.max() - first + 2).toInt())
        if (s.frontierRecordOffs.any { u16le(records, (it - first).toInt()) > 49 }) stars++
    }
    s.allCaughtStar?.let { all -> if (all.all { caught(it) }) stars++ }
    if (s.achievementBitsOff >= 0 && (s.achievementMagicOff < 0 || u16le(c.readCoreMemory(sb1 + s.achievementMagicOff, 2), 0) == 0xACE1)) {
        val bits = c.readCoreMemory(sb1 + s.achievementBitsOff, (s.achievementIds.max() / 8) + 1)
        if (s.achievementIds.count { (bits[it / 8].toInt() shr (it % 8)) and 1 != 0 } >= s.achievementsNeeded) stars++
    }
    val drawn = s.drawnStarFlags?.let { f -> (if (stat(STAT_ENTERED_HOF) != 0L) 1 else 0) + f.count(::flag) + (if (symbols) 1 else 0) } ?: stars

    val name = (0 until 7).map { head[it].toInt() and 0xFF }.takeWhile { it != 0xFF }
    TrainerCardInfo(
        style = s.style,
        name = name,
        female = head[8].toInt() != 0,
        trainerId = u16le(head, 0x0A),
        hours = minOf(u16le(head, 0x0E), 999),
        minutes = minOf(head[0x10].toInt() and 0xFF, 59),
        money = readMoney(c, cfg) ?: 0L,
        dexCaught = dexCaught.takeIf { flag(s.pokedexFlag) },
        badges = (0 until 8).fold(0) { acc, i -> if (flag(s.badgeFlag + i)) acc or (1 shl i) else acc },
        stars = stars,
        hofDebut = hof,
        linkWins = capped(STAT_LINK_BATTLE_WINS, 9999),
        linkLosses = capped(STAT_LINK_BATTLE_LOSSES, 9999),
        trades = capped(STAT_POKEMON_TRADES, 0xFFFF),
        unionRoom = if (!s.style.hoenn && s.linkLines) capped(STAT_UNION_ROOM_BATTLES, 0xFFFF) else 0,
        berryCrush = if (!s.style.hoenn && s.linkLines) capped(STAT_BERRY_CRUSH_POINTS, 0xFFFF) else 0,
        linkContests = if (s.style.hoenn) capped(STAT_WON_LINK_CONTEST, 999) else 0,
        linkPokeblocks = if (s.style.hoenn) capped(STAT_POKEBLOCKS_WITH_FRIENDS, 0xFFFF) else 0,
        battlePoints = if (s.frontierBpOff >= 0) u16le(c.readCoreMemory(sb2 + s.frontierBpOff, 2), 0) else 0,
        stickers = if (s.stickerVar >= 0) (0 until 3).map { variable(s.stickerVar + it) } else emptyList(),
        drawnStars = drawn,
        backIcons = if (s.iconVar >= 0) (0 until 6).map { cardIcon(c, s, variable(s.iconVar + it), variable(s.tintVar)) } else emptyList(),
    )
}.getOrNull()

/** Recharged Yellow's LoadMonIconGfx: [mail] species (MailSpeciesToIconSpecies: 30000 + letter is an UNOWN form,
 * past 412 the "?" icon), its icon's first frame from the ROM and its sprite palette, [tint]ed (1 black, 2 pink, 3 sepia:
 * FireRed's palette.c tones); null for an empty spot. */
private fun cardIcon(c: MemoryReader, s: TrainerCardSave, mail: Int, tint: Int): CardIcon? = runCatching {
    if (mail == 0) return null
    val species = when {
        mail - 30000 in 0..27 -> if (mail == 30000) 201 else 412 + (mail - 30000)
        mail > 412 -> 260
        else -> mail
    }
    val tiles = c.readCoreMemory(u32le(c.readCoreMemory(s.monIconTable + 4L * species, 4), 0), 0x200)
    val packed = c.readCoreMemory(u32le(c.readCoreMemory(s.monPaletteTable + 8L * species, 4), 0), 0x40)
    val raw = RomArt.lz77(packed, 0, 32) ?: return null
    val tones = when (tint) { 1 -> intArrayOf(0, 0, 0); 2 -> intArrayOf(500, 330, 310); 3 -> intArrayOf(307, 256, 240); else -> null }
    val tinted = ByteArray(32)
    for (i in 0 until 16) {
        var v = (raw[2 * i].toInt() and 0xFF) or ((raw[2 * i + 1].toInt() and 0xFF) shl 8)
        if (tones != null) {
            val gray = ((v and 31) * 76 + ((v shr 5) and 31) * 151 + ((v shr 10) and 31) * 29) shr 8
            val (r, g, b) = tones.map { ((it * gray) and 0xFFFF) shr 8 }
            v = minOf(r, 31) or ((if (tint == 3) g else minOf(g, 31)) shl 5) or ((if (tint == 3) b else minOf(b, 31)) shl 10)
        }
        tinted[2 * i] = v.toByte()
        tinted[2 * i + 1] = (v shr 8).toByte()
    }
    CardIcon(tiles, RomArt.palette(tinted))
}.getOrNull()

/** GetSetPokedexFlag(n, FLAG_GET_CAUGHT): owned, and seen in all three copies ([caughtOnly]: owned alone). With
 * [stray], n = 0 reads the bit a one-past-the-end loop reaches ([TrainerCardSave.regionalStrayBit]). */
private fun readCaught(c: MemoryReader, sb1: Long, sb2: Long, f: DexFlags, caughtOnly: Boolean = false, stray: Boolean = false): (Int) -> Boolean {
    val base = if (f.block == DexFlagBlock.SAVE_BLOCK_1) sb1 else sb2
    val owned = c.readCoreMemory(base + f.caught, f.bytes)
    val seen = if (caughtOnly) emptyList() else
        listOf(c.readCoreMemory(base + f.seen, f.bytes)) + f.seenCopies.map { c.readCoreMemory(sb1 + it, f.bytes) }
    val strayBit = stray && (c.readCoreMemory(base + f.caught + 0x1FFF, 1)[0].toInt() shr 7) and 1 != 0
    fun bit(b: ByteArray, n: Int) = (b[(n - 1) / 8].toInt() shr ((n - 1) % 8)) and 1 != 0
    return { n -> if (n == 0) strayBit else n in 1..f.bytes * 8 && bit(owned, n) && seen.all { bit(it, n) } }
}

