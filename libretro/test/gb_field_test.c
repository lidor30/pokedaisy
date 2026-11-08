/* gb_field_find over real carts: gb_field_test ROM EXPECTED [ROM EXPECTED ...] (EXPECTED = gb_field_name, or "none").
 * The app's GbFieldTest: every cart's own config, and no other's. The calls into the game are stubbed: this only reads ROMs. */
#include "../../linux/gb_field.c"
#include <stdio.h>
#include <stdlib.h>

const uint16_t pd_gb_landmarks_crystal[][2] = {{0, 0}};
const int pd_gb_landmarks_crystal_count = 0;
bool pk_gb_write(struct mCore *core, uint32_t addr, const uint8_t *src, int len) { (void)core, (void)addr, (void)src, (void)len; return false; }
bool pk_gb_call_at(struct mCore *core, uint16_t at, uint16_t fn, uint8_t a, uint8_t b, uint16_t hl, int maxHalts) {
  (void)core, (void)at, (void)fn, (void)a, (void)b, (void)hl, (void)maxHalts;
  return false;
}

int main(int argc, char **argv) {
  int fails = 0, ran = 0;
  for (int i = 1; i + 1 < argc; i += 2) {
    FILE *f = fopen(argv[i], "rb");
    if (!f) {
      printf("SKIP %s (not found)\n", argv[i]);
      continue;
    }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    rewind(f);
    uint8_t *rom = malloc((size_t)n);
    if (!rom || fread(rom, 1, (size_t)n, f) != (size_t)n) {
      printf("FAIL %s (read)\n", argv[i]);
      fails++;
      fclose(f);
      free(rom);
      continue;
    }
    fclose(f);
    const char *got = gb_field_name(gb_field_find(rom, (size_t)n));
    bool ok = !strcmp(got, argv[i + 1]);
    printf("%s %s: %s (want %s)\n", ok ? "PASS" : "FAIL", argv[i], got, argv[i + 1]);
    fails += !ok;
    ran++;
    free(rom);
  }
  printf("%d checked, %d failed\n", ran, fails);
  return fails != 0 || ran == 0;
}
