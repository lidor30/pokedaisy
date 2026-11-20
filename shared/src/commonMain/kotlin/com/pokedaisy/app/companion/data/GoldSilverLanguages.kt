package com.pokedaisy.app.companion.data

/** German Gold / Silver's (Goldene / Silberne Edition's) names over English Gold / Silver's tables (scripts/gen_gen2_tables.py gold_silver_de). */
internal val GOLD_SILVER_DE_TEXT by lazy {
    GameText(
        species = { speciesNamesGoldSilverDe }, moves = { moveNamesGoldSilverDe }, items = { itemNamesGoldSilverDe }, natures = emptyList(),
        mapSecs = { landmarkNamesGoldSilverDe }, smallFontWidths = { IntArray(0) }, englishMapSecs = { mapSecDataGoldSilver },
        englishMoves = { moveDataCrystal },
    )
}
