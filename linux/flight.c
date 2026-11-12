/* FREE FLIGHT, FLIGHT AREA and the scenic FLY (flight.h): app/.../companion/data/Flight.kt, FlightArea.kt and ScenicFly.kt
 * over the config's generated flight fields - keep them in step. ROM tables are read from the ROM's bytes, RAM through the
 * bus, the game's functions through pk_call. GPL-3.0. */
#include "flight.h"

#include "../app/src/main/cpp/pk_call.h"
#include <mgba-util/crc32.h>
#include <mgba/core/core.h>
#include <stdio.h>
#include <string.h>

#define GBA_ROM 0x08000000u
#define GBA_ROM_END 0x0A000000u
#define TRAINER_FLAGS_START 0x500 // FlightArea.TRAINER_FLAGS_START
#define MAX_TRAINER 0x400
#define OBJECT_TEMPLATE 24

static const uint8_t *rom_at(const uint8_t *rom, size_t len, uint32_t addr, size_t n) {
  if (addr < GBA_ROM || addr - GBA_ROM > len || len - (addr - GBA_ROM) < n)
    return NULL;
  return rom + (addr - GBA_ROM);
}

static uint32_t le32(const uint8_t *p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }
static bool in_rom(uint32_t a) { return a >= GBA_ROM && a < GBA_ROM_END; }

static bool call(struct mCore *core, uint32_t fn, uint32_t a0, uint32_t *r0) {
  const uint32_t args[4] = {a0, 0, 0, 0};
  uint32_t got = 0;
  if (!pk_call(core, fn, args, &got))
    return false;
  if (r0)
    *r0 = got;
  return true;
}

const char *flight_vehicle_name(int v) {
  static const char *const NAMES[FLIGHT_VEHICLES] = {"BIRD", "AIR BALLOON", "HELICOPTER", "ZEPPELIN"};
  return v >= 0 && v < FLIGHT_VEHICLES ? NAMES[v] : NAMES[0];
}

// FlightFns.functions: the fixed eleven, then the optional ones a record has.
static int functions(const struct pd_config *cfg, uint32_t out[17]) {
  int n = 0;
  const uint32_t fixed[] = {cfg->flightLockControls, cfg->flightUnlockControls, cfg->flightCreateSprite, cfg->flightDestroySprite,
                            cfg->flightStartSpriteAnim, cfg->flightBehaviorAt, cfg->flightCollisionAt, cfg->flightElevationAt,
                            cfg->flightObjectAt, cfg->flightPlaySe, cfg->flightDrawMetatileAt};
  for (size_t i = 0; i < sizeof fixed / sizeof *fixed; i++)
    out[n++] = fixed[i];
  const uint32_t extra[] = {cfg->flightRiderTemplate, cfg->flightRiderGfx, cfg->flightDrawView, cfg->flightBirdPalette,
                            cfg->flightFollowerRemove, cfg->flightFollowerUpdate};
  for (size_t i = 0; i < sizeof extra / sizeof *extra; i++)
    if (extra[i])
      out[n++] = extra[i];
  return n;
}

bool flight_supported(const struct pd_config *cfg, const uint8_t *rom, size_t len, bool retail) {
  if (!cfg || !cfg->flightObjectEvents || !retail || !fly_supported(cfg, rom, len))
    return false;
  uint32_t fns[17];
  int n = functions(cfg, fns);
  for (int i = 0; i < n; i++) {
    const uint8_t *code = rom_at(rom, len, fns[i] & ~1u, 32);
    if (!code || doCrc32(code, 32) != cfg->flightCrcs[i])
      return false;
  }
  return true;
}

