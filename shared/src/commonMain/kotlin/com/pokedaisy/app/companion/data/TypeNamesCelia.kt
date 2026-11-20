package com.pokedaisy.app.companion.data

// Pokemon Celia's Stupid Romhack v1.1.4's type names, extracted directly
// from the ROM: FireRed-style 7-byte gTypeNames at 0x0873A5DC (5 code refs),
// 34 ids. Custom order - Steel is 4, Ground 13, Fire 14 - plus joke types
// (Brock, Weird, Dad, Choco, Large, Bird, Small, Sound) and duplicates
// (Water 8/15, Electric 9/17, Psychic 10/18, Fighting 1/33). ROM spellings
// are ALL CAPS abbreviations (FIGHT/ELECTR/PSYCHC); expanded/title-cased
// here so the UI's per-type colours apply. The duplicate-named slots 8-10
// are real, distinct types (own chart rows; no species, but a few moves -
// Fire Punch is type 8 "Water"). 11 is drawn with glyphs ("?"),
// 12 is the Mystery slot ("???", skipped by typeMatchups), 27 is blank.
val typeNamesCelia: Map<Int, String> = mapOf(
    0 to "Normal",
    1 to "Fighting",
    2 to "Flying",
    3 to "Poison",
    4 to "Steel",
    5 to "Rock",
    6 to "Bug",
    7 to "Ghost",
    8 to "Water",
    9 to "Electric",
    10 to "Psychic",
    11 to "?",
    12 to "???",
    13 to "Ground",
    14 to "Fire",
    15 to "Water",
    16 to "Grass",
    17 to "Electric",
    18 to "Psychic",
    19 to "Ice",
    20 to "Dragon",
    21 to "Dark",
    22 to "Fairy",
    23 to "Brock",
    24 to "Weird",
    25 to "Dad",
    26 to "Choco",
    28 to "Large",
    29 to "Bird",
    30 to "Small",
    31 to "Fairy",
    32 to "Sound",
    33 to "Fighting",
)
