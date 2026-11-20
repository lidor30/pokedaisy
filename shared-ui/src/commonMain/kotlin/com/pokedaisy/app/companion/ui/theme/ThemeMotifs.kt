package com.pokedaisy.app.companion.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A theme's own picture ([ThemeSpec.motif]): 16x16 pixel art of ours, floating over the Library's backdrop where the
 * PokeDaisy mark does in the default theme (DaisyBackdrop), and on the limited-time theme's popup.
 */
class ThemeMotif(val rows: List<String>, val palette: Map<Char, Color>)

object ThemeMotifs {
    /** CHERRY's blossom. */
    val BLOSSOM = ThemeMotif(
        listOf(
            "......KKKK......",
            ".....KPppPK.....",
            ".....KPpPPK.....",
            ".KKK..KPPK..KKK.",
            "KPppK.KPPK.KppPK",
            "KPpPPKKPPKKPPpPK",
            ".KPPPPKYYKPPPPK.",
            "..KKKKYYYYKKKK..",
            "...KPKYYYYKPK...",
            "..KPPPKYYKPPPK..",
            ".KPpPPKPPKPPpPK.",
            ".KPpPK.KK.KPpPK.",
            "..KPK......KPK..",
            "...K........K...",
            "................",
            "................",
        ),
        mapOf('K' to Color(0xFFB04070), 'P' to Color(0xFFFFA0BE), 'p' to Color(0xFFFFDCE6), 'Y' to Color(0xFFF8D030)),
    )
    /** VALENTINE'S DAY's heart. */
    val HEART = ThemeMotif(
        listOf(
            "................",
            "..KKKK...KKKK...",
            ".KHHRRK.KRRRRK..",
            "KHHRRRRKRRRRRDK.",
            "KHRRRRRRRRRRRDK.",
            "KRRRRRRRRRRRRDK.",
            "KRRRRRRRRRRRDDK.",
            ".KRRRRRRRRRRDK..",
            "..KRRRRRRRRDK...",
            "...KRRRRRRDK....",
            "....KRRRRDK.....",
            ".....KRRDK......",
            "......KDK.......",
            ".......K........",
            "................",
            "................",
        ),
        mapOf('K' to Color(0xFF802030), 'R' to Color(0xFFF04860), 'D' to Color(0xFFC02840), 'H' to Color(0xFFFFB0C0)),
    )
    /** ST. PATRICK'S DAY's four-leaf clover. */
    val CLOVER = ThemeMotif(
        listOf(
            "....KKK..KKK....",
            "...KgGGKKgGGK...",
            "...KGGGGGGGGK...",
            "....KGGGGGGK....",
            ".KKK.KGGGGK.KKK.",
            "KgGGKKGGGGKKgGGK",
            "KGGGGGGDDGGGGGGK",
            "KGGGGGDDDDGGGGGK",
            "KGGGGGGDDGGGGGGK",
            ".KGGKKGGGGKKGGK.",
            "..KK.KGGGGK.KK..",
            "....KGGGGGGK....",
            "...KGGGKKGGGK...",
            "...KGGK.KDDKK...",
            "....KK.....KDK..",
            ".............K..",
        ),
        mapOf('K' to Color(0xFF205830), 'G' to Color(0xFF48B858), 'g' to Color(0xFFA8E8A0), 'D' to Color(0xFF308840)),
    )
    /** EASTER's painted egg. */
    val EGG = ThemeMotif(
        listOf(
            "......KKKK......",
            ".....KppppK.....",
            "....KppppppK....",
            "....KYYYYYYK....",
            "...KYYYYYYYYK...",
            "...KppppppppK...",
            "...KBpBpBpBpK...",
            "..KpBpBpBpBpBK..",
            "..KppppppppppK..",
            "..KGGGGGGGGGGK..",
            "..KGGGGGGGGGGK..",
            "..KppppppppppK..",
            "...KppppppppK...",
            "....KppppppK....",
            ".....KKKKKK.....",
            "................",
        ),
        mapOf('K' to Color(0xFF705880), 'p' to Color(0xFFFFE8F0), 'Y' to Color(0xFFF8E070), 'B' to Color(0xFF80B8F0), 'G' to Color(0xFF98E0A8)),
    )
    /** HALLOWEEN's carved pumpkin. */
    val PUMPKIN = ThemeMotif(
        listOf(
            "................",
            ".......KK.......",
            "......KGK.......",
            "...KKKKGKKKKK...",
            "..KoOOOOOOOOOK..",
            ".KoOOOOOOOOOOOK.",
            ".KOKKOOOOOOKKOK.",
            "KoOKYKOOOOKYKODK",
            "KOOOOOOKKOOOOODK",
            "KOOOOOOOOOOOOODK",
            "KOOKOKKKKKKOKODK",
            ".KOOKYYYYYYKODK.",
            ".KDOOKKOOKKOODK.",
            "..KKDDDDDDDDKK..",
            "....KKKKKKKK....",
            "................",
        ),
        mapOf('K' to Color(0xFF301808), 'O' to Color(0xFFF08820), 'o' to Color(0xFFFFC060), 'D' to Color(0xFFC05810), 'G' to Color(0xFF408830), 'Y' to Color(0xFFFFE060)),
    )
    /** CHRISTMAS's snowflake. */
    val SNOWFLAKE = ThemeMotif(
        listOf(
            ".......B........",
            "....B..B..B.....",
            ".....B.B.B......",
            "......BBB.......",
            ".B.....B.....B..",
            "..B....B....B...",
            "...B..BWB..B....",
            "BBBBBBWWWBBBBBB.",
            "...B..BWB..B....",
            "..B....B....B...",
            ".B.....B.....B..",
            "......BBB.......",
            ".....B.B.B......",
            "....B..B..B.....",
            ".......B........",
            "................",
        ),
        mapOf('B' to Color(0xFF5890D0), 'W' to Color(0xFFD8ECFF)),
    )
    /** LUNAR NEW YEAR's paper lantern. */
    val LANTERN = ThemeMotif(
        listOf(
            ".......KK.......",
            ".......KK.......",
            ".....KKKKKK.....",
            "....KYYYYYYK....",
            "...KRRRRRRRRK...",
            "..KHRRDRRDRRRK..",
            "..KHRRDRRDRRRK..",
            "..KRRRDRRDRRRK..",
            "..KRRRDRRDRRDK..",
            "...KRRRRRRRRK...",
            "....KYYYYYYK....",
            ".....KKKKKK.....",
            ".......YY.......",
            "......YYYY......",
            "......Y.YY......",
            ".......Y.Y......",
        ),
        mapOf('K' to Color(0xFF501010), 'R' to Color(0xFFE02828), 'D' to Color(0xFFA81818), 'H' to Color(0xFFFF8070), 'Y' to Color(0xFFF8C830)),
    )
    /** NEW YEAR's firework sparkle. */
    val SPARKLE = ThemeMotif(
        listOf(
            "........K.......",
            ".......KYK......",
            ".......KYK......",
            "......KYYYK.....",
            "......KYWYK.....",
            ".....KYYWYYK....",
            "..KKKYYWWWYYKKK.",
            "KKYYYYWWWWWYYYYK",
            "..KKKYYWWWYYKKK.",
            ".....KYYWYYK....",
            "......KYWYK.....",
            "......KYYYK.....",
            ".......KYK......",
            ".......KYK......",
            "........K.......",
            "................",
        ),
        mapOf('K' to Color(0xFF806010), 'Y' to Color(0xFFF8C830), 'W' to Color(0xFFFFF8D0)),
    )
    /** RAINBOW's arc. */
    val RAINBOW = ThemeMotif(
        listOf(
            "................",
            "................",
            "................",
            ".....RRRRRR.....",
            "...RROOOOOORR...",
            "..ROOYYYYYYOOR..",
            ".ROYYGGGGGGYYOR.",
            ".ROYGGBBBBGGYOR.",
            "ROYGBBVVVVBBGYOR",
            "ROYGBV....VBGYOR",
            "ROYGBV....VBGYOR",
            "ROYGBV....VBGYOR",
            "................",
            "................",
            "................",
            "................",
        ),
        mapOf('R' to Color(0xFFE83838), 'O' to Color(0xFFF89030), 'Y' to Color(0xFFF8D838), 'G' to Color(0xFF58C058), 'B' to Color(0xFF4890E8), 'V' to Color(0xFF9060D0)),
    )
}
