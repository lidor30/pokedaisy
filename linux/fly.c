/* FLY from the QUICK MENU (fly.h): app/.../companion/data/Fly.kt's flyTargets / flyStart / flyFinish over the
 * config's generated FLY fields - keep the two in step. The party comes from the snapshot (the moves the
 * companion already reads) rather than GetMonData, the field's lock from the host's scene. GPL-3.0. */
#include "fly.h"

#include "../app/src/main/cpp/pk_call.h"
#include <mgba-util/crc32.h>
#include <mgba/core/core.h>
#include <string.h>

#define FLY_MOVE 19
#define GBA_ROM 0x08000000u

static const uint8_t *rom_at(const uint8_t *rom, size_t len, uint32_t addr, size_t n) {
  if (addr < GBA_ROM || addr - GBA_ROM > len || len - (addr - GBA_ROM) < n)
    return NULL;
  return rom + (addr - GBA_ROM);
}

// The functions the CRCs check, in Fly.kt's FlyFns.functions order.
static int functions(const struct pd_config *cfg, uint32_t out[9]) {
  int n = 0;
  out[n++] = cfg->flyFlagGet;
  out[n++] = cfg->flySetUp;
  out[n++] = cfg->flyGetMapsecType;
  out[n++] = cfg->flyFade;
  out[n++] = cfg->flyLock;
  out[n++] = cfg->flyUnlock;
  if (cfg->flyHealTo) {
    out[n++] = cfg->flyHealTo;
    out[n++] = cfg->flyMapWarp;
    out[n++] = cfg->flyReturn;
  } else {
    out[n++] = cfg->flyWarp;
  }
  return n;
}

bool fly_supported(const struct pd_config *cfg, const uint8_t *rom, size_t len) {
  if (!cfg || !cfg->flyFlagGet || !rom)
    return false;
  uint32_t fns[9];
  int n = functions(cfg, fns);
  for (int i = 0; i < n; i++) {
    const uint8_t *code = rom_at(rom, len, fns[i] & ~1u, 32);
    if (!code || doCrc32(code, 32) != cfg->flyCrcs[i])
      return false;
  }
  return true;
}

int fly_flyer(const struct pd_snapshot *snap) {
  if (!snap || !snap->valid)
    return -1;
  for (int i = 0; i < snap->partyCount && i < PD_PARTY_SIZE; i++) {
    const struct pd_mon *m = &snap->party[i];
    if (m->species == 0 || m->species == PD_SPECIES_EGG)
      continue;
    for (int k = 0; k < PD_NUM_MOVES; k++)
      if (m->moves[k] == FLY_MOVE)
        return i;
  }
  return -1;
}

// One of the game's functions; false if it couldn't run now or never returned.
static bool call(struct mCore *core, uint32_t fn, uint32_t a0, uint32_t a1, uint32_t a2, uint32_t *r0) {
  const uint32_t args[4] = {a0, a1, a2, 0};
  uint32_t got = 0;
  if (!pk_call(core, fn, args, &got))
    return false;
  if (r0)
    *r0 = got;
  return true;
}

static bool flies(const struct pd_config *cfg, uint32_t type) {
  for (int i = 0; i < 4; i++)
    if (cfg->flyTypes[i] && (uint32_t)cfg->flyTypes[i] == type)
      return true;
  return false;
}

// The party menu's checks in its order (Fly.kt's flyGate): busy, the badge, the map type.
enum fly_result fly_gate(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                            bool busy, bool battle) {
  if (!fly_supported(cfg, rom, len))
    return FLY_FAILED;
  if (battle)
    return FLY_IN_BATTLE;
  if (busy)
    return FLY_NOT_NOW;
  if (!pk_call_safe(core))
    return FLY_FAILED;
  uint32_t r = 0;
  if (!call(core, cfg->flyFlagGet, (uint32_t)cfg->flyBadgeFlag, 0, 0, &r))
    return FLY_FAILED;
  if (r != 1)
    return FLY_NO_BADGE;
  // Ruby / Sapphire's SetUpFieldMove_Fly takes the map type (Fly.kt's mayFlyHere).
  uint32_t type = cfg->flyMapHeader ? core->busRead8(core, cfg->flyMapHeader + 0x17) : 0;
  if (!call(core, cfg->flySetUp, type, 0, 0, &r))
    return FLY_FAILED;
  return r == 1 ? FLY_OK : FLY_CANT_FLY_HERE;
}