void flight_words(const struct pd_config *cfg, int vehicle, uint32_t w[PK_FLIGHT_WORDS]) {
  memset(w, 0, sizeof(uint32_t) * PK_FLIGHT_WORDS);
  w[PK_FLIGHT_OBJECT_EVENTS] = cfg->flightObjectEvents;
  w[PK_FLIGHT_PLAYER_AVATAR] = cfg->flightPlayerAvatar;
  w[PK_FLIGHT_SPRITES] = cfg->flightSprites;
  w[PK_FLIGHT_VMAP] = cfg->flightVmap;
  w[PK_FLIGHT_CONNECTION_FLAGS] = cfg->flightConnectionFlags;
  w[PK_FLIGHT_SAVE_BLOCK2_PTR] = cfg->flightSaveBlock2Ptr;
  w[PK_FLIGHT_MAIN] = cfg->flightMain;
  w[PK_FLIGHT_CB2_OVERWORLD] = cfg->flightCb2Overworld;
  w[PK_FLIGHT_LOCK_BYTE] = cfg->flightLockByte;
  w[PK_FLIGHT_PALETTE_FADE] = cfg->flightPaletteFade;
  w[PK_FLIGHT_LOCK_CONTROLS] = cfg->flightLockControls;
  w[PK_FLIGHT_UNLOCK_CONTROLS] = cfg->flightUnlockControls;
  w[PK_FLIGHT_CREATE_SPRITE] = cfg->flightCreateSprite;
  w[PK_FLIGHT_DESTROY_SPRITE] = cfg->flightDestroySprite;
  w[PK_FLIGHT_START_SPRITE_ANIM] = cfg->flightStartSpriteAnim;
  w[PK_FLIGHT_BIRD_TEMPLATE] = cfg->flightBirdTemplate;
  w[PK_FLIGHT_BEHAVIOR_AT] = cfg->flightBehaviorAt;
  w[PK_FLIGHT_COLLISION_AT] = cfg->flightCollisionAt;
  w[PK_FLIGHT_ELEVATION_AT] = cfg->flightElevationAt;
  w[PK_FLIGHT_OBJECT_AT] = cfg->flightObjectAt;
  w[PK_FLIGHT_PLAY_SE] = cfg->flightPlaySe;
  w[PK_FLIGHT_SE_FLY] = (uint32_t)cfg->flightSeFly;
  w[PK_FLIGHT_SE_FAILURE] = (uint32_t)cfg->flightSeFailure;
  w[PK_FLIGHT_WALK_FAST] = (uint32_t)cfg->flightWalkFast;
  w[PK_FLIGHT_WALK_FASTER] = (uint32_t)cfg->flightWalkFaster;
  w[PK_FLIGHT_SPRITE_OFFSET] = cfg->flightSpriteOffset;
  w[PK_FLIGHT_WALK_CURRENT] = (uint32_t)cfg->flightWalkCurrent;
  w[PK_FLIGHT_WALK_SLIDE] = (uint32_t)cfg->flightWalkSlide;
  w[PK_FLIGHT_MAP_HEADER] = cfg->flightMapHeader;
  w[PK_FLIGHT_MAP_GROUPS] = cfg->flightMapGroups;
  w[PK_FLIGHT_DRAW_METATILE] = cfg->flightDrawMetatileAt;
  w[PK_FLIGHT_RIDER_TEMPLATE] = cfg->flightRiderTemplate;
  w[PK_FLIGHT_RIDER_GFX] = cfg->flightRiderGfx;
  w[PK_FLIGHT_SPRITE_DUMMY] = cfg->flightSpriteDummy;
  w[PK_FLIGHT_RIDER_ANIM] = (uint32_t)cfg->flightRiderAnim;
  w[PK_FLIGHT_SCRATCH] = cfg->flightScratch;
  w[PK_FLIGHT_DRAW_VIEW] = cfg->flightDrawView;
  w[PK_FLIGHT_SPRITE_OFFSET_Y] = cfg->flightSpriteOffsetY;
  w[PK_FLIGHT_MAP_GROUPS_XOR] = cfg->flightMapGroupsXor;
  w[PK_FLIGHT_BIRD_PALETTE] = cfg->flightBirdPalette;
  w[PK_FLIGHT_BIRD_PALETTE_TAG] = (uint32_t)cfg->flightBirdPaletteTag;
  w[PK_FLIGHT_OBJ_SPRITE_ID] = (uint32_t)cfg->flightObjSpriteId;
  w[PK_FLIGHT_FOLLOWER_REMOVE] = cfg->flightFollowerRemove;
  w[PK_FLIGHT_FOLLOWER_UPDATE] = cfg->flightFollowerUpdate;
  w[PK_FLIGHT_MAPGRID_UNDEFINED] = (uint32_t)cfg->flightMapGridUndefined;
  w[PK_FLIGHT_VEHICLE] = vehicle >= 0 && vehicle < FLIGHT_VEHICLES ? (uint32_t)vehicle : 0;
  // noFly: a u16 each, 0xFFFF where none (the generated list's 0 = none).
  uint32_t ids[4];
  for (int i = 0; i < 4; i++)
    ids[i] = cfg->flightNoFly[i] ? (uint32_t)cfg->flightNoFly[i] & 0xFFFF : 0xFFFF;
  w[PK_FLIGHT_NO_FLY] = ids[0] | ids[1] << 16;
  w[PK_FLIGHT_NO_FLY + 1] = ids[2] | ids[3] << 16;
  // A bit per behaviour a player may land on (the generated list is padded with 0, which every record has anyway).
  for (int i = 0; i < 32; i++) {
    int b = cfg->flightLandOk[i];
    if (b >= 0 && b < 256)
      w[PK_FLIGHT_LAND_OK + b / 32] |= 1u << (b % 32);
  }
}

enum fly_result flight_check(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                             const struct pd_snapshot *snap, bool busy, bool battle) {
  static struct fly_targets t;
  fly_targets(core, cfg, rom, len, snap, busy, battle, &t);
  return t.result;
}

