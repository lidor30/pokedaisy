// The app's first Nintendo DS core: melonDS (third_party/melonds, 1.1), as pk_ndsm_* behind pk_nds.h (pk_nds_core.cpp
// hands it the calls while it runs; pk_nds_cores.h). See pk_nds.h for who calls what.
//
// Saves: the game writes its flash a sector at a time over many frames; each write marks the save dirty
// (pk_nds_platform.cpp's onSave), and it reaches the file once the writes have been quiet for SAVE_QUIET_FRAMES
// (or at pk_nds_flush_save: a state, a close). The whole file is rewritten each time - 512 KB for Gen 4.
//
// States: melonDS's own format behind a small header of ours carrying the save memory's CRC32 when the state was
// made, so pk_nds_state_matches_save can tell (like the GBA's pkStateMatchesSave) whether loading it would put an
// older save back: melonDS states carry the cart's save memory, and a load writes it to the file here as mGBA does.

#include "pk_nds.h"
#include "pk_nds_cores.h"
#include "pk_nds_platform.h"
#include "pk_nds_field.h"
#include "pk_nds_call.h"
#include "pk_nds_wide.h"
#include "pk_nds_music.h"
#include "pk_nds_gl.h"

#include <NDS.h>
#include <NDSCart.h>
#include <GPU.h>
#include <GPU3D_Soft.h>
#include <SPU.h>
#include <RTC.h>
#include <Savestate.h>
#include <Args.h>
#include <AREngine.h>

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <string>
#include <vector>

#include <zlib.h>

#define LOG_TAG "pokedaisy/nds"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace melonDS;

namespace {

constexpr int SAVE_QUIET_FRAMES = 30;
constexpr u32 STATE_MAGIC = PK_NDS_STATE_MAGIC_MELONDS;
constexpr u32 STATE_VERSION = 1;
constexpr u32 ROM_WINDOW = 0x08000000; // pk_nds_read: the cart's ROM file from here

struct Ds {
    NDS* nds = nullptr;
    PkNdsHost host {};
    std::string savePath;
    bool saveReadOnly = false;
    std::vector<u8> saveCopy;      // the save memory as the game last wrote it
    bool saveDirty = false;
    int saveQuiet = 0;             // frames since the last write
    u32 keys = 0;
    u32 top[PK_NDS_W * PK_NDS_H] {};
    u32 bottom[PK_NDS_W * PK_NDS_H] {};
    std::vector<s16> audio;
    int audioFrames = 0;
    int sampleRate = 48000;
    const PkNdsFieldGame* field = nullptr;   // the field read's table entry for this cart, if any
    bool wide = false;                       // EXPAND on (pk_nds_wide_enable)
    bool draw = true, drawNext = true;       // this frame / the next one is shown (pk_nds_set_draw: fast-forward's skips)
    bool drawn = true;                       // the last frame was drawn (pk_nds_frame_drawn)
    int fieldBits = 0;                       // the field read after the last frame
    bool wildOff = false;                    // WILD ENCOUNTERS OFF (pk_nds_field.cpp holds the map's rates at 0)
    bool keepStartMenu = false;              // DS LAYOUT floats the X menu: it comes back after a screen left by touch
    bool cpuBoost = false;                   // DS CPU BOOST: the ARM9 at twice the DS's clock (setArm9Shift)
    int menuLitFrames = 0;                   // frames this menu screen has been lit (its closing fade comes after)
    int scaleWant = 1;                       // DS RESOLUTION: 1 = the software renderer, 2-8 the GPU's (pk_nds_gl.h)
    bool scalePending = false;
    bool presented = false;                  // the last frame went out through the GPU path's window
    int winX = 0, winY = 0, winW = PK_NDS_W, winH = PK_NDS_H;   // EXPAND's window (the screen without it)
    u32 topArgb[PK_NDS_W * PK_NDS_H] {};     // GPU mode: the top screen's 1x copy as melonDS draws (EXPAND reads it)
};

Ds* ds = nullptr;
// The stylus, set from any thread (the bottom screen's view): bit 31 = down, x in bits 8-15, y in bits 0-7.
std::atomic<u32> touch {0};

void onSave(PkNdsHost* host, const uint8_t* data, uint32_t len, uint32_t, uint32_t)
{
    if (!ds || host != &ds->host) return;
    ds->saveCopy.assign(data, data + len);
    ds->saveDirty = true;
    ds->saveQuiet = 0;
}

void onStop(PkNdsHost*, int reason) { LOGI("DS stopped (reason %d)", reason); }

void onLog(int level, const char* msg)
{
    if (level >= Platform::Warn) __android_log_print(level >= Platform::Error ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, LOG_TAG, "%s", msg);
}

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

// DS CPU BOOST: the ARM9 at 2^shift times the bus clock (1 = a DS; 2 = twice that, as a DSi runs it), switched live the
// way DSi::Set_SCFG_Clock9 does: its timestamps rescaled, its memory timings redone, and the JIT's blocks (their cycle
// counts) dropped. A game that ran out of time in a frame (HeartGold walking: a 30 fps overworld held for 3 or 4 refreshes
// every few steps, headless) gets its frames done; one that waits for the next frame anyway waits longer, nothing else.
// States are always taken and read at the DS's own clock: melonDS stores the timestamps as they are.
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

} // namespace

