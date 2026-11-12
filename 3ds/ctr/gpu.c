#include "gpu.h"

#include <3ds.h>
#include <citro2d.h>
#include <string.h>

#include "../core/pd_ui.h"

#define GBA_W 240
#define GBA_H 160
#define TOP_W 400
#define TOP_H 240

static C3D_RenderTarget* topTarget;
static C3D_RenderTarget* bottomTarget;

// mGBA renders into gameBuf; each frame it's written into the texture the
// screen mode needs: gameTex at 1x (nearest), or game2xTex at 2x for the
// smooth modes - mGBA's "sharp bilinear", the 2x nearest copy sampled
// linearly. Everything is written by the CPU in the GPU's tiled order: no
// display transfers, no render-to-texture.
static uint32_t* gameBuf;
static uint32_t* gameBufs[2];
static uint32_t* shownBuf; // the last whole frame
static bool gameDoubled;
// Two of each in turn: a frame is written before the wait for the screen's refresh, while the GPU may still be reading
// the one before.
static C3D_Tex gameTex[2];
static C3D_Tex wideTex[2]; // EXPAND's window (up to 400 x 240)
static int texSide;
// The smooth modes' 2x picture, drawn by the GPU from the 1x one: built by the CPU it was four times the memory traffic,
// 5-7 ms of every frame, and the game felt it too.
static C3D_Tex upTex;
static C3D_RenderTarget* upTarget;
static C3D_Tex bottomTex, topTex, overlayTex, perfTex;

// A New 3DS draws on its third core, so writing the textures and waiting for the refresh come off the game's time.
// gpu_draw hands a job over: what the frame needs is copied into the stage (the game runs on and rewrites its buffers),
// and the thread takes it at the next refresh. One job at a time: a game that's ahead waits for the stage, which is
// what paces it. Without that core gpu_draw runs the job itself.
struct wide_hole {
    int x, y, w, h, gameX, gameY;
};

struct draw_job {
    bool game;
    int screenMode;
    bool holdGame, overlayShown, overlayDim;
    int perfX, perfY, perfW;
    bool perfNew;
    int gameW, gameH;
    const uint32_t* gamePx;
    const uint32_t* widePx;
    int wideW, wideH;
    struct wide_hole hole;
    const uint32_t* bottom;
    const uint32_t* top;
    const uint32_t* overlay;
};
#define DRAW_STACK (64 * 1024)
static Thread drawThread;
static Thread wideThread;
static LightEvent wideStart, wideDone;
static void (*wideWork)(void);
static volatile bool wideStop;
static LightEvent jobReady, stageFree;
static volatile unsigned jobsHanded, jobsDone;
static volatile bool drawStop;
static struct draw_job job;
static uint32_t* stageGame;
static uint32_t* stageBottom;
static uint32_t* stageTop;
static uint32_t* stageOverlay;
static aptHookCookie drawHook;
static volatile unsigned framesShown;

// Texel (x, y) of a w-wide texture in the GPU's layout: 8x8 tiles, row by
// row, Morton (Z) order inside a tile. The first row is the image's top
// (v = 1) - checked in Azahar, where writing rows bottom-up drew everything
// upside down.
static inline u32 tiled(u32 x, u32 y, u32 w) {
    return ((((y >> 3) * (w >> 3)) + (x >> 3)) << 6) |
        ((x & 1) | ((y & 1) << 1) | ((x & 2) << 1) | ((y & 2) << 2) | ((x & 4) << 2) | ((y & 4) << 3));
}

// A w x h image in the texture's first w x h texels, its top row at v = 1.
static C2D_Image image(C3D_Tex* tex, Tex3DS_SubTexture* sub, int w, int h) {
    sub->width = (u16) w;
    sub->height = (u16) h;
    sub->left = 0.0f;
    sub->top = 1.0f;
    sub->right = (float) w / tex->width;
    sub->bottom = 1.0f - (float) h / tex->height;
    return (C2D_Image) { tex, sub };
}

static bool tex_init(C3D_Tex* tex, int w, int h, GPU_TEXTURE_FILTER_PARAM filter) {
    if (!C3D_TexInit(tex, (u16) w, (u16) h, GPU_RGB565)) return false;
    C3D_TexSetFilter(tex, filter, filter);
    C3D_TexSetWrap(tex, GPU_CLAMP_TO_EDGE, GPU_CLAMP_TO_EDGE);
    memset(tex->data, 0, tex->size);
    GSPGPU_FlushDataCache(tex->data, tex->size);
    return true;
}

