/* PokeDaisy - PC anywhere, FLY and WILD ENCOUNTERS on the Game Boy games: GbField.kt in C (gb_field.h has the how).
 * Every address and signature is the app's (pret/pokeyellow, pokered, pokecrystal symbols; the European Yellows'
 * mapped by scripts/port_gen1.py; PureRGB 2.7.6 from its pokered.sym). Keep in sync with GbField.kt. */
#include "gb_field.h"
#include "pk_gb_field.h"
#include "pd_guide.h"
#include <mgba/core/core.h>
#include <mgba/internal/gb/gb.h>
#include <string.h>

#define WRAMX(bank, addr) ((uint32_t)(bank) << 16 | (addr))
#define FLY_MOVE_ID 19 /* MOVE_FLY in Gen 1 and 2 */

struct sig {
  uint32_t off;
  const char *hex;
};

struct gb_field {
  const char *name;
  bool crystal;
  /* Gen 1 */
  uint16_t loopAfterWait, bankswitch, pcRoutine;
  uint32_t walkCounter, flags5, inBattle, badges, tileset, townsVisited, destination, flags6;
  int32_t pikachuFlags, pikachuImage; /* -1: none (Red / Blue, PureRGB) */
  uint32_t partyCount, partyMoves, curMap, grassRate, waterRate;
  uint16_t wildDataPointers;
  uint8_t pcBank, wildBank;
  struct sig sigs[8];
};

