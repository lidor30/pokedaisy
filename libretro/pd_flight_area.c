/* FLIGHT AREA (pd_flight_area.h): app/.../companion/data/FlightArea.kt's Keeper, readMaps, seed, saveKey and SeenMaps, with
 * ScenicFly.destination / here and Fly.kt's flyTownSections / flyVisitedTowns - keep the two in step. GPL-3.0. */
#include "pd_flight_area.h"

#include "../app/src/main/cpp/pk_call.h"
#include "../app/src/main/cpp/pk_flight.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

bool pd_lr_read_memory(void *ctx, uint32_t a, void *out, size_t n);

#define MAPS 65536                 // every group << 8 | num
#define TRAINER_FLAGS_START 0x500  // beaten = this + trainer id, in every engine FREE FLIGHT runs
#define MAX_TRAINER 0x400          // past it a script's bytes weren't a trainerbattle
#define OBJECT_TEMPLATE 24         // struct ObjectEventTemplate, the script pointer at +0x10
#define MAX_CONNS 32               // a header's connections (at most 16) and the ones joined to it the other way
#define MAX_TRAINERS 64            // object events per map (readMaps' 1..64)
#define ROM_START 0x08000000u
#define ROM_END 0x0A000000u

struct map_info {
  uint8_t nConns, nTrainers;
  uint16_t conns[MAX_CONNS];
  uint16_t trainers[MAX_TRAINERS];
};

static const struct pd_flight_area_cfg *cfg;
static const uint8_t *romData;
static size_t romLen;
static uint32_t romKey;
static char dirPath[1024];
static char filePath[1200];

static bool keyKnown;        // a save is loaded (saveKey said one)
static uint32_t key;         // its key
static uint8_t seen[MAPS / 8]; // its maps, a bit per id (pk_flight_set_area's layout)
static int seenCount;
static bool seeded;          // the save's flags were read into it this run
static int sent = -1;        // the count pk_flight was last given, -1 = give it again
static bool anywhereSent;    // pk_flight was told "anywhere" since ANYWHERE was picked

static struct map_info *mapList; // readMaps' answer, one per map in gMapGroups (NULL until read) ...
static int16_t *mapIndex;        // ... its place there by id
static uint8_t *present;         // a bit per id: in gMapGroups
#define MAP(id) (mapList[mapIndex[id]]) /* a map that's present */

/* ---- reads ---- */

static bool rom(uint32_t a) { return a >= ROM_START && a < ROM_END; }

static const uint8_t *rom_at(uint32_t a, size_t n) {
  if (!romData || a < ROM_START || a - ROM_START > romLen || romLen - (a - ROM_START) < n)
    return NULL;
  return romData + (a - ROM_START);
}