// The sections FLY may offer: the table's filled entries, then the ones past it.
static int sections(const struct pd_config *cfg, const uint8_t *rom, size_t len, int out[FLY_MAX_TOWNS + 4]) {
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

void fly_targets(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                 const struct pd_snapshot *snap, bool busy, bool battle, struct fly_targets *out) {
  memset(out, 0, sizeof *out);
  out->flyerSlot = -1;
  out->result = fly_gate(core, cfg, rom, len, busy, battle);
  if (out->result != FLY_OK)
    return;
  int slot = fly_flyer(snap);
  if (slot < 0) {
    out->result = FLY_NO_FLYER;
    return;
  }
  out->flyerSlot = slot;
  out->flyerPersonality = snap->party[slot].personality;
  int all[FLY_MAX_TOWNS + 4], n = sections(cfg, rom, len, all);
  for (int i = 0; i < n && out->count < FLY_MAX_TOWNS; i++) {
    uint32_t type = 0;
    if (!call(core, cfg->flyGetMapsecType, (uint32_t)all[i], 0, 0, &type)) {
      out->result = FLY_FAILED;
      return;
    }
    if (flies(cfg, type))
      out->towns[out->count++] = all[i];
  }
}

enum fly_result fly_start(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                          const struct pd_snapshot *snap, int mapsec, const struct fly_targets *t, bool busy,
                          bool battle) {
  enum fly_result r = fly_gate(core, cfg, rom, len, busy, battle);
  if (r != FLY_OK)
    return r;
  bool known = mapsec - cfg->flyFirstMapsec >= 0 && mapsec - cfg->flyFirstMapsec < cfg->flyCount;
  for (int i = 0; i < 4 && cfg->flyExtra[i]; i++)
    known |= cfg->flyExtra[i] == mapsec;
  uint32_t type = 0;
  if (!known || !call(core, cfg->flyGetMapsecType, (uint32_t)mapsec, 0, 0, &type) || !flies(cfg, type))
    return FLY_FAILED;
  int slot = fly_flyer(snap);
  if (slot < 0 || slot != t->flyerSlot || snap->party[slot].personality != t->flyerPersonality)
    return FLY_NO_FLYER;
  if (!call(core, cfg->flyLock, 0, 0, 0, NULL) || !call(core, cfg->flyFade, 1, 0, 0, NULL))
    return FLY_FAILED;
  return FLY_OK;
}

static bool warp(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len, int mapsec) {
  if (!cfg->flyHealTo)
    return call(core, cfg->flyWarp, (uint32_t)mapsec, 0, 0, NULL);
  // Emerald's CB_ExitFlyMap: the heal location (the special ones first, the home town by the player's gender),
  // else the map warp - then back to the field with the Fly landing.
  int i = mapsec - cfg->flyFirstMapsec;
  const uint8_t *entry = i >= 0 && i < cfg->flyCount ? rom_at(rom, len, cfg->flyDestinations + (uint32_t)i * 3, 3) : NULL;
  uint8_t e[3] = {0, 0, 0};
  if (entry)
    memcpy(e, entry, 3);
  int heal = -1;
  for (int k = 0; k < 4; k++)
    if (cfg->flySpecial[k][1] && cfg->flySpecial[k][0] == mapsec)
      heal = cfg->flySpecial[k][1];
  if (heal < 0 && mapsec == cfg->flyGenderMapsec) {
    uint32_t block = cfg->flySaveBlock2Static ? cfg->flySaveBlock2Ptr : core->busRead32(core, cfg->flySaveBlock2Ptr);
    heal = core->busRead8(core, block + 8) == 0 ? cfg->flyMaleHeal : cfg->flyFemaleHeal;
  }
  if (heal < 0)
    heal = e[2];
  bool ok = heal ? call(core, cfg->flyHealTo, (uint32_t)heal, 0, 0, NULL)
                 : (e[0] | e[1]) && call(core, cfg->flyMapWarp, e[0], e[1], 0xFFFFFFFFu, NULL);
  return ok && call(core, cfg->flyReturn, 0, 0, 0, NULL);
}

bool fly_finish(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len, int mapsec,
                const struct fly_targets *t, bool leftField) {
  if (leftField)
    return false;
  core->busWrite8(core, cfg->flyPartySlot, (uint8_t)t->flyerSlot);
  bool ok = core->busRead8(core, cfg->flyPartySlot) == (uint8_t)t->flyerSlot && warp(core, cfg, rom, len, mapsec);
  if (!ok) {
    call(core, cfg->flyFade, 0, 0, 0, NULL);
    call(core, cfg->flyUnlock, 0, 0, 0, NULL);
  }
  return ok;
}

uint32_t fly_main_callback(struct mCore *core, const struct pd_config *cfg) {
  return cfg && cfg->gMain ? core->busRead32(core, cfg->gMain + 4) : 0;
}

const char *fly_reason(enum fly_result r) {
  switch (r) {
  case FLY_NOT_NOW: return "Not now: close the game's menus and battles first.";
  case FLY_IN_BATTLE: return "You can't do that during a battle.";
  case FLY_NO_BADGE: return "FLY needs a new BADGE first.";
  case FLY_CANT_FLY_HERE: return "You can't FLY from here.";
  case FLY_NO_FLYER: return "Nobody in the party knows FLY.";
  case FLY_OK: return NULL;
  default: return "It can't be used here.";
  }
}
