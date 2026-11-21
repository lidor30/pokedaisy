// The app's third Nintendo DS core: BananaDS (third_party/bananads, GPL-3.0-or-later), as pk_ndsb_* behind pk_nds.h
// (pk_nds_core.cpp hands it the calls while it runs; pk_nds_cores.h). It lives in libpkbananads.so (CMakeLists.txt here
// says why), the same emulator BananaOS's handhelds run: melonDS at BananaDS's pin with BananaDS's patches, melonDS's
// AArch64 JIT, the Banana 2D engine over melonDS's software 3D (BananaDS's render.renderer software), set up with
// BananaDS's own defaults (its emu.cpp with no config file). Not its Gengis Engine (setRenderer says why).
//
// What it does as melonDS does (pk_nds.cpp): the screens as RGBA, the audio at the engine's rate, the save written once
// the game's writes have been quiet for SAVE_QUIET_FRAMES, states behind our header carrying the save's CRC32
// (pk_nds_state_matches_save), the field read (pk_nds_field.cpp), cheats, DS CPU BOOST. What it doesn't have, as on
// DSperate: DS RESOLUTION, PC / FLY calls and STEADY music (they hook the app's patched melonDS 1.1); those calls do
// nothing here and the settings grey them out. EXPAND it has (pk_nds_wide.cpp, built against its melonDS). States are BananaDS's melonDS's own and never cross cores.

#include "pk_nds_cores.h"
#include "pk_nds_field.h"
#include "pk_nds_wide.h"

#include <NDS.h>
#include <NDSCart.h>
#include <GPU.h>
#include <SPU.h>
#include <RTC.h>
#include <Savestate.h>
#include <Args.h>
#include <AREngine.h>

#include "GPU_Banana.h"
#include "GPU2D_Banana.h"
#include "GPU_Banana.h"

#include <android/log.h>
#include <android/native_window.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <zlib.h>

#define LOG_TAG "pokedaisy/bananads"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace melonDS;

// BananaDS's emu.cpp's scheduler hooks, which its renderers may name (sched.emu_share: never here).
namespace bananads {
int g_emu_share = 0;
void sched_off_emu_core() {}
}

