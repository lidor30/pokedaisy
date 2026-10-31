/* Native single-screen host for PokeDaisy. GPL-3.0, see ../NOTICE. */
#include "../3ds/desktop/pd_png.h"
#include "pd_menu.h"
#include "pd_romart.h"
#include "pd_settings.h"
#include "pd_ui.h"
#include "pd_ui_internal.h"
#include "host_io.h"
#include "field_items.h"
#include "fly.h"
#include "hud.h"
#include "../app/src/main/cpp/pk_call.h"
#include "quick_menu.h"
#include "wild.h"
#include "../app/src/main/cpp/pk_wide.h"
#include <SDL.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <mgba-util/vfs.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/serialize.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
// The window's size without POCKET_FULLSCREEN / POCKET_WINDOW, and the companion's
// canvas until a screen says otherwise (companion_size).
#define W 512
#define H 384
#define CANVAS_MAX_W 1024
#define CANVAS_MAX_H 768
static uint32_t pixels[CANVAS_MAX_W * CANVAS_MAX_H];
static int canvasW = W, canvasH = H;
static color_t video[256 * 256];
static struct pd_canvas canvas;
static SDL_Window *window;
static SDL_Renderer *renderer;
static SDL_Texture *texture, *gameTexture;
static SDL_Rect lastGameRect;
/* EXPAND (the app's pk_wide.c): the overworld rebuilt past the game's edges
 * into a window the screen's shape, at the scale the game's own picture gets
 * (ZOOM OUT: one whole scale less), so the original pixels stay where they were. */
static SDL_Texture *wideTexture;
static uint32_t wideSource[240 * 160], wideFrame[PK_WIDE_MAX_VIEW_W * PK_WIDE_MAX_VIEW_H];
static bool wideSupported, wideReady;
static unsigned wideW, wideH;
static SDL_AudioDeviceID audio;
static volatile sig_atomic_t running = 1;
static bool muted;
static int appError;
#ifdef POCKET_TEST
static unsigned audioNonzero;
#endif
static const char *root = ".";
static char message[128];
static unsigned messageUntil;
enum {
  A,
  B,
  SELECT,
  START,
  RIGHT,
  LEFT,
  UP,
  DOWN,
  R,
  L,
  X,
  Y,
  L2,
  R2,
  MENU,
  NKEYS
};
static SDL_GameController *controller;
static bool rawTrimui;
static unsigned held, pressed;
static unsigned repeatAt[NKEYS];
static void stop(int sig) {
  (void)sig;
  running = 0;
}
static void notice(const char *s) {
  snprintf(message, sizeof message, "%s", s);
  messageUntil = SDL_GetTicks() + 2500;
  fprintf(stderr, "%s\n", s);
}
/* mGBA logs every DMA and BIOS call at DEBUG; keep warnings and errors only, so
 * launch.sh's log stays readable. */
static void quiet_log(struct mLogger *l, int category, enum mLogLevel level,
                      const char *format, va_list args) {
  (void)l;
  if (!(level & (mLOG_FATAL | mLOG_ERROR | mLOG_WARN)))
    return;
  fprintf(stderr, "%s: ", mLogCategoryName(category));
  vfprintf(stderr, format, args);
  fputc('\n', stderr);
}
static struct mLogger quietLogger = {.log = quiet_log};
/* The audio device. A handheld's ALSA device can go bad under the app (the
 * screen turned off and on: "ALSA write failed (unrecoverable)"), and SDL then
 * closes it for good; it's reopened as soon as SDL says so, or within a second
 * of it no longer playing. */
static bool audioLost;
static void audio_open(void) {
  SDL_AudioSpec want = {0};
  want.freq = 32768;
  want.format = AUDIO_S16SYS;
  want.channels = 2;
  want.samples = 512;
  audio = SDL_OpenAudioDevice(NULL, 0, &want, NULL, 0);
  if (audio)
    SDL_PauseAudioDevice(audio, 0);
  else
    fprintf(stderr, "Audio unavailable: %s\n", SDL_GetError());
}
static void audio_check(void) {
  static Uint32 next;
  bool stopped = audio && SDL_GetAudioDeviceStatus(audio) == SDL_AUDIO_STOPPED;
  if (!audioLost && !stopped)
    return;
  if (SDL_GetTicks() < next)
    return;
  next = SDL_GetTicks() + 1000;
  if (audio)
    SDL_CloseAudioDevice(audio);
  audio = 0;
  audio_open();
  audioLost = !audio;
  if (audio)
    fprintf(stderr, "Audio device reopened\n");
}
static void post_audio(struct mAVStream *s, blip_t *l, blip_t *r) {
  (void)s;
  int16_t out[2048];
  int n = blip_samples_avail(l);
  if (n > 1024)
    n = 1024;
  blip_read_samples(l, out, n, true);
  blip_read_samples(r, out + 1, n, true);
#ifdef POCKET_TEST
  for (int i = 0; i < n * 2; i++)
    if (out[i])
      audioNonzero++;
#endif
  if (audio && !muted && SDL_GetQueuedAudioSize(audio) < 32768)
    SDL_QueueAudio(audio, out, n * 4);
}
static bool read_memory(void *ctx, uint32_t a, void *out, size_t n) {
  struct mCore *c = ctx;
  unsigned char *p = out;
  if (c->platform(c) == mPLATFORM_GBA && a >= 0x08000000 && a < 0x0e000000) {
    size_t size = 0;
    const unsigned char *r = c->getMemoryBlock(c, 0x08, &size);
    size_t start = (a - 0x08000000) & 0x01ffffff;
    if (!r || start > size || n > size - start)
      return false;
    memcpy(p, r + start, n);
    return true;
  }
  for (size_t i = 0; i < n; i++)
    p[i] = c->busRead8(c, a + (uint32_t)i);
  return true;
}
static int keyboard(SDL_Keycode k) {
  switch (k) {
  case SDLK_z:
    return A;
  case SDLK_x:
    return B;
  case SDLK_BACKSPACE:
    return SELECT;
  case SDLK_RETURN:
    return START;
  case SDLK_RIGHT:
    return RIGHT;
  case SDLK_LEFT:
    return LEFT;
  case SDLK_UP:
    return UP;
  case SDLK_DOWN:
    return DOWN;
  case SDLK_e:
    return R;
  case SDLK_q:
    return L;
  case SDLK_TAB:
    return X;
  case SDLK_f:
    return Y;
  case SDLK_1:
    return L2;
  case SDLK_2:
    return R2;
  case SDLK_ESCAPE:
    return MENU;
  default:
    return -1;
  }
}
static int controller_button(int button) {
  switch(button) {
    case SDL_CONTROLLER_BUTTON_A:return B;
    case SDL_CONTROLLER_BUTTON_B:return A;
    case SDL_CONTROLLER_BUTTON_X:return Y;
    case SDL_CONTROLLER_BUTTON_Y:return X;
    case SDL_CONTROLLER_BUTTON_BACK:return SELECT;
    case SDL_CONTROLLER_BUTTON_START:return START;
    case SDL_CONTROLLER_BUTTON_GUIDE:return MENU;
    case SDL_CONTROLLER_BUTTON_LEFTSHOULDER:return L;
    case SDL_CONTROLLER_BUTTON_RIGHTSHOULDER:return R;
    case SDL_CONTROLLER_BUTTON_DPAD_UP:return UP;
    case SDL_CONTROLLER_BUTTON_DPAD_DOWN:return DOWN;
    case SDL_CONTROLLER_BUTTON_DPAD_LEFT:return LEFT;
    case SDL_CONTROLLER_BUTTON_DPAD_RIGHT:return RIGHT;
    default:return -1;
  }
}
static int joy(int n) {
  static const int map[] = {B, A, Y, X, L, R, SELECT, START, MENU, -1, L2, R2};
  return n >= 0 && n < 12 ? map[n] : -1;
}
static void key(int k, bool down) {
  if (k < 0)
    return;
  unsigned bit = 1u << k;
  if (down) {
    if (!(held & bit)) {
      pressed |= bit;
      repeatAt[k] = SDL_GetTicks() + 400;
    }
    held |= bit;
  } else
    held &= ~bit;
}
/* Controllers come and go (a USB or Bluetooth pad, a handheld whose built-in
 * one shows up late): SDL reports the ones it has a mapping for as game
 * controllers (its own database, DATA/gamecontrollerdb.txt, or
 * SDL_GAMECONTROLLERCONFIG, which PortMaster sets). The first one is used; an
 * unmapped device is named in the log so a mapping can be added. */