/* ---- the carts (GbField.kt's GB_FIELD_* and Crystal) ---- */
static const struct gb_field GB_FIELDS[] = {
    {"Yellow", false, 0x0248, 0x3E84, 0x66F4, 0xCFC4, 0xD72F, 0xD056, 0xD355, 0xD366, 0xD70A, 0xD719, 0xD731, 0xD42F,
     0xC1F2, 0xD162, 0xD172, 0xD35D, 0xD886, 0xD8A3, 0x4B95, 0x18, 0x03,
     {{0x245, "cd641ecd2a34cd6f1ecde10ffac4cfa7c2c303cd"},
      {0x3E84, "f0b8f578e0b8ea0020cd983e"},
      {0x3F3A, "e08c21673fcd543f2111cfcb"},
      {0x626EE, "fa09c1fe04c0cd293c3e01ea0ccf3e21c33a3ff9"},
      {0x710B4, "7eea19d72131d7cbde23cbfe"},
      {0x710FB, "213dcd36ff23fa0ad75ffa0bd75701"},
      {0xCB62, "21954bfa5dd34f060009092a666f2aea86d8"},
      {0x1510, "e5212fd4cbfe21f2c136ffe1c9"}}},
    {"Yellow DE", false, 0x0248, 0x3E89, 0x66F4, 0xCFC9, 0xD734, 0xD05B, 0xD35A, 0xD36B, 0xD70F, 0xD71E, 0xD736, 0xD434,
     0xC1F2, 0xD167, 0xD177, 0xD362, 0xD88B, 0xD8A8, 0x4B95, 0x18, 0x03,
     {{0x245, "cd691ecd2f34cd741ecde10ffac9cfa7c2c303cd"},
      {0x3E89, "f0b8f578e0b8ea0020cd9d3e"},
      {0x3F3F, "e08c216c3fcd593f2116cfcb"},
      {0x626EE, "fa09c1fe04c0cd2e3c3e01ea11cf3e21c33f3ff9"},
      {0x710B2, "7eea1ed72136d7cbde23cbfe"},
      {0x710F9, "213dcd36ff23fa0fd75ffa10d75701"},
      {0xCB62, "21954bfa62d34f060009092a666f2aea8bd8"},
      {0x1510, "e52134d4cbfe21f2c136ffe1c9"}}},
    {"Yellow FR", false, 0x0248, 0x3E87, 0x66F4, 0xCFC9, 0xD734, 0xD05B, 0xD35A, 0xD36B, 0xD70F, 0xD71E, 0xD736, 0xD434,
     0xC1F2, 0xD167, 0xD177, 0xD362, 0xD88B, 0xD8A8, 0x4B95, 0x18, 0x03,
     {{0x245, "cd601ecd2d34cd6b1ecde10ffac9cfa7c2c303cd"},
      {0x3E87, "f0b8f578e0b8ea0020cd9b3e"},
      {0x3F3D, "e08c216a3fcd573f2116cfcb"},
      {0x626EE, "fa09c1fe04c0cd2c3c3e01ea11cf3e21c33d3ff9"},
      {0x710B7, "7eea1ed72136d7cbde23cbfe"},
      {0x710FE, "213dcd36ff23fa0fd75ffa10d75701"},
      {0xCB62, "21954bfa62d34f060009092a666f2aea8bd8"},
      {0x150D, "e52134d4cbfe21f2c136ffe1c9"}}},
    {"Yellow IT", false, 0x0248, 0x3E82, 0x66F4, 0xCFC9, 0xD734, 0xD05B, 0xD35A, 0xD36B, 0xD70F, 0xD71E, 0xD736, 0xD434,
     0xC1F2, 0xD167, 0xD177, 0xD362, 0xD88B, 0xD8A8, 0x4B95, 0x18, 0x03,
     {{0x245, "cd641ecd2834cd6f1ecde10ffac9cfa7c2c303cd"},
      {0x3E82, "f0b8f578e0b8ea0020cd963e"},
      {0x3F38, "e08c21653fcd523f2116cfcb"},
      {0x626EE, "fa09c1fe04c0cd273c3e01ea11cf3e21c3383ff9"},
      {0x710B5, "7eea1ed72136d7cbde23cbfe"},
      {0x710FC, "213dcd36ff23fa0fd75ffa10d75701"},
      {0xCB62, "21954bfa62d34f060009092a666f2aea8bd8"},
      {0x1510, "e52134d4cbfe21f2c136ffe1c9"}}},
    {"Yellow ES", false, 0x0248, 0x3E89, 0x66F4, 0xCFC9, 0xD734, 0xD05B, 0xD35A, 0xD36B, 0xD70F, 0xD71E, 0xD736, 0xD434,
     0xC1F2, 0xD167, 0xD177, 0xD362, 0xD88B, 0xD8A8, 0x4B95, 0x18, 0x03,
     {{0x245, "cd631ecd2f34cd6e1ecde10ffac9cfa7c2c303cd"},
      {0x3E89, "f0b8f578e0b8ea0020cd9d3e"},
      {0x3F3F, "e08c216c3fcd593f2116cfcb"},
      {0x626EE, "fa09c1fe04c0cd2e3c3e01ea11cf3e21c33f3ff9"},
      {0x710B8, "7eea1ed72136d7cbde23cbfe"},
      {0x710FF, "213dcd36ff23fa0fd75ffa10d75701"},
      {0xCB62, "21954bfa62d34f060009092a666f2aea8bd8"},
      {0x1510, "e52134d4cbfe21f2c136ffe1c9"}}},
    {"Red / Blue", false, 0x0405, 0x35D6, 0x651C, 0xCFC5, 0xD730, 0xD057, 0xD356, 0xD367, 0xD70B, 0xD71A, 0xD732, -1, -1,
     0xD163, 0xD173, 0xD35E, 0xD887, 0xD8A4, 0x4EEB, 0x18, 0x03,
     {{0x402, "cdaf20cdba20fa36d7cb77c49e03fac5cfa7c2b5"},
      {0x35D6, "f0b8f578e0b8ea002001e435"},
      {0x3EF5, "e08c21223fcd0f3f2111cfcb"},
      {0x62516, "fa09c1fe04c0cd3c3c3e01ea0ccf3e1fc3f53ef9"},
      {0x7102B, "7eea1ad72132d7cbde23cbfe"},
      {0x71070, "213dcd36ff23fa0bd75ffa0cd75701"},
      {0xCEB8, "21eb4efa5ed34f060009092a666f2aea87d8"}}},
    {"PureRGB", false, 0x03D7, 0x3297, 0x46B5, 0xCFC5, WRAMX(1, 0xD738), WRAMX(1, 0xD057), WRAMX(1, 0xD35E),
     WRAMX(1, 0xD36F), WRAMX(1, 0xD713), WRAMX(1, 0xD722), WRAMX(1, 0xD73A), -1, -1, WRAMX(1, 0xD16B), WRAMX(1, 0xD17B),
     WRAMX(1, 0xD366), WRAMX(1, 0xD88F), WRAMX(1, 0xD8AC), 0x484A, 0x33, 0x2C,
     {{0x3D6, "d721bd680635c7cdb01efa3ed7cb7728"},
      {0x3297, "f0b8f578cda332cda932c178"},
      {0xCC6AF, "fa09c1fe04c0cd9b363e05c37129"},
      {0x70AE8, "7eea22d7213ad7cbde23cbfe"},
      {0x70B2D, "213dcd3eff22fa13d75ffa14d757"},
      {0xB076A, "214a484f060009092a666f2aea8fd8"}}},
    {"Crystal", true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0,
     {{0x25 * 0x4000 + (0x7C30 - 0x4000), "fa5cd4a7c8215ed42a666ffa5dd4cd1f"}, // RunMemScript
      {0x2F * 0x4000 + (0x41A9 - 0x4000), "470f1c00499185"},                   // PCScript
      {0x03 * 0x4000 + (0x4AA3 - 0x4000), "7c0e0016300f3500"},                 // FlyScript
      {0x25 * 0x4000 + (0x68C7 - 0x4000), "2152d47ea7c835c837c9"},             // CheckWildEncounterCooldown
      {0x24 * 0x4000 + (0x5C5E - 0x4000), "010e030f"}}},                        // Flypoints: NEW BARK, CHERRYGROVE
};

