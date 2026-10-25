// Name / move / type tables, generated from the app's Kotlin tables by
// 3ds/tools/gen_tables.py (pd_tables_gen.c), plus small lookups over them.
#ifndef PD_TABLES_H
#define PD_TABLES_H

#include <stddef.h>

struct pd_move_info {
    const char* name;
    int type;
    int power;
};

struct pd_type_pair {
    int atk, def, pct;
};

extern const char* const pd_species_names[];
extern const int pd_species_names_count;
extern const struct pd_move_info pd_moves[];
extern const int pd_moves_count;
extern const char* const pd_type_names[];
extern const int pd_type_names_count;
extern const struct pd_type_pair pd_type_chart[];
extern const int pd_type_chart_count;
extern const char* const pd_mapsec_firered[];
extern const int pd_mapsec_firered_count;
extern const char* const pd_mapsec_emerald[];
extern const int pd_mapsec_emerald_count;
extern const char* const pd_item_names_firered[];
extern const int pd_item_names_firered_count;
extern const char* const pd_item_desc_firered[];
extern const int pd_item_desc_firered_count;
extern const char* const pd_item_names_emerald[];
extern const int pd_item_names_emerald_count;
extern const char* const pd_item_desc_emerald[];
extern const int pd_item_desc_emerald_count;
// gBaseStats[species].genderRatio (FireRed's numbering, which Emerald shares).
extern const unsigned char pd_gender_ratios[];
extern const int pd_gender_ratios_count;
extern const unsigned char pd_species_type_table[][2];
extern const int pd_species_type_table_count;
// Generated from MonStats.kt, not a second maintained list.
extern const char* const pd_nature_names[];
extern const int pd_nature_names_count;
extern const unsigned char pd_hidden_power_types[16];

#define PD_TYPE_NONE 255
// An egg, whatever its game numbers it (retail 412, Emerald Legacy 415: pd_config.eggSpecies): past every game's
// species, so a hack's species 412 (Legacy's Deoxys forms) isn't one.
#define PD_SPECIES_EGG 0x7FFF

// Names in the game's own casing: FireRed / Emerald print species and moves
// in capitals with a small é (the app's gameCase()). Writes into buf.
const char* pd_species_name(int species, char* buf, size_t len);
const char* pd_move_name(int move, char* buf, size_t len);
const char* pd_type_name(int type);
int pd_move_type(int move);
int pd_move_power(int move);

// One attacking type against a (possibly dual-typed) defender, in percent
// (100 = 1x) - the app's typeMultiplierPct().
int pd_type_multiplier_pct(int atk, int def1, int def2);
// A species' types (the app's speciesTypeData; the same type twice for one),
// PD_TYPE_NONE both when unknown.
void pd_species_type_pair(int species, int* t1, int* t2);

#endif
