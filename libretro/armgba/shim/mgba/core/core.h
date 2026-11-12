/* A stand-in for mGBA's <mgba/core/core.h>, for pk_wide.c over armGBA: the platform ids, the view's types (through the
 * names pd_names.h gives them) and the two colour helpers, the 5-6-5 forms mGBA's libretro core is built with
 * (mgba/core/interface.h, MPL-2.0, Jeffrey Pfau and contributors). */
#ifndef PD_SHIM_MGBA_CORE_H
#define PD_SHIM_MGBA_CORE_H
#include "pd_agba_view.h"

enum mPlatform { mPLATFORM_NONE = -1, mPLATFORM_GBA = PD_PLATFORM_GBA, mPLATFORM_GB = PD_PLATFORM_GB };

/* the two memory constants pk_wide.c names (mgba/internal/gba/memory.h) */
enum { BASE_PALETTE_RAM = 0x05000000, SIZE_WORKING_RAM = 0x00040000 };

typedef uint16_t color_t;

static inline color_t mColorFrom555(uint16_t value) {
  color_t color = 0;
  color |= (value & 0x001F) << 11;
  color |= (value & 0x03E0) << 1;
  color |= (value & 0x7C00) >> 10;
  return color;
}

__attribute__((unused)) static unsigned mColorMix5Bit(int weightA, unsigned colorA, int weightB, unsigned colorB) {
  unsigned c = 0;
  unsigned a, b;
  a = colorA & 0xF81F;
  b = colorB & 0xF81F;
  a |= (colorA & 0x7C0) << 16;
  b |= (colorB & 0x7C0) << 16;
  c = ((a * weightA + b * weightB) / 16);
  if (c & 0x08000000) {
    c = (c & ~0x0FC00000) | 0x07C00000;
  }
  if (c & 0x0020) {
    c = (c & ~0x003F) | 0x001F;
  }
  if (c & 0x10000) {
    c = (c & ~0x1F800) | 0xF800;
  }
  c = (c & 0xF81F) | ((c >> 16) & 0x07C0);
  return c;
}
#endif
