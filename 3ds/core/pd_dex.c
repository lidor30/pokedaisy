#include "pd_dex.h"

#include <ctype.h>
#include <stdio.h>
#include <string.h>

#include "pd_gb.h"
#include "pd_map.h"
#include "pd_romart.h"
#include "pd_smol.h"
#include "pd_snapshot.h"
#include "pd_tables.h"

// Species ids and national numbers are bounded by the largest game's (Elite Redux: 2902 species).
#define SPECIES_MAX 2903
#define NATIONAL_MAX (PD_DEX_BYTES * 8)

static uint16_t u16(const uint8_t* p) {
    return (uint16_t) (p[0] | p[1] << 8);
}

static uint32_t u32(const uint8_t* p) {
    return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24;
}

// species -> national and back, built once per ROM.
static unsigned tablesRom;
static uint16_t toNational[SPECIES_MAX];
static uint16_t toSpecies[NATIONAL_MAX + 1];
static bool available;

// The generated 0 means retail's 412 species.
static int species_count(const struct pd_config* cfg) {
    return cfg->speciesCount ? cfg->speciesCount : 412;
}

static bool in_rom(const struct pd_game* g, uint32_t addr) {
    return addr >= 0x08000000 && addr < 0x08000000 + g->romSize;
}

// The entry's category text (the game's own language) and its height / weight offsets, as the probe and the page read them.
static void entry_category(const struct pd_game* g, const uint8_t* e, char* out, size_t outLen) {
    const struct pd_config* cfg = g->cfg;
    out[0] = 0;
    if (cfg->entryCategoryPtr) {
        uint32_t p = u32(e);
        const uint8_t* s = in_rom(g, p) ? pd_rom_at(g, p, (size_t) cfg->entryCategoryLen + 1) : NULL;
        if (s) pd_gen3_text(s, (size_t) cfg->entryCategoryLen + 1, out, outLen);
    } else {
        pd_gen3_text(e, (size_t) cfg->entryCategoryLen, out, outLen);
    }
}

static bool same_text(const char* a, const char* b) {
    for (; *a && *b; a++, b++) {
        if (tolower((unsigned char) *a) != tolower((unsigned char) *b)) return false;
    }
    return *a == *b;
}

static void build(const struct pd_game* g) {
    if (tablesRom == pd_rom_id(g)) return;
    tablesRom = pd_rom_id(g);
    available = false;
    memset(toNational, 0, sizeof(toNational));
    memset(toSpecies, 0, sizeof(toSpecies));
    const struct pd_config* cfg = g->cfg;
    if (!cfg || !g->rom || (!cfg->dexEntries && !cfg->expansion)) return;
    int speciesCount = species_count(cfg), nationalCount = cfg->nationalCount;
    if (speciesCount < 2 || speciesCount > SPECIES_MAX || nationalCount < 1 || nationalCount > NATIONAL_MAX) return;
    if (cfg->expansion) {
        // The page lives in gSpeciesInfo (the app's speciesNationals): each entry holds its own national number. As the
        // app's BestEffort.dexHolds, species 1 must read national 1, 0.7 m, 6.9 kg.
        const struct pd_dex_x* x = cfg->expansion;
        const uint8_t* one = pd_rom_at(g, cfg->speciesInfo + (uint32_t) x->stride, (size_t) x->natDexOff + 6);
        if (!one || u16(one + x->natDexOff) != 1 || u16(one + x->natDexOff + 2) != 7 || u16(one + x->natDexOff + 4) != 69) return;
        for (int s = 1; s < speciesCount; s++) {
            const uint8_t* e = pd_rom_at(g, cfg->speciesInfo + (uint32_t) s * (uint32_t) x->stride + (uint32_t) x->natDexOff, 2);
            if (!e) return;
            int n = u16(e);
            toNational[s] = (uint16_t) n;
            if (n >= 1 && n <= nationalCount && !toSpecies[n]) toSpecies[n] = (uint16_t) s;
        }
        available = true;
        return;
    }
    // The app's pokedexMatchesRom: entry 1 reads as the game's first Pokémon, and so do the species tables.
    size_t eLen = (size_t) cfg->entryHeightOff + 4;
    if (eLen < 0x10) eLen = 0x10;
    const uint8_t* e1 = pd_rom_at(g, cfg->dexEntries + cfg->dexEntryStride, eLen);
    const uint8_t* nat = pd_rom_at(g, cfg->speciesToNational, (size_t) (speciesCount - 1) * 2);
    const uint8_t* pic = pd_rom_at(g, cfg->frontPics + 8u * (uint32_t) cfg->probeSpecies + 6, 2);
    if (!e1 || !nat || !pic) return;
    char category[24];
    entry_category(g, e1, category, sizeof(category));
    if (!same_text(category, cfg->probeCategory) || u16(e1 + cfg->entryHeightOff) != cfg->probeHeight ||
        u16(e1 + cfg->entryHeightOff + 2) != cfg->probeWeight || u16(nat + (cfg->probeSpecies - 1) * 2) != 1 ||
        u16(pic) != cfg->probeSpecies) {
        return;
    }
    if (cfg->nationalToSpecies) {
        // Only this way round in the ROM (Celia): invert it, the lowest number winning.
        const uint8_t* raw = pd_rom_at(g, cfg->nationalToSpecies, (size_t) (nationalCount + 1) * 2);
        if (!raw) return;
        for (int n = nationalCount; n >= 1; n--) {
            int s = u16(raw + n * 2);
            if (s >= 1 && s < speciesCount) toNational[s] = (uint16_t) n;
        }
        for (int n = 1; n <= nationalCount; n++) {
            int s = u16(raw + n * 2);
            toSpecies[n] = s < speciesCount ? (uint16_t) s : 0;
        }
    } else {
        for (int s = 1; s < speciesCount; s++) {
            int n = u16(nat + (s - 1) * 2);
            toNational[s] = (uint16_t) n;
            // The lowest species id wins (the app's inverse).
            if (n >= 1 && n <= nationalCount && !toSpecies[n]) toSpecies[n] = (uint16_t) s;
        }
    }
    available = true;
}

