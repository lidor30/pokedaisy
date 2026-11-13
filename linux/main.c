/* PokeDaisy Linux: an SDL2 frontend for the PokeDaisy libretro cores. The companion, HUD, quick menu, FLY / PC /
 * REPEL / BIKE and EXPAND are the cores' own (libretro/, libretro/armgba/), so this host shows exactly what the
 * cores do on every other frontend. It loads the core for the ROM from beside the binary
 * (armGBA for .gba, mGBA for .gb / .gbc, DSperate for .nds), tells it the screen (the pokedaisy_panel option: the core then picks its canvas), shows its frame at a whole scale, and owns
 * what a frontend owns: input, sound, pacing, the battery save and its backups, save states, fast-forward,
 * rewind, the LCD mask and the game list. */
#include "libretro.h"
#include "../3ds/desktop/pd_png.h"
#include "display_filter.h"
#include "pd_canvas.h"
#include "pd_menu.h"
#include <SDL.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

// The window's size without POCKET_FULLSCREEN / POCKET_WINDOW.
#define W 512
#define H 384
#define OVERLAY_MAX_W 1024
#define OVERLAY_MAX_H 768
#define KEY_COLOR 0xFF00FFu // the overlay's see-through colour
#define OUT_RATE 48000
#define MODIFIER_TAP_FRAMES 6 // a tap of the MODIFIER alone reaches the game this long (Game Boy menus miss 1-frame presses)

static SDL_Window *window;
static SDL_Renderer *renderer;
static SDL_AudioDeviceID audio;
static volatile sig_atomic_t running = 1;
static int appError;
static const char *root = ".";
/* plorpOS: --save FILE, the battery save another emulator keeps (its backups then go to DATA/backups);
 * NULL = DATA/saves/ROM.sav. */
static const char *saveFile;
static char savesDir[PATH_MAX], exeDir[PATH_MAX];
static char message[128];
static unsigned messageUntil;

static void stop(int sig) {
  (void)sig;
  running = 0;
}
static void notice(const char *s) {
  snprintf(message, sizeof message, "%s", s);
  messageUntil = SDL_GetTicks() + 2500;
  fprintf(stderr, "%s\n", s);
}

/* ---- input ---- */
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
  L3,
  SUP, // the left stick's directions apart (they're also the D-pad's)
  SDOWN,
  SLEFT,
  SRIGHT,
  R3,
  P1, // the back paddles (the Steam Deck's L4 / R4 / L5 / R5, an Elite pad's), when SDL is given them
  P2,
  P3,
  P4,
  FFKEY, // the keyboard's own: fast-forward held, rewind held, save / load state
  RWKEY,
  SAVEKEY,
  LOADKEY,
  NKEYS
};
static SDL_GameController *controller;
static bool rawTrimui;
static bool swapFace; // the host menu's A/B BUTTONS: SWAPPED (host.ini swap_ab)
// A controller's face button, after the host menu's A/B BUTTONS (the keyboard's keys stay where they are).
static int face(int k) {
  if (!swapFace)
    return k;
  return k == A ? B : k == B ? A : k == X ? Y : k == Y ? X : k;
}
static unsigned held, pressed;
static bool inputReset;
static unsigned repeatAt[NKEYS];
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
  case SDLK_SPACE:
    return FFKEY;
  case SDLK_r:
    return RWKEY;
  case SDLK_F2:
    return SAVEKEY;
  case SDLK_F4:
    return LOADKEY;
  default:
    return -1;
  }
}
static int controller_button(int button) {
  switch (button) {
  case SDL_CONTROLLER_BUTTON_A:
    return B;
  case SDL_CONTROLLER_BUTTON_B:
    return A;
  case SDL_CONTROLLER_BUTTON_X:
    return Y;
  case SDL_CONTROLLER_BUTTON_Y:
    return X;
  case SDL_CONTROLLER_BUTTON_BACK:
    return SELECT;
  case SDL_CONTROLLER_BUTTON_START:
    return START;
  case SDL_CONTROLLER_BUTTON_GUIDE:
    return MENU;
  case SDL_CONTROLLER_BUTTON_LEFTSTICK:
    return L3;
  case SDL_CONTROLLER_BUTTON_RIGHTSTICK:
    return R3;
  case SDL_CONTROLLER_BUTTON_PADDLE1:
    return P1;
  case SDL_CONTROLLER_BUTTON_PADDLE2:
    return P2;
  case SDL_CONTROLLER_BUTTON_PADDLE3:
    return P3;
  case SDL_CONTROLLER_BUTTON_PADDLE4:
    return P4;
  case SDL_CONTROLLER_BUTTON_LEFTSHOULDER:
    return L;
  case SDL_CONTROLLER_BUTTON_RIGHTSHOULDER:
    return R;
  case SDL_CONTROLLER_BUTTON_DPAD_UP:
    return UP;
  case SDL_CONTROLLER_BUTTON_DPAD_DOWN:
    return DOWN;
  case SDL_CONTROLLER_BUTTON_DPAD_LEFT:
    return LEFT;
  case SDL_CONTROLLER_BUTTON_DPAD_RIGHT:
    return RIGHT;
  default:
    return -1;
  }
}
static int joy(int n) {
  static const int map[] = {B, A, Y, X, L, R, SELECT, START, MENU, -1, L2, R2};
  return n >= 0 && n < 12 ? map[n] : -1;
}
/* A direction is held by the D-pad (or the keyboard, the hat) and by the left stick apart: letting go of one keeps it
 * held while the other still has it. */
static unsigned padHeld;  // directions the D-pad, keyboard or hat hold
static bool fromStick;    // key() called by stick_dir
static int stick_of(int dir) { return dir == UP ? SUP : dir == DOWN ? SDOWN : dir == LEFT ? SLEFT : dir == RIGHT ? SRIGHT : -1; }
static void key(int k, bool isDown) {
  if (k < 0)
    return;
  unsigned bit = 1u << k;
  int stick = stick_of(k);
  if (stick >= 0) {
    if (fromStick) {
      if (!isDown && (padHeld & bit))
        return; // the D-pad still holds it
    } else {
      padHeld = isDown ? padHeld | bit : padHeld & ~bit;
      if (!isDown && (held & (1u << stick)))
        return; // the stick still holds it
    }
  }
  if (isDown) {
    if (!(held & bit)) {
      pressed |= bit;
      repeatAt[k] = SDL_GetTicks() + 400;
    }
    held |= bit;
  } else
    held &= ~bit;
}
/* The left stick is the D-pad too, but only when it crosses the threshold: a stick at rest still sends small axis
 * events (the Steam Deck's, all the time), and releasing the direction on each of them let go of a held D-pad. */
static void stick_dir(int stickKey, int dir, bool on) {
  if (on == ((held & (1u << stickKey)) != 0))
    return;
  key(stickKey, on);
  fromStick = true;
  key(dir, on);
  fromStick = false;
}
/* Controllers come and go (a USB or Bluetooth pad, a handheld whose built-in one shows up late): SDL reports the
 * ones it has a mapping for as game controllers (its own database, DATA/gamecontrollerdb.txt, or
 * SDL_GAMECONTROLLERCONFIG, which PortMaster sets). The first one is used; an unmapped device is named in the log
 * so a mapping can be added. */
