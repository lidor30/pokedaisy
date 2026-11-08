// A Game Boy cart's POKéDEX pages, read from its own bytes: the app's Gen1Dex.kt and Gen2Dex.kt in C. A cart is
// bank-switched, so everything is a file offset (pd_gb_dex's tables); 3ds/test/gb_check.c holds every page and picture
// of every cart against what the app reads.
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "pd_dex.h"
#include "pd_gb.h"
#include "pd_games.h"
#include "pd_map.h"

#define BREAK "\n"
#define PIC 56
#define MEW 151
#define PICS_FIX 0x36
#define CLEAR 0xFF000000u

static uint32_t off(int bank, int addr) {
    return bank == 0 ? (uint32_t) addr : (uint32_t) bank * 0x4000u + (uint32_t) (addr - 0x4000);
}

static int u8(const struct pd_game* g, uint32_t o) {
    return o < g->romSize ? g->rom[o] : 0;
}

static int u16(const struct pd_game* g, uint32_t o) {
    return u8(g, o) | u8(g, o + 1) << 8;
}

// bank << 16 | address, Polished Crystal's way of naming a table.
static uint32_t at(const struct pd_gb_dex* d, int k) {
    return off(d->polished[k] >> 16, d->polished[k] & 0xFFFF);
}

// --- text ---

struct text {
    char* p;
    size_t n, cap;
};

static void put(struct text* t, const char* s) {
    size_t l = strlen(s);
    if (t->n + l + 1 > t->cap) l = t->cap > t->n + 1 ? t->cap - t->n - 1 : 0;
    memcpy(t->p + t->n, s, l);
    t->n += l;
    t->p[t->n] = 0;
}

// split(' ').filter(nonempty).join(" ") over [len] bytes of [s], into [out].
static void collapse(const char* s, size_t len, struct text* out) {
    bool word = false, first = true;
    for (size_t i = 0; i < len; i++) {
        if (s[i] == ' ') {
            word = false;
            continue;
        }
        if (!word && !first) put(out, " ");
        char one[2] = { s[i], 0 };
        put(out, one);
        word = true;
        first = false;
    }
}

// Whether a byte of UTF-8 text at [i] ends a letter: ASCII, or the é / umlauts' second byte.
static bool letter_before(const char* s, size_t i) {
    unsigned char c = (unsigned char) s[i];
    return isalpha(c) || (c >= 0x80 && i > 0 && (unsigned char) s[i - 1] == 0xC3);
}

static bool lower_at(const char* s) {
    unsigned char c = (unsigned char) s[0];
    if (c == 0xC3) return (unsigned char) s[1] >= 0xA0;
    return islower(c);
}

// The second halves of hyphenated words Prism's and Polished Crystal's text breaks over two lines (COMPOUND_ENDS).
static bool compound_end(const char* s) {
    static const char* const ENDS[] = { "type", "like", "colored", "shaped", "bodied", "white", "strike", "covered", "pitched",
                                        "hard", "evolved", "smelling", "level", "lowering", "boost", "filled" };
    size_t n = 0;
    while (isalpha((unsigned char) s[n])) n++;
    for (size_t i = 0; i < sizeof(ENDS) / sizeof(*ENDS); i++)
        if (strlen(ENDS[i]) == n && !strncmp(ENDS[i], s, n)) return true;
    return false;
}

// The game's lines as one paragraph (Gen2Dex.flow): a word the game broke over two lines joined back.
static void flow(const char* raw, char* out, size_t cap) {
    struct text t = { out, 0, cap };
    out[0] = 0;
    for (const char* p = raw;;) {
        const char* end = strchr(p, '\n');
        size_t len = end ? (size_t) (end - p) : strlen(p);
        char line[256];
        struct text l = { line, 0, sizeof(line) };
        line[0] = 0;
        collapse(p, len, &l);
        if (l.n) {
            if (!t.n) {
                put(&t, line);
            } else if (t.n >= 2 && out[t.n - 1] == '-' && letter_before(out, t.n - 2) && lower_at(line)) {
                if (!compound_end(line)) out[--t.n] = 0;
                put(&t, line);
            } else {
                put(&t, " ");
                put(&t, line);
            }
        }
        if (!end) break;
        p = end + 1;
    }
}

