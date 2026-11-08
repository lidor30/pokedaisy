package com.pokedaisy.app.companion.i18n

/**
 * Every translation table, one per area of the app (each file `Tr<Area>.kt`).
 * Entries are `"ENGLISH" to Tr(ja = ..., fr = ..., de = ..., it = ..., es = ..., br = ..., pt = ..., ko = ...)`,
 * one per line in that field order - scripts/check_translations.py parses them.
 *
 * House style: keep the English's casing (the UI is mostly ALL CAPS, like the
 * games' menus) and its `{0}` placeholders; use the localized games' own
 * words (BAG = SAC / BEUTEL / BORSA / MOCHILA / バッグ / 가방; both Portuguese use the Brazilian
 * games' words, Portugal's only its own everyday ones: ecrã, guardar, ficheiro); Japanese in kana
 * only, like Gen 3's Japanese text; Korean in the Korean games' words, labels as nouns, sentences in 해요체,
 * no particle right after a {0} (its form depends on the word); stay about as short as the English -
 * the buttons and tab chips are sized for it.
 */
internal fun allTranslationTables(): List<Map<String, Tr>> = listOf(
    trCore,
    trSettings,
    trLibrary,
    trCompanion,
    trCompanionInfo,
    trCoop,
    trCoopBattle,
    trCoopLock,
)