/* Crystal (rev 0, rev 1, Australia: one set of addresses) */
#define CR_QUEUE WRAMX(1, 0xD45C) /* wMapReentryScriptQueueFlag, Bank, Address (lo, hi) */
#define CR_PC_BANK 0x2F
#define CR_PC_SCRIPT 0x41A9
#define CR_FLY_BANK 0x03
#define CR_FLY_SCRIPT 0x4AA3
#define CR_SCRIPT_RUNNING WRAMX(1, 0xD438)
#define CR_MAP_EVENT_STATUS WRAMX(1, 0xD433)
#define CR_BATTLE_MODE WRAMX(1, 0xD22D)
#define CR_DEFAULT_SPAWN WRAMX(1, 0xD001)
#define CR_VISITED_SPAWNS WRAMX(1, 0xDCA5)
#define CR_JOHTO_BADGES WRAMX(1, 0xD857) /* bit 5: ENGINE_STORMBADGE */
#define CR_ENVIRONMENT WRAMX(1, 0xD19A)  /* TOWN 1, ROUTE 2 */
#define CR_PARTY_COUNT WRAMX(1, 0xDCD7)  /* then wPartySpecies */
#define CR_PARTY_MON1_MOVES WRAMX(1, 0xDCE1)
#define CR_PARTY_MON_SIZE 0x30
#define CR_EGG 0xFD
#define CR_COOLDOWN WRAMX(1, 0xD452)
#define CR_MAP_GROUP WRAMX(1, 0xDCB5)
#define CR_BACKUP_MAP_GROUP WRAMX(1, 0xDCAD)
#define CR_FLYPOINTS (0x24 * 0x4000 + (0x5C5E - 0x4000))
#define CR_KANTO_LANDMARK 47
#define CR_KANTO_FLYPOINT 12
#define CR_SPAWN_INDIGO 13

#define G1_JOY_IGNORE 0xCD6B /* the same in every Gen 1 cart */
#define G1_PARTY_MON_SIZE 44
#define G1_NUM_CITY_MAPS 11

/* ---- memory ---- */
static bool rd(struct mCore *c, uint32_t a, uint8_t *out, size_t n) {
  if (a >= 0x10000) {
    unsigned bank = a >> 16, addr = a & 0xFFFF;
    struct GB *gb = c->board;
    if (bank > 7 || addr < 0xD000 || addr >= 0xE000 || n > 0xE000u - addr || !gb->memory.wram)
      return false;
    memcpy(out, gb->memory.wram + bank * 0x1000 + addr - 0xD000, n);
    return true;
  }
  for (size_t i = 0; i < n; i++)
    out[i] = (uint8_t)c->busRead8(c, a + (uint32_t)i);
  return true;
}
static int u8(struct mCore *c, uint32_t a) {
  uint8_t v = 0;
  return rd(c, a, &v, 1) ? v : 0;
}
static void put(struct mCore *c, uint32_t a, uint8_t v) { pk_gb_write(c, a, &v, 1); }
static const uint8_t *rom_at(const uint8_t *rom, size_t len, uint32_t off, size_t n) {
  return rom && off <= len && n <= len - off ? rom + off : NULL;
}

