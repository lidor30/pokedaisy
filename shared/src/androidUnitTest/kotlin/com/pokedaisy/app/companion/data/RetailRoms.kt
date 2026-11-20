package com.pokedaisy.app.companion.data

import java.io.File

/**
 * The retail carts in [dir] (the retail ROM dir by default), every language and revision: their No-Intro names begin
 * "Pokemon - " / "Pocket Monsters - ", which no hack's does (CLAUDE.md's naming: "Pokemon <Title> (v<version>)"). Since
 * 2026-11-07 the other languages sit there too, beside the English ones and the hacks.
 */
internal fun retailRomFiles(dir: File = File(RETAIL_ROM_DIR)): Sequence<File> =
    dir.walk().filter { it.isFile && it.extension == "gba" && RETAIL_NAME.containsMatchIn(it.name) }

private val RETAIL_NAME = Regex("^(Pokemon|Pocket Monsters) - ")