const char *flight_reason(int code) {
  switch (code) {
  case PK_FLIGHT_OK: return NULL;
  case PK_FLIGHT_BUSY:
  case PK_FLIGHT_NOT_NOW: return fly_reason(FLY_NOT_NOW);
  case PK_FLIGHT_NOT_ON_FOOT: return "You can only take off from land.";
  default: return fly_reason(FLY_FAILED);
  }
}

// ScenicFly.here: SaveBlock1's pos (x, y: s16) and location (group, num) after it.
bool flight_here(struct mCore *core, const struct pd_config *cfg, struct flight_spot *out) {
  uint32_t sb1 = cfg->flightSaveBlockStatic ? cfg->flightSaveBlock1Ptr : core->busRead32(core, cfg->flightSaveBlock1Ptr);
  if (sb1 < 0x02000000u || sb1 >= 0x04000000u)
    return false;
  uint32_t p = sb1 + (uint32_t)cfg->flightSaveBlock1PosOff;
  out->x = (int16_t)core->busRead16(core, p);
  out->y = (int16_t)core->busRead16(core, p + 2);
  out->map = core->busRead8(core, p + 4) << 8 | core->busRead8(core, p + 5);
  return true;
}

// The sections FLY could ever offer: the table's filled entries, then the ones past it (Fly.kt's flySections).
static int fly_sections(const struct pd_config *cfg, const uint8_t *rom, size_t len, int out[FLY_MAX_TOWNS + 4]) {
  const uint8_t *t = rom_at(rom, len, cfg->flyDestinations, (size_t)cfg->flyCount * 3);
  int n = 0;
  if (!t)
    return 0;
  for (int i = 0; i < cfg->flyCount && n < FLY_MAX_TOWNS; i++)
    if (t[i * 3] | t[i * 3 + 1] | t[i * 3 + 2])
      out[n++] = cfg->flyFirstMapsec + i;
  for (int i = 0; i < 4 && cfg->flyExtra[i]; i++)
    out[n++] = cfg->flyExtra[i];
  return n;
}

bool flight_destination(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len, int mapsec,
                        struct flight_spot *out) {
  int heal = -1;
  if (cfg->flyHealTo && mapsec == cfg->flyGenderMapsec) {
    uint32_t sb2 = cfg->flySaveBlock2Static ? cfg->flySaveBlock2Ptr : core->busRead32(core, cfg->flySaveBlock2Ptr);
    heal = sb2 && (core->busRead8(core, sb2 + 8) & 1) ? cfg->flyFemaleHeal : cfg->flyMaleHeal;
  }
  for (int k = 0; heal < 0 && cfg->flyHealTo && k < 4; k++)
    if (cfg->flySpecial[k][1] && cfg->flySpecial[k][0] == mapsec)
      heal = cfg->flySpecial[k][1];
  if (heal < 0) {
    int i = mapsec - cfg->flyFirstMapsec;
    const uint8_t *e = i >= 0 && i < cfg->flyCount ? rom_at(rom, len, cfg->flyDestinations + (uint32_t)i * 3, 3) : NULL;
    if (!e)
      return false;
    heal = e[2];
  }
  if (heal < 1 || heal > cfg->flightHealLocationCount)
    return false;
  const uint8_t *e = rom_at(rom, len, cfg->flightHealLocations + (uint32_t)(heal - 1) * (uint32_t)cfg->flightHealLocationSize, 6);
  if (!e)
    return false;
  out->map = e[0] << 8 | e[1];
  out->x = (int16_t)(e[2] | e[3] << 8);
  out->y = (int16_t)(e[4] | e[5] << 8);
  return true;
}

// ---- FLIGHT AREA ----

uint32_t flight_save_key(struct mCore *core, const struct pd_config *cfg) {
  uint32_t sb2 = cfg->flightSaveBlockStatic ? cfg->flightSaveBlock2Ptr : core->busRead32(core, cfg->flightSaveBlock2Ptr);
  if (sb2 < 0x02000000u || sb2 >= 0x04000000u)
    return 0;
  uint8_t key[13];
  bool zero = true, ff = true;
  for (int i = 0, k = 0; i < 14; i++) {
    if (i == 9)
      continue; // the special-warp flags
    key[k] = core->busRead8(core, sb2 + (uint32_t)i);
    zero &= key[k] == 0;
    ff &= key[k] == 0xFF;
    k++;
  }
  if (zero || ff)
    return 0;
  uint32_t crc = doCrc32(key, sizeof key);
  return crc ? crc : 1;
}

void flight_area_clear(struct flight_area *a, uint32_t key) {
  memset(a, 0, sizeof *a);
  a->key = key;
  a->sent = -1;
}

