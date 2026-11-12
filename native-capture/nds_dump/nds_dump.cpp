// nds_dump: headless melonDS for DS test fixtures, mgba_dump's twin (see README.md here).
//
//   nds_dump ROM.nds SAVE.sav [--jit] < script.txt
//
// Boots ROM with melonDS's FreeBIOS and generated firmware (no BIOS / firmware files), reading SAVE
// when it exists and writing every save the game makes back to it - so give it a copy, never a real
// save. Then runs the stdin script (README.md lists the commands).

#include <NDS.h>
#include <NDSCart.h>
#include <GPU.h>
#include <Savestate.h>
#include <Args.h>
#include <AREngine.h>
#include <GPU3D_Soft.h>

#include "pk_nds_platform.h"
#include "pk_nds_field.h"
#include "pk_nds_call.h"
#include "pk_nds_wide.h"
#include "pk_nds_music.h"
#ifdef NDS_DUMP_GL
#include "pk_nds_gl.h"
#endif

#include <zlib.h>

#include <chrono>
#include <ctime>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

using namespace melonDS;

static std::string savePath;
static NDS* nds = nullptr;

// watch LO HI [r|w|rw] [N]: the ARM9's own reads / writes of main RAM in [LO, HI) reported with its registers (the
// interpreter only: the JIT's fast paths skip the bus). How the Gen 5 addresses were traced back to code.
struct Watch { u32 lo, hi; bool r, w; int left; };
static std::vector<Watch> watches;
static bool inFrame = false;
static u64 frameCount_();
class WatchNDS : public NDS {
public:
    using NDS::NDS;
    void hit(u32 addr, u32 v, int size, bool write)
    {
        if (!inFrame) return;
        for (Watch& w : watches) {
            if (addr + size <= w.lo || addr >= w.hi || !(write ? w.w : w.r) || w.left == 0) continue;
            if (w.left > 0) w.left--;
            const u32* R = ARM9.R;
            printf("watch f%llu %s%d %08X = %08X  pc %08X lr %08X r0 %08X r1 %08X r2 %08X r3 %08X r4 %08X r5 %08X sp %08X%s\n",
                (unsigned long long) frameCount_(), write ? "W" : "R", size * 8, addr, v, R[15], R[14], R[0], R[1], R[2],
                R[3], R[4], R[5], R[13], ARM9.CPSR & 0x20 ? " T" : "");
            // WATCH_STACK=N: N words from the stack too (return addresses: the callers)
            static int stackWords = getenv("WATCH_STACK") ? atoi(getenv("WATCH_STACK")) : 0;
            if (stackWords > 0) {
                printf("    stack:");
                for (int i = 0; i < stackWords; i++) {
                    u32 sp = R[13] + 4 * i, v = 0;
                    if ((sp & ARM9.DTCMMask) == ARM9.DTCMBase) memcpy(&v, &ARM9.DTCM[sp & (DTCMPhysicalSize - 1)], 4);
                    else if ((sp & 0xFF000000u) == 0x02000000u) memcpy(&v, &MainRAM[sp & MainRAMMask], 4);
                    printf(" %08X", v);
                }
                printf("\n");
            }
            fflush(stdout);
        }
    }
    void ARM9Write8(u32 a, u8 v) override { if (!watches.empty()) hit(a, v, 1, true); NDS::ARM9Write8(a, v); }
    void ARM9Write16(u32 a, u16 v) override { if (!watches.empty()) hit(a, v, 2, true); NDS::ARM9Write16(a, v); }
    void ARM9Write32(u32 a, u32 v) override { if (!watches.empty()) hit(a, v, 4, true); NDS::ARM9Write32(a, v); }
    u32 ARM9Read32(u32 a) override
    {
        u32 v = NDS::ARM9Read32(a);
        if (!watches.empty()) hit(a, v, 4, false);
        return v;
    }
    u16 ARM9Read16(u32 a) override
    {
        u16 v = NDS::ARM9Read16(a);
        if (!watches.empty()) hit(a, v, 2, false);
        return v;
    }
    u8 ARM9Read8(u32 a) override
    {
        u8 v = NDS::ARM9Read8(a);
        if (!watches.empty()) hit(a, v, 1, false);
        return v;
    }
};
static u32 heldKeys = 0;     // pressed keys, bit = melonDS key index (A 0 .. L 9, X 10, Y 11)
static bool touching = false;
static u16 touchX = 0, touchY = 0;
static u64 frameCount = 0;
static u64 frameCount_() { return frameCount; }
static int wideMargin = 0;   // wide MX [MY]: EXPAND drawn every frame (pk_nds_wide.cpp)
static int wideMarginY = 0;
// look DX DY: the window (wide's, at first) moved DX, DY DS pixels a frame, as the look-around moves it - set before
// each frame as the app sets it (pk_nds_wide_need), the GPU path composing that frame's window
static int lookX = 0, lookY = 0, lookDX = 0, lookDY = 0;
static bool wideDrawn = false;
// gpu N (NDS_DUMP_GL builds): DS RESOLUTION's GPU renderer at N, as pk_nds_run_frame drives it; the screens are then
// its 1x copies (RGBA) and gpushot writes the composed window.
static bool gpuOn = false;
static std::vector<u32> gpuTop(256 * 192), gpuBottom(256 * 192), gpuTopArgb(256 * 192);

static u32 rgbaToArgb(u32 p) { return 0xFF000000u | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF); }
static u32 read32(u32 addr) { return nds->ARM9Read32(addr); }
static void write32(u32 addr, u32 v) { nds->ARM9Write32(addr, v); }
static int wildOff = 0;          // wildoff 1: WILD ENCOUNTERS OFF, the field read run every frame (pk_nds_field.cpp)
static int battlesSeen = 0;      // battles started since wildoff (the field read's BATTLE bit going on)

static void onSave(PkNdsHost*, const uint8_t* data, uint32_t len, uint32_t, uint32_t)
{
    FILE* f = fopen(savePath.c_str(), "wb");
    if (!f) { fprintf(stderr, "can't write %s\n", savePath.c_str()); return; }
    fwrite(data, 1, len, f);
    fclose(f);
}

static void onStop(PkNdsHost*, int reason) { fprintf(stderr, "DS stopped (reason %d)\n", reason); }

static void logLine(int level, const char* msg)
{
    if (level >= 2 || getenv("NDS_DUMP_VERBOSE")) fprintf(stderr, "%s", msg);
}

static std::vector<u8> readFile(const char* path)
{
    std::vector<u8> out;
    FILE* f = fopen(path, "rb");
    if (!f) return out;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    out.resize(n);
    if (fread(out.data(), 1, n, f) != (size_t) n) out.clear();
    fclose(f);
    return out;
}

static bool writeFile(const std::string& path, const void* data, size_t len)
{
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) { fprintf(stderr, "can't write %s\n", path.c_str()); return false; }
    fwrite(data, 1, len, f);
    fclose(f);
    return true;
}