extern "C" {

bool pk_nds_is_rom(const char* path)
{
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    u8 h[0x160];
    bool ok = fread(h, 1, sizeof(h), f) == sizeof(h);
    fclose(f);
    // The Nintendo logo's CRC16, the same on every DS cart.
    return ok && h[0x15C] == 0x56 && h[0x15D] == 0xCF;
}

bool pk_ndsm_init(const char* romPath, const char* savePath, int sampleRate)
{
    pk_ndsm_deinit();
    // Read once, into the buffer melonDS keeps (a DS ROM is up to 512 MB: no second copy).
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
    ds->host = PkNdsHost {onSave, onStop, nullptr};
    ds->sampleRate = sampleRate;
    pkNdsSetLog(onLog);

    std::vector<u8> save;
    if (savePath) {
        ds->savePath = savePath;
        readFile(savePath, save);
        FILE* probe = fopen(savePath, save.empty() ? "ab" : "r+b");
        ds->saveReadOnly = probe == nullptr;
        if (probe) fclose(probe);
        if (ds->saveReadOnly) LOGI("save opened read-only (new saves won't persist): %s", savePath);
    }

    NDSArgs args;
    args.OutputSampleRate = sampleRate;
    // The JIT with fast memory: the DS's memory mapped where the compiled code reaches it directly, a fault mapping a
    // page or sending the access to the slow path. The fault handler (ARMJIT_Memory::SigsegvHandler) is melonDS's own,
    // set up with the JIT whether fast memory is on or not; it takes only faults in compiled code and passes the rest to
    // the handler before it (crash reporting's, set up at app start; pk_wide.c's guard). Off where melonDS can't
    // (IsFastMemSupported: page sizes other than 4 / 16 KB); 32-bit ARM has no JIT at all.
    args.JIT = JITArgs {32, true, true, true};
    ds->nds = new NDS(std::move(args), &ds->host);
    ds->nds->Reset();

    NDSCart::NDSCartArgs cartArgs;
    if (!save.empty()) {
        cartArgs.SRAM = std::make_unique<u8[]>(save.size());
        memcpy(cartArgs.SRAM.get(), save.data(), save.size());
        cartArgs.SRAMLength = (u32) save.size();
    }
    auto cart = NDSCart::ParseROM(std::move(rom), romLen, &ds->host, std::move(cartArgs));
    if (!cart) {
        LOGE("not a DS ROM melonDS can run");
        pk_ndsm_deinit();
        return false;
    }
    ds->nds->SetNDSCart(std::move(cart));
    ds->nds->Reset();
    ds->nds->SetupDirectBoot("rom.nds");
    // The 3D on melonDS's render thread, beside the emulation (HeartGold's overworld, headless: a quarter less time a
    // frame). From 2026-10-20 to 2026-11-09 it ran on the emulation thread: with fast-forward's frame skip, a frame
    // shown read 3D lines not drawn yet on the Thor (black from a row down). The cause was a render started for a
    // skipped frame (a state taken or loaded mid-skip, a restart, the thread switched on), whose line signals the next
    // frame took; the patch's SoftRenderer::EnableRenderThread starts none now, and RenderFrame drops any left over.
    static_cast<SoftRenderer&>(ds->nds->GPU.GetRenderer3D()).SetThreaded(true, ds->nds->GPU);
    setClock();
    setArm9Shift(boostShift());
    ds->nds->Start();
    if (const u8* mem = ds->nds->GetNDSSave()) ds->saveCopy.assign(mem, mem + ds->nds->GetNDSSaveLength());
    ds->audio.resize(8192 * 2);
    {
        const auto* c = ds->nds->NDSCartSlot.GetCart();
        ds->field = pk_nds_field_game_rom(c->GetROM(), c->GetROMLength());
    }
    LOGI("DS up: save %u bytes, %d Hz", ds->nds->GetNDSSaveLength(), sampleRate);
    return true;
}

void pk_ndsm_deinit(void)
{
    if (!ds) return;
    if (ds->nds) {
        pk_ndsm_flush_save();
        pk_nds_wide_set(nullptr, false);
        pk_nds_gl_end(ds->nds);
        pk_nds_music_end(true);
        ds->nds->Stop();
        delete ds->nds;
    }
    delete ds;
    ds = nullptr;
    touch.store(0);
}

bool pk_ndsm_active(void) { return ds != nullptr; }

void pk_ndsm_set_keys(uint32_t keys)
{
    if (ds) ds->keys = keys & 0xFFF;
}

void pk_ndsm_touch(bool down, int x, int y)
{
    u32 cx = x < 0 ? 0 : x > PK_NDS_W - 1 ? PK_NDS_W - 1 : x;
    u32 cy = y < 0 ? 0 : y > PK_NDS_H - 1 ? PK_NDS_H - 1 : y;
    touch.store((down ? 0x80000000u : 0u) | (cx << 8) | cy);
}

void pk_ndsm_run_frame(void)
{
    if (!ds) return;
    NDS* nds = ds->nds;
    if (ds->scalePending) {
        ds->scalePending = false;
        pk_nds_wide_sync();
        pk_nds_gl_set_scale(nds, ds->scaleWant);
    }
    bool gpu = pk_nds_gl_scale() > 1;
    if (gpu) pk_nds_gl_begin_frame();
    nds->SetKeyMask(~ds->keys & 0xFFF);
    u32 t = touch.load();
    if (t & 0x80000000u) nds->TouchScreen((u16) ((t >> 8) & 0xFF), (u16) (t & 0xFF));
    else nds->ReleaseScreen();
    // A frame no one will see (fast-forward shows one of each group) isn't drawn at all: no 2D lines, no 3D.
    // drawNext is a promise made a frame ahead (the 3D for this frame was started, or not, at the last one's line 215;
    // EXPAND's slices for it were drawn, or not, after the last one), and fast-forward can break it: SMART's 1x for a
    // menu, FF off, the refresh pacing dropping out all end a group part-way. A frame whose 3D was skipped can't be
    // shown - its 3D would be an older frame's and EXPAND's margins black (a flash) - so it's skipped too, and the
    // next one, drawn whole, is shown in its place.
    bool draw = ds->draw && !nds->GPU.SkippingFrame;
    bool drawNext = ds->drawNext || (ds->draw && !draw);
    ds->drawn = draw;
    nds->GPU.SkipDraw = !draw;
    nds->GPU.SkipDrawNext = !drawNext;
    nds->RunFrame();
    int fb = nds->GPU.FrontBuffer;
    // GPU mode: the framebuffers hold the 2D lines for the compositor; the screens come from it (below).
    if (draw && !gpu) {
        convert(nds->GPU.Framebuffer[fb][0].get(), ds->top);
        convert(nds->GPU.Framebuffer[fb][1].get(), ds->bottom);
    }
    ds->audioFrames = nds->SPU.ReadOutput(ds->audio.data(), (int) ds->audio.size() / 2);
    ds->fieldBits = pk_ndsm_field_bits();
    pk_nds_call_frame(nds, ds->field);
    if (ds->wide) {
        // The overworld only: the field map on screen with nothing over it (a text box keeps it) - or a battle that's
        // all 3D like it (Black's).
        bool inBattle = (ds->fieldBits & PK_NDS_FIELD_BATTLE) != 0;
        bool overworld = ((ds->fieldBits & PK_NDS_FIELD_SCREEN) && !(ds->fieldBits & (PK_NDS_FIELD_MENU | PK_NDS_FIELD_BATTLE))) ||
            (inBattle && pk_nds_battle_3d(ds->field));
        bool battle = inBattle && pk_nds_battle_margins(ds->field);
        pk_nds_wide_gpu_want(overworld || battle);
        pk_nds_wide_frame(nds, gpu ? ds->topArgb : nds->GPU.Framebuffer[fb][0].get(), draw, overworld, drawNext, battle);
    }
    ds->presented = false;
    if (gpu && draw) {
        bool win = ds->wide && ds->winW > 0 && ds->winH > 0;
        ds->presented = pk_nds_gl_frame(nds, win ? ds->winX : 0, win ? ds->winY : 0, win ? ds->winW : PK_NDS_W,
            win ? ds->winH : PK_NDS_H, ds->top, ds->bottom);
        // The copy as melonDS's own frames are (0xAARRGGBB), for EXPAND's checks on the next frame.
        for (int i = 0; i < PK_NDS_W * PK_NDS_H; i++) {
            u32 p = ds->top[i];
            ds->topArgb[i] = 0xFF000000u | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
        }
    }
    if (ds->saveDirty && ++ds->saveQuiet >= SAVE_QUIET_FRAMES) pk_ndsm_flush_save();
}

uint32_t* pk_ndsm_top(void) { return ds ? ds->top : nullptr; }
uint32_t* pk_ndsm_bottom(void) { return ds ? ds->bottom : nullptr; }

int pk_ndsm_read_audio(int16_t* out, int maxFrames)
{
    if (!ds) return 0;
    int n = ds->audioFrames < maxFrames ? ds->audioFrames : maxFrames;
    memcpy(out, ds->audio.data(), (size_t) n * 2 * sizeof(s16));
    ds->audioFrames = 0;
    return n;
}

void pk_ndsm_set_audio_rate(double rate)
{
    // Output samples per emulated second = OutputSampleRate / skew.
    if (ds && rate > 0) ds->nds->SPU.SetOutputSkew(ds->sampleRate / rate);
}

bool pk_ndsm_flush_save(void)
{
    if (!ds || !ds->saveDirty) return false;
    ds->saveDirty = false;
    writeSaveFile();
    return true;
}

int pk_ndsm_finish_save(int maxFrames)
{
    if (!ds) return 0;
    int n = 0;
    u32 keys = ds->keys;
    u32 t = touch.exchange(0);
    ds->keys = 0;
    while (ds->saveDirty && n < maxFrames) {
        pk_ndsm_run_frame();
        n++;
    }
    pk_ndsm_flush_save();
    ds->keys = keys;
    touch.store(t);
    return n;
}

bool pk_ndsm_save_state(const char* path)
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

bool pk_ndsm_load_state(const char* path)
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
    pk_nds_call_reset();
    // The state put its save memory back: the file follows, as on the GBA.
    if (const u8* mem = ds->nds->GetNDSSave()) {
        ds->saveCopy.assign(mem, mem + ds->nds->GetNDSSaveLength());
        ds->saveDirty = true;
        pk_ndsm_flush_save();
    }
    setClock();
    return true;
}

