/* PokeDaisy as a libretro core: mGBA's own libretro core (MPL-2.0, third_party/mgba, included below
 * unchanged) with the companion layered over it. GPL-3.0, see ../NOTICE.
 *
 * mGBA's entry points are renamed with the preprocessor while its file is included, so this file can
 * define the real retro_* functions around them and reach its static state (core, data, outputBuffer)
 * without editing a line of upstream. */
#define retro_set_environment mgba_retro_set_environment
#define retro_set_video_refresh mgba_retro_set_video_refresh
#define retro_set_input_state mgba_retro_set_input_state
#define retro_get_system_info mgba_retro_get_system_info
#define retro_get_system_av_info mgba_retro_get_system_av_info
#define retro_run mgba_retro_run
#define retro_load_game mgba_retro_load_game
#define retro_unload_game mgba_retro_unload_game
#define retro_unserialize mgba_retro_unserialize
#ifdef __ARM_NEON
#include <arm_neon.h>
#endif
#include "libretro.c"
#undef retro_set_environment
#undef retro_set_video_refresh
#undef retro_set_input_state
#undef retro_get_system_info
#undef retro_get_system_av_info
#undef retro_run
#undef retro_load_game
#undef retro_unload_game
#undef retro_unserialize

#include "field_items.h"
#include "fly.h"
#include "hud.h"
#include "pd_guide.h"
#include "pk_call.h"
#include "pk_wide.h"
#include "quick_menu.h"
#include "wild.h"
#include "pd_dex.h"
#include "pd_map.h"
#include "pd_menu.h"
#include "pd_romart.h"
#include "pd_settings.h"
#include "pd_ui.h"
#include "pd_ui_internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

bool pd_lr_read_memory(void *ctx, uint32_t a, void *out, size_t n);

/* ---- keys: the retropad, as the SDL host's key enum numbers them (A..L are mGBA's own key bits) ---- */
enum { A, B, SELECT, START, RIGHT, LEFT, UP, DOWN, R, L, X, Y, L2, R2, MENU, L3, NKEYS };
static const unsigned retroId[NKEYS] = {
    RETRO_DEVICE_ID_JOYPAD_A,     RETRO_DEVICE_ID_JOYPAD_B,    RETRO_DEVICE_ID_JOYPAD_SELECT, RETRO_DEVICE_ID_JOYPAD_START,
    RETRO_DEVICE_ID_JOYPAD_RIGHT, RETRO_DEVICE_ID_JOYPAD_LEFT, RETRO_DEVICE_ID_JOYPAD_UP,     RETRO_DEVICE_ID_JOYPAD_DOWN,
    RETRO_DEVICE_ID_JOYPAD_R,     RETRO_DEVICE_ID_JOYPAD_L,    RETRO_DEVICE_ID_JOYPAD_X,      RETRO_DEVICE_ID_JOYPAD_Y,
    RETRO_DEVICE_ID_JOYPAD_L2,    RETRO_DEVICE_ID_JOYPAD_R2,   RETRO_DEVICE_ID_JOYPAD_SELECT, RETRO_DEVICE_ID_JOYPAD_L3};

/* ---- the options this layer adds to mGBA's ---- */
#define PD_OPTION_COUNT 2
static const struct retro_core_option_v2_definition pdOptions[PD_OPTION_COUNT] = {
    {"pokedaisy_canvas", "Companion canvas", NULL, "The frame size the core reports: the game sits in it at a whole scale, the companion fills it. auto = 320x240.", NULL, NULL,
     {{"auto", NULL}, {"320x240", NULL}, {"341x256", NULL}, {"400x300", NULL}, {"427x240", NULL}, {NULL, NULL}}, "auto"},
    {"pokedaisy_panel", "Panel size", NULL, "The frontend's screen. With it the core picks its own canvas: EXPAND at the game's whole scale, ZOOM OUT one scale less. auto = the canvas option alone.", NULL, NULL,
     {{"auto", NULL}, {"640x480", NULL}, {"720x480", NULL}, {"720x720", NULL}, {"960x544", NULL}, {"1024x768", NULL}, {"1280x720", NULL}, {"1600x1440", NULL}, {"1920x1080", NULL}, {NULL, NULL}}, "auto"},
};

static retro_environment_t realEnv;
/* diatom's "behind schedule" signal, given just before each frame (its frame skip): this frame's heavy drawing is left out. */
static bool frameLate;
static void audio_status(bool active, unsigned occupancy, bool underrun) {
  (void)active;
  (void)occupancy;
  frameLate = underrun;
}
static retro_video_refresh_t realVideo;
static retro_input_state_t realInput;
static bool optionsDirty;

static const char *option(const char *key) {
  struct retro_variable v = {key, NULL};
  return realEnv && realEnv(RETRO_ENVIRONMENT_GET_VARIABLE, &v) && v.value ? v.value : NULL;
}

/* mGBA defines its options in one call; ours go in with them (an environment may take only one). */
static bool env_wrap(unsigned cmd, void *data) {
  if ((cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL || cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2) && data) {
    const struct retro_core_options_v2 *us =
        cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL ? ((struct retro_core_options_v2_intl *)data)->us : data;
    size_t n = 0;
    while (us && us->definitions[n].key)
      n++;
    struct retro_core_option_v2_definition *defs = calloc(n + PD_OPTION_COUNT + 1, sizeof *defs);
    if (defs && us) {
      memcpy(defs, us->definitions, n * sizeof *defs);
      memcpy(defs + n, pdOptions, sizeof pdOptions);
      static struct retro_core_options_v2 merged;
      static struct retro_core_options_v2_intl mergedIntl;
      merged = (struct retro_core_options_v2){us->categories, defs};
      mergedIntl = (struct retro_core_options_v2_intl){&merged, NULL};
      return realEnv(cmd, cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL ? (void *)&mergedIntl : (void *)&merged);
    }
  }
  if (cmd == RETRO_ENVIRONMENT_GET_VARIABLE && data && !strcmp(((struct retro_variable *)data)->key, "mgba_sgb_borders")) {
    // EXPAND needs the Game Boy's own 160x144, so a Super Game Boy border never frames it (as the SDL host's pk_gb_config).
    ((struct retro_variable *)data)->value = "OFF";
    return true;
  }
  bool r = realEnv(cmd, data);
  if (cmd == RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE && data && *(bool *)data)
    optionsDirty = true;
  return r;
}

/* ---- the frame: the canvas the frontend sees, and where the game and the overlays are drawn ---- */
#define CANVAS_MAX_W 640
#define CANVAS_MAX_H 480
static int CW = 320, CH = 240;       // the canvas now (follows the aspect, see canvas_update)
static int CWMAX = 320, CHMAX = 240; // the largest canvas: what the buffers hold and what the frontend is told is the maximum
static int panelW, panelH;           // the frontend's screen, 0 = unknown (the canvas option alone then)
static int baseGW = 240, baseGH = 160; // the game's frame before the first picture
static uint32_t *cvs;     // the companion's canvas, 0xRRGGBB
static uint16_t *out565;  // what the frontend gets
static struct pd_canvas canvas;
static unsigned gameW, gameH;
static bool gameShown;

static char message[128];
static int messageFrames;
static void notice(const char *s) {
  snprintf(message, sizeof message, "%s", s);
  messageFrames = 90;
  fprintf(stderr, "pokedaisy: %s\n", s);
}

static inline uint32_t from565(uint16_t p) {
  uint32_t r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
  return ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
}
static inline uint16_t to565(uint32_t p) {
  return (uint16_t)(((p >> 8) & 0xf800) | ((p >> 5) & 0x07e0) | ((p >> 3) & 0x001f));
}