namespace {

constexpr int SAVE_QUIET_FRAMES = 30;
constexpr u32 STATE_MAGIC = PK_NDS_STATE_MAGIC_BANANADS;
constexpr u32 STATE_VERSION = 1;
constexpr u32 ROM_WINDOW = 0x08000000; // pk_nds_read: the cart's ROM file from here

struct Ds {
    NDS* nds = nullptr;
    BananaRenderer* banana = nullptr;
    std::string savePath;
    bool saveReadOnly = false;
    std::vector<u8> saveCopy;      // the save memory as the game last wrote it
    bool saveDirty = false;
    int saveQuiet = 0;
    u32 keys = 0;
    u32 top[PK_NDS_W * PK_NDS_H] {};
    u32 bottom[PK_NDS_W * PK_NDS_H] {};
    std::vector<s16> audio;
    int audioFrames = 0;
    int sampleRate = 48000;
    const PkNdsFieldGame* field = nullptr;
    bool draw = true, drawNext = true;
    bool drawn = true;
    int fieldBits = 0;
    bool wildOff = false;
    bool keepStartMenu = false;
    bool cpuBoost = false;
    int menuLitFrames = 0;
    // DS RESOLUTION: BananaDS's hires is its Gengis Engine, not built here, so the scale stays 1 (setRenderer); the
    // hires paths below are kept for it but never taken.
    int scale = 1;                           // in effect
    int scaleWant = 1;
    bool scalePending = false;
    bool presented = false;
    // EXPAND (pk_nds_wide.cpp, built against BananaDS's melonDS): the overworld past the screen's edges, from the game's
    // own polygons drawn by melonDS's software 3D renderer in slices around the screen.
    bool wide = false;
    int winX = 0, winY = 0, winW = PK_NDS_W, winH = PK_NDS_H;   // EXPAND's window (the screen without it)
    u32 topArgb[PK_NDS_W * PK_NDS_H] {};     // the top screen at 1x as melonDS draws it (EXPAND reads it) when hires
    std::vector<u32> view;                   // EXPAND at 2x-4x: the window at 1x (pk_nds_wide_view)
    int coarseMix = 0;                       // SPU::MixShift on unseen frames
    int showHz = 0;                          // frames read back a second at most (0: all asked for)
    int64_t lastShown = 0;
    bool promisedNext = true;                // the last frame's drawNext (EXPAND's slices drawn for this one)
};

// The game screen's window (pk_nds_set_window), any thread; taken on the emu thread before a frame goes out.
struct Win {
    std::mutex lock;
    ANativeWindow* next = nullptr;
    bool pending = false;
    ANativeWindow* win = nullptr;
    int w = 0, h = 0;
    std::mutex infoLock;
    int32_t info[6] = {};
    bool infoValid = false;
} win;

Ds* ds = nullptr;
bool geomReject = true;   // render.geom_reject as the console started (EXPAND turns it off while on)
std::atomic<u32> touch {0};

bool readFile(const char* path, std::vector<u8>& out)
{
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    out.resize(n > 0 ? n : 0);
    bool ok = n <= 0 || fread(out.data(), 1, n, f) == (size_t) n;
    fclose(f);
    return ok;
}

bool writeSaveFile()
{
    if (ds->savePath.empty() || ds->saveReadOnly || ds->saveCopy.empty()) return false;
    FILE* f = fopen(ds->savePath.c_str(), "wb");
    if (!f) {
        LOGE("can't write the save %s", ds->savePath.c_str());
        return false;
    }
    bool ok = fwrite(ds->saveCopy.data(), 1, ds->saveCopy.size(), f) == ds->saveCopy.size();
    ok = fflush(f) == 0 && ok;
    fclose(f);
    return ok;
}

// melonDS draws 0xAARRGGBB words; the screen (EmulatorView) uploads RGBA bytes, i.e. 0xAABBGGRR words.
void convert(const u32* src, u32* dst)
{
    if (!src) return;
    for (int i = 0; i < PK_NDS_W * PK_NDS_H; i++) {
        u32 p = src[i];
        dst[i] = 0xFF000000u | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
    }
}

u32 saveCrc(const u8* data, size_t len) { return (u32) crc32(0, data, (uInt) len); }

void setClock()
{
    time_t now = time(nullptr);
    struct tm t {};
    localtime_r(&now, &t);
    ds->nds->RTC.SetDateTime(t.tm_year + 1900, t.tm_mon + 1, t.tm_mday, t.tm_hour, t.tm_min, t.tm_sec);
}

// DS CPU BOOST, as pk_nds.cpp's setArm9Shift (the same melonDS fields at BananaDS's pin).
void setArm9Shift(u32 shift)
{
    NDS* n = ds->nds;
    if (n->ARM9ClockShift == shift) return;
    n->ARM9Timestamp >>= n->ARM9ClockShift;
    n->ARM9Target >>= n->ARM9ClockShift;
    n->ARM9ClockShift = shift;
    n->ARM9Timestamp <<= shift;
    n->ARM9Target <<= shift;
    n->ARM9.UpdateRegionTimings(0x00000, 0x100000);
    n->JIT.ResetBlockCache();
}

u32 boostShift() { return ds->cpuBoost ? 2 : 1; }

// BananaDS's settings with no config file (emu.cpp's defaults): the ones read from the environment by its patches first.

void bananaEnv()
{
    setenv("MELONDS_GEOMTHREAD", "0", 1);   // render.geom_thread off (measured: slower on the Thor too)
    setenv("MELONDS_JIT_PINREGS", "300f", 0);
    setenv("MELONDS_JIT_LOOPREGS", "1", 0);
}

// The renderer: render.renderer software (melonDS's software 3D, threaded, as BananaDS's render.threaded default) under
// render.2d banana, at 1x. BananaDS's Gengis Engine isn't built here: it carries C ports of DraStic's reverse-engineered
// 3D routines (its own README's Provenance), so no hires (DS RESOLUTION) on this core. The 2D on its own thread.
void setRenderer(NDS& n, int)
{
    n.SetRenderer(std::make_unique<BananaRenderer>(n));
    RendererSettings rs {};
    rs.ScaleFactor = 1;
    rs.Threaded = true;
    n.GetRenderer().SetRenderSettings(rs);
    ds->banana = &static_cast<BananaRenderer&>(n.GetRenderer());
    ds->banana->SetSplitRing(true);
    ds->banana->SetMerge(true);
    ds->banana->SetSkip(true);
    ds->banana->SetOutSkip(true);
    ds->banana->SetTestDelay(0);
    ds->banana->SetKickBatch(64);
    ds->banana->SetNice(0);
    ds->banana->SetFenceWaitBlocking(true);
    // render.2d_thread at every scale (BananaDS turns it on at 2x and up only, for the RG DS's four small cores;
    // uncapped fast-forward on the Thor: +7%), and render.2d_lazy: a frame's end waits only for the frame before's 2D
    // lines, not its own
    ds->banana->SetThreaded2D(true);
    ds->banana->SetLazyFrames(true);
    ds->scale = 1;
}

void bananaSetup(NDS& n)
{
    NDSCart::NDSCartSlot::XferShift = 0;   // core.card_fast 0
    n.SetSlice(64, 64, 4096);   // core.slice 64 (melonDS's) until the cart is known (pk_ndsb_init)
    n.FastGXDMA = false;
    n.FastSPU = false;
    n.SPU.SetFastMix(true);
    n.SPU.SetFastSamples(true);
    n.SetFastDMA(true);
    n.SetFastSched(true);
    n.SetFastTimers(true);
    n.SetFastLoop(true);
    n.SetHaltSkip(true);
    n.SetRegionPair(true);
    n.SetRegionTrio(true);
    n.SetLookupPrefetch(true);
    n.JIT.SetMainRAMBlock(true);
    n.DivSqrtBlock = true;

    melonDS::Banana2DFast = true;
    melonDS::Banana3DShare = true;
    melonDS::Banana3DWait2D = true;
    melonDS::BananaCapNeedA = false;
    melonDS::BananaHiresShown = true;
    setRenderer(n, 1);
}

// EXPAND's margins at the renderer's scale while both are on (else at 1x, as melonDS's software renderer draws them)
void updateHires()
{
    pk_nds_wide_hires(1, nullptr, nullptr);   // 1x always: no Gengis here (setRenderer)
}

// A margin texel by EXPAND's plan (pk_nds_wide.h's PK_NDS_PLAN_*): pk_nds_gl.cpp's Margin() on the CPU, [c3] the hires
// 3D there (r | g << 8 | b << 16 | alpha << 24, 0: none) and [screen] the screen's texel for PLAN_SCREEN's pixel. RGBA.
inline int bright6(int c, u32 mb)
{
    const int mode = (int) (mb >> 14), evy = std::min<int>(mb & 0x1F, 16);
    if (mode == 1) c += ((0x3F - c) * evy) >> 4;
    else if (mode == 2) c -= ((c * evy) + 0xF) >> 4;
    return c;
}

inline u32 out8(int r, int g, int b)
{
    auto w = [](int c) { return (u32) ((c << 2) | (c >> 4)); };
    return 0xFF000000u | w(r) | w(g) << 8 | w(b) << 16;
}

u32 marginTexel(const PkNdsWidePlan& p, u32 plan, u32 c3)
{
    const u32 code = plan >> 24;
    if (code == PK_NDS_PLAN_COLOR) return 0xFF000000u | (plan & 0xFFFFFF);
    const bool has3D = (c3 >> 24) & 0x1F;
    const int r3 = c3 & 0x3F, g3 = (c3 >> 8) & 0x3F, b3 = (c3 >> 16) & 0x3F, a3 = (c3 >> 24) & 0x1F;
    const int br = p.backdrop & 0x3F, bg = (p.backdrop >> 8) & 0x3F, bb = (p.backdrop >> 16) & 0x3F;
    int r, g, b;
    if (code == PK_NDS_PLAN_3D_ON) {
        r = plan & 0x3F; g = (plan >> 8) & 0x3F; b = (plan >> 16) & 0x3F;
        if (has3D) {
            const int eva = a3 + 1;
            const bool target2 = (p.blendCnt & ((plan & 0x80) ? 0x1000 : 0x800)) != 0;
            if (target2 && eva < 32) {
                r = std::min((r3 * eva + r * (32 - eva) + 0x10) >> 5, 0x3F);
                g = std::min((g3 * eva + g * (32 - eva) + 0x10) >> 5, 0x3F);
                b = std::min((b3 * eva + b * (32 - eva) + 0x10) >> 5, 0x3F);
            } else { r = r3; g = g3; b = b3; }
        }
        return out8(bright6(r, p.brightness), bright6(g, p.brightness), bright6(b, p.brightness));
    }
    int ur = has3D ? r3 : br, ug = has3D ? g3 : bg, ub = has3D ? b3 : bb;
    r = ur; g = ug; b = ub;
    if (code == PK_NDS_PLAN_3D_OVER) {
        const int orr = plan & 0x3F, og = (plan >> 8) & 0x3F, ob = (plan >> 16) & 0x3F;
        const bool target2 = (p.blendCnt & (has3D ? 0x100 : 0x2000)) != 0;
        if (target2) {
            r = std::min((orr * (int) p.eva + ur * (int) p.evb + 8) >> 4, 0x3F);
            g = std::min((og * (int) p.eva + ug * (int) p.evb + 8) >> 4, 0x3F);
            b = std::min((ob * (int) p.eva + ub * (int) p.evb + 8) >> 4, 0x3F);
        } else { r = orr; g = og; b = ob; }
    } else if (has3D && (p.blendCnt & 0x2000)) {
        const int eva = a3 + 1;
        if (eva < 32) {
            r = std::min((r3 * eva + br * (32 - eva) + 0x10) >> 5, 0x3F);
            g = std::min((g3 * eva + bg * (32 - eva) + 0x10) >> 5, 0x3F);
            b = std::min((b3 * eva + bb * (32 - eva) + 0x10) >> 5, 0x3F);
        }
    }
    if (code != PK_NDS_PLAN_3D_OVER && (p.fxTargets & (has3D ? 1u : 0x20u))) {
        const int evy = std::min<int>(p.fx & 0x1F, 16);
        auto f = [&](int c) { return (p.fx >> 14) == 1 ? c + (((0x3F - c) * evy + 8) >> 4) : (p.fx >> 14) == 2 ? c - ((c * evy + 7) >> 4) : c; };
        r = f(r); g = f(g); b = f(b);
    }
    return out8(bright6(r, p.brightness), bright6(g, p.brightness), bright6(b, p.brightness));
}

void takeWindow()
{
    std::lock_guard<std::mutex> l(win.lock);
    if (!win.pending) return;
    win.pending = false;
    if (win.win) ANativeWindow_release(win.win);
    win.win = win.next;
    win.next = nullptr;
    win.w = win.h = 0;
}

// melonDS's 0xAARRGGBB words as the window's RGBA bytes (0xAABBGGRR words).
inline u32 toRgba(u32 p) { return 0xFF000000u | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF); }