static void draw_thread_start(void);

bool gpu_init(void) {
    gfxInitDefault();
    if (!C3D_Init(C3D_DEFAULT_CMDBUF_SIZE)) return false;
    if (!C2D_Init(C2D_DEFAULT_MAX_OBJECTS)) return false;
    C2D_Prepare();
    topTarget = C2D_CreateScreenTarget(GFX_TOP, GFX_LEFT);
    bottomTarget = C2D_CreateScreenTarget(GFX_BOTTOM, GFX_LEFT);

    gameBuf = linearMemAlign(GPU_GAME_STRIDE * GBA_H * sizeof(uint32_t), 0x80);
    if (!gameBuf) return false;
    memset(gameBuf, 0, GPU_GAME_STRIDE * GBA_H * sizeof(uint32_t));
    gameBufs[0] = shownBuf = gameBuf;
    if (!C3D_TexInit(&perfTex, 128, 16, GPU_RGBA8)) return false;
    C3D_TexSetFilter(&perfTex, GPU_NEAREST, GPU_NEAREST);
    C3D_TexSetWrap(&perfTex, GPU_CLAMP_TO_EDGE, GPU_CLAMP_TO_EDGE);
    if (!C3D_TexInit(&overlayTex, 512, 256, GPU_RGBA8)) return false;
    C3D_TexSetFilter(&overlayTex, GPU_NEAREST, GPU_NEAREST);
    C3D_TexSetWrap(&overlayTex, GPU_CLAMP_TO_EDGE, GPU_CLAMP_TO_EDGE);
    memset(overlayTex.data, 0, overlayTex.size);
    GSPGPU_FlushDataCache(overlayTex.data, overlayTex.size);
    for (int i = 0; i < 2; i++)
        if (!(tex_init(&gameTex[i], 256, 256, GPU_NEAREST) && tex_init(&wideTex[i], 512, 256, GPU_NEAREST))) return false;
    if (!C3D_TexInitVRAM(&upTex, 1024, 512, GPU_RGB565)) return false;
    C3D_TexSetFilter(&upTex, GPU_LINEAR, GPU_LINEAR);
    C3D_TexSetWrap(&upTex, GPU_CLAMP_TO_EDGE, GPU_CLAMP_TO_EDGE);
    upTarget = C3D_RenderTargetCreateFromTex(&upTex, GPU_TEXFACE_2D, 0, -1);
    if (!upTarget) return false;
    if (!(tex_init(&bottomTex, 512, 256, GPU_NEAREST) && tex_init(&topTex, 512, 256, GPU_NEAREST))) return false;
    draw_thread_start();
    return true;
}

void gpu_exit(void) {
    if (wideThread) {
        wideStop = true;
        LightEvent_Signal(&wideStart);
        threadJoin(wideThread, U64_MAX);
        threadFree(wideThread);
    }
    if (drawThread) {
        drawStop = true;
        LightEvent_Signal(&jobReady);
        threadJoin(drawThread, U64_MAX);
        threadFree(drawThread);
        aptUnhook(&drawHook);
    }
    for (int i = 0; i < 2; i++) {
        C3D_TexDelete(&gameTex[i]);
        C3D_TexDelete(&wideTex[i]);
    }
    if (upTarget) C3D_RenderTargetDelete(upTarget);
    C3D_TexDelete(&upTex);
    C3D_TexDelete(&bottomTex);
    C3D_TexDelete(&topTex);
    C3D_TexDelete(&overlayTex);
    C3D_TexDelete(&perfTex);
    for (int i = 0; i < 2; i++)
        if (gameBufs[i]) linearFree(gameBufs[i]);
    C2D_Fini();
    C3D_Fini();
    gfxExit();
}

uint32_t* gpu_game_buffer(void) {
    return gameBuf;
}

// What shows beside the game's picture: black, or a Game Boy game's white.
static u32 barColor;

void gpu_set_bars(uint32_t rgb) {
    barColor = C2D_Color32((u8) (rgb >> 16), (u8) (rgb >> 8), (u8) rgb, 0xFF);
}

void gpu_double_game(bool on) {
    gameDoubled = on && drawThread && gameBufs[1];
    shownBuf = gameBuf;
}