static void controller_added(int index) {
  if (!SDL_IsGameController(index)) {
    char guid[64];
    SDL_JoystickGetGUIDString(SDL_JoystickGetDeviceGUID(index), guid, sizeof guid);
    fprintf(stderr,
            "Controller without a mapping: %s (%s); add one to "
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
  if (!controller || SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(controller)) != id)
    return;
  SDL_GameControllerClose(controller);
  controller = NULL;
  inputReset = true;
  held = 0; // nothing stays pressed on a pad that's gone
  for (int i = 0; i < SDL_NumJoysticks() && !controller; i++)
    if (SDL_IsGameController(i))
      controller_added(i);
}
/* The pointer (a touch screen, or the mouse): where on the core's frame, for RETRO_DEVICE_POINTER. */
static SDL_Rect frameRect;
static int pointerX, pointerY;
static bool pointerDown;
static void pointer_at(int x, int y) {
  if (frameRect.w <= 0 || frameRect.h <= 0)
    return;
  int px = (int)((long)(x - frameRect.x) * 0xFFFE / frameRect.w) - 0x7FFF;
  int py = (int)((long)(y - frameRect.y) * 0xFFFE / frameRect.h) - 0x7FFF;
  pointerX = px < -0x7FFF ? -0x7FFF : px > 0x7FFF ? 0x7FFF : px;
  pointerY = py < -0x7FFF ? -0x7FFF : py > 0x7FFF ? 0x7FFF : py;
}
static bool audioLost, outputChanged;
static int key_named(const char *s);
/* POCKET_KEYS="TICK:KEY[:HOLD],...": presses scripted by loop tick (each events() call), held HOLD ticks (2), for
 * tests: KEY is a key name (a, b, up, menu, start, l3, r2 ...: keyNames). */
static struct {
  unsigned at, hold;
  int key;
} script[64];
static int scriptN = -1;
static unsigned tick;
static void key(int k, bool isDown);
static void script_keys(void) {
  if (scriptN < 0) {
    scriptN = 0;
    char buf[1024], *save = NULL;
    snprintf(buf, sizeof buf, "%s", getenv("POCKET_KEYS") ? getenv("POCKET_KEYS") : "");
    for (char *t = strtok_r(buf, ",", &save); t && scriptN < 64; t = strtok_r(NULL, ",", &save)) {
      char name[16];
      unsigned at, hold = 2;
      if (sscanf(t, "%u:%15[^:]:%u", &at, name, &hold) >= 2 && key_named(name) >= 0)
        script[scriptN++] = (typeof(script[0])){at, hold, key_named(name)};
    }
  }
  for (int i = 0; i < scriptN; i++) {
    if (tick == script[i].at)
      key(script[i].key, true);
    if (tick == script[i].at + script[i].hold)
      key(script[i].key, false);
  }
  tick++;
}
static void events(void) {
  pressed = 0;
  SDL_Event e;
  while (SDL_PollEvent(&e)) {
    if (e.type == SDL_QUIT)
      running = 0;
    if (e.type == SDL_JOYDEVICEADDED && !SDL_IsGameController(e.jdevice.which))
      controller_added(e.jdevice.which);
    if (e.type == SDL_AUDIODEVICEREMOVED && !e.adevice.iscapture && audio && e.adevice.which == audio)
      audioLost = true;
    if (e.type == SDL_CONTROLLERDEVICEADDED)
      controller_added(e.cdevice.which);
    if (e.type == SDL_CONTROLLERDEVICEREMOVED)
      controller_removed(e.cdevice.which);
    if ((e.type == SDL_KEYDOWN || e.type == SDL_KEYUP) && !e.key.repeat)
      key(keyboard(e.key.keysym.sym), e.type == SDL_KEYDOWN);
    if (e.type == SDL_CONTROLLERBUTTONDOWN || e.type == SDL_CONTROLLERBUTTONUP)
      key(face(controller_button(e.cbutton.button)), e.type == SDL_CONTROLLERBUTTONDOWN);
    if (e.type == SDL_CONTROLLERAXISMOTION) {
      if (e.caxis.axis == SDL_CONTROLLER_AXIS_TRIGGERLEFT)
        key(L2, e.caxis.value > 16000);
      if (e.caxis.axis == SDL_CONTROLLER_AXIS_TRIGGERRIGHT)
        key(R2, e.caxis.value > 16000);
      if (e.caxis.axis == SDL_CONTROLLER_AXIS_LEFTX) {
        stick_dir(SLEFT, LEFT, e.caxis.value < -16000);
        stick_dir(SRIGHT, RIGHT, e.caxis.value > 16000);
      }
      if (e.caxis.axis == SDL_CONTROLLER_AXIS_LEFTY) {
        stick_dir(SUP, UP, e.caxis.value < -16000);
        stick_dir(SDOWN, DOWN, e.caxis.value > 16000);
      }
    }
    if (!controller && rawTrimui && (e.type == SDL_JOYBUTTONDOWN || e.type == SDL_JOYBUTTONUP))
      key(face(joy(e.jbutton.button)), e.type == SDL_JOYBUTTONDOWN);
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
    if (e.type == SDL_MOUSEBUTTONDOWN || e.type == SDL_MOUSEBUTTONUP) {
      pointer_at(e.button.x, e.button.y);
      pointerDown = e.type == SDL_MOUSEBUTTONDOWN && e.button.button == SDL_BUTTON_LEFT;
    }
    if (e.type == SDL_MOUSEMOTION)
      pointer_at(e.motion.x, e.motion.y);
    if (e.type == SDL_WINDOWEVENT && e.window.event == SDL_WINDOWEVENT_FOCUS_LOST) {
      inputReset = true;
      held = pressed = 0;
    }
    if (e.type == SDL_WINDOWEVENT && e.window.event == SDL_WINDOWEVENT_SIZE_CHANGED)
      outputChanged = true;
  }
  script_keys();
}
static void repeat_directions(void) {
  for (int k = RIGHT; k <= DOWN; k++)
    if ((held & (1u << k)) && SDL_GetTicks() >= repeatAt[k]) {
      pressed |= 1u << k;
      repeatAt[k] = SDL_GetTicks() + 90;
    }
}
static bool down(int k) { return (pressed & (1u << k)) != 0; }

/* Hotkeys: a button per action, set on the host menu's HOTKEYS page (host.ini hk_*). FF and rewind act while held,
 * the states on the press. A hotkey's button never reaches the game. The keyboard keeps its own (Space, R, F2, F4). */
enum { HK_FF, HK_REWIND, HK_SAVE, HK_LOAD, HK_ACTIONS };
static const char *const hotkeyIds[HK_ACTIONS] = {"ff", "rewind", "savestate", "loadstate"};
static unsigned hotkeyMask;
// The game's own buttons and the menu's stay theirs: A, B, START, SELECT, the D-pad (and the stick's), MENU.
static bool hotkey_assignable(int k) {
  return k == L || k == R || k == X || k == Y || k == L2 || k == R2 || k == L3 || k == R3 || (k >= P1 && k <= P4);
}
// START+SELECT opens the host menu too, unless POCKET_MENU_COMBO=off (PortMaster: there it is the port's exit).
static bool comboMenu = true;
static const struct {
  const char *name;
  int key;
} keyNames[] = {{"a", A},       {"b", B},         {"x", X},         {"y", Y},           {"l1", L},
                {"r1", R},      {"l2", L2},       {"r2", R2},       {"up", UP},         {"down", DOWN},
                {"left", LEFT}, {"right", RIGHT}, {"menu", MENU},   {"select", SELECT}, {"start", START}, {"l3", L3},
                {"sup", SUP},   {"sdown", SDOWN}, {"sleft", SLEFT}, {"sright", SRIGHT}, {"r3", R3},
                {"p1", P1},     {"p2", P2},       {"p3", P3},       {"p4", P4}};
static int key_named(const char *s) {
  for (size_t i = 0; i < sizeof keyNames / sizeof keyNames[0]; i++)
    if (!strcmp(s, keyNames[i].name))
      return keyNames[i].key;
  return -1;
}
static const char *key_name(int k) {
  for (size_t i = 0; i < sizeof keyNames / sizeof keyNames[0]; i++)
    if (keyNames[i].key == k)
      return keyNames[i].name;
  return "off";
}
/* ---- the host's settings (DATA/host.ini): what its menu sets ---- */
static struct {
  int ffSpeed; // 0 = MAX (as many frames as fit), else 2..8
  bool rewind, lcd;
  bool swapAB; // the controller's A / B and X / Y the other way round (devices disagree on which is SDL's "A")
  int hotkey[HK_ACTIONS]; // the HOTKEYS page's button per action, -1 none
  int dsHotkey[HK_ACTIONS];
  int modifier;
} hostSet = {0, false, false, false, {R2, L2, -1, -1}, {R2, L2, -1, -1}, SELECT};
static char hostIni[PATH_MAX];
/* A DS game's L2 / R2 are its companion and screen buttons: with no modifier held for them, its FAST FORWARD and REWIND
 * go back to the stick clicks (R3 / L3), and come back to R2 / L2 when a modifier is picked again. */
static void ds_hotkeys_follow_modifier(void) {
  int *ds = hostSet.dsHotkey;
  if (hostSet.modifier < 0 && ds[HK_FF] == R2 && ds[HK_REWIND] == L2) {
    ds[HK_FF] = R3;
    ds[HK_REWIND] = L3;
  } else if (hostSet.modifier >= 0 && ds[HK_FF] == R3 && ds[HK_REWIND] == L3) {
    ds[HK_FF] = R2;
    ds[HK_REWIND] = L2;
  }
}
/* A new modifier can't also be a hotkey's own button (that hotkey would never fire): the hotkey lets it go. */
static void modifier_takes(int k) {
  for (int i = 0; i < HK_ACTIONS; i++) {
    if (hostSet.hotkey[i] == k)
      hostSet.hotkey[i] = -1;
    if (hostSet.dsHotkey[i] == k)
      hostSet.dsHotkey[i] = -1;
  }
}
static void host_load(void) {
  FILE *f = fopen(hostIni, "r");
  char line[128];
  int v;
  bool modifierSaved = false;
  while (f && fgets(line, sizeof line, f)) {
    if (sscanf(line, "ff_speed=%d", &v) == 1)
      hostSet.ffSpeed = v == 0 || (v >= 2 && v <= 8) ? v : 0;
    if (sscanf(line, "rewind=%d", &v) == 1)
      hostSet.rewind = v != 0;
    if (sscanf(line, "lcd=%d", &v) == 1)
      hostSet.lcd = v != 0;
    if (sscanf(line, "swap_ab=%d", &v) == 1)
      hostSet.swapAB = v != 0;
    char modifier[16];
    if (sscanf(line, "hk_modifier=%15s", modifier) == 1) {
      int k = key_named(modifier);
      if (k == SELECT || k == START || hotkey_assignable(k) || !strcmp(modifier, "off")) {
        hostSet.modifier = k;
        modifierSaved = true;
      }
    }
    for (int i = 0; i < HK_ACTIONS; i++) {
      char key[32], name[16];
      snprintf(key, sizeof key, "hk_%s=%%15s", hotkeyIds[i]);
      if (sscanf(line, key, name) == 1)
        hostSet.hotkey[i] = hotkey_assignable(key_named(name)) ? key_named(name) : -1;
      snprintf(key, sizeof key, "ds_hk_%s=%%15s", hotkeyIds[i]);
      if (sscanf(line, key, name) == 1)
        hostSet.dsHotkey[i] = hotkey_assignable(key_named(name)) ? key_named(name) : -1;
    }
  }
  if (f)
    fclose(f);
  if (!modifierSaved && hostSet.dsHotkey[HK_FF] == R3 && hostSet.dsHotkey[HK_REWIND] == L3) {
    hostSet.dsHotkey[HK_FF] = R2;
    hostSet.dsHotkey[HK_REWIND] = L2;
  }
  ds_hotkeys_follow_modifier();
  swapFace = hostSet.swapAB;
}
static void host_save(void) {
  FILE *f = fopen(hostIni, "w");
  if (!f)
    return;
  fprintf(f, "ff_speed=%d\nrewind=%d\nlcd=%d\nswap_ab=%d\n", hostSet.ffSpeed, hostSet.rewind, hostSet.lcd, hostSet.swapAB);
  fprintf(f, "hk_modifier=%s\n", key_name(hostSet.modifier));
  for (int i = 0; i < HK_ACTIONS; i++) {
    fprintf(f, "hk_%s=%s\n", hotkeyIds[i], key_name(hostSet.hotkey[i]));
    fprintf(f, "ds_hk_%s=%s\n", hotkeyIds[i], key_name(hostSet.dsHotkey[i]));
  }
  fclose(f);
}
// A hotkey's button as the HOTKEYS page shows it.
static const char *hotkey_label(int k) {
  static const char *const labels[] = {[START] = "START", [SELECT] = "SELECT", [L] = "L1", [R] = "R1",  [X] = "X",         [Y] = "Y",         [L2] = "L2",
                                       [R2] = "R2", [L3] = "L3", [R3] = "R3",       [P1] = "PADDLE 1", [P2] = "PADDLE 2",
                                       [P3] = "PADDLE 3", [P4] = "PADDLE 4"};
  return k >= 0 && k < (int)(sizeof labels / sizeof labels[0]) && labels[k] ? labels[k] : "OFF";
}
/* The actions held this frame; *fired: those whose button went down now. The keyboard's own keys count too. */
static unsigned hotkeys_held(const int *hotkeys, unsigned modifier, unsigned *latched, unsigned *fired) {
  unsigned on = 0;
  *fired = 0;
  hotkeyMask = 0;
  for (int i = 0; i < HK_ACTIONS; i++) {
    int k = hotkeys[i];
    // REWIND's button is the game's while REWIND is off (L2 to start with: the core's own buttons may be on it).
    if (k < 0 || (i == HK_REWIND && !hostSet.rewind))
      continue;
    unsigned bit = 1u << k;
    if (bit == modifier)
      continue;
    if (modifier) {
      if ((held & modifier) && (pressed & bit))
        *latched |= bit;
      if (!(*latched & bit) || !(held & modifier))
        continue;
    }
    hotkeyMask |= bit;
    // pressed too: a tap shorter than a frame is let go before this sees it.
    if (!((held | pressed) & bit))
      continue;
    on |= 1u << i;
    if (pressed & bit)
      *fired |= 1u << i;
  }
  if (held & (1u << FFKEY))
    on |= 1u << HK_FF;
  if (held & (1u << RWKEY))
    on |= 1u << HK_REWIND;
  if (down(SAVEKEY))
    *fired |= 1u << HK_SAVE;
  if (down(LOADKEY))
    *fired |= 1u << HK_LOAD;
  return on;
}

/* ---- files ---- */
static bool exists(const char *p) { return access(p, F_OK) == 0; }
static unsigned char *read_file(const char *path, size_t *size, size_t max) {
  FILE *f = fopen(path, "rb");
  if (!f)
    return NULL;
  fseek(f, 0, SEEK_END);
  long len = ftell(f);
  rewind(f);
  unsigned char *d = len > 0 && (size_t)len <= max ? malloc((size_t)len) : NULL;
  if (d && fread(d, 1, (size_t)len, f) != (size_t)len) {
    free(d);
    d = NULL;
  }
  fclose(f);
  *size = d ? (size_t)len : 0;
  return d;
}
// Written beside, then renamed over: a crash leaves the old file or the new one, never half of one.
static bool write_file(const char *path, const void *d, size_t n, bool sync) {
  char tmp[PATH_MAX + 8];
  snprintf(tmp, sizeof tmp, "%s.tmp", path);
  FILE *f = fopen(tmp, "wb");
  if (!f)
    return false;
  bool ok = fwrite(d, 1, n, f) == n && fflush(f) == 0 && (!sync || fsync(fileno(f)) == 0);
  ok = fclose(f) == 0 && ok;
  if (ok && rename(tmp, path) == 0)
    return true;
  unlink(tmp);
  return false;
}
/* A copy of the battery save before the game opens, like Android's and as host_io.c's pocket_save_backup names them (the
 * DS path's, and the hosts' before this one): DIR/STEM.backup-YYYYmmdd-HHMMSS.EXT, a new one only when the save data
 * differs from the newest backup's, the newest 10 kept. Every save chip is a multiple of 512 bytes, so what is past the
 * last whole 512 is mGBA's clock footer, which moves every session: a save where only the clock moved is the same save.
 * A save that can't be backed up keeps the game closed. */
static bool same_save(const unsigned char *a, size_t an, const unsigned char *b, size_t bn) {
  size_t n = an - an % 512;
  if (!n || n != bn - bn % 512)
    return an == bn && !memcmp(a, b, an);
  return !memcmp(a, b, n);
}
static int newest_first(const void *a, const void *b) { return -strcmp(*(char *const *)a, *(char *const *)b); }
static bool save_backup(const char *sav, const char *dir) {
  size_t n;
  unsigned char *d = read_file(sav, &n, 64u << 20);
  if (!d)
    return !exists(sav); // nothing to keep yet
  if (!n) {
    free(d);
    return true;
  }
  mkdir(dir, 0755);
  const char *file = strrchr(sav, '/');
  file = file ? file + 1 : sav;
  const char *dot = strrchr(file, '.');
  char stem[512], ext[32] = "sav";
  snprintf(stem, sizeof stem, "%.*s.backup-", dot && dot != file ? (int)(dot - file) : (int)strlen(file), file);
  if (dot && dot != file)
    snprintf(ext, sizeof ext, "%s", dot + 1);
  char *list[256];
  int count = 0;
  DIR *dd = opendir(dir);
  for (struct dirent *e; dd && (e = readdir(dd)) && count < 256;)
    if (!strncmp(e->d_name, stem, strlen(stem)) && !strstr(e->d_name, ".tmp"))
      list[count++] = strdup(e->d_name);
  if (dd)
    closedir(dd);
  qsort(list, count, sizeof *list, newest_first);
  bool write = true, ok = true;
  char path[PATH_MAX * 4 + 64];
  if (count) {
    size_t m;
    snprintf(path, sizeof path, "%s/%s", dir, list[0]);
    unsigned char *last = read_file(path, &m, 64u << 20);
    write = !last || !same_save(last, m, d, n);
    free(last);
  }
  if (write) {
    char stamp[32];
    time_t now = time(NULL);
    strftime(stamp, sizeof stamp, "%Y%m%d-%H%M%S", localtime(&now));
    snprintf(path, sizeof path, "%s/%s%s.%s", dir, stem, stamp, ext);
    ok = write_file(path, d, n, true);
  }
  // The newest 10 stay (a new one counts as the newest).
  for (int i = ok && write ? 9 : 10; ok && i < count; i++) {
    snprintf(path, sizeof path, "%s/%s", dir, list[i]);
    unlink(path);
  }
  for (int i = 0; i < count; i++)
    free(list[i]);
  free(d);
  return ok;
}

/* ---- the core ---- */
static struct {
  void *dl;
  void (*init)(void);
  void (*deinit)(void);
  void (*set_environment)(retro_environment_t);
  void (*set_video_refresh)(retro_video_refresh_t);
  void (*set_audio_sample)(retro_audio_sample_t);
  void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
  void (*set_input_poll)(retro_input_poll_t);
  void (*set_input_state)(retro_input_state_t);
  void (*get_system_info)(struct retro_system_info *);
  void (*get_system_av_info)(struct retro_system_av_info *);
  bool (*load_game)(const struct retro_game_info *);
  void (*unload_game)(void);
  void (*run)(void);
  size_t (*serialize_size)(void);
  bool (*serialize)(void *, size_t);
  bool (*unserialize)(const void *, size_t);
  void *(*get_memory_data)(unsigned);
  size_t (*get_memory_size)(unsigned);
} core;
static bool core_open(const char *path) {
  core.dl = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!core.dl) {
    fprintf(stderr, "%s\n", dlerror());
    return false;
  }
#define SYM(field, name)                                                                                       \
  if (!(*(void **)&core.field = dlsym(core.dl, name))) {                                                       \
    fprintf(stderr, "%s: no %s\n", path, name);                                                                \
    dlclose(core.dl);                                                                                          \
    core.dl = NULL;                                                                                            \
    return false;                                                                                              \
  }
  SYM(init, "retro_init")
  SYM(deinit, "retro_deinit")
  SYM(set_environment, "retro_set_environment")
  SYM(set_video_refresh, "retro_set_video_refresh")
  SYM(set_audio_sample, "retro_set_audio_sample")
  SYM(set_audio_sample_batch, "retro_set_audio_sample_batch")
  SYM(set_input_poll, "retro_set_input_poll")
  SYM(set_input_state, "retro_set_input_state")
  SYM(get_system_info, "retro_get_system_info")
  SYM(get_system_av_info, "retro_get_system_av_info")
  SYM(load_game, "retro_load_game")
  SYM(unload_game, "retro_unload_game")
  SYM(run, "retro_run")
  SYM(serialize_size, "retro_serialize_size")
  SYM(serialize, "retro_serialize")
  SYM(unserialize, "retro_unserialize")
  SYM(get_memory_data, "retro_get_memory_data")
  SYM(get_memory_size, "retro_get_memory_size")
#undef SYM
  return true;
}