static void controller_added(int index) {
  if (!SDL_IsGameController(index)) {
    char guid[64];
    SDL_JoystickGetGUIDString(SDL_JoystickGetDeviceGUID(index), guid, sizeof guid);
    fprintf(stderr, "Controller without a mapping: %s (%s); add one to "
            "DATA/gamecontrollerdb.txt or SDL_GAMECONTROLLERCONFIG\n",
            SDL_JoystickNameForIndex(index) ? SDL_JoystickNameForIndex(index) : "?", guid);
    return;
  }
  if (controller)
    return;
  controller = SDL_GameControllerOpen(index);
  if (controller)
    fprintf(stderr, "Controller: %s\n", SDL_GameControllerName(controller));
}
static void controller_removed(SDL_JoystickID id) {
  if (!controller ||
      SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(controller)) != id)
    return;
  SDL_GameControllerClose(controller);
  controller = NULL;
  held = 0; // nothing stays pressed on a pad that's gone
  for (int i = 0; i < SDL_NumJoysticks() && !controller; i++)
    if (SDL_IsGameController(i))
      controller_added(i);
}
static void events(void) {
  pressed = 0;
  SDL_Event e;
  while (SDL_PollEvent(&e)) {
    if (e.type == SDL_QUIT)
      running = 0;
    if (e.type == SDL_JOYDEVICEADDED && !SDL_IsGameController(e.jdevice.which))
      controller_added(e.jdevice.which);
    if (e.type == SDL_AUDIODEVICEREMOVED && !e.adevice.iscapture && audio &&
        e.adevice.which == audio)
      audioLost = true;
    if (e.type == SDL_CONTROLLERDEVICEADDED)
      controller_added(e.cdevice.which);
    if (e.type == SDL_CONTROLLERDEVICEREMOVED)
      controller_removed(e.cdevice.which);
    if (e.type == SDL_KEYDOWN || e.type == SDL_KEYUP) {
      if (!e.key.repeat)
        key(keyboard(e.key.keysym.sym), e.type == SDL_KEYDOWN);
    }
    if(e.type == SDL_CONTROLLERBUTTONDOWN || e.type == SDL_CONTROLLERBUTTONUP)
      key(controller_button(e.cbutton.button),e.type == SDL_CONTROLLERBUTTONDOWN);
    if(e.type == SDL_CONTROLLERAXISMOTION) {
      if(e.caxis.axis==SDL_CONTROLLER_AXIS_TRIGGERLEFT)key(L2,e.caxis.value>16000);
      if(e.caxis.axis==SDL_CONTROLLER_AXIS_TRIGGERRIGHT)key(R2,e.caxis.value>16000);
      if(e.caxis.axis==SDL_CONTROLLER_AXIS_LEFTX) {key(LEFT,e.caxis.value < -16000);key(RIGHT,e.caxis.value > 16000);}
      if(e.caxis.axis==SDL_CONTROLLER_AXIS_LEFTY) {key(UP,e.caxis.value < -16000);key(DOWN,e.caxis.value > 16000);}
    }
    if (!controller && rawTrimui && (e.type == SDL_JOYBUTTONDOWN || e.type == SDL_JOYBUTTONUP))
      key(joy(e.jbutton.button), e.type == SDL_JOYBUTTONDOWN);
    if (!controller && rawTrimui && e.type == SDL_JOYHATMOTION) {
      key(UP, e.jhat.value & SDL_HAT_UP);
      key(DOWN, e.jhat.value & SDL_HAT_DOWN);
      key(LEFT, e.jhat.value & SDL_HAT_LEFT);
      key(RIGHT, e.jhat.value & SDL_HAT_RIGHT);
    }
    if (!controller && rawTrimui && e.type == SDL_JOYAXISMOTION) {
      if (e.jaxis.axis == 2)
        key(L2, e.jaxis.value > 16000);
      if (e.jaxis.axis == 5)
        key(R2, e.jaxis.value > 16000);
    }
    if (e.type == SDL_WINDOWEVENT &&
        e.window.event == SDL_WINDOWEVENT_FOCUS_LOST)
      held = 0;
  }
}
static void repeat_directions(void) {
  for (int k = RIGHT; k <= DOWN; k++)
    if ((held & (1u << k)) && SDL_GetTicks() >= repeatAt[k]) {
      pressed |= 1u << k;
      repeatAt[k] = SDL_GetTicks() + 90;
    }
}
static bool down(int k) { return (pressed & (1u << k)) != 0; }
/* The companion's canvas for an ow x oh screen: the largest whole scale that
 * keeps it at least 320x240 (the 3DS's, which every tab is laid out for), so
 * its pixel text is about as big on every panel: 320x240 at 2x on 640x480,
 * 426x240 at 3x on 1280x720, 341x256 on 1024x768, 480x270 on 1920x1080.
 * POCKET_COMPANION_SCALE=N picks the scale instead. */
static void companion_size(int ow, int oh, int *w, int *h) {
  int scale = ow / 320 < oh / 240 ? ow / 320 : oh / 240;
  const char *forced = getenv("POCKET_COMPANION_SCALE");
  if (forced && atoi(forced) > 0)
    scale = atoi(forced);
  if (scale < 1)
    scale = 1;
  *w = ow / scale;
  *h = oh / scale;
  if (*w > CANVAS_MAX_W)
    *w = CANVAS_MAX_W;
  if (*h > CANVAS_MAX_H)
    *h = CANVAS_MAX_H;
  if (*w < 320)
    *w = 320;
  if (*h < 240)
    *h = 240;
}
/* Before the companion draws: the canvas follows the window (a resize, a
 * rotated panel), with a texture to match. */
static void companion_canvas(void) {
  int ow = W, oh = H, w, h;
  if (SDL_GetRendererOutputSize(renderer, &ow, &oh) < 0 || ow <= 0 || oh <= 0)
    ow = W, oh = H;
  companion_size(ow, oh, &w, &h);
  if (w == canvasW && h == canvasH && texture)
    return;
  SDL_Texture *t = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_RGB888,
                                     SDL_TEXTUREACCESS_STREAMING, w, h);
  if (!t) {
    notice(SDL_GetError());
    return;
  }
  SDL_SetTextureBlendMode(t, SDL_BLENDMODE_NONE);
  if (texture)
    SDL_DestroyTexture(texture);
  texture = t;
  canvasW = w;
  canvasH = h;
  pd_canvas_init(&canvas, w, h, pixels);
}
/* How long the last presents waited (the driver holds each one for the screen's
 * refresh), for POCKET_PERF. */
static double presentMs, emuMs, wideMs;
static double ms_since(uint64_t t) {
  return (double)(SDL_GetPerformanceCounter() - t) * 1000.0 / SDL_GetPerformanceFrequency();
}
static void timed_present(void) {
  uint64_t t = SDL_GetPerformanceCounter();
  SDL_RenderPresent(renderer);
  presentMs += (double)(SDL_GetPerformanceCounter() - t) * 1000.0 / SDL_GetPerformanceFrequency();
}
static void present(void) {
  SDL_RenderSetLogicalSize(renderer, canvasW, canvasH);
  SDL_RenderSetIntegerScale(renderer, SDL_TRUE);
  if (SDL_GetTicks() < messageUntil) {
    pd_fill(&canvas, 4, canvasH - 50, canvasW - 8, 24, 0x181818);
    pd_text_fit(&canvas, 10, canvasH - 46, canvasW - 20, message, 0xffffff, PD_NO_SHADOW);
  }
  if (SDL_UpdateTexture(texture, NULL, pixels, canvasW * 4) < 0) {
    notice(SDL_GetError());
    return;
  }
  SDL_RenderClear(renderer);
  if (SDL_RenderCopy(renderer, texture, NULL, NULL) < 0) {
    notice(SDL_GetError());
    return;
  }
  timed_present();
}
/* The EXPAND window for an ow x oh screen and a fw x fh game: the screen's
 * size over the game's whole scale (one less for ZOOM OUT), within pk_wide's
 * largest view, never smaller than the game, with even margins so the game's
 * own pixels sit exactly in the middle. */
static void wide_window(int ow, int oh, unsigned fw, unsigned fh, int aspect,
                        unsigned *w, unsigned *h) {
  int scale = ow / (int)fw < oh / (int)fh ? ow / (int)fw : oh / (int)fh;
  if (aspect == PD_ASPECT_ZOOM_OUT)
    scale--;
  if (scale < 1)
    scale = 1;
  unsigned x = (unsigned)ow / scale, y = (unsigned)oh / scale;
  x = x > PK_WIDE_MAX_VIEW_W ? PK_WIDE_MAX_VIEW_W : x < fw ? fw : x;
  y = y > PK_WIDE_MAX_VIEW_H ? PK_WIDE_MAX_VIEW_H : y < fh ? fh : y;
  *w = x - ((x - fw) & 1);
  *h = y - ((y - fh) & 1);
}
/* After every frame while EXPAND can run: a frame nobody sees (fast-forward)
 * only keeps pk_wide's RAM history. wideReady = this frame shows the window:
 * the plain overworld (text boxes too with AUTO ZOOM IN off), its edges all
 * map; anything else (battles, menus, transitions) shows the game's picture. */
