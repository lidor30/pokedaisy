/* PNG covers for the library (see cover_png.h): 8-bit grey, grey + alpha, RGB, RGBA and palette images, 16-bit ones by
 * their high byte, not interlaced. */
#include "cover_png.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "puff.h"

#define MAX_FILE (24u << 20)
#define MAX_SIDE 2048 // a cover is drawn at 320: a bigger picture costs a 1 GB handheld up to 48 MB to decode

static uint32_t be32(const unsigned char *p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }

static unsigned char paeth(int a, int b, int c) {
  int p = a + b - c, pa = abs(p - a), pb = abs(p - b), pc = abs(p - c);
  return (unsigned char)(pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
}

/* The file's pixels as 0xAARRGGBB, w * h of them (malloc'd), or NULL. */
static uint32_t *decode(const char *path, int *outW, int *outH) {
  FILE *f = fopen(path, "rb");
  if (!f)
    return NULL;
  fseek(f, 0, SEEK_END);
  long size = ftell(f);
  fseek(f, 0, SEEK_SET);
  unsigned char *file = size > 0 && (unsigned long)size <= MAX_FILE ? malloc((size_t)size) : NULL;
  bool read = file && fread(file, 1, (size_t)size, f) == (size_t)size;
  fclose(f);
  static const unsigned char SIG[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
  if (!read || size < 8 + 25 || memcmp(file, SIG, 8)) {
    free(file);
    return NULL;
  }
  int w = 0, h = 0, depth = 0, type = 0, interlace = 0;
  uint32_t palette[256];
  for (int i = 0; i < 256; i++)
    palette[i] = 0xFF000000u;
  unsigned char *idat = malloc((size_t)size);
  unsigned long idatLen = 0;
  for (long at = 8; idat && at + 12 <= size;) {
    uint32_t len = be32(file + at);
    const unsigned char *kind = file + at + 4, *data = file + at + 8;
    if (len > (uint32_t)(size - at - 12))
      break;
    if (!memcmp(kind, "IHDR", 4) && len >= 13) {
      w = (int)be32(data);
      h = (int)be32(data + 4);
      depth = data[8];
      type = data[9];
      interlace = data[12];
    } else if (!memcmp(kind, "PLTE", 4)) {
      for (uint32_t i = 0; i + 2 < len && i / 3 < 256; i += 3)
        palette[i / 3] = 0xFF000000u | (uint32_t)data[i] << 16 | (uint32_t)data[i + 1] << 8 | data[i + 2];
    } else if (!memcmp(kind, "tRNS", 4) && type == 3) {
      for (uint32_t i = 0; i < len && i < 256; i++)
        palette[i] = (palette[i] & 0xFFFFFF) | (uint32_t)data[i] << 24;
    } else if (!memcmp(kind, "IDAT", 4)) {
      memcpy(idat + idatLen, data, len);
      idatLen += len;
    } else if (!memcmp(kind, "IEND", 4)) {
      break;
    }
    at += 12 + (long)len;
  }
  free(file);
  int channels = type == 0 ? 1 : type == 2 ? 3 : type == 3 ? 1 : type == 4 ? 2 : type == 6 ? 4 : 0;
  int bytes = depth == 16 ? 2 : 1;
  // 1, 2 and 4 bits a pixel: palette and grey images only (PNG allows no other).
  bool packed = depth < 8 && (type == 3 || type == 0) && (depth == 1 || depth == 2 || depth == 4);
  if (!idat || w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || !channels || interlace ||
      !(packed || depth == 8 || (depth == 16 && type != 3)) || idatLen < 2) {
    free(idat);
    return NULL;
  }
  size_t stride = packed ? ((size_t)w * depth + 7) / 8 : (size_t)w * channels * bytes, rawLen = (stride + 1) * (size_t)h;
  unsigned char *raw = malloc(rawLen);
  unsigned long destLen = rawLen, srcLen = idatLen - 2;
  // The zlib stream's two header bytes, then raw deflate (puff's).
  if (!raw || puff(raw, &destLen, idat + 2, &srcLen) != 0 || destLen != rawLen) {
    free(idat);
    free(raw);
    return NULL;
  }
  free(idat);
  int bpp = packed ? 1 : channels * bytes; // the filters' byte step
  for (int y = 0; y < h; y++) {
    unsigned char *row = raw + (size_t)y * (stride + 1), *line = row + 1;
    const unsigned char *up = y ? raw + (size_t)(y - 1) * (stride + 1) + 1 : NULL;
    for (size_t i = 0; i < stride; i++) {
      int a = i >= (size_t)bpp ? line[i - bpp] : 0, b = up ? up[i] : 0, c = up && i >= (size_t)bpp ? up[i - bpp] : 0;
      switch (row[0]) {
      case 1: line[i] = (unsigned char)(line[i] + a); break;
      case 2: line[i] = (unsigned char)(line[i] + b); break;
      case 3: line[i] = (unsigned char)(line[i] + (a + b) / 2); break;
      case 4: line[i] = (unsigned char)(line[i] + paeth(a, b, c)); break;
      default: break;
      }
    }
  }
  uint32_t *px = malloc((size_t)w * h * 4);
  for (int y = 0; px && y < h; y++) {
    const unsigned char *line = raw + (size_t)y * (stride + 1) + 1;
    for (int x = 0; x < w; x++) {
      const unsigned char *p = line + (size_t)x * bpp;
      unsigned v[4];
      if (packed) {
        unsigned mask = (1u << depth) - 1, bit = (unsigned)x * (unsigned)depth;
        v[0] = (unsigned)(line[bit / 8] >> (8 - depth - bit % 8)) & mask;
        if (type == 0)
          v[0] = v[0] * 255 / mask;
      } else
        for (int k = 0; k < channels; k++)
          v[k] = p[k * bytes]; // a 16-bit sample's high byte
      uint32_t argb;
      if (type == 3)
        argb = palette[v[0]];
      else if (type == 0)
        argb = 0xFF000000u | v[0] << 16 | v[0] << 8 | v[0];
      else if (type == 4)
        argb = v[1] << 24 | v[0] << 16 | v[0] << 8 | v[0];
      else if (type == 2)
        argb = 0xFF000000u | v[0] << 16 | v[1] << 8 | v[2];
      else
        argb = v[3] << 24 | v[0] << 16 | v[1] << 8 | v[2];
      px[(size_t)y * w + x] = argb;
    }
  }
  free(raw);
  *outW = w;
  *outH = h;
  return px;
}

bool cover_png_square(const char *path, int size, uint32_t *out, bool fit) {
  int w, h;
  uint32_t *px = decode(path, &w, &h);
  if (!px)
    return false;
  // The middle square (the box's sides cut evenly), area-averaged down (or sampled up) to size x size.
  int side = fit ? (w > h ? w : h) : (w < h ? w : h), x0 = (w - side) / 2, y0 = (h - side) / 2;
  for (int y = 0; y < size; y++) {
    int sy0 = y * side / size, sy1 = (y + 1) * side / size;
    if (sy1 <= sy0)
      sy1 = sy0 + 1;
    for (int x = 0; x < size; x++) {
      int sx0 = x * side / size, sx1 = (x + 1) * side / size;
      if (sx1 <= sx0)
        sx1 = sx0 + 1;
      unsigned long a = 0, r = 0, g = 0, b = 0, n = 0;
      for (int sy = sy0; sy < sy1; sy++)
        for (int sx = sx0; sx < sx1; sx++) {
          int ix = x0 + sx, iy = y0 + sy;
          uint32_t p = ix >= 0 && iy >= 0 && ix < w && iy < h ? px[(size_t)iy * w + ix] : 0;
          unsigned long al = p >> 24;
          a += al;
          r += (p >> 16 & 0xFF) * al;
          g += (p >> 8 & 0xFF) * al;
          b += (p & 0xFF) * al;
          n++;
        }
      out[y * size + x] = a ? (uint32_t)(a / n) << 24 | (uint32_t)(r / a) << 16 | (uint32_t)(g / a) << 8 | (uint32_t)(b / a) : 0;
    }
  }
  free(px);
  return true;
}