bool pd_dex_available(const struct pd_game* g) {
    if (g->cfg->gb) return g->cfg->gb->dexCount > 0;  // a Game Boy game: the list from the save's flags only
    build(g);
    return available;
}

int pd_dex_species(const struct pd_game* g, int national) {
    if (g->cfg->gb) return national >= 1 && national <= g->cfg->gb->dexCount ? national : 0;  // species ids are Dex numbers
    build(g);
    return available && national >= 1 && national <= g->cfg->nationalCount ? toSpecies[national] : 0;
}

int pd_dex_national(const struct pd_game* g, int species) {
    build(g);
    return available && species >= 1 && species < species_count(g->cfg) ? toNational[species] : 0;
}

int pd_dex_regional(const struct pd_game* g, int i) {
    const struct pd_config* cfg = g->cfg;
    if (!cfg || i < 1 || i > cfg->regionalCount) return 0;
    if (!cfg->regionalOrder) return i; // Kanto: national 1..regionalCount
    int w = cfg->regionalOrderStride == 4 ? 4 : 2;
    const uint8_t* p = pd_rom_at(g, cfg->regionalOrder + (uint32_t) (i - 1) * (uint32_t) w, (size_t) w);
    if (!p) return 0;
    int id = w == 4 ? (int) u32(p) : u16(p);
    if (!cfg->regionalOrderIsSpecies) return id;
    return id >= 1 && id < species_count(cfg) ? pd_dex_national(g, id) : 0;
}

// An ability's name by id (gAbilityNames: records of abilityNameStride bytes holding the name or a pointer to it).
static void ability_name(const struct pd_game* g, int id, char* out, size_t outLen) {
    const struct pd_config* cfg = g->cfg;
    out[0] = 0;
    if (id <= 0 || !cfg->abilityNames) return;
    uint32_t stride = (uint32_t) (cfg->abilityNameStride > 0 ? cfg->abilityNameStride : cfg->abilityNameLength);
    size_t len = (size_t) cfg->abilityNameLength;
    const uint8_t* at = pd_rom_at(g, cfg->abilityNames + (uint32_t) id * stride, cfg->abilityNamePtr ? 4 : len);
    if (!at) return;
    if (cfg->abilityNamePtr) {
        uint32_t p = u32(at);
        at = in_rom(g, p) ? pd_rom_at(g, p, len) : NULL;
        if (!at) return;
    }
    pd_gen3_text(at, len, out, outLen);
}