uint32_t* gpu_frame_done(void) {
    shownBuf = gameBuf;
    if (gameDoubled) gameBuf = gameBuf == gameBufs[0] ? gameBufs[1] : gameBufs[0];
    return gameBuf;
}

// A 0xRRGGBB canvas into an RGB565 texture.
static void write_canvas(C3D_Tex* tex, const uint32_t* px, int w, int h) {
    uint16_t* t = tex->data;
    for (int y = 0; y < h; y++) {
        const uint32_t* row = px + y * w;
        for (int x = 0; x < w; x++) {
            uint32_t p = row[x];
            t[tiled((u32) x, (u32) y, tex->width)] =
                (uint16_t) (((p >> 19) & 0x1F) << 11 | ((p >> 10) & 0x3F) << 5 | ((p >> 3) & 0x1F));
        }
    }
    GSPGPU_FlushDataCache(tex->data, tex->size);
}

// Canvases are written when they change; C3D_FrameBegin (SYNCDRAW) has waited
// for the GPU to finish the frame that read them.
static const uint32_t* pendingBottom;
static const uint32_t* pendingTop;

void gpu_set_bottom(const uint32_t* px) {
    pendingBottom = px;
}

void gpu_set_top(const uint32_t* px) {
    pendingTop = px;
}

// mGBA's 0xXXBBGGRR as RGB565.
static inline uint16_t to565(uint32_t p) {
    return (uint16_t) ((p & 0xF8) << 8 | (p >> 5 & 0x7E0) | (p >> 19 & 0x1F));
}

// In the texture's own order, a tile at a time. The hole's pixels come from the game's frame.
static const struct wide_hole NO_HOLE;

static void write_tiles(C3D_Tex* tex, const uint32_t* px, int w, int h, int stride, const struct wide_hole* hole, const uint32_t* game) {
    static uint8_t morton[8][8];
    if (!morton[0][1])
        for (u32 y = 0; y < 8; y++)
            for (u32 x = 0; x < 8; x++) morton[y][x] = (uint8_t) tiled(x, y, 8);
    uint16_t* t16 = tex->data;
    int across = 8;
    u32 tiles = tex->width >> 3;
    int tileRows = 0;
    for (int y0 = 0; y0 < h; y0 += across, tileRows++) {
        const uint32_t* rows[8];
        const uint32_t* gameRows[8];
        int down = h - y0 < across ? h - y0 : across;
        for (int j = 0; j < down; j++) {
            int y = y0 + j;
            rows[j] = px + y * stride;
            gameRows[j] = hole->w && y >= hole->y && y < hole->y + hole->h
                              ? game + (y - hole->y + hole->gameY) * GPU_GAME_STRIDE + hole->gameX - hole->x : NULL;
        }
        u32 tile = (u32) tileRows * tiles << 6;
        for (int x0 = 0; x0 < w; x0 += across, tile += 64) {
            int wide = w - x0 < across ? w - x0 : across;
            bool inHole = x0 >= hole->x && x0 + wide <= hole->x + hole->w, offHole = x0 + wide <= hole->x || x0 >= hole->x + hole->w;
            for (int j = 0; j < down; j++) {
                const uint32_t* row = rows[j];
                const uint32_t* inGame = gameRows[j];
                if (inGame && inHole) row = inGame;
                if (!inGame || inHole || offHole) inGame = NULL;
                if (inGame && offHole) inGame = NULL;
                for (int i = 0; i < wide; i++) {
                    int x = x0 + i;
                    uint32_t p = inGame && x >= hole->x && x < hole->x + hole->w ? inGame[x] : row[x];
                    t16[tile + morton[j][i]] = to565(p);
                }
            }
        }
    }
    u32 used = (u32) tileRows * tiles * 128;
    GSPGPU_FlushDataCache(tex->data, used < tex->size ? used : tex->size);
}

static const uint32_t* widePx;
static int wideW, wideH;
static struct wide_hole wideHole;
static const uint32_t* pendingOverlay;
static bool overlayShown, overlayDim;

void gpu_set_wide(const uint32_t* px, int w, int h) {
    widePx = px;
    wideW = w;
    wideH = h;
}

void gpu_set_wide_hole(int x, int y, int w, int h, int gameX, int gameY) {
    wideHole = (struct wide_hole) { x, y, w, h, gameX, gameY };
}

