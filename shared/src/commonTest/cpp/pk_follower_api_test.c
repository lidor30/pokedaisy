#include "pk_api.h"
#include "pk_wide.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures;
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #c); failures++; } } while (0)

static void flags(int on, int have) {
    char info[512], prefix[32];
    pk_follower_info(info, sizeof info);
    snprintf(prefix, sizeof prefix, "on %d have %d ", on, have);
    CHECK(strncmp(info, prefix, strlen(prefix)) == 0);
}

static void assets(void) {
    uint8_t frames[PK_API_FOLLOWER_FRAME_BYTES], palette[PK_API_FOLLOWER_PALETTE_BYTES];
    memset(frames, 0x11, sizeof frames); memset(palette, 0xFF, sizeof palette);
    pk_api_follower_set(frames, sizeof frames, palette, sizeof palette); flags(0, 1);
    pk_api_follower_set(frames, sizeof frames - 1, palette, sizeof palette); flags(0, 0);
    pk_api_follower_set(frames, sizeof frames, palette, sizeof palette - 1); flags(0, 0);
    pk_api_follower_set(NULL, sizeof frames, palette, sizeof palette); flags(0, 0);
    pk_api_follower_set(frames, sizeof frames, NULL, sizeof palette); flags(0, 0);
    pk_api_follower_set(frames, -1, palette, sizeof palette); flags(0, 0);
    pk_api_follower_set(frames, sizeof frames + 1, palette, sizeof palette); flags(0, 0);
    pk_api_follower_set(frames, sizeof frames, palette, sizeof palette + 1); flags(0, 0);
    pk_api_follower_set(frames, sizeof frames, palette, sizeof palette); flags(0, 1);
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    assets(); pk_api_follower_show(true); flags(0, 1);
    pk_api_follower_set(NULL, 0, NULL, 0); flags(0, 0);
    const char* carts[] = { "diagnostic.gb", "diagnostic.gba" };
    for (unsigned i = 0; i < 2; i++) {
        char rom[1024]; snprintf(rom, sizeof rom, "%s/%s", argv[1], carts[i]);
        CHECK(pk_api_init(rom, NULL)); flags(0, 0);
        for (int f = 0; f < 30; f++) pk_api_run_frame();
        size_t n = (size_t) pk_api_video_width() * pk_api_video_height() * 4;
        void* before = malloc(n); CHECK(before != NULL); if (!before) return 2;
        memcpy(before, pk_api_video(), n);
        assets(); pk_api_follower_show(true); flags(i == 1, 1);
        for (int f = 0; f < 10; f++) pk_api_run_frame();
        CHECK(!memcmp(before, pk_api_video(), n));
        pk_api_follower_show(false); flags(0, 1);
        pk_api_follower_set(NULL, 0, NULL, 0); flags(0, 0);
        pk_api_follower_show(true); flags(i == 1, 0);
        pk_api_deinit(); flags(0, 0);
        pk_api_follower_show(true); flags(0, 0);
        free(before);
    }
    return failures != 0;
}