// --- PNG (RGB, filter 0 rows, one zlib stream) ---

static void put32(std::vector<u8>& v, u32 x)
{
    v.push_back(x >> 24); v.push_back(x >> 16); v.push_back(x >> 8); v.push_back(x);
}

static void chunk(std::vector<u8>& png, const char* type, const std::vector<u8>& data)
{
    put32(png, (u32) data.size());
    size_t start = png.size();
    png.insert(png.end(), type, type + 4);
    png.insert(png.end(), data.begin(), data.end());
    put32(png, (u32) crc32(0, png.data() + start, (uInt) (png.size() - start)));
}

static bool writePng(const std::string& path, const u32* argb, int w, int h)
{
    std::vector<u8> raw;
    raw.reserve((size_t) (w * 3 + 1) * h);
    for (int y = 0; y < h; y++) {
        raw.push_back(0);
        for (int x = 0; x < w; x++) {
            u32 p = argb[y * w + x];
            raw.push_back(p >> 16); raw.push_back(p >> 8); raw.push_back(p);
        }
    }
    uLongf zlen = compressBound((uLong) raw.size());
    std::vector<u8> z(zlen);
    compress2(z.data(), &zlen, raw.data(), (uLong) raw.size(), 6);
    z.resize(zlen);
    std::vector<u8> png = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    std::vector<u8> ihdr;
    put32(ihdr, w); put32(ihdr, h);
    ihdr.insert(ihdr.end(), {8, 2, 0, 0, 0});
    chunk(png, "IHDR", ihdr);
    chunk(png, "IDAT", z);
    chunk(png, "IEND", {});
    return writeFile(path, png.data(), png.size());
}

// Both screens, top over bottom, 256x384.
static bool shot(const std::string& path)
{
    std::vector<u32> px(256 * 384);
    if (gpuOn) {
        for (int i = 0; i < 256 * 192; i++) {
            px[i] = rgbaToArgb(gpuTop[i]);
            px[256 * 192 + i] = rgbaToArgb(gpuBottom[i]);
        }
        return writePng(path, px.data(), 256, 384);
    }
    int fb = nds->GPU.FrontBuffer;
    for (int s = 0; s < 2; s++) {
        const u32* src = nds->GPU.Framebuffer[fb][s].get();
        if (src) memcpy(px.data() + s * 256 * 192, src, 256 * 192 * 4);
    }
    return writePng(path, px.data(), 256, 384);
}

// --- input ---

static u32 parseKeys(const char* s)
{
    static const char* names[] = {"A", "B", "SELECT", "START", "RIGHT", "LEFT", "UP", "DOWN", "R", "L", "X", "Y"};
    u32 bits = 0;
    std::string list(s);
    size_t pos = 0;
    while (pos <= list.size()) {
        size_t comma = list.find(',', pos);
        std::string key = list.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
        bool found = key == "NONE" || key.empty();
        for (int i = 0; i < 12; i++) {
            if (key == names[i]) { bits |= 1u << i; found = true; }
        }
        if (!found) fprintf(stderr, "unknown key %s\n", key.c_str());
        if (comma == std::string::npos) break;
        pos = comma + 1;
    }
    return bits;
}

// skipdraw N: as fast-forward at Nx does, only every Nth frame is drawn (GPU::SkipDraw on the others); 0 = all
static int skipDraw = 0;

// ffmode / ffgroup: the app's fast-forward pattern (EmulatorEngine's pkNdsSetDraw: GROUP frames a refresh, counted
// down, the last one shown); drawn frames written out (recordShown) to compare two runs of one script.
static int appGroup = 0, appPerTick = 0;
static bool drawGuard = true;   // ffguard 0: pk_nds_run_frame's guard off (a frame drawn though its 3D was skipped)
static int musicPerFrame = 0;   // musicon N: the STEADY copy's frames after each game frame
// steady G FILE: EmulatorEngine's STEADY for a DS game, G game frames a refresh: the song id read every frame, a copy
// begun once it has changed and its player has settled (pk_nds_bgm_settle) for DS_MUSIC_SETTLE_FRAMES, taken again when
// that player changes, a copy frame each refresh - its audio collected and written to FILE by `steady off`. With G 0 the
// game's own audio is collected instead.
static int steadyPer = -1, steadyGroup = 0, steadyBgm = -1, steadyWait = -1;
static bool steadyOn = false;
static int steadySince = 0;   // STEADY_DEBUG: the copy's state 1 and 30 frames after its begin
static std::string steadyFile;
static std::vector<s16> steadyPcm;
static void steadyFrame();
static std::string recordPrefix;
static u64 recordStart = 0;
static void recordShown();

static void runFrames(int n, u32 keys)
{
    for (int i = 0; i < n; i++) {
        // The frames the caller looks at (the last of each run) are always drawn.
        bool draw = !skipDraw || i == n - 1 || (frameCount + 1) % skipDraw == 0;
        bool drawNext = !skipDraw || i + 1 >= n - 1 || (frameCount + 2) % skipDraw == 0;
        if (appPerTick > 0) {
            // EmulatorEngine's pkNdsSetDraw, as written there
            draw = appGroup <= 1;
            drawNext = appGroup > 1 ? appGroup <= 2 : appPerTick <= 1;
            if (--appGroup <= 0) appGroup = appPerTick;
        }
        if (drawGuard) {
            // As pk_nds_run_frame: a frame whose 3D the last one skipped (drawNext false) isn't drawn either, the next
            // one is instead
            bool want = draw;
            draw = draw && !nds->GPU.SkippingFrame;
            drawNext = drawNext || (want && !draw);
        }
        nds->GPU.SkipDraw = !draw;
        nds->GPU.SkipDrawNext = !drawNext;
        if (wideMargin && (lookDX || lookDY)) {
            lookX += lookDX;
            lookY += lookDY;
            pk_nds_wide_need(wideMargin - lookX, wideMargin + lookX, wideMarginY - lookY, wideMarginY + lookY);
        }
        nds->SetKeyMask(~keys & 0xFFF);
        if (touching) nds->TouchScreen(touchX, touchY);
        else nds->ReleaseScreen();
#ifdef NDS_DUMP_GL
        if (gpuOn) pk_nds_gl_begin_frame();
#endif
        inFrame = true;
        nds->RunFrame();
        inFrame = false;
        frameCount++;
        static int wasBattle = 0;
        int battle = 0, inBattle = 0, battle3d = 0;
        if (auto* cart = nds->NDSCartSlot.GetCart()) {
            if (const PkNdsFieldGame* g = pk_nds_field_game_rom(cart->GetROM(), cart->GetROMLength())) {
                int bits = pk_nds_field_state(g, read32, write32, wildOff);
                pk_nds_call_frame(nds, g);
                battle = (bits & PK_NDS_FIELD_BATTLE) != 0;
                if (battle && !wasBattle) battlesSeen++;
                wasBattle = battle;
                inBattle = battle;
                if (!pk_nds_battle_margins(g)) battle = 0;   // as the app: no battle margins there
                battle3d = inBattle && pk_nds_battle_3d(g);   // ... but the overworld's (Black)
            }
        }
        // As the app: a battle's margins are its background and its 3D, everything else the overworld's 3D.
        if (wideMargin) {
            pk_nds_wide_gpu_want(true);
            wideDrawn = pk_nds_wide_frame(nds, gpuOn ? gpuTopArgb.data() : nds->GPU.Framebuffer[nds->GPU.FrontBuffer][0].get(),
                draw, (!battle && !inBattle) || battle3d, appPerTick > 0 ? drawNext : true, battle) || wideDrawn;
        }
#ifdef NDS_DUMP_GL
        if (gpuOn && draw) {
            // As pk_nds_run_frame: the window is EXPAND's whole view here
            pk_nds_gl_frame(nds, -wideMargin + lookX, -wideMarginY + lookY, 256 + 2 * wideMargin, 192 + 2 * wideMarginY,
                gpuTop.data(), gpuBottom.data());
            for (int k = 0; k < 256 * 192; k++) gpuTopArgb[k] = rgbaToArgb(gpuTop[k]);
        }
#endif
        if (!recordPrefix.empty() && draw) recordShown();
        if (musicPerFrame > 0) {
            static s16 musicBuf[4096];
            for (int k = 0; k < musicPerFrame; k++) pk_nds_music_frame(musicBuf, 2048);
        }
        if (steadyPer >= 0) steadyFrame();
    }
}