int pk_ndsm_state_matches_save(const char* path)
{
    if (!ds) return -1;
    FILE* f = fopen(path, "rb");
    if (!f) return -1;
    u32 header[4];
    bool ok = fread(header, sizeof(header), 1, f) == 1;
    fclose(f);
    if (ok && header[0] == PK_NDS_STATE_MAGIC_DSPERATE) return 2;   // DSperate's (pk_nds.h): never loaded here
    if (!ok || header[0] != STATE_MAGIC || header[3] == 0) return -1;
    if (ds->savePath.empty() || ds->saveReadOnly) return 1;
    std::vector<u8> file;
    readFile(ds->savePath.c_str(), file);
    file.resize(header[3], 0xFF);   // flash the file hasn't got yet reads erased
    return saveCrc(file.data(), file.size()) == header[2] ? 1 : 0;
}

void pk_ndsm_read(uint32_t addr, uint8_t* dst, int len)
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
        // In runs up to the mirror's end (a sample copies all 4 MB: byte by byte it took ~4 ms on the RP6).
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

bool pk_ndsm_write(uint32_t addr, const uint8_t* src, int len)
{
    if (!ds || (addr & 0xFF000000u) != 0x02000000u || len <= 0) return false;
    for (int i = 0; i < len; i++) ds->nds->MainRAM[(addr + (u32) i) & ds->nds->MainRAMMask] = src[i];
    return true;
}

