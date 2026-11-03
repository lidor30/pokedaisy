#include "pd_tables.h"

#include "pd_game.h"

#include <stdio.h>
#include <string.h>

// Upper-cases ASCII letters, leaving é (UTF-8 C3 A9) and the rest alone.
static const char* game_case(const char* src, char* buf, size_t len) {
    size_t i = 0;
    for (; src[i] && i + 1 < len; i++) {
        char c = src[i];
        buf[i] = (c >= 'a' && c <= 'z') ? (char) (c - 'a' + 'A') : c;
    }
    buf[i] = 0;
    return buf;
}

// Emerald Legacy numbers DEOXYS's other forms 412-414 (its egg 415): named and typed as DEOXYS (the game's own
// names, EmeraldTextLegacyGen.kt). No retail game has a species there (an egg is PD_SPECIES_EGG by its flag).
static int form_base(int species) {
    return species >= 412 && species <= 414 ? 410 : species;
}

static const struct pd_text* hack_text(const struct pd_game* g) {
    return g && g->cfg ? g->cfg->text : NULL;
}

const char* pd_species_name(const struct pd_game* g, int species, char* buf, size_t len) {
    if (species == 0) return "-";
    if (species == PD_SPECIES_EGG) return "EGG";
    const struct pd_text* t = hack_text(g);
    if (t && t->species) {
        if (species > 0 && species < t->speciesCount && t->species[species]) {
            snprintf(buf, len, "%s", t->species[species]);
            return buf;
        }
    } else {
        species = form_base(species);
        if (species > 0 && species < pd_species_names_count && pd_species_names[species]) {
            return game_case(pd_species_names[species], buf, len);
        }
    }
    snprintf(buf, len, "#%d", species);
    return buf;
}

// The move a hack's table (when it has one) or retail's names; NULL = none.
static const struct pd_move_info* move_info(const struct pd_game* g, int move) {
    const struct pd_text* t = hack_text(g);
    int count = t && t->moves ? t->movesCount : pd_moves_count;
    const struct pd_move_info* table = t && t->moves ? t->moves : pd_moves;
    return move > 0 && move < count && table[move].name ? &table[move] : NULL;
}

const char* pd_move_name(const struct pd_game* g, int move, char* buf, size_t len) {
    if (move <= 0) return "-";
    const struct pd_move_info* m = move_info(g, move);
    if (m) {
        if (hack_text(g) && hack_text(g)->moves) {
            snprintf(buf, len, "%s", m->name);
            return buf;
        }
        return game_case(m->name, buf, len);
    }
    snprintf(buf, len, "MOVE#%d", move);
    return buf;
}

const char* pd_type_name(const struct pd_game* g, int type) {
    const struct pd_text* t = hack_text(g);
    if (t && t->typeNames) {
        return type >= 0 && type < t->typeNamesCount && t->typeNames[type] ? t->typeNames[type] : "";
    }
    if (type >= 0 && type < pd_type_names_count && pd_type_names[type]) return pd_type_names[type];
    return "";
}

int pd_move_type(const struct pd_game* g, int move) {
    const struct pd_move_info* m = move_info(g, move);
    return m ? m->type : PD_TYPE_NONE;
}

int pd_move_power(const struct pd_game* g, int move) {
    const struct pd_move_info* m = move_info(g, move);
    return m ? m->power : 0;
}

static int single_pct(const struct pd_game* g, int atk, int def) {
    const struct pd_text* t = hack_text(g);
    const struct pd_type_pair* chart = t && t->typeChart ? t->typeChart : pd_type_chart;
    int n = t && t->typeChart ? t->typeChartCount : pd_type_chart_count;
    for (int i = 0; i < n; i++) {
        if (chart[i].atk == atk && chart[i].def == def) return chart[i].pct;
    }
    return 100;
}

int pd_type_multiplier_pct(const struct pd_game* g, int atk, int def1, int def2) {
    int pct = single_pct(g, atk, def1);
    if (def2 != PD_TYPE_NONE && def2 != def1) pct = pct * single_pct(g, atk, def2) / 100;
    return pct;
}

void pd_species_type_pair(const struct pd_game* g, int species, int* t1, int* t2) {
    *t1 = *t2 = PD_TYPE_NONE;
    const struct pd_text* t = hack_text(g);
    if (t && t->speciesTypes) {
        if (species > 0 && species < t->speciesTypesCount) {
            *t1 = t->speciesTypes[species][0];
            *t2 = t->speciesTypes[species][1];
        }
        return;
    }
    species = form_base(species);
    if (species <= 0 || species >= pd_species_type_table_count) return;
    *t1 = pd_species_type_table[species][0];
    *t2 = pd_species_type_table[species][1];
}