// ffmode: a frame as shown - both screens (256x384) and, with wide on, the wide frame's window.
struct Shown { std::vector<u32> screens, wide; int ww = 0, wh = 0; };

static Shown grabShown()
{
    Shown s;
    s.screens.resize(256 * 384);
    int fb = nds->GPU.FrontBuffer;
    for (int k = 0; k < 2; k++)
        memcpy(s.screens.data() + k * 256 * 192, nds->GPU.Framebuffer[fb][k].get(), 256 * 192 * 4);
    if (wideMargin && wideDrawn) {
        // the window (wide's, moved by look)
        int x = -wideMargin + lookX, y = -wideMarginY + lookY;
        s.ww = 256 + 2 * wideMargin;
        s.wh = 192 + 2 * wideMarginY;
        s.wide.resize((size_t) s.ww * s.wh);
        pk_nds_wide_view(s.wide.data(), x, y, s.ww, s.wh);
        for (u32& p : s.wide) p = 0xFF000000u | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
    }
    return s;
}

static void recordShown()
{
#ifdef NDS_DUMP_GL
    if (gpuOn) {
        // The GPU path's window, composed this frame (its 1x copies lag a drawn frame: not comparable across patterns)
        int w = 0, h = 0;
        const u32* px = pk_nds_gl_captured(&w, &h);
        if (!px) return;
        std::vector<u32> argb((size_t) w * h);
        for (size_t k = 0; k < argb.size(); k++) argb[k] = rgbaToArgb(px[k]);
        char path[600];
        snprintf(path, sizeof(path), "%s_%05d_gpu.png", recordPrefix.c_str(), (int) (frameCount - recordStart - 1));
        writePng(path, argb.data(), w, h);
        return;
    }
#endif
    Shown got = grabShown();
    char path[600];
    int f = (int) (frameCount - recordStart - 1);
    snprintf(path, sizeof(path), "%s_%05d.png", recordPrefix.c_str(), f);
    writePng(path, got.screens.data(), 256, 384);
    if (!got.wide.empty()) {
        snprintf(path, sizeof(path), "%s_%05d_wide.png", recordPrefix.c_str(), f);
        writePng(path, got.wide.data(), got.ww, got.wh);
    }
}

// --- savestates (melonDS's own format, the save memory left out of the picture: the cart's
// SRAM rides in the state, so loading one also puts that save back in RAM - never on disk here) ---

static bool saveState(const std::string& path)
{
    Savestate st;
    pk_nds_wide_sync();
    if (!nds->DoSavestate(&st) || st.Error) { fprintf(stderr, "savestate failed\n"); return false; }
    st.Finish();
    return writeFile(path, st.Buffer(), st.Length());
}

static bool loadState(const std::string& path)
{
    std::vector<u8> data = readFile(path.c_str());
    if (data.empty()) { fprintf(stderr, "can't read %s\n", path.c_str()); return false; }
    Savestate st(data.data(), (u32) data.size(), false);
    pk_nds_wide_sync();
    if (st.Error || !nds->DoSavestate(&st) || st.Error) { fprintf(stderr, "loadstate failed\n"); return false; }
    return true;
}

static u32 num(const char* s) { return (u32) strtoul(s, nullptr, 0); }

// 16-bit stereo PCM at [rate] Hz.
static bool writeWav(const char* path, const std::vector<s16>& pcm, int rate)
{
    FILE* f = fopen(path, "wb");
    if (!f) return false;
    u32 data = (u32) (pcm.size() * 2), riff = 36 + data, fmtLen = 16, byteRate = (u32) rate * 4, r = (u32) rate;
    u16 pcmTag = 1, ch = 2, align = 4, bits = 16;
    fwrite("RIFF", 1, 4, f); fwrite(&riff, 4, 1, f); fwrite("WAVEfmt ", 1, 8, f);
    fwrite(&fmtLen, 4, 1, f); fwrite(&pcmTag, 2, 1, f); fwrite(&ch, 2, 1, f); fwrite(&r, 4, 1, f);
    fwrite(&byteRate, 4, 1, f); fwrite(&align, 2, 1, f); fwrite(&bits, 2, 1, f);
    fwrite("data", 1, 4, f); fwrite(&data, 4, 1, f); fwrite(pcm.data(), 2, pcm.size(), f);
    fclose(f);
    return true;
}