void gpu_set_overlay(const uint32_t* px) {
    pendingOverlay = px;
}

static uint32_t perfPx[128 * 16], stagePerf[128 * 16];
static bool perfPending;
static int perfX, perfY, perfW;

void gpu_set_perf(const uint32_t* px, int x, int y, int w) {
    if (px) {
        memcpy(perfPx, px, sizeof(perfPx));
        perfPending = true;
    }
    perfX = x;
    perfY = y;
    perfW = w;
}

static void write_perf(const uint32_t* px) {
    uint32_t* t = perfTex.data;
    for (int y = 0; y < 16; y++)
        for (int x = 0; x < 128; x++) {
            uint32_t p = px[y * 128 + x];
            t[tiled((u32) x, (u32) y, perfTex.width)] = (p & 0xFF000000) >> 24 | (p & 0xFF) << 8 | (p & 0xFF00) << 8 | (p & 0xFF0000) << 8;
        }
    GSPGPU_FlushDataCache(perfTex.data, perfTex.size);
}

void gpu_show_overlay(bool shown, bool dim) {
    overlayShown = shown;
    overlayDim = dim;
}

// 0xAARRGGBB into the RGBA8 texture (the GPU reads its bytes A, B, G, R).
static void write_overlay(const uint32_t* px) {
    uint32_t* t = overlayTex.data;
    for (int y = 0; y < TOP_H; y++) {
        const uint32_t* row = px + y * TOP_W;
        for (int x = 0; x < TOP_W; x++) {
            uint32_t p = row[x];
            t[tiled((u32) x, (u32) y, overlayTex.width)] = (p & 0xFF000000) >> 24 | (p & 0xFF) << 8 | (p & 0xFF00) << 8 | (p & 0xFF0000) << 8;
        }
    }
    GSPGPU_FlushDataCache(overlayTex.data, overlayTex.size);
}

static bool holdGame;
static int gameW = GBA_W, gameH = GBA_H;

void gpu_set_game_size(int w, int h) {
    gameW = w;
    gameH = h;
}

void gpu_hold_game(bool hold) {
    holdGame = hold;
}