// Gen 1 text from [o] until its end (@, or the dex text's DEXEND).
static void gen1_text(const struct pd_game* g, uint32_t o, const struct pd_gb_dex* d, char* out, size_t cap) {
    char raw[512];
    struct text t = { raw, 0, sizeof(raw) };
    raw[0] = 0;
    for (uint32_t i = o; i < g->romSize && i < o + 400;) {
        int c = u8(g, i++);
        if (c == 0x5F) { // DEXEND prints the entry's last full stop
            put(&t, ".");
            break;
        }
        if (c == 0x50) break;
        if (c == 0 && !t.n) continue; // the text command that opens a far text
        put(&t, d->chars[c]);
    }
    struct text r = { out, 0, cap };
    out[0] = 0;
    collapse(raw, t.n, &r);
}

// Crystal Clear's dictionary: a byte -> its string in bank 0, 0 = none.
static void dictionary(const struct pd_game* g, const struct pd_gb_dex* d, uint16_t* words) {
    memset(words, 0, 256 * sizeof(*words));
    if (!d->textDict) return;
    for (int c = 0x16; c <= 0x5F; c++) {
        if (c == 0x1F || c == 0x50) continue; // a space before the lookup / the end of the text
        uint32_t e = d->textDict + 3u * (uint32_t) (c - 0x16);
        int p = u16(g, e + 1);
        if (u8(g, e) == 0 && p < 0x4000) words[c] = (uint16_t) p;
    }
}

static void gen2_raw(const struct pd_game* g, const struct pd_gb_dex* d, uint32_t o, int pages, const uint16_t* words, struct text* t, int depth) {
    int left = pages;
    for (uint32_t i = o; i < g->romSize && i < o + 400;) {
        int c = u8(g, i++);
        if (c == 0x50) {
            if (--left <= 0) break;
            put(t, BREAK);
            continue;
        }
        if (words[c] && depth < 4) gen2_raw(g, d, words[c], 1, words, t, depth + 1);
        else put(t, d->chars[c]);
    }
}

static void gen2_text(const struct pd_game* g, const struct pd_gb_dex* d, uint32_t o, int pages, char* out, size_t cap) {
    uint16_t words[256];
    dictionary(g, d, words);
    char raw[640];
    struct text t = { raw, 0, sizeof(raw) };
    raw[0] = 0;
    gen2_raw(g, d, o, pages, words, &t, 0);
    flow(raw, out, cap);
}

// --- Prism's text engine (Gen2PackedText) ---

struct packed_run {
    const struct pd_game* g;
    const struct pd_gb_dex* d;
    uint8_t codes[1200];
    int count, steps;
    int next; // past the 0x57 that ended the text: what its caller reads on from, -1 = none
    bool bad;
};

static int run_byte(struct packed_run* r, int bank, int addr) {
    if (bank == 0 ? addr < 0 || addr >= 0x4000 : addr < 0x4000 || addr >= 0x8000) {
        r->bad = true;
        return 0;
    }
    uint32_t o = off(bank, addr);
    if (o >= r->g->romSize) {
        r->bad = true;
        return 0;
    }
    return r->g->rom[o];
}

static bool tick(struct packed_run* r) {
    if (++r->steps >= 6000 || r->count >= 1200) r->bad = true;
    return !r->bad;
}

static int rare(struct packed_run* r, int value) {
    int v = value;
    for (int i = 0; i < 8; i++) {
        int n = u8(r->g, r->d->packedRareRanges + 2u * (uint32_t) i);
        if (v < n) return (u8(r->g, r->d->packedRareRanges + 2u * (uint32_t) i + 1) - n + v) & 0xFF;
        v -= n;
    }
    r->bad = true;
    return 0;
}

// 03's stream from [start]: where the text goes on after it; *done = a done ended it.
static int huffman(struct packed_run* r, int bank, int start, bool* done) {
    int a = start, left = 0, cur = 0;
    *done = false;
#define BIT() (left == 0 ? (cur = run_byte(r, bank, a++), left = 8) : 0, left--, (cur >> left) & 1)
    for (;;) {
        if (!tick(r)) return a;
        int node = 0;
        do {
            int b = BIT();
            node = u8(r->g, r->d->packedTree + 2u * (uint32_t) node + (uint32_t) b);
        } while (node < 0x70 && !r->bad);
        int ch;
        if (node < 0x78) ch = node;
        else if (node < 0x7C) ch = node - 0x20;
        else if (node == 0x7D) {
            int v = 0;
            for (int i = 0; i < 4; i++) v = v << 1 | BIT();
            ch = u8(r->g, r->d->packedRareChars + (uint32_t) v);
        } else if (node == 0x7C) {
            int v = 0;
            for (int i = 0; i < 6; i++) v = v << 1 | BIT();
            ch = rare(r, v);
        } else if (node < 0xBC) ch = node;
        else if (node < 0xD0) ch = node - 0x78;
        else ch = node;
        if (ch == 0x50) return a;
        if (ch == 0x57) {
            r->next = a;
            *done = true;
            return a;
        }
        r->codes[r->count++] = (uint8_t) ch;
    }
#undef BIT
}

