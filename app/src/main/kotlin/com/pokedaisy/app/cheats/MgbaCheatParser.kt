package com.pokedaisy.app.cheats

import com.pokedaisy.app.MgbaCore

/** [CheatParser] over mGBA's parser in libpokedaisy (PokeDaisyApp sets it). */
internal object MgbaCheatParser : CheatParser {
    override fun check(code: String, type: Int, directive: String): String? =
        runCatching { MgbaCore.pkCheatsCheck(code, type, directive) }.getOrNull()
}
