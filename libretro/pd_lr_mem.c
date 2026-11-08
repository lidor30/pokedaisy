/* The companion's memory reader for the libretro core: the three branches of
 * linux/main.c's read_memory (GB banked WRAM, GBA ROM block, bus read). */
#include <mgba/core/core.h>
#include <mgba/gb/core.h>
#include <mgba/internal/gb/gb.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

bool pd_lr_read_memory(void *ctx, uint32_t a, void *out, size_t n) {
  struct mCore *c = ctx;
  unsigned char *p = out;
  if (c->platform(c) == mPLATFORM_GB && a >= 0x10000) {
    unsigned bank = a >> 16, addr = a & 65535;
    struct GB *gb = c->board;
    if (bank > 7 || addr < 0xd000 || addr >= 0xe000 || n > 0xe000 - addr || !gb->memory.wram)
      return false;
    memcpy(p, gb->memory.wram + bank * 0x1000 + addr - 0xd000, n);
    return true;
  }
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
