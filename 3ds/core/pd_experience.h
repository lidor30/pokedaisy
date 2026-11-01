// Android ExpTables.kt and SnapshotView.ExpProgress, without host dependencies.
#ifndef PD_EXPERIENCE_H
#define PD_EXPERIENCE_H
#include <stdbool.h>
#include <stdint.h>
struct pd_exp { uint32_t start, next, toNext; int filled; };
extern const uint32_t pd_experience_tables[6][101];
extern const unsigned char pd_growth_rates[];
extern const int pd_growth_rates_count;
// Growth ids are Android's Gen 3 ids; game adapters map their own rates.
bool pd_exp_progress(int growth, int level, uint32_t total, int width, struct pd_exp* out);
// Retail species numbering only. Unknown species/rates are deliberately hidden.
bool pd_retail_exp_progress(int species, int level, uint32_t total, int width, struct pd_exp* out);
#endif