/* The core's options: their defaults as it declares them, and the panel this host has. */
static struct {
  const char *key, *value;
  char legacyDefault[128];
} options[160];
static int optionN;
static char panelValue[32];
static bool optionsChanged;
static void options_from(const struct retro_core_option_v2_definition *d) {
  for (optionN = 0; d && d->key && optionN < (int)(sizeof options / sizeof options[0]); d++, optionN++) {
    options[optionN].key = d->key;
    options[optionN].value = d->default_value;
  }
}
static void options_from_v1(const struct retro_core_option_definition *d) {
  for (optionN = 0; d && d->key && optionN < (int)(sizeof options / sizeof options[0]); d++, optionN++) {
    options[optionN].key = d->key;
    options[optionN].value = d->default_value;
  }
}

static void options_from_variables(const struct retro_variable *v) {
  for (optionN = 0; v && v->key && optionN < (int)(sizeof options / sizeof options[0]); v++) {
    const char *first = v->value ? strchr(v->value, ';') : NULL;
    if (!first)
      continue;
    first++;
    first += strspn(first, " ");
    size_t len = strcspn(first, "|");
    if (len >= sizeof options[0].legacyDefault)
      continue;
    options[optionN].key = v->key;
    memcpy(options[optionN].legacyDefault, first, len);
    options[optionN].legacyDefault[len] = '\0';
    options[optionN].value = options[optionN].legacyDefault;
    optionN++;
  }
}