// The hires top screen ([S] texels a DS pixel) into the window; false without one. With EXPAND, the window shown
// ([x, y, w, h] in DS pixels): the screen at its hires, the margins at 1x scaled up (melonDS's software renderer
// draws them; Gengis the screen).
bool present(const u32* hiTop, int S)
{
    takeWindow();
    if (!win.win) return false;
    const bool wide = ds->wide && ds->winW > 0 && ds->winH > 0;
    const int vx = wide ? ds->winX : 0, vy = wide ? ds->winY : 0;
    const int vw = wide ? ds->winW : PK_NDS_W, vh = wide ? ds->winH : PK_NDS_H;
    PkNdsWidePlan plan;
    if (wide) pk_nds_wide_plan(&plan);
    const bool planned = wide && plan.valid;
    if (wide && !planned) {
        ds->view.resize((size_t) vw * vh);
        pk_nds_wide_view(ds->view.data(), vx, vy, vw, vh);
    }
    const int w = vw * S, h = vh * S;
    if (win.w != w || win.h != h) {
        if (ANativeWindow_setBuffersGeometry(win.win, w, h, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) != 0) return false;
        win.w = w;
        win.h = h;
    }
    ANativeWindow_Buffer b;
    if (ANativeWindow_lock(win.win, &b, nullptr) != 0) return false;
    if (b.width < w || b.height < h) {
        ANativeWindow_unlockAndPost(win.win);
        return false;
    }
    const int hw = PK_NDS_W * S;
    for (int y = 0; y < h; y++) {
        u32* dst = (u32*) b.bits + (size_t) y * b.stride;
        const int sy = vy * S + y;   // in the screen's texels
        if (!wide) {
            const u32* src = hiTop + (size_t) y * hw;
            for (int x = 0; x < w; x++) dst[x] = toRgba(src[x]);
            continue;
        }
        const bool rowIn = sy >= 0 && sy < PK_NDS_H * S;
        if (!planned) {
            const u32* vrow = ds->view.data() + (size_t) (y / S) * vw;
            for (int x = 0; x < w; x++) {
                const int sx = vx * S + x;
                dst[x] = rowIn && sx >= 0 && sx < hw ? toRgba(hiTop[(size_t) sy * hw + sx]) : vrow[x / S];
            }
            continue;
        }
        // the margins by EXPAND's plan, with the slices' hires 3D (pk_nds_wide_hires)
        const int dy = sy >= 0 ? sy / S : -((-sy + S - 1) / S);   // the DS row (floor)
        const int fy = sy - dy * S;
        const int slY = dy < 0 ? -1 : dy >= PK_NDS_H ? 1 : 0;
        for (int x = 0; x < w; x++) {
            const int sx = vx * S + x;
            if (rowIn && sx >= 0 && sx < hw) {
                dst[x] = toRgba(hiTop[(size_t) sy * hw + sx]);
                continue;
            }
            const int dx = sx >= 0 ? sx / S : -((-sx + S - 1) / S);
            const int fx = sx - dx * S;
            if (dx < plan.x0 || dx >= plan.x1 || dy < plan.y0 || dy >= plan.y1) {
                dst[x] = 0xFF000000u;
                continue;
            }
            const u32 pl = plan.px[(ptrdiff_t) dy * plan.stride + dx];
            if ((pl >> 24) == PK_NDS_PLAN_SCREEN) {
                const int ex = (int) ((pl & 0xFF) | ((pl >> 16) & 0xFF) << 8), ey = (int) ((pl >> 8) & 0xFF);
                dst[x] = toRgba(hiTop[(size_t) (ey * S + fy) * hw + ex * S + fx]);
                continue;
            }
            u32 c3 = 0;
            const int slX = dx < 0 ? -1 : dx >= PK_NDS_W ? 1 : 0;
            if (const u32* im = pk_nds_wide_hires_slice(slX, slY)) {
                // (pk_nds_wide.cpp's slices: a slice's (0, 0) at the screen's (sx * (256 - 4), sy * (192 - 4)))
                const int lx = dx - slX * (PK_NDS_W - 4), ly = dy - slY * (PK_NDS_H - 4);
                if (lx >= 0 && lx < PK_NDS_W && ly >= 0 && ly < PK_NDS_H)
                    c3 = im[(size_t) (ly * S + fy) * hw + lx * S + fx];
            }
            dst[x] = marginTexel(plan, pl, c3);
        }
    }
    ANativeWindow_unlockAndPost(win.win);
    std::lock_guard<std::mutex> l(win.infoLock);
    const int32_t v[6] = {vx, vy, vw, vh, w, h};
    memcpy(win.info, v, sizeof(v));
    win.infoValid = true;
    return true;
}