// The text at [bank]:[start] (00:3266): returns at its end, a 0x50 where a segment starts or a done.
static void run_text(struct packed_run* r, int bank, int start, int depth) {
    if (depth >= 8) {
        r->bad = true;
        return;
    }
    int a = start;
    for (;;) {
        if (!tick(r)) return;
        int c = run_byte(r, bank, a++);
        if (r->bad || c == 0x50) return;
        if (c == 0x03) {
            bool done;
            a = huffman(r, bank, a, &done);
            if (done || r->bad) return;
        } else if (c == 0x01) {
            // Code to run: only the UNITS option's choice of two texts is known, read as the metric one.
            bool ok = run_byte(r, bank, a) == 0xFA && run_byte(r, bank, a + 3) == 0xE6 && run_byte(r, bank, a + 4) == 0x04 &&
                      run_byte(r, bank, a + 5) == 0x21 && run_byte(r, bank, a + 8) == 0xC8 && run_byte(r, bank, a + 9) == 0x21 &&
                      run_byte(r, bank, a + 12) == 0xC9;
            if (!ok) {
                r->bad = true;
                return;
            }
            a = run_byte(r, bank, a + 6) | run_byte(r, bank, a + 7) << 8;
        } else if (c < 0x04) {
            r->bad = true;
            return;
        } else if (c < 0x44) {
            int to = (c + 0x3C) << 8 | run_byte(r, bank, a);
            int b = run_byte(r, bank, a + 1);
            a += 2;
            run_text(r, b & 0x7F, to, depth + 1);
            if (r->bad || b & 0x80) return;
        } else {
            // A plain string to its 0x50.
            int ch = c;
            while (ch != 0x50) {
                if (!tick(r)) return;
                if (ch == 0x57) {
                    r->next = a;
                    return;
                }
                r->codes[r->count++] = (uint8_t) ch;
                ch = run_byte(r, bank, a++);
                if (r->bad) return;
            }
        }
    }
}

// The word a control character's handler prints (`push de / ld de, word / jp`); false = it isn't one.
static bool packed_word(const struct pd_game* g, const struct pd_gb_dex* d, int c, struct text* out) {
    if (c < 0x44 || c > 0x5F) return false;
    int h = u16(g, d->packedControlChars + 2u * (uint32_t) (c - 0x44));
    if (h + 5 >= 0x4000 || u8(g, (uint32_t) h) != 0xD5 || u8(g, (uint32_t) h + 1) != 0x11 || u8(g, (uint32_t) h + 4) != 0xC3) return false;
    int s = u16(g, (uint32_t) h + 2);
    if (s >= 0x4000) return false;
    for (int i = s; i < s + 16 && u8(g, (uint32_t) i) != 0x50; i++) put(out, d->chars[u8(g, (uint32_t) i)]);
    return true;
}

// Prism's text at [bank]:[addr]; returns the address its caller reads on from (-1: none, -2: it didn't read).
static int packed_text(const struct pd_game* g, const struct pd_gb_dex* d, int bank, int addr, struct text* out) {
    static struct packed_run r;
    memset(&r, 0, sizeof(r));
    r.g = g;
    r.d = d;
    r.next = -1;
    run_text(&r, bank, addr, 0);
    if (r.bad) return -2;
    for (int i = 0; i < r.count; i++) {
        int c = r.codes[i];
        if (packed_word(g, d, c, out)) continue;
        if (c == 0x5F) put(out, BREAK);
        else put(out, d->chars[c]);
    }
    return r.next;
}

// --- Polished Crystal's text ---

static bool polished_end(int c) {
    return c == 0x53 || c == 0x52;
}

static void trim(struct text* t) {
    while (t->n && (t->p[t->n - 1] == ' ' || t->p[t->n - 1] == '\n')) t->p[--t->n] = 0;
    size_t i = 0;
    while (i < t->n && (t->p[i] == ' ' || t->p[i] == '\n')) i++;
    if (i) {
        memmove(t->p, t->p + i, t->n - i + 1);
        t->n -= i;
    }
}

