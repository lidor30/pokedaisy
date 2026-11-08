#include "pd_stats.h"
#include "pd_tables.h"

// Direct translations of natureRaises/natureLowers and ivBits (MonStats.kt).
int pd_nature_raises(int nature) {
    return nature >= 0 && nature < 25 && nature / 5 != nature % 5 ? 1 + nature / 5 : -1;
}

int pd_nature_lowers(int nature) {
    return nature >= 0 && nature < 25 && nature / 5 != nature % 5 ? 1 + nature % 5 : -1;
}

static int iv_bits(const struct pd_mon_stats* stats, int bit) {
    int bits = 0;
    for (int i = 0; i < 6; i++) bits |= ((stats->ivs[i] >> bit) & 1) << i;
    return bits;
}

int pd_hidden_power_type(const struct pd_mon_stats* stats) {
    return stats->valid ? pd_hidden_power_types[iv_bits(stats, 0) * 15 / 63] : PD_TYPE_NONE;
}

int pd_hidden_power_power(const struct pd_mon_stats* stats) {
    return stats->valid ? iv_bits(stats, 1) * 40 / 63 + 30 : 0;
}
