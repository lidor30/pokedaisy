/* What the shared game-code layer (app/src/main/cpp/pk_wide.c, later pk_call.c) reads from the emulator, in armGBA's
 * case: a view over the machine, with the shapes and names of mGBA's struct GBA / struct GBAVideoRenderer / struct mCore
 * that those files were written against. Neutral on purpose: pd_agba_view.c (which sees armGBA's own struct GBA) and the
 * shim headers under armgba/shim/ (which give pk_wide.c the mGBA names) both include it, and neither includes the other's
 * world. GPL-3.0, see ../NOTICE. */
#ifndef PD_AGBA_VIEW_H
#define PD_AGBA_VIEW_H

#include <stddef.h>
#include <stdint.h>

struct PdRenderer {
  void (*drawScanline)(struct PdRenderer *, int y);
  void (*writeVRAM)(struct PdRenderer *, uint32_t address);
  void (*writeOAM)(struct PdRenderer *, uint32_t oam);
  void (*writePalette)(struct PdRenderer *, uint32_t address, uint16_t value);
};

/* Pointers into armGBA's struct GBA (set by pd_agba_attach), so reading them is reading the machine. */
struct PdGbaView {
  struct {
    uint8_t *wram;     /* EWRAM, 256 KiB */
    uint8_t *iwram;    /* IWRAM, 32 KiB */
    uint8_t *rom;
    size_t romSize;
    uint16_t *io;
  } memory;
  struct {
    uint16_t *palette; /* 512 colours, BGR555 */
    uint16_t *vram;
    struct {
      uint16_t *raw;   /* 512 halfwords */
    } oam;
    struct PdRenderer *renderer;
  } video;
};

/* mCore's platform ids (mPLATFORM_GBA is 0). */
enum { PD_PLATFORM_GBA = 0, PD_PLATFORM_GB = 1 };

struct PdCore {
  int (*platform)(const struct PdCore *);
  uint32_t (*getKeys)(struct PdCore *);
  void *board;                                              /* struct PdGbaView * */
  uint8_t (*busRead8)(struct PdCore *, uint32_t address);
  uint16_t (*busRead16)(struct PdCore *, uint32_t address);
  uint32_t (*busRead32)(struct PdCore *, uint32_t address);
  void (*busWrite8)(struct PdCore *, uint32_t address, uint8_t value);
  void (*busWrite16)(struct PdCore *, uint32_t address, uint16_t value);
  void (*busWrite32)(struct PdCore *, uint32_t address, uint32_t value);
  /* mCore's: a memory region in place (pk_flight.c's sprite copy). Left NULL here: callers fall back to bus reads. */
  void *(*getMemoryBlock)(struct PdCore *, size_t id, size_t *sizeOut);
};

/* pd_agba_view.c */
void pd_agba_attach(void);   /* after a game is loaded: point the view at the machine */
void pd_agba_detach(void);
struct PdCore *pd_agba_core(void);

#endif