// EmulatorEngine's STEADY, after each game frame (see steadyPer). pk_nds_bgm / pk_nds_bgm_settle as pk_nds.cpp reads them.
static void steadyFrame()
{
    static s16 buf[4096];
    if (steadyPer == 0) {
        int got = nds->SPU.ReadOutput(buf, 2048);
        steadyPcm.insert(steadyPcm.end(), buf, buf + got * 2);
        return;
    }
    while (nds->SPU.ReadOutput(buf, 2048) > 0) {}   // the game's own, muted
    auto* cart = nds->NDSCartSlot.GetCart();
    const PkNdsFieldGame* g = cart ? pk_nds_field_game_rom(cart->GetROM(), cart->GetROMLength()) : nullptr;
    int bgm = -1;
    if (g && g->currentBgm) {
        u32 depth = g->bgmDepth ? read32(g->bgmDepth) : 0;
        if (depth <= 7) bgm = (int) (read32(g->currentBgm + 0x1C * depth) & 0xFFFF);
    }
    int state = g ? pk_nds_bgm_settle(g, read32) : 0;
    static int copyState = -1, stateSeen = -1, stateFrames = 0;
    if (state != stateSeen) { stateSeen = state; stateFrames = 0; } else stateFrames++;
    bool settled = state >= 0 && stateFrames >= 4;
    if (bgm >= 0) {
        if (!steadyOn && steadyWait < 0) steadyWait = 4;
        else if (steadyOn && bgm != steadyBgm) {
            if (bgm == 0) pk_nds_music_fade(30); else steadyWait = 0;
            printf("steady %llu: song %d -> %d\n", (unsigned long long) frameCount, steadyBgm, bgm);
        } else if (steadyOn && steadyWait < 0 && settled && state != copyState) {
            printf("steady %llu: song %d's player %08X -> %08X\n", (unsigned long long) frameCount, bgm, copyState, state);
            if ((state & 0xFFFF) == 0) pk_nds_music_fade(30); else steadyWait = 4;
            copyState = state;
        }
        steadyBgm = bgm;
        if (steadyWait >= 0 && ++steadyWait > 4 && (settled || steadyWait > 300)) {
            steadyWait = -1;
            copyState = state;
            steadyOn = pk_nds_music_begin(nds, 48000);
            steadySince = 0;
            printf("steady %llu: copy %s, song %d (player %08X)\n", (unsigned long long) frameCount, steadyOn ? "playing" : "FAILED", bgm, state);
        }
    }
    // A copy frame a refresh: the game's G frames make one
    if (--steadyGroup > 0) return;
    steadyGroup = steadyPer;
    int got = steadyOn ? pk_nds_music_frame(buf, 2048) : 800;
    if (!steadyOn) memset(buf, 0, sizeof(buf));
    if (steadyOn && getenv("STEADY_DEBUG") && steadyWait < 0) {
        if (++steadySince == 30 || steadySince == 1) {
            char line[2000];
            pk_nds_music_debug(line, sizeof(line));
            printf("steady %llu: copy +%d %s\n", (unsigned long long) frameCount, steadySince, line);
        }
    }
    steadyPcm.insert(steadyPcm.end(), buf, buf + got * 2);
}

// fieldinfo: the field read's raw words and bits (pk_nds_field.cpp), for the cart's table entry.
static void fieldInfo()
{
    auto* cart = nds->NDSCartSlot.GetCart();
    const PkNdsFieldGame* g = cart ? pk_nds_field_game_rom(cart->GetROM(), cart->GetROMLength()) : nullptr;
    if (!g) { printf("fieldinfo: no table for this cart\n"); fflush(stdout); return; }
    if (g->gen == 5) {
        u32 gs = read32(g->fieldSystemPtr);
        u32 ps = gs ? read32(gs + g->offProcSys) : 0;
        u32 proc = ps ? read32(ps + g->offCurrentProc) : 0;
        printf("fieldinfo frame %llu: gs %08X proc %08X overlay %u fieldmap %08X event %08X bg tiles %d/%d/%d/%d battle %d"
               " -> bits 0x%X\n", (unsigned long long) frameCount, gs, proc, proc ? read32(proc + g->offProcOverlay) : 0,
               gs ? read32(gs + g->offFieldMap) : 0, gs ? read32(gs + g->offEvent) : 0, pk_nds_bg3_tiles(nds, 0),
               pk_nds_bg3_tiles(nds, 1), pk_nds_bg3_tiles(nds, 2), pk_nds_bg3_tiles(nds, 3), pk_nds_battle_command(g, read32),
               pk_nds_field_state(g, read32, write32, wildOff));
        int panel[3];
        if (pk_nds_gen5_battle_panel(g, read32, panel)) printf("  battle panel %d waiting %d screen %d\n", panel[0], panel[1], panel[2]);
        fflush(stdout);
        return;
    }
    u32 fs = read32(g->fieldSystemPtr);
    u32 pm = fs ? read32(fs + g->offProcessManager) : 0;
    u32 av = fs ? read32(fs + g->offPlayerAvatar) : 0;
    printf("fieldinfo frame %llu: fs %08X pm %08X parent %08X child %08X pause %u task %08X running %u avatar %08X state %u"
           " childInit %08X battleInit %08X bg3 %d -> bits 0x%X\n",
           (unsigned long long) frameCount, fs, pm, pm ? read32(pm) : 0, pm ? read32(pm + 4) : 0, pm ? read32(pm + 8) : 0,
           fs ? read32(fs + g->offTask) : 0, fs ? read32(fs + g->offRunningFieldMap) : 0, av, av ? read32(av + g->offAvatarState) : 0,
           pm && read32(pm + 4) ? read32(read32(pm + 4)) : 0, read32(g->battleTemplate), pk_nds_bg3_tiles(nds), pk_nds_field_state(g, read32, write32, wildOff));
    fflush(stdout);
}

