package com.pokedaisy.app.companion.i18n

import com.pokedaisy.app.companion.i18n.guide.guideTextEmerald
import com.pokedaisy.app.companion.i18n.guide.guideTextFireRed
import com.pokedaisy.app.companion.i18n.guide.guideTextQuetzal
import com.pokedaisy.app.companion.i18n.guide.guideTextRubySapphire
import com.pokedaisy.app.companion.i18n.guide.guideTextUnbound

/**
 * The GUIDE's hand-written text (Guide<Game>.kt: entries, hints, answers, notes, bosses, the
 * buildings in HERE's area data) in the language on screen - for the games that come in other
 * languages: FireRed / LeafGreen, Emerald, Ruby / Sapphire, and the translated hacks (Unbound, Quetzal).
 * Kept apart from the app's own tables ([translations]): a guide line is matched whole, by its English,
 * and its names are the localized games' own (from each ROM's tables), so they read like the game.
 * Anything not here (an English-only hack's guide, names already from the ROM) shows as it is.
 * `GuideTranslationsTest` fails while a line of those guides has no translation.
 */
val guideTranslations: Map<String, Tr> by lazy {
    guideTextFireRed + guideTextEmerald + guideTextRubySapphire + guideTextUnbound + guideTextQuetzal
}

/** [english] in the language on screen when a guide table has it, else as it is. */
fun trGuide(english: String): String =
    if (L10n.language == AppLanguage.EN) english else guideTranslations[english]?.of(L10n.language) ?: english