static enum retro_pixel_format pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
static struct retro_system_av_info av;
static SDL_Texture *frameTexture;
static int textureW, textureH, frameW, frameH;
static enum retro_pixel_format textureFormat; // what frameTexture was made for: a core of another format needs a new one
static double srcRate = 32768, rateTrim = 1;   // the core's audio rate (SET_SYSTEM_AV_INFO can change it), the pacing's trim
static bool ffNow, rewindingNow;
static int ffSpeedNow = 1;
static void core_log(enum retro_log_level level, const char *fmt, ...) {
  if (level < RETRO_LOG_WARN && !getenv("POCKET_CORE_LOG"))
    return;
  va_list ap;
  va_start(ap, fmt);
  vfprintf(stderr, fmt, ap);
  va_end(ap);
}
static void frame_texture(void) {
  int w = (int)av.geometry.max_width, h = (int)av.geometry.max_height;
  if (frameTexture && w <= textureW && h <= textureH && pixelFormat == textureFormat)
    return;
  if (frameTexture)
    SDL_DestroyTexture(frameTexture);
  frameTexture = SDL_CreateTexture(renderer,
                                   pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888 ? SDL_PIXELFORMAT_ARGB8888
                                   : pixelFormat == RETRO_PIXEL_FORMAT_RGB565 ? SDL_PIXELFORMAT_RGB565
                                                                              : SDL_PIXELFORMAT_ARGB1555,
                                   SDL_TEXTUREACCESS_STREAMING, w, h);
  textureW = w;
  textureH = h;
  textureFormat = pixelFormat;
  if (frameTexture)
    SDL_SetTextureBlendMode(frameTexture, SDL_BLENDMODE_NONE);
  else
    fprintf(stderr, "Texture: %s\n", SDL_GetError());
}
static bool environment(unsigned cmd, void *data) {
  switch (cmd) {
  case RETRO_ENVIRONMENT_GET_VARIABLE: {
    struct retro_variable *v = data;
    v->value = NULL;
    if (!strcmp(v->key, "pokedaisy_ds_select_modifier"))
      v->value = hostSet.modifier == SELECT ? "enabled" : "disabled";
    if (!strcmp(v->key, "pokedaisy_panel"))
      v->value = panelValue;
    for (int i = 0; i < optionN && !v->value; i++)
      if (!strcmp(options[i].key, v->key))
        v->value = options[i].value;
    return v->value != NULL;
  }
  case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
    *(bool *)data = optionsChanged;
    optionsChanged = false;
    return true;
  case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
    *(unsigned *)data = 2;
    return true;
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
    options_from(data ? ((const struct retro_core_options_v2 *)data)->definitions : NULL);
    return true;
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
    const struct retro_core_options_v2_intl *i = data;
    options_from(i && i->us ? i->us->definitions : NULL);
    return true;
  }
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
    options_from_v1(data);
    return true;
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
    options_from_v1(data ? ((const struct retro_core_options_intl *)data)->us : NULL);
    return true;
  case RETRO_ENVIRONMENT_SET_VARIABLES:
    options_from_variables(data);
    return true;
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
  case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
  case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
    return true;
  case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
    enum retro_pixel_format f = *(const enum retro_pixel_format *)data;
    if (f != RETRO_PIXEL_FORMAT_XRGB8888 && f != RETRO_PIXEL_FORMAT_RGB565 && f != RETRO_PIXEL_FORMAT_0RGB1555)
      return false;
    pixelFormat = f;
    return true;
  }
  case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
    *(const char **)data = savesDir;
    return true;
  case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    *(const char **)data = root;
    return true;
  case RETRO_ENVIRONMENT_SET_GEOMETRY:
    av.geometry = *(const struct retro_game_geometry *)data;
    if (renderer)
      frame_texture();
    return true;
  case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
    av = *(const struct retro_system_av_info *)data;
    if (av.timing.sample_rate > 0)
      srcRate = av.timing.sample_rate; // the audio resampler follows the core's new rate
    if (renderer)
      frame_texture();
    return true;
  case RETRO_ENVIRONMENT_GET_CAN_DUPE:
    *(bool *)data = true;
    return true;
  case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
    return true;
  case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
    ((struct retro_log_callback *)data)->log = core_log;
    return true;
  case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
    if (data)
      *(int *)data = 3;
    return true;
  case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
    *(bool *)data = ffNow;
    return true;
  case RETRO_ENVIRONMENT_GET_THROTTLE_STATE: {
    struct retro_throttle_state *t = data;
    t->mode = rewindingNow ? RETRO_THROTTLE_REWINDING
              : ffNow      ? RETRO_THROTTLE_FAST_FORWARD
                           : RETRO_THROTTLE_VSYNC;
    t->rate = (float)(av.timing.fps * (ffNow ? ffSpeedNow : 1));
    return true;
  }
  default:
    return false;
  }
}

/* ---- video ---- */
static void video_refresh(const void *data, unsigned w, unsigned h, size_t pitch) {
  if (!data || !frameTexture) // a dupe: the last frame stays
    return;
  SDL_Rect r = {0, 0, (int)w, (int)h};
  SDL_UpdateTexture(frameTexture, &r, data, (int)pitch);
  frameW = (int)w;
  frameH = (int)h;
}
/* The frame at the largest whole scale the screen holds (the core sized it for this screen), centred; a screen
 * smaller than the frame gets it shrunk to fit. */
static SDL_Rect frame_place(int ow, int oh) {
  SDL_Rect r = {0, 0, ow, oh};
  if (frameW <= 0 || frameH <= 0)
    return r;
  int s = ow / frameW < oh / frameH ? ow / frameW : oh / frameH;
  if (s >= 1) {
    r.w = frameW * s;
    r.h = frameH * s;
  } else if ((long)ow * frameH < (long)oh * frameW) {
    r.w = ow;
    r.h = ow * frameH / frameW;
  } else {
    r.h = oh;
    r.w = oh * frameW / frameH;
  }
  r.x = (ow - r.w) / 2;
  r.y = (oh - r.h) / 2;
  return r;
}

/* The overlay (notices, the host menu, the game list): a canvas at the largest whole scale that keeps it at least
 * 320x240 (as the companion's), KEY_COLOR see-through. */
static uint32_t overlayPx[OVERLAY_MAX_W * OVERLAY_MAX_H];
static struct pd_canvas overlay;
static SDL_Texture *overlayTexture;
static int overlayTexW, overlayTexH;
static void overlay_begin(void) {
  int ow = W, oh = H;
  SDL_GetRendererOutputSize(renderer, &ow, &oh);
  int s = ow / 320 < oh / 240 ? ow / 320 : oh / 240;
  if (s < 1)
    s = 1;
  int w = ow / s, h = oh / s;
  w = w > OVERLAY_MAX_W ? OVERLAY_MAX_W : w;
  h = h > OVERLAY_MAX_H ? OVERLAY_MAX_H : h;
  pd_canvas_init(&overlay, w, h, overlayPx);
  pd_fill(&overlay, 0, 0, w, h, KEY_COLOR);
}
static void overlay_draw(bool opaque) {
  if (!overlayTexture || overlayTexW != overlay.w || overlayTexH != overlay.h) {
    if (overlayTexture)
      SDL_DestroyTexture(overlayTexture);
    overlayTexture =
        SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888, SDL_TEXTUREACCESS_STREAMING, overlay.w, overlay.h);
    overlayTexW = overlay.w;
    overlayTexH = overlay.h;
    if (!overlayTexture)
      return;
    SDL_SetTextureBlendMode(overlayTexture, SDL_BLENDMODE_BLEND);
  }
  int n = overlay.w * overlay.h;
  for (int i = 0; i < n; i++) {
    uint32_t p = overlayPx[i] & 0xFFFFFF;
    overlayPx[i] = p == KEY_COLOR && !opaque ? 0 : 0xFF000000u | p;
  }
  SDL_UpdateTexture(overlayTexture, NULL, overlayPx, overlay.w * 4);
  int ow = W, oh = H;
  SDL_GetRendererOutputSize(renderer, &ow, &oh);
  int s = ow / overlay.w < oh / overlay.h ? ow / overlay.w : oh / overlay.h;
  if (s < 1)
    s = 1;
  SDL_Rect r = {(ow - overlay.w * s) / 2, (oh - overlay.h * s) / 2, overlay.w * s, overlay.h * s};
  SDL_RenderCopy(renderer, overlayTexture, NULL, &r);
}
static bool overlay_notice(void) {
  if (SDL_GetTicks() >= messageUntil)
    return false;
  pd_fill(&overlay, 4, overlay.h - 30, overlay.w - 8, 24, 0x181818);
  pd_text_fit(&overlay, 10, overlay.h - 26, overlay.w - 20, message, 0xffffff, PD_NO_SHADOW);
  return true;
}

/* LCD (the host menu's LCD FILTER): Keegan Whitacre's output-space aperture mask (pokedaisy-brick dfc62a0),
 * multiplied over the whole presented frame. */
static SDL_Texture *lcdTexture;
static int lcdW, lcdH;
static void render_lcd(void) {
  if (!hostSet.lcd)
    return;
  int w, h;
  if (SDL_GetRendererOutputSize(renderer, &w, &h) < 0 || w <= 0 || h <= 0)
    return;
  if (!lcdTexture || lcdW != w || lcdH != h) {
    SDL_DestroyTexture(lcdTexture);
    lcdTexture = NULL;
    uint32_t *mask = malloc((size_t)w * h * 4);
    if (!mask)
      return;
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++)
        mask[y * w + x] = pocket_lcd_pixel(x, y);
    lcdTexture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888, SDL_TEXTUREACCESS_STATIC, w, h);
    if (lcdTexture && (SDL_UpdateTexture(lcdTexture, NULL, mask, w * 4) < 0 ||
                       SDL_SetTextureBlendMode(lcdTexture, SDL_BLENDMODE_MOD) < 0)) {
      SDL_DestroyTexture(lcdTexture);
      lcdTexture = NULL;
    }
    free(mask);
    lcdW = w;
    lcdH = h;
  }
  if (lcdTexture)
    SDL_RenderCopy(renderer, lcdTexture, NULL, NULL);
}
static double presentMs;
static void present_game(const struct pd_menu *menu) {
  int ow = W, oh = H;
  SDL_GetRendererOutputSize(renderer, &ow, &oh);
  SDL_RenderClear(renderer);
  frameRect = frame_place(ow, oh);
  if (frameTexture && frameW > 0) {
    SDL_Rect src = {0, 0, frameW, frameH};
    SDL_RenderCopy(renderer, frameTexture, &src, &frameRect);
  }
  render_lcd();
  bool any = false;
  overlay_begin();
  if (menu) {
    pd_menu_draw((struct pd_menu *)menu, &overlay);
    any = true;
  }
  any |= overlay_notice();
  if (any)
    overlay_draw(false);
  uint64_t t = SDL_GetPerformanceCounter();
  SDL_RenderPresent(renderer);
  presentMs += (double)(SDL_GetPerformanceCounter() - t) * 1000.0 / SDL_GetPerformanceFrequency();
}
static bool capture(const char *path) {
  int w = W, h = H;
  SDL_GetRendererOutputSize(renderer, &w, &h);
  uint32_t *px = malloc((size_t)w * h * 4);
  bool ok = px && SDL_RenderReadPixels(renderer, NULL, SDL_PIXELFORMAT_ARGB8888, px, w * 4) == 0;
  for (int i = 0; ok && i < w * h; i++)
    px[i] &= 0xFFFFFF;
  ok = ok && pd_png_write(path, w, h, px);
  free(px);
  return ok;
}

/* ---- audio: the core's samples resampled to the device, a little faster or slower to keep its queue near 60 ms
 * (the screen paces the game, not the sound card); fast-forward keeps every Nth sample, so it plays sped up. ---- */