// The string at [start], to its '@' or <DONE> (a line break read as one); returns the address after it.
static uint32_t polished_string(const struct pd_game* g, const struct pd_gb_dex* d, uint32_t start, struct text* out) {
    uint32_t o = start;
    for (;;) {
        int c = u8(g, o++);
        if (polished_end(c) || o > start + 800) break;
        if (c == 0x5D) {
            // <CTXT>: the rest is Huffman-coded, most significant bit first, to a terminator: node n's branches at
            // tree[2n], [2n + 1]; a value from 0x7F is a character, 0xEC-0xFB being 0x4D-0x5C.
            uint32_t tree = at(d, 25);
            int bits = 0, byte = 0;
            for (;;) {
                int node = 0;
                for (;;) {
                    if (bits == 0) {
                        byte = u8(g, o++);
                        bits = 8;
                    }
                    int bit = byte >> 7 & 1;
                    byte = byte << 1 & 0xFF;
                    bits--;
                    node = u8(g, tree + 2u * (uint32_t) node + (uint32_t) bit);
                    if (node >= 0x7F) break;
                }
                int ch = node >= 0xEC ? node - 0xEC + 0x4D : node;
                if (ch == 0x53 || ch == 0x52 || ch == 0x54 || o > start + 800) {
                    trim(out);
                    return o;
                }
                put(out, ch >= 0x55 && ch <= 0x59 ? BREAK : d->polishedChars[ch]);
            }
        }
        // <LNBRK> <NEXT> <LINE> <CONT> <PARA>: the next line.
        put(out, c >= 0x55 && c <= 0x59 ? BREAK : d->polishedChars[c]);
    }
    trim(out);
    return o;
}

// --- the pages ---

const struct pd_gb_dex* pd_gb2_dex(const struct pd_game* g) {
    return g->entry && g->cfg && g->cfg->gb ? g->entry->gbDex : NULL;
}

int pd_gb2_dex_species(const struct pd_game* g, int dex) {
    const struct pd_gb_dex* d = pd_gb2_dex(g);
    if (!d || dex < 1) return 0;
    if (d->dexOrder) return dex <= d->dexOrderCount ? d->dexOrder[dex - 1] : 0;
    return dex <= d->count ? dex : 0;
}

int pd_gb2_dex_number(const struct pd_game* g, int species) {
    const struct pd_gb_dex* d = pd_gb2_dex(g);
    if (!d || species < 1) return 0;
    // A Polished Crystal variant form counts as its species, as the dex flags do (guideDexSpecies).
    if (d->polishedSpecies && species > d->polishedSpecies) {
        int i = species - d->polishedSpecies - 1;
        if (i >= pd_gb_polished_variants_count) return 0;
        species = (int) (pd_gb_polished_variants[i] >> 8);
    }
    if (d->dexOrder) {
        for (int i = 0; i < d->dexOrderCount; i++)
            if (d->dexOrder[i] == species) return i + 1;
        return 0;
    }
    return species <= d->count ? species : 0;
}

static int internal_of(const struct pd_gb_config* c, int dex) {
    for (int i = 0, n = pd_gb_internal_count(c); i < n; i++)
        if (c->internalToDex[i] == dex) return i;
    return -1;
}

static uint32_t gen1_stats(const struct pd_gb_dex* d, int dex) {
    if (dex == MEW && d->mewBaseStats) return d->mewBaseStats;
    return d->baseData + (uint32_t) d->baseDataSize * (uint32_t) (dex - 1);
}

static bool gen1_entry(const struct pd_game* g, int dex, struct pd_dex_entry* e) {
    const struct pd_gb_dex* d = pd_gb2_dex(g);
    if (dex < 1 || dex > 151) return false;
    uint32_t o;
    if (d->dexEntryData) {
        // PureRGB: the record by Dex number, the category by its index among DexCategoryStrings.
        uint32_t s = d->categoryStrings;
        for (int n = u8(g, d->categoryMapping + (uint32_t) dex - 1); n > 0; n--) {
            while (s < g->romSize && u8(g, s) != 0x50) s++;
            s++;
        }
        gen1_text(g, s, d, e->category, sizeof(e->category));
        o = d->dexEntryData + 8u * (uint32_t) (dex - 1);
    } else {
        int internal = internal_of(g->cfg->gb, dex);
        if (internal <= 0) return false;
        int bank = (int) (d->dexEntryPointers >> 14);
        uint32_t page = off(bank, u16(g, d->dexEntryPointers + 2u * (uint32_t) (internal - 1)));
        o = page;
        while (o < g->romSize && u8(g, o) != 0x50) o++;
        gen1_text(g, page, d, e->category, sizeof(e->category));
        o++;
    }
    // English: feet, inches, pounds x 10; Europe: decimetres, hectograms (one byte less).
    int feet = u8(g, o), inches = u8(g, o + 1), lbs10 = u16(g, o + 2);
    int dm = u8(g, o), hg = u16(g, o + 1);
    uint32_t far = o + (d->metric ? 3 : 4);
    // text_far: the command, address, bank.
    if (u8(g, far) == d->textFar) gen1_text(g, off(u8(g, far + 3), u16(g, far + 1)), d, e->description, sizeof(e->description));
    uint32_t s = gen1_stats(d, dex);
    e->national = e->species = dex;
    if (d->metric) {
        e->heightDm = dm;
        e->weightHg = hg;
    } else {
        e->heightIn = feet * 12 + inches;
        e->weightLbs10 = lbs10;
        e->heightDm = (int) ((double) e->heightIn * 2.54 / 10 + 0.5);
        e->weightHg = (int) ((double) lbs10 * 0.45359237 + 0.5);
    }
    e->type1 = u8(g, s + 6);
    e->type2 = u8(g, s + 7);
    // Gen 1 has one SPECIAL, in its own order.
    for (int i = 0; i < 5; i++) e->stats[i] = u8(g, s + 1 + (uint32_t) i);
    e->statCount = 5;
    return true;
}

