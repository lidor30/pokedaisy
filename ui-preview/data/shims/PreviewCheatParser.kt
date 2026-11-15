package com.pokedaisy.app.cheats

/** mGBA's cheat parser stand-in (the app's [CheatParser] calls libpokedaisy): a line is
 * read when it looks like a code - 8 hex digits and 4 or 8 more, or VBA's ADDRESS:VALUE. */
object PreviewCheatParser : CheatParser {
    private val CODE = Regex("^[0-9A-F]{8}( [0-9A-F]{4}| [0-9A-F]{8}|:[0-9A-F]{2,8})$")

    override fun check(code: String, type: Int, directive: String): String =
        directive + "\n" + code.split('\n').joinToString("") { if (CODE.matches(it)) "1" else "0" }
}