static double resamplePos;
static int16_t prevL, prevR;
static unsigned audioSkip, audioKeepEvery = 1;
static bool muted;
static void audio_open(void) {
  SDL_AudioSpec want = {0};
  want.freq = OUT_RATE;
  want.format = AUDIO_S16SYS;
  want.channels = 2;
  want.samples = 512;
  audio = SDL_OpenAudioDevice(NULL, 0, &want, NULL, 0);
  if (audio)
    SDL_PauseAudioDevice(audio, 0);
  else
    fprintf(stderr, "Audio unavailable: %s\n", SDL_GetError());
}
/* A handheld's ALSA device can go bad under the app (the screen turned off and on: "ALSA write failed
 * (unrecoverable)"), and SDL then closes it for good; it's reopened as soon as SDL says so, or within a second of it
 * no longer playing. */
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
#ifdef POCKET_TEST_AUDIO
static unsigned long audioNonzero;
#endif
static size_t audio_batch(const int16_t *in, size_t frames) {
  static int16_t out[4096 * 2];
  if (!audio || muted)
    return frames;
  size_t n = 0;
  double step = srcRate * rateTrim / OUT_RATE;
  const bool room = SDL_GetQueuedAudioSize(audio) < OUT_RATE * 4 / 5; // never more than 200 ms behind
  for (size_t i = 0; i < frames; i++) {
    if (audioSkip++ % audioKeepEvery)
      continue;
    int16_t l = in[2 * i], r = in[2 * i + 1];
#ifdef POCKET_TEST_AUDIO
    audioNonzero += l || r;
#endif
    while (resamplePos < 1.0) {
      if (n == 4096) { // a batch longer than the buffer: send what's made and go on (never skip ahead in the input)
        if (room)
          SDL_QueueAudio(audio, out, (Uint32)(n * 4));
        n = 0;
      }
      out[2 * n] = (int16_t)(prevL + (l - prevL) * resamplePos);
      out[2 * n + 1] = (int16_t)(prevR + (r - prevR) * resamplePos);
      n++;
      resamplePos += step;
    }
    resamplePos -= 1.0;
    prevL = l;
    prevR = r;
  }
  if (n && room)
    SDL_QueueAudio(audio, out, (Uint32)(n * 4));
  return frames;
}
static void audio_sample(int16_t l, int16_t r) {
  int16_t s[2] = {l, r};
  audio_batch(s, 1);
}
static void audio_trim(double gameHz, double paceHz) {
  double rate = paceHz > 0 && gameHz > 0 ? paceHz / gameHz : 1;
  if (audio) {
    double queued = SDL_GetQueuedAudioSize(audio) / 4.0, target = OUT_RATE * 0.06;
    double d = (target - queued) / target;
    d = d > 1 ? 1 : d < -1 ? -1 : d;
    rate *= 1 - 0.005 * d; // short of the target: a little more output per input sample
  }
  rateTrim = rate;
}

/* ---- input to the core ---- */
static unsigned gameKeys; // this frame's joypad, in RETRO_DEVICE_ID_JOYPAD bits
static void input_poll(void) {}
static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
  (void)index;
  if (port)
    return 0;
  if (device == RETRO_DEVICE_JOYPAD)
    return id == RETRO_DEVICE_ID_JOYPAD_MASK ? (int16_t)gameKeys : (int16_t)((gameKeys >> id) & 1);
  if (device == RETRO_DEVICE_POINTER)
    return id == RETRO_DEVICE_ID_POINTER_X         ? (int16_t)pointerX
           : id == RETRO_DEVICE_ID_POINTER_Y       ? (int16_t)pointerY
           : id == RETRO_DEVICE_ID_POINTER_PRESSED ? pointerDown
                                                   : 0;
  return 0;
}
static unsigned joypad(unsigned keys) {
  static const struct {
    int key;
    unsigned id;
  } map[] = {{A, RETRO_DEVICE_ID_JOYPAD_A},           {B, RETRO_DEVICE_ID_JOYPAD_B},
             {SELECT, RETRO_DEVICE_ID_JOYPAD_SELECT}, {START, RETRO_DEVICE_ID_JOYPAD_START},
             {RIGHT, RETRO_DEVICE_ID_JOYPAD_RIGHT},   {LEFT, RETRO_DEVICE_ID_JOYPAD_LEFT},
             {UP, RETRO_DEVICE_ID_JOYPAD_UP},         {DOWN, RETRO_DEVICE_ID_JOYPAD_DOWN},
             {R, RETRO_DEVICE_ID_JOYPAD_R},           {L, RETRO_DEVICE_ID_JOYPAD_L},
             {X, RETRO_DEVICE_ID_JOYPAD_X},           {Y, RETRO_DEVICE_ID_JOYPAD_Y},
             {L2, RETRO_DEVICE_ID_JOYPAD_L2},         {R2, RETRO_DEVICE_ID_JOYPAD_R2},
             {L3, RETRO_DEVICE_ID_JOYPAD_L3},         {R3, RETRO_DEVICE_ID_JOYPAD_R3}};
  unsigned bits = 0;
  for (size_t i = 0; i < sizeof map / sizeof map[0]; i++)
    if (keys & (1u << map[i].key))
      bits |= 1u << map[i].id;
  return bits;
}

/* ---- rewind: a state every 2 frames, kept as the newest whole state plus the differences back from it (XOR,
 * zero runs packed), within a memory budget (POCKET_REWIND_MB, default 32): about 20 s on a GBA game. ---- */
#define REWIND_CAP 4096
static unsigned char *rewindNow, *rewindTmp;
static size_t rewindSize, rewindBytes, rewindBudget;
static struct {
  unsigned char *d;
  size_t n;
} rewindRing[REWIND_CAP];
static int rewindHead, rewindCount, rewindTick;
static void rewind_clear(void) {
  for (int i = 0; i < rewindCount; i++)
    free(rewindRing[(rewindHead + i) % REWIND_CAP].d);
  rewindHead = rewindCount = 0;
  rewindBytes = 0;
  free(rewindNow);
  free(rewindTmp);
  rewindNow = rewindTmp = NULL;
  rewindSize = 0;
}
// a ^ b packed: (u32 zeros, u32 literal count, literal bytes)...
static size_t delta_pack(const unsigned char *a, const unsigned char *b, size_t n, unsigned char *out) {
  size_t o = 0, i = 0;
  while (i < n) {
    size_t z = i;
    while (z < n && a[z] == b[z])
      z++;
    size_t lit = z;
    while (lit < n && (a[lit] != b[lit] || (lit + 1 < n && a[lit + 1] != b[lit + 1])))
      lit++;
    uint32_t zeros = (uint32_t)(z - i), count = (uint32_t)(lit - z);
    memcpy(out + o, &zeros, 4);
    memcpy(out + o + 4, &count, 4);
    o += 8;
    for (size_t k = z; k < lit; k++)
      out[o++] = a[k] ^ b[k];
    i = lit;
  }
  return o;
}
static void delta_apply(unsigned char *s, const unsigned char *d, size_t dn) {
  size_t i = 0, o = 0;
  while (o + 8 <= dn) {
    uint32_t zeros, count;
    memcpy(&zeros, d + o, 4);
    memcpy(&count, d + o + 4, 4);
    o += 8;
    i += zeros;
    for (uint32_t k = 0; k < count; k++)
      s[i++] ^= d[o++];
  }
}
static void rewind_push(void) {
  size_t size = core.serialize_size();
  if (!size)
    return;
  if (size != rewindSize) {
    rewind_clear();
    rewindNow = malloc(size);
    rewindTmp = malloc(size * 3 + 64); // the worst packing: 9 bytes per 3 (1 literal + its header)
    if (!rewindNow || !rewindTmp || !core.serialize(rewindNow, size)) {
      rewind_clear();
      return;
    }
    rewindSize = size;
    return;
  }
  static unsigned char *next;
  static size_t nextSize;
  if (nextSize != size) {
    free(next);
    next = malloc(size);
    nextSize = next ? size : 0;
  }
  if (!next || !core.serialize(next, size))
    return;
  size_t dn = delta_pack(rewindNow, next, size, rewindTmp);
  unsigned char *d = malloc(dn);
  if (!d)
    return;
  memcpy(d, rewindTmp, dn);
  while (rewindCount && (rewindCount == REWIND_CAP || rewindBytes + dn > rewindBudget)) {
    free(rewindRing[rewindHead].d);
    rewindBytes -= rewindRing[rewindHead].n;
    rewindHead = (rewindHead + 1) % REWIND_CAP;
    rewindCount--;
  }
  int at = (rewindHead + rewindCount++) % REWIND_CAP;
  rewindRing[at].d = d;
  rewindRing[at].n = dn;
  rewindBytes += dn;
  memcpy(rewindNow, next, size);
}
// One state back: false when there's none left.
static bool rewind_pop(void) {
  if (!rewindCount || !rewindNow)
    return false;
  int at = (rewindHead + --rewindCount) % REWIND_CAP;
  delta_apply(rewindNow, rewindRing[at].d, rewindRing[at].n);
  rewindBytes -= rewindRing[at].n;
  free(rewindRing[at].d);
  return core.unserialize(rewindNow, rewindSize);
}

