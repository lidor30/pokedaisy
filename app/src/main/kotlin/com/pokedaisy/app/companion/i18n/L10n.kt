package com.pokedaisy.app.companion.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * The app's own text in nine languages. Game data (Pokémon / move / item / type
 * names, GUIDE pages) isn't translated here - it comes from the game's tables.
 *
 * Text is keyed by its English: `tr("CLOSE GAME")` returns the current
 * language's version from [translations], or the English itself when there's
 * none. A string the code also compares (a selector option, an enum label)
 * stays English in the logic and is marked [tk]; the OPTION-menu pieces
 * (OptionLine, OptionTitleWindow, OptionBadge) translate what they draw.
 * Arguments go in as `{0}`, `{1}`: `tr("{0} LEFT", n)`. One English word
 * with two meanings gets a context after a `|`: `tr("NEXT|continue")` shows
 * "NEXT" in English and has its own translations. `TranslationsTest` fails
 * on a `tr` / `tk` literal missing from a table.
 */
enum class AppLanguage(val code: String, val romLetter: Char?, val locale: String, val nativeName: String) {
    EN("EN", 'E', "en", "ENGLISH"),
    JA("JA", 'J', "ja", "日本語"),
    FR("FR", 'F', "fr", "FRANÇAIS"),
    DE("DE", 'D', "de", "DEUTSCH"),
    IT("IT", 'I', "it", "ITALIANO"),
    ES("ES", 'S', "es", "ESPAÑOL"),
    // No Gen 3 game came out in Portuguese, so no ROM picks these: the device's language or the LANGUAGE row does
    // (GitHub #61). Brazil's is the official games' Portuguese; Portugal's keeps those game words, its own everyday ones.
    BR("BR", null, "pt-BR", "PORTUGUÊS (BR)"),
    PT("PT", null, "pt", "PORTUGUÊS (PT)"),
    // No Gen 3 game came out in Korean; the Korean DS carts (HG / SS, B / W, B2 / W2: game codes ending in K) pick it,
    // else the device's language or the LANGUAGE row. Hangul draws in GalmuriKO (PixelTypeface.kt).
    KO("KO", 'K', "ko", "한국어"),
    ;

    companion object {
        fun ofCode(code: String?): AppLanguage? = entries.firstOrNull { it.code == code }

        /** The game code's 4th letter is its language: BPRE English, BPRF French, AXVJ Japanese... */
        fun ofRomCode(gameCode: String?): AppLanguage? =
            gameCode?.getOrNull(3)?.let { l -> entries.firstOrNull { it.romLetter == l } }

        /** Portuguese by region: Brazil's for pt-BR, Portugal's for every other pt. */
        fun ofLocale(locale: Locale): AppLanguage? =
            if (locale.language == "pt") { if (locale.country == "BR") BR else PT }
            else entries.firstOrNull { it.locale == locale.language }
    }
}

/** [Prefs.appLanguage]'s value for "follow the ROM". */
const val LANGUAGE_AUTO = "AUTO"

/**
 * One English string's translations. [br] (Brazilian Portuguese), [pt] (Portugal's) and [ko] (Korean) are required
 * in the app's own tables (`TranslationsTest`), optional in the GUIDE's, which show English in those languages.
 */
data class Tr(
    val ja: String, val fr: String, val de: String, val it: String, val es: String,
    val br: String? = null, val pt: String? = null, val ko: String? = null,
) {
    fun of(lang: AppLanguage): String? = when (lang) {
        AppLanguage.EN -> null
        AppLanguage.JA -> ja
        AppLanguage.FR -> fr
        AppLanguage.DE -> de
        AppLanguage.IT -> it
        AppLanguage.ES -> es
        AppLanguage.BR -> br
        AppLanguage.PT -> pt
        AppLanguage.KO -> ko
    }
}

object L10n {
    /** The language on screen. Compose state, so every screen redraws when it changes. */
    var language: AppLanguage by mutableStateOf(AppLanguage.EN)
        private set

    /** What AUTO picked ([auto]), for the LANGUAGE row's "AUTO (ENGLISH)". */
    var autoLanguage: AppLanguage by mutableStateOf(AppLanguage.EN)
        private set

    /**
     * Applies [setting] ([LANGUAGE_AUTO] or an [AppLanguage.code]). AUTO = the
     * running ROM's language ([romCode], its header game code); with no ROM
     * (Library / Settings) the device's, else English. A language no ROM comes in
     * (Portuguese) wins over an English game too.
     */
    fun apply(setting: String, romCode: String?, device: Locale = Locale.getDefault()) {
        if (romCode != lastRomCode) gameLetter = null
        lastRomCode = romCode
        lastSetting = setting
        val game = if (romCode != null) gameLetter?.let { l -> AppLanguage.entries.firstOrNull { it.romLetter == l } } else null
        val rom = game ?: AppLanguage.ofRomCode(romCode)
        val dev = AppLanguage.ofLocale(device)
        // No game is in Portuguese: on a Portuguese device an English game shows the device's, not English.
        autoLanguage = if (rom != null && !(rom == AppLanguage.EN && dev?.romLetter == null && dev != null)) rom
            else dev ?: AppLanguage.EN
        language = AppLanguage.ofCode(setting) ?: autoLanguage
    }

    @Volatile private var lastRomCode: String? = null
    @Volatile private var lastSetting = LANGUAGE_AUTO
    @Volatile private var gameLetter: Char? = null

    /**
     * The running game's language, once the Poller has told which game it is: a translated hack keeps
     * its base game's header code (Unbound FR says BPRE, Quetzal Spanish BPEE), so AUTO follows this
     * instead until another ROM is applied.
     */
    fun applyGameLanguage(letter: Char) {
        if (gameLetter == letter) return
        gameLetter = letter
        apply(lastSetting, lastRomCode)
    }

    /** The LANGUAGE row's value: "AUTO (ENGLISH)" or the picked language's own name. */
    fun settingLabel(setting: String): String =
        AppLanguage.ofCode(setting)?.nativeName ?: "${tr("AUTO")} (${autoLanguage.nativeName})"

    /** The LANGUAGE selector's options, AUTO first: (setting value, label). */
    val options: List<Pair<String, String>>
        get() = listOf(LANGUAGE_AUTO to tr("AUTO")) + AppLanguage.entries.map { it.code to it.nativeName }
}

/** Every table, merged. Each area of the app keeps its own (TrCore, TrCompanion, ...). */
val translations: Map<String, Tr> by lazy { allTranslationTables().fold(emptyMap()) { acc, t -> acc + t } }

/** [en] in the current language (or [en] itself where there's no translation). */
fun tr(en: String): String {
    val lang = L10n.language
    if (lang != AppLanguage.EN) translations[en]?.of(lang)?.let { return it }
    return en.substringBefore('|')
}

/** [tr] with `{0}`, `{1}`... replaced by [args]. */
fun tr(en: String, vararg args: Any?): String {
    var s = tr(en)
    args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
    return s
}

/**
 * A string kept in English where the code uses it (an option compared by
 * label, an enum's label) and translated where it's drawn - OptionLine and
 * friends call [tr] on what they show. Only marks the literal for the tables.
 */
fun tk(en: String): String = en
