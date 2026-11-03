package com.pokedaisy.app.companion.ui

import androidx.compose.ui.graphics.Color

/**
 * The MORE tab's coloured app icons, one per tab id ([forTab]): 16-pixel-wide [PixelArt] bitmaps, each
 * character a colour of its own [TabIcon.palette] ('k' the dark outline, '.' see-through). Our own
 * drawings, never the games' art; they keep their colours in dark mode, like the game art does.
 */
internal object TabIcons {
    class TabIcon(val bitmap: List<String>, val palette: Map<Char, Color>)

    /** Every icon is this many pixels square. */
    const val SIZE = 16

    private val OUTLINE = Color(0xFF303038)
    private val WHITE = Color(0xFFF8F8F8)

    /** A Poké Ball, the same both sides but its shine (top left). */
    private val party = TabIcon(
        listOf(
            ".....kkkkkk.....",
            "...kkrrrrrrkk...",
            "..krhhrrrrrrrk..",
            ".krhrrrrrrrrrrk.",
            ".krrrrrrrrrrrrk.",
            "krrrrrrrrrrrrrrk",
            "krrrrrkkkkrrrrrk",
            "kkkkkkwwwwkkkkkk",
            "kkkkkkwwwwkkkkkk",
            "kwwwwwkkkkwwwwwk",
            "kwwwwwwwwwwwwwwk",
            ".kwwwwwwwwwwwwk.",
            ".kwwwwwwwwwwwwk.",
            "..kwwwwwwwwwwk..",
            "...kkwwwwwwkk...",
            ".....kkkkkk.....",
        ),
        mapOf('k' to OUTLINE, 'r' to Color(0xFFE83838), 'h' to Color(0xFFFF9C8C), 'w' to WHITE),
    )

    /** A red handheld: a blue lens, two lights, a green screen. */
    private val dex = TabIcon(
        listOf(
            "..kkkkkkkkkkkk..",
            ".krrrrrrrrrrrrk.",
            ".krkkkkrryrgrrk.",
            ".krkbbkrrrrrrrk.",
            ".krkbbkrrrrrrrk.",
            ".krkkkkrrrrrrrk.",
            ".krrrrrrrrrrrrk.",
            ".krkkkkkkkkkkrk.",
            ".krkggggggggkrk.",
            ".krkgllgggggkrk.",
            ".krkggggggggkrk.",
            ".krkkkkkkkkkkrk.",
            ".krrrrrrrrrrrrk.",
            ".krkkrrrrrkkkrk.",
            ".krrrrrrrrrrrrk.",
            "..kkkkkkkkkkkk..",
        ),
        mapOf(
            'k' to OUTLINE, 'r' to Color(0xFFD83030), 'b' to Color(0xFF58B0F8), 'y' to Color(0xFFF8D030),
            'g' to Color(0xFF88D870), 'l' to Color(0xFFC8F8B8),
        ),
    )

    /** A map folded in three: land, sea, a dotted route and an X. */
    private val map = TabIcon(
        listOf(
            "kkkkkk....kkkkkk",
            "kppppkkkkkkppppk",
            "kggppkqqqqkppbbk",
            "kgggpkqqqqkpbbbk",
            "kggppkqqqqkppbbk",
            "kppppkqqqqkppppk",
            "kppppkqqqqkxppxk",
            "kppppkqqqxkpxxpk",
            "kppppkqqxqkpxxpk",
            "kppppkqxqqkxppxk",
            "kppxpkxqqqkppppk",
            "kpxppkqqqqkppppk",
            "kppppkqqqqkppppk",
            "kppppkqqqqkppppk",
            "kkkkkkqqqqkkkkkk",
            ".....kkkkkk.....",
        ),
        mapOf(
            'k' to OUTLINE, 'p' to Color(0xFFF8E8B8), 'q' to Color(0xFFE0C888), 'g' to Color(0xFF70C058),
            'b' to Color(0xFF58A8F0), 'x' to Color(0xFFE03830),
        ),
    )

    /** A satchel with a buckle. */
    private val bag = TabIcon(
        listOf(
            ".....kkkkkk.....",
            "....kk....kk....",
            "....k......k....",
            "..kkkkkkkkkkkk..",
            ".kddddddddddddk.",
            "kddddddddddddddk",
            "kddddddddddddddk",
            "kddddddkkddddddk",
            "kbbbbbkyykbbbbbk",
            "kbbbbbkyykbbbbbk",
            "kbbbbbbkkbbbbbbk",
            "kbbbbbbbbbbbbbbk",
            "kbbbbbbbbbbbbbbk",
            "kbbbbbbbbbbbbbbk",
            ".kbbbbbbbbbbbbk.",
            "..kkkkkkkkkkkk..",
        ),
        mapOf('k' to OUTLINE, 'd' to Color(0xFFA06030), 'b' to Color(0xFFD08848), 'y' to Color(0xFFF8D030)),
    )

    /** A blue book with a gold title plate. */
    private val guide = TabIcon(
        listOf(
            ".kkkkkkkkrrkkk..",
            "kddbbbbbbrrbbk..",
            "kddbbbbbbbbbbbk.",
            "kddbkkkkkkkkbbk.",
            "kddbkyyyyyykbbk.",
            "kddbkkkkkkkkbbk.",
            "kddbbbbbbbbbbbk.",
            "kddbbbbbbbbbbbk.",
            "kddbbbbbbbbbbbk.",
            "kddbbbbbbbbbbbk.",
            "kddbbbbbbbbbbbk.",
            "kddbbbbbbbbbbbk.",
            "kdkwwwwwwwwwwwwk",
            "kdkssssssssssssk",
            ".kkkkkkkkkkkkkkk",
        ),
        mapOf(
            'k' to OUTLINE, 'd' to Color(0xFF2850A8), 'b' to Color(0xFF4880E0), 'y' to Color(0xFFF8D030),
            'w' to WHITE, 's' to Color(0xFFC8C8D0), 'r' to Color(0xFFE03830),
        ),
    )