int main(int argc, char** argv)
{
    if (argc < 3) {
        fprintf(stderr, "usage: nds_dump ROM.nds SAVE.sav [--jit] < script\n");
        return 2;
    }
    bool jit = argc > 3 && !strcmp(argv[3], "--jit");
    savePath = argv[2];
    pkNdsSetLog(logLine);

    std::vector<u8> rom = readFile(argv[1]);
    if (rom.empty()) { fprintf(stderr, "can't read %s\n", argv[1]); return 1; }
    std::vector<u8> save = readFile(argv[2]);

    static PkNdsHost host {onSave, onStop, nullptr};

    NDSArgs args;
    // The app's JIT (pk_nds.cpp): fast memory on, which melonDS turns off on macOS (IsFastMemSupported)
    if (jit) args.JIT = JITArgs {32, true, true, true};
    else args.JIT = std::nullopt;
    if (const char* dir = getenv("NDS_BIOS_DIR")) {
        // The real BIOS (bios7.bin / bios9.bin in that folder) instead of FreeBIOS, to compare against
        std::vector<u8> b7 = readFile((std::string(dir) + "/bios7.bin").c_str());
        std::vector<u8> b9 = readFile((std::string(dir) + "/bios9.bin").c_str());
        if (b7.size() == ARM7BIOSSize && b9.size() == ARM9BIOSSize) {
            memcpy(args.ARM7BIOS->data(), b7.data(), ARM7BIOSSize);
            memcpy(args.ARM9BIOS->data(), b9.data(), ARM9BIOSSize);
            fprintf(stderr, "real BIOS from %s\n", dir);
        }
    }
    nds = new WatchNDS(std::move(args), &host);
    nds->Reset();

    NDSCart::NDSCartArgs cartArgs;
    if (!save.empty()) {
        cartArgs.SRAM = std::make_unique<u8[]>(save.size());
        memcpy(cartArgs.SRAM.get(), save.data(), save.size());
        cartArgs.SRAMLength = (u32) save.size();
    }
    auto romCopy = std::make_unique<u8[]>(rom.size());
    memcpy(romCopy.get(), rom.data(), rom.size());
    auto cart = NDSCart::ParseROM(std::move(romCopy), (u32) rom.size(), &host, std::move(cartArgs));
    if (!cart) { fprintf(stderr, "not a DS ROM\n"); return 1; }
    nds->SetNDSCart(std::move(cart));
    nds->Reset();
    nds->SetupDirectBoot("rom.nds");
    // The 3D on its own thread, as the app runs it (pk_nds_init); `threaded 0` puts it on this one
    static_cast<SoftRenderer&>(nds->GPU.GetRenderer3D()).SetThreaded(true, nds->GPU);
    nds->Start();
    fprintf(stderr, "booted %s, save %u bytes\n", argv[1], nds->GetNDSSaveLength());

    char line[512];
    while (fgets(line, sizeof(line), stdin)) {
        char* hash = strchr(line, '#');
        if (hash) *hash = 0;
        char cmd[64] = {0}, a[256] = {0}, b[64] = {0}, c[64] = {0}, d[64] = {0};
        int n = sscanf(line, "%63s %255s %63s %63s %63s", cmd, a, b, c, d);
        if (n <= 0) continue;

        if (!strcmp(cmd, "wait")) {
            runFrames((int) num(a), heldKeys);
        } else if (!strcmp(cmd, "keys")) {
            heldKeys = parseKeys(a);
        } else if (!strcmp(cmd, "press")) {
            runFrames(1, heldKeys | parseKeys(a));
            runFrames(1, heldKeys);
        } else if (!strcmp(cmd, "hold")) {
            runFrames((int) num(b), heldKeys | parseKeys(a));
            runFrames(1, heldKeys);
        } else if (!strcmp(cmd, "touch")) {
            // touch X Y [N]: the stylus down at (X, Y) on the bottom screen for N frames (default 2), then up
            touching = true;
            touchX = (u16) num(a);
            touchY = (u16) num(b);
            runFrames(n > 3 ? (int) num(c) : 2, heldKeys);
            touching = false;
            runFrames(1, heldKeys);
        } else if (!strcmp(cmd, "shot")) {
            shot(a);
        } else if (!strcmp(cmd, "shots")) {
            // shots PREFIX EVERY COUNT: COUNT shots EVERY frames apart, PREFIX_0000.png ...
            int every = (int) num(b), count = (int) num(c);
            for (int i = 0; i < count; i++) {
                runFrames(every, heldKeys);
                char path[300];
                snprintf(path, sizeof(path), "%s_%04d.png", a, i);
                shot(path);
            }
        } else if (!strcmp(cmd, "dump")) {
            // Main RAM (4 MB at 0x02000000), shared WRAM (32 KB) and the ARM9's DTCM (16 KB, its stack)
            std::string dir(a);
            writeFile(dir + "/mainram.bin", nds->MainRAM, 0x400000);
            writeFile(dir + "/swram.bin", nds->SharedWRAM, nds->SharedWRAMSize);
            writeFile(dir + "/dtcm.bin", nds->ARM9.DTCM, 0x4000);
            writeFile(dir + "/itcm.bin", nds->ARM9.ITCM, 0x8000);
        } else if (!strcmp(cmd, "savestate")) {
            saveState(a);
        } else if (!strcmp(cmd, "loadstate")) {
            loadState(a);
        } else if (!strcmp(cmd, "savefile")) {
            writeFile(a, nds->GetNDSSave(), nds->GetNDSSaveLength());
        } else if (!strcmp(cmd, "peek8") || !strcmp(cmd, "peek16") || !strcmp(cmd, "peek32")) {
            u32 addr = num(a), v;
            if (!strcmp(cmd, "peek8")) v = nds->ARM9Read8(addr);
            else if (!strcmp(cmd, "peek16")) v = nds->ARM9Read16(addr);
            else v = nds->ARM9Read32(addr);
            printf("%s 0x%08X = 0x%X (%u)\n", cmd, addr, v, v);
            fflush(stdout);
        } else if (!strcmp(cmd, "dump7")) {
            // dump7 ADDR LEN FILE: LEN bytes of the ARM7's bus from ADDR (its WRAM, code, the shared area)
            u32 addr = num(a), n7 = num(b);
            std::vector<u8> bytes(n7);
            for (u32 k = 0; k < n7; k++) bytes[k] = nds->ARM7Read8(addr + k);
            writeFile(c, bytes.data(), n7);
        } else if (!strcmp(cmd, "poke8") || !strcmp(cmd, "poke16") || !strcmp(cmd, "poke32")) {
            u32 addr = num(a), v = num(b);
            if (!strcmp(cmd, "poke8")) nds->ARM9Write8(addr, (u8) v);
            else if (!strcmp(cmd, "poke16")) nds->ARM9Write16(addr, (u16) v);
            else nds->ARM9Write32(addr, v);
        } else if (!strcmp(cmd, "ards")) {
            // ards W W ...: one Action Replay DS cheat (hex words, two a line), replacing the ones before, the way the
            // app's CHEATS do (pk_nds_set_cheats); "ards" alone takes them out. melonDS runs them on the ARM7's VBlank.
            ARCode code{};
            code.Parent = nullptr;
            code.Enabled = true;
            const char* p = line + 4;
            char* end = nullptr;
            for (;;) {
                unsigned long w = strtoul(p, &end, 16);
                if (end == p) break;
                code.Code.push_back((u32) w);
                p = end;
            }
            nds->AREngine.Cheats.clear();
            if (!code.Code.empty()) nds->AREngine.Cheats.push_back(code);
            printf("ards: %zu words\n", code.Code.size());
            fflush(stdout);
        } else if (!strcmp(cmd, "look")) {
            // look DX DY: from here on the window moves DX, DY DS pixels a frame (look 0 0 stops it where it is)
            lookDX = (int) num(a);
            lookDY = n > 2 ? (int) num(b) : 0;
        } else if (!strcmp(cmd, "wide")) {
            // wide MX [MY]: EXPAND with MX DS pixels left and right, MY above and below (0 = off; MY defaults to 0)
            wideMargin = (int) num(a);
            int my = n > 2 ? (int) num(b) : 0;
            wideMarginY = my;
            pk_nds_wide_set(nds, wideMargin > 0);
            pk_nds_wide_need(wideMargin, wideMargin, my, my);
            wideDrawn = false;
#ifdef NDS_DUMP_GL
        } else if (!strcmp(cmd, "gpu")) {
            // gpu N: DS RESOLUTION at N (1 = back to the software renderer)
            pk_nds_gl_capture(true);
            pk_nds_wide_sync();
            int s = pk_nds_gl_set_scale(nds, (int) num(a));
            gpuOn = s > 1;
            printf("gpu: scale %d\n", s);
        } else if (!strcmp(cmd, "gpushot")) {
            // gpushot FILE: the GPU path's last window (the screen, or EXPAND's view), at its scale
            int w = 0, h = 0;
            const u32* px = pk_nds_gl_captured(&w, &h);
            if (!px) { fprintf(stderr, "gpushot: no frame\n"); continue; }
            std::vector<u32> argb((size_t) w * h);
            for (size_t k = 0; k < argb.size(); k++) argb[k] = rgbaToArgb(px[k]);
            writePng(a, argb.data(), w, h);
#endif
        } else if (!strcmp(cmd, "wideshot")) {
            // wideshot FILE: the last wide frame (RGBA words -> PNG)
            if (!wideDrawn) { fprintf(stderr, "wideshot: no wide frame\n"); continue; }
            int x, y, w, h;
            pk_nds_wide_rect(&x, &y, &w, &h);
            std::vector<u32> px((size_t) w * h), argb((size_t) w * h);
            pk_nds_wide_view(px.data(), x, y, w, h);
            for (size_t i = 0; i < argb.size(); i++) {
                u32 p = px[i];
                argb[i] = 0xFF000000u | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
            }
            writePng(a, argb.data(), w, h);
        } else if (!strcmp(cmd, "wideshots")) {
            // wideshots PREFIX EVERY COUNT: COUNT wide frames EVERY frames apart, PREFIX_0000.png ...
            int every = (int) num(b), count = (int) num(c);
            for (int i = 0; i < count; i++) {
                runFrames(every, heldKeys);
                if (!wideDrawn) continue;
                int x, y, w, h;
                pk_nds_wide_rect(&x, &y, &w, &h);
                std::vector<u32> px((size_t) w * h), argb((size_t) w * h);
                pk_nds_wide_view(px.data(), x, y, w, h);
                for (size_t k = 0; k < argb.size(); k++) {
                    u32 p = px[k];
                    argb[k] = 0xFF000000u | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
                }
                char path[300];
                snprintf(path, sizeof(path), "%s_%04d.png", a, i);
                writePng(path, argb.data(), w, h);
            }
        } else if (!strcmp(cmd, "scriptgo")) {
            // scriptgo FILE: as script, but back once it has started (a battle doesn't free the field for a while)
            auto* cart = nds->NDSCartSlot.GetCart();
            const PkNdsFieldGame* g = cart ? pk_nds_field_game_rom(cart->GetROM(), cart->GetROMLength()) : nullptr;
            std::vector<u8> bytes = readFile(a);
            int seq = pk_nds_call_script(nds, g, bytes.data(), (u32) bytes.size());
            int f = 0;
            while (seq && pk_nds_call_status(seq) == 0 && f < 20) { runFrames(1, heldKeys); f++; }
            printf("scriptgo %s: %s\n", a, seq ? "started" : "refused");
            fflush(stdout);
        } else if (!strcmp(cmd, "bgdump")) {
            // bgdump BG FILE: engine A's text BG whole (its map's size), as the hardware reads it; clear = magenta
            int bg = (int) num(a);
            u16 cnt = nds->GPU.GPU2D_A.BGCnt[bg & 3];
            int w = (cnt >> 14) & 1 ? 512 : 256, h = (cnt >> 14) & 2 ? 512 : 256;
            std::vector<u32> argb((size_t) w * h);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    u16 c = pk_nds_bg_pixel(nds, bg, x, y);
                    argb[(size_t) y * w + x] = !c ? 0xFFFF00FFu
                        : 0xFF000000u | ((c & 0x1F) * 255 / 31) << 16 | (((c >> 5) & 0x1F) * 255 / 31) << 8 | ((c >> 10) & 0x1F) * 255 / 31;
                }
            writePng(b, argb.data(), w, h);
            printf("bgdump %d: %dx%d, cnt %04X, scroll %d,%d\n", bg, w, h, cnt, nds->GPU.GPU2D_A.BGXPos[bg & 3], nds->GPU.GPU2D_A.BGYPos[bg & 3]);
            fflush(stdout);
        } else if (!strcmp(cmd, "widecheck")) {
            // widecheck N: N frames, each checked where the slices overlap the game's frame
            int frames = n > 1 ? (int) num(a) : 1, cmpTotal = 0, diffTotal = 0, farTotal = 0, badFrames = 0;
            for (int i = 0; i < frames; i++) {
                runFrames(1, heldKeys);
                int c, d, f;
                pk_nds_wide_check(nds, nds->GPU.Framebuffer[nds->GPU.FrontBuffer][0].get(), &c, &d, &f);
                cmpTotal += c; diffTotal += d; farTotal += f;
                if (f) badFrames++;
            }
            printf("widecheck %d frames: %d overlap pixels compared, %d differ (%d by more than a texel), %d frames with those\n",
                frames, cmpTotal, diffTotal, farTotal, badFrames);
            fflush(stdout);
        } else if (!strcmp(cmd, "threaded")) {
            // threaded 1 / 0: the 3D renderer on its own thread (the default, as the app runs it: pk_nds.cpp) / on this one
            pk_nds_wide_sync();
            static_cast<SoftRenderer&>(nds->GPU.GetRenderer3D()).SetThreaded(num(a) != 0, nds->GPU);
        } else if (!strcmp(cmd, "ffmode")) {
            // ffmode GROUP PREFIX: from here on every command's frames run in the app's fast-forward pattern at GROUP
            // frames a refresh (0: every frame drawn), each frame drawn written as PREFIX_<frame>.png (both screens;
            // with wide on also PREFIX_<frame>_wide.png); ffmode off stops. Two runs of one script compared.
            if (!strcmp(a, "off")) { appPerTick = 0; recordPrefix.clear(); continue; }
            appPerTick = (int) num(a);
            appGroup = appPerTick;
            recordPrefix = b;
            recordStart = frameCount;
        } else if (!strcmp(cmd, "ffguard")) {
            // ffguard 0 / 1: pk_nds_run_frame's guard (a frame whose 3D was skipped isn't shown) off / on (default)
            drawGuard = num(a) != 0;
        } else if (!strcmp(cmd, "ffgroup")) {
            // ffgroup N: ffmode's frames a refresh changed to N from the next frame on (0: every frame drawn), still
            // recording - what EmulatorEngine does when ffPerTick changes (SMART's 1x, FF off, the refresh estimate)
            appPerTick = (int) num(a);
            appGroup = appPerTick;
        } else if (!strcmp(cmd, "steady")) {
            // steady G FILE: EmulatorEngine's STEADY from here on (G game frames a refresh; 0 = the game's own audio),
            // its audio into FILE at `steady off`
            if (!strcmp(a, "off")) {
                if (steadyPer >= 0) writeWav(steadyFile.c_str(), steadyPcm, 48000);
                printf("steady: %.1f s into %s\n", steadyPcm.size() / 2 / 48000.0, steadyFile.c_str());
                fflush(stdout);
                pk_nds_music_end();
                steadyPer = -1;
                steadyOn = false;
                continue;
            }
            steadyPer = (int) num(a);
            steadyGroup = steadyPer;
            steadyFile = b;
            steadyPcm.clear();
            steadyOn = false;
            steadyWait = -1;
            steadyBgm = -1;
        } else if (!strcmp(cmd, "musicon")) {
            // musicon N: STEADY's copy begun now and run N frames after each game frame from here on, as the app runs it
            // between its frames (0: stopped)
            musicPerFrame = (int) num(a);
            if (musicPerFrame > 0) printf("musicon: %s\n", pk_nds_music_begin(nds, 48000) ? "playing" : "failed");
            else pk_nds_music_end();
            fflush(stdout);
        } else if (!strcmp(cmd, "skipdraw")) {
            skipDraw = (int) num(a);
        } else if (!strcmp(cmd, "battlemenu")) {
            // battlemenu N: the battle controller's step (pk_nds_battle_command) each frame for N frames, as it changes
            auto* cart = nds->NDSCartSlot.GetCart();
            const PkNdsFieldGame* g = cart ? pk_nds_field_game_rom(cart->GetROM(), cart->GetROMLength()) : nullptr;
            int frames = (int) num(a), last = -100;
            for (int i = 0; i < frames; i++) {
                int m = pk_nds_battle_command(g, read32);
                if (m != last) { printf("battlemenu frame %d: %d\n", i, m); last = m; }
                runFrames(1, heldKeys);
            }
            fflush(stdout);
        } else if (!strcmp(cmd, "widecount")) {
            int c[8];
            pk_nds_wide_counts(c);
            printf("widecount frame %llu: above %d %d %d, beside %d %d, below %d %d %d polygons\n",
                (unsigned long long) frameCount, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
            fflush(stdout);
        } else if (!strcmp(cmd, "wildoff")) {
            // wildoff 1 / 0: WILD ENCOUNTERS OFF / back on; battles counts the battles started since
            wildOff = (int) num(a);
            battlesSeen = 0;
        } else if (!strcmp(cmd, "wildrates")) {
            // wildrates FILE: the cart's rates by map ([map grass surf] triples, whitespace apart), as the app hands them over
            std::vector<int32_t> t;
            if (FILE* f = fopen(a, "r")) { int v; while (fscanf(f, "%d", &v) == 1) t.push_back(v); fclose(f); }
            pk_nds_field_set_rates(t.data(), (int) t.size() / 3);
            printf("wildrates %d maps\n", (int) t.size() / 3);
            fflush(stdout);
        } else if (!strcmp(cmd, "wildrate")) {
            // wildrate: the current map's grass / surf rates as the field holds them now
            auto* cart = nds->NDSCartSlot.GetCart();
            const PkNdsFieldGame* g = cart ? pk_nds_field_game_rom(cart->GetROM(), cart->GetROMLength()) : nullptr;
            if (g) {
                u32 fs = read32(g->fieldSystemPtr), data = read32(fs + g->offMapHeaderData), map = read32(read32(fs + g->offLocation));
                auto rate = [&](u32 at) { return g->rateBytes ? (read32(at & ~3u) >> ((at & 3) * 8)) & 0xFF : read32(at); };
                printf("wildrate frame %llu: map %u grass %u surf %u\n", (unsigned long long) frameCount, map,
                       rate(data + g->offGrassRate), rate(data + g->offSurfRate));
                fflush(stdout);
            }
        } else if (!strcmp(cmd, "battles")) {
            printf("battles %d\n", battlesSeen);
            fflush(stdout);
        } else if (!strcmp(cmd, "action")) {
            // action N [A0 A1 A2 A3]: post pk_nds_call's action N (1 = PC) for the next frame, then report when it ran
            auto* cart = nds->NDSCartSlot.GetCart();
            const PkNdsFieldGame* g = cart ? pk_nds_field_game_rom(cart->GetROM(), cart->GetROMLength()) : nullptr;
            u32 args[4] = {n > 2 ? num(b) : 0, n > 3 ? num(c) : 0, n > 4 ? num(d) : 0, 0};
            if (n > 5) sscanf(line, "%*s %*s %*s %*s %*s %u", &args[3]);
            int seq = pk_nds_call_action(nds, g, (int) num(a), args);
            int st = 0, f = 0;
            while (seq && (st = pk_nds_call_status(seq)) == 0 && f < 20) { runFrames(1, heldKeys); f++; }
            printf("action %s: job %d %s after %d frames\n", a, seq, !seq ? "refused" : st > 0 ? "ran" : "never ran", f);
            u32 res[PK_NDS_MAX_RESULTS];
            if (st > 0 && pk_nds_call_results(seq, res)) {
                printf("results:");
                for (int i = 0; i < PK_NDS_MAX_RESULTS; i++) printf(" %X", res[i]);
                printf("\n");
            }
            fflush(stdout);
        } else if (!strcmp(cmd, "call")) {
            // call FN [A0 A1 A2 A3]: the game's function FN (Thumb: odd) from the field's main loop next frame (pk_nds_call)
            auto* cart = nds->NDSCartSlot.GetCart();
            const PkNdsFieldGame* g = cart ? pk_nds_field_game_rom(cart->GetROM(), cart->GetROMLength()) : nullptr;
            u32 v[5] = {0};
            int got = sscanf(line, "%*s %i %i %i %i %i", (int*) &v[0], (int*) &v[1], (int*) &v[2], (int*) &v[3], (int*) &v[4]);
            PkNdsOp op = {v[0], {v[1], v[2], v[3], v[4]}};
            int seq = got > 0 ? pk_nds_call_ops(nds, g, &op, 1) : 0;
            int st = 0, f = 0;
            while (seq && (st = pk_nds_call_status(seq)) == 0 && f < 20) { runFrames(1, heldKeys); f++; }
            u32 res[PK_NDS_MAX_RESULTS] = {0};
            if (st > 0) pk_nds_call_results(seq, res);
            printf("call 0x%08X: %s, r0 = 0x%X\n", v[0], !seq ? "refused" : st > 0 ? "ran" : "never ran", res[0]);
            fflush(stdout);
        } else if (!strcmp(cmd, "script")) {
            // script FILE: a field script (the game's bytecode, e.g. assembled with pret/pokeplatinum's macros) run as
            // the PC action runs its own; then frames until the field is free again (at most 3000)
            auto* cart = nds->NDSCartSlot.GetCart();
            const PkNdsFieldGame* g = cart ? pk_nds_field_game_rom(cart->GetROM(), cart->GetROMLength()) : nullptr;
            std::vector<u8> bytes = readFile(a);
            int seq = pk_nds_call_script(nds, g, bytes.data(), (u32) bytes.size());
            int f = 0;
            while (seq && pk_nds_call_status(seq) == 0 && f < 20) { runFrames(1, heldKeys); f++; }
            int run = 0;
            while (seq && run < 3000) {
                runFrames(1, heldKeys);
                run++;
                if (g && (pk_nds_field_state(g, read32, nullptr, 0) & PK_NDS_FIELD_LOCKED) == 0) break;
            }
            printf("script %s: %s, field free after %d frames\n", a, seq ? "started" : "refused", run);
            fflush(stdout);
        } else if (!strcmp(cmd, "music")) {
            // music SECONDS FILE.wav: STEADY's music instance (pk_nds_music) begun from the game as it is now, run alone
            // for SECONDS (the game doesn't move), its audio written out; then the time it took per frame
            int frames = (int) (num(a) * 60);
            const int rate = 48000;
            auto t0 = std::chrono::steady_clock::now();
            bool ok = pk_nds_music_begin(nds, rate);
            double beginMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            std::vector<s16> pcm;
            s16 buf[4096];
            t0 = std::chrono::steady_clock::now();
            int stall = 0, stallAt = -1;
            for (int i = 0; ok && i < frames; i++) {
                int got = pk_nds_music_frame(buf, 2048);
                pcm.insert(pcm.end(), buf, buf + got * 2);
                if (pk_nds_music_stalled() > stall) { stall = pk_nds_music_stalled(); stallAt = i; }
                if (getenv("MUSIC_DEBUG") && (i < 30 || i % 60 == 0)) {
                    char line[2000];
                    pk_nds_music_debug(line, sizeof(line));
                    printf("music %d: still %d %s\n", i, pk_nds_music_stalled(), line);
                }
            }
            printf("music stall: longest %d frames (to frame %d)\n", stall, stallAt);
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            pk_nds_music_end();
            writeWav(b, pcm, rate);
            printf("music: %s, begin %.1f ms, %.3f ms a frame, %zu samples (%.2f s)\n", ok ? "playing" : "failed", beginMs,
                frames ? ms / frames : 0.0, pcm.size() / 2, pcm.size() / 2.0 / rate);
            fflush(stdout);
        } else if (!strcmp(cmd, "livewav")) {
            // livewav SECONDS FILE.wav: the game's own audio over SECONDS of frames (keys held as set)
            int frames = (int) (num(a) * 60);
            std::vector<s16> pcm;
            s16 buf[4096];
            while (nds->SPU.ReadOutput(buf, 2048) > 0) {}
            for (int i = 0; i < frames; i++) {
                runFrames(1, heldKeys);
                int got = nds->SPU.ReadOutput(buf, 2048);
                pcm.insert(pcm.end(), buf, buf + got * 2);
            }
            writeWav(b, pcm, 48000);   // NDSArgs' default output rate
        } else if (!strcmp(cmd, "trace")) {
            // trace N: N frames (keys held as set), each one's field bits and the top screen's master brightness
            auto* cart = nds->NDSCartSlot.GetCart();
            const PkNdsFieldGame* g = cart ? pk_nds_field_game_rom(cart->GetROM(), cart->GetROMLength()) : nullptr;
            int frames = (int) num(a);
            for (int i = 0; i < frames; i++) {
                runFrames(1, heldKeys);
                int bits = g ? pk_nds_field_state(g, read32, nullptr, 0) : 0;
                bits |= pk_nds_field_screen_touch(g, bits, nds->GPU.GPU2D_A.DispCnt);   // as pk_nds_field_bits adds it
                // Each screen of one colour (the app's oneColour: a 16 x 12 grid alike), as T / B.
                char flat[3] = "--";
                for (int sc = 0; sc < 2; sc++) {
                    const u32* px = nds->GPU.Framebuffer[nds->GPU.FrontBuffer][sc].get();
                    bool one = px != nullptr;
                    for (int gy = 0; one && gy < 12; gy++)
                        for (int gx = 0; one && gx < 16; gx++)
                            one = (px[(gy * 16 + 8) * 256 + gx * 16 + 8] & 0xFFFFFF) == (px[8 * 256 + 8] & 0xFFFFFF);
                    if (one) flat[sc] = sc ? 'B' : 'T';
                }
                printf("trace %llu: bits %04X brightA %04X brightB %04X flat %s dispA %08X bg3 %d dispB %08X\n", (unsigned long long) frameCount, bits,
                    nds->GPU.GPU2D_A.MasterBrightness, nds->GPU.GPU2D_B.MasterBrightness, flat, nds->GPU.GPU2D_A.DispCnt,
                    pk_nds_bg3_tiles(nds), nds->GPU.GPU2D_B.DispCnt);
            }
            fflush(stdout);
        } else if (!strcmp(cmd, "arm9shift")) {
            // arm9shift N: the ARM9's clock as 2^N times the bus's (1 = a DS, 2 = twice that, the DSi's), switched live
            // the way DSi::Set_SCFG_Clock9 does it.
            u32 shift = num(a) ? num(a) : 1;
            nds->ARM9Timestamp >>= nds->ARM9ClockShift;
            nds->ARM9Target >>= nds->ARM9ClockShift;
            nds->ARM9ClockShift = shift;
            nds->ARM9Timestamp <<= shift;
            nds->ARM9Target <<= shift;
            nds->ARM9.UpdateRegionTimings(0x00000, 0x100000);
            printf("arm9shift %u\n", shift);
        } else if (!strcmp(cmd, "bench")) {
            // bench N: N frames as fast as they go, then the average ms per frame (with wide on, its frames too)
            int frames = (int) num(a);
            timespec c0, c1;
            clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &c0);
            auto t0 = std::chrono::steady_clock::now();
            runFrames(frames, heldKeys);
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &c1);
            double cpu = (c1.tv_sec - c0.tv_sec) * 1e3 + (c1.tv_nsec - c0.tv_nsec) / 1e6;
            // (process CPU as dsp_dump prints it: the two DS cores' runs compare)
            printf("bench %d frames: %.3f ms a frame (wide %d), %.3f ms CPU\n", frames, ms / frames, wideMargin, cpu / frames);
            fflush(stdout);
        } else if (!strcmp(cmd, "watch")) {
            // watch LO HI [r|w|rw] [N]: report the game's accesses to [LO, HI) (at most N, default 20); watch off clears
            if (!strcmp(a, "off")) watches.clear();
            else {
                bool reads = c[0] && strchr(c, 'r'), writes = !c[0] || strchr(c, 'w');   // writes only by default
                watches.push_back({num(a), num(b), reads, writes, d[0] ? (int) num(d) : 20});
            }
        } else if (!strcmp(cmd, "itcm32")) {
            // itcm32 ADDR VALUE: a word into the hook's spare ITCM (an argument a call points at)
            u32 val = num(b);
            pk_nds_call_itcm_write(nds, num(a), &val, 4);
        } else if (!strcmp(cmd, "fieldinfo")) {
            fieldInfo();
        } else if (!strcmp(cmd, "frame")) {
            printf("frame %llu\n", (unsigned long long) frameCount);
            fflush(stdout);
        } else if (!strcmp(cmd, "echo")) {
            printf("%s", line + 5);
            fflush(stdout);
        } else {
            fprintf(stderr, "unknown command %s\n", cmd);
        }
    }
    pk_nds_wide_set(nullptr, false);   // its helper threads joined
    pk_nds_music_end(true);
    delete nds;
    return 0;
}