/* ---- pacing: the screen's refresh when it's the game's (58-62 Hz and presents wait for it), else the timer ---- */
static bool vsyncPaced;
static int screenHz;
static void pacing_start(double gameHz) {
  SDL_DisplayMode mode = {0};
  screenHz = SDL_GetCurrentDisplayMode(0, &mode) == 0 ? mode.refresh_rate : 0;
  SDL_RendererInfo info;
  vsyncPaced = !getenv("POCKET_NO_VSYNC") && screenHz >= 58 && screenHz <= 62 &&
               SDL_GetRendererInfo(renderer, &info) == 0 && (info.flags & SDL_RENDERER_PRESENTVSYNC) && gameHz > 0;
}
// A refresh-paced loop whose presents don't wait (a driver ignoring vsync) goes back to the timer.
static void pacing_check(double frameMs) {
  static double sum;
  static int count;
  if (!vsyncPaced)
    return;
  sum += frameMs;
  if (++count < 120)
    return;
  if (sum / count < 12.0) {
    vsyncPaced = false;
    fprintf(stderr, "Presents don't wait for the refresh (%.1f ms apart): pacing by the timer\n", sum / count);
  }
  sum = 0;
  count = 0;
}
/* POCKET_PERF=1: every 5 s, how the frames went. */
static double emuMs;
static void perf_frame(uint64_t start, double hz) {
  static int on = -1, n, late;
  static double sum, worst, presents, emus;
  static uint64_t windowStart;
  if (on < 0)
    on = getenv("POCKET_PERF") != NULL;
  if (!on)
    return;
  uint64_t end = SDL_GetPerformanceCounter(), f = SDL_GetPerformanceFrequency();
  double work = (double)(end - start) * 1000.0 / f;
  if (!windowStart)
    windowStart = end;
  n++;
  sum += work;
  presents += presentMs;
  emus += emuMs;
  presentMs = emuMs = 0;
  worst = work > worst ? work : worst;
  late += work > 1000.0 / hz;
  if (end - windowStart >= 5 * f) {
    fprintf(stderr,
            "perf: %d frames in %.1f s, core %.2f ms, work %.2f ms avg (%.2f in present) / %.2f worst, %d late, "
            "screen %d Hz, %s, audio queued %u B\n",
            n, (double)(end - windowStart) / f, emus / n, sum / n, presents / n, worst, late, screenHz,
            vsyncPaced ? "paced by the screen" : "paced by the timer", audio ? SDL_GetQueuedAudioSize(audio) : 0);
    n = late = 0;
    sum = worst = presents = emus = 0;
    windowStart = end;
  }
}

/* ---- a game ---- */
static void panel_follow(void) {
  int ow = W, oh = H;
  SDL_GetRendererOutputSize(renderer, &ow, &oh);
  char v[32];
  snprintf(v, sizeof v, "%dx%d", ow, oh);
  if (strcmp(v, panelValue)) {
    snprintf(panelValue, sizeof panelValue, "%s", v);
    optionsChanged = true;
  }
}
static bool state_file(const char *ss, bool save) {
  if (save) {
    size_t n = core.serialize_size();
    void *d = n ? malloc(n) : NULL;
    bool ok = d && core.serialize(d, n) && write_file(ss, d, n, false);
    free(d);
    return ok;
  }
  size_t n;
  unsigned char *d = read_file(ss, &n, 64u << 20);
  bool ok = d && core.unserialize(d, n);
  free(d);
  if (ok)
    rewind_clear(); // its differences lead back from another history
  return ok;
}
/* The .sav: the core's save memory, then its cartridge clock (RETRO_MEMORY_RTC: a Game Boy MBC3's, Crystal's) right
 * after it - mGBA's own layout, which the old host wrote too. NULL out: the size. */
