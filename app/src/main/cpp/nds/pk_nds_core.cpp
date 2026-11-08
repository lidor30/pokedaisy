// pk_nds.h's calls, handed to the DS core that's up: melonDS (pk_nds.cpp) or DSperate (pk_nds_dsp.cpp). See
// pk_nds_cores.h. With no DS up every call goes to melonDS's, which does nothing then (as it always did).

#include "pk_nds_cores.h"

#include <atomic>

namespace {

std::atomic<int> wanted {PK_NDS_CORE_MELONDS};   // the next init's (pk_nds_set_core)
std::atomic<bool> dsperate {false};              // DSperate is the one up

} // namespace

extern "C" {

void pk_nds_set_core(int core) { wanted.store(core == PK_NDS_CORE_DSPERATE ? PK_NDS_CORE_DSPERATE : PK_NDS_CORE_MELONDS); }

int pk_nds_core(void)
{
    if (pk_ndsd_active()) return PK_NDS_CORE_DSPERATE;
    return pk_ndsm_active() ? PK_NDS_CORE_MELONDS : -1;
}

bool pk_nds_init(const char* romPath, const char* savePath, int sampleRate)
{
    pk_nds_deinit();
    bool useDsperate = wanted.load() == PK_NDS_CORE_DSPERATE;
    bool ok = useDsperate ? pk_ndsd_init(romPath, savePath, sampleRate) : pk_ndsm_init(romPath, savePath, sampleRate);
    dsperate.store(ok && useDsperate);
    return ok;
}

void pk_nds_deinit(void)
{
    pk_ndsd_deinit();
    pk_ndsm_deinit();
    dsperate.store(false);
}

bool pk_nds_active(void) { return pk_ndsd_active() || pk_ndsm_active(); }

#define PK_NDS_DISPATCH(ret, name, params, args) \
    ret pk_nds_##name params { return dsperate.load(std::memory_order_relaxed) ? pk_ndsd_##name args : pk_ndsm_##name args; }
PK_NDS_CORE_CALLS(PK_NDS_DISPATCH)
#undef PK_NDS_DISPATCH

} // extern "C"