static bool polished_entry(const struct pd_game* g, int dex, int species, struct pd_dex_entry* e) {
    const struct pd_gb_dex* d = pd_gb2_dex(g);
    uint32_t i = (uint32_t) species - 1;
    uint32_t p = at(d, 11) + 3 * i;
    uint32_t o = off(u8(g, p), u16(g, p + 1));
    struct text cat = { e->category, 0, sizeof(e->category) };
    e->category[0] = 0;
    o = polished_string(g, d, o, &cat);
    char raw[400], a[200], b[200];
    a[0] = b[0] = 0;
    struct text page1 = { a, 0, sizeof(a) }, page2 = { b, 0, sizeof(b) };
    o = polished_string(g, d, o, &page1);
    polished_string(g, d, o, &page2);
    snprintf(raw, sizeof(raw), "%s" BREAK "%s", a, b);
    flow(raw, e->description, sizeof(e->description));
    uint32_t body = at(d, 12) + 4 * i, s = at(d, 15) + (uint32_t) d->baseDataSize * i;
    e->national = dex;
    e->species = species;
    e->heightDm = u8(g, body);
    e->weightHg = u16(g, body + 1);
    e->type1 = u8(g, s + 6);
    e->type2 = u8(g, s + 7);
    // BaseData: HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF -> the page's HP, ATK, DEF, SP. ATK, SP. DEF, SPEED.
    static const int ORDER[6] = { 0, 1, 2, 4, 5, 3 };
    for (int k = 0; k < 6; k++) e->stats[k] = u8(g, s + (uint32_t) ORDER[k]);
    return true;
}

static bool gen2_entry(const struct pd_game* g, int dex, struct pd_dex_entry* e) {
    const struct pd_gb_dex* d = pd_gb2_dex(g);
    int species = pd_gb2_dex_species(g, dex);
    if (species <= 0) return false;
    if (d->polished) return polished_entry(g, dex, species, e);
    int bank = d->dexEntryFirstBank >= 0 ? d->dexEntryFirstBank + ((species - 1) >> 6) : u8(g, d->dexEntryBanks + (uint32_t) ((species - 1) >> 6));
    int ptr = u16(g, d->dexEntryPointers + 2u * (uint32_t) (species - 1));
    if (d->dexEntryFar) {
        // Crystal Legacy: a far pointer per species (bank, then address), as the app's Gen2Dex.entry.
        uint32_t far = d->dexEntryPointers + 3u * (uint32_t) (species - 1);
        bank = u8(g, far);
        ptr = u16(g, far + 1);
    }
    uint32_t s = d->baseData + (uint32_t) d->baseDataSize * (uint32_t) (species - 1);
    if (d->packedTree) {
        // Prism: the category, then (where its done left off) HT, WT and page 1's length, then the two pages.
        char raw[640];
        raw[0] = 0;
        struct text t = { raw, 0, sizeof(raw) };
        int next = packed_text(g, d, bank, ptr, &t);
        if (next < 0) return false;
        flow(raw, e->category, sizeof(e->category));
        uint32_t o = off(bank, next);
        e->heightDm = u16(g, o);
        e->weightHg = u16(g, o + 2);
        raw[0] = 0;
        t.n = 0;
        if (packed_text(g, d, bank, next + 5, &t) == -2) return false;
        put(&t, BREAK);
        if (packed_text(g, d, bank, next + 5 + u8(g, o + 4), &t) == -2) return false;
        flow(raw, e->description, sizeof(e->description));
    } else {
        uint32_t page = off(bank, ptr), o = page;
        while (o < g->romSize && u8(g, o) != 0x50) o++;
        gen2_text(g, d, page, 1, e->category, sizeof(e->category));
        o++;
        int height = u16(g, o); // feet x 100 + inches
        e->weightLbs10 = u16(g, o + 2);
        e->heightIn = (height / 100) * 12 + height % 100;
        e->heightDm = (int) ((double) e->heightIn * 2.54 / 10 + 0.5);
        e->weightHg = (int) ((double) e->weightLbs10 * 0.45359237 + 0.5);
        gen2_text(g, d, o + 4, 2, e->description, sizeof(e->description));
    }
    e->national = dex;
    e->species = species;
    e->type1 = u8(g, s + 7);
    e->type2 = u8(g, s + 8);
    static const int ORDER[6] = { 1, 2, 3, 5, 6, 4 };
    for (int k = 0; k < 6; k++) e->stats[k] = u8(g, s + (uint32_t) ORDER[k]);
    return true;
}

