/* PokeDaisy as a libretro core: mGBA's own libretro core (MPL-2.0, third_party/mgba, included below
 * unchanged) with the companion layered over it. GPL-3.0, see ../NOTICE.
 *
 * mGBA's entry points are renamed with the preprocessor while its file is included, so this file can
 * define the real retro_* functions around them and reach its static state (core, data, outputBuffer)
 * without editing a line of upstream. */
#ifdef __ARM_NEON
#include <arm_neon.h>
#endif
#include "pd_backend.h"

#include "field_items.h"
#include "flash.h"
#include "flight.h"
#include "fly.h"
#include "gb_field.h"
#include "polished_field.h"
#include "pd_binput.h"
#include "polished_action_data.h"
#include "hud.h"
#include "pd_guide.h"
#include "pk_call.h"
#include "pk_flight.h"
#include "pd_flight_gen.h"
#include "pk_wide.h"
#include "quick_menu.h"
#include "wild.h"
#include "pd_dex.h"
#include "pd_font.h"
#include "pd_gb.h"
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
     {{"auto", NULL}, {"480x320", NULL}, {"480x800", NULL}, {"640x480", NULL}, {"720x480", NULL}, {"720x540", NULL}, {"720x720", NULL}, {"752x560", NULL}, {"960x544", NULL}, {"960x720", NULL}, {"1024x768", NULL}, {"1280x720", NULL}, {"1600x1440", NULL}, {"1920x1080", NULL}, {NULL, NULL}}, "auto"},
};

static retro_environment_t realEnv;
/* diatom's "behind schedule" signal, given just before each frame (its frame skip): this frame's heavy drawing is left out. */
static bool frameLate;
static void audio_status(bool active, unsigned occupancy, bool underrun) {
  (void)active;
  (void)occupancy;
  frameLate = underrun;
}
/* The emulator's own (armGBA's frame skip registers one, again on every option change): the frontend holds one callback,
 * so both are called from it, and ours stays registered whatever the emulator sets later. */
static retro_audio_buffer_status_callback_t emuAudioStatus;
static void audio_status_both(bool active, unsigned occupancy, bool underrun) {
  audio_status(active, occupancy, underrun);
  if (emuAudioStatus)
    emuAudioStatus(active, occupancy, underrun);
}
static retro_video_refresh_t realVideo;
static retro_input_state_t realInput;
static bool optionsDirty;

static const char *pd_option(const char *key) {
  struct retro_variable v = {key, NULL};
  return realEnv && realEnv(RETRO_ENVIRONMENT_GET_VARIABLE, &v) && v.value ? v.value : NULL;
}

#ifdef PD_ARMGBA
/* armGBA's options this core can't follow, left out of the list and answered here: the LCD effects and screen sizes
 * (a frame bigger than the GBA's, which the companion draws over), the link modes (two games, or a GameCube, in one
 * frame), Low Memory (no ROM in memory to read), Threaded Video (EXPAND's middle a frame behind its margins), the save
 * sharing (another save loaded over the player's) and the Game Boy side (Gambatte isn't in this core). Each keeps the
 * value set here, else armGBA's own default. */
static const struct { const char *key, *value; } HELD[] = {
    {"armgba_low_memory", "disabled"}, {"armgba_threaded_video", "disabled"}, {"armgba_lcd_effects", "disabled"},
    {"armgba_lcd_grid", "disabled"}, {"armgba_lcd_screen", "auto"}, {"armgba_link_mode", "off"},
    {"armgba_player1_saves", "retroarch"}, {"armgba_load_save", "off"}, {"armgba_send_save", "off"},
    {"armgba_back_up_save", "off"}, {"armgba_shared_controls", "off"}, {"armgba_player2_game", "same"},
};
static const char *const HIDDEN_PREFIX[] = {"armgba_lcd_", "armgba_gb_", "armgba_link_"};
static const char *const HIDDEN[] = {"armgba_r2_follows", "armgba_switch_controller", "armgba_player_numbers",
    "armgba_gamecube_address", "armgba_receive_into", "armgba_load_into", "armgba_keep_progress", "armgba_new_save",
    "armgba_keep_saves", "armgba_list_saves", "armgba_safe_saves"};
#define HELD_DEFAULTS_MAX 64
static struct { const char *key, *value; } heldDefaults[HELD_DEFAULTS_MAX]; // armGBA's defaults of what's hidden
static int heldDefaultsN;
static const char *held_value(const char *key) {
  for (size_t i = 0; i < sizeof HELD / sizeof *HELD; i++)
    if (!strcmp(key, HELD[i].key))
      return HELD[i].value;
  return NULL;
}
static bool option_hidden(const char *key) {
  if (held_value(key))
    return true;
  for (size_t i = 0; i < sizeof HIDDEN_PREFIX / sizeof *HIDDEN_PREFIX; i++)
    if (!strncmp(key, HIDDEN_PREFIX[i], strlen(HIDDEN_PREFIX[i])))
      return true;
  for (size_t i = 0; i < sizeof HIDDEN / sizeof *HIDDEN; i++)
    if (!strcmp(key, HIDDEN[i]))
      return true;
  return false;
}
/* What a hidden option reads as (NULL: not one). */
static const char *hidden_value(const char *key) {
  const char *v = held_value(key);
  if (v || !option_hidden(key))
    return v;
  for (int i = 0; i < heldDefaultsN; i++)
    if (!strcmp(key, heldDefaults[i].key))
      return heldDefaults[i].value;
  return NULL;
}
#endif