static uint32_t le32(const uint8_t *p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

static uint32_t rom32(uint32_t a) {
  const uint8_t *p = rom_at(a, 4);
  return p ? le32(p) : 0;
}

/* RAM (or ROM) through the companion's reader: no bus cycles. */
static bool ram(struct mCore *core, uint32_t a, void *out, size_t n) {
  const uint8_t *p = rom_at(a, n);
  if (p) {
    memcpy(out, p, n);
    return true;
  }
  return pd_lr_read_memory(core, a, out, n);
}

static uint32_t ram32(struct mCore *core, uint32_t a) {
  uint8_t b[4];
  return ram(core, a, b, 4) ? le32(b) : 0;
}

static bool has(const uint8_t *bits, int id) { return id >= 0 && id < MAPS && (bits[id >> 3] >> (id & 7)) & 1; }
static void put(uint8_t *bits, int id) { bits[id >> 3] |= (uint8_t)(1 << (id & 7)); }

static uint32_t crc32_of(const uint8_t *p, size_t n) {
  uint32_t c = 0xffffffffu;
  for (size_t i = 0; i < n; i++) {
    c ^= p[i];
    for (int k = 0; k < 8; k++)
      c = (c >> 1) ^ (0xedb88320u & (0u - (c & 1u)));
  }
  return ~c;
}

/* ---- the world: FlightArea.readMaps ---- */

static void free_maps(void) {
  free(mapList);
  free(mapIndex);
  free(present);
  mapList = NULL;
  mapIndex = NULL;
  present = NULL;
}

static void add_conn(struct map_info *m, int id) {
  for (int i = 0; i < m->nConns; i++)
    if (m->conns[i] == id)
      return;
  if (m->nConns < MAX_CONNS)
    m->conns[m->nConns++] = (uint16_t)id;
}

/* Every map in gMapGroups: its connections and the trainers its object events battle (scripts starting with
 * trainerbattle, 0x5C; the trainer id at +2, or +3 in expansion 1.12's format, voted on by the first text pointer). */
static bool read_maps(void) {
  int total = 0;
  for (int g = 0; g < cfg->mapGroupCount; g++)
    total += cfg->mapGroupSizes[g];
  mapList = calloc(total ? (size_t)total : 1, sizeof *mapList);
  mapIndex = malloc(MAPS * sizeof *mapIndex);
  present = calloc(MAPS / 8, 1);
  if (!mapList || !mapIndex || !present) {
    free_maps(); // all or nothing: the next sync tries again from scratch
    return false;
  }
  int count = 0;
  // The scripts' first 13 bytes, kept until the format's vote: at most 64 a map.
  struct script { uint16_t id; uint8_t b[13]; } *scripts = NULL;
  size_t nScripts = 0, capScripts = 0;
  for (int g = 0; g < cfg->mapGroupCount; g++) {
    uint32_t list = rom32(cfg->mapGroups + 4u * (uint32_t)g) ^ cfg->mapGroupsXor;
    if (!rom(list))
      continue;
    for (int n = 0; n < cfg->mapGroupSizes[g] && n < 256; n++) {
      uint32_t h = rom32(list + 4u * (uint32_t)n);
      if (!rom(h))
        continue;
      int id = g << 8 | n;
      put(present, id);
      mapIndex[id] = (int16_t)count;
      struct map_info *m = &mapList[count++];
      uint32_t c = rom32(h + 0xC);
      if (rom(c)) {
        uint32_t cn = rom32(c), cl = rom32(c + 4);
        const uint8_t *e = cn >= 1 && cn <= 16 && rom(cl) ? rom_at(cl, 12 * cn) : NULL;
        for (uint32_t k = 0; e && k < cn; k++)
          if (e[12 * k] >= 1 && e[12 * k] <= 4)
            add_conn(m, e[12 * k + 8] << 8 | e[12 * k + 9]);
      }
      uint32_t ev = rom32(h + 4);
      if (rom(ev)) {
        const uint8_t *cp = rom_at(ev, 1);
        int objects = cp ? cp[0] : 0;
        uint32_t objs = rom32(ev + 4);
        const uint8_t *t = objects >= 1 && objects <= 64 && rom(objs) ? rom_at(objs, (size_t)OBJECT_TEMPLATE * objects) : NULL;
        for (int k = 0; t && k < objects; k++) {
          uint32_t sp = le32(t + OBJECT_TEMPLATE * k + 0x10);
          const uint8_t *sb = rom(sp) ? rom_at(sp, 13) : NULL;
          if (!sb || sb[0] != 0x5C)
            continue;
          if (nScripts == capScripts) {
            capScripts = capScripts ? capScripts * 2 : 1024;
            void *grown = realloc(scripts, capScripts * sizeof *scripts);
            if (!grown) {
              free(scripts);
              free_maps();
              return false;
            }
            scripts = grown;
          }
          scripts[nScripts].id = (uint16_t)id;
          memcpy(scripts[nScripts].b, sb, 13);
          nScripts++;
        }
      }
    }
  }
  size_t old = 0, neu = 0;
  for (size_t i = 0; i < nScripts; i++) {
    old += scripts[i].b[7] == 0x08 || scripts[i].b[7] == 0x09;
    neu += scripts[i].b[8] == 0x08 || scripts[i].b[8] == 0x09;
  }
  int at = neu > old ? 3 : 2;
  for (size_t i = 0; i < nScripts; i++) {
    int t = scripts[i].b[at] | scripts[i].b[at + 1] << 8;
    struct map_info *m = &MAP(scripts[i].id);
    if (t >= 1 && t < MAX_TRAINER && m->nTrainers < MAX_TRAINERS)
      m->trainers[m->nTrainers++] = (uint16_t)t;
  }
  free(scripts);
  // Connections both ways: a map joined only one way still lies between the two.
  for (int id = 0; id < MAPS; id++) {
    if (!has(present, id))
      continue;
    for (int i = 0; i < MAP(id).nConns; i++) {
      int o = MAP(id).conns[i];
      if (has(present, o))
        add_conn(&MAP(o), id);
    }
  }
  return true;
}

/* ---- the towns: ScenicFly.destination, Fly.kt's flySections ---- */

/* Where FLY to [mapsec] lands: its heal location's map, or -1. */
static int destination(struct mCore *core, int mapsec) {
  int i = mapsec - cfg->firstMapsec, heal = -1;
  if (cfg->healWarp && mapsec == cfg->genderMapsec) {
    uint32_t sb2 = cfg->hwSaveBlock2Static ? cfg->hwSaveBlock2Ptr : ram32(core, cfg->hwSaveBlock2Ptr);
    uint8_t gender = 0;
    heal = sb2 && ram(core, sb2 + 8, &gender, 1) && (gender & 1) ? cfg->femaleHeal : cfg->maleHeal;
  } else {
    for (int k = 0; cfg->healWarp && k < 4 && heal < 0; k++)
      if (cfg->special[k][0] >= 0 && cfg->special[k][0] == mapsec)
        heal = cfg->special[k][1];
    if (heal < 0) {
      if (i < 0 || i >= cfg->count)
        return -1;
      const uint8_t *d = rom_at(cfg->destinations + (uint32_t)i * 3 + 2, 1);
      heal = d ? d[0] : -1;
    }
  }
  if (heal < 1 || heal > cfg->healLocationCount)
    return -1;
  const uint8_t *e = rom_at(cfg->healLocations + (uint32_t)(heal - 1) * (uint32_t)cfg->healLocationSize, 2);
  return e ? e[0] << 8 | e[1] : -1;
}

/* The map sections FLY may offer: the table's filled entries, then the sections past it. */
static int fly_sections(int out[300]) {
  const uint8_t *t = rom_at(cfg->destinations, (size_t)cfg->count * 3);
  int n = 0;
  for (int i = 0; t && i < cfg->count && n < 296; i++)
    if (t[i * 3] | t[i * 3 + 1] | t[i * 3 + 2])
      out[n++] = cfg->firstMapsec + i;
  for (int i = 0; i < 4 && cfg->extraMapsecs[i] >= 0; i++)
    out[n++] = cfg->extraMapsecs[i];
  return n;
}

static bool flies(uint32_t type) {
  for (int i = 0; i < 4; i++)
    if (cfg->flyTypes[i] >= 0 && (uint32_t)cfg->flyTypes[i] == type)
      return true;
  return false;
}

static bool call(struct mCore *core, uint32_t fn, uint32_t a0, uint32_t *r0) {
  const uint32_t args[4] = {a0, 0, 0, 0};
  return pk_call(core, fn, args, r0) != 0;
}

static bool no_fly(int id) {
  for (int i = 0; i < 4; i++)
    if (cfg->noFly[i] != 0xFFFF && cfg->noFly[i] == id)
      return true;
  return false;
}

/* ---- the save: FlightArea.saveKey, SeenMaps ---- */

static bool save_key(struct mCore *core, uint32_t *out) {
  uint32_t sb2 = cfg->saveBlockStatic ? cfg->saveBlock2Ptr : ram32(core, cfg->saveBlock2Ptr);
  if (sb2 < 0x02000000u || sb2 >= 0x04000000u)
    return false;
  uint8_t b[14], k[13];
  if (!ram(core, sb2, b, sizeof b))
    return false;
  memcpy(k, b, 9);           // the player's name, gender ...
  memcpy(k + 9, b + 10, 4);  // ... and trainer ID, not the special-warp flags between
  bool zero = true, ff = true;
  for (int i = 0; i < 13; i++) {
    zero &= k[i] == 0;
    ff &= k[i] == 0xFF;
  }
  if (zero || ff)
    return false;
  *out = crc32_of(k, sizeof k);
  return true;
}

static void load_file(void) {
  memset(seen, 0, sizeof seen);
  seenCount = 0;
  if (!*filePath)
    return;
  FILE *f = fopen(filePath, "r");
  if (!f)
    return;
  char line[64];
  while (fgets(line, sizeof line, f)) {
    int g, n;
    if (sscanf(line, " %d.%d", &g, &n) == 2 && g >= 0 && g < 256 && n >= 0 && n < 256 && !has(seen, g << 8 | n)) {
      put(seen, g << 8 | n);
      seenCount++;
    }
  }
  fclose(f);
}

/* The whole set again, through a temporary file and a rename (SeenMaps.addAll), `group.num` a line by id. */
static void write_file(void) {
  if (!*filePath)
    return;
  char folder[1100], tmp[1210];
  snprintf(folder, sizeof folder, "%s/flight-area", dirPath);
  if (mkdir(folder, 0755) != 0 && errno != EEXIST)
    return;
  snprintf(tmp, sizeof tmp, "%s.tmp", filePath);
  FILE *f = fopen(tmp, "w");
  if (!f)
    return;
  bool first = true;
  for (int id = 0; id < MAPS; id++)
    if (has(seen, id)) {
      fprintf(f, "%s%d.%d", first ? "" : "\n", id >> 8, id & 0xFF);
      first = false;
    }
  if (fclose(f) == 0)
    rename(tmp, filePath);
}

/* Adds [id]; true when it was new. */
static bool add(int id) {
  if (id < 0 || id >= MAPS || has(seen, id))
    return false;
  put(seen, id);
  seenCount++;
  return true;
}

/* ---- FlightArea.seed ---- */

/* What the save proves the player has seen, on top of what's kept: the visited towns' maps, every map on a shortest way
 * between two of them through maps that aren't towns, and each map with a beaten trainer touching what's seen by then
 * (a chain of them too). False when the game couldn't be asked now (try again). */
static bool seed(struct mCore *core) {
  if (!mapList && !read_maps())
    return false;
  if (!pk_call_safe(core))
    return false;
  static uint8_t towns[MAPS / 8], visited[MAPS / 8], out[MAPS / 8];
  memset(towns, 0, sizeof towns);
  memset(visited, 0, sizeof visited);
  memcpy(out, seen, sizeof out);
  int sections[300], n = fly_sections(sections);
  for (int i = 0; i < n; i++) {
    int town = destination(core, sections[i]);
    if (town < 0)
      continue;
    put(towns, town);
    uint32_t type = 0;
    if (!call(core, cfg->getMapsecType, (uint32_t)sections[i], &type))
      return false;
    if (flies(type)) {
      put(visited, town);
      put(out, town);
    }
  }
  // Shortest ways out of each visited town, stopping at towns: a visited one marks the way back.
  static int32_t parent[MAPS];
  static uint16_t queue[MAPS];
  for (int t = 0; t < MAPS; t++) {
    if (!has(visited, t) || !has(present, t))
      continue;
    for (int i = 0; i < MAPS; i++)
      parent[i] = -1;
    parent[t] = t;
    int head = 0, tail = 0;
    queue[tail++] = (uint16_t)t;
    while (head < tail) {
      int cur = queue[head++];
      for (int i = 0; i < MAP(cur).nConns; i++) {
        int nb = MAP(cur).conns[i];
        if (parent[nb] >= 0 || no_fly(nb) || !has(present, nb))
          continue;
        parent[nb] = cur;
        if (has(towns, nb)) {
          if (has(visited, nb))
            for (int k = cur; k != t; k = parent[k])
              put(out, k);
          continue;
        }
        queue[tail++] = (uint16_t)nb;
      }
    }
  }
  // Maps with a beaten trainer, joined to what's seen (a chain of them too).
  static uint8_t fought[MAPS / 8];
  memset(fought, 0, sizeof fought);
  for (int id = 0; id < MAPS; id++) {
    if (!has(present, id) || has(out, id) || no_fly(id))
      continue;
    for (int i = 0; i < MAP(id).nTrainers; i++) {
      uint32_t r = 0;
      if (!call(core, cfg->flagGet, TRAINER_FLAGS_START + MAP(id).trainers[i], &r))
        return false;
      if (r & 0xFF) {
        put(fought, id);
        break;
      }
    }
  }
  bool grew;
  do {
    grew = false;
    for (int id = 0; id < MAPS; id++) {
      if (!has(fought, id))
        continue;
      for (int i = 0; i < MAP(id).nConns; i++)
        if (has(out, MAP(id).conns[i])) {
          put(out, id);
          fought[id >> 3] &= (uint8_t)~(1 << (id & 7));
          grew = true;
          break;
        }
    }
  } while (grew);
  bool added = false;
  for (int id = 0; id < MAPS; id++)
    if (has(out, id))
      added |= add(id);
  if (added)
    write_file();
  return true;
}

/* ---- the keeper ---- */

void pd_flight_area_begin(const struct pd_flight_area_cfg *c, const uint8_t *r, size_t len, uint32_t crc, const char *dir) {
  pd_flight_area_end();
  cfg = c;
  romData = r;
  romLen = len;
  romKey = crc;
  snprintf(dirPath, sizeof dirPath, "%s", dir ? dir : "");
  pk_flight_set_area(NULL, 0); // the last game's maps are no use here
}

void pd_flight_area_end(void) {
  cfg = NULL;
  romData = NULL;
  keyKnown = seeded = anywhereSent = false;
  sent = -1;
  seenCount = 0;
  memset(seen, 0, sizeof seen);
  *filePath = 0;
  free_maps();
}

void pd_flight_area_resend(void) {
  sent = -1;
  anywhereSent = false;
}

void pd_flight_area_sync(struct mCore *core, bool anywhere, bool standing) {
  if (!cfg)
    return;
  if (anywhere) {
    if (!anywhereSent) {
      anywhereSent = true;
      sent = -1;
      pk_flight_set_area(NULL, 0);
      fprintf(stderr, "pokedaisy: FLIGHT AREA: anywhere\n");
    }
    return;
  }
  anywhereSent = false;
  uint32_t k;
  if (!save_key(core, &k))
    return;
  if (!keyKnown || k != key) {
    keyKnown = true;
    key = k;
    seeded = false;
    sent = -1;
    if (*dirPath)
      snprintf(filePath, sizeof filePath, "%s/flight-area/%08x-%08x.txt", dirPath, (unsigned)romKey, (unsigned)k);
    load_file();
  }
  if (!seeded)
    seeded = seed(core);
  if (!seeded)
    return; // asked again at the next sync: pk_flight keeps what it had
  if (standing) {
    uint32_t sb1 = cfg->saveBlockStatic ? cfg->saveBlock1Ptr : ram32(core, cfg->saveBlock1Ptr);
    uint8_t loc[6];
    if (sb1 && ram(core, sb1 + (uint32_t)cfg->saveBlock1PosOff, loc, sizeof loc) && add(loc[4] << 8 | loc[5]))
      write_file();
  }
  if (seenCount == sent)
    return;
  if (sent < 0)
    fprintf(stderr, "pokedaisy: FLIGHT AREA: %d maps seen (%s)\n", seenCount, *filePath ? filePath : "not kept");
  sent = seenCount;
  pk_flight_set_area(seen, sizeof seen);
}

int pd_flight_area_count(void) { return keyKnown ? seenCount : -1; }
bool pd_flight_area_has(int id) { return has(seen, id); }
const char *pd_flight_area_file(void) { return filePath; }
