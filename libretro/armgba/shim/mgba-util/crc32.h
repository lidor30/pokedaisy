/* A stand-in for mGBA's <mgba-util/crc32.h>: doCrc32 (the standard CRC-32), which fly.c and field_items.c use to recognise
 * 32 bytes of the game's code. */
#ifndef PD_SHIM_CRC32_H
#define PD_SHIM_CRC32_H
#include <stddef.h>
#include <stdint.h>

static inline uint32_t doCrc32(const void *buf, size_t size) {
  const uint8_t *p = buf;
  uint32_t c = 0xffffffffu;
  for (size_t i = 0; i < size; i++) {
    c ^= p[i];
    for (int k = 0; k < 8; k++)
      c = (c >> 1) ^ (0xedb88320u & (0u - (c & 1u)));
  }
  return ~c;
}
#endif
