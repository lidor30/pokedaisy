package com.pokedaisy.app.companion.data

/**
 * The German, French, Italian and Spanish Yellows' own names (YellowText<Lang>Gen.kt, from each cart by
 * scripts/gen_gen1_tables.py) over English Yellow's tables, which they share id for id: the Dex numbers,
 * moves' types and powers, items and map ids. Keyed by the cart's CGB manufacturer code ([Gen1Config.gameCode]).
 */
private fun yellowText(species: Map<Int, String>, moves: Map<Int, String>, items: Map<Int, String>, maps: Map<Int, String>) =
    GameText(
        species = { species }, moves = { moves }, items = { items }, natures = emptyList(), mapSecs = { maps },
        smallFontWidths = { IntArray(0) }, englishMapSecs = { mapSecDataYellow }, englishMoves = { moveDataYellow },
    )

internal val YELLOW_DE_TEXT by lazy { yellowText(speciesNamesYellowDe, moveNamesYellowDe, itemNamesYellowDe, mapNamesYellowDe) }
/** German Red / Blue ("APAD" / "APED", their product codes: [GEN1_RED_DE]): German Yellow's names, the hideout's aside
 * (gen_gen1_tables.py red_de / blue_de checks every other name equal). */
internal val RED_BLUE_DE_TEXT by lazy {
    yellowText(speciesNamesYellowDe, moveNamesYellowDe, itemNamesYellowDe,
        mapNamesYellowDe.mapValues { (_, n) -> if (n == "TEAM ROCKET HQ") "ROCKET HQ" else n })
}
internal val YELLOW_FR_TEXT by lazy { yellowText(speciesNamesYellowFr, moveNamesYellowFr, itemNamesYellowFr, mapNamesYellowFr) }
internal val YELLOW_IT_TEXT by lazy { yellowText(speciesNamesYellowIt, moveNamesYellowIt, itemNamesYellowIt, mapNamesYellowIt) }
internal val YELLOW_ES_TEXT by lazy { yellowText(speciesNamesYellowEs, moveNamesYellowEs, itemNamesYellowEs, mapNamesYellowEs) }