static void wide_tick(struct mCore *c, bool skip, const struct pd_settings *s) {
  wideReady = false;
  if (!wideSupported)
    return;
  unsigned fw = (unsigned)pk_wide_frame_w(), fh = (unsigned)pk_wide_frame_h();
  for (unsigned y = 0; y < fh; y++)
    memcpy(wideSource + y * fw, video + y * 256, fw * sizeof *wideSource);
  if (skip) {
    pk_wide_skip(c, wideSource);
    return;
  }
  if (s->aspect == PD_ASPECT_ORIGINAL) {
    // Nothing past the picture, but the scene still read (FLY's "on the field"): a one-pixel window.
    static uint32_t px;
    pk_wide_view(c, wideSource, &px, (struct PkWideView){0, 0, 1, 1}, NULL);
    return;
  }
  // ZOOM OUT steps back in to EXPAND's window off the plain overworld (a text
  // box, the START menu, a battle) with AUTO ZOOM IN on, as the app's camera
  // does - by the last frame's scene, the one known before this one is drawn.
  int last = pk_wide_scene();
  int aspect = s->aspect == PD_ASPECT_ZOOM_OUT && s->autoZoom && last != PK_WIDE_SCENE_FIELD
                   ? PD_ASPECT_EXPAND : s->aspect;
  int ow = W, oh = H;
  if (SDL_GetRendererOutputSize(renderer, &ow, &oh) < 0 || ow <= 0 || oh <= 0)
    ow = W, oh = H;
  wide_window(ow, oh, fw, fh, aspect, &wideW, &wideH);
  struct PkWideView v = {((int)fw - (int)wideW) / 2, ((int)fh - (int)wideH) / 2,
                         (int)wideW, (int)wideH};
  // Like the app's EXPAND, the window shows whatever the scene: the map past
  // the edges on the overworld (text boxes and the START menu too), the
  // battle's own scenery, and off the map a glow from the frame's own edge
  // pixels, crossfaded - never a cut back to the bare picture.
  pk_wide_view(c, wideSource, wideFrame, v, NULL);
  wideReady = !pk_wide_fault();
}
// Gameplay scales in physical panel pixels; the companion keeps its 512x384
// grid.
static bool render_game(unsigned gw, unsigned gh) {
  int ow, oh;
  if (SDL_GetRendererOutputSize(renderer, &ow, &oh) < 0 || ow <= 0 || oh <= 0)
    return false;
  SDL_RenderSetLogicalSize(renderer, ow, oh);
  SDL_RenderSetIntegerScale(renderer, SDL_FALSE);
  int scale = ow / (int)gw;
  if (oh / (int)gh < scale)
    scale = oh / (int)gh;
  if (scale < 1)
    scale = 1;
  lastGameRect =
      (SDL_Rect){(ow - (int)gw * scale) / 2, (oh - (int)gh * scale) / 2,
                 (int)gw * scale, (int)gh * scale};
  // The textures take mGBA's pixels as they are (XBGR: SDL's BGR888).
  if (SDL_UpdateTexture(gameTexture, &(SDL_Rect){0, 0, (int)gw, (int)gh}, video, 256 * 4) < 0) {
    notice(SDL_GetError());
    return false;
  }
  SDL_Texture *drawn = gameTexture;
  if (wideReady) {
    // The same scale: the window's middle lands on the game's own picture.
    gw = wideW;
    gh = wideH;
    scale = ow / (int)gw < oh / (int)gh ? ow / (int)gw : oh / (int)gh;
    if (scale < 1)
      scale = 1;
    lastGameRect = (SDL_Rect){(ow - (int)gw * scale) / 2, (oh - (int)gh * scale) / 2,
                              (int)gw * scale, (int)gh * scale};
    if (SDL_UpdateTexture(wideTexture, &(SDL_Rect){0, 0, (int)gw, (int)gh},
                          wideFrame, (int)gw * 4) < 0) {
      notice(SDL_GetError());
      return false;
    }
    drawn = wideTexture;
  }
  SDL_RenderClear(renderer);
  SDL_Rect src = {0, 0, (int)gw, (int)gh};
  if (SDL_RenderCopy(renderer, drawn, &src, &lastGameRect) < 0) {
    notice(SDL_GetError());
    return false;
  }
  return true;
}
static void present_game(unsigned gw, unsigned gh) {
  if (render_game(gw, gh))
    timed_present();
}
/* The HUD over the overworld (hud.c): the screen with EXPAND (its corners), else the game's own picture, at the
 * companion's canvas scale. Its texture is redrawn only when what it shows changed (hud_key). */
static uint32_t hudPixels[CANVAS_MAX_W * CANVAS_MAX_H];
static SDL_Texture *hudTexture;
static uint32_t hudDrawnKey;
static int hudW, hudH;
static bool hudShown;
static void present_hud_game(const struct pd_game *g, const struct pd_snapshot *s, const struct hud_state *h,
                             int hidden, unsigned gw, unsigned gh) {
  if (!render_game(gw, gh))
    return;
  int ow, oh;
  SDL_GetRendererOutputSize(renderer, &ow, &oh);
  int scale = ow / canvasW < oh / canvasH ? ow / canvasW : oh / canvasH;
  if (scale < 1)
    scale = 1;
  SDL_Rect area = wideReady ? (SDL_Rect){0, 0, ow, oh} : lastGameRect;
  int w = area.w / scale, hh = area.h / scale;
  if (w > CANVAS_MAX_W)
    w = CANVAS_MAX_W;
  if (hh > CANVAS_MAX_H)
    hh = CANVAS_MAX_H;
  if (!hudTexture) {
    hudTexture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888, SDL_TEXTUREACCESS_STREAMING, CANVAS_MAX_W,
                                   CANVAS_MAX_H);
    if (hudTexture)
      SDL_SetTextureBlendMode(hudTexture, SDL_BLENDMODE_BLEND);
  }
  uint32_t key = hud_key(s, h, hidden) ^ (uint32_t)(w * 65599 + hh);
  if (hudTexture && (key != hudDrawnKey || w != hudW || hh != hudH)) {
    struct pd_canvas hc;
    pd_canvas_init(&hc, w, hh, hudPixels);
    hudShown = hud_draw(&hc, g, s, h, hidden);
    quick_finish(&hc);
    SDL_UpdateTexture(hudTexture, &(SDL_Rect){0, 0, w, hh}, hudPixels, w * 4);
    hudDrawnKey = key;
    hudW = w;
    hudH = hh;
  }
  if (hudTexture && hudShown) {
    SDL_Rect at = {area.x + (area.w - w * scale) / 2, area.y + (area.h - hh * scale) / 2, w * scale, hh * scale};
    SDL_RenderCopy(renderer, hudTexture, &(SDL_Rect){0, 0, w, hh}, &at);
  }
  timed_present();
}
/* The game menu (resume, states, quit): an OPTION window over the game's
 * picture, EXPAND's edges and all, dimmed behind it - the companion's canvas
 * scale, its windows only (pd_menu.overlay), drawn into a texture whose
 * untouched pixels stay see-through. */
