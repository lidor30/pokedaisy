/* GPL-3.0; recovered from the working pocket/polished.c decoder. */
#include <stddef.h>
int pc_lz(const unsigned char *rom, size_t size, size_t p, unsigned char *out,
          size_t capacity) {
  int used = 0;
#define BYTE(v)                                                                \
  do {                                                                         \
    if (p >= size)                                                             \
      return -1;                                                               \
    (v) = rom[p++];                                                            \
  } while (0)
  while (1) {
    int b;
    BYTE(b);
    if (b == 255)
      return used;
    int cmd = b >> 5, n = (b & 31) + 1;
    if (cmd == 7) {
      int v;
      BYTE(v);
      cmd = (b >> 2) & 7;
      n = ((b & 1) * 256 + v) + 1;
    }
    if (cmd > 6)
      return -1;
    if (cmd == 1)
      n++;
    if (cmd == 2)
      n += 2;
    if ((size_t)n > capacity - (size_t)used)
      return -1;
    int a = 0, c = 0, from = 0;
    if (cmd == 1 || cmd == 2)
      BYTE(a);
    if (cmd == 2)
      BYTE(c);
    if (cmd >= 4) {
      BYTE(a);
      if (a & 128)
        from = used - (a & 127) - 1;
      else {
        BYTE(c);
        from = a * 256 + c;
      }
    }
    for (int i = 0; i < n; i++) {
      int v = 0;
      if (cmd == 0)
        BYTE(v);
      else if (cmd == 1)
        v = a;
      else if (cmd == 2)
        v = (i & 1) ? c : a;
      else if (cmd >= 4) {
        int at = from + (cmd == 6 ? -i : i);
        if (at < 0 || at >= used)
          return -1;
        v = out[at];
        if (cmd == 5) {
          int r = 0;
          for (int k = 0; k < 8; k++)
            r |= ((v >> k) & 1) << (7 - k);
          v = r;
        }
      }
      out[used++] = (unsigned char)v;
    }
  }
#undef BYTE
}