bool flight_area_has(const struct flight_area *a, int map) {
  return map >= 0 && map < PK_FLIGHT_AREA_BYTES * 8 && (a->bits[map >> 3] >> (map & 7) & 1);
}

bool flight_area_add(struct flight_area *a, int map) {
  if (map < 0 || map >= PK_FLIGHT_AREA_BYTES * 8 || flight_area_has(a, map))
    return false;
  a->bits[map >> 3] |= (uint8_t)(1 << (map & 7));
  a->count++;
  a->dirty = true;
  return true;
}

void flight_area_parse(struct flight_area *a, const char *text, size_t n) {
  int g = -1, v = 0;
  bool dot = false;
  for (size_t i = 0; i <= n; i++) {
    char ch = i < n ? text[i] : '\n';
    if (ch >= '0' && ch <= '9') {
      v = v * 10 + (ch - '0');
    } else if (ch == '.' && !dot) {
      g = v;
      v = 0;
      dot = true;
    } else if (ch == '\n' || ch == '\r') {
      if (dot && g >= 0 && g < 256 && v < 256)
        flight_area_add(a, g << 8 | v);
      g = -1;
      v = 0;
      dot = false;
    }
  }
  a->dirty = false;
}

size_t flight_area_format(const struct flight_area *a, char *out, size_t n) {
  size_t at = 0;
  for (int id = 0; id < PK_FLIGHT_AREA_BYTES * 8; id++) {
    if (!flight_area_has(a, id))
      continue;
    int w = snprintf(out + at, at < n ? n - at : 0, "%s%d.%d", at ? "\n" : "", id >> 8, id & 0xFF);
    if (w < 0 || at + (size_t)w >= n)
      break;
    at += (size_t)w;
  }
  return at;
}

// Every map in gMapGroups: its connections (both ways) and the trainers its people battle (FlightArea.readMaps).
#define MAX_MAPS 1024
#define MAP_CONNS 16
#define MAP_TRAINERS 32
#define MAP_IDS (64 * 256)
struct map_info {
  int id, nconn, ntrainer;
  uint16_t conn[MAP_CONNS];
  uint16_t trainer[MAP_TRAINERS];
};
static struct map_info maps[MAX_MAPS];
static int16_t mapIndex[MAP_IDS];
static int mapCount;

// A map's header in gMapGroups, from the ROM's bytes; 0 = none.
static uint32_t map_header(const struct pd_config *cfg, const uint8_t *rom, size_t len, int group, int num) {
  if (group < 0 || group >= 64 || num < 0 || num >= cfg->flightMapGroupSizes[group])
    return 0;
  const uint8_t *g = rom_at(rom, len, cfg->flightMapGroups + 4u * (uint32_t)group, 4);
  if (!g)
    return 0;
  const uint8_t *h = rom_at(rom, len, (le32(g) ^ cfg->flightMapGroupsXor) + 4u * (uint32_t)num, 4);
  return h && in_rom(le32(h)) ? le32(h) : 0;
}

