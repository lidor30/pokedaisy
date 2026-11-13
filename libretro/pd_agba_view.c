/* The view of armGBA's machine that pk_wide.c (EXPAND) is written against, and the one hook it needs: a call for each visible
 * line, made as the line is drawn, with the registers it is drawn with. GPL-3.0, see ../NOTICE.
 *
 * mGBA's renderer has drawScanline / writeVRAM / writeOAM / writePalette function pointers, which pk_wide.c replaces with
 * its own to record each line's registers, palette and OAM. armGBA's renderer is the same code reorganised and calls
 * renderScanline directly, from video.c, when the line's H-blank comes (or before anything the line shows changes). The
 * CMake project renames that call (and renderThreadScanline's) to pdRenderScanline here, so the hook sits exactly where
 * mGBA's did: after the line's registers are settled, before they move on to the next line. What the renderer's own
 * write hooks tell mGBA's, armGBA keeps as dirty bits (memoryChanged, vramChanged) that this reports just ahead of the
 * line they belong to. */
#include "core.h"   /* armGBA's libretro glue: extern struct GBA *gba */
#include "gba.h"
#include "pd_agba_view.h"

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static struct PdGbaView view;
static struct PdRenderer renderer;
static struct PdCore pdcore;
static struct GBA *drawing; /* the machine the line being drawn belongs to (the hook's "orig" has no argument for it) */
static bool threaded;
#ifdef PD_WIDECHECK
static unsigned hooked, unhooked; /* lines drawn through pk_wide's hook / straight (a test build's summary) */
#endif

/* "origDrawScanline": what the renderer does when nothing hooks it. */
static void realDraw(struct PdRenderer *r, int y) {
  (void)r;
  if (threaded)
    renderThreadScanline(drawing, y);
  else
    renderScanline(drawing, y);
}
/* mGBA's renderer learns of a store to video memory through these; armGBA's learns it from the same dirty bits it keeps. */
static void noVram(struct PdRenderer *r, uint32_t a) {
  (void)r;
  if (gba)
    gba->renderer.vramChanged[((a & 0x1FFFF) >= SIZE_VRAM ? a & 0x17FFF : a & 0x1FFFF) >> 11] = 1;
}
static void noOam(struct PdRenderer *r, uint32_t a) { (void)r; (void)a; if (gba) gba->renderer.memoryChanged |= RENDER_OAM; }
static void noPalette(struct PdRenderer *r, uint32_t a, uint16_t v) {
  (void)r; (void)a; (void)v;
  if (gba)
    gba->renderer.memoryChanged |= RENDER_PALETTE;
}

static void vramChanged(struct GBA *g) {
  static const uint8_t zero[sizeof g->renderer.vramChanged];
  if (memcmp(g->renderer.vramChanged, zero, sizeof zero))
    renderer.writeVRAM(&renderer, 0);
}

static void line(struct GBA *g, int y, bool thread) {
  drawing = g;
  threaded = thread;
  if (g != gba || !renderer.drawScanline) { /* not the machine the view is on, or not attached: just draw */
    threaded = thread;
    realDraw(&renderer, y);
#ifdef PD_WIDECHECK
    unhooked++;
#endif
    return;
  }
#ifdef PD_WIDECHECK
  hooked += renderer.drawScanline != realDraw;
  unhooked += renderer.drawScanline == realDraw;
#endif
  uint8_t changed = g->renderer.memoryChanged;
  if (changed & RENDER_PALETTE)
    renderer.writePalette(&renderer, 0, 0);
  if (changed & RENDER_OAM)
    renderer.writeOAM(&renderer, 0);
  vramChanged(g);
  renderer.drawScanline(&renderer, y);
}

/* video.c's calls, renamed (see the CMake project). */
void pdRenderScanline(struct GBA *g, int y) { line(g, y, false); }
void pdRenderThreadScanline(struct GBA *g, int y) { line(g, y, true); }

static int platform(const struct PdCore *c) { (void)c; return PD_PLATFORM_GBA; }
static uint32_t getKeys(struct PdCore *c) { (void)c; return gba ? gba->keysActive : 0; }
static uint8_t read8(struct PdCore *c, uint32_t a) { (void)c; return gba ? (uint8_t)memoryLoad8(gba, a, NULL) : 0; }
static uint16_t read16(struct PdCore *c, uint32_t a) { (void)c; return gba ? (uint16_t)memoryLoad16(gba, a, NULL) : 0; }
static uint32_t read32(struct PdCore *c, uint32_t a) { (void)c; return gba ? memoryLoad32(gba, a, NULL) : 0; }
static void write8(struct PdCore *c, uint32_t a, uint8_t v) { (void)c; if (gba) memoryStore8(gba, a, v, NULL); }
static void write16(struct PdCore *c, uint32_t a, uint16_t v) { (void)c; if (gba) memoryStore16(gba, a, v, NULL); }
static void write32(struct PdCore *c, uint32_t a, uint32_t v) { (void)c; if (gba) memoryStore32(gba, a, v, NULL); }

void pd_agba_attach(void) {
  if (!gba)
    return;
  memset(&view, 0, sizeof view);
  view.memory.wram = gba->ewram;
  view.memory.iwram = gba->iwram;
  view.memory.rom = (uint8_t *)gba->rom;
  view.memory.romSize = gba->romSize;
  view.memory.io = gba->io;
  view.video.palette = (uint16_t *)gba->palette;
  view.video.vram = (uint16_t *)gba->vram;
  view.video.oam.raw = (uint16_t *)gba->oam;
  renderer = (struct PdRenderer){realDraw, noVram, noOam, noPalette};
  view.video.renderer = &renderer;
  pdcore = (struct PdCore){platform, getKeys, &view, read8, read16, read32, write8, write16, write32};
}

void pd_agba_detach(void) {
#ifdef PD_WIDECHECK
  fprintf(stderr, "pokedaisy: lines through the hook %u, straight %u\n", hooked, unhooked);
#endif
  memset(&view, 0, sizeof view);
  memset(&renderer, 0, sizeof renderer);
  drawing = NULL;
}

struct PdCore *pd_agba_core(void) { return &pdcore; }
