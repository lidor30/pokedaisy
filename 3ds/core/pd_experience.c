/* GPL-3.0. SnapshotView.expProgress / ExpProgress fraction and toNext rules. */
#include "pd_experience.h"
bool pd_exp_progress(int growth, int level, uint32_t total, int width,
                         struct pd_exp *out) {
  if (growth < 0 || growth >= 6 || level < 1 || level > 100 || width < 0 ||
      !out)
    return false;
  uint32_t start = pd_experience_tables[growth][level],
           next = level == 100 ? start : pd_experience_tables[growth][level + 1];
  uint32_t have = total > start ? total - start : 0, span = next - start;
  if (have > span)
    have = span;
  *out = (struct pd_exp){
      start, next, total < next ? next - total : 0,
      span ? (int)((uint64_t)have * (unsigned)width / span) : width};
  return true;
}

bool pd_retail_exp_progress(int species, int level, uint32_t total, int width,
                            struct pd_exp* out) {
  if (species <= 0 || species >= pd_growth_rates_count) return false;
  return pd_exp_progress(pd_growth_rates[species], level, total, width, out);
}
