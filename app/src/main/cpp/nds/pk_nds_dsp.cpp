// The app's second Nintendo DS core: DSperate (third_party/dsperate, GPL-3.0-or-later), as pk_ndsd_* behind pk_nds.h
// (pk_nds_core.cpp hands it the calls while it runs; pk_nds_cores.h). For devices too slow for melonDS: its
// recompilers (AArch64, and ARMv7, where melonDS has none), fast memory, the 3D on worker threads and idle-loop
// skipping. See pk_nds.h for who calls what.
//
// What it does as melonDS does (pk_nds.cpp): the screens as RGBA, the audio resampled to the engine's rate, the save
// written once the game's writes have been quiet for SAVE_QUIET_FRAMES, states behind our header carrying the save's
// CRC32 (pk_nds_state_matches_save), the field read (pk_nds_field.cpp, RAM only, as on the Linux port). What it
// doesn't have yet, all melonDS internals: EXPAND (pk_nds_wide), DS RESOLUTION (pk_nds_gl), the game's own functions
// (pk_nds_call: PC / FLY), STEADY music's second core (pk_nds_music), DS CPU BOOST - those calls do nothing here and
// report off; the settings grey them out.
//
// States are DSperate's own format and never cross cores: a melonDS state isn't loaded here (2 from
// pk_nds_state_matches_save, so a resume boots from the save file instead), nor this core's there. A state is loaded
// into a second console first: DSperate leaves a machine unusable after a failed load, and the running game mustn't be.

#include "dsperate_android.h"   // first: bionic's PAGE_SIZE macro would break DSperate's headers

#include "pk_nds_cores.h"
#include "pk_nds_field.h"

#include "core/nds.h"
#include "core/state/state.h"
#include "core/cpu/jit/jit.h"

#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include <zlib.h>

#define LOG_TAG "pokedaisy/nds"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if defined(__ANDROID__) && __ANDROID_API__ < 28
// dsperate_android.h: DSperate's buffers come from aligned_alloc (API 28) and go back through free().
extern "C" __attribute__((visibility("hidden"))) void* aligned_alloc(size_t alignment, size_t size)
{
    void* p = nullptr;
    if (alignment < sizeof(void*)) alignment = sizeof(void*);
    return posix_memalign(&p, alignment, size) == 0 ? p : nullptr;
}
#endif

using ds::u8;
using ds::u16;
using ds::u32;
using ds::s16;

