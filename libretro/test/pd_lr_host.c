/* A bare libretro frontend for testing pokedaisy_gbc_libretro on the desktop: scripted buttons, option values, frame dumps.
 *   pd_lr_host CORE ROM SAVEDIR [-o key=value]... [-p FRAME:BUTTON[:HOLD]]... [-d FRAME:FILE.ppm]... [-n FRAMES] [-R FRAME: unload and load again after it] [-s SAVE.srm]
 *   [-w FRAME:OFFSET:HEX] [-r FRAME:OFFSET:N] (system RAM poke / peek)
 * BUTTON: a b select start right left up down r l x y l2 r2 l3. -r: write the frontend's view of the last frame size. */
#define _GNU_SOURCE
#include "libretro.h"
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *names[] = {"b", "y", "select", "start", "up", "down", "left", "right", "a", "x", "l", "r", "l2", "r2", "l3", "r3"};
static struct { const char *k, *v; } opts[64];
static int nopts;
static struct { int frame, button, hold; } presses[256];
static int npress;
/* -w FRAME:OFFSET:HEX - bytes written into the core's system RAM after that frame (a Game Boy core: Color WRAM, C000
 * at 0 and bank N's D000 at N * 0x1000), or read: -r FRAME:OFFSET:N prints N bytes. */
static struct { int frame; long off; char *hex; int n; } pokes[64];
static int npoke;
static struct { int frame; const char *file; } dumps[64];
static int ndump;
static int frame, lastW, lastH;
static uint16_t shot[1024 * 768];
static const char *saveDir;

static unsigned char *gbWram; // a Game Boy core's Color WRAM (its C000 memory descriptor): -w / -r go there
static bool env(unsigned cmd, void *data) {
  if (cmd == RETRO_ENVIRONMENT_SET_MEMORY_MAPS) {
    const struct retro_memory_map *mm = data;
    for (unsigned i = 0; i < mm->num_descriptors; i++)
      if (mm->descriptors[i].start == 0xC000 && mm->descriptors[i].ptr)
        gbWram = (unsigned char *)mm->descriptors[i].ptr + mm->descriptors[i].offset;
  }
  switch (cmd) {
  case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: return *(int *)data == RETRO_PIXEL_FORMAT_RGB565;
  case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
  case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *(const char **)data = saveDir; return true;
  case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: *(unsigned *)data = 2; return true;
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: return true;
  case RETRO_ENVIRONMENT_GET_VARIABLE: {
    struct retro_variable *v = data;
    for (int i = 0; i < nopts; i++)
      if (!strcmp(opts[i].k, v->key)) { v->value = opts[i].v; return true; }
    return false;
  }
  case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool *)data = false; return true;
  case RETRO_ENVIRONMENT_GET_THROTTLE_STATE: {   // PD_FF=1: the frontend says it is fast-forwarding, PD_RW=1: rewinding
    struct retro_throttle_state *t = data;
    t->mode = getenv("PD_RW") ? RETRO_THROTTLE_REWINDING : getenv("PD_FF") ? RETRO_THROTTLE_FAST_FORWARD : RETRO_THROTTLE_VSYNC;
    t->rate = 0;
    return true;
  }
  case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
  default: return false;
  }
}
static void video(const void *d, unsigned w, unsigned h, size_t pitch) {
  lastW = (int)w; lastH = (int)h;
  for (unsigned y = 0; y < h; y++) memcpy(shot + y * w, (const char *)d + y * pitch, w * 2);
}
static void audio_one(int16_t l, int16_t r) { (void)l; (void)r; }
static size_t audio(const int16_t *d, size_t n) { (void)d; return n; }
static void poll(void) {}
/* -t FRAME:FX:FY[:HOLD] - a finger at (FX, FY), 0..1 of the picture, from FRAME for HOLD frames (default 3): the libretro
 * pointer, as the RG Rotate's diatom passes its panel. */