// The dev build's split of the draw jobs (ticks): writing the game's texture, the canvases, waiting in FrameBegin, the rest.
volatile u64 gpuTexTicks, gpuCanvasTicks, gpuBeginTicks, gpuRestTicks;
static void run_job(const struct draw_job* j, bool threaded) {
    u64 tA = svcGetSystemTick();
    bool game = j->game;
    int screenMode = j->screenMode, gameW = j->gameW, gameH = j->gameH, wideW = j->wideW, wideH = j->wideH;
    bool holdGame = j->holdGame;
    const uint32_t* widePx = j->widePx;
    const uint32_t* gamePx = j->gamePx;
    // The frame first, so the game can hand over the next one while this one waits for the screen's refresh.
    if (game && !holdGame) {
        texSide ^= 1;
        if (widePx) {
            gpu_wide_join();
            write_tiles(&wideTex[texSide], widePx, wideW, wideH, wideW, &j->hole, gamePx);
        } else {
            write_tiles(&gameTex[texSide], gamePx, gameW, gameH, GPU_GAME_STRIDE, &NO_HOLE, NULL);
        }
    }
    bool canvases = j->bottom || j->top || j->overlay || j->perfNew;
    if (threaded && !canvases) LightEvent_Signal(&stageFree);
    u64 tB = svcGetSystemTick();
    C3D_FrameBegin(C3D_FRAME_SYNCDRAW);
    u64 tC = svcGetSystemTick();
    if (j->bottom) write_canvas(&bottomTex, j->bottom, PD_UI_WIDTH, PD_UI_HEIGHT);
    if (j->top) write_canvas(&topTex, j->top, TOP_W, TOP_H);
    if (j->overlay) write_overlay(j->overlay);
    if (j->perfNew) write_perf(stagePerf);
    if (threaded && canvases) LightEvent_Signal(&stageFree);
    u64 tD = svcGetSystemTick();
    Tex3DS_SubTexture sub, upSub;
    u32 black = C2D_Color32(0, 0, 0, 0xFF);
    // The smooth modes: the frame at 2x nearest, sampled linearly down to size (mGBA's "sharp bilinear").
    bool sharp = game && (widePx ? wideW < TOP_W : screenMode != PD_SCREEN_PIXEL);
    int srcW = widePx ? wideW : gameW, srcH = widePx ? wideH : gameH;
    C3D_Tex* src = widePx ? &wideTex[texSide] : &gameTex[texSide];
    if (sharp) {
        C2D_SceneBegin(upTarget);
        C2D_DrawImageAt(image(src, &sub, srcW, srcH), 0, 0, 0.5f, NULL, 2.0f, 2.0f);
    }
    C2D_TargetClear(topTarget, game && barColor ? barColor : black);
    C2D_SceneBegin(topTarget);
    if (!game) {
        C2D_DrawImageAt(image(&topTex, &sub, TOP_W, TOP_H), 0, 0, 0.5f, NULL, 1.0f, 1.0f);
    } else if (!sharp) {
        C2D_DrawImageAt(image(src, &sub, srcW, srcH), (TOP_W - srcW) / 2.0f, (TOP_H - srcH) / 2.0f, 0.5f, NULL, 1.0f, 1.0f);
    } else {
        C2D_Image up = image(&upTex, &upSub, 2 * srcW, 2 * srcH);
        if (widePx) {
            float k = (float) TOP_H / (2 * wideH);
            C2D_DrawImageAt(up, (TOP_W - 2 * wideW * k) / 2, 0, 0.5f, NULL, k, k);
        } else if (screenMode == PD_SCREEN_STRETCH) {
            C2D_DrawImageAt(up, 0, 0, 0.5f, NULL, (float) TOP_W / (2 * gameW), (float) TOP_H / (2 * gameH));
        } else {
            // The screen's height (a GBA game at 1.5x), or 1.25x whatever the game.
            float s = screenMode == PD_SCREEN_SHARP_125 ? 0.625f : (float) TOP_H / (2 * gameH);
            C2D_DrawImageAt(up, (TOP_W - 2 * gameW * s) / 2, (TOP_H - 2 * gameH * s) / 2, 0.5f, NULL, s, s);
        }
    }
    if (game && j->overlayDim) C2D_DrawRectSolid(0, 0, 0.6f, TOP_W, TOP_H, C2D_Color32(0, 0, 0, 0x55));
    if (game && j->overlayShown) {
        Tex3DS_SubTexture osub;
        C2D_DrawImageAt(image(&overlayTex, &osub, TOP_W, TOP_H), 0, 0, 0.7f, NULL, 1.0f, 1.0f);
    }
    if (game && j->perfW) {
        Tex3DS_SubTexture psub;
        C2D_DrawImageAt(image(&perfTex, &psub, j->perfW, 16), j->perfX, j->perfY, 0.8f, NULL, 1.0f, 1.0f);
    }

    Tex3DS_SubTexture bottomSub;
    C2D_TargetClear(bottomTarget, black);
    C2D_SceneBegin(bottomTarget);
    C2D_DrawImageAt(image(&bottomTex, &bottomSub, PD_UI_WIDTH, PD_UI_HEIGHT), 0, 0, 0.5f, NULL, 1.0f, 1.0f);
    C3D_FrameEnd(0);
    framesShown++;
    u64 tE = svcGetSystemTick();
    gpuTexTicks += tB - tA;
    gpuBeginTicks += tC - tB;
    gpuCanvasTicks += tD - tC;
    gpuRestTicks += tE - tD;
}

unsigned gpu_frames_shown(void) {
    return framesShown;
}

// The fourth core's work, in ticks (the dev build's stat).
volatile u64 gpuWideTicks, gpuDrawTicks;

static void draw_main(void* arg) {
    (void) arg;
    for (;;) {
        LightEvent_Wait(&jobReady);
        if (drawStop) return;
        // Its own copy: the stage goes free halfway through, and the next job can be written then.
        struct draw_job mine = job;
        u64 t0 = svcGetSystemTick();
        run_job(&mine, true);
        gpuDrawTicks += svcGetSystemTick() - t0;
        jobsDone++;
    }
}

// HOME, the lid, closing: citro3d's own hook expects no frame under way.
static void draw_apt_hook(APT_HookType hook, void* param) {
    (void) param;
    if (hook == APTHOOK_ONSUSPEND || hook == APTHOOK_ONSLEEP || hook == APTHOOK_ONEXIT)
        while (jobsDone != jobsHanded) svcSleepThread(1000000);
}