#define MENU_KEY 0x00FF00FFu
static uint32_t menuPixels[CANVAS_MAX_W * CANVAS_MAX_H];
static SDL_Texture *menuTexture;
static bool overlay_texture(void) {
  if (!menuTexture) {
    menuTexture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888, SDL_TEXTUREACCESS_STREAMING,
                                    CANVAS_MAX_W, CANVAS_MAX_H);
    if (!menuTexture)
      return false;
    SDL_SetTextureBlendMode(menuTexture, SDL_BLENDMODE_BLEND);
  }
  return true;
}
/* The QUICK MENU over the running game: a light dim, the ring at the companion's canvas scale. */
static void present_quick(const struct pd_game *g, const struct quick_item *items, int n, int sel,
                          const char *title, const char *const *rows, int rowCount, int rowSel, unsigned gw,
                          unsigned gh) {
  if (!render_game(gw, gh) || !overlay_texture())
    return;
  int ow, oh;
  SDL_GetRendererOutputSize(renderer, &ow, &oh);
  int scale = ow / canvasW < oh / canvasH ? ow / canvasW : oh / canvasH;
  if (scale < 1)
    scale = 1;
  struct pd_canvas qc;
  pd_canvas_init(&qc, canvasW, canvasH, menuPixels);
  if (rows)
    quick_list_draw(&qc, title, rows, rowCount, rowSel);
  else if (!quick_draw(&qc, g, items, n, sel))
    return;
  quick_finish(&qc);
  SDL_UpdateTexture(menuTexture, &(SDL_Rect){0, 0, canvasW, canvasH}, menuPixels, canvasW * 4);
  SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
  SDL_SetRenderDrawColor(renderer, 0, 0, 0, 0x55);
  SDL_RenderFillRect(renderer, NULL);
  SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_NONE);
  SDL_SetRenderDrawColor(renderer, 0, 0, 0, 255);
  SDL_Rect at = {(ow - canvasW * scale) / 2, (oh - canvasH * scale) / 2, canvasW * scale, canvasH * scale};
  SDL_RenderCopy(renderer, menuTexture, &(SDL_Rect){0, 0, canvasW, canvasH}, &at);
  timed_present();
}
static void present_menu(struct pd_menu *m, unsigned gw, unsigned gh) {
  if (!render_game(gw, gh))
    return;
  int ow, oh;
  SDL_GetRendererOutputSize(renderer, &ow, &oh);
  int scale = ow / canvasW < oh / canvasH ? ow / canvasW : oh / canvasH;
  if (scale < 1)
    scale = 1;
  int w = canvasW * 3 / 4 < 300 ? (canvasW < 300 ? canvasW : 300) : canvasW * 3 / 4;
  int h = 24 + 4 + m->count * 18 + 2 * 7 + 8 + 2 * MARGIN;
  if (h > canvasH)
    h = canvasH;
  if (!overlay_texture())
    return;
  struct pd_canvas mc;
  pd_canvas_init(&mc, w, h, menuPixels);
  for (int i = 0; i < w * h; i++)
    menuPixels[i] = MENU_KEY;
  m->overlay = true;
  pd_menu_draw(m, &mc);
  for (int i = 0; i < w * h; i++)
    menuPixels[i] = menuPixels[i] == MENU_KEY ? 0 : (menuPixels[i] | 0xFF000000u);
  SDL_UpdateTexture(menuTexture, &(SDL_Rect){0, 0, w, h}, menuPixels, w * 4);
  SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
  SDL_SetRenderDrawColor(renderer, 0, 0, 0, 120);
  SDL_RenderFillRect(renderer, NULL);
  SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_NONE);
  SDL_SetRenderDrawColor(renderer, 0, 0, 0, 255);
  SDL_Rect at = {(ow - w * scale) / 2, (oh - h * scale) / 2, w * scale, h * scale};
  SDL_RenderCopy(renderer, menuTexture, &(SDL_Rect){0, 0, w, h}, &at);
  timed_present();
}
static bool capture_frame(const char *path, unsigned *lit) {
  int ow, oh;
  if (SDL_GetRendererOutputSize(renderer, &ow, &oh) < 0 || ow <= 0 || oh <= 0 ||
      ow > 8192 || oh > 8192)
    return false;
  uint32_t *shot = malloc((size_t)ow * oh * 4);
  if (!shot)
    return false;
  bool ok = SDL_RenderReadPixels(renderer, NULL, SDL_PIXELFORMAT_RGB888, shot,
                                 ow * 4) == 0;
  if (ok && lit) {
    *lit = 0;
    for (int i = 0; i < ow * oh; i++)
      if (shot[i] & 0xffffff)
        (*lit)++;
  }
  if (ok && path)
    ok = pd_png_write(path, ow, oh, shot);
  free(shot);
  return ok;
}
static bool state(struct mCore *c, const char *path, bool save) {
  char tmp[1200];
  snprintf(tmp, sizeof tmp, "%s.tmp", path);
  struct VFile *f = VFileOpen(save ? tmp : path,
                              save ? O_CREAT | O_TRUNC | O_RDWR : O_RDONLY);
  if (!f)
    return false;
  bool ok = save ? mCoreSaveStateNamed(c, f, SAVESTATE_RTC)
                 : mCoreLoadStateNamed(c, f, SAVESTATE_RTC);
  if(!f->close(f))ok=false;
  if (ok && !save) {
    pk_wide_reset(); // EXPAND's RAM history is from before the jump
    wideReady = false;
  }
  if (save) {
    if (ok)
      ok = rename(tmp, path) == 0;
    else
      unlink(tmp);
  }
  return ok;
}
/* The D-pad's cursor over the companion. Kept by hit id, since every draw makes
 * the hit list anew: a press that leaves the screen as it was keeps it where it
 * is (the nearest item if that one went), and a new screen (a tab, a summary, a
 * pick-list, a dex entry) puts it on that screen's start (pd_ui.startId: a
 * pick-list's current choice, a confirm's CANCEL), else its first item. */
static int focusId = -1;
static struct pd_hit focusAt;
struct screen {
  int tab, overlay, summarySlot, dexOpen;
};
static struct screen lastScreen = {-1, -1, -1, -1};
static struct screen screen_of(const struct pd_ui *u) {
  return (struct screen){u->tab, u->overlay, u->summarySlot, u->dexOpen};
}
static void focus_new_screen(void) { lastScreen.tab = -1; }
// What the cursor can land on: not the tab bar, nor a scrim or a list's drag area.
static bool focusable(int id) {
  return !(id >= HIT_TAB && id < HIT_TAB + PD_TAB_COUNT) && id != HIT_FF && id != HIT_SCRIM &&
         id != HIT_BAG_LIST && id != HIT_DEX_LIST && id != HIT_GUIDE_LIST && id != HIT_SETTINGS_LIST;
}
static int focus_index(const struct pd_ui *u, int id) {
  for (int i = 0; i < u->hitCount; i++)
    if (u->hits[i].id == id)
      return i;
  return -1;
}
// After a draw: where the cursor is now (an index into u->hits, -1 = none).
static int focus_resolve(struct pd_ui *u) {
  struct screen now = screen_of(u);
  bool changed = memcmp(&now, &lastScreen, sizeof now) != 0;
  lastScreen = now;
  int i = changed ? -1 : focus_index(u, focusId);
  if (i < 0 && !changed && focusId >= 0) { // gone: the nearest item to where it was
    int best = 1 << 30;
    for (int j = 0; j < u->hitCount; j++) {
      const struct pd_hit *h = &u->hits[j];
      int dx = h->x + h->w / 2 - (focusAt.x + focusAt.w / 2),
          dy = h->y + h->h / 2 - (focusAt.y + focusAt.h / 2);
      if (focusable(h->id) && dx * dx + dy * dy < best)
        best = dx * dx + dy * dy, i = j;
    }
  }
  if (i < 0 && u->startId >= 0)
    i = focus_index(u, u->startId);
  for (int j = 0; i < 0 && j < u->hitCount; j++)
    if (focusable(u->hits[j].id))
      i = j;
  if (i < 0 && u->hitCount)
    i = 0;
  focusId = i >= 0 ? u->hits[i].id : -1;
  if (i >= 0)
    focusAt = u->hits[i];
  return i;
}
static void focus_move(struct pd_ui *u, int direction) {
  int cur = focus_index(u, focusId);
  if (cur < 0) {
    focus_resolve(u);
    return;
  }
  const struct pd_hit *h = &u->hits[cur];
  int best = -1, score = 1000000;
  for (int i = 0; i < u->hitCount; i++) {
    if (i == cur)
      continue;
    struct pd_hit *t = &u->hits[i];
    if (t->id == HIT_SCRIM || t->id == HIT_BAG_LIST || t->id == HIT_DEX_LIST ||
        t->id == HIT_GUIDE_LIST || t->id == HIT_SETTINGS_LIST)
      continue;
    int dx = (t->x + t->w / 2) - (h->x + h->w / 2),
        dy = (t->y + t->h / 2) - (h->y + h->h / 2);
    int along = direction == LEFT    ? -dx
                : direction == RIGHT ? dx
                : direction == UP    ? -dy
                                     : dy;
    int across = direction == LEFT || direction == RIGHT ? abs(dy) : abs(dx);
    if (along > 0 && along + across * 4 < score) {
      best = i;
      score = along + across * 4;
    }
  }
  if (best >= 0) {
    focusId = u->hits[best].id;
    focusAt = u->hits[best];
  } else if (direction == UP || direction == DOWN) {
    int d = direction == DOWN ? 36 : -36;
    if (u->overlay == PD_OVERLAY_PLACES)
      u->placesScroll += d;
    else if (u->tab == PD_TAB_BAG)
      u->bagScroll += d;
    else if (u->tab == PD_TAB_DEX)
      u->dexScroll += d;
    else if (u->tab == PD_TAB_GUIDE)
      u->guideScroll += d;
    else if (u->tab == PD_TAB_SETTINGS && u->overlay == PD_OVERLAY_NONE)
      u->settingsScroll += d;
  }
}
static void focus_draw(struct pd_ui *u, int focus) {
  if (focus < 0 || focus >= u->hitCount)
    return;
  struct pd_hit *h = &u->hits[focus];
  if (h->id >= HIT_SLOT && h->id < HIT_SLOT + PD_PARTY_SIZE)
    return; // a party slot shows its own selected look (pd_ui.focusId)
  pd_fill(&canvas, h->x, h->y, h->w, 2, 0xff7331);
  pd_fill(&canvas, h->x, h->y + h->h - 2, h->w, 2, 0xff7331);
  pd_fill(&canvas, h->x, h->y, 2, h->h, 0xff7331);
  pd_fill(&canvas, h->x + h->w - 2, h->y, 2, h->h, 0xff7331);
}
/* Pacing. The screen's refresh paces the game where it's close to the game's
 * 59.73 fps (a 60 Hz panel): a game frame per refresh, the present waiting for
 * it, no timer - the timer's frames drifted against the refresh and every few
 * seconds one missed it (the Smart Pro lost ~13 frames in 5 s). The audio then
 * plays a hair faster, so its sample rate follows the queue (dynamic rate
 * control, as RetroArch and the Android app do): +-0.5% around the ratio of the
 * two rates, keeping ~60 ms queued. Elsewhere, or with POCKET_NO_VSYNC, the
 * timer paces as before. */