// A hires screen at 1x (its texel at each DS pixel's top left); [argb] also as melonDS draws (EXPAND reads that).
void downsample(const u32* hi, int S, u32* dst, u32* argb = nullptr)
{
    const int hw = PK_NDS_W * S;
    for (int y = 0; y < PK_NDS_H; y++) {
        const u32* row = hi + (size_t) y * S * hw;
        for (int x = 0; x < PK_NDS_W; x++) {
            const u32 p = row[x * S];
            dst[y * PK_NDS_W + x] = toRgba(p);
            if (argb) argb[y * PK_NDS_W + x] = p;
        }
    }
}

u32 mainRead32(u32 addr)
{
    if ((addr & 0xFF000000u) != 0x02000000u) return 0;
    const u8* m = ds->nds->MainRAM;
    u32 mask = ds->nds->MainRAMMask;
    return (u32) m[addr & mask] | ((u32) m[(addr + 1) & mask] << 8) | ((u32) m[(addr + 2) & mask] << 16) |
        ((u32) m[(addr + 3) & mask] << 24);
}

void mainWrite32(u32 addr, u32 v)
{
    if ((addr & 0xFF000000u) != 0x02000000u) return;
    u8* m = ds->nds->MainRAM;
    u32 mask = ds->nds->MainRAMMask;
    for (int i = 0; i < 4; i++) m[(addr + i) & mask] = (u8) (v >> (8 * i));
}

bool oneColour(const u32* px)
{
    const u32 first = px[0] & 0xFFFFFF;
    for (int gy = 0; gy < 12; gy++)
        for (int gx = 0; gx < 16; gx++)
            if ((px[(gy * PK_NDS_H / 12 + PK_NDS_H / 24) * PK_NDS_W + gx * PK_NDS_W / 16 + PK_NDS_W / 32] & 0xFFFFFF) != first) return false;
    return true;
}

int fadeFactor(u16 mb)
{
    const int mode = mb >> 14, k = mb & 0x1F;
    return (mode == 1 || mode == 2) ? (k > 16 ? 16 : k) : 0;
}

} // namespace

// Platform's calls back in (bnd_platform.cpp): userdata is the Ds that made the console.
void bnd_on_save(void* userdata, const u8* data, u32 len)
{
    if (!ds || userdata != ds) return;
    ds->saveCopy.assign(data, data + len);
    ds->saveDirty = true;
    ds->saveQuiet = 0;
}