static uint32_t mainRead32(uint32_t addr)
{
    if ((addr & 0xFF000000u) != 0x02000000u) return 0;
    const u8* m = ds->nds->MainRAM;
    u32 mask = ds->nds->MainRAMMask;
    return (u32) m[addr & mask] | ((u32) m[(addr + 1) & mask] << 8) | ((u32) m[(addr + 2) & mask] << 16) |
        ((u32) m[(addr + 3) & mask] << 24);
}

static void mainWrite32(uint32_t addr, uint32_t v)
{
    if ((addr & 0xFF000000u) != 0x02000000u) return;
    u8* m = ds->nds->MainRAM;
    u32 mask = ds->nds->MainRAMMask;
    for (int i = 0; i < 4; i++) m[(addr + i) & mask] = (u8) (v >> (8 * i));
}

// A screen of one colour (a 16 x 12 grid of its pixels alike): black between scenes, or the top screen while a game's
// menu is on the touch screen alone.
static bool oneColour(const u32* px)
{
    const u32 first = px[0] & 0xFFFFFF;
    for (int gy = 0; gy < 12; gy++)
        for (int gx = 0; gx < 16; gx++)
            if ((px[(gy * PK_NDS_H / 12 + PK_NDS_H / 24) * PK_NDS_W + gx * PK_NDS_W / 16 + PK_NDS_W / 32] & 0xFFFFFF) != first) return false;
    return true;
}