namespace {

constexpr int SAVE_QUIET_FRAMES = 30;
constexpr u32 STATE_MAGIC = PK_NDS_STATE_MAGIC_DSPERATE;
constexpr u32 STATE_VERSION = 1;
constexpr u32 ROM_WINDOW = 0x08000000; // pk_nds_read: the cart's ROM file from here
constexpr u32 MAIN_RAM = 0x02000000;

struct Ds {
    std::unique_ptr<ds::NDS> nds;
    bool jit = false;                        // the recompiler is attached to nds
    std::string romPath, savePath;
    bool saveReadOnly = false;
    u32 saveWrites = 0;                      // the chip's write count when last seen (Cart::sram_writes)
    int saveQuiet = 0;                       // frames since the game last wrote its save
    u32 keys = 0;
    u32 top[PK_NDS_W * PK_NDS_H] {};
    u32 bottom[PK_NDS_W * PK_NDS_H] {};
    // Audio: DSperate's mixer runs at ~32728 Hz (its output_rate_hz); the engine wants [rate] a second.
    std::vector<s16> in;                     // stereo frames taken from the SPU, not yet resampled
    std::vector<s16> audio;                  // the last frame's, resampled
    int audioFrames = 0;
    double rate = 48000;                     // output frames per emulated second (pk_nds_set_audio_rate)
    double pos = 0;                          // the next output frame's place in `in`, in input frames
    const PkNdsFieldGame* field = nullptr;   // the field read's table entry for this cart, if any
    int fieldBits = 0;
    bool wildOff = false;
    bool keepStartMenu = false;
    int menuLitFrames = 0;
    bool draw = true, drawNext = true;       // pk_nds_set_draw
    bool drawn = true;                       // pk_nds_frame_drawn
    std::vector<ds::cheat::Code> cheats;     // what pk_nds_set_cheats gave (a loaded state's console gets them too)
};

Ds* d = nullptr;
// The stylus, from any thread: bit 31 = down, x in bits 8-15, y in bits 0-7 (as pk_nds.cpp).
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

u32 crcOf(const u8* data, size_t len) { return (u32) crc32(0, data, (uInt) len); }

// A console with the ROM in, booted straight into the game (FreeBIOS, generated firmware: no BIOS / firmware files).
std::unique_ptr<ds::NDS> boot(const std::string& rom)
{
    auto nds = std::make_unique<ds::NDS>();
    std::string err;
    if (!nds->load_bios("", "", "", {}, &err)) {
        LOGE("DSperate: no BIOS (%s)", err.c_str());
        return nullptr;
    }
    if (!nds->load_rom(rom)) {
        LOGE("DSperate: can't load the ROM (%s)", nds->rom_error.c_str());
        return nullptr;
    }
    nds->setup_direct_boot();
    nds->io.start_rtc_clock();   // the device's clock (the games' day / night, berries)
    return nds;
}

bool attachJit(ds::NDS& nds)
{
#if DSPERATE_JIT
    if (ds::jit::attach(nds, true, true)) return true;
    LOGE("DSperate: the recompiler didn't attach; interpreting");
#endif
    return false;
}

void detachJit()
{
#if DSPERATE_JIT
    if (d->jit) ds::jit::detach(*d->nds);
#endif
    d->jit = false;
}

// DSperate draws 0xAARRGGBB words; the screen (EmulatorView) uploads RGBA bytes, i.e. 0xAABBGGRR words.
void convert(const u32* src, u32* dst)
{
    for (int i = 0; i < PK_NDS_W * PK_NDS_H; i++) {
        u32 p = src[i];
        dst[i] = 0xFF000000u | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
    }
}

bool writeSaveFile()
{
    auto& s = d->nds->cart->sram();
    if (d->savePath.empty() || d->saveReadOnly || s.empty()) return false;
    FILE* f = fopen(d->savePath.c_str(), "wb");
    if (!f) {
        LOGE("can't write the save %s", d->savePath.c_str());
        return false;
    }
    bool ok = fwrite(s.data(), 1, s.size(), f) == s.size();
    ok = fflush(f) == 0 && ok;
    fclose(f);
    return ok;
}

// The SPU's frames since the last call, resampled (linearly) to d->rate a second into d->audio.
void takeAudio()
{
    auto& spu = d->nds->spu;
    s16 chunk[2048];
    size_t n;
    while ((n = spu.take(chunk, 1024)) > 0) d->in.insert(d->in.end(), chunk, chunk + n * 2);
    const size_t have = d->in.size() / 2;
    const double step = spu.output_rate_hz() / d->rate;
    int out = 0;
    const int cap = (int) d->audio.size() / 2;
    while (out < cap && d->pos + 1 < (double) have) {
        size_t i = (size_t) d->pos;
        double f = d->pos - (double) i;
        for (int c = 0; c < 2; c++) {
            double a = d->in[i * 2 + c], b = d->in[(i + 1) * 2 + c];
            d->audio[out * 2 + c] = (s16) std::lround(a + (b - a) * f);
        }
        out++;
        d->pos += step;
    }
    // Keep from the frame the next output frame starts at (it and the one after are what it mixes).
    size_t used = std::min((size_t) d->pos, have);
    d->in.erase(d->in.begin(), d->in.begin() + (ptrdiff_t) (used * 2));
    d->pos -= (double) used;
    d->audioFrames = out;
}

u32 mainRead32(u32 addr)
{
    if ((addr & 0xFF000000u) != MAIN_RAM) return 0;
    const u8* m = d->nds->bus.main_ram.get();
    const u32 mask = d->nds->bus.main_ram_size() - 1;
    return (u32) m[addr & mask] | ((u32) m[(addr + 1) & mask] << 8) | ((u32) m[(addr + 2) & mask] << 16) |
        ((u32) m[(addr + 3) & mask] << 24);
}

void mainWrite32(u32 addr, u32 v)
{
    if ((addr & 0xFF000000u) != MAIN_RAM) return;
    u8* m = d->nds->bus.main_ram.get();
    const u32 mask = d->nds->bus.main_ram_size() - 1;
    for (int i = 0; i < 4; i++) m[(addr + i) & mask] = (u8) (v >> (8 * i));
}

// A screen of one colour (pk_nds.cpp's oneColour): black between scenes, or a top screen with nothing on it.
bool oneColour(const u32* px)
{
    const u32 first = px[0] & 0xFFFFFF;
    for (int gy = 0; gy < 12; gy++)
        for (int gx = 0; gx < 16; gx++)
            if ((px[(gy * PK_NDS_H / 12 + PK_NDS_H / 24) * PK_NDS_W + gx * PK_NDS_W / 16 + PK_NDS_W / 32] & 0xFFFFFF) != first) return false;
    return true;
}

// pk_nds_field_gpu.cpp's pk_nds_bg3_tiles on DSperate: the tiles engine A's BG [bg] has on screen (0 = off or none).
int bgTiles(int bg)
{
    ds::NDS& n = *d->nds;
    const u32 disp = n.gpu.engine[0].dispcnt();
    if (!(disp & (1u << (8 + bg)))) return 0;
    if (bg == 3 && (disp & 7) > 1) return 0;
    if (bg == 2 && (disp & 7) > 3) return 0;
    const u16 cnt = (u16) n.gpu.reg_read(0x04000008u + (u32) bg * 2, 16);
    const u32 base = 0x06000000u + ((disp >> 27) & 7) * 0x10000 + ((cnt >> 8) & 0x1F) * 0x800;
    int count = 0;
    for (u32 i = 0; i < 32 * 24; i++)
        if (n.bus.dma_read16(ds::Cpu::ARM9, base + i * 2) & 0x3FF) count++;
    return count;
}

int fadeFactor(u16 mb)
{
    const int mode = mb >> 14, k = mb & 0x1F;
    return (mode == 1 || mode == 2) ? (k > 16 ? 16 : k) : 0;
}

void setCheats(ds::NDS& nds)
{
    nds.cheats.codes = d->cheats;
    nds.cheats.forget_writes();
}

} // namespace