static size_t save_image(unsigned char *out) {
  void *mem = core.get_memory_data(RETRO_MEMORY_SAVE_RAM), *rtc = core.get_memory_data(RETRO_MEMORY_RTC);
  size_t n = mem ? core.get_memory_size(RETRO_MEMORY_SAVE_RAM) : 0, rn = rtc ? core.get_memory_size(RETRO_MEMORY_RTC) : 0;
  if (out && n)
    memcpy(out, mem, n);
  if (out && n && rn)
    memcpy(out + n, rtc, rn);
  return n ? n + rn : 0;
}
// The file's bytes into the core: the save memory, and what follows it into the clock (a GBA .sav's 16-byte clock
// footer from mGBA finds no clock memory on armGBA, so a dead battery recorded there never comes back).
static void save_load(const unsigned char *d, size_t fn) {
  void *mem = core.get_memory_data(RETRO_MEMORY_SAVE_RAM), *rtc = core.get_memory_data(RETRO_MEMORY_RTC);
  size_t n = mem ? core.get_memory_size(RETRO_MEMORY_SAVE_RAM) : 0, rn = rtc ? core.get_memory_size(RETRO_MEMORY_RTC) : 0;
  if (!n)
    return;
  memcpy(mem, d, fn < n ? fn : n);
  if (rn && fn > n)
    memcpy(rtc, d + n, fn - n < rn ? fn - n : rn);
}
struct save_baseline {
  unsigned char *data;
  size_t size;
};
static void sram_write(const char *sav, struct save_baseline *copy, bool sync) {
  static unsigned char *now;
  static size_t nowSize;
  size_t n = save_image(NULL);
  if (!n)
    return;
  if (n > nowSize) {
    free(now);
    now = malloc(n);
    nowSize = now ? n : 0;
    if (!now)
      return;
  }
  save_image(now);
  if (n == copy->size && !memcmp(now, copy->data, n))
    return;
  if (!write_file(sav, now, n, sync)) {
    notice("Save write failed");
    return;
  }
  unsigned char *previous = copy->data;
  size_t previousSize = copy->size;
  copy->data = now;
  copy->size = n;
  now = previous;
  nowSize = previousSize;
}
// The settings the old host kept in DATA/settings.ini become the core's (SAVES/pokedaisy/settings.ini), once.
static void settings_migrate(void) {
  char from[PATH_MAX + 16], dir[PATH_MAX + 16], to[PATH_MAX + 32];
  snprintf(from, sizeof from, "%s/settings.ini", root);
  snprintf(dir, sizeof dir, "%s/pokedaisy", savesDir);
  snprintf(to, sizeof to, "%s/settings.ini", dir);
  size_t n;
  unsigned char *d = exists(to) ? NULL : read_file(from, &n, 1u << 20);
  if (!d)
    return;
  mkdir(dir, 0755);
  if (write_file(to, d, n, false))
    fprintf(stderr, "Settings moved from %s to %s\n", from, to);
  free(d);
}
static const char *core_for(const char *path) {
  const char *ext = strrchr(path, '.');
  if (ext && !strcasecmp(ext, ".gba"))
    return "pokedaisy_gba_libretro.so";
  if (ext && (!strcasecmp(ext, ".gb") || !strcasecmp(ext, ".gbc")))
    return "pokedaisy_gbc_libretro.so";
  if (ext && !strcasecmp(ext, ".nds"))
    return "pokedaisy_ds_libretro.so";
  return NULL;
}
#ifdef POCKET_DSPERATE
extern int pd_ds_run(const char *, const char *, int, const char *, SDL_Renderer *, bool (*)(void));
static bool ds_running(void) { return running != 0; }
#endif
// The host menu's rows, top to bottom (fast-forward is the FAST FORWARD hotkey's, held).
enum { M_RESUME, M_SAVE, M_LOAD, M_HOTKEYS, M_REWIND, M_FFSPEED, M_LCD, M_SWAP, M_QUIT, M_COUNT };
static void run_game(const char *path, int maxFrames, const char *shot, bool fromList) {
#ifdef POCKET_DSPERATE
  const char *ext = strrchr(path, '.');
  if (ext && !strcasecmp(ext, ".nds")) {
    if (audio) {
      SDL_CloseAudioDevice(audio);
      audio = 0;
    }
    appError = pd_ds_run(path, root, maxFrames, shot, renderer, ds_running);
    // The DS backend owns its device while running; the GB / GBA games get theirs back.
    audio_open();
    audioLost = !audio;
    held = pressed = 0;
    return;
  }
#endif
  const char *coreName = core_for(path);
  char corePath[PATH_MAX * 2];
  const char *dir = getenv("POCKET_CORE_DIR") ? getenv("POCKET_CORE_DIR") : exeDir;
  snprintf(corePath, sizeof corePath, "%s/%s", dir, coreName ? coreName : "");
  if (!coreName || !core_open(corePath)) {
    appError = 1;
    notice(coreName ? "Core missing: put it beside pokedaisy-linux" : "Unsupported ROM");
    return;
  }
  struct retro_system_info system = {0};
  core.get_system_info(&system);
  size_t romSize = 0;
  unsigned char *rom = system.need_fullpath ? NULL : read_file(path, &romSize, 64u << 20);
  if (!system.need_fullpath && (!rom || romSize < 0x150)) {
    free(rom);
    dlclose(core.dl);
    appError = 1;
    notice("Cannot read ROM");
    return;
  }
  const char *name = strrchr(path, '/');
  name = name ? name + 1 : path;
  char sav[PATH_MAX * 2], ss[PATH_MAX * 2], backups[PATH_MAX * 2];
  if (saveFile) {
    snprintf(sav, sizeof sav, "%s", saveFile);
    snprintf(backups, sizeof backups, "%s/backups", root);
  } else {
    snprintf(sav, sizeof sav, "%s/%s.sav", savesDir, name);
    snprintf(backups, sizeof backups, "%s/pokedaisy-backups", savesDir);
  }
  // DS uses L2 for the Companion and R2 for screen switching. Keep the host's DS bindings separate.
  const bool dsGame = !strcmp(coreName, "pokedaisy_ds_libretro.so");
  int *hotkeys = dsGame ? hostSet.dsHotkey : hostSet.hotkey;
  // armGBA's states aren't mGBA's: each core its own file.
  snprintf(ss, sizeof ss, "%s/states/%s%s.ss", root, name, strstr(coreName, "_gba_") ? ".armgba" : "");
  if (strstr(coreName, "_gba_")) {
    // A GBA state from before armGBA is mGBA's: it can't load here, so say so instead of "No state saved yet".
    char old[PATH_MAX * 2 + 16];
    snprintf(old, sizeof old, "%s/states/%s.ss", root, name);
    if (exists(old) && !exists(ss))
      notice("Your older save state doesn't load in this version");
  }
  if (!save_backup(sav, backups)) {
    free(rom);
    dlclose(core.dl);
    appError = 1;
    notice("Save backup failed; game not opened");
    return;
  }
  settings_migrate();
  optionN = 0;
  pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
  panel_follow();
  optionsChanged = false;
  core.set_environment(environment);
  core.init();
  core.set_video_refresh(video_refresh);
  core.set_audio_sample(audio_sample);
  core.set_audio_sample_batch(audio_batch);
  core.set_input_poll(input_poll);
  core.set_input_state(input_state);
  struct retro_game_info info = {path, rom, romSize, NULL};
  if (!core.load_game(&info)) {
    core.deinit();
    dlclose(core.dl);
    free(rom);
    appError = 1;
    notice("ROM load failed");
    return;
  }
  core.get_system_av_info(&av);
  srcRate = av.timing.sample_rate > 0 ? av.timing.sample_rate : 32768;
  double hz = av.timing.fps > 0 ? av.timing.fps : 59.7275;
  frameW = frameH = 0;
  frame_texture();
  // The battery save: the file's bytes into the core's, and written back whenever they change.
  struct save_baseline sramCopy = {0};
  {
    size_t n = save_image(NULL), fn;
    unsigned char *d = n ? read_file(sav, &fn, 64u << 20) : NULL;
    if (d)
      save_load(d, fn);
    free(d);
    sramCopy.data = n ? malloc(n) : NULL;
    if (sramCopy.data)
      sramCopy.size = save_image(sramCopy.data);
  }
  int rewindMB = getenv("POCKET_REWIND_MB") ? atoi(getenv("POCKET_REWIND_MB")) : 32;
  rewindBudget = (size_t)(rewindMB > 0 ? rewindMB : 32) << 20; // a bad value (negative, 0) is the default
  rewindTick = 0;
  bool menuOpen = false, leave = false;
  struct pd_menu menu, hkMenu;
  static char rows[M_COUNT][40], hkRows[HK_ACTIONS + 1][40];
  static const char *rowPtrs[M_COUNT], *hkPtrs[HK_ACTIONS + 1];
  static const char *const hkNames[HK_ACTIONS] = {"FAST FORWARD", "REWIND", "SAVE STATE", "LOAD STATE"};
  bool hkPage = false;
  unsigned latched = 0, menuConsumed = 0;
  bool modifierPending = false, modifierUsed = false;
  int pulseLeft = 0; // frames the modifier's own tap still reaches the game
  int capturing = -1; // the HOTKEYS row waiting for a button
  // LOAD STATE and QUIT ask first, CANCEL on top (as the hosts before this one): a stray A never loses the game's progress.
  struct pd_menu confirmMenu;
  static const char *confirmRows[2];
  int confirming = 0; // the menu row asked about (M_LOAD / M_QUIT), 0 = none
  int frames = 0;
  uint64_t due = SDL_GetPerformanceCounter(), lastStart = 0, lastFrame = due;
  pacing_start(hz);
  while (running && !leave) {
    uint64_t frameStart = SDL_GetPerformanceCounter();
    // Back from a pause (a stopped process, sleep): no catching up.
    if (lastStart && (frameStart - lastStart) * 4 > SDL_GetPerformanceFrequency())
      due = frameStart;
    lastStart = frameStart;
    events();
    audio_check();
    repeat_directions();
    if (outputChanged) {
      outputChanged = false;
      panel_follow();
    }
    const unsigned menuChord = (1u << START) | (1u << SELECT);
    const bool menuChordDown = (held & menuChord) == menuChord;
    bool menuKey = down(MENU) || (comboMenu && menuChordDown && (pressed & menuChord));
    snprintf(rows[M_RESUME], 40, "RESUME");
    snprintf(rows[M_SAVE], 40, "SAVE STATE");
    snprintf(rows[M_LOAD], 40, "LOAD STATE");
    if (hostSet.ffSpeed)
      snprintf(rows[M_FFSPEED], 40, "FF SPEED  %dx", hostSet.ffSpeed);
    else
      snprintf(rows[M_FFSPEED], 40, "FF SPEED  MAX");
    snprintf(rows[M_REWIND], 40, "REWIND  %s", hostSet.rewind ? "ON" : "OFF");
    snprintf(rows[M_LCD], 40, "LCD FILTER  %s", hostSet.lcd ? "ON" : "OFF");
    snprintf(rows[M_SWAP], 40, "A/B BUTTONS  %s", hostSet.swapAB ? "SWAPPED" : "NORMAL");
    snprintf(rows[M_HOTKEYS], 40, "HOTKEYS");
    snprintf(rows[M_QUIT], 40, fromList ? "QUIT TO GAME LIST" : "QUIT");
    for (int i = 0; i < M_COUNT; i++)
      rowPtrs[i] = rows[i];
    for (int i = 0; i < HK_ACTIONS; i++) {
      snprintf(hkRows[i], 40, "%s  %s", hkNames[i], capturing == i ? "..." : hotkey_label(hotkeys[i]));
      hkPtrs[i] = hkRows[i];
    }
    snprintf(hkRows[HK_ACTIONS], 40, "MODIFIER  %s",
             capturing == HK_ACTIONS ? "..." : hotkey_label(hostSet.modifier));
    hkPtrs[HK_ACTIONS] = hkRows[HK_ACTIONS];
    menuConsumed &= held;
    if (inputReset || menuKey || menuChordDown) {
      modifierPending = modifierUsed = false;
      pulseLeft = 0;
      inputReset = false;
      if (menuKey || menuChordDown)
        menuConsumed |= held;
    }
    if (menuKey) {
      menuOpen = !menuOpen;
      hkPage = false;
      confirming = 0;
      capturing = -1;
      if (menuOpen) {
        pd_menu_init(&menu, "POKEDAISY", rowPtrs, M_COUNT);
        menu.overlay = true;
      }
    }
    unsigned hkFired = 0, hkOn = 0;
    hotkeyMask = 0;
    unsigned modifier = hostSet.modifier < 0 ? 0 : 1u << hostSet.modifier;
    latched &= held;
    unsigned modifierPulse = 0;
    if (!menuOpen && !menuKey && !menuChordDown) {
      if (pressed & modifier & ~menuConsumed)
        modifierPending = true;
      if (!(menuConsumed & modifier))
        hkOn = hotkeys_held(hotkeys, modifier, &latched, &hkFired);
      // The DS core handles its stylus shortcuts with Select held.
      const unsigned stylus = (1u << UP) | (1u << DOWN) | (1u << LEFT) | (1u << RIGHT) | (1u << A) | (1u << Y);
      bool dsStylus = dsGame && hostSet.modifier == SELECT && (held & modifier)
                      && (held & stylus & ~latched & ~menuConsumed) && !(latched & hotkeyMask);
      modifierUsed |= !!(latched & hotkeyMask) || dsStylus;
      if (!(held & modifier)) {
        if (modifierPending && !modifierUsed)
          pulseLeft = MODIFIER_TAP_FRAMES;
        modifierPending = modifierUsed = false;
      } else
        pulseLeft = 0; // held again: it's the modifier now, not a tap
      if (pulseLeft > 0)
        modifierPulse = modifier;
      if (!dsStylus)
        hotkeyMask |= modifier;
    } else
      pulseLeft = 0;
    hotkeyMask |= latched | menuConsumed;
    if (hkFired & (1u << HK_SAVE))
      notice(state_file(ss, true) ? "State saved" : "State save failed");
    if (hkFired & (1u << HK_LOAD))
      notice(!exists(ss) ? "No state saved yet" : state_file(ss, false) ? "State loaded" : "State load failed");
    if ((hkFired & (1u << HK_REWIND)) && !hostSet.rewind)
      notice("REWIND is off: turn it on in the menu");
    const bool menuFrame = menuOpen; // the frame that closes the menu is still the menu's
    if (menuOpen && capturing >= 0) {
      // The next assignable button is the hotkey (taken from any other action that had it); B cancels, SELECT = OFF.
      if (down(B))
        capturing = -1;
      else if (down(SELECT)) {
        if (capturing == HK_ACTIONS) {
          hostSet.modifier = hostSet.modifier == SELECT ? -1 : SELECT;
          if (hostSet.modifier >= 0)
            modifier_takes(hostSet.modifier);
          ds_hotkeys_follow_modifier();
        } else
          hotkeys[capturing] = -1;
        host_save();
        capturing = -1;
      } else
        for (int k = 0; k < NKEYS; k++)
          if (down(k) && (hotkey_assignable(k) || (capturing == HK_ACTIONS && k == START))) {
            if (capturing == HK_ACTIONS) {
              hostSet.modifier = k;
              modifier_takes(k);
              ds_hotkeys_follow_modifier();
            } else if (k == hostSet.modifier) {
              notice("That button is the MODIFIER");
              capturing = -1;
              break;
            } else {
              for (int j = 0; j < HK_ACTIONS; j++)
                if (hotkeys[j] == k)
                  hotkeys[j] = -1;
              hotkeys[capturing] = k;
            }
            host_save();
            capturing = -1;
            break;
          }
      hkMenu.title = capturing >= 0 ? "PRESS A BUTTON" : "HOTKEYS";
    } else if (menuOpen && confirming) {
      if (down(UP))
        pd_menu_move(&confirmMenu, -1);
      if (down(DOWN))
        pd_menu_move(&confirmMenu, 1);
      if (down(B))
        confirming = 0;
      if (down(A)) {
        if (confirmMenu.selected == 1 && confirming == M_LOAD) {
          notice(state_file(ss, false) ? "State loaded" : "State load failed");
          menuOpen = false;
        } else if (confirmMenu.selected == 1 && confirming == M_QUIT) {
          leave = true;
        }
        confirming = 0;
      }
    } else if (menuOpen && hkPage) {
      if (down(UP))
        pd_menu_move(&hkMenu, -1);
      if (down(DOWN))
        pd_menu_move(&hkMenu, 1);
      if (down(B))
        hkPage = false;
      if (down(A)) {
        capturing = hkMenu.selected;
        hkMenu.title = "PRESS A BUTTON";
        notice(capturing == HK_ACTIONS ? "SELECT: select / off   B: cancel" : "B: cancel   SELECT: off");
      }
    } else if (menuOpen) {
      if (down(UP))
        pd_menu_move(&menu, -1);
      if (down(DOWN))
        pd_menu_move(&menu, 1);
      if (down(B))
        menuOpen = false;
      if (down(A)) {
        switch (menu.selected) {
        case M_RESUME:
          menuOpen = false;
          break;
        case M_SAVE:
          notice(state_file(ss, true) ? "State saved" : "State save failed");
          menuOpen = false;
          break;
        case M_LOAD:
          if (!exists(ss)) {
            notice("No state saved yet");
            menuOpen = false;
            break;
          }
          confirming = M_LOAD;
          confirmRows[0] = "CANCEL";
          confirmRows[1] = "LOAD STATE";
          pd_menu_init(&confirmMenu, "LOAD THE STATE?", confirmRows, 2);
          confirmMenu.overlay = true;
          break;
        case M_FFSPEED:
          hostSet.ffSpeed = hostSet.ffSpeed == 0 ? 2 : hostSet.ffSpeed >= 8 ? 0 : hostSet.ffSpeed + 1;
          host_save();
          break;
        case M_REWIND:
          hostSet.rewind = !hostSet.rewind;
          if (!hostSet.rewind)
            rewind_clear();
          host_save();
          break;
        case M_LCD:
          hostSet.lcd = !hostSet.lcd;
          host_save();
          break;
        case M_SWAP:
          hostSet.swapAB = swapFace = !hostSet.swapAB;
          held = 0; // the button that chose this is now the other one: let it go
          host_save();
          break;
        case M_HOTKEYS:
          hkPage = true;
          pd_menu_init(&hkMenu, "HOTKEYS", hkPtrs, HK_ACTIONS + 1);
          hkMenu.overlay = true;
          break;
        case M_QUIT:
          confirming = M_QUIT;
          confirmRows[0] = "CANCEL";
          confirmRows[1] = fromList ? "QUIT TO GAME LIST" : "QUIT";
          pd_menu_init(&confirmMenu, "LEAVE THE GAME?", confirmRows, 2);
          confirmMenu.overlay = true;
          break;
        }
      }
    }
    if (menuFrame) {
      muted = true;
      gameKeys = 0;
      present_game(menuOpen ? (confirming ? &confirmMenu : hkPage ? &hkMenu : &menu) : NULL);
      if (maxFrames && ++frames >= maxFrames) { // a scripted run can end, and be captured, with the menu up
        if (shot && !capture(shot))
          appError = 1;
        break;
      }
      if (!vsyncPaced && !maxFrames)
        SDL_Delay(16);
      due = SDL_GetPerformanceCounter();
      continue;
    }
    muted = false;
    bool rewinding = (hkOn & (1u << HK_REWIND)) && hostSet.rewind;
    bool ff = !rewinding && (hkOn & (1u << HK_FF));
    int runs = 1;
    if (ff) {
      double cost = emuMs > 0 ? emuMs : 4;
      runs = hostSet.ffSpeed ? hostSet.ffSpeed : (int)(800.0 / hz / cost);
      runs = runs < 2 ? 2 : runs > 30 ? 30 : runs;
    }
    ffNow = ff;
    ffSpeedNow = runs;
    rewindingNow = rewinding;
    audioKeepEvery = (unsigned)runs;
    gameKeys = joypad((held & ~hotkeyMask & ~((1u << MENU) | (1u << FFKEY) | (1u << RWKEY))) | modifierPulse);
    uint64_t t = SDL_GetPerformanceCounter();
    if (rewinding) {
      muted = true;
      if (rewind_pop())
        core.run();
      else
        rewinding = false;
    } else {
      for (int i = 0; i < runs && running; i++) {
        core.run();
        if (modifierPulse && --pulseLeft <= 0) {
          gameKeys &= ~joypad(modifierPulse);
          modifierPulse = 0;
        }
        if (hostSet.rewind && !(++rewindTick & 1))
          rewind_push();
      }
    }
    emuMs = (double)(SDL_GetPerformanceCounter() - t) * 1000.0 / SDL_GetPerformanceFrequency() / runs;
    if (++frames % 120 == 0)
      sram_write(sav, &sramCopy, true);
    present_game(NULL);
    if (maxFrames && frames >= maxFrames) {
      if (shot && !capture(shot))
        appError = 1;
      break;
    }
    uint64_t now = SDL_GetPerformanceCounter(), f = SDL_GetPerformanceFrequency();
    pacing_check((double)(now - lastFrame) * 1000.0 / f);
    lastFrame = now;
    audio_trim(hz, vsyncPaced ? screenHz : hz);
    if (!vsyncPaced && !maxFrames) {
      due += (uint64_t)(f / hz);
      if (now < due)
        SDL_Delay((Uint32)((due - now) * 1000 / f));
      else if (now - due > f / 10)
        due = now; // far behind (a stall): start again rather than run to catch up
    }
    perf_frame(frameStart, hz);
  }
  sram_write(sav, &sramCopy, true);
  free(sramCopy.data);
  rewind_clear();
  core.unload_game();
  core.deinit();
  dlclose(core.dl);
  memset(&core, 0, sizeof core);
  free(rom);
  if (audio)
    SDL_ClearQueuedAudio(audio);
  muted = false;
  held = pressed = 0;
}