static void read_maps(const struct pd_config *cfg, const uint8_t *rom, size_t len) {
  mapCount = 0;
  memset(mapIndex, 0xFF, sizeof mapIndex);
  // The trainer id's place in trainerbattle (+2, or +3 for expansion 1.12's): voted on by where the first text pointer's
  // top byte falls.
  static uint8_t scripts[MAX_MAPS][MAP_TRAINERS][2][2]; // per map: the bytes at +2 / +3 and +3 / +4
  static int nscripts[MAX_MAPS];
  int votesOld = 0, votesNew = 0;
  for (int g = 0; g < 64; g++)
    for (int n = 0; n < cfg->flightMapGroupSizes[g] && mapCount < MAX_MAPS; n++) {
      uint32_t h = map_header(cfg, rom, len, g, n);
      const uint8_t *head = h ? rom_at(rom, len, h, 0x10) : NULL;
      if (!head)
        continue;
      struct map_info *m = &maps[mapCount];
      memset(m, 0, sizeof *m);
      m->id = g << 8 | n;
      nscripts[mapCount] = 0;
      const uint8_t *c = rom_at(rom, len, le32(head + 0xC), 8);
      if (c && in_rom(le32(head + 0xC))) {
        uint32_t cn = le32(c);
        const uint8_t *e = cn >= 1 && cn <= 16 ? rom_at(rom, len, le32(c + 4), 12 * cn) : NULL;
        for (uint32_t k = 0; e && k < cn; k++)
          if (e[12 * k] >= 1 && e[12 * k] <= 4 && m->nconn < MAP_CONNS)
            m->conn[m->nconn++] = (uint16_t)(e[12 * k + 8] << 8 | e[12 * k + 9]);
      }
      const uint8_t *ev = rom_at(rom, len, le32(head + 4), 8);
      if (ev && in_rom(le32(head + 4))) {
        int count = ev[0];
        const uint8_t *objs = count >= 1 && count <= 64 ? rom_at(rom, len, le32(ev + 4), (size_t)OBJECT_TEMPLATE * count) : NULL;
        for (int k = 0; objs && k < count; k++) {
          uint32_t sp = le32(objs + OBJECT_TEMPLATE * k + 0x10);
          const uint8_t *sb = in_rom(sp) ? rom_at(rom, len, sp, 13) : NULL;
          if (!sb || sb[0] != 0x5C)
            continue;
          votesOld += sb[7] == 0x08 || sb[7] == 0x09;
          votesNew += sb[8] == 0x08 || sb[8] == 0x09;
          int i = nscripts[mapCount];
          if (i < MAP_TRAINERS) {
            scripts[mapCount][i][0][0] = sb[2];
            scripts[mapCount][i][0][1] = sb[3];
            scripts[mapCount][i][1][0] = sb[3];
            scripts[mapCount][i][1][1] = sb[4];
            nscripts[mapCount]++;
          }
        }
      }
      mapIndex[m->id] = (int16_t)mapCount;
      mapCount++;
    }
  int at = votesNew > votesOld ? 1 : 0;
  for (int i = 0; i < mapCount; i++)
    for (int k = 0; k < nscripts[i]; k++) {
      int t = scripts[i][k][at][0] | scripts[i][k][at][1] << 8;
      if (t >= 1 && t < MAX_TRAINER)
        maps[i].trainer[maps[i].ntrainer++] = (uint16_t)t;
    }
  // Connections both ways: a map joined only one way still lies between the two.
  for (int i = 0; i < mapCount; i++)
    for (int k = 0; k < maps[i].nconn; k++) {
      int j = maps[i].conn[k] < MAP_IDS ? mapIndex[maps[i].conn[k]] : -1;
      if (j < 0)
        continue;
      bool has = false;
      for (int q = 0; q < maps[j].nconn; q++)
        has |= maps[j].conn[q] == maps[i].id;
      if (!has && maps[j].nconn < MAP_CONNS)
        maps[j].conn[maps[j].nconn++] = (uint16_t)maps[i].id;
    }
}

static bool no_fly(const struct pd_config *cfg, int id) {
  for (int i = 0; i < 4; i++)
    if (cfg->flightNoFly[i] && cfg->flightNoFly[i] == id)
      return true;
  return false;
}

static int index_of(int id) { return id >= 0 && id < MAP_IDS ? mapIndex[id] : -1; }

void flight_area_seed(struct flight_area *a, struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len) {
  read_maps(cfg, rom, len);
  static bool town[MAX_MAPS], visited[MAX_MAPS], out[MAX_MAPS];
  memset(town, 0, sizeof town);
  memset(visited, 0, sizeof visited);
  memset(out, 0, sizeof out);
  int secs[FLY_MAX_TOWNS + 4], ns = fly_sections(cfg, rom, len, secs);
  for (int i = 0; i < ns; i++) {
    struct flight_spot d;
    if (!flight_destination(core, cfg, rom, len, secs[i], &d))
      continue;
    int k = index_of(d.map);
    if (k < 0)
      continue;
    town[k] = true;
    uint32_t type = 0;
    if (!call(core, cfg->flyGetMapsecType, (uint32_t)secs[i], &type))
      return; // try again another time
    for (int f = 0; f < 4; f++)
      if (cfg->flyTypes[f] && (uint32_t)cfg->flyTypes[f] == type)
        visited[k] = out[k] = true;
  }
  for (int i = 0; i < mapCount; i++)
    if (flight_area_has(a, maps[i].id))
      out[i] = true;
  // Every map on a shortest way between two visited towns through maps that aren't towns.
  static int16_t parent[MAX_MAPS], queue[MAX_MAPS];
  for (int t = 0; t < mapCount; t++) {
    if (!visited[t])
      continue;
    memset(parent, 0xFF, sizeof parent);
    parent[t] = (int16_t)t;
    int head = 0, tail = 0;
    queue[tail++] = (int16_t)t;
    while (head < tail) {
      int cur = queue[head++];
      for (int k = 0; k < maps[cur].nconn; k++) {
        int nb = index_of(maps[cur].conn[k]);
        if (nb < 0 || parent[nb] >= 0 || no_fly(cfg, maps[nb].id))
          continue;
        parent[nb] = (int16_t)cur;
        if (town[nb]) {
          if (visited[nb])
            for (int p = cur; p != t; p = parent[p])
              out[p] = true;
          continue;
        }
        if (tail < MAX_MAPS)
          queue[tail++] = (int16_t)nb;
      }
    }
  }
  // Each map with a beaten trainer that touches what's seen by then (a chain of them too).
  static bool fought[MAX_MAPS];
  for (int i = 0; i < mapCount; i++) {
    fought[i] = false;
    if (out[i] || no_fly(cfg, maps[i].id))
      continue;
    for (int k = 0; k < maps[i].ntrainer && !fought[i]; k++) {
      uint32_t r = 0;
      if (call(core, cfg->flyFlagGet, TRAINER_FLAGS_START + maps[i].trainer[k], &r) && (r & 0xFF))
        fought[i] = true;
    }
  }
  for (bool more = true; more;) {
    more = false;
    for (int i = 0; i < mapCount; i++) {
      if (!fought[i])
        continue;
      for (int k = 0; k < maps[i].nconn; k++) {
        int nb = index_of(maps[i].conn[k]);
        if (nb >= 0 && out[nb]) {
          out[i] = true;
          fought[i] = false;
          more = true;
          break;
        }
      }
    }
  }
  for (int i = 0; i < mapCount; i++)
    if (out[i])
      flight_area_add(a, maps[i].id);
  a->seeded = true;
}

