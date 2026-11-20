// The menus' own text in the app's languages (the app's companion/i18n: L10n.kt's tr()). Game data (Pokémon, moves,
// items, places) isn't translated here: it comes from the game's tables, in the game's language.
//
// Text is keyed by its English, as in the app: pd_tr("CLOSE GAME") is the current language's version, or the English
// itself where there's none. One English word with two meanings has a context after a '|' ("NEXT|continue"), which
// English drops. Arguments go in as {0}, {1}: pd_trf(buf, sizeof buf, "{0} LEFT", "3"). The table (pd_tr_gen.c) is
// written by 3ds/tools/gen_tr.py from the app's Tr*.kt and 3ds/tools/tr_3ds.txt (the 3DS's own lines), keeping only
// the keys the 3DS / Linux code has as string literals. The menu pieces (rows, buttons, titles, notices, tabs) translate
// what they draw, so a literal handed to them needs no call of its own; a literal drawn with pd_text directly does.
#ifndef PD_TR_H
#define PD_TR_H

#include <stddef.h>

// The languages, in the app's AppLanguage order less Japanese / Korean (their glyphs aren't in the 3DS's fonts yet).
enum pd_lang { PD_LANG_EN, PD_LANG_FR, PD_LANG_DE, PD_LANG_IT, PD_LANG_ES, PD_LANG_BR, PD_LANG_PT, PD_LANGS };

// LANGUAGE's setting: PD_LANG_AUTO, else an enum pd_lang.
#define PD_LANG_AUTO (-1)

// A language's own name for the LANGUAGE row ("DEUTSCH"), and its short code for the settings file ("DE").
const char* pd_lang_name(int lang);
const char* pd_lang_code(int lang);
// LANGUAGE's value, as the app's L10n.settingLabel: the picked language's own name, or "AUTO (DEUTSCH)" in [buf].
const char* pd_lang_label(int setting, char* buf, size_t n);
// A settings file's code back to the setting (PD_LANG_AUTO for "AUTO" or anything unknown).
int pd_lang_of_code(const char* code);

// The language on screen. Process-wide, set on the render thread (pd_look_apply).
int pd_tr_language(void);
// What AUTO picks now: the game's language (its header's letter, 0 = no game), else the system's.
int pd_tr_auto(void);
// The setting (PD_LANG_AUTO or a language) and the running game's language letter ('E', 'D', ... 0 = none).
void pd_tr_apply(int setting, char gameLetter);
// The console's / desktop's language (an enum pd_lang), set once by the host at start. Default English.
void pd_tr_set_system(int lang);
// The language for a header letter ('F' French, 'D' German, 'I' Italian, 'S' Spanish, 'E' English), -1 = none of these.
int pd_lang_of_letter(char letter);

// The desktop previews' PD_PREVIEW_LANG: this language whatever the setting (-1 = off).
void pd_tr_force(int lang);

// [en] in the current language, or [en] itself (before any '|') where there's none. Never NULL for a non-NULL [en].
const char* pd_tr(const char* en);
// [en] in [lang].
const char* pd_tr_in(int lang, const char* en);
// pd_tr(en) with {0} ... {3} replaced by the arguments (NULL = an empty string), into [out]. Returns [out].
char* pd_trf(char* out, size_t n, const char* en, const char* a0, const char* a1, const char* a2, const char* a3);
#define PD_TRF1(out, en, a) pd_trf(out, sizeof(out), en, a, NULL, NULL, NULL)
#define PD_TRF2(out, en, a, b) pd_trf(out, sizeof(out), en, a, b, NULL, NULL)

// The generated table: sorted by key (strcmp), each key's display English then the translations in enum order from FR.
struct pd_tr_entry {
    const char* key;
    const char* text[PD_LANGS]; // [PD_LANG_EN] = the key without its '|' context
};
extern const struct pd_tr_entry pd_tr_table[];
extern const int pd_tr_table_count;

#endif
