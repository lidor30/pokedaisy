package com.pokedaisy.app.companion.data

/**
 * Gen 1's text characters (constants/charmap.asm), by cart: English's, and the European carts'
 * two sets - German / French and Italian / Spanish - which drop English's 'd 'l 's 't 'v ligatures
 * at 0xBB-0xBF for accented letters (read off each cart's FontGraphics; scripts/gen_gen1_tables.py
 * has the same tables). Kana and unused tiles are left out.
 */
enum class Gen1Charset(val chars: Map<Int, String>) {
    EN(BASE + mapOf(0xBA to "é", 0xBB to "'d", 0xBC to "'l", 0xBD to "'s", 0xBE to "'t", 0xBF to "'v", 0xE4 to "'r", 0xE5 to "'m")),
    DF(BASE + zipCodes(0xBA, "àèéùßçÄÖÜäöüëïâôûêî") + mapOf(0xE4 to "+") +
        listOf("c'", "d'", "j'", "l'", "m'", "n'", "p'", "s'", "'s", "t'", "u'", "y'").withIndex().associate { 0xD4 + it.index to it.value }),
    IS(BASE + zipCodes(0xBA, "àèéùÀÁÄÖÜäöüÈÉÌÍÑÒÓÙÚáìíñòóúº&") + mapOf(0xE4 to "¿", 0xE5 to "¡") +
        listOf("'d", "'l", "'m", "'r", "'s", "'t", "'v").withIndex().associate { 0xD8 + it.index to it.value }),
    /** PureRGB: English's, plus its one-byte words from 0x33 (home/text.asm's TextShortcutCommandJumpTable), quotes where
     * the brackets were, an arrow, + and % (its charmap.asm; scripts/gen_gen1_tables.py's "PURE"). */
    PURE(EN.chars + listOf("like", "an", "You", "er", "with", "at", "ight", "have", " a ", "here", "to ", " to", "or", "ing",
        "the", "you", "opponent", "user", "POKéMON", "TRAINER TIPS", "TEAM ").withIndex().associate { 0x33 + it.index to it.value } +
        mapOf(0x48 to " ", 0x4D to "is", 0x56 to "...", 0x5B to "PC", 0x5C to "TM", 0x5D to "TRAINER", 0x5E to "ROCKET",
            0x74 to "·", 0x9E to "“", 0x9F to "”", 0xE9 to "→", 0xEA to "+", 0xEB to "%")),
    ;

    /** The single characters a font tile stands for (the companion's font: ligatures left out, and 0xF2's
     * decimal point - the full stop is 0xE8's). */
    val glyphs: Map<Int, Char> get() = chars.filter { it.key >= 0x80 && it.key != 0xF2 && it.value.length == 1 }.mapValues { it.value[0] }
}

private fun zipCodes(first: Int, s: String) = s.withIndex().associate { first + it.index to it.value.toString() }

private val BASE: Map<Int, String> = buildMap {
    for (i in 0 until 26) { put(0x80 + i, ('A' + i).toString()); put(0xA0 + i, ('a' + i).toString()) }
    for (i in 0 until 10) put(0xF6 + i, ('0' + i).toString())
    putAll(mapOf(
        0x7F to " ", 0x9A to "(", 0x9B to ")", 0x9C to ":", 0x9D to ";", 0x9E to "[", 0x9F to "]",
        0xE0 to "'", 0xE3 to "-", 0xE6 to "?",
        0xE7 to "!", 0xE8 to ".", 0xEC to "▷", 0xED to "▶", 0xEE to "▼", 0xEF to "♂", 0xF0 to "¥", 0xF1 to "×",
        0xF2 to ".", 0xF3 to "/", 0xF4 to ",", 0xF5 to "♀", 0x54 to "POKé", 0x75 to "…", 0x4E to " ", 0x4F to " ",
        0x49 to " ", 0x51 to " ",
    ))
}