/* Frame cost by section, for finding what makes a mode slow: compiled in only with -DPD_PROFILE. */
#ifdef PD_PROFILE
#include <time.h>
static double pf_now(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return (double)t.tv_sec * 1e3 + (double)t.tv_nsec / 1e6;
}
enum { PF_RUN, PF_WIDE, PF_DRAW, PF_PRESENT, PF_TOTAL, PF_CONV, PF_VIEW, PF_N };
static double pfSum[PF_N], pfMax[PF_N];
static int pfFrames;
#define PF_T(v) double v = pf_now()
static int pfLate;
/* Distributions, not averages: core time of each full frame (ms) and the wall gap between retro_run calls. */
static int pfHist[6], pfGap[5];
static double pfPrev;
static void pf_hist(double ms) { pfHist[ms < 10 ? 0 : ms < 13 ? 1 : ms < 15 ? 2 : ms < 16.7 ? 3 : ms < 20 ? 4 : 5]++; }
static void pf_gap(double now) {
  if (pfPrev > 0) { double g = now - pfPrev; pfGap[g < 15 ? 0 : g < 18.5 ? 1 : g < 25 ? 2 : g < 40 ? 3 : 4]++; }
  pfPrev = now;
}
#define PF_SKIPPED() do { double te_ = pf_now(); pf_add(PF_TOTAL, te_ - t0); pfLate++; } while (0)
static void pf_add(int i, double ms) {
  pfSum[i] += ms;
  if (ms > pfMax[i]) pfMax[i] = ms;
}
static void pf_frame(void) {
  if (++pfFrames < 600)
    return;
  fprintf(stderr, "pokedaisy: prof %d frames (%d skipped), ms avg/max: mgba %.2f/%.1f  wide %.2f/%.1f  draw %.2f/%.1f  present %.2f/%.1f  total %.2f/%.1f\n", pfFrames, pfLate,
          pfSum[PF_RUN] / pfFrames, pfMax[PF_RUN], pfSum[PF_WIDE] / pfFrames, pfMax[PF_WIDE], pfSum[PF_DRAW] / pfFrames, pfMax[PF_DRAW],
          pfSum[PF_PRESENT] / pfFrames, pfMax[PF_PRESENT], pfSum[PF_TOTAL] / pfFrames, pfMax[PF_TOTAL]);
  fprintf(stderr, "pokedaisy: prof core-ms hist <10:%d 10-13:%d 13-15:%d 15-16.7:%d 16.7-20:%d >20:%d | call-gap <15:%d 15-18.5:%d 18.5-25:%d 25-40:%d >40:%d\n", pfHist[0], pfHist[1], pfHist[2], pfHist[3], pfHist[4], pfHist[5],
          pfGap[0], pfGap[1], pfGap[2], pfGap[3], pfGap[4]);
  memset(pfHist, 0, sizeof pfHist);
  memset(pfGap, 0, sizeof pfGap);
  fprintf(stderr, "pokedaisy: prof detail ms avg: wide-convert %.2f  pk_wide_view %.2f\n", pfSum[PF_CONV] / pfFrames, pfSum[PF_VIEW] / pfFrames);
  memset(pfSum, 0, sizeof pfSum);
  memset(pfMax, 0, sizeof pfMax);
  pfFrames = 0;
  pfLate = 0;
}
#else
#define PF_T(v) (void)0
#define pf_hist(ms) ((void)0)
#define pf_gap(now) ((void)0)
#define PF_SKIPPED() (void)0
#define pf_add(i, ms) ((void)0)
#define pf_frame() ((void)0)
enum { PF_RUN, PF_WIDE, PF_DRAW, PF_PRESENT, PF_TOTAL, PF_CONV, PF_VIEW };
#endif