    /** An ID card: a red band, a picture, lines of text and three stars. */
    private val card = TabIcon(
        listOf(
            ".kkkkkkkkkkkkkk.",
            "khhhhhhhhhhhhhhk",
            "khhhhhhhhhhhhhhk",
            "kcccccccccccccck",
            "kckkkkkcccccccck",
            "kckaaakcllllllck",
            "kckssskcccccccck",
            "kckpppkcllllccck",
            "kckkkkkcccccccck",
            "kcccccccccccccck",
            "kcyycyycyyccccck",
            ".kkkkkkkkkkkkkk.",
        ),
        mapOf(
            'k' to OUTLINE, 'h' to Color(0xFFE85848), 'c' to Color(0xFFD8F0F0), 'a' to Color(0xFF805030),
            's' to Color(0xFFF8C8A0), 'p' to Color(0xFF4880E0), 'l' to Color(0xFF708890), 'y' to Color(0xFFF0B820),
        ),
    )

    /** A floppy disk: a save slot. */
    private val states = TabIcon(
        listOf(
            "kkkkkkkkkkkkkk..",
            "kbbkssssssskbbk.",
            "kbbkssddssskbbbk",
            "kbbkssddssskbbbk",
            "kbbkssssssskbbbk",
            "kbbbkkkkkkkbbbbk",
            "kbbbbbbbbbbbbbbk",
            "kbbwwwwwwwwwwbbk",
            "kbbwllllllllwbbk",
            "kbbwwwwwwwwwwbbk",
            "kbbwllllllwwwbbk",
            "kbbwwwwwwwwwwbbk",
            "kbbwwwwwwwwwwbbk",
            "kkkkkkkkkkkkkkkk",
        ),
        mapOf(
            'k' to OUTLINE, 'b' to Color(0xFF7048C8), 's' to Color(0xFFD0D0D8), 'd' to Color(0xFF606070),
            'w' to WHITE, 'l' to Color(0xFF9090A0),
        ),
    )

    /** A gold trophy on a stand. */
    private val achievements = TabIcon(
        listOf(
            "..kkkkkkkkkkkk..",
            "kkkyhyyyyyyyykkk",
            "k.kyhyyyyyyyyk.k",
            "k.kyhyyyyyyyyk.k",
            ".kkyhyyyyyyyykk.",
            "...kyhyyyyyyk...",
            "....kyyyyyyk....",
            ".....kyyyyk.....",
            "......kyyk......",
            "......kyyk......",
            ".....kkyykk.....",
            "....kooooook....",
            "...kkkkkkkkkk...",
            "...kddddddddk...",
            "...kkkkkkkkkk...",
        ),
        mapOf('k' to OUTLINE, 'y' to Color(0xFFF8C830), 'h' to Color(0xFFFFF0A0), 'o' to Color(0xFFD89820), 'd' to Color(0xFF905830)),
    )

    /** A grey gravestone with a cross on grass: the NUZLOCKE tab (and a fainted Pokémon's mark). */
    val grave = TabIcon(
        listOf(
            "................",
            ".....kkkkkk.....",
            "...kkwwsssskk...",
            "..kwwssddsssdk..",
            "..kwsssddsssdk..",
            "..kwsddddddsdk..",
            "..kwsddddddsdk..",
            "..kwsssddsssdk..",
            "..kwsssddsssdk..",
            "..kwsssddsssdk..",
            "..kwssssssssdk..",
            "..kwssssssssdk..",
            ".gkkkkkkkkkkkkg.",
            "gggGggggggGggggg",
            "ggggggGgggggggGg",
            "GGGGGGGGGGGGGGGG",
        ),
        mapOf(
            'k' to OUTLINE, 'w' to Color(0xFFE0E0E8), 's' to Color(0xFFA8A8B8), 'd' to Color(0xFF686878),
            'g' to Color(0xFF58B848), 'G' to Color(0xFF388830),
        ),
    )

    /** A PC whose screen is a box on a grassy wallpaper, three Pokémon in it: the BOXES tab. */
    private val boxes = TabIcon(
        listOf(
            "................",
            ".kkkkkkkkkkkkkk.",
            ".kwwwwwwwwwwwwk.",
            ".kwkkkkkkkkkkwk.",
            ".kwkggggggggkwk.",
            ".kwkgrgggbggkwk.",
            ".kwkggggggggkwk.",
            ".kwkggyggggrkwk.",
            ".kwkggggggggkwk.",
            ".kwkkkkkkkkkkwk.",
            ".kwwwwwwwwwwwwk.",
            ".kkkkkkkkkkkkkk.",
            "......kssk......",
            ".....kssssk.....",
            "...kkkkkkkkkk...",
            "...kssssssssk...",
        ),
        mapOf(
            'k' to OUTLINE, 'w' to Color(0xFFD8D8E0), 's' to Color(0xFF9898A8), 'g' to Color(0xFF78C058),
            'r' to Color(0xFFE83838), 'b' to Color(0xFF3878E8), 'y' to Color(0xFFF8C830),
        ),
    )

    /** A tab id's icon; null for a tab without one (BATTLE and SETTINGS never sit on the MORE grid). */
    fun forTab(id: String): TabIcon? = when (id) {
        "PARTY" -> party
        "DEX" -> dex
        "MAP" -> map
        "ITEMS" -> bag
        "BOXES" -> boxes
        "GUIDE" -> guide
        "NUZLOCKE" -> grave
        "CARD" -> card
        "STATES" -> states
        "ACHIEVEMENTS" -> achievements
        else -> null
    }
}