extern "C" {

bool pk_ndsd_init(const char* romPath, const char* savePath, int sampleRate)
{
    pk_ndsd_deinit();
    // Fast memory maps the guest in 4 KB views; a 16 KB-page kernel can't: the recompiler goes through the page table.
    if (sysconf(_SC_PAGESIZE) != 4096) setenv("DS_FASTMEM", "0", 0);
    d = new Ds();
    d->romPath = romPath;
    d->rate = sampleRate > 0 ? sampleRate : 48000;
    d->nds = boot(d->romPath);
    if (!d->nds) {
        pk_ndsd_deinit();
        return false;
    }
    if (savePath) {
        d->savePath = savePath;
        std::vector<u8> save;
        readFile(savePath, save);
        FILE* probe = fopen(savePath, save.empty() ? "ab" : "r+b");
        d->saveReadOnly = probe == nullptr;
        if (probe) fclose(probe);
        if (d->saveReadOnly) LOGI("save opened read-only (new saves won't persist): %s", savePath);
        if (!save.empty() && !d->nds->cart->load_save(save.data(), save.size()).fitted) {
            // Not the size of this game's save chip: the file is left alone (a write would cut it to the chip's size).
            LOGE("DSperate: the save isn't this game's save size (%zu bytes); not starting, so it isn't overwritten",
                save.size());
            pk_ndsd_deinit();
            return false;
        }
        d->nds->cart->clear_sram_dirty();
    }
    d->saveWrites = d->nds->cart->sram_writes();
    d->jit = attachJit(*d->nds);
    d->audio.resize(8192 * 2);
    {
        // The field read's table (pk_nds_field.cpp), as on melonDS: by the header, and a hack by a word of its code
        // (Origin HeartGold's, 0x2B73C in) - the cart's first 256 KB is all it reads.
        const u32 len = std::min<u32>(d->nds->cart->rom_size(), 0x40000);
        std::vector<u8> head(len);
        d->nds->cart->rom_read(0, head.data(), len);
        d->field = pk_nds_field_game_rom(head.data(), len);
    }
    LOGI("DSperate up: save %zu bytes, %d Hz, JIT %s", d->nds->cart->sram().size(), sampleRate, d->jit ? "on" : "off");
    return true;
}

void pk_ndsd_deinit(void)
{
    if (!d) return;
    if (d->nds) {
        pk_ndsd_flush_save();
        detachJit();
        d->nds.reset();
    }
    delete d;
    d = nullptr;
    touch.store(0);
}

bool pk_ndsd_active(void) { return d != nullptr; }

void pk_ndsd_set_keys(uint32_t keys)
{
    if (d) d->keys = keys & 0xFFF;
}

void pk_ndsd_touch(bool down, int x, int y)
{
    u32 cx = x < 0 ? 0 : x > PK_NDS_W - 1 ? PK_NDS_W - 1 : x;
    u32 cy = y < 0 ? 0 : y > PK_NDS_H - 1 ? PK_NDS_H - 1 : y;
    touch.store((down ? 0x80000000u : 0u) | (cx << 8) | cy);
}

void pk_ndsd_run_frame(void)
{
    if (!d) return;
    ds::NDS& n = *d->nds;
    // DSperate's frameskip is decided a frame ahead (at line 215) as drawNext is; the frame now starting was decided
    // by the last one. A frame it skips can't be shown, so the next one is drawn in its place (as pk_nds.cpp does).
    const bool skipped = n.gpu.frame_begun() && n.gpu.will_skip_frame();
    const bool draw = d->draw && !skipped;
    const bool drawNext = d->drawNext || (d->draw && !draw);
    n.gpu.set_frame_skip(!drawNext);
    n.io.set_buttons(d->keys);
    const u32 t = touch.load();
    n.io.set_touch((int) ((t >> 8) & 0xFF), (int) (t & 0xFF), (t & 0x80000000u) != 0);
    n.run_frame();
    d->drawn = draw;
    if (draw) {
        convert(n.gpu.framebuffer(0), d->top);
        convert(n.gpu.framebuffer(1), d->bottom);
    }
    takeAudio();
    d->fieldBits = pk_ndsd_field_bits();
    const u32 writes = n.cart->sram_writes();
    if (writes != d->saveWrites) {
        d->saveWrites = writes;
        d->saveQuiet = 0;
    } else if (n.cart->sram_dirty() && ++d->saveQuiet >= SAVE_QUIET_FRAMES) {
        pk_ndsd_flush_save();
    }
}

uint32_t* pk_ndsd_top(void) { return d ? d->top : nullptr; }
uint32_t* pk_ndsd_bottom(void) { return d ? d->bottom : nullptr; }

int pk_ndsd_read_audio(int16_t* out, int maxFrames)
{
    if (!d) return 0;
    int n = std::min(d->audioFrames, maxFrames);
    memcpy(out, d->audio.data(), (size_t) n * 2 * sizeof(s16));
    d->audioFrames = 0;
    return n;
}

void pk_ndsd_set_audio_rate(double rate)
{
    if (d && rate > 0) d->rate = rate;
}

bool pk_ndsd_flush_save(void)
{
    if (!d || !d->nds->cart->sram_dirty()) return false;
    d->nds->cart->clear_sram_dirty();
    writeSaveFile();
    return true;
}

int pk_ndsd_finish_save(int maxFrames)
{
    if (!d) return 0;
    int n = 0;
    u32 keys = d->keys;
    u32 t = touch.exchange(0);
    d->keys = 0;
    while (d->nds->cart->sram_dirty() && n < maxFrames) {
        pk_ndsd_run_frame();
        n++;
    }
    pk_ndsd_flush_save();
    d->keys = keys;
    touch.store(t);
    return n;
}

bool pk_ndsd_save_state(const char* path)
{
    if (!d) return false;
    ds::state::Writer w;
    std::string err;
    if (!d->nds->save_state(w, err)) {
        LOGE("DSperate: savestate failed: %s", err.c_str());
        return false;
    }
    const auto& save = d->nds->cart->sram();
    u32 header[4] = {STATE_MAGIC, STATE_VERSION, save.empty() ? 0 : crcOf(save.data(), save.size()), (u32) save.size()};
    FILE* f = fopen(path, "wb");
    if (!f) return false;
    const auto& data = w.data();
    bool ok = fwrite(header, sizeof(header), 1, f) == 1 && fwrite(data.data(), 1, data.size(), f) == data.size();
    ok = fclose(f) == 0 && ok;
    return ok;
}

bool pk_ndsd_load_state(const char* path)
{
    if (!d) return false;
    std::vector<u8> data;
    if (!readFile(path, data) || data.size() < 16) return false;
    u32 header[4];
    memcpy(header, data.data(), sizeof(header));
    if (header[0] != STATE_MAGIC) {
        LOGE("loadstate: not a DSperate state (%08x): %s", header[0], path);
        return false;
    }
    // Into a second console: a failed load leaves its machine unusable, and the running game stays as it was.
    auto next = boot(d->romPath);
    if (!next) return false;
    ds::state::Reader r(data.data() + 16, data.size() - 16);
    std::string err;
    if (!next->load_state(r, err)) {
        LOGE("loadstate failed: %s (%s)", path, err.c_str());
        return false;
    }
    next->io.start_rtc_clock();   // the device's time again, not the state's
    setCheats(*next);
    detachJit();
    d->nds = std::move(next);
    d->jit = attachJit(*d->nds);
    d->in.clear();
    d->pos = 0;
    // The state put its save memory back: the file follows, as on melonDS and the GBA (the resume only loads one
    // whose save matches the file: pk_nds_state_matches_save).
    d->saveWrites = d->nds->cart->sram_writes();
    if (!d->nds->cart->sram().empty()) writeSaveFile();
    d->nds->cart->clear_sram_dirty();
    d->saveQuiet = 0;
    return true;
}

int pk_ndsd_state_matches_save(const char* path)
{
    if (!d) return -1;
    FILE* f = fopen(path, "rb");
    if (!f) return -1;
    u32 header[4];
    bool ok = fread(header, sizeof(header), 1, f) == 1;
    fclose(f);
    if (ok && header[0] == PK_NDS_STATE_MAGIC_MELONDS) return 2;   // melonDS's: never loaded here
    if (!ok || header[0] != STATE_MAGIC || header[3] == 0) return -1;
    if (d->savePath.empty() || d->saveReadOnly) return 1;
    std::vector<u8> file;
    readFile(d->savePath.c_str(), file);
    file.resize(header[3], 0xFF);   // flash the file hasn't got yet reads erased
    return crcOf(file.data(), file.size()) == header[2] ? 1 : 0;
}

void pk_ndsd_read(uint32_t addr, uint8_t* dst, int len)
{
    if (!d) {
        memset(dst, 0, (size_t) len);
        return;
    }
    ds::NDS& n = *d->nds;
    if (addr >= ROM_WINDOW) {
        const u32 off = addr - ROM_WINDOW;
        n.cart->rom_read(off, dst, (u32) len);   // 0xFF past the end, as melonDS's read gives
        return;
    }
    if ((addr & 0xFF000000u) == MAIN_RAM) {
        const u8* m = n.bus.main_ram.get();
        const u32 mask = n.bus.main_ram_size() - 1;
        int done = 0;
        while (done < len) {
            u32 off = (addr + (u32) done) & mask;
            int k = (int) std::min<u32>(mask + 1 - off, (u32) (len - done));
            memcpy(dst + done, m + off, (size_t) k);
            done += k;
        }
        return;
    }
    for (int i = 0; i < len; i++) dst[i] = n.bus.dma_read8(ds::Cpu::ARM9, addr + (u32) i);
}

bool pk_ndsd_write(uint32_t addr, const uint8_t* src, int len)
{
    if (!d || (addr & 0xFF000000u) != MAIN_RAM || len <= 0) return false;
    u8* m = d->nds->bus.main_ram.get();
    const u32 mask = d->nds->bus.main_ram_size() - 1;
    for (int i = 0; i < len; i++) m[(addr + (u32) i) & mask] = src[i];
    return true;
}

int pk_ndsd_field_bits(void)
{
    if (!d || !d->field) return 0;
    ds::NDS& n = *d->nds;
    int bits = pk_nds_field_state(d->field, mainRead32, mainWrite32, d->wildOff);
    bits |= pk_nds_field_screen_touch(d->field, bits, n.gpu.engine[0].dispcnt());
    const bool darkTop = oneColour(d->top), darkBottom = oneColour(d->bottom);
    if (darkTop && !darkBottom) bits |= PK_NDS_FIELD_TOUCH;
    if (darkTop) bits |= PK_NDS_FIELD_DARK_TOP;
    if (darkBottom) bits |= PK_NDS_FIELD_DARK_BOTTOM;
    if (!(bits & PK_NDS_FIELD_MENU)) d->menuLitFrames = 0;
    else if (!darkTop || !darkBottom) d->menuLitFrames++;
    else if (d->keepStartMenu && d->menuLitFrames > 0) pk_nds_field_keep_start_menu(d->field, mainRead32, mainWrite32);
    if ((bits & PK_NDS_FIELD_TEXT) && !bgTiles(d->field->textBg ? d->field->textBg : 3)) bits &= ~PK_NDS_FIELD_TEXT;
    if ((bits & PK_NDS_FIELD_BATTLE) && pk_nds_battle_command(d->field, mainRead32) == pk_nds_battle_selecting(d->field))
        bits |= PK_NDS_FIELD_BATTLE_INPUT;
    return bits;
}

// The game's own functions (pk_nds_call: PC / FLY) run inside melonDS: none here (0 = the field isn't free).
int pk_ndsd_action(int, const uint32_t[4]) { return 0; }
int pk_ndsd_action_status(int) { return -1; }
bool pk_ndsd_action_results(int, uint32_t*) { return false; }

void pk_ndsd_set_wild_off(bool off)
{
    if (d) d->wildOff = off;
}

void pk_ndsd_set_keep_start_menu(bool on)
{
    if (d) d->keepStartMenu = on;
}

void pk_ndsd_set_cpu_boost(bool) {}

int pk_ndsd_set_cheats(const uint32_t* words, int nWords, const int32_t* lengths, int nCheats)
{
    if (!d) return -1;
    std::vector<ds::cheat::Code> codes;
    int at = 0;
    for (int i = 0; i < nCheats; i++) {
        int len = lengths[i];
        if (len <= 0 || (len & 1) || at + len > nWords) break;   // whole lines only (Cheats.kt never sends less)
        ds::cheat::Code c;
        c.name = std::to_string(i);
        c.enabled = true;
        c.words.assign(words + at, words + at + len);
        at += len;
        codes.push_back(std::move(c));
    }
    d->cheats = std::move(codes);
    setCheats(*d->nds);
    return (int) d->cheats.size();
}

// STEADY music copies melonDS's state into a second melonDS: none here (-1 = the song isn't readable, so FF plays
// SPED-UP).
int pk_ndsd_bgm(void) { return -1; }
int pk_ndsd_bgm_state(void) { return 0x7F00; }
bool pk_ndsd_music_start(void) { return false; }
int pk_ndsd_music_read(int16_t*, int) { return 0; }
void pk_ndsd_music_fade_out(int) {}
void pk_ndsd_music_stop(void) {}

// EXPAND draws melonDS's polygons past the screen: none here.
void pk_ndsd_wide_enable(bool) {}
bool pk_ndsd_wide_draw(uint32_t*, int, int, int, int) { return false; }
void pk_ndsd_wide_window(int, int, int, int) {}

void pk_ndsd_set_draw(bool draw, bool drawNext)
{
    if (!d) return;
    d->draw = draw;
    d->drawNext = drawNext;
}

bool pk_ndsd_frame_drawn(void) { return d && d->drawn; }

int pk_ndsd_wide_scene(void)
{
    // As pk_nds.cpp's (WideCamera.SCENE_*): the HUD and the hold read it with EXPAND off too.
    int b = d ? d->fieldBits : 0;
    if (b & PK_NDS_FIELD_BATTLE) return 4;
    u16 mb = d ? d->nds->gpu.engine[0].master_bright() : 0;
    if (((mb >> 14) == 1 || (mb >> 14) == 2) && (mb & 0x1F) >= 16) return 5;
    if (b & PK_NDS_FIELD_MENU) return 6;
    if (!(b & PK_NDS_FIELD_SCREEN)) return 4;
    return (b & PK_NDS_FIELD_TEXT) ? 1 : 0;
}

int pk_ndsd_fade(void)
{
    if (!d) return 0;
    return fadeFactor(d->nds->gpu.engine[0].master_bright()) | (fadeFactor(d->nds->gpu.engine[1].master_bright()) << 8);
}

// DS RESOLUTION is melonDS's OpenGL renderer: always the DS's own here.
void pk_ndsd_set_scale(int) {}
int pk_ndsd_scale(void) { return 1; }
bool pk_ndsd_presented(void) { return false; }
bool pk_ndsd_gpu_frame_info(int64_t, int32_t[6]) { return false; }

bool pk_ndsd_rom_code(char out[5])
{
    if (!d) return false;
    memcpy(out, d->nds->cart->header().game_code, 4);
    out[4] = 0;
    return true;
}

int pk_ndsd_rom_revision(void) { return d ? d->nds->cart->header().rom_version : -1; }

uint32_t pk_ndsd_rom_size(void) { return d ? d->nds->cart->rom_size() : 0; }

const uint8_t* pk_ndsd_dtcm(void) { return d ? d->nds->bus.dtcm.get() : nullptr; }

} // extern "C"