int pk_ndsm_field_bits(void)
{
    if (!ds || !ds->field) return 0;
    int bits = pk_nds_field_state(ds->field, mainRead32, mainWrite32, ds->wildOff);
    bits |= pk_nds_field_screen_touch(ds->field, bits, ds->nds->GPU.GPU2D_A.DispCnt);   // the intro's naming screen
    // The top screen blank and the touch screen showing something (the main menu's CONTINUE, a question asked down
    // there): the touch screen is what there is to see.
    const bool darkTop = oneColour(ds->top), darkBottom = oneColour(ds->bottom);
    if (darkTop && !darkBottom) bits |= PK_NDS_FIELD_TOUCH;
    if (darkTop) bits |= PK_NDS_FIELD_DARK_TOP;
    if (darkBottom) bits |= PK_NDS_FIELD_DARK_BOTTOM;
    // A menu screen's closing fade (black after it was lit): keep the X menu it came from (pk_nds_field_keep_start_menu).
    if (!(bits & PK_NDS_FIELD_MENU)) ds->menuLitFrames = 0;
    else if (!darkTop || !darkBottom) ds->menuLitFrames++;
    else if (ds->keepStartMenu && ds->menuLitFrames > 0) pk_nds_field_keep_start_menu(ds->field, mainRead32, mainWrite32);
    if ((bits & PK_NDS_FIELD_TEXT) && !pk_nds_bg3_tiles(ds->nds, ds->field->textBg ? ds->field->textBg : 3)) bits &= ~PK_NDS_FIELD_TEXT;
    if ((bits & PK_NDS_FIELD_BATTLE) && pk_nds_battle_command(ds->field, mainRead32) == pk_nds_battle_selecting(ds->field))
        bits |= PK_NDS_FIELD_BATTLE_INPUT;
    return bits;
}