/* ---- finding the cart's config ---- */
static int hexval(char ch) { return ch <= '9' ? ch - '0' : (ch | 32) - 'a' + 10; }
static bool code_matches(const struct gb_field *f, const uint8_t *rom, size_t len) {
  int n = 0;
  for (int i = 0; i < 8 && f->sigs[i].hex; i++, n++) {
    size_t bytes = strlen(f->sigs[i].hex) / 2;
    const uint8_t *p = rom_at(rom, len, f->sigs[i].off, bytes);
    if (!p)
      return false;
    for (size_t k = 0; k < bytes; k++)
      if (p[k] != (hexval(f->sigs[i].hex[2 * k]) << 4 | hexval(f->sigs[i].hex[2 * k + 1])))
        return false;
  }
  return n > 0;
}
const struct gb_field *gb_field_find(const uint8_t *rom, size_t len) {
  for (size_t i = 0; i < sizeof GB_FIELDS / sizeof *GB_FIELDS; i++)
    if (code_matches(&GB_FIELDS[i], rom, len))
      return &GB_FIELDS[i];
  return NULL;
}
const char *gb_field_name(const struct gb_field *f) { return f ? f->name : "none"; }

/* ---- Gen 1 ---- */
// wFontLoaded (right before wWalkCounter in every cart) bit 0: a text box is up.
static bool g1_free(struct mCore *c, const struct gb_field *f) {
  return u8(c, f->walkCounter) == 0 && (u8(c, f->walkCounter - 1) & 1) == 0 && u8(c, G1_JOY_IGNORE) == 0 &&
         (u8(c, f->flags5) & 0xA0) == 0 && u8(c, f->inBattle) == 0 && (u8(c, f->flags6) & 0x1C) == 0;
}
static int g1_flyer(struct mCore *c, const struct gb_field *f) {
  int n = u8(c, f->partyCount);
  if (n > 6)
    n = 6;
  for (int i = 0; i < n; i++) {
    uint8_t m[4];
    if (rd(c, f->partyMoves + (uint32_t)(i * G1_PARTY_MON_SIZE), m, 4))
      for (int k = 0; k < 4; k++)
        if (m[k] == FLY_MOVE_ID)
          return i;
  }
  return -1;
}
static uint8_t g1_pika[2];
static bool g1_pikaSaved;

/* ---- Crystal ---- */
static bool cr_free(struct mCore *c) {
  return u8(c, CR_SCRIPT_RUNNING) == 0 && u8(c, CR_MAP_EVENT_STATUS) == 0 && u8(c, CR_BATTLE_MODE) == 0 &&
         u8(c, CR_QUEUE) == 0;
}
static void cr_queue(struct mCore *c, uint8_t bank, uint16_t addr) {
  uint8_t q[3] = {bank, (uint8_t)(addr & 0xFF), (uint8_t)(addr >> 8)};
  pk_gb_write(c, CR_QUEUE + 1, q, 3);
  put(c, CR_QUEUE, 1);
}
static int cr_flyer(struct mCore *c) {
  int n = u8(c, CR_PARTY_COUNT);
  if (n > 6)
    n = 6;
  uint8_t species[6] = {0};
  rd(c, CR_PARTY_COUNT + 1, species, 6);
  for (int i = 0; i < n; i++) {
    uint8_t m[4];
    if (species[i] == CR_EGG || !rd(c, CR_PARTY_MON1_MOVES + (uint32_t)(i * CR_PARTY_MON_SIZE), m, 4))
      continue;
    for (int k = 0; k < 4; k++)
      if (m[k] == FLY_MOVE_ID)
        return i;
  }
  return -1;
}
static bool cr_visited(struct mCore *c, int spawn) { return (u8(c, CR_VISITED_SPAWNS + (uint32_t)(spawn / 8)) >> (spawn % 8)) & 1; }
static int cr_landmark_of(int group, int num) {
  int key = group << 8 | num;
  for (int i = 0; i < pd_gb_landmarks_crystal_count; i++)
    if (pd_gb_landmarks_crystal[i][0] == key)
      return pd_gb_landmarks_crystal[i][1];
  return -1;
}
/* gen2Landmark: a SPECIAL (0) landmark is the backup map's, as the game's own town map does. */
static int cr_landmark(struct mCore *c) {
  uint8_t m[2], b[2];
  if (!rd(c, CR_MAP_GROUP, m, 2) || !rd(c, CR_BACKUP_MAP_GROUP, b, 2))
    return 0;
  int lm = cr_landmark_of(m[0], m[1]);
  if (lm < 0)
    return 0;
  if (lm == 0) {
    lm = cr_landmark_of(b[0], b[1]);
    return lm < 0 ? 0 : lm;
  }
  return lm;
}
/* The Fly map's points as (landmark, spawn), the ROM's own table; FlyMap shows Kanto's from a Kanto landmark once
 * Indigo Plateau is visited, else Johto's. Returns how many, into [lm] / [sp]. */