/* EXPAND (the game-code layer, below): the window it rebuilt past the game's edges, when this frame has one. */
static bool wideSupported, wideReady, wideHave, fieldKnown; // wideHave: wideFrame holds a window (from the last full frame)
static unsigned wideW, wideH;
static uint32_t wideSource[240 * 160], wideFrame[PK_WIDE_MAX_VIEW_W * PK_WIDE_MAX_VIEW_H];
static uint32_t wildSwitch;
static inline uint32_t to_xbgr(uint16_t p) {
  uint32_t r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
  g |= g >> 5;   // mGBA's 565 leaves green's low bit 0; the margins (pk_wide color(), mColorFrom555) have g5>>4 there: match them or the middle's edge shows a seam
  return 0xFF000000u | ((b << 3 | b >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (r << 3 | r >> 2);
}
static inline uint32_t xbgr_to_rgb(uint32_t p) { return (p & 255) << 16 | (p >> 8 & 255) << 8 | (p >> 16 & 255); }

/* The game, whole-scale and centred, over a black canvas. */
static void draw_game(void) {
  memset(cvs, 0, (size_t)CW * CH * sizeof *cvs);
  if (!outputBuffer || !gameW || !gameH)
    return;
  if (wideReady) {
    int ws = CW / (int)wideW < CH / (int)wideH ? CW / (int)wideW : CH / (int)wideH;
    if (ws < 1)
      ws = 1;
    int wx = (CW - (int)wideW * ws) / 2, wy = (CH - (int)wideH * ws) / 2;
    for (unsigned y = 0; y < wideH; y++)
      for (int k = 0; k < ws; k++) {
        int cy = wy + (int)y * ws + k;
        if (cy < 0 || cy >= CH)
          continue;
        for (unsigned x = 0; x < wideW; x++) {
          uint32_t p = xbgr_to_rgb(wideFrame[y * wideW + x]);
          for (int j = 0; j < ws; j++) {
            int cx = wx + (int)x * ws + j;
            if (cx >= 0 && cx < CW)
              cvs[(size_t)cy * CW + cx] = p;
          }
        }
      }
    return;
  }
  int s = CW / (int)gameW < CH / (int)gameH ? CW / (int)gameW : CH / (int)gameH;
  if (s < 1)
    s = 1;
  int ox = (CW - (int)gameW * s) / 2, oy = (CH - (int)gameH * s) / 2;
  for (unsigned y = 0; y < gameH; y++) {
    uint32_t *row = cvs + (size_t)(oy + (int)y * s) * CW + ox;
    if (oy + (int)y * s < 0 || oy + ((int)y + 1) * s > CH)
      continue;
    for (unsigned x = 0; x < gameW; x++) {
      uint32_t p = from565(outputBuffer[y * 256 + x]);
      for (int k = 0; k < s; k++)
        row[x * s + k] = p;
    }
    for (int k = 1; k < s; k++)
      memcpy(row + (size_t)k * CW, row, (size_t)gameW * s * sizeof *row);
  }
}


/* The usual frame (nothing over it but the HUD) goes straight into the frontend's 565 buffer, never through the 32-bit
 * canvas: that detour costs two passes over every pixel of the window. */
static bool direct565;
static inline uint16_t xbgr_to565(uint32_t p) { return (uint16_t)(((p & 0xF8) << 8) | ((p >> 5) & 0x07E0) | ((p >> 19) & 0x1F)); }
/* n pixels of XBGR into 565, 16 at a time on NEON (R,G,B are bytes 0,1,2 of each pixel). */
static void row_to565(uint16_t *dst, const uint32_t *src, unsigned n) {
  unsigned x = 0;
#ifdef __ARM_NEON
  for (; x + 16 <= n; x += 16) {
    uint8x16x4_t p = vld4q_u8((const uint8_t *)(src + x));
    uint8x16x2_t o;
    o.val[1] = vorrq_u8(vandq_u8(p.val[0], vdupq_n_u8(0xF8)), vshrq_n_u8(p.val[1], 5));
    o.val[0] = vorrq_u8(vandq_u8(vshlq_n_u8(p.val[1], 3), vdupq_n_u8(0xE0)), vshrq_n_u8(p.val[2], 3));
    vst2q_u8((uint8_t *)(dst + x), o);
  }
#endif
  for (; x < n; x++)
    dst[x] = xbgr_to565(src[x]);
}
static void draw_game_fast(void) {
  memset(out565, 0, (size_t)CW * CH * sizeof *out565);
  if (!outputBuffer || !gameW || !gameH)
    return;
  if (wideReady) {
    int ws = CW / (int)wideW < CH / (int)wideH ? CW / (int)wideW : CH / (int)wideH;
    if (ws < 1)
      ws = 1;
    int wx = (CW - (int)wideW * ws) / 2, wy = (CH - (int)wideH * ws) / 2;
    for (unsigned y = 0; y < wideH; y++) {
      int cy = wy + (int)y * ws;
      if (cy < 0 || cy + ws > CH)
        continue;
      uint16_t *row = out565 + (size_t)cy * CW + wx;
      const uint32_t *src = wideFrame + (size_t)y * wideW;
      if (ws == 1) {
        row_to565(row, src, wideW);
      } else {
        for (unsigned x = 0; x < wideW; x++)
          for (int j = 0; j < ws; j++)
            row[x * ws + j] = xbgr_to565(src[x]);
        for (int k = 1; k < ws; k++)
          memcpy(row + (size_t)k * CW, row, (size_t)wideW * ws * sizeof *row);
      }
    }
    return;
  }
  int s = CW / (int)gameW < CH / (int)gameH ? CW / (int)gameW : CH / (int)gameH;
  if (s < 1)
    s = 1;
  int ox = (CW - (int)gameW * s) / 2, oy = (CH - (int)gameH * s) / 2;
  for (unsigned y = 0; y < gameH; y++) {
    int cy = oy + (int)y * s;
    if (cy < 0 || cy + s > CH)
      continue;
    uint16_t *row = out565 + (size_t)cy * CW + ox;
    if (s == 1) {
      memcpy(row, outputBuffer + (size_t)y * 256, (size_t)gameW * sizeof *row);
    } else {
      for (unsigned x = 0; x < gameW; x++)
        for (int j = 0; j < s; j++)
          row[x * s + j] = outputBuffer[y * 256 + x];
      for (int k = 1; k < s; k++)
        memcpy(row + (size_t)k * CW, row, (size_t)gameW * s * sizeof *row);
    }
  }
}

/* An overlay drawn over QUICK_CLEAR and finished (quick_finish): its alpha laid over the canvas at the
 * given integer scale, its top-left at (x, y) in canvas pixels. */
static void blend_overlay(const uint32_t *px, int w, int h, int scale, int x0, int y0) {
  for (int y = 0; y < h * scale; y++) {
    int cy = y0 + y;
    if (cy < 0 || cy >= CH)
      continue;
    for (int x = 0; x < w * scale; x++) {
      int cx = x0 + x;
      if (cx < 0 || cx >= CW)
        continue;
      uint32_t p = px[(y / scale) * w + x / scale], a = p >> 24;
      if (!a)
        continue;
      if (direct565) {
        uint16_t *d16 = &out565[(size_t)cy * CW + cx];
        if (a == 255) {
          *d16 = to565(p & 0xffffff);
        } else {
          uint32_t c = from565(*d16);
          uint32_t r = ((c >> 16 & 255) * (255 - a) + (p >> 16 & 255) * a) / 255, g = ((c >> 8 & 255) * (255 - a) + (p >> 8 & 255) * a) / 255,
                   b = ((c & 255) * (255 - a) + (p & 255) * a) / 255;
          *d16 = to565(r << 16 | g << 8 | b);
        }
        continue;
      }
      uint32_t *d = &cvs[(size_t)cy * CW + cx];
      if (a == 255) {
        *d = p & 0xffffff;
      } else {
        uint32_t dr = *d >> 16 & 255, dg = *d >> 8 & 255, db = *d & 255;
        uint32_t r = (dr * (255 - a) + (p >> 16 & 255) * a) / 255, g = (dg * (255 - a) + (p >> 8 & 255) * a) / 255,
                 b = (db * (255 - a) + (p & 255) * a) / 255;
        *d = r << 16 | g << 8 | b;
      }
    }
  }
}

static void dim(int alpha) {
  for (size_t i = 0; i < (size_t)CW * CH; i++) {
    uint32_t p = cvs[i];
    cvs[i] = (((p >> 16 & 255) * (255 - alpha) / 255) << 16) | (((p >> 8 & 255) * (255 - alpha) / 255) << 8) |
             ((p & 255) * (255 - alpha) / 255);
  }
}

/* Convert and hand the finished canvas to the frontend. */
static bool haveFrame; // a picture has gone to the frontend: a late frame may repeat it
static void present(void) {
  if (messageFrames > 0) {
    pd_fill(&canvas, 4, CH - 50, CW - 8, 24, 0x181818);
    pd_text_fit(&canvas, 10, CH - 46, CW - 20, message, 0xffffff, PD_NO_SHADOW);
    messageFrames--;
  }
  if (!direct565)
    for (size_t i = 0; i < (size_t)CW * CH; i++)
      out565[i] = to565(cvs[i]);
  realVideo(out565, (unsigned)CW, (unsigned)CH, (size_t)CW * 2);
  direct565 = false;
  haveFrame = true;
}

/* ---- input ---- */
static unsigned held, prevHeld, pressedKeys;
static int heldFrames[NKEYS];
static int quickBtn = Y, companionBtn = X, hudBtn = -1; // key indices, -1 none (from settings: key_of)
static unsigned gameKeys; // keys the layer presses for the game (BIKE's SELECT)
static int gameKeyFrames;
static bool maskGame;     // while an overlay is up the game sees no keys

/* The pad buttons for the quick menu, the companion and the HUD cursor are PokeDaisy's own SETTINGS rows
 * (a core option is out of reach in plorpOS): each frame's key index, -1 for none. */
static int key_of(int btn) {
  return btn == PD_BTN_X ? X : btn == PD_BTN_Y ? Y : btn == PD_BTN_L2 ? L2 : btn == PD_BTN_R2 ? R2 : -1;
}
static void read_options(void) {} // (kept for the option-update hook: the canvas is read once at load)

static void poll_keys(void) {
  held = 0;
  if (realInput)
    for (int k = 0; k < NKEYS; k++)
      if (k != MENU && realInput(0, RETRO_DEVICE_JOYPAD, 0, retroId[k]))
        held |= 1u << k;
  pressedKeys = held & ~prevHeld;
#ifdef PD_KEYLOG
  {
    static int logFrame;
    logFrame++;
    for (int k = 0; k < NKEYS; k++)
      if ((pressedKeys | (prevHeld & ~held)) >> k & 1)
        fprintf(stderr, "pokedaisy: key %d %s (poll %d)\n", k, (held >> k & 1) ? "down" : "up", logFrame);
  }
#endif
  for (int k = 0; k < NKEYS; k++) {
    heldFrames[k] = (held >> k & 1) ? heldFrames[k] + 1 : 0;
    // The D-pad repeats while held: after a quarter second, every ~90 ms.
    if (k >= RIGHT && k <= DOWN && heldFrames[k] > 15 && (heldFrames[k] - 15) % 5 == 0)
      pressedKeys |= 1u << k;
  }
  prevHeld = held;
}
static bool down(int k) { return (pressedKeys >> k & 1) != 0; }

/* What mGBA reads while its frame runs. */
static int16_t masked_input(unsigned port, unsigned device, unsigned index, unsigned id) {
  if (port != 0 || device != RETRO_DEVICE_JOYPAD)
    return 0;
  if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
    int16_t m = 0;
    for (int k = A; k <= L; k++)
      if (maskGame ? (gameKeys >> k & 1) : ((held >> k & 1) || (gameKeyFrames > 0 && (gameKeys >> k & 1))))
        m |= 1 << retroId[k];
    return m;
  }
  for (int k = A; k <= L; k++)
    if (retroId[k] == id)
      return maskGame ? (gameKeyFrames > 0 && (gameKeys >> k & 1)) : ((held >> k & 1) || (gameKeyFrames > 0 && (gameKeys >> k & 1)));
  return maskGame ? 0 : realInput(port, device, index, id);
}

/* ---- the companion ---- */
#include "pd_focus.inc"

static struct pd_game game;
static struct pd_settings settings;
static struct pd_ui ui;
static struct pd_snapshot snap;
static struct pd_host_info host;
static char settingsPath[1100];
static bool companion, companionWas, haveGame;
static int frames;

/* the HUD over the overworld (B2) */
static struct hud_state hudState = {0, 0, false, -1, -1};
static struct hud_hits hudHits;
static bool hudFocus, hudShown;
static uint32_t *hudPixels;
static uint32_t hudDrawnKey;
static int hudW, hudH;
static int fieldFlags, hudFieldFrames;

/* the quick menu and the game-code jobs: PC, FLY, REPEL, BIKE (fly.c, field_items.c) */
static bool quickOpen;
static int quickSel;
static const struct pd_config *flyCfg;
static struct fly_targets flyT;
static const char *flyNames[FLY_MAX_TOWNS];
static int flyAsk, flySel, flyStage, flyMapsec, flyWait;
static bool flyList;
static uint32_t flyCallback;
static bool itemsOk, pcOk, bikeOk;
static enum { JOB_NONE, JOB_PC, JOB_REPEL, JOB_BIKE, JOB_BIKE_WAIT, JOB_BIKE_RESTORE } job;
static int jobItem, jobFrames, jobTries, repelSel, repelKinds[REPEL_KINDS], repelKindCount;
static bool repelList, bikeWasRiding, bikeTook;
static uint16_t bikeOld;
static char repelRows[REPEL_KINDS][48];
static const char *repelNames[REPEL_KINDS];
static uint32_t *ovPixels; // the quick menu's canvas, see-through where nothing is drawn
static const unsigned char *romData;
static size_t romLen;


static void snapshot(void) { pd_snapshot_read(&snap, &game, pd_lr_read_memory, core); }

static int hud_wild_uncaught(const struct pd_game *g, const struct pd_snapshot *s) {
  struct pd_wild_set w;
  if (!g->cfg || !g->cfg->wildHeaders)
    return -1;
  if (!pd_wild_here(g, s, &w))
    return 0;
  int seen[PD_WILD_METHODS * PD_WILD_MAX], n = 0, left = 0;
  for (int m = 0; m < PD_WILD_METHODS; m++)
    for (int i = 0; i < w.count[m]; i++) {
      int sp = w.mons[m][i].species, k = 0;
      while (k < n && seen[k] != sp)
        k++;
      if (k < n)
        continue;
      seen[n++] = sp;
      left += !pd_dex_caught(s, pd_dex_national(g, sp));
    }
  return left;
}

/* The HUD over the game's own picture, at the companion's pixel scale (SIZE steps it). */
static void draw_hud(void) {
  int gs = CW / (int)gameW < CH / (int)gameH ? CW / (int)gameW : CH / (int)gameH;
  if (gs < 1)
    gs = 1;
  int ax = (CW - (int)gameW * gs) / 2, ay = (CH - (int)gameH * gs) / 2, aw = (int)gameW * gs, ah = (int)gameH * gs;
  if (wideReady) { // the wide view (EXPAND, ZOOM OUT) is what is on screen: anchor to it, not to the centred game picture
    int ws = CW / (int)wideW < CH / (int)wideH ? CW / (int)wideW : CH / (int)wideH;
    if (ws < 1)
      ws = 1;
    aw = (int)wideW * ws;
    ah = (int)wideH * ws;
    ax = (CW - aw) / 2;
    ay = (CH - ah) / 2;
  }
  int scale = 1 + settings.hudSize - PD_HUD_NORMAL;
  if (scale < 1)
    scale = 1;
  int w = aw / scale, hh = ah / scale;
  if (!hudPixels)
    hudPixels = malloc((size_t)CANVAS_MAX_W * CANVAS_MAX_H * sizeof *hudPixels);
  if (!hudPixels)
    return;
  uint32_t key = hud_key(&snap, &hudState, &settings) ^ (uint32_t)(w * 65599 + hh);
  if (key != hudDrawnKey || w != hudW || hh != hudH) {
    struct pd_canvas hc;
    pd_canvas_init(&hc, w, hh, hudPixels);
    hudShown = hud_draw(&hc, &game, &snap, &hudState, &settings, &hudHits);
    quick_finish(&hc);
    hudDrawnKey = key;
    hudW = w;
    hudH = hh;
  }
  if (hudShown)
    blend_overlay(hudPixels, w, hh, scale, ax + (aw - w * scale) / 2, ay + (ah - hh * scale) / 2);
}


/* ---- game-code layer: EXPAND (pk_wide), the field read, WILD ENCOUNTERS ---- */


/* The EXPAND window for the canvas and a fw x fh game: the canvas over the game's whole scale (one less for
 * ZOOM OUT), within pk_wide's largest view, never smaller than the game, even margins. */
static void wide_window(int aspect, unsigned fw, unsigned fh, unsigned *w, unsigned *h) {
  int scale = CW / (int)fw < CH / (int)fh ? CW / (int)fw : CH / (int)fh;
  if (aspect == PD_ASPECT_ZOOM_OUT)
    scale--;
  if (scale < 1)
    scale = 1;
  unsigned x = (unsigned)CW / scale, y = (unsigned)CH / scale;
  x = x > PK_WIDE_MAX_VIEW_W ? PK_WIDE_MAX_VIEW_W : x < fw ? fw : x;
  y = y > PK_WIDE_MAX_VIEW_H ? PK_WIDE_MAX_VIEW_H : y < fh ? fh : y;
  *w = x - ((x - fw) & 1);
  *h = y - ((y - fh) & 1);
}

static bool rewinding;   // the frontend is rewinding: the camera stays where the aspect put it (no AUTO ZOOM IN on the odd frames)
/* The window for this frame, in the game frame's pixels (and wideW x wideH set). */
static struct PkWideView wide_rect(unsigned fw, unsigned fh) {
  int last = pk_wide_scene();
  int aspect = settings.aspect == PD_ASPECT_ZOOM_OUT && settings.autoZoom && last != PK_WIDE_SCENE_FIELD && !rewinding
                   ? PD_ASPECT_EXPAND : settings.aspect;
  wide_window(aspect, fw, fh, &wideW, &wideH);
  return (struct PkWideView){((int)fw - (int)wideW) / 2, ((int)fh - (int)wideH) / 2, (int)wideW, (int)wideH};
}

/* A fast-forwarded frame: no window rebuild (that is the cost), but the picture over pk_wide's ambient glow, as rewind and
 * the menus show it, rather than over black (a Game Boy game: over its menus' bars). */
static void wide_glow_tick(void) {
  unsigned fw = (unsigned)pk_wide_frame_w(), fh = (unsigned)pk_wide_frame_h();
  for (unsigned y = 0; y < fh; y++)
    for (unsigned x = 0; x < fw; x++)
      wideSource[y * fw + x] = to_xbgr(outputBuffer[y * 256 + x]);
  struct PkWideView v = wide_rect(fw, fh);
  wideReady = pk_wide_glow(core, wideSource, wideFrame, v) && !pk_wide_fault();
  wideHave = false;   // not a rebuilt window: the next full frame starts from the map again
}

/* After every game frame: pk_wide's RAM history, and (wideReady) this frame's window. */
static void wide_tick(void) {
  wideReady = wideHave = false;
  if (!wideSupported)
    return;
  unsigned fw = (unsigned)pk_wide_frame_w(), fh = (unsigned)pk_wide_frame_h();
  PF_T(cv0);
  for (unsigned y = 0; y < fh; y++)
    for (unsigned x = 0; x < fw; x++)
      wideSource[y * fw + x] = to_xbgr(outputBuffer[y * 256 + x]);
  PF_T(cv1);
  pf_add(PF_CONV, cv1 - cv0);
  if (settings.aspect == PD_ASPECT_ORIGINAL) {
    // Nothing past the picture, but the scene still read (FLY's "on the field"): a one-pixel window.
    static uint32_t px;
    pk_wide_view(core, wideSource, &px, (struct PkWideView){0, 0, 1, 1}, NULL);
    return;
  }
  struct PkWideView v = wide_rect(fw, fh);
  PF_T(vw0);
  pk_wide_view(core, wideSource, wideFrame, v, NULL);
  PF_T(vw1);
  pf_add(PF_VIEW, vw1 - vw0);
  wideReady = !pk_wide_fault();
  wideHave = wideReady;
}

/* The canvas for a fw x fh game on the panel: the panel over the game's whole scale (one less for ZOOM OUT), so the
 * frontend shows it at exactly that integer scale. EXPAND's window is the whole canvas. */
enum { CANVAS_EXPAND, CANVAS_ZOOM, CANVAS_MENU };
static void canvas_for(int fw, int fh, int kind, int *w, int *h) {
  int s = panelW / fw < panelH / fh ? panelW / fw : panelH / fh;
  if (s < 1)
    s = 1;
  if (kind != CANVAS_EXPAND && s > 1)
    s--;
  // The companion and its menus are laid out for 320x240 at least: a Game Boy game's ZOOM OUT on a 1024x768 panel is
  // 256x192, where text overran its boxes (plorpOS plorpos-oe0.26). Menus step down until the canvas holds them.
  while (kind == CANVAS_MENU && s > 1 && (panelW / s < 320 || panelH / s < 240))
    s--;
  int x = panelW / s, y = panelH / s;
  *w = x < fw ? fw : x > CANVAS_MAX_W ? CANVAS_MAX_W : x;
  *h = y < fh ? fh : y > CANVAS_MAX_H ? CANVAS_MAX_H : y;
}

static void choose_canvas(void) {
  const char *v = option("pokedaisy_canvas");
  int w = 320, h = 240;
  if (v && sscanf(v, "%dx%d", &w, &h) != 2)
    w = 320, h = 240;
  CWMAX = w < 320 ? 320 : w > CANVAS_MAX_W ? CANVAS_MAX_W : w;
  CHMAX = h < 240 ? 240 : h > CANVAS_MAX_H ? CANVAS_MAX_H : h;
  panelW = panelH = 0;
  v = option("pokedaisy_panel");
  if (v && sscanf(v, "%dx%d", &panelW, &panelH) == 2 && panelW > 0 && panelH > 0) {
    struct retro_system_av_info av;
    mgba_retro_get_system_av_info(&av);
    baseGW = (int)av.geometry.base_width;
    baseGH = (int)av.geometry.base_height;
    canvas_for(baseGW, baseGH, CANVAS_MENU, &CWMAX, &CHMAX); // the menus' is the largest
  } else
    panelW = panelH = 0;
  CW = CWMAX;
  CH = CHMAX;
}

/* Which canvas this moment wants. With a known panel: ZOOM OUT (one scale less) while it is the chosen view, and while a
 * menu is up (they are laid out for it); EXPAND's scale otherwise, which for a game with no EXPAND is the default. The
 * HUD cursor is not a menu: it sits on the game's own view, so the canvas stays as it is. */
static int want_canvas(void) {
  if (companion || quickOpen || settings.aspect == PD_ASPECT_ORIGINAL)
    return CANVAS_MENU;
  if (!wideSupported || settings.aspect != PD_ASPECT_ZOOM_OUT)
    return CANVAS_EXPAND;
  return settings.autoZoom && !rewinding && pk_wide_scene() != PK_WIDE_SCENE_FIELD ? CANVAS_EXPAND : CANVAS_ZOOM;
}

/* Follow the aspect: when the wanted canvas differs, resize it and tell the frontend (it rescales to the new whole factor). */
static void canvas_update(bool notify) {
  if (!panelW)
    return;
  int w, h;
  unsigned fw = gameW ? gameW : (unsigned)baseGW, fh = gameH ? gameH : (unsigned)baseGH;
  canvas_for((int)fw, (int)fh, want_canvas(), &w, &h);
  if (w == CW && h == CH)
    return;
  CW = w;
  CH = h;
  if (cvs)
    pd_canvas_init(&canvas, CW, CH, cvs);
  hudDrawnKey = 0;
  if (notify && realEnv) {
    struct retro_game_geometry g = {(unsigned)CW, (unsigned)CH, (unsigned)CWMAX, (unsigned)CHMAX, (float)CW / (float)CH};
    realEnv(RETRO_ENVIRONMENT_SET_GEOMETRY, &g);
  }
}

/* ---- entry points ---- */
RETRO_API void retro_set_environment(retro_environment_t e) {
  realEnv = e;
  mgba_retro_set_environment(env_wrap);
}
/* What the capture of mGBA's own video call records: the size; the pixels stay in outputBuffer. */
static void capture_video(const void *d, unsigned w, unsigned h, size_t pitch) {
  (void)d;
  (void)pitch;
  gameW = w;
  gameH = h;
}
RETRO_API void retro_set_video_refresh(retro_video_refresh_t v) {
  realVideo = v;
  mgba_retro_set_video_refresh(capture_video);
}
RETRO_API void retro_set_input_state(retro_input_state_t i) {
  realInput = i;
  mgba_retro_set_input_state(masked_input);
}
RETRO_API void retro_get_system_info(struct retro_system_info *info) {
  mgba_retro_get_system_info(info);
  info->library_name = "PokeDaisy";
  info->library_version = "0.3";
}
RETRO_API void retro_get_system_av_info(struct retro_system_av_info *info) {
  mgba_retro_get_system_av_info(info);
  info->geometry.base_width = (unsigned)CW;
  info->geometry.base_height = (unsigned)CH;
  info->geometry.max_width = (unsigned)CWMAX;
  info->geometry.max_height = (unsigned)CHMAX;
  info->geometry.aspect_ratio = (float)CW / (float)CH;
}

RETRO_API bool retro_load_game(const struct retro_game_info *g) {
  if (!mgba_retro_load_game(g))
    return false;
  choose_canvas();
  read_options();
  {
    struct retro_audio_buffer_status_callback abc = {audio_status};
    realEnv(RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK, &abc);
  }
  frameLate = false;
  cvs = calloc((size_t)CWMAX * CHMAX, sizeof *cvs);
  out565 = calloc((size_t)CWMAX * CHMAX, sizeof *out565);
  if (!cvs || !out565)
    return false;
  pd_canvas_init(&canvas, CW, CH, cvs);
  uint32_t crc = 0;
  core->checksum(core, &crc, mCHECKSUM_CRC32);
  const unsigned char *rom = data;
  size_t len = dataSize;
  pd_game_detect(&game, rom, len);
  game.rom = rom;
  game.romSize = len;
  pd_game_identify(&game);
  fprintf(stderr, "pokedaisy: %s, sha1 %s%s%s\n", game.title ? game.title : "?", game.sha1[0] ? game.sha1 : "-",
          game.unsupported ? " - " : "", game.unsupported ? game.unsupported : "");
  if (core->platform(core) == mPLATFORM_GBA)
    pd_romart_scan(&game);
  const char *dir = NULL;
  if (realEnv(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY, &dir) && dir) {
    char sub[1050];
    snprintf(sub, sizeof sub, "%s/pokedaisy", dir);
    mkdir(sub, 0755);
    snprintf(settingsPath, sizeof settingsPath, "%s/settings.ini", sub);
  } else
    snprintf(settingsPath, sizeof settingsPath, "pokedaisy-settings.ini");
  pd_settings_load(&settings, settingsPath);
  // A resident frontend loads the core again in the same process: nothing of the last game may survive.
  memset(&snap, 0, sizeof snap);
  memset(&hudState, 0, sizeof hudState);
  hudState.wild = hudState.focus = -1;
  memset(&hudHits, 0, sizeof hudHits);
  haveFrame = wideHave = false;
  hudDrawnKey = 0;
  hudW = hudH = 0;
  hudShown = hudFocus = false;
  hudFieldFrames = 0;
  messageFrames = 0;
  frames = 0;
  gameKeys = 0;
  gameKeyFrames = 0;
  prevHeld = held = pressedKeys = 0;
  memset(heldFrames, 0, sizeof heldFrames);
  gameW = gameH = 0;
  quickSel = 0;
  wideSupported = pk_wide_configure(crc) != 0;
  wideReady = false;
  canvas_update(false);
  fieldKnown = core->platform(core) == mPLATFORM_GBA && pk_field_configure(crc);
  wildSwitch = fieldKnown ? pk_wild_switch_find(rom, len) : 0;
  if (wildSwitch)
    pk_field_wild(wildSwitch, settings.wildOff);
  pk_rest_reset();
  fieldFlags = 0;
  romData = rom;
  romLen = len;
  bool gba = core->platform(core) == mPLATFORM_GBA;
  flyCfg = game.cfg && gba && fly_supported(game.cfg, rom, len) ? game.cfg : NULL;
  itemsOk = game.cfg && gba && field_items_supported(game.cfg, rom, len);
  pcOk = itemsOk && pc_supported(game.cfg, rom, len);
  bikeOk = itemsOk && bike_supported(game.cfg);
  quickOpen = flyList = repelList = false;
  flyAsk = flyStage = 0;
  job = JOB_NONE;
  quick_set_grid(settings.quickGrid);
  ovPixels = calloc((size_t)CWMAX * CHMAX, sizeof *ovPixels);
  pd_ui_init(&ui, &settings);
  memset(&host, 0, sizeof host);
  host.hideScreenMode = true;
  host.hasQuickMenu = true;
  host.hasButtonRows = true;
  host.frontendOwnsPlayback = true;
  host.hasHud = core->platform(core) == mPLATFORM_GBA;
  host.hasExpand = true;
  host.expandGame = wideSupported;
  host.count = 1;
  host.labels[0] = "HOST";
  snprintf(host.values[0], 40, "PokeDaisy core");
  focusId = -1;
  focus_new_screen();
  companion = hudFocus = false;
  haveGame = true;
  return true;
}

RETRO_API bool retro_unserialize(const void *d, size_t n) {
  bool r = mgba_retro_unserialize(d, n);
  if (r && haveGame) {
    pk_wide_reset(); // EXPAND's RAM history is from before the jump
    wideReady = false;
    snap.valid = false;
  }
  return r;
}

RETRO_API void retro_unload_game(void) {
  pk_wide_configure(0);
  wideSupported = wideReady = false;
  mgba_retro_unload_game();
  free(cvs);
  free(out565);
  free(hudPixels);
  free(ovPixels);
  ovPixels = NULL;
  cvs = NULL;
  out565 = NULL;
  hudPixels = NULL;
  haveGame = false;
}

/* The HUD's cursor goes onto it: the lead's slot where the party shows, else the first target. */
static void hud_focus_start(void) {
  hudFocus = true;
  hudState.focus = hudHits.at[0].id;
  for (int i = 0, lowest = PD_PARTY_SIZE; i < hudHits.n; i++)
    if (hudHits.at[i].id >= HUD_SLOT && hudHits.at[i].id - HUD_SLOT < lowest) {
      lowest = hudHits.at[i].id - HUD_SLOT;
      hudState.focus = hudHits.at[i].id;
    }
}

RETRO_API void retro_run(void) {
  if (!haveGame) {
    mgba_retro_run();
    return;
  }
  PF_T(t0);
#ifdef PD_PROFILE
  pf_gap(t0);
#endif
  if (optionsDirty) {
    optionsDirty = false;
    read_options();
  }
  quickBtn = key_of(settings.quickBtn);
  companionBtn = key_of(settings.companionBtn);
  hudBtn = key_of(settings.hudBtn);
  poll_keys();
  bool wasCompanion = companion;
  if (down(companionBtn) && !hudFocus) {
    companion = !companion;
    quickOpen = flyList = repelList = false;
    flyAsk = 0;
  }
  // The HUD button: the cursor goes onto the HUD, the D-pad moves it, A presses what it is on, B / the button leave.
  if (hudFocus && (companion || !hudShown)) {
    hudFocus = false;
    hudState.focus = -1;
  }
  if (hudBtn >= 0 && down(hudBtn) && !companion) {
    if (hudFocus || !hudShown || !hudHits.n) {
      if (!hudFocus)
        notice("No HUD on screen");
      hudFocus = false;
      hudState.focus = -1;
    } else {
      hud_focus_start();
    }
  } else if (hudFocus) {
    if (down(B)) {
      hudFocus = false;
      hudState.focus = -1;
    } else if (down(A)) {
      int id = hudState.focus;
      hudFocus = false;
      hudState.focus = -1;
      if (id >= HUD_SLOT && id < HUD_SLOT + PD_PARTY_SIZE) {
        ui.tab = PD_TAB_PARTY;
        ui.summarySlot = id - HUD_SLOT;
      } else if (id == HUD_MAP || id == HUD_MAP_BUTTON) {
        ui.tab = PD_TAB_MAP;
        ui.mapPage = -1;
      } else if (id == HUD_BALLS || id == HUD_REPEL || id == HUD_BAG) {
        ui.tab = PD_TAB_BAG;
      } else if (id == HUD_WILD) {
        ui.tab = PD_TAB_GUIDE;
        ui.guidePage = 0;
        ui.guideOpen = -1;
      }
      if (id != HUD_PC)
        companion = true;
    } else {
      int dir = down(UP) ? UP : down(DOWN) ? DOWN : down(LEFT) ? LEFT : down(RIGHT) ? RIGHT : -1;
      if (dir >= 0)
        hudState.focus = hud_step(&hudHits, hudState.focus, dir);
    }
  }


  // The QUICK MENU over the paused game, in the app's order (QuickAction): PC, FLY, REPEL, BIKE, WILD, COMPANION;
  // each only where the game can, dimmed with why when not now.
  struct quick_item quick[QUICK_COUNT];
  int quickN = 0;
  char repelWhy[32] = "";
  if (pcOk)
    quick[quickN++] = (struct quick_item){QUICK_PC, false, false, NULL, 0, 0, NULL};
  if (flyCfg) {
    int flyer = fly_flyer(&snap);
    quick[quickN++] = (struct quick_item){QUICK_FLY, false, false, flyer < 0 ? "No one in your party knows FLY" : NULL,
                                          flyer < 0 ? 0 : snap.party[flyer].species, 0, NULL};
  }
  if (itemsOk) {
    repelKindCount = 0;
    for (int k = 0; k < REPEL_KINDS; k++)
      if (bag_count(&snap, repel_kinds[k][0]) > 0)
        repelKinds[repelKindCount++] = repel_kinds[k][0];
    int left = repel_steps(core, game.cfg);
    if (left)
      snprintf(repelWhy, sizeof repelWhy, "%d steps left", left);
    quick[quickN++] = (struct quick_item){QUICK_REPEL, false, false,
                                          left ? repelWhy : repelKindCount ? NULL : "No REPEL in your bag", 0,
                                          repelKindCount ? repelKinds[repelKindCount - 1] : repel_kinds[0][0], NULL};
  }
  if (bikeOk) {
    int bike = bag_bike(&snap);
    bool riding = (fieldFlags & PK_FIELD_AVATAR) && (fieldFlags & PK_FIELD_BIKE);
    quick[quickN++] = (struct quick_item){QUICK_BIKE, false, false, bike ? NULL : "No bike in your bag", 0, bike ? bike : 360,
                                          riding ? "GET OFF" : NULL};
  }
  quick[quickN++] = (struct quick_item){QUICK_WILD, settings.wildOff, false, wildSwitch ? NULL : "Not in this game", 0, 0, NULL};
  if (host.hasHud && settings.hud)
    quick[quickN++] = (struct quick_item){QUICK_HUD, false, false, hudShown && hudHits.n ? NULL : "No HUD on screen", 0, 0, NULL};
  quick[quickN++] = (struct quick_item){QUICK_COMPANION, false, false, NULL, 0, 0, NULL};
  if (quickSel >= quickN)
    quickSel = 0;
  quick_set_grid(settings.quickGrid);
  if (down(quickBtn) && !companion && !hudFocus) {
    quickOpen = !quickOpen;
    flyList = repelList = false;
    flyAsk = 0;
  } else if (quickOpen && repelList) {
    if (down(UP))
      repelSel = (repelSel + repelKindCount - 1) % repelKindCount;
    if (down(DOWN))
      repelSel = (repelSel + 1) % repelKindCount;
    if (down(B))
      repelList = false;
    else if (down(A)) {
      quickOpen = repelList = false;
      job = JOB_REPEL;
      jobItem = repelKinds[repelSel];
      jobFrames = 0;
    }
  } else if (quickOpen && flyList) {
    if (down(UP) && flyT.count)
      flySel = (flySel + flyT.count - 1) % flyT.count;
    if (down(DOWN) && flyT.count)
      flySel = (flySel + 1) % flyT.count;
    if (down(B))
      flyList = false;
    else if (down(A) && flyT.count) {
      quickOpen = flyList = false;
      flyMapsec = flyT.towns[flySel];
      flyStage = 1;
      flyWait = 0;
    }
  } else if (quickOpen && down(B)) {
    quickOpen = false;
  } else if (quickOpen) {
    int dx = down(RIGHT) - down(LEFT), dy = down(DOWN) - down(UP);
    if (dx || dy)
      quickSel = quick_step(quickN, quickSel, dx, dy, CW, CH);
    if (down(A) && !quick[quickSel].why && quick[quickSel].action == QUICK_FLY) {
      flyAsk = 1; // answered below, between two frames
    } else if (down(A) && !quick[quickSel].why && quick[quickSel].action == QUICK_REPEL && repelKindCount > 1) {
      for (int k = 0; k < repelKindCount; k++) {
        int steps = 0;
        for (int r = 0; r < REPEL_KINDS; r++)
          if (repel_kinds[r][0] == repelKinds[k])
            steps = repel_kinds[r][1];
        snprintf(repelRows[k], sizeof repelRows[k], "%s x%d  %d steps", pd_item_name(&game, repelKinds[k]),
                 bag_count(&snap, repelKinds[k]), steps);
        repelNames[k] = repelRows[k];
      }
      repelList = true;
      repelSel = repelKindCount - 1;
    } else if (down(A) && !quick[quickSel].why) {
      quickOpen = false;
      switch (quick[quickSel].action) {
      case QUICK_PC:
        job = JOB_PC;
        jobFrames = 0;
        break;
      case QUICK_REPEL:
        job = JOB_REPEL;
        jobItem = repelKinds[0];
        jobFrames = 0;
        break;
      case QUICK_BIKE:
        job = JOB_BIKE;
        jobItem = bag_bike(&snap);
        jobFrames = 0;
        jobTries = 15; // PokeDaisyActivity's SELECT_TRIES
        break;
      case QUICK_WILD:
        settings.wildOff = !settings.wildOff;
        pd_settings_save(&settings, settingsPath);
        notice(settings.wildOff ? "Wild encounters off" : "Wild encounters on");
        break;
      case QUICK_COMPANION:
        companion = true;
        break;
      case QUICK_HUD:
        hud_focus_start();
        break;
      default:
        break;
      }
    }
  }

  if (companion) {
    if (down(B) && !map_cursor_back(&ui) && !pd_ui_back(&ui))
      companion = false;
    if (down(L) || down(R))
      pd_ui_next_tab(&ui, down(R) ? 1 : -1);
    for (int k = RIGHT; k <= DOWN; k++)
      if (down(k))
        focus_move(&ui, k);
    if (down(A))
      focus_activate(&ui);
    switch (pd_ui_take_action(&ui)) {
    case PD_ACTION_SETTINGS_CHANGED:
      pd_settings_save(&settings, settingsPath);
      hudDrawnKey = 0;
      break;
    default:
      break;
    }
  }

  canvas_update(true);
#ifdef PD_NOSKIP
  frameLate = false;
#endif
  bool late = frameLate;
  frameLate = false;
  // Fast-forward leaves a fraction of the normal frame budget (2x: half), which EXPAND's window cannot fit: while the
  // frontend says it is fast-forwarding, the game is drawn as in ORIGINAL (the RAM history EXPAND needs still advances).
  bool ff = false;
  if (realEnv) {
    struct retro_throttle_state th = {0, 0.0f};
    bool got = realEnv(RETRO_ENVIRONMENT_GET_THROTTLE_STATE, &th);
    ff = got && th.mode == RETRO_THROTTLE_FAST_FORWARD;
    rewinding = got && th.mode == RETRO_THROTTLE_REWINDING;
  }
  bool paused = companion || hudFocus || (quickOpen && !flyAsk);
  maskGame = paused;
  if (!paused) {
    PF_T(pa);
    mgba_retro_run();
    PF_T(pb);
    pf_add(PF_RUN, pb - pa);
    if (gameKeyFrames > 0)
      gameKeyFrames--;
    if (core->platform(core) == mPLATFORM_GBA)
      pk_rest_note(core);
    if (fieldKnown)
      fieldFlags = pk_field_state(core, settings.runIndoors, wildSwitch && settings.wildOff ? 0 : 100);
    if (ff && wideSupported && settings.aspect != PD_ASPECT_ORIGINAL) {
      wide_glow_tick();
    } else if (late && wideSupported && settings.aspect != PD_ASPECT_ORIGINAL) {
      // Behind schedule: only the RAM history EXPAND needs (as for a fast-forwarded frame), no window, and the frame below
      // repeats the last picture whole (a stale margin around a live middle showed as a seam).
      pk_wide_skip(core, wideSource);
      wideReady = wideHave = false;
    } else
      wide_tick();
    PF_T(pc);
    pf_add(PF_WIDE, pc - pb);
  } else {
    static const int16_t silence[2 * 1024];
    audioCallback(silence, 549);
  }

  if (!snap.valid || (companion && !wasCompanion) || (!companion && frames % 30 == 0))
    snapshot();
  companionWas = companion;
  frames++;

  // FLY's steps and the field-item jobs, each between two frames the game's own code can be called in.
  bool flyBattle = snap.inBattle || (fieldFlags & PK_FIELD_BATTLE);
  bool flyBusy = companion || (fieldFlags & PK_FIELD_LOCKED) || (wideSupported && pk_wide_scene() != PK_WIDE_SCENE_FIELD);
  if (flyCfg && flyAsk && quickOpen && !companion) {
    if (pk_call_safe(core)) {
      snapshot();
      fly_targets(core, flyCfg, romData, romLen, &snap, flyBusy, flyBattle, &flyT);
      flyAsk = 0;
      if (flyT.result == FLY_OK && flyT.count) {
        for (int i = 0; i < flyT.count; i++) {
          const char *name = pd_mapsec_name(&game, flyT.towns[i]);
          flyNames[i] = name && *name ? name : "?";
        }
        flyList = true;
        flySel = 0;
      } else {
        quickOpen = false;
        notice(flyT.result == FLY_OK ? "You haven't been anywhere to FLY to yet." : fly_reason(flyT.result));
      }
    } else if (++flyAsk > 120) {
      flyAsk = 0;
      quickOpen = false;
      notice(fly_reason(FLY_FAILED));
    }
  }
  if (flyCfg && flyStage == 1 && !companion && pk_call_safe(core)) {
    snapshot();
    enum fly_result r = fly_start(core, flyCfg, romData, romLen, &snap, flyMapsec, &flyT, flyBusy, flyBattle);
    if (r == FLY_OK) {
      flyStage = 2;
      flyWait = 0;
      flyCallback = fly_main_callback(core, flyCfg);
    } else {
      flyStage = 0;
      notice(fly_reason(r));
    }
  } else if (flyCfg && flyStage == 2 && !companion && ++flyWait >= FLY_FADE_FRAMES && pk_call_safe(core)) {
    flyStage = 0;
    if (!fly_finish(core, flyCfg, romData, romLen, flyMapsec, &flyT, fly_main_callback(core, flyCfg) != flyCallback))
      notice(fly_reason(FLY_FAILED));
  }
  bool safe = job != JOB_NONE && !companion && pk_call_safe(core);
  if (job == JOB_PC && safe) {
    job = JOB_NONE;
    enum item_result r = pc_open(core, game.cfg, romData, romLen, flyBusy, flyBattle);
    if (r != ITEM_USED)
      notice(item_reason(r));
  } else if (job == JOB_REPEL && safe) {
    job = JOB_NONE;
    int steps = 0;
    enum item_result r = repel_use(core, game.cfg, romData, romLen, jobItem, flyBusy, flyBattle, &steps);
    if (r == ITEM_USED) {
      char msg[48];
      snprintf(msg, sizeof msg, "Used! It lasts %d steps.", steps);
      notice(msg);
    } else
      notice(item_reason(r));
  } else if (job == JOB_BIKE && safe) {
    enum item_result r = bike_register(core, game.cfg, romData, romLen, jobItem, flyBusy, flyBattle, &bikeOld);
    if (r == ITEM_USED) {
      gameKeys = 1u << SELECT;
      gameKeyFrames = 2;
      bikeWasRiding = fieldFlags & PK_FIELD_BIKE;
      bikeTook = false;
      job = JOB_BIKE_WAIT;
      jobFrames = 0;
    } else {
      job = JOB_NONE;
      notice(item_reason(r));
    }
  } else if (job == JOB_BIKE_WAIT && !companion) {
    // SELECT again if the game dropped it (a bike rolling at full speed ignores it), but only where taking it shows
    // for sure: the riding state flips, or the field's controls stay held.
    if (((fieldFlags & PK_FIELD_BIKE) != 0) != bikeWasRiding || (fieldFlags & PK_FIELD_LOCKED))
      bikeTook = true;
    if (++jobFrames >= 10) {
      if (bikeTook || !(fieldFlags & PK_FIELD_AVATAR) || --jobTries <= 0) {
        job = JOB_BIKE_RESTORE;
      } else {
        gameKeys = 1u << SELECT;
        gameKeyFrames = 2;
      }
      jobFrames = 0;
    }
  } else if (job == JOB_BIKE_RESTORE && ++jobFrames >= 20) {
    job = JOB_NONE;
    bike_restore(core, game.cfg, jobItem, bikeOld);
  } else if ((job == JOB_PC || job == JOB_REPEL || job == JOB_BIKE) && !companion && ++jobFrames > 120) {
    job = JOB_NONE;
    notice(item_reason(ITEM_FAILED));
  }


  if (late && !ff && !paused && !companion && !quickOpen && haveFrame && !wideHave) {
    realVideo(NULL, (unsigned)CW, (unsigned)CH, (size_t)CW * 2); // the same picture again: nothing drawn
    PF_SKIPPED();
    pf_frame();
    return;
  }
  PF_T(td0);
  if (companion) {
    pd_ui_update(&ui, &snap);
    ui.focusId = focusId;
    host.ffOn = false;
    host.hasState = false;
    memset(cvs, 0, (size_t)CW * CH * sizeof *cvs);
    pd_ui_draw(&ui, &canvas, &game, &snap, &host);
    map_cursor_sync(&ui, &game, &snap);
    focus_draw(&ui, focus_after_draw(&ui, focus_resolve(&ui)));
  } else if (quickOpen) {
    draw_game();
    dim(0x55);
    struct pd_canvas qc;
    pd_canvas_init(&qc, CW, CH, ovPixels);
    const char *const *rows = flyList ? flyNames : repelList ? repelNames : NULL;
    bool drawn = true;
    if (rows)
      quick_list_draw(&qc, flyList ? "FLY" : "REPEL", rows, flyList ? flyT.count : repelKindCount, flyList ? flySel : repelSel);
    else
      drawn = quick_draw(&qc, &game, quick, quickN, quickSel);
    if (drawn) {
      quick_finish(&qc);
      blend_overlay(ovPixels, CW, CH, 1, 0, 0);
    }
  } else {
    direct565 = messageFrames == 0;
    if (direct565)
      draw_game_fast();
    else
      draw_game();
    bool field = (fieldFlags & PK_FIELD_KNOWN) && !(fieldFlags & (PK_FIELD_LOCKED | PK_FIELD_BATTLE)) &&
                 (!wideSupported || pk_wide_scene() == PK_WIDE_SCENE_FIELD) && flyStage == 0;
    hudFieldFrames = field ? hudFieldFrames + 1 : 0;
    if (settings.hud && game.cfg && core->platform(core) == mPLATFORM_GBA && hudFieldFrames >= 20) {
      int steps = repel_steps(core, game.cfg);
      if (steps <= 0)
        hudState.repelFull = 0;
      else if (!hudState.repelFull || steps > hudState.repelFull)
        hudState.repelFull = hud_repel_full(steps);
      hudState.repel = steps > 0 ? steps : 0;
      hudState.pc = pcOk;
      hudState.wild = hud_wild_uncaught(&game, &snap);
      draw_hud();
    } else
      hudShown = false;
  }
  PF_T(td1);
  pf_add(PF_DRAW, td1 - td0);
  present();
  PF_T(td2);
  pf_add(PF_PRESENT, td2 - td1);
  pf_add(PF_TOTAL, td2 - t0);
#ifdef PD_PROFILE
  pf_hist(td2 - t0);
#endif
  pf_frame();
}