int pk_ndsm_bgm(void)
{
    if (!ds || !ds->field || !ds->field->currentBgm) return -1;
    // Gen 5: a stack of songs (a battle's over the field's), the playing one at its depth.
    u32 depth = ds->field->bgmDepth ? mainRead32(ds->field->bgmDepth) : 0;
    if (depth > 7) return -1;
    return (int) (mainRead32(ds->field->currentBgm + 0x1C * depth) & 0xFFFF);
}

int pk_ndsm_bgm_state(void)
{
    return ds && ds->field ? pk_nds_bgm_settle(ds->field, mainRead32) : 0x7F00;
}

bool pk_ndsm_music_start(void)
{
    if (!ds) return false;
    // (Copied at the DS's own clock, as a state is: the copy runs at it.)
    setArm9Shift(1);
    bool ok = pk_nds_music_begin(ds->nds, ds->sampleRate);
    setArm9Shift(boostShift());
    return ok;
}

int pk_ndsm_music_read(int16_t* out, int maxFrames) { return ds ? pk_nds_music_frame(out, maxFrames) : 0; }

void pk_ndsm_music_fade_out(int frames) { pk_nds_music_fade(frames); }

void pk_ndsm_music_stop(void) { pk_nds_music_end(); }

int pk_ndsm_action(int action, const uint32_t args[4])
{
    return ds ? pk_nds_call_action(ds->nds, ds->field, action, args) : 0;
}

bool pk_ndsm_action_results(int seq, uint32_t* out)
{
    return pk_nds_call_results(seq, out);
}

int pk_ndsm_action_status(int seq)
{
    return pk_nds_call_status(seq);
}

void pk_ndsm_set_wild_off(bool off)
{
    if (ds) ds->wildOff = off;
}

void pk_ndsm_set_keep_start_menu(bool on)
{
    if (ds) ds->keepStartMenu = on;
}

void pk_ndsm_set_cpu_boost(bool on)
{
    if (!ds || ds->cpuBoost == on) return;
    ds->cpuBoost = on;
    setArm9Shift(boostShift());
}

void pk_nds_set_wild_rates(const int32_t* triples, int n) { pk_nds_field_set_rates(triples, n); }