// The page of an expansion game (the app's readExpansionEntry): everything is in the species' gSpeciesInfo entry.
static bool expansion_entry(const struct pd_game* g, int national, int species, struct pd_dex_entry* out) {
    const struct pd_config* cfg = g->cfg;
    const struct pd_dex_x* x = cfg->expansion;
    const uint8_t* b = pd_rom_at(g, cfg->speciesInfo + (uint32_t) species * (uint32_t) x->stride, (size_t) x->stride);
    if (!b) return false;
    out->national = national;
    out->species = species;
    pd_gen3_text(b + x->categoryOff, 13, out->category, sizeof(out->category));
    out->heightDm = u16(b + x->natDexOff + 2);
    out->weightHg = u16(b + x->natDexOff + 4);
    uint32_t d = u32(b + x->descriptionOff);
    const uint8_t* text = in_rom(g, d) ? pd_rom_at(g, d, 200) : NULL;
    if (text) pd_gen3_text(text, 200, out->description, sizeof(out->description));
    static const int ORDER[6] = { 0, 1, 2, 4, 5, 3 };
    for (int i = 0; i < 6; i++) out->stats[i] = b[ORDER[i]];
    out->type1 = b[6];
    out->type2 = b[7];
    int a1 = u16(b + x->abilitiesOff), a2 = u16(b + x->abilitiesOff + 2);
    if (a1) ability_name(g, a1, out->ability1, sizeof(out->ability1));
    if (a2 && a2 != a1) ability_name(g, a2, out->ability2, sizeof(out->ability2));
    return true;
}

bool pd_dex_entry(const struct pd_game* g, int national, struct pd_dex_entry* out) {
    memset(out, 0, sizeof(*out));
    const struct pd_config* cfg = g->cfg;
    if (cfg && cfg->gb) return pd_gb_dex_entry(g, national, out);
    if (!pd_dex_available(g)) return false;
    int species = pd_dex_species(g, national);
    if (!species) return false;
    if (cfg->expansion) return expansion_entry(g, national, species, out);
    const struct pd_base_stats* bs = cfg->baseStats;
    const uint8_t* e = pd_rom_at(g, cfg->dexEntries + (uint32_t) (cfg->entriesBySpecies ? species : national) * cfg->dexEntryStride,
        cfg->dexEntryStride);
    if (!e) return false;
    out->national = national;
    out->species = species;
    entry_category(g, e, out->category, sizeof(out->category));
    out->heightDm = u16(e + cfg->entryHeightOff);
    out->weightHg = u16(e + cfg->entryHeightOff + 2);
    // The text pages (Ruby / Sapphire split it in two), joined by a space.
    int pageOff[2] = { cfg->entryDescOff, cfg->descriptionPage2Off };
    size_t used = 0;
    for (int k = 0; k < 2; k++) {
        if (pageOff[k] < 0) continue;
        uint32_t p = u32(e + pageOff[k]);
        const uint8_t* d = in_rom(g, p) ? pd_rom_at(g, p, 200) : NULL;
        if (!d) continue;
        if (used && used + 1 < sizeof(out->description)) out->description[used++] = ' ';
        pd_gen3_text(d, 200, out->description + used, sizeof(out->description) - used);
        used = strlen(out->description);
    }

    const uint8_t* b = pd_rom_at(g, cfg->speciesInfo + (uint32_t) species * (uint32_t) bs->stride, (size_t) bs->stride);
    if (b) {
        // gBaseStats: HP ATK DEF SPEED SP.ATK SP.DEF; shown with SPEED last.
        static const int ORDER[6] = { 0, 1, 2, 4, 5, 3 };
        for (int i = 0; i < 6; i++) out->stats[i] = bs->statsU16 ? u16(b + ORDER[i] * 2) : b[ORDER[i]];
        out->type1 = b[bs->typesOff];
        out->type2 = b[bs->typesOff + 1];
        int a1, a2;
        if (bs->abilityU16) {
            a1 = u16(b + bs->abilitiesOff);
            a2 = u16(b + bs->abilitiesOff + 2);
        } else {
            a1 = b[bs->abilitiesOff];
            a2 = b[bs->abilitiesOff + 1];
        }
        if (a1) ability_name(g, a1, out->ability1, sizeof(out->ability1));
        if (a2 && a2 != a1) ability_name(g, a2, out->ability2, sizeof(out->ability2));
    }
    return true;
}

