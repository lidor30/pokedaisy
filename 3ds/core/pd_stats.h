// Read-only summary data; game adapters declare optional mechanics explicitly.
#ifndef PD_STATS_H
#define PD_STATS_H

#include <stdbool.h>
#include <stdint.h>

struct pd_mon_stats {
    bool valid;
    bool hasIvs, hasEvs, hasNature, hasHiddenPower;
    // Resolved by the game adapter: later generations need not use Gen 3 power.
    int hiddenPowerType, hiddenPowerPower; // power 0 = unknown/not applicable
    uint8_t ivs[6], evs[6]; // HP, Attack, Defense, Speed, Sp.Atk, Sp.Def
    int values[6], nature;
};

int pd_nature_raises(int nature); // struct index, -1 for neutral/unknown
int pd_nature_lowers(int nature);
int pd_hidden_power_type(const struct pd_mon_stats* stats);
int pd_hidden_power_power(const struct pd_mon_stats* stats); // retail Gen 3

#endif