int pk_ndsm_set_cheats(const uint32_t* words, int nWords, const int32_t* lengths, int nCheats)
{
    if (!ds) return -1;
    std::vector<ARCode> codes;
    int at = 0;
    for (int i = 0; i < nCheats; i++) {
        int len = lengths[i];
        if (len <= 0 || (len & 1) || at + len > nWords) break;   // whole lines only (Cheats.kt never sends less)
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

void pk_ndsm_set_draw(bool draw, bool drawNext)
{
    if (!ds) return;
    ds->draw = draw;
    ds->drawNext = drawNext;
}

bool pk_ndsm_frame_drawn(void) { return ds && ds->drawn; }

void pk_ndsm_wide_enable(bool on)
{
    if (!ds || ds->wide == on) return;
    ds->wide = on;
    pk_nds_wide_set(ds->nds, on);
}

bool pk_ndsm_wide_draw(uint32_t* out, int x, int y, int w, int h)
{
    if (!ds || !ds->wide) return false;
    pk_nds_wide_view(out, x, y, w, h);
    return true;
}

void pk_ndsm_wide_window(int x, int y, int w, int h)
{
    pk_nds_wide_need(-x, x + w - PK_NDS_W, -y, y + h - PK_NDS_H);
    if (!ds) return;
    ds->winX = x;
    ds->winY = y;
    ds->winW = w;
    ds->winH = h;
}

void pk_ndsm_set_scale(int scale)
{
    if (!ds) return;
    ds->scaleWant = scale;
    ds->scalePending = true;
}

int pk_ndsm_scale(void) { return ds ? pk_nds_gl_scale() : 1; }

int pk_nds_gpu_supported(void) { return pk_nds_gl_supported(); }

bool pk_ndsm_presented(void) { return ds && ds->presented; }

bool pk_ndsm_gpu_frame_info(int64_t timestamp, int32_t out[6]) { return pk_nds_gl_frame_info(timestamp, out); }

void pk_nds_set_window(ANativeWindow* window) { pk_nds_gl_set_window(window); }

int pk_ndsm_wide_scene(void)
{
    // WideCamera.SCENE_FIELD 0, _TEXT 1, _OFF 4, _FADE 5, _MENU 6
    int b = ds ? ds->fieldBits : 0;
    if (b & PK_NDS_FIELD_BATTLE) return 4;
    // The top screen faded all the way to black or white (master brightness, mode 1 / 2 at 16): between two maps (a
    // door, a warp - the field process stops meanwhile) or two screens. Decides nothing: the zoom holds through it.
    u32 mb = ds ? ds->nds->GPU.GPU2D_A.MasterBrightness : 0;
    if ((mb >> 14) == 1 || (mb >> 14) == 2) {
        if ((mb & 0x1F) >= 16) return 5;
    }
    if (b & PK_NDS_FIELD_MENU) return 6;
    if (!(b & PK_NDS_FIELD_SCREEN)) return 4;
    return (b & PK_NDS_FIELD_TEXT) ? 1 : 0;
}

static int fadeFactor(u16 mb)
{
    const int mode = mb >> 14, k = mb & 0x1F;
    return (mode == 1 || mode == 2) ? (k > 16 ? 16 : k) : 0;
}

int pk_ndsm_fade(void)
{
    if (!ds) return 0;
    return fadeFactor(ds->nds->GPU.GPU2D_A.MasterBrightness) | (fadeFactor(ds->nds->GPU.GPU2D_B.MasterBrightness) << 8);
}

bool pk_ndsm_rom_code(char out[5])
{
    if (!ds) return false;
    auto* cart = ds->nds->NDSCartSlot.GetCart();
    if (!cart) return false;
    memcpy(out, cart->GetHeader().GameCode, 4);
    out[4] = 0;
    return true;
}

int pk_ndsm_rom_revision(void)
{
    auto* cart = ds ? ds->nds->NDSCartSlot.GetCart() : nullptr;
    return cart ? cart->GetHeader().ROMVersion : -1;
}

uint32_t pk_ndsm_rom_size(void)
{
    auto* cart = ds ? ds->nds->NDSCartSlot.GetCart() : nullptr;
    return cart ? cart->GetROMLength() : 0;
}

const uint8_t* pk_ndsm_dtcm(void)
{
    return ds ? ds->nds->ARM9.DTCM : nullptr;
}

} // extern "C"