// ---- The scenic FLY's route ----

#define WORLD_MAPS 200
#define MAX_SIDE 512
#define MAX_CELLS (640 * 640)
#define NO_CELL 0xFF
#define HOLE_CELL 0xFE
enum { SOUTH = 1, NORTH = 2, WEST = 3, EAST = 4 };

struct conn {
  int dir, offset, to; // to: map id
};
struct placed {
  int id, x, y, w, h, nconn;
  struct conn conn[MAP_CONNS];
  uint32_t data; // the layout's squares (ROM)
};
struct edge {
  int dir, to, x, y; // to: index in world
  bool there;
};
static struct placed world[WORLD_MAPS];
static int worldCount;
static struct edge edges[WORLD_MAPS][MAP_CONNS];
static int nedges[WORLD_MAPS];
static uint8_t owner[MAX_CELLS], cameFrom[MAX_CELLS];
static int gx0, gy0, gw, gh;

static int world_index(int id) {
  for (int i = 0; i < worldCount; i++)
    if (world[i].id == id)
      return i;
  return -1;
}

// The layout's size (MapLayout: width, height at +0 / +4, the squares at +0xC).
static bool layout_of(const struct pd_config *cfg, const uint8_t *rom, size_t len, int id, int *w, int *h, uint32_t *data) {
  uint32_t hd = map_header(cfg, rom, len, id >> 8, id & 0xFF);
  const uint8_t *head = hd ? rom_at(rom, len, hd, 4) : NULL;
  const uint8_t *lay = head && in_rom(le32(head)) ? rom_at(rom, len, le32(head), 16) : NULL;
  if (!lay)
    return false;
  *w = (int)le32(lay);
  *h = (int)le32(lay + 4);
  if (data)
    *data = le32(lay + 12);
  return *w >= 1 && *w <= MAX_SIDE && *h >= 1 && *h <= MAX_SIDE;
}

// Every map joined to [start] by connections, laid out from it at (0, 0) (ScenicFly.read).
static bool read_world(const struct pd_config *cfg, const uint8_t *rom, size_t len, int start, const struct flight_area *area) {
  static int qid[WORLD_MAPS * 4], qx[WORLD_MAPS * 4], qy[WORLD_MAPS * 4];
  int head = 0, tail = 0;
  worldCount = 0;
  qid[tail] = start, qx[tail] = 0, qy[tail] = 0, tail++;
  while (head < tail && worldCount < WORLD_MAPS) {
    int id = qid[head], x = qx[head], y = qy[head];
    head++;
    if (world_index(id) >= 0 || no_fly(cfg, id) || (id != start && area && !flight_area_has(area, id)))
      continue;
    int w, h;
    uint32_t data;
    if (!layout_of(cfg, rom, len, id, &w, &h, &data))
      continue;
    struct placed *p = &world[worldCount++];
    *p = (struct placed){.id = id, .x = x, .y = y, .w = w, .h = h, .data = data};
    uint32_t hd = map_header(cfg, rom, len, id >> 8, id & 0xFF);
    const uint8_t *hh = rom_at(rom, len, hd, 0x10);
    const uint8_t *c = hh && in_rom(le32(hh + 0xC)) ? rom_at(rom, len, le32(hh + 0xC), 8) : NULL;
    uint32_t cn = c ? le32(c) : 0;
    const uint8_t *e = cn >= 1 && cn <= 16 && in_rom(le32(c + 4)) ? rom_at(rom, len, le32(c + 4), 12 * cn) : NULL;
    for (uint32_t k = 0; e && k < cn; k++) {
      int dir = e[12 * k];
      if (dir < 1 || dir > 4 || p->nconn >= MAP_CONNS)
        continue;
      struct conn *cc = &p->conn[p->nconn++];
      cc->dir = dir;
      cc->offset = (int32_t)le32(e + 12 * k + 4);
      cc->to = e[12 * k + 8] << 8 | e[12 * k + 9];
      if (world_index(cc->to) >= 0 || no_fly(cfg, cc->to) || (area && !flight_area_has(area, cc->to)))
        continue;
      int ow, oh;
      if (!layout_of(cfg, rom, len, cc->to, &ow, &oh, NULL) || tail >= WORLD_MAPS * 4)
        continue;
      qid[tail] = cc->to;
      qx[tail] = dir == SOUTH || dir == NORTH ? x + cc->offset : dir == WEST ? x - ow : x + w;
      qy[tail] = dir == WEST || dir == EAST ? y + cc->offset : dir == SOUTH ? y + h : y - oh;
      tail++;
    }
  }
  return worldCount > 0;
}

