// The app's third Nintendo DS core: BananaDS (third_party/bananads, GPL-3.0-or-later), as pk_ndsb_* behind pk_nds.h
// (pk_nds_core.cpp hands it the calls while it runs; pk_nds_cores.h). It lives in libpkbananads.so (CMakeLists.txt here
// says why), the same emulator BananaOS's handhelds run: melonDS at BananaDS's pin with BananaDS's patches, melonDS's
// AArch64 JIT, the Banana 2D engine and Gengis Engine for the 3D, set up with BananaDS's own defaults (its emu.cpp with
// no config file).
//
// What it does as melonDS does (pk_nds.cpp): the screens as RGBA, the audio at the engine's rate, the save written once
// the game's writes have been quiet for SAVE_QUIET_FRAMES, states behind our header carrying the save's CRC32
// (pk_nds_state_matches_save), the field read (pk_nds_field.cpp), cheats, DS CPU BOOST. What it doesn't have, as on
// DSperate: EXPAND, DS RESOLUTION, PC / FLY calls and STEADY music (they hook the app's patched melonDS 1.1); those
// calls do nothing here and the settings grey them out. States are BananaDS's melonDS's own and never cross cores.

#include "pk_nds_cores.h"
#include "pk_nds_field.h"

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
#include "gengis_renderer.h"

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <memory>
#include <string>
#include <vector>

#include <zlib.h>

#define LOG_TAG "pokedaisy/bananads"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace melonDS;

// BananaDS's emu.cpp: Gengis's workers leave the emulator's core when sched.emu_share asks (never here).
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
};

Ds* ds = nullptr;
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
    setenv("MELONDS_JIT_PINREGS", "300f", 0);
    setenv("MELONDS_JIT_LOOPREGS", "1", 0);
    setenv("MELONDS_GEOMTHREAD", "0", 0);
}

void bananaSetup(NDS& n)
{
    NDSCart::NDSCartSlot::XferShift = 0;   // core.card_fast 0
    n.SetSlice(64, 64, 4096);              // core.slice 64 (melonDS's)
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
    using bananads::GengisRenderer3D;
    GengisRenderer3D::Help = 1;
    GengisRenderer3D::Nice = 0;
    GengisRenderer3D::WakeN = 1;
    GengisRenderer3D::AbModes.clear();
    GengisRenderer3D::OptAb.clear();
    GengisRenderer3D::Stats = 0;
    GengisRenderer3D::HrNeon = -1;
    GengisRenderer3D::Verify = 0;
    GengisRenderer3D::Lazy = 0;
    GengisRenderer3D::Units = 3;
    GengisRenderer3D::UnitsLow = 2;
    GengisRenderer3D::KeySkip = 1;
    GengisRenderer3D::VbWait = -1;
    GengisRenderer3D::Crc = 0;
    GengisRenderer3D::TexInval = 1;
    GengisRenderer3D::Zva = 1;
    GengisRenderer3D::Trim = 1;
    GengisRenderer3D::Reuse = 1;
    GengisRenderer3D::SetEffects(true, true);
    // render.renderer gengis on render.gengis_threads 3, render.2d banana, 1x
    n.SetRenderer(std::make_unique<bananads::GengisBananaRenderer>(n, 3, false, 1));
    ds->banana = &static_cast<BananaRenderer&>(n.GetRenderer());
    ds->banana->SetSplitRing(true);
    ds->banana->SetMerge(true);
    ds->banana->SetSkip(true);
    ds->banana->SetOutSkip(true);
    ds->banana->SetTestDelay(0);
    ds->banana->SetKickBatch(64);
    ds->banana->SetNice(0);
    ds->banana->SetFenceWaitBlocking(true);
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
    ds->nds->Start();
    if (const u8* mem = ds->nds->GetNDSSave()) ds->saveCopy.assign(mem, mem + ds->nds->GetNDSSaveLength());
    ds->audio.resize(8192 * 2);
    {
        const auto* c = ds->nds->NDSCartSlot.GetCart();
        ds->field = pk_nds_field_game_rom(c->GetROM(), c->GetROMLength());
    }
    LOGI("BananaDS up: save %u bytes, %d Hz", ds->nds->GetNDSSaveLength(), sampleRate);
    return true;
}

void pk_ndsb_deinit(void)
{
    if (!ds) return;
    if (ds->nds) {
        pk_ndsb_flush_save();
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
    nds->SetKeyMask(~ds->keys & 0xFFF);
    u32 t = touch.load();
    if (t & 0x80000000u) nds->TouchScreen((u16) ((t >> 8) & 0xFF), (u16) (t & 0xFF));
    else nds->ReleaseScreen();
    nds->RunFrame();
    // Every frame is drawn (BananaDS's melonDS has no draw skip); a frame fast-forward won't show isn't copied out.
    ds->drawn = ds->draw;
    if (ds->draw) {
        void *top = nullptr, *bottom = nullptr;
        if (nds->GPU.GetFramebuffers(&top, &bottom)) {
            convert((const u32*) top, ds->top);
            convert((const u32*) bottom, ds->bottom);
        }
    }
    ds->audioFrames = nds->SPU.ReadOutput(ds->audio.data(), (int) ds->audio.size() / 2);
    ds->fieldBits = pk_ndsb_field_bits();
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
    if (darkTop && !darkBottom) bits |= PK_NDS_FIELD_TOUCH;
    if (darkTop) bits |= PK_NDS_FIELD_DARK_TOP;
    if (darkBottom) bits |= PK_NDS_FIELD_DARK_BOTTOM;
    if (!(bits & PK_NDS_FIELD_MENU)) ds->menuLitFrames = 0;
    else if (!darkTop || !darkBottom) ds->menuLitFrames++;
    else if (ds->keepStartMenu && ds->menuLitFrames > 0) pk_nds_field_keep_start_menu(ds->field, mainRead32, mainWrite32);
    if ((bits & PK_NDS_FIELD_TEXT) && !pk_nds_bg3_tiles(ds->nds, ds->field->textBg ? ds->field->textBg : 3)) bits &= ~PK_NDS_FIELD_TEXT;
    if ((bits & PK_NDS_FIELD_BATTLE) && pk_nds_battle_command(ds->field, mainRead32) == pk_nds_battle_selecting(ds->field))
        bits |= PK_NDS_FIELD_BATTLE_INPUT;
    return bits;
}

// The game's own functions (PC / FLY) hook the app's melonDS: none here.
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

// EXPAND: none here.
void pk_ndsb_wide_enable(bool) {}
bool pk_ndsb_wide_draw(uint32_t*, int, int, int, int) { return false; }
void pk_ndsb_wide_window(int, int, int, int) {}

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

// DS RESOLUTION is the app's melonDS's OpenGL renderer: always the DS's own here.
void pk_ndsb_set_scale(int) {}
int pk_ndsb_scale(void) { return 1; }
bool pk_ndsb_presented(void) { return false; }
bool pk_ndsb_gpu_frame_info(int64_t, int32_t[6]) { return false; }

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