static void wide_main(void* arg) {
    (void) arg;
    for (;;) {
        LightEvent_Wait(&wideStart);
        if (wideStop) return;
        u64 t0 = svcGetSystemTick();
        wideWork();
        gpuWideTicks += svcGetSystemTick() - t0;
        LightEvent_Signal(&wideDone);
    }
}

bool gpu_wide_thread(void (*work)(void)) {
    if (wideThread) return true;
    if (!drawThread) return false;
    wideWork = work;
    LightEvent_Init(&wideStart, RESET_ONESHOT);
    LightEvent_Init(&wideDone, RESET_STICKY);
    LightEvent_Signal(&wideDone);
    s32 priority = 0x30;
    svcGetThreadPriority(&priority, CUR_THREAD_HANDLE);
    wideThread = threadCreate(wide_main, NULL, DRAW_STACK, priority - 2, 3, false);
    return wideThread != NULL;
}

void gpu_wide_go(void) {
    LightEvent_Clear(&wideDone);
    LightEvent_Signal(&wideStart);
}

void gpu_wide_join(void) {
    if (wideThread) LightEvent_Wait(&wideDone);
}

static void draw_thread_start(void) {
    bool isNew = false;
    APT_CheckNew3DS(&isNew);
    if (!isNew) return;
    stageGame = linearAlloc(GPU_GAME_STRIDE * GBA_H * sizeof(uint32_t));
    stageBottom = linearAlloc(PD_UI_WIDTH * PD_UI_HEIGHT * sizeof(uint32_t));
    stageTop = linearAlloc(TOP_W * TOP_H * sizeof(uint32_t));
    stageOverlay = linearAlloc(TOP_W * TOP_H * sizeof(uint32_t));
    gameBufs[1] = linearMemAlign(GPU_GAME_STRIDE * GBA_H * sizeof(uint32_t), 0x80);
    if (!stageGame || !stageBottom || !stageTop || !stageOverlay) return;
    LightEvent_Init(&jobReady, RESET_ONESHOT);
    LightEvent_Init(&stageFree, RESET_ONESHOT);
    LightEvent_Signal(&stageFree);
    s32 priority = 0x30;
    svcGetThreadPriority(&priority, CUR_THREAD_HANDLE);
    // The New 3DS's fourth core, beside armGBA's mixer (it sleeps between batches); mGBA's renderer and armGBA's render
    // worker have the third. armGBA's second worker is kept off (pd_armgba.c): beside it this thread starved.
    drawThread = threadCreate(draw_main, NULL, DRAW_STACK, priority - 1, 3, false);
    if (!drawThread) drawThread = threadCreate(draw_main, NULL, DRAW_STACK, priority - 1, 2, false);
    if (drawThread) aptHook(&drawHook, draw_apt_hook, NULL);
}

void gpu_draw(bool game, int screenMode) {
    struct draw_job next = { game, screenMode, holdGame, overlayShown, overlayDim, perfX, perfY, perfW, false, gameW, gameH, shownBuf,
                             widePx, wideW, wideH, wideHole, pendingBottom, pendingTop, pendingOverlay };
    pendingBottom = pendingTop = pendingOverlay = NULL;
    if (!drawThread) {
        next.perfNew = perfPending;
        if (perfPending) memcpy(stagePerf, perfPx, sizeof(stagePerf));
        perfPending = false;
        run_job(&next, false);
        return;
    }
    LightEvent_Wait(&stageFree);
    next.perfNew = perfPending;
    if (perfPending) memcpy(stagePerf, perfPx, sizeof(stagePerf));
    perfPending = false;
    // No copies of a frame the next one won't touch: the game's other buffer, the session's other window (wideFrames).
    if (!gameDoubled) {
        if (game && !holdGame && (!widePx || wideHole.w)) memcpy(stageGame, gameBuf, GPU_GAME_STRIDE * (size_t) gameH * sizeof(uint32_t));
        next.gamePx = stageGame;
    }
    if (next.bottom) next.bottom = memcpy(stageBottom, next.bottom, PD_UI_WIDTH * PD_UI_HEIGHT * sizeof(uint32_t));
    if (next.top) next.top = memcpy(stageTop, next.top, TOP_W * TOP_H * sizeof(uint32_t));
    if (next.overlay) next.overlay = memcpy(stageOverlay, next.overlay, TOP_W * TOP_H * sizeof(uint32_t));
    job = next;
    jobsHanded++;
    LightEvent_Signal(&jobReady);
}