// The world as cells (ScenicFly.Grid): which map each one is in, and which steps between two maps are a connection.
static bool build_grid(const struct pd_config *cfg, const uint8_t *rom, size_t len) {
  int x1 = world[0].x + world[0].w, y1 = world[0].y + world[0].h;
  gx0 = world[0].x, gy0 = world[0].y;
  for (int i = 1; i < worldCount; i++) {
    if (world[i].x < gx0) gx0 = world[i].x;
    if (world[i].y < gy0) gy0 = world[i].y;
    if (world[i].x + world[i].w > x1) x1 = world[i].x + world[i].w;
    if (world[i].y + world[i].h > y1) y1 = world[i].y + world[i].h;
  }
  gw = x1 - gx0;
  gh = y1 - gy0;
  if ((long)gw * gh > MAX_CELLS)
    return false;
  memset(owner, NO_CELL, (size_t)gw * gh);
  int undefined = cfg->flightMapGridUndefined ? cfg->flightMapGridUndefined : 0x3FF;
  for (int i = 0; i < worldCount; i++) {
    const struct placed *m = &world[i];
    const uint8_t *sq = rom_at(rom, len, m->data, (size_t)m->w * m->h * 2);
    for (int yy = 0; yy < m->h; yy++)
      for (int xx = 0; xx < m->w; xx++) {
        int c = (m->y + yy - gy0) * gw + (m->x + xx - gx0);
        if (owner[c] != NO_CELL)
          continue; // overlapping layouts: the first map placed keeps the cell
        int v = sq ? (sq[2 * (yy * m->w + xx)] | sq[2 * (yy * m->w + xx) + 1] << 8) : 0;
        owner[c] = (v & undefined) == undefined ? HOLE_CELL : (uint8_t)i;
      }
  }
  for (int i = 0; i < worldCount; i++) {
    const struct placed *m = &world[i];
    nedges[i] = 0;
    for (int k = 0; k < m->nconn; k++) {
      int j = world_index(m->conn[k].to);
      if (j < 0)
        continue;
      const struct placed *o = &world[j];
      int dir = m->conn[k].dir, off = m->conn[k].offset;
      int ex = dir == SOUTH || dir == NORTH ? m->x + off : dir == WEST ? m->x - o->w : m->x + m->w;
      int ey = dir == WEST || dir == EAST ? m->y + off : dir == SOUTH ? m->y + m->h : m->y - o->h;
      edges[i][nedges[i]++] = (struct edge){dir, j, ex, ey, o->x == ex && o->y == ey};
    }
  }
  return true;
}

static int cell_at(int x, int y) {
  int cx = x - gx0, cy = y - gy0;
  return cx >= 0 && cx < gw && cy >= 0 && cy < gh && owner[cy * gw + cx] < HOLE_CELL ? cy * gw + cx : -1;
}

// The cell one step [dir] from [c], or -1: off the world, or into another map the game doesn't join there.
static int step(int c, int dir) {
  int x = c % gw, y = c / gw;
  int n = dir == SOUTH ? (y + 1 < gh ? c + gw : -1) : dir == NORTH ? (y > 0 ? c - gw : -1)
        : dir == WEST ? (x > 0 ? c - 1 : -1) : (x + 1 < gw ? c + 1 : -1);
  if (n < 0 || owner[n] >= HOLE_CELL)
    return -1;
  int a = owner[c], b = owner[n];
  if (a == b)
    return n;
  // Off the edge the game takes the first connection that way whose map spans the spot (GetIncomingConnection).
  int wx = n % gw + gx0, wy = n / gw + gy0;
  for (int k = 0; k < nedges[a]; k++) {
    const struct edge *e = &edges[a][k];
    const struct placed *o = &world[e->to];
    if (e->dir == dir && wx >= e->x && wx < e->x + o->w && wy >= e->y && wy < e->y + o->h)
      return e->to == b && e->there ? n : -1;
  }
  return -1;
}

static int path[FLIGHT_MAX_STEPS + 1], straight[FLIGHT_MAX_STEPS + 1], line[FLIGHT_MAX_STEPS + 2];