/* mGBA defines its options in one call; ours go in with them (an environment may take only one). */
static bool env_wrap(unsigned cmd, void *data) {
  if ((cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL || cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2) && data) {
    const struct retro_core_options_v2 *us =
        cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL ? ((struct retro_core_options_v2_intl *)data)->us : data;
    size_t n = 0;
    while (us && us->definitions[n].key)
      n++;
    static struct retro_core_option_v2_definition *defs; // the last merge's, kept while the frontend may read it
    free(defs);
    defs = calloc(n + PD_OPTION_COUNT + 1, sizeof *defs);
    if (defs && us) {
#ifdef PD_ARMGBA
      size_t kept = 0;
      heldDefaultsN = 0;
      for (size_t i = 0; i < n; i++) {
        const struct retro_core_option_v2_definition *o = &us->definitions[i];
        if (!option_hidden(o->key))
          defs[kept++] = *o;
        else if (heldDefaultsN < HELD_DEFAULTS_MAX)
          heldDefaults[heldDefaultsN].key = o->key, heldDefaults[heldDefaultsN++].value = o->default_value;
      }
      n = kept;
#else
      memcpy(defs, us->definitions, n * sizeof *defs);
#endif
      memcpy(defs + n, pdOptions, sizeof pdOptions);
      static struct retro_core_options_v2 merged;
      static struct retro_core_options_v2_intl mergedIntl;
      merged = (struct retro_core_options_v2){us->categories, defs};
      // The frontend's own language keeps mGBA's translations (ours are English only).
      mergedIntl = (struct retro_core_options_v2_intl){&merged,
          cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL ? ((struct retro_core_options_v2_intl *)data)->local : NULL};
      return realEnv(cmd, cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL ? (void *)&mergedIntl : (void *)&merged);
    }
  }
#ifdef PD_ARMGBA
  if (cmd == RETRO_ENVIRONMENT_GET_VARIABLE && data && ((struct retro_variable *)data)->key) {
    const char *v = hidden_value(((struct retro_variable *)data)->key);
    if (v) {
      ((struct retro_variable *)data)->value = v;
      return true;
    }
  }
  if (cmd == RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO)
    return false; // two linked games: not loaded by retro_load_game_special here
#endif
  if (cmd == RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK) {
    emuAudioStatus = data ? ((struct retro_audio_buffer_status_callback *)data)->callback : NULL;
    struct retro_audio_buffer_status_callback both = {audio_status_both};
    return realEnv(cmd, &both);
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
static int menuW, menuH;
static int canvasScale = 1; // integer game-pixel multiplier for the output canvas
static int panelW, panelH;           // the frontend's screen, 0 = unknown (the canvas option alone then)
static int baseGW = 240, baseGH = 160; // the game's frame before the first picture
static uint32_t *cvs;     // the companion's canvas, 0xRRGGBB
static uint16_t *out565;  // what the frontend gets
static struct pd_canvas canvas;
static unsigned gameW, gameH;

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
static bool wideSupported, wideReady, fieldKnown;
static unsigned wideW, wideH;
static uint32_t wideSource[240 * 160], wideFrame[PK_WIDE_MAX_VIEW_W * PK_WIDE_MAX_VIEW_H];
static const uint16_t *base_pixels(void);
static uint16_t flightPixels[256 * 160];
static bool flightFrame;
static uint32_t wildSwitch;
static inline uint32_t to_xbgr(uint16_t p) {
  uint32_t r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
  g |= g >> 5;   // mGBA's 565 leaves green's low bit 0; the margins (pk_wide color(), mColorFrom555) have g5>>4 there: match them or the middle's edge shows a seam
  return 0xFF000000u | ((b << 3 | b >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (r << 3 | r >> 2);
}
static inline uint32_t xbgr_to_rgb(uint32_t p) { return (p & 255) << 16 | (p >> 8 & 255) << 8 | (p >> 16 & 255); }

/* The Quick Menu uses a larger logical canvas on the Brick. Preserve the frozen expanded view at panel scale. */
static void draw_wide_quick_background(void) {
  const int w = CW * (int)wideH <= CH * (int)wideW ? CW : CH * (int)wideW / (int)wideH;
  const int h = CW * (int)wideH <= CH * (int)wideW ? CW * (int)wideH / (int)wideW : CH;
  const int x0 = (CW - w) / 2, y0 = (CH - h) / 2;
  memset(cvs, 0, (size_t)CW * CH * sizeof *cvs);
  for (int y = 0; y < h; y++) {
    const uint32_t *src = wideFrame + (unsigned)y * wideH / (unsigned)h * wideW;
    uint32_t *dst = cvs + (size_t)(y0 + y) * CW + x0;
    for (int x = 0; x < w; x++)
      dst[x] = xbgr_to_rgb(src[(unsigned)x * wideW / (unsigned)w]);
  }
}

enum { CANVAS_EXPAND, CANVAS_ZOOM, CANVAS_MENU };
static int game_scale(unsigned w, unsigned h) {
  int sx = CW / canvasScale / (int)w, sy = CH / canvasScale / (int)h;
  int scale = sx < sy ? sx : sy;
  return (scale < 1 ? 1 : scale) * canvasScale;
}

/* The game, whole-scale and centred, over a black canvas. */
static void draw_game(void) {
  memset(cvs, 0, (size_t)CW * CH * sizeof *cvs);
  if (!outputBuffer || !gameW || !gameH)
    return;
  if (wideReady) {
    int ws = game_scale(wideW, wideH);
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
  int s = game_scale(gameW, gameH);
  int ox = (CW - (int)gameW * s) / 2, oy = (CH - (int)gameH * s) / 2;
  for (unsigned y = 0; y < gameH; y++) {
    uint32_t *row = cvs + (size_t)(oy + (int)y * s) * CW + ox;
    if (oy + (int)y * s < 0 || oy + ((int)y + 1) * s > CH)
      continue;
    for (unsigned x = 0; x < gameW; x++) {
      uint32_t p = from565(base_pixels()[y * 256 + x]);
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
static const uint16_t *base_pixels(void) { return flightFrame ? flightPixels : outputBuffer; }

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
    int ws = game_scale(wideW, wideH);
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
  int s = game_scale(gameW, gameH);
  int ox = (CW - (int)gameW * s) / 2, oy = (CH - (int)gameH * s) / 2;
  for (unsigned y = 0; y < gameH; y++) {
    int cy = oy + (int)y * s;
    if (cy < 0 || cy + s > CH)
      continue;
    uint16_t *row = out565 + (size_t)cy * CW + ox;
    if (s == 1) {
      memcpy(row, base_pixels() + (size_t)y * 256, (size_t)gameW * sizeof *row);
    } else {
      for (unsigned x = 0; x < gameW; x++)
        for (int j = 0; j < s; j++)
          row[x * s + j] = base_pixels()[y * 256 + x];
      for (int k = 1; k < s; k++)
        memcpy(row + (size_t)k * CW, row, (size_t)gameW * s * sizeof *row);
    }
  }
}

/* An overlay drawn over QUICK_CLEAR and finished (quick_finish): its alpha laid over the canvas at the
 * given integer scale, its top-left at (x, y) in canvas pixels. */
static void blend_overlay(const uint32_t *px, int w, int h, int scale, int x0, int y0) {
  // A zero scale fills the output canvas, sampling the overlay with nearest-neighbor.
  const int width = scale ? w * scale : CW;
  const int height = scale ? h * scale : CH;
  for (int y = 0; y < height; y++) {
    int cy = y0 + y;
    if (cy < 0 || cy >= CH)
      continue;
    const uint32_t *row = px + (scale ? y / scale : y * h / height) * w;
    for (int x = 0; x < width; x++) {
      int cx = x0 + x;
      if (cx < 0 || cx >= CW)
        continue;
      uint32_t p = row[scale ? x / scale : x * w / width], a = p >> 24;
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
// Only a gameplay frame at the current geometry may be repeated.
static bool canDupe, haveGameFrame, repeatedFrame;
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
  repeatedFrame = false;
}

/* ---- input ---- */
static unsigned held, prevHeld, pressedKeys;
static int heldFrames[NKEYS];
static int quickBtn = Y, companionBtn = X, hudBtn = -1; // key indices, -1 none (from settings: key_of)
static unsigned gameKeys; // keys the layer presses for the game (BIKE's SELECT)
static int gameKeyFrames;
static int hudOx, hudOy, hudScale = 1; // where draw_hud last put the HUD on the canvas (hud_hit_at)
static unsigned shortcutKeys, shortcutBlocked;
static bool shortcutExclusive;
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
static bool down(int k) { return k >= 0 && (pressedKeys >> k & 1) != 0; }

/* What mGBA reads while its frame runs. */
static int16_t masked_input(unsigned port, unsigned device, unsigned index, unsigned id) {
  if (port != 0 || device != RETRO_DEVICE_JOYPAD)
    return 0;
  const unsigned keys = shortcutExclusive ? shortcutKeys :
      ((maskGame ? 0 : held & ~shortcutBlocked) | (gameKeyFrames > 0 ? gameKeys : 0));
  if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
    int16_t m = 0;
    for (int k = A; k <= L; k++)
      if (keys >> k & 1)
        m |= 1 << retroId[k];
    return m;
  }
  for (int k = A; k <= L; k++)
    if (retroId[k] == id)
      return (keys >> k) & 1;
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
static char pdDir[1050]; // <save dir>/pokedaisy: settings.ini and FLIGHT AREA's seen maps ("" = none)
static bool companion, companionWas, haveGame;
static int frames;

/* the HUD over the overworld (B2) */
static struct hud_state hudState = {.wild = -1, .focus = -1};
static struct hud_hits hudHits;
static bool hudFocus, hudShown;
static uint32_t *hudPixels;
static uint32_t hudDrawnKey;
static int hudW, hudH;
static int fieldFlags, hudFieldFrames;

/* the quick menu and the game-code jobs: PC, FLY, FLASH, REPEL, BIKE (fly.c, flash.c, field_items.c) */
static bool quickOpen;
static int quickSel;
static const struct pd_config *flyCfg;
/* FLASH: the config whose functions this ROM has, and a pick waiting for a frame the game can be called in (counts the
 * frames it has waited, given up at 120 as the 3DS does). */
static const struct pd_config *flashCfg;
static int flashAsk;
static bool freeFlight, takeOff;
static int areaToldAt = -100000; // when the bird last turned at FLIGHT AREA's edge with a notice (frames)
/* FLY ON and the scenic FLY (the 3DS's flight_configure / fly_to): the profile's words, sent again with MAKEOVER's vehicle
 * when it changes (pk_flight takes a vehicle-only change mid-flight); a FLY pick waiting to be flown over the field (counts
 * the frames it has waited for one the game can be called in), to its map section. The route is linux/flight.c's, over the
 * config's flight record: a game whose config has none flies the classic way. */
static const uint32_t *flightWords;
static int flightVehicle, scenicAsk, scenicMapsec;
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
/* The Game Boy games' PC / FLY / WILD (gb_field.c, the app's GbField): the cart's config, the running frames left before
 * asking whether the game took a queued action, and whether the map's encounters may still be held (put back once). */
static const struct gb_field *gbf;
static int gbTake;
static bool gbWildHeld;
static size_t romLen;
static bool polishedActions;
static struct pd_binput polishedInput;

static int polished_byte(uint32_t addr) {
  uint8_t v = 0;
  pd_lr_read_memory(core, addr, &v, 1);
  return v;
}

static void polished_open_pc(void) {
  const char *why = polished_pc_reason(game.sha1, pd_lr_read_memory, core);
  if (why)
    notice(why);
  else if (!polished_pc_open(core, game.sha1, pd_lr_read_memory, core))
    notice("Could not open the PC");
}

static void polished_use(int item) {
  if (!pd_binput_field_start(&polishedInput, PD_FIELD_USE, item, 0))
    notice(pd_field_input_message(&polishedInput.field));
}

static bool polished_potion(int item) {
  return item == PD_PC_POTION;
}


static void snapshot(void) { pd_snapshot_read(&snap, &game, pd_lr_read_memory, core); }

/* pk_flight told the profile's words with FLY ON's vehicle (again only when it changed). */
static void flight_configure_words(void) {
  if (!flightWords)
    return;
  uint32_t w[PK_FLIGHT_WORDS];
  memcpy(w, flightWords, sizeof w);
  flightVehicle = settings.flightVehicle >= 0 && settings.flightVehicle < FLIGHT_VEHICLES ? settings.flightVehicle : 0;
  w[PK_FLIGHT_VEHICLE] = (uint32_t)flightVehicle;
  pk_flight_configure(w, PK_FLIGHT_WORDS);
}

/* The scenic FLY can plan here: FREE FLIGHT, FLY STYLE SCENIC, and the config's flight record for linux/flight.c's route
 * (retail FireRed rev 0 has a profile but no record yet: classic). */
static bool scenic_ok(void) { return freeFlight && !settings.flyClassic && game.cfg && game.cfg->flightMapGroups; }

/* FLIGHT AREA's seen maps as flight_route takes them (NULL = anywhere, or none known yet). */
static const struct flight_area *scenic_area(void) {
  static struct flight_area a;
  if (settings.flightAnywhere || pd_flight_area_count() <= 0)
    return NULL;
  flight_area_clear(&a, 1);
  for (int id = 0; id < PK_FLIGHT_AREA_BYTES * 8; id++)
    if (pd_flight_area_has(id))
      flight_area_add(&a, id);
  return &a;
}

/* The scenic FLY to [scenicMapsec], between two frames (the 3DS's flight_jobs): up on the bird where the field joins
 * here to there over seen maps, else the game's own FLY (a town past them, Saffron, surfing). */
static void scenic_job(bool busy, bool battle) {
  if (!scenicAsk)
    return;
  if (!pk_call_safe(core)) {
    if (++scenicAsk > 120) {
      scenicAsk = 0;
      notice(fly_reason(FLY_FAILED));
    }
    return;
  }
  scenicAsk = 0;
  snapshot();
  enum fly_result gate = flight_check(core, game.cfg, romData, romLen, &snap, busy, battle);
  if (gate != FLY_OK) {
    notice(fly_reason(gate));
    return;
  }
  pd_flight_area_sync(core, settings.flightAnywhere, true); // the maps up to date before the route is planned
  static uint8_t route[FLIGHT_MAX_STEPS];
  struct flight_spot here, dest;
  int steps = -1;
  if (flight_here(core, game.cfg, &here) &&
      flight_destination(core, game.cfg, romData, romLen, scenicMapsec, &dest))
    steps = flight_route(core, game.cfg, romData, romLen, &here, &dest, scenic_area(), route);
  int code = steps < 0 ? PK_FLIGHT_NOT_NOW : pk_flight_take_off(core);
  if (steps >= 0 && code == PK_FLIGHT_OK) {
    pk_flight_route(route, steps);
    return;
  }
  if (steps >= 0 && code != PK_FLIGHT_NOT_ON_FOOT) {
    notice(flight_reason(code));
    return;
  }
  flyMapsec = scenicMapsec; // no route, or surfing: the game's own FLY can
  flyStage = 1;
  flyWait = 0;
}

static int hud_wild_uncaught(const struct pd_game *g, const struct pd_snapshot *s) {
  struct pd_wild_set w;
  if (!g->cfg || (!g->cfg->gb && !g->cfg->wildHeaders)) // the Game Boy games' come from their GUIDE data
    return -1;
  if (!pd_wild_here(g, s, &w))
    return -1;
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
  return n ? left : -1;
}

/* The HUD over the game's own picture, a canvas pixel a game pixel: SIZE picks the mini HUD (SMALL, NORMAL) or the full
 * one (LARGE). */
static void draw_hud(void) {
  int gs = game_scale(gameW, gameH);
  int ax = (CW - (int)gameW * gs) / 2, ay = (CH - (int)gameH * gs) / 2, aw = (int)gameW * gs, ah = (int)gameH * gs;
  if (wideReady) { // the wide view (EXPAND, ZOOM OUT) is what is on screen: anchor to it, not to the centred game picture
    int ws = game_scale(wideW, wideH);
    aw = (int)wideW * ws;
    ah = (int)wideH * ws;
    ax = (CW - aw) / 2;
    ay = (CH - ah) / 2;
  }
  // SMALL / NORMAL: the mini HUD (a core's canvas is the game's own pixels, shown 3x to 6x); LARGE: the full one.
  hudState.mini = settings.hudSize != PD_HUD_LARGE;
  int scale = canvasScale;
  int w = aw / scale, hh = ah / scale;
  if (!hudPixels)
    hudPixels = malloc((size_t)CANVAS_MAX_W * CANVAS_MAX_H * sizeof *hudPixels);
  if (!hudPixels)
    return;
  uint32_t key = hud_key(&game, &snap, &hudState, &settings) ^ (uint32_t)(w * 65599 + hh);
  if (key != hudDrawnKey || w != hudW || hh != hudH) {
    struct pd_canvas hc;
    pd_canvas_init(&hc, w, hh, hudPixels);
    hudShown = hud_draw(&hc, &game, &snap, &hudState, &settings, &hudHits);
    quick_finish(&hc);
    hudDrawnKey = key;
    hudW = w;
    hudH = hh;
  }
  hudOx = ax + (aw - w * scale) / 2;
  hudOy = ay + (ah - hh * scale) / 2;
  hudScale = scale;
  if (hudShown)
    blend_overlay(hudPixels, w, hh, scale, hudOx, hudOy);
}


/* ---- game-code layer: EXPAND (pk_wide), the field read, WILD ENCOUNTERS ---- */


/* The EXPAND window for the canvas and a fw x fh game: the canvas over the game's whole scale (one less for
 * ZOOM OUT), within pk_wide's largest view, never smaller than the game, even margins. */
static void wide_window(int aspect, unsigned fw, unsigned fh, unsigned *w, unsigned *h) {
  int width = CW / canvasScale, height = CH / canvasScale;
  int sx = width / (int)fw, sy = height / (int)fh;
  int scale = sx < sy ? sx : sy;
  if (aspect == PD_ASPECT_ZOOM_OUT)
    scale--;
  if (scale < 1)
    scale = 1;
  unsigned x = (unsigned)width / scale, y = (unsigned)height / scale;
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

#ifdef PD_WIDECHECK
/* EXPAND's ground rule: the rebuilt window's middle (the game's own 240x160) must equal the frame the emulator drew, pixel
 * for pixel. Counted per frame while the scene is the field; a summary at unload (-DPD_WIDECHECK, a test build only). */
static unsigned wcAll, wcField, wcBad, wcWorst;
static int wcFirstFrame = -1, wcFirstX, wcFirstY;
static void wide_check(unsigned fw, unsigned fh, struct PkWideView v) {
  wcAll++;
  if (pk_wide_scene() != PK_WIDE_SCENE_FIELD || pk_wide_fault())
    return;
  wcField++;
  unsigned bad = 0;
  for (unsigned y = 0; y < fh; y++)
    for (unsigned x = 0; x < fw; x++)
      if (wideFrame[(size_t)((int)y - v.y) * wideW + (size_t)((int)x - v.x)] != wideSource[y * fw + x]) {
        if (!bad && wcFirstFrame < 0)
          wcFirstFrame = (int)wcAll, wcFirstX = (int)x, wcFirstY = (int)y;
        bad++;
      }
  if (bad) {
    wcBad++;
    if (bad > wcWorst)
      wcWorst = bad;
  }
}
static void wide_check_report(void) {
  fprintf(stderr, "pokedaisy: widecheck frames %u, field frames %u, frames with a differing middle %u, worst %u px%s\n", wcAll,
          wcField, wcBad, wcWorst, wcFirstFrame < 0 ? "" : " (first: see below)");
  if (wcFirstFrame >= 0)
    fprintf(stderr, "pokedaisy: widecheck first difference: frame %d at (%d,%d)\n", wcFirstFrame, wcFirstX, wcFirstY);
}
#define WIDE_CHECK(fw, fh, v) wide_check((fw), (fh), (v))
#define WIDE_CHECK_REPORT() wide_check_report()
#else
#define WIDE_CHECK(fw, fh, v) ((void)0)
#define WIDE_CHECK_REPORT() ((void)0)
#endif

#ifdef PD_CALLTEST
/* A test build's check of the game-call layer (pk_call): FLY's own first question, asked every 300 frames from the 2500th
 * while the field is free, through the real functions (the game's FlagGet and its Fly set-up); the result codes, towns and,
 * on armGBA, the steps each call took are printed, and the player's last position at unload. The same on both cores, one
 * save: their answers must agree, and the game must go on as before. */
static int ctFrame, ctCalls, ctStage, ctWait, ctMapsec;
static uint32_t ctCallback;
static struct fly_targets ctT;
#ifdef PD_ARMGBA
long pk_call_last_steps(void);
#define CT_STEPS() pk_call_last_steps()
#else
#define CT_STEPS() 0L
#endif
static void call_test(bool busy, bool battle) {
  ctFrame++;
#ifdef PD_CALLTEST_PC
  /* PC anywhere: the game's own PC menu, opened by a script written to EWRAM and run by the game (pc_open). Once, frame 3000. */
  if (flyCfg && ctFrame == 3000) {
    enum item_result r = pc_open(core, game.cfg, romData, romLen, busy, battle);
    fprintf(stderr, "pokedaisy: calltest frame %d pc_open -> %d (%ld steps), busy %d\n", ctFrame, (int)r, CT_STEPS(), busy);
  }
  return;
#endif
  if (!flyCfg || ctFrame < 2500 || ctStage >= 3 || !pk_call_safe(core))
    return;
  if (ctStage == 0 && ctFrame % 300 == 0) {
    snapshot();
    fly_targets(core, flyCfg, romData, romLen, &snap, busy, battle, &ctT);
    ctCalls++;
    fprintf(stderr, "pokedaisy: calltest frame %d busy %d: result %d flyer %d towns %d first %d (last call %ld steps)\n", ctFrame,
            busy, (int)ctT.result, ctT.flyerSlot, ctT.count, ctT.count ? ctT.towns[0] : -1, CT_STEPS());
    if (ctT.result == FLY_OK && ctT.count) {
      ctMapsec = ctT.towns[0];
      ctStage = 1;
    }
  } else if (ctStage == 1 && !busy) { /* FLY's first step: the checks again, then the Fly map closing: controls locked, a fade */
    snapshot();
    enum fly_result r = fly_start(core, flyCfg, romData, romLen, &snap, ctMapsec, &ctT, busy, battle);
    fprintf(stderr, "pokedaisy: calltest frame %d fly_start -> %d (%ld steps), from map %d.%d (%d,%d)\n", ctFrame, (int)r, CT_STEPS(),
            snap.mapGroup, snap.mapNum, snap.x, snap.y);
    ctCallback = fly_main_callback(core, flyCfg);
    ctWait = 0;
    ctStage = r == FLY_OK ? 2 : 3;
  } else if (ctStage == 2 && ++ctWait >= FLY_FADE_FRAMES) { /* the second: the game flies */
    bool ok = fly_finish(core, flyCfg, romData, romLen, ctMapsec, &ctT, fly_main_callback(core, flyCfg) != ctCallback);
    fprintf(stderr, "pokedaisy: calltest frame %d fly_finish -> %d (%ld steps)\n", ctFrame, ok, CT_STEPS());
    ctStage = 3;
  }
}
static void call_test_report(void) {
  snapshot();
  fprintf(stderr, "pokedaisy: calltest calls %d, stage %d, player at map %d.%d (%d,%d)\n", ctCalls, ctStage, snap.mapGroup,
          snap.mapNum, snap.x, snap.y);
}
#define CALL_TEST(busy, battle) call_test((busy), (battle))
#define CALL_TEST_REPORT() call_test_report()
#else
#define CALL_TEST(busy, battle) ((void)0)
#define CALL_TEST_REPORT() ((void)0)
#endif

/* After every game frame: pk_wide's RAM history, and (wideReady) this frame's window. */
static void wide_tick(void) {
  wideReady = false;
  flightFrame = false;
  if (!wideSupported && (!freeFlight || pk_flight_state() == PK_FLIGHT_IDLE))
    return;
  unsigned fw = wideSupported ? (unsigned)pk_wide_frame_w() : 240;
  unsigned fh = wideSupported ? (unsigned)pk_wide_frame_h() : 160;
  PF_T(cv0);
  for (unsigned y = 0; y < fh; y++)
    for (unsigned x = 0; x < fw; x++)
      wideSource[y * fw + x] = to_xbgr(outputBuffer[y * 256 + x]);
  if (freeFlight && pk_flight_state() != PK_FLIGHT_IDLE && fw == 240 && fh == 160) {
    // Its shadow, clouds and bird; with EXPAND drawing its picture next, the clouds go over that (pk_flight_draw_wide).
    pk_flight_draw(wideSource, 240, 160, 240, !(wideSupported && settings.aspect != PD_ASPECT_ORIGINAL));
    for (unsigned y = 0; y < 160; y++)
      row_to565(flightPixels + y * 256, wideSource + y * 240, 240);
    flightFrame = true;
  }
  if (!wideSupported)
    return;
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
  if (freeFlight)
    pk_flight_draw_wide(wideFrame, v.w, v.h, v.x, v.y); // FREE FLIGHT's clouds over the whole picture
  WIDE_CHECK(fw, fh, v);
  PF_T(vw1);
  pf_add(PF_VIEW, vw1 - vw0);
  wideReady = !pk_wide_fault();
}

/* The canvas for a fw x fh game on the panel: the panel over the game's whole scale (one less for ZOOM OUT), so the
 * frontend shows it at exactly that integer scale. EXPAND's window is the whole canvas. */
static void canvas_for(int fw, int fh, int kind, int *w, int *h) {
  int s = panelW / fw < panelH / fh ? panelW / fw : panelH / fh;
  if (s < 1)
    s = 1;
  if (kind != CANVAS_EXPAND && s > 1)
    s--;
  // The companion and its menus are laid out for 320x240 at least: a Game Boy game's ZOOM OUT on a 1024x768 panel is
  // 256x192, where text overran its boxes. Menus step down until the canvas holds them.
  while (kind == CANVAS_MENU && s > 1 && (panelW / s < 320 || panelH / s < 240))
    s--;
  int x = panelW / s, y = panelH / s;
  *w = x < fw ? fw : x > CANVAS_MAX_W ? CANVAS_MAX_W : x;
  *h = y < fh ? fh : y > CANVAS_MAX_H ? CANVAS_MAX_H : y;
}

static void choose_canvas(void) {
  const char *v = pd_option("pokedaisy_canvas");
  int w = 320, h = 240;
  if (v && sscanf(v, "%dx%d", &w, &h) != 2)
    w = 320, h = 240;
  CWMAX = w < 320 ? 320 : w > CANVAS_MAX_W ? CANVAS_MAX_W : w;
  CHMAX = h < 240 ? 240 : h > CANVAS_MAX_H ? CANVAS_MAX_H : h;
  struct retro_system_av_info av;
  mgba_retro_get_system_av_info(&av);
  baseGW = (int)av.geometry.base_width;
  baseGH = (int)av.geometry.base_height;
  panelW = panelH = 0;
  v = pd_option("pokedaisy_panel");
  if (v && sscanf(v, "%dx%d", &panelW, &panelH) == 2 && panelW > 0 && panelH > 0)
    canvas_for(baseGW, baseGH, CANVAS_MENU, &CWMAX, &CHMAX);
  else
    panelW = panelH = 0;
  menuW = CW = CWMAX;
  menuH = CH = CHMAX;
  canvasScale = 1;
  if (PD_BE_IS_GBA()) {
    CWMAX = CANVAS_MAX_W;
    CHMAX = CANVAS_MAX_H;
  }
}

/* Keep GBA Quick Menu on the selected gameplay canvas. Companion has its own layout. */
static int want_canvas(void) {
  if (companion || (quickOpen && !PD_BE_IS_GBA()))
    return CANVAS_MENU;
  if (settings.aspect == PD_ASPECT_ORIGINAL)
    return PD_BE_IS_GBA() ? CANVAS_EXPAND : CANVAS_ZOOM;
  if (!wideSupported || settings.aspect != PD_ASPECT_ZOOM_OUT)
    return CANVAS_EXPAND;
  return settings.autoZoom && !rewinding && pk_wide_scene() != PK_WIDE_SCENE_FIELD ? CANVAS_EXPAND : CANVAS_ZOOM;
}

/* Follow the aspect: when the wanted canvas differs, resize it and tell the frontend (it rescales to the new whole factor). */
static void canvas_update(bool notify) {
  int w = menuW, h = menuH, scale = 1;
  unsigned fw = gameW ? gameW : (unsigned)baseGW, fh = gameH ? gameH : (unsigned)baseGH;
  if (PD_BE_IS_GBA() && !companion && settings.aspect == PD_ASPECT_ORIGINAL && fw && fh) {
    w = (int)fw;
    h = (int)fh;
  } else if (panelW)
    canvas_for((int)fw, (int)fh, want_canvas(), &w, &h);
  // Keep extra pixels available for the menu throughout gameplay. Double the game
  // pixels exactly, then draw menu text and icons directly at output resolution.
  if (PD_BE_IS_GBA() && !companion && (w < 320 || h < 240) && w <= CWMAX / 2 && h <= CHMAX / 2) {
    scale = 2;
    w *= scale;
    h *= scale;
  }
  if (w == CW && h == CH && scale == canvasScale)
    return;
  CW = w;
  CH = h;
  canvasScale = scale;
  haveGameFrame = false;
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
  /* A frame the capture can't hold (armGBA's larger LCD / link pictures) keeps the last one: the drawing reads
   * outputBuffer at gameW x gameH. */
  if (!PD_BE_CAPTURE(d, w, h, pitch))
    return;
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
#ifdef PD_ARMGBA
  info->valid_extensions = "gba|bin"; // armGBA's Game Boy side (Gambatte) isn't in this core
#endif
}
/* Linked games (a subsystem): the companion follows one game, loaded by retro_load_game. */
RETRO_API bool retro_load_game_special(unsigned type, const struct retro_game_info *info, size_t n) {
  (void)type;
  (void)info;
  (void)n;
  return false;
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
  /* No game: armGBA offers "Start Core" (SET_SUPPORT_NO_GAME), but the companion needs one (PD_BE_AFTER_LOAD reads it). */
  if (!g || !mgba_retro_load_game(g))
    return false;
  PD_BE_AFTER_LOAD(g);
  choose_canvas();
  read_options();
  {
    struct retro_audio_buffer_status_callback abc = {audio_status_both};
    realEnv(RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK, &abc);
  }
  frameLate = false;
  cvs = calloc((size_t)CWMAX * CHMAX, sizeof *cvs);
  out565 = calloc((size_t)CWMAX * CHMAX, sizeof *out565);
  if (!cvs || !out565)
    return false;
  pd_canvas_init(&canvas, CW, CH, cvs);
  uint32_t crc = 0;
  PD_BE_CRC32(&crc);
  const unsigned char *rom = PD_BE_ROM();
  size_t len = PD_BE_ROM_LEN();
  pd_game_detect(&game, rom, len);
  game.rom = rom;
  game.romSize = len;
  pd_game_identify(&game);
  fprintf(stderr, "pokedaisy: %s, sha1 %s%s%s\n", game.title ? game.title : "?", game.sha1[0] ? game.sha1 : "-",
          game.unsupported ? " - " : "", game.unsupported ? game.unsupported : "");
  if (PD_BE_IS_GBA())
    pd_romart_scan(&game);
  const char *dir = NULL;
  if (realEnv(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY, &dir) && dir && *dir) {
    snprintf(pdDir, sizeof pdDir, "%s/pokedaisy", dir);
    mkdir(pdDir, 0755);
    snprintf(settingsPath, sizeof settingsPath, "%s/settings.ini", pdDir);
  } else {
    *pdDir = 0;
    snprintf(settingsPath, sizeof settingsPath, "pokedaisy-settings.ini");
  }
  pd_settings_load(&settings, settingsPath);
  // A resident frontend loads the core again in the same process: nothing of the last game may survive.
  memset(&snap, 0, sizeof snap);
  memset(&hudState, 0, sizeof hudState);
  hudState.wild = hudState.focus = -1;
  memset(&hudHits, 0, sizeof hudHits);
  haveGameFrame = repeatedFrame = false;
  canDupe = false;
  realEnv(RETRO_ENVIRONMENT_GET_CAN_DUPE, &canDupe);
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
  wideSupported = pk_wide_configure(crc) != 0;   // Polished Crystal 3.2.3 too (pk_wide_gb.c, by its CRC)
  wideReady = false;
  canvas_update(false);
  fieldKnown = PD_BE_IS_GBA() && pk_field_configure(crc);
  wildSwitch = fieldKnown ? pk_wild_switch_find(rom, len) : 0;
  if (wildSwitch)
    pk_field_wild(wildSwitch, settings.wildOff);
  pk_rest_reset();
  fieldFlags = 0;
  romData = rom;
  romLen = len;
  bool gba = PD_BE_IS_GBA();
  flyCfg = game.cfg && gba && fly_supported(game.cfg, rom, len) ? game.cfg : NULL;
  flashCfg = game.cfg && gba && flash_supported(game.cfg, rom, len) ? game.cfg : NULL;
  flashAsk = 0;
  freeFlight = takeOff = flightFrame = false;
  flightWords = NULL;
  scenicAsk = 0;
  pk_flight_set_call(pk_call);
  pk_flight_configure(NULL, 0);
  const struct pd_flight_area_cfg *areaCfg = NULL;
  for (size_t i = 0; flyCfg && i < sizeof flight_profiles / sizeof flight_profiles[0]; i++) {
    if (!strcmp(game.sha1, flight_profiles[i].sha1)) {
      flightWords = flight_profiles[i].words;
      flight_configure_words();
      areaCfg = &flight_profiles[i].area;
      freeFlight = true;
      break;
    }
  }
  // FLIGHT AREA: the save's seen maps beside settings.ini (the app's FlightArea.Keeper), the first sync seeding them.
  pd_flight_area_begin(areaCfg, rom, len, crc, *pdDir ? pdDir : NULL);
  areaToldAt = -100000;
  itemsOk = game.cfg && gba && field_items_supported(game.cfg, rom, len);
  pcOk = itemsOk && pc_supported(game.cfg, rom, len);
  bikeOk = itemsOk && bike_supported(game.cfg);
  gbf = !gba && game.cfg ? gb_field_find(rom, len) : NULL;
  polishedActions = !gba && game.cfg && polished_field_supported(game.sha1);
  shortcutBlocked = 0;
  shortcutExclusive = false;
  gbTake = 0;
  gbWildHeld = gbf != NULL; // a resume state taken while OFF carries the held rates: put back once when ON
  quickOpen = flyList = repelList = false;
  flyAsk = flyStage = 0;
  job = JOB_NONE;
  quick_set_grid(settings.quickGrid);
  if (!ovPixels)
    ovPixels = calloc((size_t)CWMAX * CHMAX, sizeof *ovPixels);
  pd_ui_init(&ui, &settings);
  ui.handheldDex = ui.handheldParty = ui.guideController = ui.shoulderTabs = true;
  if (polishedActions) {
    pd_binput_init(&polishedInput, &game, pd_lr_read_memory, NULL, core);
    ui.battleInput = &polishedInput;
  }
  memset(&host, 0, sizeof host);
  host.hideScreenMode = true;
  host.hasQuickMenu = true;
  host.hasButtonRows = true;
  host.frontendOwnsPlayback = true;
  // The Game Boy games get the HUD where the field scene is known (English Yellow, Crystal: pk_wide_gb), as in the app.
  host.hasHud = PD_BE_IS_GBA() || wideSupported;
  host.hasExpand = true;
  host.hasFlightArea = freeFlight;
  host.flightSupported = freeFlight; // FLY STYLE / FLIGHT AREA / FLY ON (SCENIC needs scenic_ok, else classic)
  host.itemCanUse = polishedActions ? polished_potion : NULL;
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

/** After a state load or a reset the game is another one: nothing the layer had started may finish in it (a FLY's warp,
 * the bike's registered item put back, a Game Boy action withdrawn), and EXPAND's RAM history is from before the jump. */
static void layer_jump(void) {
  takeOff = flightFrame = false;
  scenicAsk = 0;
  pk_flight_reset();
  pd_flight_area_resend(); // the loaded game may be another save: checked and sent again at the next sync
  if (wideSupported)
    pk_wide_jump(core, wideSource); // the loaded RAM is the next frame's history: the map past the edges at once
  else
    pk_wide_reset();
  haveGameFrame = repeatedFrame = false;
  wideReady = false;
  snap.valid = false;
  job = JOB_NONE;
  flyAsk = flyStage = 0;
  gbTake = 0;
  gameKeyFrames = 0;
  shortcutExclusive = false;
  shortcutBlocked = polishedActions ? held : 0;
  if (polishedActions) {
    pd_binput_init(&polishedInput, &game, pd_lr_read_memory, NULL, core);
    ui.battleChoosingMove = ui.battleChoosingMon = false;
  }
  gbWildHeld = gbf != NULL; // a state taken while OFF carries the held rates: put back once when ON
}

RETRO_API bool retro_unserialize(const void *d, size_t n) {
#ifdef PD_ARMGBA
  /* armGBA's state is the whole console, the cartridge's save memory with it, and its unserialize puts that back ("A
   * loaded state has every console's save as it was"): a frontend that writes the save file afterwards would take the
   * player's in-game save back to the state's. The save stays the cartridge's here, as in the mGBA core (states load
   * with SAVESTATE_RTC only) and the DS core (load_state_keeping_save): a state load or a rewind step never touches it. */
  static uint8_t keep[0x20000]; /* the largest GBA save, 1 Mbit flash */
  uint8_t *save = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
  size_t saveSize = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
  bool kept = save && saveSize && saveSize <= sizeof keep;
  if (kept)
    memcpy(keep, save, saveSize);
#endif
  bool r = mgba_retro_unserialize(d, n);
#ifdef PD_ARMGBA
  if (r && kept) {
    uint8_t *now = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (now && retro_get_memory_size(RETRO_MEMORY_SAVE_RAM) == saveSize)
      memcpy(now, keep, saveSize);
  }
#endif
  if (r && haveGame)
    layer_jump();
  return r;
}

RETRO_API void retro_reset(void) {
  mgba_retro_reset();
  if (haveGame)
    layer_jump();
}

RETRO_API void retro_unload_game(void) {
  pk_flight_configure(NULL, 0);
  pd_flight_area_end();
  pk_flight_set_area(NULL, 0);
  freeFlight = takeOff = flightFrame = false;
  pk_wide_configure(0);
  WIDE_CHECK_REPORT();
  CALL_TEST_REPORT();
  wideSupported = wideReady = false;
  PD_BE_BEFORE_UNLOAD();
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
  hudState.focus = hud_first(&hudHits);
}

/* What a HUD part does when pressed (the HUD cursor's A, or a tap): its companion tab, or the game's PC. */
static void hud_press(int id) {
  if (id >= HUD_SLOT && id < HUD_SLOT + PD_PARTY_SIZE) {
    ui.tab = PD_TAB_PARTY;
    ui.summarySlot = id - HUD_SLOT;
    ui.summaryLearnRow = 0;
    ui.summaryLearn = ui.summaryInfo = ui.summaryStats = false;
  } else if (id == HUD_MAP || id == HUD_MAP_BUTTON) {
    ui.tab = PD_TAB_MAP;
    ui.mapPage = -1;
  } else if (id == HUD_BALLS || id == HUD_REPEL || id == HUD_BAG) {
    ui.tab = PD_TAB_BAG;
  } else if (id == HUD_WILD) {
    ui.tab = PD_TAB_GUIDE;
    ui.guidePage = 0;
    pd_ui_guide_reset(&ui);
  } else if (id == HUD_PC) {
    if (polishedActions) {
      shortcutBlocked |= held;
      polished_open_pc();
    } else {
      job = JOB_PC; // the game's own PC, as the quick menu's
      jobFrames = 0;
    }
  }
  if (id != HUD_PC)
    companion = true;
}

/* A touch panel's finger (the RG Rotate passes it as the libretro pointer over the whole picture), in canvas pixels:
 * where it is, where it went down, and last frame's state. */
static bool touchDown, touchWas;
static int touchX, touchY, touchX0, touchY0;
/* Use Companion's logical size in a GBA view that shows it at a whole scale (the same on both axes), so the pixel font
 * stays even; any other canvas draws the menu at its own size, 1:1. */
static bool quick_use_menu_canvas(void) {
  return PD_BE_IS_GBA() && menuW > 0 && menuH > 0 && CW % menuW == 0 && CH % menuH == 0 && CW / menuW == CH / menuH;
}
static int quick_width(void) { return quick_use_menu_canvas() ? menuW : CW; }
static int quick_height(void) { return quick_use_menu_canvas() ? menuH : CH; }

static void touch_poll(void) {
  touchWas = touchDown;
  touchDown = realInput && realInput(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_PRESSED);
  if (!touchDown)
    return;
  int x = realInput(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_X);
  int y = realInput(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_Y);
  touchX = (int)((x + 0x7fff) * (long)CW / 0xfffe);
  touchY = (int)((y + 0x7fff) * (long)CH / 0xfffe);
  touchX = touchX < 0 ? 0 : touchX >= CW ? CW - 1 : touchX;
  touchY = touchY < 0 ? 0 : touchY >= CH ? CH - 1 : touchY;
  if (!touchWas) {
    touchX0 = touchX;
    touchY0 = touchY;
  }
}
static bool touch_tapped(void) { return touchWas && !touchDown; }
static int hud_hit_at(int x, int y) {
  // hudHits are in the HUD's own canvas, which draw_hud blends at (hudOx, hudOy), hudScale canvas pixels a pixel.
  if (hudScale < 1 || x < hudOx || y < hudOy)
    return -1;
  x = (x - hudOx) / hudScale;
  y = (y - hudOy) / hudScale;
  for (int i = 0; i < hudHits.n; i++)
    if (x >= hudHits.at[i].x && x < hudHits.at[i].x + hudHits.at[i].w && y >= hudHits.at[i].y &&
        y < hudHits.at[i].y + hudHits.at[i].h)
      return hudHits.at[i].id;
  return -1;
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
  // The frontend's poll, every frame: mGBA's run polls only when the game runs, and the layer pauses it (companion,
  // quick menu, HUD cursor), where a frontend that refreshes the pad in its poll left every button frozen.
  PD_BE_INPUT_POLL();
  poll_keys();
  shortcutBlocked &= held;
  const bool fieldRunning = polishedActions && polishedInput.field.result == PD_FIELD_RUNNING;
  const bool inputRunning = polishedActions && pd_binput_exclusive(&polishedInput);
  if (fieldRunning && down(B)) {
    pd_binput_cancel(&polishedInput);
    notice(pd_field_input_message(&polishedInput.field));
  }
  const bool airborne = freeFlight && pk_flight_state() != PK_FLIGHT_IDLE;
  if (inputRunning || airborne)
    pressedKeys = 0;
  touch_poll();
  if (inputRunning || airborne)
    touchDown = touchWas = false;
  if ((polishedActions || flashCfg) && down(quickBtn) && !quickOpen && !companion)
    snapshot(); // FLASH dims on who knows it and the lit flag: read as the menu opens, not up to 30 frames old
  // A tap on the quick menu: a tile (or a FLY / REPEL list row) is picked as A picks it; outside, the list or the menu
  // closes as B. On the field, a tap that starts and ends on one HUD part presses it; the game picture itself does nothing.
  if (touch_tapped() && quickOpen && !companion) {
    const int x = touchX * quick_width() / CW, y = touchY * quick_height() / CH;
    int i = (flyList || repelList) ? quick_list_hit(x, y) : quick_hit(x, y);
    if (i >= 0) {
      if (flyList)
        flySel = i;
      else if (repelList)
        repelSel = i;
      else
        quickSel = i;
      pressedKeys |= 1u << A;
    } else
      pressedKeys |= 1u << B;
  } else if (touch_tapped() && !quickOpen && !companion && hudShown) {
    int id = hud_hit_at(touchX0, touchY0);
    if (id >= 0 && id == hud_hit_at(touchX, touchY)) {
      hudFocus = false;
      hudState.focus = -1;
      hud_press(id);
    }
  }
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
      hud_press(id);
    } else {
      int dir = down(UP) ? UP : down(DOWN) ? DOWN : down(LEFT) ? LEFT : down(RIGHT) ? RIGHT : -1;
      if (dir >= 0)
        hudState.focus = hud_step(&hudHits, hudState.focus, (dir == RIGHT) - (dir == LEFT), (dir == DOWN) - (dir == UP));
    }
  }


  // The QUICK MENU over the paused game, in the app's order (QuickAction): PC, FLY, TAKE OFF, FLASH, REPEL, BIKE, WILD,
  // COMPANION; each only where the game can, dimmed with why when not now.
  struct quick_item quick[QUICK_COUNT];
  int quickN = 0;
  char repelWhy[32] = "";
  const bool pcMenu = polishedActions && (quickOpen || down(quickBtn));
  const char *pcFieldWhy = pcMenu ? pd_field_input_reason(game.sha1, pd_lr_read_memory, core) : NULL;
  if (pcOk || gbf || polishedActions)
    quick[quickN++] = (struct quick_item){QUICK_PC, false, false,
        pcMenu ? polished_pc_reason(game.sha1, pd_lr_read_memory, core) : NULL, 0, 0, NULL};
  if (flyCfg || gbf || polishedActions) {
    int flyer = pcMenu ? pd_field_input_flyer(game.sha1, pd_lr_read_memory, core) : fly_flyer(&snap);
    const char *why = flyer < 0 ? "No one in your party knows FLY" : NULL;
    if (pcMenu)
      why = pcFieldWhy ? pcFieldWhy : pd_field_input_fly_reason(game.sha1, pd_lr_read_memory, core);
    quick[quickN++] = (struct quick_item){QUICK_FLY, false, false, why,
        flyer < 0 || flyer >= snap.partyCount ? 0 : snap.party[flyer].species, 0, NULL};
  }
  if (freeFlight)
    quick[quickN++] = (struct quick_item){QUICK_FLIGHT, false, false,
        fly_flyer(&snap) < 0 ? "No one in your party knows FLY" : NULL, 0, 0, NULL};
  if (flashCfg) {
    // FLASH (QuickMenu.kt's quickBlocked): who knows it, then whether it's dark here at all.
    int dark = flash_dark(core, flashCfg, &snap);
    quick[quickN++] = (struct quick_item){QUICK_FLASH, false, false,
        flash_flasher(&snap) < 0 ? flash_reason(FLASH_NO_FLASHER) : dark == 0 ? flash_reason(FLASH_CANT_USE_HERE) : NULL,
        0, 0, NULL};
  }
  if (itemsOk || polishedActions) {
    repelKindCount = 0;
    for (int k = 0; k < REPEL_KINDS; k++) {
      int item = polishedActions ? (int)pd_pc_repels[k].id : repel_kinds[k][0];
      if ((!polishedActions || pcMenu) && bag_count(&snap, item) > 0)
        repelKinds[repelKindCount++] = item;
    }
    int left = pcMenu ? polished_byte(PD_PC_REPEL_STEPS) : itemsOk ? repel_steps(core, game.cfg) : 0;
    if (left)
      snprintf(repelWhy, sizeof repelWhy, "%d steps left", left);
    const char *why = pcFieldWhy ? pcFieldWhy : left ? repelWhy : repelKindCount ? NULL : "No REPEL in your bag";
    int icon = polishedActions ? (int)pd_pc_repels[0].id : repel_kinds[0][0];
    quick[quickN++] = (struct quick_item){QUICK_REPEL, false, false, why, 0,
        repelKindCount ? repelKinds[repelKindCount - 1] : icon, NULL};
  }
  if (bikeOk || polishedActions) {
    int bike = polishedActions ? (pcMenu && bag_count(&snap, PD_PC_BICYCLE) ? PD_PC_BICYCLE : 0) : bag_bike(&snap);
    bool riding = pcMenu ? polished_byte(PD_PC_PLAYER_STATE) == 1 :
        (fieldFlags & PK_FIELD_AVATAR) && (fieldFlags & PK_FIELD_BIKE);
    quick[quickN++] = (struct quick_item){QUICK_BIKE, false, false,
        pcFieldWhy ? pcFieldWhy : bike ? NULL : "No bike in your bag", 0,
        bike ? bike : polishedActions ? PD_PC_BICYCLE : 360, riding ? "GET OFF" : NULL};
  }
  if (wildSwitch || gbf || polishedActions)
    quick[quickN++] = (struct quick_item){QUICK_WILD, settings.wildOff, false,
        settings.wildOff ? NULL : pcFieldWhy, 0, 0, NULL};
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
      if (polishedActions) {
        shortcutBlocked |= held; // the A that picked it stays out of the game
        polished_use(repelKinds[repelSel]);
      }
      else {
        job = JOB_REPEL;
        jobItem = repelKinds[repelSel];
        jobFrames = 0;
      }
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
      if (scenic_ok()) {
        scenicMapsec = flyMapsec; // FLY STYLE SCENIC: over the field, planned between two frames
        scenicAsk = 1;
      } else {
        flyStage = 1;
        flyWait = 0;
      }
    }
  } else if (quickOpen && down(B)) {
    quickOpen = false;
  } else if (quickOpen) {
    int dx = down(RIGHT) - down(LEFT), dy = down(DOWN) - down(UP);
    if (dx || dy)
      quickSel = quick_step(quickN, quickSel, dx, dy, quick_width(), quick_height());
    if (down(A) && !quick[quickSel].why && quick[quickSel].action == QUICK_FLIGHT) {
      quickOpen = false;
      takeOff = true;
      shortcutBlocked |= held;
    } else if (down(A) && !quick[quickSel].why && quick[quickSel].action == QUICK_FLY) {
      if (polishedActions) {
        quickOpen = false;
        shortcutBlocked |= held; // the A that picked it stays out of the game
        if (!pd_binput_field_start(&polishedInput, PD_FIELD_FLY, 0, 0))
          notice(pd_field_input_message(&polishedInput.field));
      } else
        flyAsk = 1; // answered below, between two frames
    } else if (down(A) && !quick[quickSel].why && quick[quickSel].action == QUICK_REPEL && repelKindCount > 1) {
      for (int k = 0; k < repelKindCount; k++) {
        int steps = 0;
        for (int r = 0; r < REPEL_KINDS; r++)
          if ((polishedActions ? (int)pd_pc_repels[r].id : repel_kinds[r][0]) == repelKinds[k])
            steps = repel_kinds[r][1];
        snprintf(repelRows[k], sizeof repelRows[k], "%s x%d  %d steps", pd_item_name(&game, repelKinds[k]),
                 bag_count(&snap, repelKinds[k]), steps);
        repelNames[k] = repelRows[k];
      }
      repelList = true;
      repelSel = repelKindCount - 1;
    } else if (down(A) && !quick[quickSel].why) {
      quickOpen = false;
      if (polishedActions)
        shortcutBlocked |= held;
      switch (quick[quickSel].action) {
      case QUICK_PC:
        if (polishedActions)
          polished_open_pc();
        else {
          job = JOB_PC;
          jobFrames = 0;
        }
        break;
      case QUICK_REPEL:
        if (polishedActions)
          polished_use(repelKinds[0]);
        else {
          job = JOB_REPEL;
          jobItem = repelKinds[0];
          jobFrames = 0;
        }
        break;
      case QUICK_BIKE:
        if (polishedActions) {
          polished_use(PD_PC_BICYCLE);
          break;
        }
        job = JOB_BIKE;
        jobItem = bag_bike(&snap);
        jobFrames = 0;
        jobTries = 15; // PokeDaisyActivity's SELECT_TRIES
        break;
      case QUICK_FLASH:
        flashAsk = 1; // run below, between two frames
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
    if (ui.tab != PD_TAB_BAG || ui.overlay != PD_OVERLAY_NONE)
      ui.bagTabs = false; // the pocket tab bar's cursor lives only on the bag
    // The finger over the companion, as the 3DS's stylus: down every frame it's held (drags scroll), then up.
    if (touchDown)
      pd_ui_touch(&ui, touchX, touchY, true);
    else if (touchWas)
      pd_ui_touch(&ui, 0, 0, false);
    const bool backing = down(B);
    const bool shoulder = down(L) || down(R);
    bool closeMap = false;
    const bool mapHandled = (backing || !shoulder) &&
        map_control(&ui, down(RIGHT) - down(LEFT), down(DOWN) - down(UP), down(A), backing, down(SELECT), &closeMap);
    if (polishedActions && ui.tab == PD_TAB_BAG && !ui.itemPicking && ui.overlay == PD_OVERLAY_NONE &&
        R2 != companionBtn && R2 != quickBtn && R2 != hudBtn && down(R2)) {
      const char *why = pd_field_input_reason(game.sha1, pd_lr_read_memory, core);
      if (why)
        notice(why);
      else if (pd_binput_field_start(&polishedInput, ui.bagSelected >= 0 ? PD_FIELD_ITEM : PD_FIELD_BAG,
                                    ui.bagItemShown, 0))
        companion = false;
      else
        notice(pd_field_input_message(&polishedInput.field));
    } else if (mapHandled) {
      if (closeMap) companion = false;
    } else if (backing && ui.tab == PD_TAB_BAG && ui.bagTabs && ui.overlay == PD_OVERLAY_NONE) {
      ui.bagTabs = false; // B on the pocket tab bar: back to the list
      focusId = -1;
      focus_new_screen();
    } else if (backing) {
      const bool places = ui.overlay == PD_OVERLAY_PLACES;
      const int slot = ui.tab == PD_TAB_PARTY ? ui.summarySlot : -1;
      if (polishedActions && ui.tab == PD_TAB_BATTLE && (ui.battleChoosingMove || ui.battleChoosingMon))
        ui.battleChoosingMove = ui.battleChoosingMon = false;
      else if (!pd_ui_back(&ui))
        companion = false;
      if (places) focusId = HIT_MAP_PLACES;
      if (slot >= 0 && ui.summarySlot < 0) focusId = HIT_SLOT + slot;
    } else if (shoulder) {
      pd_ui_next_tab(&ui, down(R) ? 1 : -1);
    } else {
      if (ui.tab == PD_TAB_DEX && !ui.dexOpen && ui.overlay == PD_OVERLAY_NONE && down(SELECT)) {
        ui_dex_next_filter(&ui);
        focus_new_screen();
      }
      if (ui.tab == PD_TAB_DEX && ui.dexOpen && ui.overlay == PD_OVERLAY_NONE) {
        if (down(SELECT)) ui_dex_act(&ui, HIT_DEX_CYCLE);
        if (down(LEFT) || down(RIGHT)) ui_dex_act(&ui, down(RIGHT) ? HIT_DEX_NEXT : HIT_DEX_PREV);
        if (ui.dexPage == PD_DEX_MOVES) {
          const int machine = pd_ui_dex_machine_row(ui.dexLevelCount);
          if (down(A)) ui.dexDetailRow = ui.dexDetailRow < machine ? machine : 0;
          ui.dexDetailRow += down(DOWN) - down(UP);
          if (ui.dexDetailRow < 0) ui.dexDetailRow = 0;
          if (ui.dexDetailRow > machine + ui.dexMachineCount) ui.dexDetailRow = machine + ui.dexMachineCount;
        } else if (ui.dexPage == PD_DEX_EVOLVE || ui.dexPage == PD_DEX_AREA) {
          ui.dexDetailRow += down(DOWN) - down(UP);
          const int count = ui.dexPage == PD_DEX_AREA ? ui.dexSpotCount : ui.dexLinkCount;
          const int last = count > 0 ? count - 1 : 0;
          if (ui.dexDetailRow < 0) ui.dexDetailRow = 0;
          if (ui.dexDetailRow > last) ui.dexDetailRow = last;
        }
      }
      const bool partySummary = ui.tab == PD_TAB_PARTY && ui.summarySlot >= 0 &&
          ui.summarySlot < snap.partyCount && ui.overlay == PD_OVERLAY_NONE;
      const bool bagBar = ui.tab == PD_TAB_BAG && ui.overlay == PD_OVERLAY_NONE && !ui.itemPicking &&
          (ui.bagTabs || (down(LEFT) && ui.bagTabCount > 1));
      if (bagBar) {
        // The pocket tab bar. LEFT from the list goes to it, on the open pocket (closing an open item in the same press);
        // up / down walk it, A opens the pocket under the cursor, RIGHT (B, above) goes back to the list.
        if (!ui.bagTabs) {
          ui.bagSelected = -1;
          ui.bagTabs = true;
          ui.bagTabKey = ui.bagPocket;
          focusId = -1;
        } else {
          int keys[PD_POCKET_COUNT + 1];
          const int n = ui.bagTabCount < PD_POCKET_COUNT + 1 ? ui.bagTabCount : PD_POCKET_COUNT + 1;
          for (int i = 0; i < n; i++)
            keys[i] = i;
          if (down(UP) || down(DOWN))
            ui.bagTabKey = pd_ui_bag_tab_step(keys, n, ui.bagTabKey, down(DOWN) - down(UP));
          if (down(A)) {
            ui.bagPocket = ui.bagTabKey;
            ui.bagScroll = 0;
            ui.bagSelected = -1;
            ui.bagTabs = false;
            focusId = -1;
            focus_new_screen();
          } else if (down(RIGHT)) {
            ui.bagTabs = false;
            focusId = -1;
            focus_new_screen();
          }
        }
      } else if (partySummary) {
        pd_ui_party_summary_control(&ui, &snap, down(RIGHT) - down(LEFT), down(DOWN) - down(UP), down(A), down(SELECT));
      } else if (bag_browsing(&ui)) {
        if (down(SELECT)) {
          pd_ui_bag_cycle_sort(&ui);
          focusId = -1;
          focus_new_screen();
        } else {
          if (down(UP)) focus_move(&ui, UP);
          if (down(DOWN)) focus_move(&ui, DOWN);
          if (down(A)) focus_activate(&ui);
        }
      } else if (ui.tab == PD_TAB_GUIDE && (ui.overlay == PD_OVERLAY_NONE || ui.overlay == PD_OVERLAY_GUIDE_NOTICE)) {
        pd_ui_guide_control(&ui, down(RIGHT) - down(LEFT), down(DOWN) - down(UP), down(A));
      } else if (ui.tab == PD_TAB_SETTINGS && ui.overlay == PD_OVERLAY_NONE) {
        pd_ui_settings_control(&ui, down(DOWN) - down(UP), down(A));
      } else {
        for (int k = RIGHT; k <= DOWN; k++) {
          if (!down(k) || (ui.overlay == PD_OVERLAY_PLACES && (k == LEFT || k == RIGHT))) continue;
          if (ui.tab == PD_TAB_DEX && ui.dexOpen && (ui.dexPage != PD_DEX_INFO || k == LEFT || k == RIGHT)) continue;
          focus_move(&ui, k);
        }
        if (down(A) && !(ui.tab == PD_TAB_DEX && ui.dexOpen && ui.dexPage == PD_DEX_MOVES)) {
          const bool places = ui.overlay == PD_OVERLAY_PLACES;
          focus_activate(&ui);
          if (places && ui.overlay == PD_OVERLAY_NONE) focusId = HIT_MAP;
        }
      }
    }
    switch (pd_ui_take_action(&ui)) {
    case PD_ACTION_ITEM_PICK:
      if (polishedActions && polished_potion(ui.itemId) && !ui.itemGive) {
        const char *why = pd_field_input_reason(game.sha1, pd_lr_read_memory, core);
        bool eligible = false;
        memset(ui.itemSlots, 0, sizeof ui.itemSlots);
        for (int i = 0; !why && i < snap.partyCount; i++) {
          const struct pd_mon *m = &snap.party[i];
          ui.itemSlots[i].ok = !m->egg && m->hp > 0 && m->hp < m->maxHp;
          eligible |= ui.itemSlots[i].ok;
        }
        if (eligible) {
          ui.itemPicking = true;
          ui.itemMoveSlot = -1;
          focus_new_screen();
        } else
          notice(why ? why : "No party member can use a Potion");
      }
      break;
    case PD_ACTION_ITEM_APPLY:
      if (polishedActions && !pd_field_input_reason(game.sha1, pd_lr_read_memory, core) &&
          pd_binput_field_start(&polishedInput, PD_FIELD_HEAL, ui.itemId, ui.itemSlot))
        companion = false;
      else if (polishedActions)
        notice("Potion unavailable");
      break;
    case PD_ACTION_FLIGHT:
      // MAP's TAKE OFF / LAND: run between two frames, the companion closed so the field is what's flown over.
      if (freeFlight) {
        takeOff = true;
        companion = false;
      }
      break;
    case PD_ACTION_SETTINGS_CHANGED:
      pd_settings_save(&settings, settingsPath);
      pd_flight_area_resend(); // FLIGHT AREA may have flipped
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
  // Fast-forward uses the same reconstructed view. If the frontend signals audio pressure, repeat a whole
  // gameplay frame rather than drawing a sharp center over soft margins.
  bool ff = false;
  if (realEnv) {
    struct retro_throttle_state th = {0, 0.0f};
    bool got = realEnv(RETRO_ENVIRONMENT_GET_THROTTLE_STATE, &th);
    if (got)
      ff = th.mode == RETRO_THROTTLE_FAST_FORWARD;
    // Older frontends (including minarch) only expose the fast-forward flag.
    else if (!realEnv(RETRO_ENVIRONMENT_GET_FASTFORWARDING, &ff))
      ff = false;
    rewinding = got && th.mode == RETRO_THROTTLE_REWINDING;
  }
  if (polishedActions && companion && pd_binput_busy(&polishedInput))
    companion = false; // The native battle / Bag / party resumes with the selected action.
  bool paused = companion || hudFocus || (quickOpen && !flyAsk);
  const bool expanded = wideSupported && settings.aspect != PD_ASPECT_ORIGINAL;
  const bool repeat = late && canDupe && haveGameFrame && !paused && !quickOpen &&
                      (!ff || (expanded && !repeatedFrame));
  maskGame = paused;
  shortcutExclusive = false;
  if (!paused) {
    if (polishedActions && !rewinding && settings.wildOff &&
        !polished_wild_hold(core, game.sha1, pd_lr_read_memory, core)) {
      settings.wildOff = false;
      pd_settings_save(&settings, settingsPath);
      notice("Wild encounters restored");
    }
    const bool fieldStarted = polishedActions && polishedInput.field.result == PD_FIELD_RUNNING;
    if (polishedActions) {
      if (rewinding)
        pd_binput_cancel(&polishedInput);
      else
        shortcutKeys = pd_binput_tick(&polishedInput, &shortcutExclusive);
      shortcutExclusive |= inputRunning || (!rewinding && (shortcutKeys || pd_binput_exclusive(&polishedInput)));
      if (rewinding)
        shortcutKeys = 0;
      if (shortcutExclusive)
        shortcutBlocked |= held;
    }
    PF_T(pa);
    mgba_retro_run();
    if (freeFlight)
      pk_flight_tick(core);
    PF_T(pb);
    if (fieldStarted && polishedInput.field.result != PD_FIELD_RUNNING && polishedInput.field.result != PD_FIELD_CANCELLED) {
      notice(pd_field_input_message(&polishedInput.field));
      snapshot();
    }
    pf_add(PF_RUN, pb - pa);
    if (gameKeyFrames > 0)
      gameKeyFrames--;
    if (PD_BE_IS_GBA())
      pk_rest_note(core);
    if (fieldKnown)
      fieldFlags = pk_field_state(core, settings.runIndoors, wildSwitch && settings.wildOff ? 0 : 100);
    if (gbf) {
      // A queued PC / FLY the game hasn't taken by now is withdrawn (counted on frames that ran: the quick menu pauses).
      if (gbTake > 0 && --gbTake == 0 && !gb_field_taken(core, gbf))
        notice(item_reason(ITEM_NOT_NOW));
      if (settings.wildOff) {
        gb_field_hold_wild(core, gbf);
        gbWildHeld = true;
      } else if (gbWildHeld && (!wideSupported || pk_wide_scene() == PK_WIDE_SCENE_FIELD) &&
                 gb_field_release_wild(core, gbf, romData, romLen))
        gbWildHeld = false;
    }
    if (repeat && expanded)
      pk_wide_skip(core, wideSource); // Keep the RAM history current for the next drawn frame.
    else
      wide_tick();
    PF_T(pc);
    pf_add(PF_WIDE, pc - pb);
  } else {
    static const int16_t silence[2 * 1024];
    PD_BE_AUDIO(silence, 549);
  }

  if (!snap.valid || (companion && !wasCompanion) || (!companion && frames % 30 == 0))
    snapshot();
  companionWas = companion;
  frames++;

  // FLY's steps and the field-item jobs, each between two frames the game's own code can be called in.
  bool flyBattle = snap.inBattle || (fieldFlags & PK_FIELD_BATTLE);
  bool flyBusy = companion || (fieldFlags & PK_FIELD_LOCKED) || (wideSupported && pk_wide_scene() != PK_WIDE_SCENE_FIELD);
  // FLIGHT AREA (the app's syncFlightArea, every half second): pk_flight told the save's seen maps, the map the player
  // stands on added while on the free field; or "anywhere".
  if (freeFlight && frames % 30 == 0)
    pd_flight_area_sync(core, settings.flightAnywhere,
                        !flyBusy && !flyBattle && pk_flight_state() == PK_FLIGHT_IDLE);
  // The bird turned at a map not seen yet: say why, now and then (it can be met many times a second).
  if (freeFlight && pk_flight_area_turned() && frames - areaToldAt > 240) {
    areaToldAt = frames;
    notice("You haven't been there yet.");
  }
  if (freeFlight && settings.flightVehicle != flightVehicle)
    flight_configure_words(); // FLY ON picked: the next frame is drawn in it, even mid-flight
  if (freeFlight)
    scenic_job(flyBusy, flyBattle);
  if (takeOff && freeFlight && pk_flight_state() != PK_FLIGHT_IDLE) {
    takeOff = false;
    pk_flight_land(); // MAP's LAND: the bird finds the nearest ground
  } else if (takeOff) {
    takeOff = false;
    snapshot();
    enum fly_result gate = fly_gate(core, flyCfg, romData, romLen, flyBusy, flyBattle);
    if (gate == FLY_OK && fly_flyer(&snap) < 0)
      gate = FLY_NO_FLYER;
    if (gate != FLY_OK)
      notice(fly_reason(gate));
    else {
      pd_flight_area_sync(core, settings.flightAnywhere, true); // the maps up to date before the bird is
      int result = pk_flight_take_off(core);
      if (result != PK_FLIGHT_OK)
        notice(result == PK_FLIGHT_NOT_ON_FOOT ? "Take off from land" : "Can't take off now");
    }
  }
  CALL_TEST(flyBusy, flyBattle);
  if ((flyCfg || gbf) && flyAsk && quickOpen && !companion) {
    bool answered = false;
    if (gbf) {
      gb_field_fly_targets(core, gbf, romData, romLen, flyBusy, &flyT);
      answered = true;
    } else if (pk_call_safe(core)) {
      snapshot();
      fly_targets(core, flyCfg, romData, romLen, &snap, flyBusy, flyBattle, &flyT);
      answered = true;
    }
    if (answered) {
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
  if (gbf && flyStage == 1 && !companion) {
    // The Game Boy's FLY is RAM the game's own loop acts on (Gen 1) or a queued script (Crystal): one step, then a check.
    enum fly_result r = gb_field_fly(core, gbf, romData, romLen, flyMapsec, flyBusy);
    flyStage = 0;
    if (r == FLY_OK)
      gbTake = GB_FIELD_TAKE_FRAMES;
    else
      notice(fly_reason(r));
  } else if (flyCfg && flyStage == 1 && !companion && pk_call_safe(core)) {
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
  if (flashCfg && flashAsk && !companion) {
    if (pk_call_safe(core)) {
      snapshot();
      enum flash_result r = flash_use(core, flashCfg, romData, romLen, &snap, flyBusy, flyBattle);
      flashAsk = 0;
      if (r != FLASH_OK)
        notice(flash_reason(r));
    } else if (++flashAsk > 120) {
      flashAsk = 0;
      notice(flash_reason(FLASH_FAILED));
    }
  }
  // pk_call_safe reads the ARM CPU: never on a Game Boy core, whose jobs need no call guard.
  bool safe = job != JOB_NONE && !companion && (gbf ? true : pk_call_safe(core));
  if (job == JOB_PC && safe) {
    job = JOB_NONE;
    enum item_result r = gbf ? gb_field_pc(core, gbf, flyBusy) : pc_open(core, game.cfg, romData, romLen, flyBusy, flyBattle);
    if (gbf && r == ITEM_USED)
      gbTake = GB_FIELD_TAKE_FRAMES;
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


  if (repeat) {
    repeatedFrame = true;
    realVideo(NULL, (unsigned)CW, (unsigned)CH, (size_t)CW * 2);
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
    host.flightReady = freeFlight && fly_flyer(&snap) >= 0; // MAP's TAKE OFF / LAND
    host.flying = freeFlight && pk_flight_state() != PK_FLIGHT_IDLE;
    memset(cvs, 0, (size_t)CW * CH * sizeof *cvs);
    // Use the Dex's compact font throughout the GBC Companion. Restore the cart font for the in-game HUD.
    const bool gen2 = game.cfg && game.cfg->gb && game.cfg->gb->gen == 2;
    const struct pd_glyph *glyphs = NULL;
    int glyphCount = 0;
    if (gen2) {
      pd_font_get(&glyphs, &glyphCount);
      pd_font_set(NULL, 0);
    }
    pd_ui_draw(&ui, &canvas, &game, &snap, &host);
    if (gen2)
      pd_font_set(glyphs, glyphCount);
    map_cursor_sync(&ui, &game, &snap);
    if (ui.tab != PD_TAB_GUIDE || (ui.overlay != PD_OVERLAY_NONE && ui.overlay != PD_OVERLAY_GUIDE_NOTICE)) {
      const int focus = focus_after_draw(&ui, focus_resolve(&ui));
      if (ui.overlay != PD_OVERLAY_NONE ||
          (ui.tab != PD_TAB_SETTINGS && (ui.tab != PD_TAB_PARTY || ui.summarySlot < 0) &&
           !(ui.tab == PD_TAB_BAG && ui.bagTabs)))
        focus_draw(&ui, focus);
    }
  } else if (quickOpen && ovPixels) {
    if (polishedActions && wideReady && settings.aspect != PD_ASPECT_ORIGINAL)
      draw_wide_quick_background();
    else
      draw_game();
    dim(0x55);
    struct pd_canvas qc;
    pd_canvas_init(&qc, quick_width(), quick_height(), ovPixels);
    const char *const *rows = flyList ? flyNames : repelList ? repelNames : NULL;
    bool drawn = true;
    if (rows)
      quick_list_draw(&qc, flyList ? "FLY" : "REPEL", rows, flyList ? flyT.count : repelKindCount, flyList ? flySel : repelSel, settings.hudLook);
    else
      drawn = quick_draw(&qc, &game, quick, quickN, quickSel, settings.hudLook);
    if (drawn) {
      quick_finish(&qc);
      blend_overlay(ovPixels, qc.w, qc.h, CW / qc.w, 0, 0);
    }
  } else {
    direct565 = messageFrames == 0;
    if (direct565)
      draw_game_fast();
    else
      draw_game();
    bool gb = PD_BE_IS_GB();
    bool field = gb ? wideSupported && pk_wide_scene() == PK_WIDE_SCENE_FIELD && !snap.inBattle && flyStage == 0
                    : (fieldFlags & PK_FIELD_KNOWN) && !(fieldFlags & (PK_FIELD_LOCKED | PK_FIELD_BATTLE)) &&
                          (!wideSupported || pk_wide_scene() == PK_WIDE_SCENE_FIELD) && flyStage == 0;
    hudFieldFrames = field ? hudFieldFrames + 1 : 0;
    if (settings.hud && game.cfg && host.hasHud && hudFieldFrames >= 20) {
      int steps = polishedActions ? polished_byte(PD_PC_REPEL_STEPS) : gb ? 0 : repel_steps(core, game.cfg);
      if (steps <= 0)
        hudState.repelFull = 0;
      else if (!hudState.repelFull || steps > hudState.repelFull)
        hudState.repelFull = hud_repel_full(steps);
      hudState.repel = steps > 0 ? steps : 0;
      hudState.repelItem = polishedActions ? pd_pc_repels[0].id : 0;
      hudState.pc = pcOk || gbf || polishedActions;
      hudState.wild = hud_wild_uncaught(&game, &snap);
      draw_hud();
    } else
      hudShown = false;
  }
  PF_T(td1);
  pf_add(PF_DRAW, td1 - td0);
  present();
  haveGameFrame = !companion && !quickOpen && !hudFocus;
  PF_T(td2);
  pf_add(PF_PRESENT, td2 - td1);
  pf_add(PF_TOTAL, td2 - t0);
#ifdef PD_PROFILE
  pf_hist(td2 - t0);
#endif
  pf_frame();
}