bool pd_gb2_dex_entry(const struct pd_game* g, int dex, struct pd_dex_entry* out) {
    if (!pd_gb2_dex(g) || !g->rom) return false;
    return g->cfg->gb->gen == 1 ? gen1_entry(g, dex, out) : gen2_entry(g, dex, out);
}

// --- the pictures ---

// Gen 1's sprite compression (home/uncompress.asm): two 1bpp chunks, RLE-packed bit pairs, delta-coded.
struct pic {
    const struct pd_game* g;
    uint32_t pos;
    int cur, left;
    int w, h; // tiles
    bool bad;
    uint8_t buf[2][8 * 64];
};

static int pic_bit(struct pic* p) {
    if (p->left == 0) {
        if (p->pos >= p->g->romSize) {
            p->bad = true;
            return 0;
        }
        p->cur = p->g->rom[p->pos++];
        p->left = 8;
    }
    p->left--;
    return p->cur >> p->left & 1;
}

static void pic_chunk(struct pic* p, uint8_t* b) {
    int height = p->h * 8, col = 0, y = 0, shift = 6;
    bool done = false;
    bool rle = pic_bit(p) == 0;
#define PUT(pair) do { \
        b[col * height + y] = (uint8_t) (b[col * height + y] | (pair) << shift); \
        if (++y == height) { \
            y = 0; \
            if (shift > 0) shift -= 2; \
            else { \
                shift = 6; \
                if (++col == p->w) done = true; \
            } \
        } \
    } while (0)
    while (!done && !p->bad) {
        if (rle) {
            int c = 0;
            while (pic_bit(p) == 1 && !p->bad) c++;
            if (c > 14) {
                p->bad = true;
                return;
            }
            int v = 0;
            for (int i = 0; i <= c; i++) v = v << 1 | pic_bit(p);
            int n = v + (1 << (c + 1)) - 1;
            for (int i = 0; i < n && !done; i++) PUT(0);
            rle = false;
        } else {
            int pair = pic_bit(p) << 1;
            pair |= pic_bit(p);
            if (pair == 0) rle = true;
            else PUT(pair);
        }
    }
#undef PUT
}

static int nybble(int n, int* last) {
    static const uint8_t DECODE0[8] = { 0x01, 0x32, 0x76, 0x45, 0xFE, 0xCD, 0x89, 0xBA };
    static const uint8_t DECODE1[8] = { 0xFE, 0xCD, 0x89, 0xBA, 0x01, 0x32, 0x76, 0x45 };
    int e = (*last & 1 ? DECODE1 : DECODE0)[n >> 1];
    *last = n & 1 ? e & 0xF : e >> 4;
    return *last;
}

static void pic_delta(const struct pic* p, uint8_t* b) {
    int height = p->h * 8;
    for (int y = 0; y < height; y++) {
        int last = 0;
        for (int col = 0; col < p->w; col++) {
            int v = b[col * height + y];
            int hi = nybble(v >> 4, &last);
            int lo = nybble(v & 0xF, &last);
            b[col * height + y] = (uint8_t) (hi << 4 | lo);
        }
    }
}

static bool pic_decompress(struct pic* p, const struct pd_game* g, uint32_t start) {
    memset(p, 0, sizeof(*p));
    p->g = g;
    p->pos = start;
    int dims = u8(g, p->pos++);
    p->h = dims & 0xF;
    p->w = dims >> 4;
    if (!p->w || !p->h || p->w > 8 || p->h > 8) return false;
    int size = p->w * p->h * 8;
    bool firstIn2 = pic_bit(p) == 1;
    uint8_t* first = p->buf[firstIn2];
    uint8_t* second = p->buf[!firstIn2];
    pic_chunk(p, first);
    int mode = pic_bit(p) == 0 ? 0 : 1 + pic_bit(p);
    pic_chunk(p, second);
    if (p->bad) return false;
    if (mode == 0) {
        pic_delta(p, p->buf[0]);
        pic_delta(p, p->buf[1]);
    } else {
        if (mode == 1) {
            pic_delta(p, first);
        } else {
            pic_delta(p, second);
            pic_delta(p, first);
        }
        for (int i = 0; i < size; i++) second[i] ^= first[i];
    }
    return true;
}

