// BananaDS's pk_ndsb_* calls (pk_nds_cores.h) as libpokedaisy sees them: libpkbananads.so is dlopen()ed the first time
// BananaDS starts, not linked. It's built with native ELF TLS (bananads/CMakeLists.txt says why), which Android only
// loads from 10 (API 29) - linked, it would take the whole app down on an older one. Where it can't load (an older
// Android, an ABI it isn't built for: AArch64 only) pk_ndsb_init says no and the game doesn't start, as any core that
// can't run a cart. libpkbananads.so binds its own calls to itself (-Bsymbolic), never to these.
#include "pk_nds_cores.h"

#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>

#include <cstring>
#include <mutex>
#include <vector>

#define LOG_TAG "pokedaisy/nds"

namespace {

struct Fns {
#define PK_NDSB_FN(ret, name, params, args) ret(*name) params = nullptr;
    PK_NDS_CORE_CALLS(PK_NDSB_FN)
#undef PK_NDSB_FN
    bool (*init)(const char*, const char*, int) = nullptr;
    void (*deinit)(void) = nullptr;
    bool (*active)(void) = nullptr;
    void (*set_wild_rates)(const int32_t*, int) = nullptr;
    void (*set_window)(ANativeWindow*) = nullptr;
};

Fns fns;
bool loaded = false, tried = false;
std::mutex lock;   // set_window comes from any thread; the rest from the emu thread
// What came before the library was loaded, handed over once it is.
std::vector<int32_t> rates;
bool ratesSet = false;
ANativeWindow* window = nullptr;
bool windowSet = false;

bool load()
{
    std::lock_guard<std::mutex> l(lock);
    if (tried) return loaded;
    tried = true;
    void* h = dlopen("libpkbananads.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "BananaDS isn't available here: %s", dlerror());
        return false;
    }
    bool ok = true;
    auto sym = [&](const char* n) {
        void* p = dlsym(h, n);
        if (!p) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "BananaDS: no %s", n);
            ok = false;
        }
        return p;
    };
#define PK_NDSB_SYM(ret, name, params, args) fns.name = (ret(*) params) sym("pk_ndsb_" #name);
    PK_NDS_CORE_CALLS(PK_NDSB_SYM)
#undef PK_NDSB_SYM
    fns.init = (bool (*)(const char*, const char*, int)) sym("pk_ndsb_init");
    fns.deinit = (void (*)(void)) sym("pk_ndsb_deinit");
    fns.active = (bool (*)(void)) sym("pk_ndsb_active");
    fns.set_wild_rates = (void (*)(const int32_t*, int)) sym("pk_ndsb_set_wild_rates");
    fns.set_window = (void (*)(ANativeWindow*)) sym("pk_ndsb_set_window");
    if (!ok) {
        dlclose(h);
        fns = Fns {};
        return false;
    }
    loaded = true;
    if (ratesSet) fns.set_wild_rates(rates.data(), (int) rates.size() / 3);
    if (windowSet) fns.set_window(window);   // (its reference goes with it)
    window = nullptr;
    windowSet = false;
    return true;
}

} // namespace

extern "C" {

bool pk_ndsb_init(const char* romPath, const char* savePath, int sampleRate)
{
    return load() && fns.init(romPath, savePath, sampleRate);
}

void pk_ndsb_deinit(void)
{
    if (loaded) fns.deinit();
}

bool pk_ndsb_active(void) { return loaded && fns.active(); }

void pk_ndsb_set_wild_rates(const int32_t* triples, int n)
{
    if (loaded) {
        fns.set_wild_rates(triples, n);
        return;
    }
    rates.assign(triples, triples + (n > 0 ? n * 3 : 0));
    ratesSet = true;
}

void pk_ndsb_set_window(ANativeWindow* w)
{
    {
        std::lock_guard<std::mutex> l(lock);
        if (!loaded) {
            if (window) ANativeWindow_release(window);
            window = w;
            windowSet = true;
            return;
        }
    }
    fns.set_window(w);
}

// The rest are called only while BananaDS is the core that's up (pk_nds_core.cpp), so loaded.
#define PK_NDSB_FWD(ret, name, params, args) ret pk_ndsb_##name params { return fns.name args; }
PK_NDS_CORE_CALLS(PK_NDSB_FWD)
#undef PK_NDSB_FWD

} // extern "C"
