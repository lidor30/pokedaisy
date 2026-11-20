#include "pd_tr.h"

#include <stdbool.h>
#include <stdio.h>
#include <string.h>

static const char* const NAMES[PD_LANGS] = { "ENGLISH", "FRANÇAIS", "DEUTSCH", "ITALIANO", "ESPAÑOL", "PORTUGUÊS (BR)",
                                             "PORTUGUÊS (PT)" };
static const char* const CODES[PD_LANGS] = { "EN", "FR", "DE", "IT", "ES", "BR", "PT" };

static int current = PD_LANG_EN, autoLang = PD_LANG_EN, systemLang = PD_LANG_EN, forced = -1;

void pd_tr_force(int lang) {
    forced = lang >= 0 && lang < PD_LANGS ? lang : -1;
    if (forced >= 0) current = forced;
}

const char* pd_lang_name(int lang) { return lang >= 0 && lang < PD_LANGS ? NAMES[lang] : "AUTO"; }
const char* pd_lang_code(int lang) { return lang >= 0 && lang < PD_LANGS ? CODES[lang] : "AUTO"; }

const char* pd_lang_label(int setting, char* buf, size_t n) {
    if (setting >= 0 && setting < PD_LANGS) return NAMES[setting];
    snprintf(buf, n, "%s (%s)", pd_tr("AUTO"), NAMES[autoLang]);
    return buf;
}

int pd_lang_of_code(const char* code) {
    for (int i = 0; code && i < PD_LANGS; i++)
        if (!strcmp(code, CODES[i])) return i;
    return PD_LANG_AUTO;
}

int pd_lang_of_letter(char letter) {
    switch (letter) {
    case 'E': return PD_LANG_EN;
    case 'F': return PD_LANG_FR;
    case 'D': return PD_LANG_DE;
    case 'I': return PD_LANG_IT;
    case 'S': return PD_LANG_ES;
    default: return -1;
    }
}

int pd_tr_language(void) { return current; }
int pd_tr_auto(void) { return autoLang; }

void pd_tr_set_system(int lang) { systemLang = lang >= 0 && lang < PD_LANGS ? lang : PD_LANG_EN; }

void pd_tr_apply(int setting, char gameLetter) {
    // As the app's L10n.apply: the game's language, else the system's; no game is in Portuguese, so a Portuguese
    // system's wins over an English game too. A Japanese / Korean game (no glyphs here yet) falls back to the system's.
    int game = gameLetter ? pd_lang_of_letter(gameLetter) : -1;
    bool portuguese = systemLang == PD_LANG_BR || systemLang == PD_LANG_PT;
    autoLang = game >= 0 && !(game == PD_LANG_EN && portuguese) ? game : systemLang;
    current = forced >= 0 ? forced : setting >= 0 && setting < PD_LANGS ? setting : autoLang;
}

static const struct pd_tr_entry* find(const char* en) {
    int lo = 0, hi = pd_tr_table_count - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2, d = strcmp(en, pd_tr_table[mid].key);
        if (d == 0) return &pd_tr_table[mid];
        if (d < 0) hi = mid - 1;
        else lo = mid + 1;
    }
    return NULL;
}

const char* pd_tr_in(int lang, const char* en) {
    if (!en) return en;
    // English needs a look only for a key with a context ("NEXT|continue").
    if (lang <= PD_LANG_EN || lang >= PD_LANGS) {
        if (!strchr(en, '|')) return en;
        lang = PD_LANG_EN;
    }
    const struct pd_tr_entry* e = find(en);
    if (e && e->text[lang]) return e->text[lang];
    return e ? e->text[PD_LANG_EN] : en;
}

const char* pd_tr(const char* en) { return pd_tr_in(current, en); }

char* pd_trf(char* out, size_t n, const char* en, const char* a0, const char* a1, const char* a2, const char* a3) {
    if (!out || n == 0) return out;
    const char* args[4] = { a0, a1, a2, a3 };
    const char* s = pd_tr(en);
    size_t o = 0;
    for (; *s && o + 1 < n; s++) {
        if (s[0] == '{' && s[1] >= '0' && s[1] <= '3' && s[2] == '}') {
            const char* a = args[s[1] - '0'];
            for (; a && *a && o + 1 < n; a++) out[o++] = *a;
            s += 2;
        } else {
            out[o++] = *s;
        }
    }
    out[o] = '\0';
    return out;
}