static int cr_shown_points(struct mCore *c, const uint8_t *rom, size_t len, int *lm, int *sp) {
  const uint8_t *raw = rom_at(rom, len, CR_FLYPOINTS, 64);
  if (!raw)
    return 0;
  int all = 0;
  while (all < 32 && raw[2 * all] != 0xFF)
    all++;
  bool kanto = cr_landmark(c) >= CR_KANTO_LANDMARK && cr_visited(c, CR_SPAWN_INDIGO);
  int from = kanto ? CR_KANTO_FLYPOINT : 0, to = kanto ? all : (all < CR_KANTO_FLYPOINT ? all : CR_KANTO_FLYPOINT), n = 0;
  for (int i = from; i < to; i++, n++) {
    lm[n] = raw[2 * i];
    sp[n] = raw[2 * i + 1];
  }
  return n;
}

/* ---- the actions ---- */
enum item_result gb_field_pc(struct mCore *c, const struct gb_field *f, bool busy) {
  if (!f)
    return ITEM_FAILED;
  if (f->crystal) {
    if (busy || !cr_free(c))
      return u8(c, CR_BATTLE_MODE) ? ITEM_IN_BATTLE : ITEM_NOT_NOW;
    cr_queue(c, CR_PC_BANK, CR_PC_SCRIPT);
    return ITEM_USED;
  }
  if (u8(c, f->inBattle))
    return ITEM_IN_BATTLE;
  if (busy || !g1_free(c, f))
    return ITEM_NOT_NOW;
  return pk_gb_call_at(c, f->loopAfterWait, f->bankswitch, 0, f->pcBank, f->pcRoutine, 2) ? ITEM_USED : ITEM_NOT_NOW;
}

void gb_field_fly_targets(struct mCore *c, const struct gb_field *f, const uint8_t *rom, size_t len, bool busy,
                          struct fly_targets *out) {
  memset(out, 0, sizeof *out);
  out->flyerSlot = -1;
  if (!f) {
    out->result = FLY_FAILED;
    return;
  }
  if (f->crystal) {
    if (u8(c, CR_BATTLE_MODE)) {
      out->result = FLY_IN_BATTLE;
      return;
    }
    if (busy || !cr_free(c)) {
      out->result = FLY_NOT_NOW;
      return;
    }
    if (!(u8(c, CR_JOHTO_BADGES) & 0x20)) {
      out->result = FLY_NO_BADGE;
      return;
    }
    int env = u8(c, CR_ENVIRONMENT);
    if (env < 1 || env > 2) {
      out->result = FLY_CANT_FLY_HERE;
      return;
    }
    if ((out->flyerSlot = cr_flyer(c)) < 0) {
      out->result = FLY_NO_FLYER;
      return;
    }
    int lm[32], sp[32], n = cr_shown_points(c, rom, len, lm, sp);
    for (int i = 0; i < n && out->count < FLY_MAX_TOWNS; i++)
      if (cr_visited(c, sp[i]))
        out->towns[out->count++] = lm[i];
    out->result = FLY_OK;
    return;
  }
  if (u8(c, f->inBattle)) {
    out->result = FLY_IN_BATTLE;
    return;
  }
  if (busy || !g1_free(c, f)) {
    out->result = FLY_NOT_NOW;
    return;
  }
  if (!(u8(c, f->badges) & 0x04)) {
    out->result = FLY_NO_BADGE;
    return;
  }
  int ts = u8(c, f->tileset);
  if (ts != 0 && ts != 23) {
    out->result = FLY_CANT_FLY_HERE;
    return;
  }
  if ((out->flyerSlot = g1_flyer(c, f)) < 0) {
    out->result = FLY_NO_FLYER;
    return;
  }
  uint8_t v[2] = {0};
  rd(c, f->townsVisited, v, 2);
  int bits = v[0] | v[1] << 8;
  // BuildFlyLocationsList's order: the town map ids, visited ones only.
  for (int i = 0; i < G1_NUM_CITY_MAPS; i++)
    if ((bits >> i) & 1)
      out->towns[out->count++] = i;
  out->result = FLY_OK;
}