static void clear(uint32_t* out) {
    for (int i = 0; i < PD_SPRITE * PD_SPRITE; i++) out[i] = CLEAR;
}

// The picture's 56 x 56 box sits in the middle of the page's 64 x 64.
#define BOX(out, x, y) out[((y) + (PD_SPRITE - PIC) / 2) * PD_SPRITE + (x) + (PD_SPRITE - PIC) / 2]

static uint32_t gen1_channel(int v) {
    return (uint32_t) ((v & 31) * 255 / 31);
}

static bool gen1_sprite(const struct pd_game* g, int dex, uint32_t* out) {
    const struct pd_gb_dex* d = pd_gb2_dex(g);
    if (dex < 1 || dex > 151) return false;
    uint32_t s = gen1_stats(d, dex);
    int ptr = u16(g, s + 11), bank;
    if (d->picBankOffset >= 0) {
        bank = u8(g, s + (uint32_t) d->picBankOffset);
    } else {
        int internal = internal_of(g->cfg->gb, dex);
        if (internal <= 0) return false;
        if (internal == d->fossilKabutops) bank = d->fossilKabutopsBank;
        else if (dex == MEW && d->mewBaseStats) bank = d->mewPicsBank;
        else {
            bank = d->picsBank;
            for (int i = 0; i < 4; i++) bank += internal >= d->picsBankBreaks[i];
        }
    }
    static struct pic p;
    if (!pic_decompress(&p, g, off(bank, ptr))) return false;
    uint32_t pal = d->colors + 8u * (uint32_t) u8(g, d->palettes + (uint32_t) dex);
    uint32_t colors[4];
    for (int i = 0; i < 4; i++) {
        int c = u16(g, pal + 2u * (uint32_t) i);
        colors[i] = gen1_channel(c) << 16 | gen1_channel(c >> 5) << 8 | gen1_channel(c >> 10);
    }
    clear(out);
    int ox = ((8 - p.w) / 2) * 8, oy = (7 - p.h) * 8, height = p.h * 8;
    for (int col = 0; col < p.w; col++) {
        for (int y = 0; y < height; y++) {
            int lo = p.buf[0][col * height + y], hi = p.buf[1][col * height + y];
            for (int bx = 0; bx < 8; bx++) {
                int v = (lo >> (7 - bx) & 1) | (hi >> (7 - bx) & 1) << 1;
                int px = ox + col * 8 + bx, py = oy + y;
                if (px >= 0 && px < PIC && py >= 0 && py < PIC) BOX(out, px, py) = v ? colors[v] : CLEAR;
            }
        }
    }
    return true;
}

static uint32_t gen2_channel(int v) {
    return (uint32_t) ((v & 31) << 3 | (v & 31) >> 2);
}

// A [size] x [size]-tile pic ([tiles], columns first) in its 7 x 7 box, in the two colours at [pal] (+ clear, black).
static bool place_pic(const struct pd_game* g, const uint8_t* tiles, int have, int size, uint32_t pal, uint32_t* out) {
    if (size < 1 || size > 7 || have < size * size * 16) return false;
    uint32_t colors[4] = { CLEAR, 0, 0, 0 };
    for (int i = 0; i < 2; i++) {
        int c = u16(g, pal + 2u * (uint32_t) i);
        colors[i + 1] = gen2_channel(c) << 16 | gen2_channel(c >> 5) << 8 | gen2_channel(c >> 10);
    }
    clear(out);
    int ox = ((7 - size + 1) / 2) * 8, oy = (7 - size) * 8;
    for (int tile = 0; tile < size * size; tile++) {
        int tx = tile / size, ty = tile % size;
        for (int y = 0; y < 8; y++) {
            int lo = tiles[tile * 16 + 2 * y], hi = tiles[tile * 16 + 2 * y + 1];
            for (int x = 0; x < 8; x++) {
                int v = (lo >> (7 - x) & 1) | (hi >> (7 - x) & 1) << 1;
                BOX(out, ox + tx * 8 + x, oy + ty * 8 + y) = colors[v];
            }
        }
    }
    return true;
}