// By the file name, whichever folder it is in.
static const char *base_name(const char *p) {
  const char *s = strrchr(p, '/');
  return s ? s + 1 : p;
}
static int cmp(const void *a, const void *b) { return strcasecmp(base_name(*(char *const *)a), base_name(*(char *const *)b)); }
static void exe_dir(const char *argv0) {
  char p[PATH_MAX];
  ssize_t n = readlink("/proc/self/exe", p, sizeof p - 1);
  if (n > 0)
    p[n] = 0;
  else
    snprintf(p, sizeof p, "%s", argv0);
  char *slash = strrchr(p, '/');
  if (slash)
    *slash = 0;
  else
    snprintf(p, sizeof p, ".");
  snprintf(exeDir, sizeof exeDir, "%s", p);
}
int main(int argc, char **argv) {
  const char *rom = NULL, *shot = NULL;
  int frames = 0;
  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "--data") && i + 1 < argc)
      root = argv[++i];
    else if (!strcmp(argv[i], "--save") && i + 1 < argc)
      saveFile = argv[++i];
    else if (!strcmp(argv[i], "--frames") && i + 1 < argc)
      frames = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--shot") && i + 1 < argc)
      shot = argv[++i];
    else
      rom = argv[i];
  }
  exe_dir(argv[0]);
  signal(SIGINT, stop);
  signal(SIGTERM, stop);
  signal(SIGHUP, stop); // a closed terminal or session: the save goes to the card like a quit
  mkdir(root, 0755);
  char path[PATH_MAX * 2];
  const char *dirs[] = {"roms", "saves", "states"};
  for (int i = 0; i < 3; i++) {
    snprintf(path, sizeof path, "%s/%s", root, dirs[i]);
    mkdir(path, 0755);
  }
  snprintf(savesDir, sizeof savesDir, "%s/saves", root);
  snprintf(hostIni, sizeof hostIni, "%s/host.ini", root);
  host_load();
  comboMenu = !(getenv("POCKET_MENU_COMBO") && !strcmp(getenv("POCKET_MENU_COMBO"), "off"));
  // A window manager can give another window the focus over the game (plorpOS's in-game menu on the GKD): the
  // controller has to keep reaching the host then.
  SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
  if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_JOYSTICK | SDL_INIT_GAMECONTROLLER)) {
    fprintf(stderr, "SDL: %s\n", SDL_GetError());
    return 1;
  }
  SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "0");
  SDL_DisplayMode display = {0};
  // Fullscreen with POCKET_FULLSCREEN, and under gamescope (the Steam Deck's Game Mode): it scales a window to the
  // screen keeping its shape, so a window there was letterboxed and EXPAND couldn't reach the edges.
  const char *desktop = getenv("XDG_CURRENT_DESKTOP");
  bool fullscreen = getenv("POCKET_FULLSCREEN") != NULL || (desktop && strstr(desktop, "gamescope"));
  if (fullscreen && SDL_GetCurrentDisplayMode(0, &display))
    fullscreen = false;
  // POCKET_WINDOW=WxH: a window the size of a device's panel, for trying a layout on a desktop.
  int winW = W, winH = H;
  const char *size = getenv("POCKET_WINDOW");
  if (size &&
      (sscanf(size, "%dx%d", &winW, &winH) != 2 || winW < 160 || winH < 144 || winW > 8192 || winH > 8192))
    winW = W, winH = H;
  window = SDL_CreateWindow("PokeDaisy Linux", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
                            fullscreen ? display.w : winW, fullscreen ? display.h : winH,
                            fullscreen ? SDL_WINDOW_FULLSCREEN : SDL_WINDOW_RESIZABLE);
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
    fprintf(stderr, "Video backend: %s\n", rendererInfo.name);
  SDL_ShowCursor(SDL_DISABLE);
  // SDL starts text input with the window, and Steam answers it with its on-screen keyboard: the host reads keys,
  // never text.
  SDL_StopTextInput();
  SDL_SetRenderDrawColor(renderer, 0, 0, 0, 255);
  audio_open();
  rawTrimui = getenv("POCKET_RAW_TRIMUI") && !strcmp(getenv("POCKET_RAW_TRIMUI"), "1");
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
    run_game(rom, frames, shot, false);
  else {
    /* DATA/roms, then the folders POCKET_ROM_DIRS names (colon-separated: the PortMaster launcher passes the
     * firmware's own gba / gb / gbc / nds folders), by file name. */
    static char *paths[1024];
    static const char *names[1024];
    int count = 0;
    char dirs[4096];
    snprintf(dirs, sizeof dirs, "%s/roms%s%s", root, getenv("POCKET_ROM_DIRS") ? ":" : "",
             getenv("POCKET_ROM_DIRS") ? getenv("POCKET_ROM_DIRS") : "");
    char *save = NULL;
    for (char *dir = strtok_r(dirs, ":", &save); dir; dir = strtok_r(NULL, ":", &save)) {
      DIR *d = opendir(dir);
      struct dirent *e;
      while (d && (e = readdir(d)) && count < 1024) {
        if (core_for(e->d_name)) {
          snprintf(path, sizeof path, "%s/%s", dir, e->d_name);
          paths[count++] = strdup(path);
        }
      }
      if (d)
        closedir(d);
    }
    qsort(paths, count, sizeof(*paths), cmp);
    for (int i = 0; i < count; i++)
      names[i] = base_name(paths[i]);
    struct pd_menu list;
    pd_menu_init(&list, "PokeDaisy Linux", names, count);
    int ticks = 0;
    while (running) {
      events();
      repeat_directions();
      if (down(B) || down(MENU))
        break;
      if (down(UP))
        pd_menu_move(&list, -1);
      if (down(DOWN))
        pd_menu_move(&list, 1);
      if (down(A) && count)
        appError = 0, run_game(paths[list.selected], 0, NULL, true); // the last game's result is the exit status
      SDL_RenderClear(renderer);
      overlay_begin();
      pd_menu_draw(&list, &overlay);
      overlay_notice();
      overlay_draw(true);
      SDL_RenderPresent(renderer);
      if (frames && ++ticks >= frames) {
        if (shot && !capture(shot))
          appError = 1;
        break;
      }
      SDL_Delay(16);
    }
    for (int i = 0; i < count; i++)
      free(paths[i]);
  }
  if (js)
    SDL_JoystickClose(js);
  if (controller)
    SDL_GameControllerClose(controller);
  if (audio)
    SDL_CloseAudioDevice(audio);
  if (frameTexture)
    SDL_DestroyTexture(frameTexture);
  if (overlayTexture)
    SDL_DestroyTexture(overlayTexture);
  if (lcdTexture)
    SDL_DestroyTexture(lcdTexture);
  SDL_DestroyRenderer(renderer);
  SDL_DestroyWindow(window);
  SDL_Quit();
  return appError;
}