void bnd_on_stop(void*) {}

extern "C" {

bool pk_ndsb_init(const char* romPath, const char* savePath, int sampleRate)
{
    pk_ndsb_deinit();
    std::unique_ptr<u8[]> rom;
    u32 romLen = 0;
    if (FILE* f = fopen(romPath, "rb")) {
        fseek(f, 0, SEEK_END);
        long n = ftell(f);
        fseek(f, 0, SEEK_SET);
        if (n >= 0x200 && n <= (512L << 20)) {
            rom = std::make_unique<u8[]>((size_t) n);
            romLen = fread(rom.get(), 1, (size_t) n, f) == (size_t) n ? (u32) n : 0;
        }
        fclose(f);
    }
    if (!romLen) {
        LOGE("can't read the ROM");
        return false;
    }
    ds = new Ds();
    ds->sampleRate = sampleRate > 0 ? sampleRate : 48000;

    std::vector<u8> save;
    if (savePath) {
        ds->savePath = savePath;
        readFile(savePath, save);
        FILE* probe = fopen(savePath, save.empty() ? "ab" : "r+b");
        ds->saveReadOnly = probe == nullptr;
        if (probe) fclose(probe);
        if (ds->saveReadOnly) LOGI("save opened read-only (new saves won't persist): %s", savePath);
    }

    NDSCart::NDSCartArgs cartArgs;
    if (!save.empty()) {
        cartArgs.SRAM = std::make_unique<u8[]>(save.size());
        memcpy(cartArgs.SRAM.get(), save.data(), save.size());
        cartArgs.SRAMLength = (u32) save.size();
    }
    auto cart = NDSCart::ParseROM(std::move(rom), romLen, ds, std::move(cartArgs));
    if (!cart) {
        LOGE("not a DS ROM BananaDS can run");
        pk_ndsb_deinit();
        return false;
    }

    bananaEnv();
    NDSArgs args;   // FreeBIOS and a generated firmware, as BananaDS
    args.OutputSampleRate = ds->sampleRate;
    ds->nds = new NDS(std::move(args), ds);
    ds->nds->SetNDSCart(std::move(cart));
    ds->nds->Reset();
    bananaSetup(*ds->nds);
    setArm9Shift(boostShift());
    ds->nds->SetupDirectBoot("rom.nds");
    setClock();
    geomReject = ds->nds->GPU.GPU3D.GeomReject;
    ds->coarseMix = 2;   // a quarter as often (uncapped fast-forward on the Thor: +7%)
    ds->showHz = 240;    // the Thor's screens refresh at 120 Hz
    ds->nds->Start();
    if (const u8* mem = ds->nds->GetNDSSave()) ds->saveCopy.assign(mem, mem + ds->nds->GetNDSSaveLength());
    ds->audio.resize(8192 * 2);
    {
        const auto* c = ds->nds->NDSCartSlot.GetCart();
        ds->field = pk_nds_field_game_rom(c->GetROM(), c->GetROMLength());
    }
    // core.slice_idle 512 (BananaDS's adaptive scheduler slice: longer slices away from IPC), for the Pokemon carts the
    // field read knows, measured with them; any other cart keeps melonDS's 64 (Golden Sun: Dark Dawn's 3D field broke
    // with it on BananaOS)
    {
        const int idle = ds->field ? 512 : 64;   // (uncapped fast-forward on the Thor: +12%)
        ds->nds->SetSlice(64, idle, 4096);
        LOGI("scheduler slice 64, %d away from IPC", idle);
    }
    LOGI("BananaDS up: save %u bytes, %d Hz", ds->nds->GetNDSSaveLength(), sampleRate);
    return true;
}

void pk_ndsb_deinit(void)
{
    if (!ds) return;
    if (ds->nds) {
        pk_ndsb_flush_save();
        pk_nds_wide_hires(1, nullptr, nullptr);
        pk_nds_wide_set(nullptr, false);
        ds->nds->Stop();
        delete ds->nds;
    }
    delete ds;
    ds = nullptr;
    touch.store(0);
}

bool pk_ndsb_active(void) { return ds != nullptr; }

void pk_ndsb_set_keys(uint32_t keys)
{
    if (ds) ds->keys = keys & 0xFFF;
}

void pk_ndsb_touch(bool down, int x, int y)
{
    u32 cx = x < 0 ? 0 : x > PK_NDS_W - 1 ? PK_NDS_W - 1 : x;
    u32 cy = y < 0 ? 0 : y > PK_NDS_H - 1 ? PK_NDS_H - 1 : y;
    touch.store((down ? 0x80000000u : 0u) | (cx << 8) | cy);
}

void pk_ndsb_run_frame(void)
{
    if (!ds) return;
    NDS* nds = ds->nds;
    if (ds->scalePending) {
        ds->scalePending = false;
        if (ds->scaleWant != ds->scale) {
            setRenderer(*nds, ds->scaleWant);
            nds->GetRenderer().PostSavestate();   // (its copies of VRAM made again, as after a state)
            if (ds->wide) updateHires();
            LOGI("DS RESOLUTION: BananaDS at %dx", ds->scale);
        }
    }
    // Uncapped fast-forward asks for every frame (its groups are for the capped speeds): past showHz frames a second
    // the frames between aren't read back (the screen can't show them; EXPAND's margins wait for a shown one too)
    // EXPAND's slices for this frame are drawn only after a frame that promised it (drawNext). Fast-forward breaks that
    // promise when a group ends part-way (FF that can't keep its speed, 4x at DS RESOLUTION 4x; SMART's 1x; FF off):
    // such a frame isn't shown, its margins would be the backdrop (a flat blue in Black), and the next one is (as
    // pk_nds.cpp does with a frame whose 3D was skipped)
    bool show = ds->draw && (!ds->wide || ds->promisedNext);
    if (show && ds->showHz > 0) {
        timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        const int64_t now = (int64_t) ts.tv_sec * 1000000000 + ts.tv_nsec;
        if (now - ds->lastShown < 1000000000 / ds->showHz) show = false;
        else ds->lastShown = now;
    }
    const bool drawNext = ds->drawNext || (ds->draw && !show);
    ds->promisedNext = drawNext;
    // Fast-forward's frames nobody sees: the sound mixed less often (melonds-expand.patch's SPU::MixShift)
    nds->SPU.MixShift = show ? 0 : (u32) ds->coarseMix;
    nds->SetKeyMask(~ds->keys & 0xFFF);
    u32 t = touch.load();
    if (t & 0x80000000u) nds->TouchScreen((u16) ((t >> 8) & 0xFF), (u16) (t & 0xFF));
    else nds->ReleaseScreen();
    nds->RunFrame();
    // Every frame is drawn (BananaDS's melonDS has no draw skip); a frame fast-forward won't show isn't copied out.
    ds->drawn = show;
    ds->presented = false;
    const u32* hiTop = nullptr;
    int S = 1;
    if (show) {
        void *top = nullptr, *bottom = nullptr;
        if (nds->GPU.GetFramebuffers(&top, &bottom)) {
            hiTop = (const u32*) top;
            S = ds->banana->Scale();
            if (S > 1) {
                downsample((const u32*) top, S, ds->top, ds->topArgb);
                downsample((const u32*) bottom, S, ds->bottom);
            } else {
                convert((const u32*) top, ds->top);
                convert((const u32*) bottom, ds->bottom);
            }
        }
    }
    ds->audioFrames = nds->SPU.ReadOutput(ds->audio.data(), (int) ds->audio.size() / 2);
    ds->fieldBits = pk_ndsb_field_bits();
    if (ds->wide) {
        // As pk_nds.cpp: the overworld only (the field map on screen with nothing over it, a text box keeps it), or a
        // battle that's all 3D like it (Black's).
        const bool inBattle = (ds->fieldBits & PK_NDS_FIELD_BATTLE) != 0;
        const bool overworld = ((ds->fieldBits & PK_NDS_FIELD_SCREEN) &&
                                   !(ds->fieldBits & (PK_NDS_FIELD_MENU | PK_NDS_FIELD_BATTLE))) ||
            (inBattle && pk_nds_battle_3d(ds->field));
        const bool battle = inBattle && pk_nds_battle_margins(ds->field);
        // Every frame, as pk_nds.cpp: a frame not shown says so (draw), and EXPAND's slices are drawn after any frame the
        // next of which is shown (drawNext) - called on the shown frames only, fast-forward's groups (4x: three frames
        // in four not drawn) left the slices undrawn before the shown one, and its margins showed the backdrop.
        if (hiTop && S == 1) memcpy(ds->topArgb, hiTop, sizeof(ds->topArgb));
        pk_nds_wide_frame(nds, ds->topArgb, ds->drawn, overworld, drawNext, battle);
    }
    if (hiTop && S > 1) ds->presented = present(hiTop, S);
    if (ds->saveDirty && ++ds->saveQuiet >= SAVE_QUIET_FRAMES) pk_ndsb_flush_save();
}

uint32_t* pk_ndsb_top(void) { return ds ? ds->top : nullptr; }
uint32_t* pk_ndsb_bottom(void) { return ds ? ds->bottom : nullptr; }

int pk_ndsb_read_audio(int16_t* out, int maxFrames)
{
    if (!ds) return 0;
    int n = ds->audioFrames < maxFrames ? ds->audioFrames : maxFrames;
    memcpy(out, ds->audio.data(), (size_t) n * 2 * sizeof(s16));
    ds->audioFrames = 0;
    return n;
}

void pk_ndsb_set_audio_rate(double rate)
{
    if (ds && rate > 0) ds->nds->SPU.SetOutputSkew(ds->sampleRate / rate);
}

bool pk_ndsb_flush_save(void)
{
    if (!ds || !ds->saveDirty) return false;
    ds->saveDirty = false;
    writeSaveFile();
    return true;
}

int pk_ndsb_finish_save(int maxFrames)
{
    if (!ds) return 0;
    int n = 0;
    u32 keys = ds->keys;
    u32 t = touch.exchange(0);
    ds->keys = 0;
    while (ds->saveDirty && n < maxFrames) {
        pk_ndsb_run_frame();
        n++;
    }
    pk_ndsb_flush_save();
    ds->keys = keys;
    touch.store(t);
    return n;
}

bool pk_ndsb_save_state(const char* path)
{
    if (!ds) return false;
    Savestate st;
    pk_nds_wide_sync();
    setArm9Shift(1);
    bool taken = ds->nds->DoSavestate(&st) && !st.Error;
    setArm9Shift(boostShift());
    if (!taken) return false;
    st.Finish();
    const u8* save = ds->nds->GetNDSSave();
    u32 saveLen = ds->nds->GetNDSSaveLength();
    u32 header[4] = {STATE_MAGIC, STATE_VERSION, save ? saveCrc(save, saveLen) : 0, saveLen};
    FILE* f = fopen(path, "wb");
    if (!f) return false;
    bool ok = fwrite(header, sizeof(header), 1, f) == 1 && fwrite(st.Buffer(), 1, st.Length(), f) == st.Length();
    ok = fclose(f) == 0 && ok;
    return ok;
}

bool pk_ndsb_load_state(const char* path)
{
    if (!ds) return false;
    std::vector<u8> data;
    if (!readFile(path, data) || data.size() < 16) return false;
    u32 header[4];
    memcpy(header, data.data(), sizeof(header));
    if (header[0] != STATE_MAGIC) return false;
    Savestate st(data.data() + 16, (u32) (data.size() - 16), false);
    pk_nds_wide_sync();
    setArm9Shift(1);
    bool loaded = !st.Error && ds->nds->DoSavestate(&st) && !st.Error;
    setArm9Shift(boostShift());
    if (!loaded) {
        LOGE("loadstate failed: %s", path);
        return false;
    }
    if (const u8* mem = ds->nds->GetNDSSave()) {
        ds->saveCopy.assign(mem, mem + ds->nds->GetNDSSaveLength());
        ds->saveDirty = true;
        pk_ndsb_flush_save();
    }
    setClock();
    return true;
}

int pk_ndsb_state_matches_save(const char* path)
{
    if (!ds) return -1;
    FILE* f = fopen(path, "rb");
    if (!f) return -1;
    u32 header[4];
    bool ok = fread(header, sizeof(header), 1, f) == 1;
    fclose(f);
    if (ok && (header[0] == PK_NDS_STATE_MAGIC_MELONDS || header[0] == PK_NDS_STATE_MAGIC_DSPERATE)) return 2;
    if (!ok || header[0] != STATE_MAGIC || header[3] == 0) return -1;
    if (ds->savePath.empty() || ds->saveReadOnly) return 1;
    std::vector<u8> file;
    readFile(ds->savePath.c_str(), file);
    file.resize(header[3], 0xFF);
    return saveCrc(file.data(), file.size()) == header[2] ? 1 : 0;
}

void pk_ndsb_read(uint32_t addr, uint8_t* dst, int len)
{
    if (!ds) {
        memset(dst, 0, (size_t) len);
        return;
    }
    NDS* nds = ds->nds;
    if (addr >= ROM_WINDOW) {
        auto* cart = nds->NDSCartSlot.GetCart();
        const u8* rom = cart ? cart->GetROM() : nullptr;
        u32 romLen = cart ? cart->GetROMLength() : 0;
        for (int i = 0; i < len; i++) {
            u32 off = addr - ROM_WINDOW + (u32) i;
            dst[i] = rom && off < romLen ? rom[off] : 0xFF;
        }
        return;
    }
    if ((addr & 0xFF000000u) == 0x02000000u) {
        u32 mask = nds->MainRAMMask;
        int done = 0;
        while (done < len) {
            u32 off = (addr + (u32) done) & mask;
            int n = (int) std::min<u32>(mask + 1 - off, (u32) (len - done));
            memcpy(dst + done, nds->MainRAM + off, (size_t) n);
            done += n;
        }
        return;
    }
    for (int i = 0; i < len; i++) dst[i] = nds->ARM9Read8(addr + (u32) i);
}

bool pk_ndsb_write(uint32_t addr, const uint8_t* src, int len)
{
    if (!ds || (addr & 0xFF000000u) != 0x02000000u || len <= 0) return false;
    for (int i = 0; i < len; i++) ds->nds->MainRAM[(addr + (u32) i) & ds->nds->MainRAMMask] = src[i];
    return true;
}

int pk_ndsb_field_bits(void)
{
    if (!ds || !ds->field) return 0;
    int bits = pk_nds_field_state(ds->field, mainRead32, mainWrite32, ds->wildOff);
    bits |= pk_nds_field_screen_touch(ds->field, bits, ds->nds->GPU.GPU2D_A.DispCnt);
    const bool darkTop = oneColour(ds->top), darkBottom = oneColour(ds->bottom);
    bits = pk_nds_field_screen_bits(bits, darkTop, darkBottom);
    if (!(bits & PK_NDS_FIELD_MENU)) ds->menuLitFrames = 0;
    else if (!darkTop || !darkBottom) ds->menuLitFrames++;
    else if (ds->keepStartMenu && ds->menuLitFrames > 0) pk_nds_field_keep_start_menu(ds->field, mainRead32, mainWrite32);
    if ((bits & PK_NDS_FIELD_TEXT) && !pk_nds_bg3_tiles(ds->nds, ds->field->textBg ? ds->field->textBg : 3)) bits &= ~PK_NDS_FIELD_TEXT;
    if ((bits & PK_NDS_FIELD_BATTLE) && pk_nds_battle_command(ds->field, mainRead32) == pk_nds_battle_selecting(ds->field))
        bits |= PK_NDS_FIELD_BATTLE_INPUT;
    return bits;
}

// The game's own functions (PC / FLY) hook the app's melonDS: none here.
// INSTANT TEXT / REUSABLE TMS's code writes (pk_nds.cpp's, with its JIT invalidation): not here, as on DSperate. The
// app offers them on melonDS only (PokeDaisyActivity.dsperate), so this just answers no.
bool pk_ndsb_code_write(uint32_t, const uint8_t*, int) { return false; }
int pk_ndsb_action(int, const uint32_t[4]) { return 0; }
int pk_ndsb_action_status(int) { return -1; }
bool pk_ndsb_action_results(int, uint32_t*) { return false; }

void pk_ndsb_set_wild_off(bool off)
{
    if (ds) ds->wildOff = off;
}

void pk_ndsb_set_wild_rates(const int32_t* triples, int n) { pk_nds_field_set_rates(triples, n); }

void pk_ndsb_set_keep_start_menu(bool on)
{
    if (ds) ds->keepStartMenu = on;
}

// EXP SHARE: melonDS only (ExpShare.dsFind), as on DSperate. Missing, libpkbananads.so lacked a call pk_nds_bnd_load.cpp
// looks up and wouldn't load: no game started on BananaDS (Discord, SoulSilver on 1.6.2-beta.1).
void pk_ndsb_set_exp_share(int, uint32_t) {}

void pk_ndsb_set_cpu_boost(bool on)
{
    if (!ds || ds->cpuBoost == on) return;
    ds->cpuBoost = on;
    setArm9Shift(boostShift());
}

int pk_ndsb_set_cheats(const uint32_t* words, int nWords, const int32_t* lengths, int nCheats)
{
    if (!ds) return -1;
    std::vector<ARCode> codes;
    int at = 0;
    for (int i = 0; i < nCheats; i++) {
        int len = lengths[i];
        if (len <= 0 || (len & 1) || at + len > nWords) break;
        ARCode c{};
        c.Parent = nullptr;
        c.Enabled = true;
        c.Code.assign(words + at, words + at + len);
        at += len;
        codes.push_back(std::move(c));
    }
    int n = (int) codes.size();
    ds->nds->AREngine.Cheats = std::move(codes);
    return n;
}

// STEADY music runs a second copy of the app's melonDS: none here (-1: FF plays SPED-UP).
int pk_ndsb_bgm(void) { return -1; }
int pk_ndsb_bgm_state(void) { return 0x7F00; }
bool pk_ndsb_music_start(void) { return false; }
int pk_ndsb_music_read(int16_t*, int) { return 0; }
void pk_ndsb_music_fade_out(int) {}
void pk_ndsb_music_stop(void) {}

void pk_ndsb_wide_enable(bool on)
{
    if (!ds || ds->wide == on) return;
    ds->wide = on;
    // render.geom_reject drops polygons wholly off the screen before EXPAND's hook sees them: off while it's on
    ds->nds->GPU.GPU3D.GeomReject = on ? false : geomReject;
    pk_nds_wide_set(ds->nds, on);
    updateHires();
}

bool pk_ndsb_wide_draw(uint32_t* out, int x, int y, int w, int h)
{
    if (!ds || !ds->wide) return false;
    pk_nds_wide_view(out, x, y, w, h);
    return true;
}

void pk_ndsb_wide_window(int x, int y, int w, int h)
{
    pk_nds_wide_need(-x, x + w - PK_NDS_W, -y, y + h - PK_NDS_H);
    if (!ds) return;
    ds->winX = x;
    ds->winY = y;
    ds->winW = w;
    ds->winH = h;
}

void pk_ndsb_set_draw(bool draw, bool drawNext)
{
    if (!ds) return;
    ds->draw = draw;
    ds->drawNext = drawNext;
}

bool pk_ndsb_frame_drawn(void) { return ds && ds->drawn; }

int pk_ndsb_wide_scene(void)
{
    int b = ds ? ds->fieldBits : 0;
    if (b & PK_NDS_FIELD_BATTLE) return 4;
    u32 mb = ds ? ds->nds->GPU.MasterBrightnessA : 0;
    if (((mb >> 14) == 1 || (mb >> 14) == 2) && (mb & 0x1F) >= 16) return 5;
    if (b & PK_NDS_FIELD_MENU) return 6;
    if (!(b & PK_NDS_FIELD_SCREEN)) return 4;
    return (b & PK_NDS_FIELD_TEXT) ? 1 : 0;
}

int pk_ndsb_fade(void)
{
    if (!ds) return 0;
    return fadeFactor(ds->nds->GPU.MasterBrightnessA) | (fadeFactor(ds->nds->GPU.MasterBrightnessB) << 8);
}

// DS RESOLUTION: BananaDS's own hires, 2x to 4x (8x and the others between ask for its 4x).
void pk_ndsb_set_scale(int scale)
{
    if (!ds) return;
    (void) scale;
    ds->scaleWant = 1;   // no hires without Gengis (setRenderer): DS RESOLUTION is melonDS's alone (DsCore.hasResolution)
    ds->scalePending = true;
}

int pk_ndsb_scale(void) { return ds ? ds->scale : 1; }
bool pk_ndsb_presented(void) { return ds && ds->presented; }

bool pk_ndsb_gpu_frame_info(int64_t, int32_t out[6])
{
    // Every frame is the whole top screen: whichever the timestamp, the newest's geometry is right.
    std::lock_guard<std::mutex> l(win.infoLock);
    if (!win.infoValid) return false;
    memcpy(out, win.info, sizeof(win.info));
    return true;
}

void pk_ndsb_set_window(ANativeWindow* window)
{
    std::lock_guard<std::mutex> l(win.lock);
    if (win.next) ANativeWindow_release(win.next);
    win.next = window;
    win.pending = true;
}

bool pk_ndsb_rom_code(char out[5])
{
    if (!ds) return false;
    auto* cart = ds->nds->NDSCartSlot.GetCart();
    if (!cart) return false;
    memcpy(out, cart->GetHeader().GameCode, 4);
    out[4] = 0;
    return true;
}

int pk_ndsb_rom_revision(void)
{
    auto* cart = ds ? ds->nds->NDSCartSlot.GetCart() : nullptr;
    return cart ? cart->GetHeader().ROMVersion : -1;
}

uint32_t pk_ndsb_rom_size(void)
{
    auto* cart = ds ? ds->nds->NDSCartSlot.GetCart() : nullptr;
    return cart ? cart->GetROMLength() : 0;
}

const uint8_t* pk_ndsb_dtcm(void) { return ds ? ds->nds->ARM9.DTCM : nullptr; }

} // extern "C"
