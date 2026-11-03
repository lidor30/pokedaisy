#include "pd_tables.h"

#include <stdio.h>
#include <string.h>

#include "pd_games.h"

static const struct pd_table_set* active;

void pd_tables_use(const struct pd_table_set* set) {
    active = set;
}

int pd_type_count(void) {
    return active ? active->typeNameCount : pd_type_names_count;
}

int pd_gender_ratio(int species) {
    if (active) return species >= 0 && species < active->genderCount ? active->gender[species] : -1;
    return species >= 0 && species < pd_gender_ratios_count ? pd_gender_ratios[species] : -1;
}

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

const char* pd_species_name(int species, char* buf, size_t len) {
    if (species == 0) return "-";
    if (active) {
        if (species > 0 && species < active->speciesCount && active->species[species]) return active->species[species];
        snprintf(buf, len, "#%d", species);
        return buf;
    }
    if (species == PD_SPECIES_EGG) return "EGG";
    if (species > 0 && species < pd_species_names_count && pd_species_names[species]) {
        return game_case(pd_species_names[species], buf, len);
    }
    snprintf(buf, len, "#%d", species);
    return buf;
}

const char* pd_move_name(int move, char* buf, size_t len) {
    if (move <= 0) return "-";
    if (active) {
        if (move < active->moveCount && active->moves[move].name) return active->moves[move].name;
        snprintf(buf, len, "MOVE#%d", move);
        return buf;
    }
    if (move < pd_moves_count && pd_moves[move].name) return game_case(pd_moves[move].name, buf, len);
    snprintf(buf, len, "MOVE#%d", move);
    return buf;
}

const char* pd_type_name(int type) {
    if (active) return type >= 0 && type < active->typeNameCount && active->typeNames[type] ? active->typeNames[type] : "";
    if (type >= 0 && type < pd_type_names_count && pd_type_names[type]) return pd_type_names[type];
    return "";
}

int pd_move_type(int move) {
    if (active) return move > 0 && move < active->moveCount && active->moves[move].name ? active->moves[move].type : PD_TYPE_NONE;
    if (move > 0 && move < pd_moves_count && pd_moves[move].name) return pd_moves[move].type;
    return PD_TYPE_NONE;
}

int pd_move_power(int move) {
    if (active) return move > 0 && move < active->moveCount && active->moves[move].name ? active->moves[move].power : 0;
    if (move > 0 && move < pd_moves_count && pd_moves[move].name) return pd_moves[move].power;
    return 0;
}

static int single_pct(int atk, int def) {
    if (active) {
        for (int i = 0; i < active->chartCount; i++)
            if (active->chart[i].atk == atk && active->chart[i].def == def) return active->chart[i].pct;
        return 100;
    }
    for (int i = 0; i < pd_type_chart_count; i++) {
        if (pd_type_chart[i].atk == atk && pd_type_chart[i].def == def) return pd_type_chart[i].pct;
    }
    return 100;
}

int pd_type_multiplier_pct(int atk, int def1, int def2) {
    int pct = single_pct(atk, def1);
    if (def2 != PD_TYPE_NONE && def2 != def1) pct = pct * single_pct(atk, def2) / 100;
    return pct;
}

void pd_species_type_pair(int species, int* t1, int* t2) {
    *t1 = *t2 = PD_TYPE_NONE;
    if (active) {
        if (species > 0 && species < active->typesCount) {
            *t1 = active->types[species][0];
            *t2 = active->types[species][1];
        }
        return;
    }
    if (species <= 0 || species >= pd_species_type_table_count) return;
    *t1 = pd_species_type_table[species][0];
    *t2 = pd_species_type_table[species][1];
}
