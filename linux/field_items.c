/* REPEL, PC anywhere and BIKE (field_items.h): FieldItems.kt's useFieldItem, Pc.kt's openPc and KeyItems.kt's
 * register in C - keep them in step. GPL-3.0. */
#include "field_items.h"

#include "../app/src/main/cpp/pk_call.h"
#include <mgba-util/crc32.h>
#include <mgba/core/core.h>
#include <string.h>

#define GBA_ROM 0x08000000u

const int repel_kinds[REPEL_KINDS][2] = {{86, 100}, {83, 200}, {84, 250}};
// FireRed / LeafGreen's BICYCLE; Ruby / Sapphire / Emerald's MACH and ACRO BIKE (KeyItems.BIKES).
static const int BIKES[] = {360, 259, 272};

static const uint8_t *rom_at(const uint8_t *rom, size_t len, uint32_t addr, size_t n) {
  if (addr < GBA_ROM || addr - GBA_ROM > len || len - (addr - GBA_ROM) < n)
    return NULL;
  return rom + (addr - GBA_ROM);
}

static bool crc_is(const uint8_t *rom, size_t len, uint32_t fn, uint32_t crc) {
  const uint8_t *code = rom_at(rom, len, fn & ~1u, 32);
  return code && doCrc32(code, 32) == crc;
}

bool field_items_supported(const struct pd_config *cfg, const uint8_t *rom, size_t len) {
  if (!cfg || !cfg->fieldVarGet || !rom)
    return false;
  const uint32_t fns[5] = {cfg->fieldVarGet, cfg->fieldVarSet, cfg->fieldCheckBag, cfg->fieldRemoveBag,
                           cfg->fieldLocked};
  for (int i = 0; i < 5; i++)
    if (!crc_is(rom, len, fns[i], cfg->fieldCrcs[i]))
      return false;
  return true;
}

bool pc_supported(const struct pd_config *cfg, const uint8_t *rom, size_t len) {
  if (!field_items_supported(cfg, rom, len) || !cfg->pcSetupScript)
    return false;
  const uint8_t *entry = rom_at(rom, len, cfg->pcSpecials + 4u * (uint32_t)cfg->pcSpecial, 4);
  uint32_t fn = entry ? (uint32_t)entry[0] | entry[1] << 8 | entry[2] << 16 | (uint32_t)entry[3] << 24 : 0;
  return fn == cfg->pcFn && crc_is(rom, len, cfg->pcSetupScript, cfg->pcCrcs[0]) &&
         crc_is(rom, len, cfg->pcFn, cfg->pcCrcs[1]);
}

bool bike_supported(const struct pd_config *cfg) {
  return cfg && cfg->fieldVarGet && (cfg->moneyOff == 0x290 || cfg->moneyOff == 0x490);
}

static uint32_t save_block1(struct mCore *core, const struct pd_config *cfg) {
  uint32_t sb1 = cfg->staticSaveBlocks ? cfg->saveBlock1Ptr : core->busRead32(core, cfg->saveBlock1Ptr);
  return sb1 >= 0x02000000u && sb1 < 0x02040000u ? sb1 : 0;
}

int repel_steps(struct mCore *core, const struct pd_config *cfg) {
  if (!cfg || !cfg->repelVar || !cfg->varsOff)
    return 0;
  uint32_t sb1 = save_block1(core, cfg);
  return sb1 ? core->busRead16(core, sb1 + cfg->varsOff + 2u * (uint32_t)(cfg->repelVar - 0x4000)) : 0;
}

int bag_count(const struct pd_snapshot *snap, int item) {
  int n = 0;
  if (!snap || !snap->valid || !snap->bagOk)
    return 0;
  for (int p = 0; p < PD_POCKET_COUNT; p++)
    for (int i = 0; i < snap->bag[p].count && i < PD_POCKET_SLOTS; i++)
      if (snap->bag[p].items[i].id == item)
        n += snap->bag[p].items[i].quantity;
  return n;
}

int bag_bike(const struct pd_snapshot *snap) {
  for (size_t i = 0; i < sizeof BIKES / sizeof *BIKES; i++)
    if (bag_count(snap, BIKES[i]) > 0)
      return BIKES[i];
  return 0;
}

static bool call(struct mCore *core, uint32_t fn, uint32_t a0, uint32_t a1, uint32_t *r0) {
  const uint32_t args[4] = {a0, a1, 0, 0};
  uint32_t got = 0;
  if (!pk_call(core, fn, args, &got))
    return false;
  if (r0)
    *r0 = got;
  return true;
}

bool field_locked(struct mCore *core, const struct pd_config *cfg) {
  uint32_t r = 1;
  return !call(core, cfg->fieldLocked, 0, 0, &r) || r != 0;
}