static bool vsyncPaced;
static double screenHz;
static void pacing_start(double gameHz) {
  SDL_DisplayMode mode = {0};
  screenHz = SDL_GetCurrentDisplayMode(0, &mode) == 0 ? mode.refresh_rate : 0;
  SDL_RendererInfo info;
  vsyncPaced = !getenv("POCKET_NO_VSYNC") && screenHz >= 58 && screenHz <= 62 &&
               SDL_GetRendererInfo(renderer, &info) == 0 && (info.flags & SDL_RENDERER_PRESENTVSYNC) &&
               gameHz > 0;
}
// A refresh-paced loop whose presents don't wait (a driver ignoring vsync) goes back to the timer.
static void pacing_check(double frameMs) {
  static double avg;
  static int count;
  if (!vsyncPaced)
    return;
  avg += frameMs;
  if (++count < 120)
    return;
  if (avg / count < 12.0) {
    vsyncPaced = false;
    fprintf(stderr, "Presents don't wait for the refresh (%.1f ms apart): pacing by the timer\n", avg / count);
  }
  avg = 0;
  count = 0;
}
static void audio_rate(struct mCore *c, double gameHz) {
  double rate = 32768;
  if (vsyncPaced && audio && screenHz > 0) {
    double queued = SDL_GetQueuedAudioSize(audio) / 4.0, target = 32768 * 0.06;
    double d = (target - queued) / target;
    d = d > 1 ? 1 : d < -1 ? -1 : d;
    rate = 32768 * gameHz / screenHz * (1 + 0.005 * d);
  }
  for (int i = 0; i < 2; i++)
    blip_set_rates(c->getAudioChannel(c, i), c->frequency(c), rate);
}
/* POCKET_PERF=1: every 5 s, how the frames went: loop iterations, the work in
 * one (emulation, EXPAND, drawing, present) on average and at worst, how many
 * took longer than a game frame, how far apart presents landed, the screen's
 * refresh rate. */
static void perf_frame(uint64_t start, double hz) {
  static int on = -1, n, late;
  static double sum, worst, gapSum, gapWorst, presents, emus, wides;
  static uint64_t lastEnd, windowStart;
  if (on < 0)
    on = getenv("POCKET_PERF") != NULL;
  if (!on)
    return;
  uint64_t end = SDL_GetPerformanceCounter(), f = SDL_GetPerformanceFrequency();
  double work = (double)(end - start) * 1000.0 / f, budget = 1000.0 / hz;
  if (!windowStart)
    windowStart = end;
  n++;
  sum += work;
  presents += presentMs;
  emus += emuMs;
  wides += wideMs;
  presentMs = emuMs = wideMs = 0;
  if (work > worst)
    worst = work;
  if (work > budget)
    late++;
  if (lastEnd) {
    double gap = (double)(end - lastEnd) * 1000.0 / f;
    gapSum += gap;
    if (gap > gapWorst)
      gapWorst = gap;
  }
  lastEnd = end;
  if (end - windowStart >= 5 * f) {
    SDL_DisplayMode mode = {0};
    SDL_GetCurrentDisplayMode(0, &mode);
    fprintf(stderr, "perf: emulation %.2f ms, EXPAND %.2f ms a frame\n", emus / n, wides / n);
    fprintf(stderr, "perf: %d frames in %.1f s, work %.2f ms avg (%.2f of it waiting in present) / %.2f worst, "
            "%d over %.2f ms, frame to frame %.2f ms avg / %.2f worst, screen %d Hz, %s, audio queued %u B\n",
            n, (double)(end - windowStart) / f, sum / n, presents / n, worst, late, budget,
            gapSum / (n > 1 ? n - 1 : 1), gapWorst, mode.refresh_rate,
            vsyncPaced ? "paced by the screen" : "paced by the timer", audio ? SDL_GetQueuedAudioSize(audio) : 0);
    n = late = 0;
    sum = worst = gapSum = gapWorst = presents = emus = wides = 0;
    windowStart = end;
  }
}
#ifdef POCKET_TEST
static void test_frame(struct mCore *, bool, int, struct pd_ui *,
                       struct pd_snapshot *);
