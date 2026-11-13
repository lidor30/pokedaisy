package com.pokedaisy.app.companion.data

/**
 * A type's name as the running game shows it (GitHub #40: a French game's companion said FIRE). The
 * English name stays the key everywhere else - the type chart, the badge colours ([activeTypeNames]) -
 * and is only turned into the game's language ([romLanguage]) where it's drawn, in the localized
 * games' own words. Gen 3 spells them in capitals; Japanese in its kana, like the rest of its text.
 */
fun typeLabel(type: String): String {
    val en = type.uppercase()
    return localTypeNames()?.get(en) ?: en
}

/** [type] inside a sentence ("knows a Fire move"): the game's word, or English's own casing. */
fun typeInText(type: String): String = localTypeNames()?.get(type.uppercase()) ?: type

/** A cart's own type words where they differ from its language's Gen 3 ones (Kristall's DRACHEN), else the language's. */
private fun localTypeNames(): Map<String, String>? = GAME_TYPE_NAMES[romGameCode] ?: LOCAL_TYPE_NAMES[romLanguage]

private val GAME_TYPE_NAMES: Map<String, Map<String, String>> by lazy {
    val goldSilverDe = typeNamesCrystal.mapNotNull { (id, en) -> typeNamesGoldSilverDe[id]?.let { en.uppercase() to it } }.toMap()
    mapOf(
        "BYTD" to typeNamesCrystal.mapNotNull { (id, en) -> typeNamesCrystalDe[id]?.let { en.uppercase() to it } }.toMap(),
        "AAUD" to goldSilverDe, "AAXD" to goldSilverDe,
    )
}

private val LOCAL_TYPE_NAMES: Map<Char, Map<String, String>> = mapOf(
    'F' to mapOf(
        "NORMAL" to "NORMAL", "FIRE" to "FEU", "WATER" to "EAU", "GRASS" to "PLANTE", "ELECTRIC" to "ÉLECTRIK",
        "ICE" to "GLACE", "FIGHTING" to "COMBAT", "POISON" to "POISON", "GROUND" to "SOL", "FLYING" to "VOL",
        "PSYCHIC" to "PSY", "BUG" to "INSECTE", "ROCK" to "ROCHE", "GHOST" to "SPECTRE", "DRAGON" to "DRAGON",
        "DARK" to "TÉNÈBRES", "STEEL" to "ACIER", "FAIRY" to "FÉE",
    ),
    'D' to mapOf(
        "NORMAL" to "NORMAL", "FIRE" to "FEUER", "WATER" to "WASSER", "GRASS" to "PFLANZE", "ELECTRIC" to "ELEKTRO",
        "ICE" to "EIS", "FIGHTING" to "KAMPF", "POISON" to "GIFT", "GROUND" to "BODEN", "FLYING" to "FLUG",
        "PSYCHIC" to "PSYCHO", "BUG" to "KÄFER", "ROCK" to "GESTEIN", "GHOST" to "GEIST", "DRAGON" to "DRACHE",
        "DARK" to "UNLICHT", "STEEL" to "STAHL", "FAIRY" to "FEE",
    ),
    'I' to mapOf(
        "NORMAL" to "NORMALE", "FIRE" to "FUOCO", "WATER" to "ACQUA", "GRASS" to "ERBA", "ELECTRIC" to "ELETTRO",
        "ICE" to "GHIACCIO", "FIGHTING" to "LOTTA", "POISON" to "VELENO", "GROUND" to "TERRA", "FLYING" to "VOLANTE",
        "PSYCHIC" to "PSICO", "BUG" to "COLEOTTERO", "ROCK" to "ROCCIA", "GHOST" to "SPETTRO", "DRAGON" to "DRAGO",
        "DARK" to "BUIO", "STEEL" to "ACCIAIO", "FAIRY" to "FOLLETTO",
    ),
    'S' to mapOf(
        "NORMAL" to "NORMAL", "FIRE" to "FUEGO", "WATER" to "AGUA", "GRASS" to "PLANTA", "ELECTRIC" to "ELÉCTRICO",
        "ICE" to "HIELO", "FIGHTING" to "LUCHA", "POISON" to "VENENO", "GROUND" to "TIERRA", "FLYING" to "VOLADOR",
        "PSYCHIC" to "PSÍQUICO", "BUG" to "BICHO", "ROCK" to "ROCA", "GHOST" to "FANTASMA", "DRAGON" to "DRAGÓN",
        "DARK" to "SINIESTRO", "STEEL" to "ACERO", "FAIRY" to "HADA",
    ),
    'J' to mapOf(
        "NORMAL" to "ノーマル", "FIRE" to "ほのお", "WATER" to "みず", "GRASS" to "くさ", "ELECTRIC" to "でんき",
        "ICE" to "こおり", "FIGHTING" to "かくとう", "POISON" to "どく", "GROUND" to "じめん", "FLYING" to "ひこう",
        "PSYCHIC" to "エスパー", "BUG" to "むし", "ROCK" to "いわ", "GHOST" to "ゴースト", "DRAGON" to "ドラゴン",
        "DARK" to "あく", "STEEL" to "はがね", "FAIRY" to "フェアリー",
    ),
)