static bool gen2_sprite(const struct pd_game* g, int species, uint32_t* out) {
    const struct pd_gb_dex* d = pd_gb2_dex(g);
    if (pd_gb2_dex_number(g, species) == 0) return false;
    static uint8_t tiles[0x4000];
    if (d->polished) {
        // PokemonPicPointers' {bank, front, back, frames} by index, its size a nybble of PokemonPicSizes (the first of
        // a byte's two in its high half), its LZ its own, coloured from PokemonPalettes (index + 1).
        int i = species - 1;
        int b = u8(g, at(d, 14) + (uint32_t) (i / 2));
        int sz = i % 2 == 0 ? b >> 4 : b & 0xF;
        uint32_t e = at(d, 13) + 7u * (uint32_t) i;
        int have = pd_gb_lz(g->rom, g->romSize, off(u8(g, e), u16(g, e + 1)), tiles, sizeof(tiles), true);
        return have > 0 && place_pic(g, tiles, have, sz, at(d, 2) + 8u * (uint32_t) species, out);
    }
    uint32_t s = d->baseData + (uint32_t) d->baseDataSize * (uint32_t) (species - 1);
    int size = u8(g, s + 17) & 0xF;
    if (size < 1 || size > 7) return false;
    // UNOWN's pics are by letter: A's for the dex.
    uint32_t ptrs = species == d->unown ? d->unownPicPointers : d->picPointers + 6u * (uint32_t) (species - 1);
    int bank;
    if (d->picBankDirect) {
        bank = u8(g, ptrs);
    } else if (d->picBankFixes) {
        // FixPicBank (Gold / Silver): the stored bank, or the one its {stored, real} pair names.
        int stored = u8(g, ptrs);
        bank = stored;
        for (uint32_t o = d->picBankFixes; u8(g, o) != 0xFF && o < g->romSize; o += 2) {
            if (u8(g, o) == stored) {
                bank = u8(g, o + 1);
                break;
            }
        }
    } else {
        // FixPicBank: the stored bank byte is an index into PicsBanks, offset by its first bank - PICS_FIX.
        bank = u8(g, d->picsBanks + (uint32_t) (u8(g, ptrs) - (u8(g, d->picsBanks) - PICS_FIX)));
    }
    int have = pd_gb_lz(g->rom, g->romSize, off(bank, u16(g, ptrs + 1)), tiles, sizeof(tiles), false);
    return have > 0 && place_pic(g, tiles, have, size, d->palettes + 8u * (uint32_t) species, out);
}

bool pd_gb2_dex_sprite(const struct pd_game* g, int species, uint32_t* out) {
    if (!pd_gb2_dex(g) || !g->rom) return false;
    return g->cfg->gb->gen == 1 ? gen1_sprite(g, species, out) : gen2_sprite(g, species, out);
}

// --- pret's LZ ---

int pd_gb_lz(const uint8_t* rom, size_t romSize, uint32_t start, uint8_t* out, int max, bool polished) {
    int n = 0;
    uint32_t p = start;
    while (n < max && p < romSize) {
        int b = rom[p++];
        if (b == 0xFF) break;
        int cmd = b >> 5, length;
        if (cmd == 7) {
            cmd = b >> 2 & 7;
            length = (((b & (polished ? 1 : 3)) << 8) | rom[p++]) + 1;
        } else {
            length = (b & 0x1F) + 1;
        }
        switch (cmd) {
        case 0:
            for (int i = 0; i < length && n < max; i++) out[n++] = rom[p + (uint32_t) i];
            p += (uint32_t) length;
            break;
        case 1: {
            int v = rom[p++], times = polished ? length + 1 : length;
            for (int i = 0; i < times && n < max; i++) out[n++] = (uint8_t) v;
            break;
        }
        case 2: {
            int v0 = rom[p++], v1 = rom[p++], times = polished ? length + 2 : length;
            for (int i = 0; i < times && n < max; i++) out[n++] = (uint8_t) (i & 1 ? v1 : v0);
            break;
        }
        case 3:
            for (int i = 0; i < length && n < max; i++) out[n++] = 0;
            break;
        default: {
            int o = rom[p++];
            int from = o & 0x80 ? n - (o & 0x7F) - 1 : (o << 8 | rom[p++]);
            if (from < 0 || from >= n) return -1;
            for (int i = 0; i < length && n < max; i++) {
                if (cmd == 4) {
                    out[n++] = out[from + i];
                } else if (cmd == 5) {
                    int v = out[from + i], f = 0;
                    for (int k = 0; k < 8; k++) f |= (v >> k & 1) << (7 - k);
                    out[n++] = (uint8_t) f;
                } else {
                    if (from - i < 0) return -1;
                    out[n++] = out[from - i];
                }
            }
            break;
        }
        }
    }
    return n;
}