// A shortest path of cells (breadth first: every step costs the same, as ScenicFly's A*), or 0.
static int shortest(int start, int goal) {
  static int ring[1 << 16];
  memset(cameFrom, 0, (size_t)gw * gh);
  cameFrom[start] = 5; // the start
  unsigned head = 0, tail = 0;
  ring[tail++ & 0xFFFF] = start;
  while (head != tail && !cameFrom[goal]) {
    int c = ring[head++ & 0xFFFF];
    for (int d = 1; d <= 4; d++) {
      int n = step(c, d);
      if (n < 0 || cameFrom[n])
        continue;
      cameFrom[n] = (uint8_t)d;
      if (tail - head >= (1u << 16))
        return 0; // a frontier past the ring: give up (the classic FLY)
      ring[tail++ & 0xFFFF] = n;
    }
  }
  if (!cameFrom[goal])
    return 0;
  int n = 0;
  for (int c = goal;; ) {
    if (n > FLIGHT_MAX_STEPS)
      return 0;
    path[n++] = c;
    int d = cameFrom[c];
    if (d == 5)
      break;
    c = d == SOUTH ? c - gw : d == NORTH ? c + gw : d == WEST ? c + 1 : c - 1;
  }
  for (int i = 0; i < n / 2; i++) {
    int t = path[i];
    path[i] = path[n - 1 - i];
    path[n - 1 - i] = t;
  }
  return n;
}

// The staircase from [a] to [b] (one axis at a time), its length into line[], or 0 if a step of it isn't allowed.
static int staircase(int a, int b) {
  int ax = a % gw, ay = a / gw, dx = b % gw - ax, dy = b / gw - ay;
  int adx = dx < 0 ? -dx : dx, ady = dy < 0 ? -dy : dy, n = adx + ady;
  if (n + 1 > (int)(sizeof line / sizeof *line))
    return 0;
  line[0] = a;
  int c = a, sx = 0, sy = 0;
  for (int i = 1; i <= n; i++) {
    // The axis further behind its share of the line goes next.
    bool wantX = adx > 0 && (ady == 0 || (sx + 0.5) / adx <= (sy + 0.5) / ady);
    int dir = wantX ? (dx > 0 ? EAST : WEST) : (dy > 0 ? SOUTH : NORTH);
    c = step(c, dir);
    if (c < 0)
      return 0;
    if (wantX) sx++;
    else sy++;
    line[i] = c;
  }
  return n + 1;
}

// [n] cells of path pulled straight: from each point, the furthest later one a staircase reaches.
static int straighten(int n) {
  if (n < 3) {
    memcpy(straight, path, sizeof(int) * (size_t)n);
    return n;
  }
  int out = 0;
  straight[out++] = path[0];
  int i = 0;
  while (i < n - 1) {
    int j = n - 1, got = 0;
    while (j > i + 1) {
      got = staircase(path[i], path[j]);
      if (got)
        break;
      j -= j - i > 64 ? 8 : 1;
    }
    if (j <= i + 1) {
      j = i + 1;
      line[0] = path[i];
      line[1] = path[j];
      got = 2;
    }
    for (int k = 1; k < got; k++) {
      if (out > FLIGHT_MAX_STEPS)
        return 0;
      straight[out++] = line[k];
    }
    i = j;
  }
  return out;
}

int flight_route(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len, const struct flight_spot *from,
                 const struct flight_spot *to, const struct flight_area *area, uint8_t out[FLIGHT_MAX_STEPS]) {
  (void)core;
  if (!read_world(cfg, rom, len, from->map, area) || !build_grid(cfg, rom, len))
    return -1;
  int a = world_index(from->map), b = world_index(to->map);
  if (a < 0 || b < 0)
    return -1;
  if (from->x < 0 || from->x >= world[a].w || from->y < 0 || from->y >= world[a].h || to->x < 0 || to->x >= world[b].w ||
      to->y < 0 || to->y >= world[b].h)
    return -1;
  int start = cell_at(world[a].x + from->x, world[a].y + from->y), goal = cell_at(world[b].x + to->x, world[b].y + to->y);
  // Each end on its own map's ground (where two layouts overlap, the first placed holds the tile).
  if (start < 0 || goal < 0 || owner[start] != a || owner[goal] != b)
    return -1;
  int n = shortest(start, goal);
  if (!n)
    return -1;
  n = straighten(n);
  if (n < 1 || n - 1 >= FLIGHT_MAX_STEPS)
    return -1;
  for (int k = 0; k < n - 1; k++) {
    int d = straight[k + 1] - straight[k];
    out[k] = d == gw ? SOUTH : d == -gw ? NORTH : d == -1 ? WEST : EAST;
  }
  return n - 1;
}