// The checks every one of them makes first: the code, a battle, a menu, the field's controls.
static enum item_result gate(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                             bool busy, bool battle) {
  if (!field_items_supported(cfg, rom, len) || !pk_call_safe(core))
    return ITEM_FAILED;
  if (battle)
    return ITEM_IN_BATTLE;
  if (busy || field_locked(core, cfg))
    return ITEM_NOT_NOW;
  return ITEM_USED;
}

enum item_result repel_use(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                           int item, bool busy, bool battle, int *steps) {
  *steps = 0;
  bool repel = false;
  for (int i = 0; i < 4; i++)
    repel |= cfg->repelItems[i] && cfg->repelItems[i] == item;
  enum item_result r = gate(core, cfg, rom, len, busy, battle);
  if (!repel || r != ITEM_USED)
    return repel ? r : ITEM_FAILED;
  uint32_t v = 0;
  if (!call(core, cfg->fieldVarGet, (uint32_t)cfg->repelVar, 0, &v))
    return ITEM_FAILED;
  if ((v & 0xFFFF) != 0)
    return ITEM_STILL_ACTIVE;
  if (!call(core, cfg->fieldCheckBag, (uint32_t)item, 1, &v))
    return ITEM_FAILED;
  if (v != 1)
    return ITEM_NONE_LEFT;
  // struct Item: name, id, price, holdEffect, then holdEffectParam (a Repel's steps); 30 bytes after the name.
  int param = cfg->itemParamOffset ? cfg->itemParamOffset : cfg->itemNameLen + 5;
  const uint8_t *p = rom_at(rom, len, cfg->gItems + (uint32_t)item * (uint32_t)(cfg->itemNameLen + 30) + (uint32_t)param, 1);
  if (!p || !*p)
    return ITEM_FAILED;
  if (!call(core, cfg->fieldVarSet, (uint32_t)cfg->repelVar, *p, NULL) ||
      !call(core, cfg->fieldRemoveBag, (uint32_t)item, 1, &v) || v != 1)
    return ITEM_FAILED;
  *steps = *p;
  return ITEM_USED;
}

enum item_result pc_open(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len, bool busy,
                         bool battle) {
  if (!pc_supported(cfg, rom, len))
    return ITEM_FAILED;
  enum item_result r = gate(core, cfg, rom, len, busy, battle);
  if (r != ITEM_USED)
    return r;
  // lockall; special N; waitstate; releaseall; end (Gen 3's script opcodes, the same in both engines).
  const uint8_t script[7] = {0x69, 0x25, (uint8_t)cfg->pcSpecial, (uint8_t)(cfg->pcSpecial >> 8), 0x27, 0x6B, 0x02};
  for (int i = 0; i < 7; i++)
    core->busWrite8(core, cfg->pcScript + (uint32_t)i, script[i]);
  for (int i = 0; i < 7; i++)
    if (core->busRead8(core, cfg->pcScript + (uint32_t)i) != script[i])
      return ITEM_FAILED;
  return call(core, cfg->pcSetupScript, cfg->pcScript, 0, NULL) ? ITEM_USED : ITEM_FAILED;
}

enum item_result bike_register(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                               int item, bool busy, bool battle, uint16_t *old) {
  if (!bike_supported(cfg))
    return ITEM_FAILED;
  enum item_result r = gate(core, cfg, rom, len, busy, battle);
  if (r != ITEM_USED)
    return r;
  uint32_t sb1 = save_block1(core, cfg);
  if (!sb1)
    return ITEM_FAILED;
  uint32_t reg = sb1 + cfg->moneyOff + 6;
  *old = core->busRead16(core, reg);
  if (*old > 0x3FF) // not a sane item id: not the register after all
    return ITEM_FAILED;
  core->busWrite16(core, reg, (uint16_t)item);
  return core->busRead16(core, reg) == (uint16_t)item ? ITEM_USED : ITEM_FAILED;
}

void bike_restore(struct mCore *core, const struct pd_config *cfg, int item, uint16_t old) {
  uint32_t sb1 = save_block1(core, cfg);
  if (sb1 && core->busRead16(core, sb1 + cfg->moneyOff + 6) == (uint16_t)item)
    core->busWrite16(core, sb1 + cfg->moneyOff + 6, old);
}

const char *item_reason(enum item_result r) {
  switch (r) {
  case ITEM_STILL_ACTIVE: return "The last one is still working.";
  case ITEM_NOT_NOW: return "Not now: close the game's menus and battles first.";
  case ITEM_IN_BATTLE: return "You can't do that during a battle.";
  case ITEM_NONE_LEFT: return "None left in the bag.";
  case ITEM_USED: return NULL;
  default: return "It can't be used here.";
  }
}
