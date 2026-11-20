package com.pokedaisy.app.companion.ui

import androidx.compose.ui.graphics.Color

/*
 * Pixel icons for SETTINGS > HUD's and > QUICK MENU's SHOW rows ([hudPartIcon], [quickRowIcon]): each row carries the icon
 * its part / tile has on the field. Where the HUD or the menu draws the game's own art (a REPEL's, a bike's, a rod's
 * item icon, the Pokémon that would FLY, the party's icons), the settings page, which may have no game open, draws one of
 * these stand-ins of our own instead.
 */

/** A spray can: K outline, P its body, L the shine, W the label band and nozzle, D the base. */
internal val REPEL_ROW_ICON = listOf(
    ".....KK.....",
    "....KWWK....",
    "...KKKKKK...",
    "..KPPPPPPK..",
    "..KLPPPPPK..",
    "..KLPPPPPK..",
    "..KWWWWWWK..",
    "..KLPPPPPK..",
    "..KLPPPPPK..",
    "..KPPPPPPK..",
    "..KDDDDDDK..",
    "...KKKKKK...",
)
internal val REPEL_ROW_COLORS = mapOf(
    'K' to Color(0xFF2A1838), 'P' to Color(0xFFA050C8), 'L' to Color(0xFFD8A8F0), 'W' to Color(0xFFF0F0F0), 'D' to Color(0xFF704090),
)

/** A bicycle: K the wheels and bars, R the red frame. */
internal val BIKE_ROW_ICON = listOf(
    "............",
    ".........KK.",
    "..KKK...K...",
    "...K....K...",
    "...RRRRRR...",
    "...R..R..K..",
    ".KKK.R..KKK.",
    "K...KR.K...K",
    "K.K.KR.K.K.K",
    "K...K..K...K",
    ".KKK....KKK.",
    "............",
)
internal val BIKE_ROW_COLORS = mapOf('K' to Color(0xFF202028), 'R' to Color(0xFFE03828))

/** A fishing rod: B the brown rod, K the reel and line, R / W the float. */
internal val ROD_ROW_ICON = listOf(
    "..........BK",
    ".........BBK",
    "........BB.K",
    ".......BB..K",
    "......BB...K",
    ".....BB....K",
    "....BB.....K",
    "...KKB.....R",
    "..KWKB....RR",
    "...KBB....WW",
    "..BB.......W",
    ".BB.........",
)
internal val ROD_ROW_COLORS = mapOf('B' to Color(0xFF8A5828), 'K' to Color(0xFF303038), 'R' to Color(0xFFE03828), 'W' to Color(0xFFF0F0F0))

/** A bird seen from the front, wings raised: K outline, B its body, W the wings' light edge, O the beak. */
internal val FLY_ROW_ICON = listOf(
    "............",
    "KK........KK",
    "KBK......KBK",
    "KWBK....KBWK",
    ".KWBK..KBWK.",
    ".KBBBKKBBBK.",
    "..KBBBBBBK..",
    "...KBOOBK...",
    "....KBBK....",
    ".....KK.....",
    "............",
    "............",
)
internal val FLY_ROW_COLORS = mapOf('K' to Color(0xFF282018), 'B' to Color(0xFFB07840), 'W' to Color(0xFFE8C890), 'O' to Color(0xFFF8C030))

/** A party member: a round creature (K outline, O body, W eyes) over its green HP bar (G, D the empty part). */
internal val PARTY_ROW_ICON = listOf(
    "...K....K...",
    "..KOK..KOK..",
    "..KOOKKOOK..",
    ".KOOOOOOOOK.",
    ".KOWKOOWKOK.",
    ".KOOOOOOOOK.",
    ".KOOOOOOOOK.",
    "..KOOOOOOK..",
    "...KKKKKK...",
    "KKKKKKKKKKKK",
    "KGGGGGGGDDDK",
    "KKKKKKKKKKKK",
)
internal val PARTY_ROW_COLORS = mapOf(
    'K' to Color(0xFF302018), 'O' to Color(0xFFF08830), 'W' to Color.White, 'G' to Color(0xFF58D080), 'D' to Color(0xFF505058),
)
