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
static C3D_Tex gameTex, game2xTex;
static C3D_Tex wideTex, wide2xTex; // EXPAND's window at 1x (up to 400 x 240), and 2x for the smooth modes
static C3D_Tex bottomTex, topTex, overlayTex;

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
    if (!C3D_TexInit(&overlayTex, 512, 256, GPU_RGBA8)) return false;
    C3D_TexSetFilter(&overlayTex, GPU_NEAREST, GPU_NEAREST);
    C3D_TexSetWrap(&overlayTex, GPU_CLAMP_TO_EDGE, GPU_CLAMP_TO_EDGE);
    memset(overlayTex.data, 0, overlayTex.size);
    GSPGPU_FlushDataCache(overlayTex.data, overlayTex.size);
    return tex_init(&gameTex, 256, 256, GPU_NEAREST) && tex_init(&game2xTex, 512, 512, GPU_LINEAR) &&
        tex_init(&wideTex, 512, 256, GPU_NEAREST) && tex_init(&wide2xTex, 1024, 512, GPU_LINEAR) &&
        tex_init(&bottomTex, 512, 256, GPU_NEAREST) && tex_init(&topTex, 512, 256, GPU_NEAREST);
}

void gpu_exit(void) {
    C3D_TexDelete(&gameTex);
    C3D_TexDelete(&game2xTex);
    C3D_TexDelete(&bottomTex);
    C3D_TexDelete(&topTex);
    C3D_TexDelete(&wideTex);
    C3D_TexDelete(&wide2xTex);
    C3D_TexDelete(&overlayTex);
    if (gameBuf) linearFree(gameBuf);
    C2D_Fini();
    C3D_Fini();
    gfxExit();
}

uint32_t* gpu_game_buffer(void) {
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

static void write_1x(C3D_Tex* tex, const uint32_t* px, int w, int h, int stride) {
    uint16_t* t = tex->data;
    for (int y = 0; y < h; y++) {
        const uint32_t* row = px + y * stride;
        for (int x = 0; x < w; x++) t[tiled((u32) x, (u32) y, tex->width)] = to565(row[x]);
    }
    GSPGPU_FlushDataCache(tex->data, tex->size);
}

// Each game pixel becomes a 2x2 block - in the tiled order an aligned 2x2
// block is 4 consecutive texels, so one 64-bit store.
static void write_2x(C3D_Tex* tex, const uint32_t* px, int w, int h, int stride) {
    uint64_t* t = tex->data;
    for (int y = 0; y < h; y++) {
        const uint32_t* row = px + y * stride;
        for (int x = 0; x < w; x++) {
            uint64_t c = to565(row[x]);
            t[tiled((u32) (2 * x), (u32) (2 * y), tex->width) >> 2] = c | c << 16 | c << 32 | c << 48;
        }
    }
    GSPGPU_FlushDataCache(tex->data, tex->size);
}

static const uint32_t* widePx;
static int wideW, wideH;
static const uint32_t* pendingOverlay;
static bool overlayShown, overlayDim;

void gpu_set_wide(const uint32_t* px, int w, int h) {
    widePx = px;
    wideW = w;
    wideH = h;
}

void gpu_set_overlay(const uint32_t* px) {
    pendingOverlay = px;
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

void gpu_draw(bool game, int screenMode) {
    C3D_FrameBegin(C3D_FRAME_SYNCDRAW);
    if (pendingBottom) {
        write_canvas(&bottomTex, pendingBottom, PD_UI_WIDTH, PD_UI_HEIGHT);
        pendingBottom = NULL;
    }
    if (pendingTop) {
        write_canvas(&topTex, pendingTop, TOP_W, TOP_H);
        pendingTop = NULL;
    }
    if (pendingOverlay) {
        write_overlay(pendingOverlay);
        pendingOverlay = NULL;
    }
    Tex3DS_SubTexture sub;
    u32 black = C2D_Color32(0, 0, 0, 0xFF);
    C2D_TargetClear(topTarget, black);
    C2D_SceneBegin(topTarget);
    if (!game) {
        C2D_DrawImageAt(image(&topTex, &sub, TOP_W, TOP_H), 0, 0, 0.5f, NULL, 1.0f, 1.0f);
    } else if (widePx) {
        // EXPAND: its window fills the screen, at 1x or sharp bilinear like the game.
        if (wideW >= TOP_W) {
            if (!holdGame) write_1x(&wideTex, widePx, wideW, wideH, wideW);
            C2D_DrawImageAt(image(&wideTex, &sub, wideW, wideH), (TOP_W - wideW) / 2.0f, (TOP_H - wideH) / 2.0f, 0.5f, NULL, 1.0f, 1.0f);
        } else {
            if (!holdGame) write_2x(&wide2xTex, widePx, wideW, wideH, wideW);
            float k = (float) TOP_H / (2 * wideH);
            C2D_DrawImageAt(image(&wide2xTex, &sub, 2 * wideW, 2 * wideH), (TOP_W - 2 * wideW * k) / 2, 0, 0.5f, NULL, k, k);
        }
    } else if (screenMode == PD_SCREEN_PIXEL) {
        if (!holdGame) write_1x(&gameTex, gameBuf, gameW, gameH, GPU_GAME_STRIDE);
        C2D_DrawImageAt(image(&gameTex, &sub, gameW, gameH), (TOP_W - gameW) / 2, (TOP_H - gameH) / 2, 0.5f, NULL,
                        1.0f, 1.0f);
    } else {
        if (!holdGame) write_2x(&game2xTex, gameBuf, gameW, gameH, GPU_GAME_STRIDE);
        C2D_Image up = image(&game2xTex, &sub, 2 * gameW, 2 * gameH);
        if (screenMode == PD_SCREEN_STRETCH) {
            C2D_DrawImageAt(up, 0, 0, 0.5f, NULL, (float) TOP_W / (2 * gameW), (float) TOP_H / (2 * gameH));
        } else {
            float s = (float) TOP_H / (2 * gameH); // the screen's height: a GBA game at 1.5x
            C2D_DrawImageAt(up, (TOP_W - 2 * gameW * s) / 2, 0, 0.5f, NULL, s, s);
        }
    }
    if (game && overlayDim) C2D_DrawRectSolid(0, 0, 0.6f, TOP_W, TOP_H, C2D_Color32(0, 0, 0, 0x55));
    if (game && overlayShown) {
        Tex3DS_SubTexture osub;
        C2D_DrawImageAt(image(&overlayTex, &osub, TOP_W, TOP_H), 0, 0, 0.7f, NULL, 1.0f, 1.0f);
    }

    Tex3DS_SubTexture bottomSub;
    C2D_TargetClear(bottomTarget, black);
    C2D_SceneBegin(bottomTarget);
    C2D_DrawImageAt(image(&bottomTex, &bottomSub, PD_UI_WIDTH, PD_UI_HEIGHT), 0, 0, 0.5f, NULL, 1.0f, 1.0f);
    C3D_FrameEnd(0);
}