enum fly_result gb_field_fly(struct mCore *c, const struct gb_field *f, const uint8_t *rom, size_t len, int mapsec,
                             bool busy) {
  struct fly_targets now;
  gb_field_fly_targets(c, f, rom, len, busy, &now);
  if (now.result != FLY_OK)
    return now.result;
  if (f->crystal) {
    int lm[32], sp[32], n = cr_shown_points(c, rom, len, lm, sp), spawn = -1;
    for (int i = 0; i < n && spawn < 0; i++)
      if (lm[i] == mapsec && cr_visited(c, sp[i]))
        spawn = sp[i];
    if (spawn < 0)
      return FLY_FAILED;
    put(c, CR_DEFAULT_SPAWN, (uint8_t)spawn);
    cr_queue(c, CR_FLY_BANK, CR_FLY_SCRIPT);
    return FLY_OK;
  }
  bool listed = false;
  for (int i = 0; i < now.count; i++)
    listed |= now.towns[i] == mapsec;
  if (!listed)
    return FLY_FAILED;
  put(c, f->destination, (uint8_t)mapsec);
  g1_pikaSaved = false;
  if (f->pikachuFlags >= 0) {
    g1_pika[0] = (uint8_t)u8(c, (uint32_t)f->pikachuFlags);
    g1_pika[1] = (uint8_t)u8(c, (uint32_t)f->pikachuImage);
    g1_pikaSaved = true;
    put(c, (uint32_t)f->pikachuFlags, g1_pika[0] | 0x80);
    put(c, (uint32_t)f->pikachuImage, 0xFF);
  }
  uint32_t flags7 = f->flags6 + 1;
  put(c, flags7, (uint8_t)(u8(c, flags7) | 0x80));
  put(c, f->flags6, (uint8_t)(u8(c, f->flags6) | 0x08));
  return FLY_OK;
}

bool gb_field_taken(struct mCore *c, const struct gb_field *f) {
  if (!f)
    return true;
  if (f->crystal) {
    if (u8(c, CR_QUEUE) == 0)
      return true;
    put(c, CR_QUEUE, 0);
    return false;
  }
  int f6 = u8(c, f->flags6);
  // HandleFlyWarpOrDungeonWarp sets bit 2 on its way; bit 3 still alone = the loop never came by. (A PC call is
  // synchronous: nothing to withdraw.)
  if (!(f6 & 0x08) || (f6 & 0x04))
    return true;
  uint32_t flags7 = f->flags6 + 1;
  put(c, f->flags6, (uint8_t)(f6 & ~0x08));
  put(c, flags7, (uint8_t)(u8(c, flags7) & ~0x80));
  if (f->pikachuFlags >= 0 && g1_pikaSaved) {
    put(c, (uint32_t)f->pikachuFlags, g1_pika[0]);
    put(c, (uint32_t)f->pikachuImage, g1_pika[1]);
  }
  return false;
}

void gb_field_hold_wild(struct mCore *c, const struct gb_field *f) {
  if (!f)
    return;
  if (f->crystal) {
    // A step meets nothing while the cooldown is still 2 or more when it's counted down.
    if (u8(c, CR_COOLDOWN) < 3)
      put(c, CR_COOLDOWN, 5);
    return;
  }
  // wGrassRate shares its bytes with a link battle's enemy name: only out of battle.
  if (u8(c, f->inBattle))
    return;
  if (u8(c, f->grassRate))
    put(c, f->grassRate, 0);
  if (u8(c, f->waterRate))
    put(c, f->waterRate, 0);
}

bool gb_field_release_wild(struct mCore *c, const struct gb_field *f, const uint8_t *rom, size_t len) {
  if (!f || f->crystal)
    return true;
  if (u8(c, f->inBattle) || !g1_free(c, f))
    return false;
  if (u8(c, f->grassRate) || u8(c, f->waterRate))
    return true;
  int map = u8(c, f->curMap);
  const uint8_t *p = rom_at(rom, len, (uint32_t)f->wildBank * 0x4000 + (f->wildDataPointers - 0x4000) + 2 * map, 2);
  if (!p)
    return false;
  int ptr = p[0] | p[1] << 8;
  if (ptr < 0x4000 || ptr > 0x7FFF)
    return true;
  const uint8_t *d = rom_at(rom, len, (uint32_t)f->wildBank * 0x4000 + (uint32_t)(ptr - 0x4000), 22);
  if (!d)
    return false;
  int grass = d[0], water = d[grass ? 21 : 1];
  if (grass)
    put(c, f->grassRate, (uint8_t)grass);
  if (water)
    put(c, f->waterRate, (uint8_t)water);
  return true;
}