static struct { int frame, hold; double fx, fy; } touches[64];
static int ntouch;
static int16_t input(unsigned port, unsigned dev, unsigned idx, unsigned id) {
  (void)idx;
  if (!port && dev == RETRO_DEVICE_POINTER) {
    for (int i = 0; i < ntouch; i++)
      if (frame >= touches[i].frame && frame < touches[i].frame + touches[i].hold) {
        if (id == RETRO_DEVICE_ID_POINTER_PRESSED || id == RETRO_DEVICE_ID_POINTER_COUNT) return 1;
        if (id == RETRO_DEVICE_ID_POINTER_X) return (int16_t)((touches[i].fx * 2 - 1) * 0x7fff);
        if (id == RETRO_DEVICE_ID_POINTER_Y) return (int16_t)((touches[i].fy * 2 - 1) * 0x7fff);
      }
    return 0;
  }
  if (port || dev != RETRO_DEVICE_JOYPAD) return 0;
  int16_t m = 0;
  for (int i = 0; i < npress; i++)
    if (frame >= presses[i].frame && frame < presses[i].frame + presses[i].hold) m |= 1 << presses[i].button;
  return id == RETRO_DEVICE_ID_JOYPAD_MASK ? m : (m >> id) & 1;
}
static void write_ppm(const char *f) {
  FILE *o = fopen(f, "wb");
  if (!o) return;
  fprintf(o, "P6\n%d %d\n255\n", lastW, lastH);
  for (int i = 0; i < lastW * lastH; i++) {
    uint16_t p = shot[i];
    unsigned r = p >> 11 & 31, g = p >> 5 & 63, b = p & 31;
    fputc(r << 3 | r >> 2, o); fputc(g << 2 | g >> 4, o); fputc(b << 3 | b >> 2, o);
  }
  fclose(o);
}
int main(int argc, char **argv) {
  if (argc < 4) return 2;
  saveDir = argv[3];
  int frames = 300, reload = 0;
  const char *savefile = NULL;
  for (int i = 4; i + 1 < argc; i += 2) {
    if (!strcmp(argv[i], "-o")) { char *e = strchr(argv[i + 1], '='); *e = 0; opts[nopts].k = argv[i + 1]; opts[nopts++].v = e + 1; }
    else if (!strcmp(argv[i], "-p")) {
      int f, h = 3; char b[16];
      if (sscanf(argv[i + 1], "%d:%15[a-z0-9]:%d", &f, b, &h) < 2) return 2;
      int k = -1;
      for (int j = 0; j < 16; j++) if (!strcmp(names[j], b)) k = j;
      if (k < 0) return 2;
      presses[npress].frame = f; presses[npress].button = k; presses[npress++].hold = h;
    } else if (!strcmp(argv[i], "-d")) { char *c = strchr(argv[i + 1], ':'); *c = 0; dumps[ndump].frame = atoi(argv[i + 1]); dumps[ndump++].file = c + 1; }
    else if (!strcmp(argv[i], "-w") || !strcmp(argv[i], "-r")) {
      char *a = argv[i + 1], *c1 = strchr(a, ':'), *c2 = c1 ? strchr(c1 + 1, ':') : NULL;
      if (!c2) return 2;
      *c1 = *c2 = 0;
      pokes[npoke].frame = atoi(a);
      pokes[npoke].off = strtol(c1 + 1, NULL, 0);
      if (argv[i][1] == 'w') pokes[npoke].hex = c2 + 1; else pokes[npoke].n = atoi(c2 + 1);
      npoke++;
    }
    else if (!strcmp(argv[i], "-t")) {
      int hold = 3;
      if (sscanf(argv[i + 1], "%d:%lf:%lf:%d", &touches[ntouch].frame, &touches[ntouch].fx, &touches[ntouch].fy, &hold) < 3) return 2;
      touches[ntouch++].hold = hold;
    }
    else if (!strcmp(argv[i], "-n")) frames = atoi(argv[i + 1]);
    else if (!strcmp(argv[i], "-R")) reload = atoi(argv[i + 1]);
    else if (!strcmp(argv[i], "-s")) savefile = argv[i + 1];
  }
  void *so = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  if (!so) { fprintf(stderr, "%s\n", dlerror()); return 1; }
#define SYM(n) __typeof__(&n) n##_ = dlsym(so, #n)
  SYM(retro_set_environment); SYM(retro_set_video_refresh); SYM(retro_set_audio_sample); SYM(retro_set_audio_sample_batch);
  SYM(retro_set_input_poll); SYM(retro_set_input_state); SYM(retro_init); SYM(retro_load_game); SYM(retro_run);
  SYM(retro_get_system_av_info); SYM(retro_get_memory_data); SYM(retro_get_memory_size); SYM(retro_unload_game); SYM(retro_deinit);
  retro_set_environment_(env); retro_init_();
  retro_set_video_refresh_(video); retro_set_audio_sample_(audio_one); retro_set_audio_sample_batch_(audio);
  retro_set_input_poll_(poll); retro_set_input_state_(input);
  FILE *f = fopen(argv[2], "rb");
  if (!f) return 1;
  fseek(f, 0, SEEK_END); long len = ftell(f); rewind(f);
  void *rom = malloc(len);
  if (fread(rom, 1, len, f) != (size_t)len) return 1;
  fclose(f);
  struct retro_game_info gi = {argv[2], rom, (size_t)len, NULL};
  if (!retro_load_game_(&gi)) { fprintf(stderr, "load failed\n"); return 1; }
  if (savefile) { // the frontend's half of a battery save: the file into the core's save memory
    void *m = retro_get_memory_data_(RETRO_MEMORY_SAVE_RAM);
    size_t n = retro_get_memory_size_(RETRO_MEMORY_SAVE_RAM);
    FILE *sf = fopen(savefile, "rb");
    if (m && sf) { size_t got = fread(m, 1, n, sf); fprintf(stderr, "save: %zu of %zu bytes\n", got, n); }
    if (sf) fclose(sf);
  }
  struct retro_system_av_info av;
  retro_get_system_av_info_(&av);
  fprintf(stderr, "av %ux%u fps %.2f\n", av.geometry.base_width, av.geometry.base_height, av.timing.fps);
  for (frame = 0; frame < frames; frame++) {
    retro_run_();
    for (int i = 0; i < ndump; i++) if (dumps[i].frame == frame) write_ppm(dumps[i].file);
    for (int i = 0; i < npoke; i++) {
      if (pokes[i].frame != frame) continue;
      unsigned char *m = retro_get_memory_data_(RETRO_MEMORY_SYSTEM_RAM);
      size_t sz = retro_get_memory_size_(RETRO_MEMORY_SYSTEM_RAM);
      if (!m && gbWram) { m = gbWram; sz = 0x8000; }
      if (!m) { fprintf(stderr, "no system RAM\n"); continue; }
      if (pokes[i].hex) {
        for (size_t k = 0; pokes[i].hex[2 * k] && pokes[i].hex[2 * k + 1]; k++) {
          char b[3] = {pokes[i].hex[2 * k], pokes[i].hex[2 * k + 1], 0};
          if ((size_t)pokes[i].off + k < sz) m[pokes[i].off + k] = (unsigned char)strtol(b, NULL, 16);
        }
      } else {
        fprintf(stderr, "ram %d @0x%lx:", frame, pokes[i].off);
        for (int k = 0; k < pokes[i].n && (size_t)pokes[i].off + k < sz; k++) fprintf(stderr, " %02x", m[pokes[i].off + k]);
        fprintf(stderr, "\n");
      }
    }
    if (reload && frame == reload) { // unload and load the same game again in this process, as the resident frontend does
      retro_unload_game_();
      if (!retro_load_game_(&gi)) { fprintf(stderr, "reload failed\n"); return 1; }
    }
  }
  retro_unload_game_(); retro_deinit_();
  return 0;
}