#endif
#ifdef POCKET_DSPERATE
extern int pd_ds_run(const char*, const char*, int, const char*, SDL_Renderer*, bool (*)(void));
static bool ds_running(void) { return running != 0; }
#endif
static void run_game(const char *path, int maxFrames, const char *shot) {
#ifdef POCKET_DSPERATE
  const char *ext = strrchr(path, '.');
  if (ext && !strcasecmp(ext, ".nds")) {
    if (audio) { SDL_CloseAudioDevice(audio); audio = 0; }
    appError = pd_ds_run(path, root, maxFrames, shot, renderer, ds_running);
    // The DS backend owns its device while running. Restore the normal host's
    // device for the next GB/GBA game selected without restarting the app.
    audio_open();
    audioLost = !audio;
    held = pressed = 0;
    return;
  }
#endif
  FILE *f = fopen(path, "rb");
  if (!f) {
    appError = 1;
    notice("Cannot open ROM");
    return;
  }
  fseek(f, 0, SEEK_END);
  long len = ftell(f);
  rewind(f);
  if (len < 0x150 || len > 40 * 1024 * 1024) {
    fclose(f);
    appError = 1;
    notice("Invalid ROM size");
    return;
  }
  unsigned char *romdata = malloc((size_t)len);
  if (!romdata || fread(romdata, 1, len, f) != (size_t)len) {
    free(romdata);
    fclose(f);
    appError = 1;
    notice("Cannot read ROM");
    return;
  }
  fclose(f);
  struct VFile *vf = VFileOpen(path, O_RDONLY);
  struct mCore *c = vf ? mCoreFindVF(vf) : NULL;
  if (!c || !c->init(c)) {
    if (vf)
      vf->close(vf);
    free(romdata);
    appError = 1;
    notice("Unsupported ROM");
    return;
  }
  mCoreInitConfig(c, NULL);
  if (c->platform(c) == mPLATFORM_GB) {
    // Match upstream's Android GB setup, including SGB-enhanced cartridges.
    mCoreConfigSetIntValue(&c->config, "sgb.borders", 0);
    c->reloadConfigOption(c, "sgb.borders", &c->config);
  }
  c->setVideoBuffer(c, video, 256);
  c->setAudioBufferSize(c, 512);
  if (!c->loadROM(c, vf)) {
    vf->close(vf);
    mCoreConfigDeinit(&c->config);
    c->deinit(c);
    free(romdata);
    appError = 1;
    notice("ROM load failed");
    return;
  }
  char sav[1024], ss[1024];
  const char *name = strrchr(path, '/');
  name = name ? name + 1 : path;
  snprintf(sav, sizeof sav, "%s/saves/%s.sav", root, name);
  snprintf(ss, sizeof ss, "%s/states/%s.ss", root, name);
  struct pocket_save_status saveStatus = {0};
  if (!pocket_save_backup(sav, time(NULL))) {
    appError = 1;
    notice("Save backup failed; game not opened");
    goto cleanup;
  }
  struct VFile *sv = pocket_save_wrap(VFileOpen(sav, O_CREAT | O_RDWR), &saveStatus);
  if (!sv || !c->loadSave(c, sv)) {
    if (sv)
      sv->close(sv);
    appError = 1;
    notice("Save file unavailable; game not opened");
    goto cleanup;
  }
  struct mAVStream stream = {.postAudioBuffer = post_audio};
  c->setAVStream(c, &stream);
  for (int i = 0; i < 2; i++)
    blip_set_rates(c->getAudioChannel(c, i), c->frequency(c), 32768);
  c->reset(c);
  uint32_t crc = 0;
  c->checksum(c, &crc, mCHECKSUM_CRC32);
  wideSupported = pk_wide_configure(crc) != 0;
  wideReady = false;
  // WILD ENCOUNTERS: the game's own switch (wild.c finds it), held by pk_wide's field read every frame.
  uint32_t wildSwitch = 0;
  bool fieldKnown = c->platform(c) == mPLATFORM_GBA && pk_field_configure(crc);
  if (fieldKnown)
    wildSwitch = pk_wild_switch_find(romdata, (size_t)len);
  pk_rest_reset(); // pk_call (FLY) learns this game's main loop depth
  unsigned gw, gh;
  c->desiredVideoDimensions(c, &gw, &gh);
  struct pd_game game;
  pd_game_detect(&game, romdata, (size_t)len);
  game.rom = romdata;
  game.romSize = (size_t)len;
  if (c->platform(c) == mPLATFORM_GBA)
    pd_romart_scan(&game);
  char config[1024];
  snprintf(config, sizeof config, "%s/settings.ini", root);
  struct pd_settings settings;
  pd_settings_load(&settings, config);
  if (wildSwitch)
    pk_field_wild(wildSwitch, settings.wildOff);
  bool quickOpen = false;
  int quickSel = 0;
  // FLY (fly.c): the config's FLY where the ROM's code matches. flyAsk = its destinations wanted (asked for between
  // two frames the game's code can be called in), flyList = the menu shows them; flyStage 1 = start the flight,
  // 2 = the fade, then finish it.
  const struct pd_config *flyCfg = game.cfg && fly_supported(game.cfg, romdata, (size_t)len) ? game.cfg : NULL;
  struct fly_targets flyT = {0};
  const char *flyNames[FLY_MAX_TOWNS];
  int flyAsk = 0, flySel = 0, flyStage = 0, flyMapsec = 0, flyWait = 0, fieldFlags = 0;
  bool flyList = false;
  uint32_t flyCallback = 0;
  // REPEL, BIKE and PC anywhere (field_items.c): offered where the ROM's code is the config's. A job waits for
  // two frames the game's code can be called between; BIKE then presses SELECT for the game (gameKeys), watches
  // the riding state flip (or the field lock) and puts the player's own registered item back 20 frames later.
  bool itemsOk = game.cfg && field_items_supported(game.cfg, romdata, (size_t)len);
  bool pcOk = itemsOk && pc_supported(game.cfg, romdata, (size_t)len);
  bool bikeOk = itemsOk && bike_supported(game.cfg);
  enum { JOB_NONE, JOB_PC, JOB_REPEL, JOB_BIKE, JOB_BIKE_WAIT, JOB_BIKE_RESTORE } job = JOB_NONE;
  int jobItem = 0, jobFrames = 0, jobTries = 0, repelSel = 0, repelKinds[REPEL_KINDS], repelKindCount = 0;
  bool repelList = false, bikeWasRiding = false, bikeTook = false;
  uint16_t bikeOld = 0;
  unsigned gameKeys = 0;
  int gameKeyFrames = 0;
  // The HUD: on after 20 frames of the plain field (FieldHud's rule), off at once.
  struct hud_state hudState = {0, 0};
  int hudFieldFrames = 0;
  hudDrawnKey = 0;
  char repelRows[REPEL_KINDS][48];
  const char *repelNames[REPEL_KINDS];
  struct pd_ui ui;
  pd_ui_init(&ui, &settings);
  struct pd_snapshot snap = {0};
  struct pd_host_info host = {.hideScreenMode = true, .hasExpand = true, .hasHud = c->platform(c) == mPLATFORM_GBA,
                              .expandGame = wideSupported, .count = 1, .labels = {"HOST"}};
  snprintf(host.values[0], 40, "PokeDaisy Linux");
  const char *opts[] = {"Resume game",
                        "Party",
                        "Save state",
                        "Load state",
                        "Toggle fast-forward",
                        "Quit to game list",
                        "About"};
  const char *confirmOpts[] = {"Cancel", "Confirm"};
  struct pd_menu gameMenu, confirmMenu;
  pd_menu_init(&gameMenu, "GAME", opts, 7);
  pd_menu_init(&confirmMenu, "CONFIRM", confirmOpts, 2);
  int menuOpen = 0;
  bool companion = false, ff = false, leave = false;
  int frames = 0;
  bool companionWas = false;
  focusId = -1;
  focus_new_screen();
  uint64_t due = SDL_GetPerformanceCounter(), lastFrame = due;
  double hz = (double)c->frequency(c) / c->frameCycles(c);
  pacing_start(hz);
  while (running && !leave) {
    uint64_t frameStart = SDL_GetPerformanceCounter();
    if(saveStatus.failed) { appError=1; notice("Save I/O failed; stopping game"); break; }
    events();
    audio_check();
    repeat_directions();
    if (down(X)) {
      menuOpen = 0;
      companion = !companion;
      c->setKeys(c, 0);
      if (audio)
        SDL_ClearQueuedAudio(audio);
    }
    if (down(MENU) || ((down(START) || down(SELECT)) &&
                       (held & (1u << START)) && (held & (1u << SELECT)))) {
      menuOpen = menuOpen ? 0 : 1;
      companion = true;
      c->setKeys(c, 0);
      if (audio)
        SDL_ClearQueuedAudio(audio);
    }
    // Y: the QUICK MENU over the running game (its keys withheld meanwhile).
    // In the app's order (QuickAction): PC, FLY, REPEL, BIKE, WILD, SAVE / LOAD STATE, FF, COMPANION; each only
    // where the game can, dimmed with why when not now.
    struct quick_item quick[QUICK_COUNT];
    int quickN = 0;
    char repelWhy[32] = "";
    if (pcOk)
      quick[quickN++] = (struct quick_item){QUICK_PC, false, false, NULL, 0, 0, NULL};
    if (flyCfg) {
      int flyer = fly_flyer(&snap);
      quick[quickN++] = (struct quick_item){QUICK_FLY, false, false,
                                            flyer < 0 ? "No one in your party knows FLY" : NULL,
                                            flyer < 0 ? 0 : snap.party[flyer].species, 0, NULL};
    }
    if (itemsOk) {
      // The strongest kind held for the icon; dimmed while one works (the steps left).
      repelKindCount = 0;
      for (int k = 0; k < REPEL_KINDS; k++)
        if (bag_count(&snap, repel_kinds[k][0]) > 0)
          repelKinds[repelKindCount++] = repel_kinds[k][0];
      int left = repel_steps(c, game.cfg);
      if (left)
        snprintf(repelWhy, sizeof repelWhy, "%d steps left", left);
      quick[quickN++] = (struct quick_item){QUICK_REPEL, false, false,
                                            left ? repelWhy : repelKindCount ? NULL : "No REPEL in your bag", 0,
                                            repelKindCount ? repelKinds[repelKindCount - 1] : repel_kinds[0][0], NULL};
    }
    if (bikeOk) {
      int bike = bag_bike(&snap);
      bool riding = (fieldFlags & PK_FIELD_AVATAR) && (fieldFlags & PK_FIELD_BIKE);
      quick[quickN++] = (struct quick_item){QUICK_BIKE, false, false, bike ? NULL : "No bike in your bag", 0,
                                            bike ? bike : 360, riding ? "GET OFF" : NULL};
    }
    quick[quickN++] = (struct quick_item){QUICK_WILD, settings.wildOff, false,
                                          wildSwitch ? NULL : "Not in this game", 0, 0, NULL};
    quick[quickN++] = (struct quick_item){QUICK_SAVE_STATE, false, false, NULL, 0, 0, NULL};
    quick[quickN++] = (struct quick_item){QUICK_LOAD_STATE, false, false,
                                          access(ss, F_OK) == 0 ? NULL : "No state saved yet", 0, 0, NULL};
    quick[quickN++] = (struct quick_item){QUICK_FF, false, ff, NULL, 0, 0, NULL};
    quick[quickN++] = (struct quick_item){QUICK_COMPANION, false, false, NULL, 0, 0, NULL};
    if (quickSel >= quickN)
      quickSel = 0;
    if (down(Y) && !companion) {
      quickOpen = !quickOpen;
      flyList = repelList = false;
      flyAsk = 0;
      c->setKeys(c, 0);
    } else if (quickOpen && repelList) {
      // REPEL's kinds in the bag: UP / DOWN, A uses one, B back to the ring.
      if (down(UP))
        repelSel = (repelSel + repelKindCount - 1) % repelKindCount;
      if (down(DOWN))
        repelSel = (repelSel + 1) % repelKindCount;
      if (down(B))
        repelList = false;
      else if (down(MENU))
        quickOpen = repelList = false;
      else if (down(A)) {
        quickOpen = repelList = false;
        job = JOB_REPEL;
        jobItem = repelKinds[repelSel];
        jobFrames = 0;
      }
    } else if (quickOpen && flyList) {
      // FLY's places: UP / DOWN, A flies, B back to the ring.
      if (down(UP) && flyT.count)
        flySel = (flySel + flyT.count - 1) % flyT.count;
      if (down(DOWN) && flyT.count)
        flySel = (flySel + 1) % flyT.count;
      if (down(B))
        flyList = false;
      else if (down(MENU))
        quickOpen = flyList = false;
      else if (down(A) && flyT.count) {
        quickOpen = flyList = false;
        flyMapsec = flyT.towns[flySel];
        flyStage = 1;
        flyWait = 0;
      }
    } else if (quickOpen && (down(B) || down(MENU))) {
      quickOpen = false;
    } else if (quickOpen) {
      int dx = down(RIGHT) - down(LEFT), dy = down(DOWN) - down(UP);
      if (dx || dy)
        quickSel = quick_step(quickN, quickSel, dx, dy, canvasW, canvasH);
      if (down(A) && !quick[quickSel].why && quick[quickSel].action == QUICK_FLY) {
        flyAsk = 1; // answered below, between two frames
      } else if (down(A) && !quick[quickSel].why && quick[quickSel].action == QUICK_REPEL && repelKindCount > 1) {
        // More than one kind: their list (QuickMenu.kt's RepelKinds), each with how many and its steps.
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
          pd_settings_save(&settings, config);
          notice(settings.wildOff ? "Wild encounters off" : "Wild encounters on");
          break;
        case QUICK_SAVE_STATE:
          notice(state(c, ss, true) ? "State saved" : "State save failed");
          break;
        case QUICK_LOAD_STATE:
          notice(state(c, ss, false) ? "State loaded" : "State load failed");
          break;
        case QUICK_FF:
          ff = !ff;
          break;
        case QUICK_COMPANION:
          companion = true;
          if (audio)
            SDL_ClearQueuedAudio(audio);
          break;
        default:
          break;
        }
      }
    }
    if (companion) {
      companion_canvas();
      if (menuOpen) {
        if (menuOpen == 4) {
          if (down(B) || down(A))
            menuOpen = 1;
        } else {
          struct pd_menu *pm = menuOpen == 1 ? &gameMenu : &confirmMenu;
          if (down(UP))
            pd_menu_move(pm, -1);
          if (down(DOWN))
            pd_menu_move(pm, 1);
          if (down(B)) {
            if (menuOpen == 1) {
              menuOpen = 0;
              companion = false;
            } else
              menuOpen = 1;
          } else if (down(A)) {
            if (menuOpen == 1) {
              switch (gameMenu.selected) {
              case 0:
                menuOpen = 0;
                companion = false;
                break;
              case 1:
                menuOpen = 0;
                ui.tab = PD_TAB_PARTY;
                break;
              case 2:
                menuOpen = 5;
                confirmMenu.title = "Overwrite save state?";
                confirmMenu.selected = 0;
                break;
              case 3:
                menuOpen = 2;
                confirmMenu.title = "Load state? Current progress will rewind.";
                confirmMenu.selected = 0;
                break;
              case 4:
                ff = !ff;
                notice(ff ? "Fast-forward on" : "Fast-forward off");
                break;
              case 5:
                menuOpen = 3;
                confirmMenu.title = "Quit? Save in-game first.";
                confirmMenu.selected = 0;
                break;
              case 6:
                menuOpen = 4;
                break;
              }
            } else {
              if (confirmMenu.selected == 1) {
                if (menuOpen == 3)
                  leave = true;
                else if (menuOpen == 5)
                  notice(state(c, ss, true) ? "State saved"
                                            : "State save failed");
                else
                  notice(state(c, ss, false) ? "State loaded"
                                             : "State load failed");
              }
              menuOpen = 1;
            }
          }
        }
      } else {
        if (down(B) && !pd_ui_back(&ui))
          companion = false;
        if (down(L) || down(R)) {
          pd_ui_next_tab(&ui, down(R) ? 1 : -1);
        }
        for (int k = RIGHT; k <= DOWN; k++)
          if (down(k))
            focus_move(&ui, k);
        int at = focus_index(&ui, focusId);
        if (down(A) && at >= 0) {
          struct pd_hit hit = ui.hits[at];
          pd_ui_touch(&ui, hit.x + hit.w / 2, hit.y + hit.h / 2, true);
          pd_ui_touch(&ui, hit.x + hit.w / 2, hit.y + hit.h / 2, false);
        }
        switch (pd_ui_take_action(&ui)) {
        case PD_ACTION_LEAVE_GAME:
          leave = true;
          break;
        case PD_ACTION_TOGGLE_FF:
          ff = !ff;
          break;
        case PD_ACTION_SAVE_STATE:
          notice(state(c, ss, true) ? "State saved" : "State save failed");
          break;
        case PD_ACTION_LOAD_STATE:
          notice(state(c, ss, false) ? "State loaded" : "State load failed");
          break;
        case PD_ACTION_SETTINGS_CHANGED:
          pd_settings_save(&settings, config);
          break;
        default:
          break;
        }
      }
      if (down(L2)) {
        menuOpen = 5;
        confirmMenu.title = "Overwrite save state?";
        confirmMenu.selected = 0;
      }
      if (down(R2)) {
        menuOpen = 2;
        confirmMenu.title = "Load state? Current progress will rewind.";
        confirmMenu.selected = 0;
      }
    } else {
      muted = ff;
      c->setKeys(c, (quickOpen ? 0 : held & 1023) | (gameKeyFrames > 0 ? gameKeys : 0));
      int count = ff ? settings.ffSpeed : 1;
      audio_rate(c, hz);
      for (int i = 0; i < count; i++) {
        uint64_t t = SDL_GetPerformanceCounter();
        c->runFrame(c);
        if (gameKeyFrames > 0 && --gameKeyFrames == 0)
          c->setKeys(c, quickOpen ? 0 : held & 1023);
        if (c->platform(c) == mPLATFORM_GBA)
          pk_rest_note(c);
        if (fieldKnown)
          fieldFlags = pk_field_state(c, 0, wildSwitch && settings.wildOff ? 0 : 100);
        emuMs += ms_since(t);
        t = SDL_GetPerformanceCounter();
        wide_tick(c, i < count - 1, &settings);
        wideMs += ms_since(t);
      }
    }
    // The companion's data: fresh when it opens (it pauses the game), else twice a
    // second - read through the bus byte by byte, every frame cost frame time.
    if (!snap.valid || (companion && !companionWas) || (!companion && frames % 30 == 0))
      pd_snapshot_read(&snap, &game, read_memory, c);
    companionWas = companion;
    // FLY's steps, each between two frames the game's own code can be called in (pk_call_safe), as the app's
    // field-item jobs wait for one.
    bool flyBattle = snap.inBattle || (fieldFlags & PK_FIELD_BATTLE);
    bool flyBusy = companion || (fieldFlags & PK_FIELD_LOCKED) ||
                   (wideSupported && pk_wide_scene() != PK_WIDE_SCENE_FIELD);
    if (flyCfg && flyAsk && quickOpen && !companion) {
      if (pk_call_safe(c)) {
        pd_snapshot_read(&snap, &game, read_memory, c);
        fly_targets(c, flyCfg, romdata, (size_t)len, &snap, flyBusy, flyBattle, &flyT);
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
    if (flyCfg && flyStage == 1 && !companion && pk_call_safe(c)) {
      pd_snapshot_read(&snap, &game, read_memory, c);
      enum fly_result r = fly_start(c, flyCfg, romdata, (size_t)len, &snap, flyMapsec, &flyT, flyBusy, flyBattle);
      if (r == FLY_OK) {
        flyStage = 2;
        flyWait = 0;
        flyCallback = fly_main_callback(c, flyCfg);
      } else {
        flyStage = 0;
        notice(fly_reason(r));
      }
    } else if (flyCfg && flyStage == 2 && !companion && ++flyWait >= FLY_FADE_FRAMES && pk_call_safe(c)) {
      flyStage = 0;
      if (!fly_finish(c, flyCfg, romdata, (size_t)len, flyMapsec, &flyT, fly_main_callback(c, flyCfg) != flyCallback))
        notice(fly_reason(FLY_FAILED));
    }
    bool safe = job != JOB_NONE && !companion && pk_call_safe(c);
    if (job == JOB_PC && safe) {
      job = JOB_NONE;
      enum item_result r = pc_open(c, game.cfg, romdata, (size_t)len, flyBusy, flyBattle);
      if (r != ITEM_USED)
        notice(item_reason(r));
    } else if (job == JOB_REPEL && safe) {
      job = JOB_NONE;
      int steps = 0;
      enum item_result r = repel_use(c, game.cfg, romdata, (size_t)len, jobItem, flyBusy, flyBattle, &steps);
      if (r == ITEM_USED) {
        char msg[48];
        snprintf(msg, sizeof msg, "Used! It lasts %d steps.", steps);
        notice(msg);
      } else
        notice(item_reason(r));
    } else if (job == JOB_BIKE && safe) {
      enum item_result r = bike_register(c, game.cfg, romdata, (size_t)len, jobItem, flyBusy, flyBattle, &bikeOld);
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
      // SELECT again if the game dropped it (a bike rolling at full speed ignores it), but only where taking it
      // shows for sure: the riding state flips, or the field's controls stay held (PokeDaisyActivity.pressSelect).
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
      bike_restore(c, game.cfg, jobItem, bikeOld);
    } else if ((job == JOB_PC || job == JOB_REPEL || job == JOB_BIKE) && !companion && ++jobFrames > 120) {
      job = JOB_NONE;
      notice(item_reason(ITEM_FAILED));
    }
    if (quickOpen && !companion) {
      companion_canvas();
      const char *const *rows = flyList ? flyNames : repelList ? repelNames : NULL;
      present_quick(&game, quick, quickN, quickSel, flyList ? "FLY" : "REPEL", rows,
                    flyList ? flyT.count : repelKindCount,
                    flyList ? flySel : repelSel, gw, gh);
    } else if (companion && menuOpen && menuOpen != 4) {
      present_menu(menuOpen == 1 ? &gameMenu : &confirmMenu, gw, gh);
    } else if (companion) {
      if (menuOpen) {
        if (menuOpen == 4) {
          pd_splash_draw(&canvas, "Based on PokeDaisy by Lidor Itzhari",
                         "https://github.com/lidor30/pokedaisy",
                         "GPL-3.0 | mGBA MPL-2.0 | B: Back");
        } else
          pd_menu_draw(menuOpen == 1 ? &gameMenu : &confirmMenu, &canvas);
      } else {
        pd_ui_update(&ui, &snap);
        // The companion draws the focused hit as its own cursor where it has one.
        ui.focusId = focusId;
        host.ffOn = ff;
        host.hasState = access(ss, F_OK) == 0;
        pd_ui_draw(&ui, &canvas, &game, &snap, &host);
        focus_draw(&ui, focus_resolve(&ui));
      }
      present();
    } else {
      bool field = (fieldFlags & PK_FIELD_KNOWN) && !(fieldFlags & (PK_FIELD_LOCKED | PK_FIELD_BATTLE)) &&
                   (!wideSupported || pk_wide_scene() == PK_WIDE_SCENE_FIELD) && flyStage == 0;
      hudFieldFrames = field ? hudFieldFrames + 1 : 0;
      if (settings.hud && game.cfg && c->platform(c) == mPLATFORM_GBA && hudFieldFrames >= 20) {
        int steps = repel_steps(c, game.cfg);
        if (steps <= 0)
          hudState.repelFull = 0;
        else if (!hudState.repelFull || steps > hudState.repelFull)
          hudState.repelFull = hud_repel_full(steps);
        hudState.repel = steps > 0 ? steps : 0;
        companion_canvas();
        present_hud_game(&game, &snap, &hudState, settings.hudHidden, gw, gh);
      } else {
        present_game(gw, gh);
      }
    }
#ifdef POCKET_TEST
    test_frame(c, companion, frames, &ui, &snap);
#endif
    frames++;
    if (maxFrames && frames >= maxFrames) {
      if (shot) {
        unsigned lit = 0;
        if (!capture_frame(shot, &lit))
          notice("Capture failed");
        fprintf(stderr,
                "Capture frame %u: %ux%u game, rect %d,%d %dx%d, lit=%u\n",
                c->frameCounter(c), gw, gh, lastGameRect.x, lastGameRect.y,
                lastGameRect.w, lastGameRect.h, lit);
      }
      break;
    }
    perf_frame(frameStart, hz);
    uint64_t now = SDL_GetPerformanceCounter();
    pacing_check((double)(now - lastFrame) * 1000.0 / SDL_GetPerformanceFrequency());
    lastFrame = now;
    if (vsyncPaced) {
      due = now; // the present waited for the refresh: that's the pace
      continue;
    }
    due += (uint64_t)(SDL_GetPerformanceFrequency() / hz);
    if (now < due)
      SDL_Delay((Uint32)((due - now) * 1000 / SDL_GetPerformanceFrequency()));
    else if (now - due > SDL_GetPerformanceFrequency() / 5)
      due = now;
  }
  if (audio)
    SDL_ClearQueuedAudio(audio);
cleanup:
  pk_wide_configure(0);
  wideSupported = wideReady = false;
  c->unloadROM(c);
  if(saveStatus.failed)appError=1;
  mCoreConfigDeinit(&c->config);
  c->deinit(c);
  free(romdata);
  held = pressed = 0;
}
static int cmp(const void *a, const void *b) {
  return strcasecmp(*(char *const *)a, *(char *const *)b);
}
int main(int argc, char **argv) {
  const char *rom = NULL, *shot = NULL;
  int frames = 0;
  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "--data") && i + 1 < argc)
      root = argv[++i];
    else if (!strcmp(argv[i], "--frames") && i + 1 < argc)
      frames = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--shot") && i + 1 < argc)
      shot = argv[++i];
    else
      rom = argv[i];
  }
  mLogSetDefaultLogger(&quietLogger);
  signal(SIGINT, stop);
  signal(SIGTERM, stop);
  mkdir(root, 0755);
  char path[1024];
  const char *dirs[] = {"roms", "saves", "states"};
  for (int i = 0; i < 3; i++) {
    snprintf(path, sizeof path, "%s/%s", root, dirs[i]);
    mkdir(path, 0755);
  }
  if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_JOYSTICK | SDL_INIT_GAMECONTROLLER)) {
    fprintf(stderr, "SDL: %s\n", SDL_GetError());
    return 1;
  }
  SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "0");
  SDL_DisplayMode display = {0};
  bool fullscreen = getenv("POCKET_FULLSCREEN") != NULL;
  if(fullscreen && SDL_GetCurrentDisplayMode(0,&display))fullscreen=false;
  // POCKET_WINDOW=WxH: a window the size of a device's panel, for trying a
  // layout on a desktop (1280x720 Smart Pro, 640x480, 720x720 ...).
  int winW = W, winH = H;
  const char *size = getenv("POCKET_WINDOW");
  if (size && (sscanf(size, "%dx%d", &winW, &winH) != 2 || winW < 160 ||
               winH < 144 || winW > 8192 || winH > 8192))
    winW = W, winH = H;
  window = SDL_CreateWindow("PokeDaisy Linux", SDL_WINDOWPOS_CENTERED,
      SDL_WINDOWPOS_CENTERED, fullscreen?display.w:winW, fullscreen?display.h:winH,
      fullscreen?SDL_WINDOW_FULLSCREEN:SDL_WINDOW_RESIZABLE);
  // Presents wait for the refresh (the pacing, above); a driver without it gets the timer.
  renderer = window ? SDL_CreateRenderer(window, -1, getenv("POCKET_NO_VSYNC") ? 0 : SDL_RENDERER_PRESENTVSYNC) : NULL;
  if (!renderer && window)
    renderer = SDL_CreateRenderer(window, -1, 0);
  if (!renderer) {
    fprintf(stderr, "Video: %s\n", SDL_GetError());
    SDL_Quit();
    return 1;
  }
  SDL_RendererInfo rendererInfo;
  if (!SDL_GetRendererInfo(renderer, &rendererInfo))
    fprintf(stderr, "Video backend: %s; opaque RGB888 canvas %dx%d\n",
            rendererInfo.name, canvasW, canvasH);
  SDL_ShowCursor(SDL_DISABLE);
  companion_canvas();
  gameTexture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_BGR888,
                                  SDL_TEXTUREACCESS_STREAMING, 256, 256);
  wideTexture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_BGR888,
                                  SDL_TEXTUREACCESS_STREAMING, PK_WIDE_MAX_VIEW_W,
                                  PK_WIDE_MAX_VIEW_H);
  if (!texture || !gameTexture || !wideTexture) {
    fprintf(stderr, "Texture: %s\n", SDL_GetError());
    return 1;
  }
  SDL_SetTextureBlendMode(gameTexture, SDL_BLENDMODE_NONE);
  SDL_SetTextureBlendMode(wideTexture, SDL_BLENDMODE_NONE);
  SDL_SetRenderDrawColor(renderer, 0, 0, 0, 255);
  audio_open();
  rawTrimui=getenv("POCKET_RAW_TRIMUI") && !strcmp(getenv("POCKET_RAW_TRIMUI"),"1");
  snprintf(path, sizeof path, "%s/gamecontrollerdb.txt", root);
  if (access(path, R_OK) == 0) {
    int added = SDL_GameControllerAddMappingsFromFile(path);
    fprintf(stderr, "Controller mappings from %s: %d\n", path, added);
  }
  for (int i = 0; i < SDL_NumJoysticks() && !controller; i++)
    if (SDL_IsGameController(i))
      controller_added(i);
  SDL_Joystick *js = !controller && rawTrimui && SDL_NumJoysticks() ? SDL_JoystickOpen(0) : NULL;
  if (rom)
    run_game(rom, frames, shot);
  else {
    char *names[256];
    int count = 0;
    snprintf(path, sizeof path, "%s/roms", root);
    DIR *d = opendir(path);
    struct dirent *e;
    while (d && (e = readdir(d)) && count < 256) {
      char *ext = strrchr(e->d_name, '.');
      if (ext && (!strcasecmp(ext, ".gba") || !strcasecmp(ext, ".gbc") ||
                  !strcasecmp(ext, ".gb")
#ifdef POCKET_DSPERATE
                  || !strcasecmp(ext, ".nds")
#endif
                  ))
        names[count++] = strdup(e->d_name);
    }
    if (d)
      closedir(d);
    qsort(names, count, sizeof(*names), cmp);
    struct pd_menu menu;
    pd_menu_init(&menu, "PokeDaisy Linux", (const char *const *)names, count);
    int ticks = 0;
    while (running) {
      events();
      repeat_directions();
      if (down(B) || down(MENU))
        break;
      if (down(UP))
        pd_menu_move(&menu, -1);
      if (down(DOWN))
        pd_menu_move(&menu, 1);
      if (down(A) && count) {
        snprintf(path, sizeof path, "%s/roms/%s", root, names[menu.selected]);
        run_game(path, 0, NULL);
      }
      companion_canvas();
      pd_menu_draw(&menu, &canvas);
      present();
      if (frames && ++ticks >= frames) {
        if (shot)
          capture_frame(shot, NULL);
        break;
      }
      SDL_Delay(16);
    }
    for (int i = 0; i < count; i++)
      free(names[i]);
  }
  if (js)
    SDL_JoystickClose(js);
  if(controller) {SDL_GameControllerClose(controller);controller=NULL;}
  if (audio)
    SDL_CloseAudioDevice(audio);
  // NULLed: the runtime test runs the app again in a new window.
  SDL_DestroyTexture(texture);
  texture = NULL;
  SDL_DestroyTexture(gameTexture);
  SDL_DestroyTexture(wideTexture);
  if (menuTexture)
    SDL_DestroyTexture(menuTexture);
  menuTexture = NULL;
  if (hudTexture)
    SDL_DestroyTexture(hudTexture);
  hudTexture = NULL;
  SDL_DestroyRenderer(renderer);
  SDL_DestroyWindow(window);
  SDL_Quit();
  return appError;
}