bool pd_dex_sprite(const struct pd_game* g, int species, uint32_t* out) {
    const struct pd_config* cfg = g->cfg;
    if (cfg && cfg->gb) return pd_gb_dex_sprite(g, species, out);
    if (!pd_dex_available(g) || species < 1 || species >= species_count(cfg)) return false;
    uint32_t picPtr, palPtr;
    if (cfg->expansion) {
        // The pointers sit in the species' own gSpeciesInfo entry.
        const struct pd_dex_x* x = cfg->expansion;
        const uint8_t* info = pd_rom_at(g, cfg->speciesInfo + (uint32_t) species * (uint32_t) x->stride, (size_t) x->stride);
        if (!info) return false;
        picPtr = u32(info + x->frontPicOff);
        palPtr = u32(info + x->paletteOff);
    } else {
        const uint8_t* pic = pd_rom_at(g, cfg->frontPics + (uint32_t) species * 8, 8);
        const uint8_t* pal = pd_rom_at(g, cfg->palettes + (uint32_t) species * 8, 8);
        if (!pic || !pal) return false;
        picPtr = u32(pic);
        palPtr = u32(pal);
    }
    const uint8_t* picLz = in_rom(g, picPtr) ? pd_rom_at(g, picPtr, 4) : NULL;
    const uint8_t* palLz = in_rom(g, palPtr) ? pd_rom_at(g, palPtr, 4) : NULL;
    if (!picLz || !palLz) return false;
    // One 64x64 frame is 2048 bytes (Emerald's pics hold two frames; the first is used). The pictures are LZ77 or
    // expansion's smol; a palette is LZ77 (it starts with 0x10) or, in some expansion games, 16 raw colours.
    static uint8_t tiles[8192], colours[64];
    size_t picAvail = g->romSize - (size_t) (picLz - g->rom), palAvail = g->romSize - (size_t) (palLz - g->rom);
    size_t picSize;
    if (pd_is_smol(picLz, picAvail)) {
        picSize = pd_smol(picLz, picAvail, tiles, sizeof tiles);
        if (picSize < 2048) return false;
    } else {
        picSize = picLz[1] | (size_t) picLz[2] << 8 | (size_t) picLz[3] << 16;
        if (picLz[0] != 0x10 || picSize < 2048 || picSize > 8192 || pd_lz77(picLz, picAvail, tiles, picSize) != picSize) return false;
    }
    if (palLz[0] == 0x10) {
        size_t palSize = palLz[1] | (size_t) palLz[2] << 8 | (size_t) palLz[3] << 16;
        if (palSize < 32 || palSize > 64 || pd_lz77(palLz, palAvail, colours, palSize) != palSize) return false;
    } else {
        if (palAvail < 32) return false;
        memcpy(colours, palLz, 32);
    }
    uint32_t rgb[16];
    for (int i = 0; i < 16; i++) {
        uint16_t c = (uint16_t) (colours[i * 2] | colours[i * 2 + 1] << 8);
        uint32_t r = c & 0x1F, gg = (c >> 5) & 0x1F, bb = (c >> 10) & 0x1F;
        rgb[i] = (r << 3 | r >> 2) << 16 | (gg << 3 | gg >> 2) << 8 | (bb << 3 | bb >> 2);
    }
    for (int y = 0; y < PD_SPRITE; y++) {
        for (int x = 0; x < PD_SPRITE; x++) {
            int tile = (y / 8) * 8 + x / 8;
            uint8_t v = tiles[tile * 32 + (y % 8) * 4 + (x % 8) / 2];
            int c = x & 1 ? v >> 4 : v & 0xF;
            out[y * PD_SPRITE + x] = c ? rgb[c] : 0xFF000000u;
        }
    }
    return true;
}

void pd_dex_height(int dm, char* buf, int len) {
    long inches = 10000L * dm / 254;
    if (inches % 10 >= 5) inches += 10;
    long feet = inches / 120, rest = (inches - feet * 120) / 10;
    snprintf(buf, (size_t) len, "%ld'%02ld\"", feet, rest);
}

void pd_dex_weight(int hg, char* buf, int len) {
    long lbs = 100000L * hg / 4536;
    if (lbs % 10 >= 5) lbs += 10;
    snprintf(buf, (size_t) len, "%ld.%ld lbs.", lbs / 100, (lbs % 100) / 10);
}
