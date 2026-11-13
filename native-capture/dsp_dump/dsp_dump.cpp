// dsp_dump: nds_dump's twin for the app's second DS core, DSperate (app/src/main/cpp/nds/pk_nds_dsp.cpp, driven through
// its pk_ndsd_* calls exactly as the app's pk_nds_core.cpp hands them on). The same script commands as nds_dump where
// both make sense, so one script runs on both cores and the outputs compare. See README.md.

#include "pk_nds_cores.h"
#include "pk_nds_field.h"

#include <zlib.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

typedef uint8_t u8;
typedef uint32_t u32;

static unsigned long long frameCount = 0;
static u32 heldKeys = 0;
static bool touching = false;
static int touchX = 0, touchY = 0;
static std::vector<int16_t> audioOut(16384);
static long long audioFrames = 0;

static bool writeFile(const std::string& path, const void* data, size_t len)
{
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) { fprintf(stderr, "can't write %s\n", path.c_str()); return false; }
    fwrite(data, 1, len, f);
    fclose(f);
    return true;
}

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

// RGBA words (as the app's screens are) as an RGB PNG.
static bool writePng(const std::string& path, const u32* rgba, int w, int h)
{
    std::vector<u8> raw;
    for (int y = 0; y < h; y++) {
        raw.push_back(0);
        for (int x = 0; x < w; x++) {
            u32 p = rgba[y * w + x];
            raw.push_back(p); raw.push_back(p >> 8); raw.push_back(p >> 16);
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

static bool shot(const std::string& path)
{
    std::vector<u32> px(256 * 384);
    memcpy(px.data(), pk_ndsd_top(), 256 * 192 * 4);
    memcpy(px.data() + 256 * 192, pk_ndsd_bottom(), 256 * 192 * 4);
    return writePng(path, px.data(), 256, 384);
}

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
        for (int i = 0; i < 12; i++)
            if (key == names[i]) { bits |= 1u << i; found = true; }
        if (!found) fprintf(stderr, "unknown key %s\n", key.c_str());
        if (comma == std::string::npos) break;
        pos = comma + 1;
    }
    return bits;
}

static void runFrames(int n, u32 keys)
{
    for (int i = 0; i < n; i++) {
        pk_ndsd_set_keys(keys);
        pk_ndsd_touch(touching, touchX, touchY);
        pk_ndsd_run_frame();
        audioFrames += pk_ndsd_read_audio(audioOut.data(), (int) audioOut.size() / 2);
        frameCount++;
    }
}

static u32 num(const char* s) { return (u32) strtoul(s, nullptr, 0); }

static double cpuMs()
{
    timespec t;
    clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

int main(int argc, char** argv)
{
    if (argc < 3) {
        fprintf(stderr, "usage: dsp_dump ROM.nds SAVE.sav < script\n");
        return 2;
    }
    if (!pk_ndsd_init(argv[1], argv[2], 48000)) {
        fprintf(stderr, "DSperate didn't start\n");
        return 1;
    }
    char code[5] = {};
    pk_ndsd_rom_code(code);
    fprintf(stderr, "booted %s (%s rev %d)\n", argv[1], code, pk_ndsd_rom_revision());

    char line[512];
    while (fgets(line, sizeof(line), stdin)) {
        if (char* hash = strchr(line, '#')) *hash = 0;
        char cmd[64] = {0}, a[256] = {0}, b[64] = {0}, c[64] = {0};
        if (sscanf(line, "%63s %255s %63s %63s", cmd, a, b, c) < 1) continue;
        if (!strcmp(cmd, "wait")) runFrames((int) num(a), heldKeys);
        else if (!strcmp(cmd, "keys")) heldKeys = parseKeys(a);
        else if (!strcmp(cmd, "press")) { runFrames(1, parseKeys(a)); runFrames(1, heldKeys); }
        else if (!strcmp(cmd, "hold")) { runFrames((int) num(b), parseKeys(a)); runFrames(1, heldKeys); }
        else if (!strcmp(cmd, "touch")) {
            touching = true; touchX = (int) num(a); touchY = (int) num(b);
            runFrames(c[0] ? (int) num(c) : 2, heldKeys);
            touching = false;
            runFrames(1, heldKeys);
        } else if (!strcmp(cmd, "shot")) shot(a);
        else if (!strcmp(cmd, "dump")) {
            std::vector<u8> ram(4 << 20);
            pk_ndsd_read(0x02000000, ram.data(), (int) ram.size());
            writeFile(std::string(a) + "/mainram.bin", ram.data(), ram.size());
            if (const u8* dtcm = pk_ndsd_dtcm()) writeFile(std::string(a) + "/dtcm.bin", dtcm, 0x4000);
        } else if (!strcmp(cmd, "savestate")) printf("savestate %s: %d\n", a, pk_ndsd_save_state(a));
        else if (!strcmp(cmd, "loadstate")) printf("loadstate %s: %d\n", a, pk_ndsd_load_state(a));
        else if (!strcmp(cmd, "matches")) printf("matches %s: %d\n", a, pk_ndsd_state_matches_save(a));
        else if (!strcmp(cmd, "flush")) printf("flush: %d\n", pk_ndsd_flush_save());
        else if (!strcmp(cmd, "finishsave")) printf("finishsave: %d frames\n", pk_ndsd_finish_save((int) num(a)));
        else if (!strcmp(cmd, "fieldinfo")) printf("fieldinfo frame %llu: bits 0x%X scene %d fade %04X\n", frameCount,
            pk_ndsd_field_bits(), pk_ndsd_wide_scene(), pk_ndsd_fade());
        else if (!strcmp(cmd, "trace")) {
            int n = (int) num(a), last = -1;
            for (int i = 0; i < n; i++) {
                runFrames(1, heldKeys);
                int bits = pk_ndsd_field_bits();
                if (bits != last) printf("trace %llu: bits %04X fade %04X\n", frameCount, bits, pk_ndsd_fade());
                last = bits;
            }
        } else if (!strcmp(cmd, "peek32")) {
            u32 v = 0;
            pk_ndsd_read(num(a), (u8*) &v, 4);
            printf("peek32 %08X: %08X\n", num(a), v);
        } else if (!strcmp(cmd, "ards")) {
            // ards W W ...: one Action Replay DS cheat as pk_nds_set_cheats takes it; ards alone takes it out
            std::vector<u32> words;
            char* p = line + strlen("ards");
            while (*p) {
                char* end;
                unsigned long w = strtoul(p, &end, 16);
                if (end == p) break;
                words.push_back((u32) w);
                p = end;
            }
            int32_t len = (int32_t) words.size();
            printf("ards: %d loaded\n", pk_ndsd_set_cheats(words.data(), (int) words.size(), &len, words.empty() ? 0 : 1));
        } else if (!strcmp(cmd, "skip")) pk_ndsd_set_draw(num(a) == 0, num(a) == 0);   // skip 1: frames not drawn (FF)
        else if (!strcmp(cmd, "bench")) {
            // bench N: N frames as fast as they go: wall and process CPU per frame, audio frames made
            int frames = (int) num(a);
            long long audio0 = audioFrames;
            double c0 = cpuMs();
            auto t0 = std::chrono::steady_clock::now();
            runFrames(frames, heldKeys);
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            double cpu = cpuMs() - c0;
            printf("bench %d frames: %.3f ms a frame wall, %.3f ms CPU, %.1f audio frames a frame\n", frames, ms / frames,
                cpu / frames, (double) (audioFrames - audio0) / frames);
        } else fprintf(stderr, "unknown command %s\n", cmd);
        fflush(stdout);
    }
    pk_ndsd_deinit();
    return 0;
}
