/* FLASH from the QUICK MENU (flash.h): app/.../companion/data/Flash.kt's useFlash / flashDark over the config's generated
 * FLASH fields - keep the two in step. The party comes from the snapshot (the moves the companion already reads) rather
 * than GetMonData, like fly.c. GPL-3.0. */
#include "flash.h"

#include "../app/src/main/cpp/pk_call.h"
#include <mgba-util/crc32.h>
#include <mgba/core/core.h>
#include <string.h>

#define FLASH_MOVE 148
#define GBA_ROM 0x08000000u

static const uint8_t *rom_at(const uint8_t *rom, size_t len, uint32_t addr, size_t n) {
  if (addr < GBA_ROM || addr - GBA_ROM > len || len - (addr - GBA_ROM) < n)
    return NULL;
  return rom + (addr - GBA_ROM);
}

bool flash_supported(const struct pd_config *cfg, const uint8_t *rom, size_t len) {
  if (!cfg || !cfg->flashFlagGet || !rom)
    return false;
  // FlashFns.functions: FlagGet, SetUpFieldMove_Flash, then each post-menu callback, each checked by its first 32 bytes.
  uint32_t fns[4] = {cfg->flashFlagGet, cfg->flashSetUp, cfg->flashCallbacks[0], cfg->flashCallbacks[1]};
  for (int i = 0; i < 4 && fns[i]; i++) {
    const uint8_t *code = rom_at(rom, len, fns[i] & ~1u, 32);
    if (!code || doCrc32(code, 32) != cfg->flashCrcs[i])
      return false;
  }
  // The party menu's badge check, where the record names it: retail's bytes, or no FLASH here.
  if (cfg->flashBadgeCheck) {
    const uint8_t *check = rom_at(rom, len, cfg->flashBadgeCheck, 16);
    if (!check || doCrc32(check, 16) != cfg->flashBadgeCheckCrc)
      return false;
  }
  return true;
}

int flash_flasher(const struct pd_snapshot *snap) {
  if (!snap || !snap->valid)
    return -1;
  for (int i = 0; i < snap->partyCount && i < PD_PARTY_SIZE; i++) {
    const struct pd_mon *m = &snap->party[i];
    if (m->species == 0 || m->egg || m->species == PD_SPECIES_EGG)
      continue;
    for (int k = 0; k < PD_NUM_MOVES; k++)
      if (m->moves[k] == FLASH_MOVE)
        return i;
  }
  return -1;
}

int flash_dark(struct mCore *core, const struct pd_config *cfg, const struct pd_snapshot *snap) {
  if (!cfg || !cfg->flashMapHeader || !snap || !snap->flagsOk)
    return -1;
  int cave = core->busRead8(core, cfg->flashMapHeader + (uint32_t)cfg->flashCaveOff);
  return cave == 1 && !pd_flag(snap, cfg->flashLitFlag);
}

static bool call(struct mCore *core, uint32_t fn, uint32_t a0, uint32_t *r0) {
  const uint32_t args[4] = {a0, 0, 0, 0};
  uint32_t got = 0;
  if (!pk_call(core, fn, args, &got))
    return false;
  if (r0)
    *r0 = got;
  return true;
}

enum flash_result flash_use(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                            const struct pd_snapshot *snap, bool busy, bool battle) {
  if (!flash_supported(cfg, rom, len))
    return FLASH_FAILED;
  if (battle)
    return FLASH_IN_BATTLE;
  if (busy)
    return FLASH_NOT_NOW;
  if (!pk_call_safe(core))
    return FLASH_FAILED;
  int slot = flash_flasher(snap);
  if (slot < 0)
    return FLASH_NO_FLASHER;
  uint32_t r = 0;
  if (!call(core, cfg->flashFlagGet, (uint32_t)cfg->flashBadgeFlag, &r))
    return FLASH_FAILED;
  if (r != 1)
    return FLASH_NO_BADGE;
  // SetUpFieldMove_Flash answers with the party menu's callbacks: they're taken, then put back as they were, the menu
  // never having opened.
  uint32_t oldCb = core->busRead32(core, cfg->flashMenuCb), oldPost = core->busRead32(core, cfg->flashPostCb);
  uint8_t oldSlot = core->busRead8(core, cfg->flashSlot);
  core->busWrite8(core, cfg->flashSlot, (uint8_t)slot);
  if (core->busRead8(core, cfg->flashSlot) != (uint8_t)slot)
    return FLASH_FAILED;
  uint32_t ok = 0;
  bool ran = call(core, cfg->flashSetUp, 0, &ok);
  uint32_t post = core->busRead32(core, cfg->flashPostCb);
  core->busWrite32(core, cfg->flashMenuCb, oldCb);
  core->busWrite32(core, cfg->flashPostCb, oldPost);
  if (!ran)
    return FLASH_FAILED;
  if (ok != 1) {
    core->busWrite8(core, cfg->flashSlot, oldSlot);
    uint32_t lit = 0;
    return call(core, cfg->flashFlagGet, (uint32_t)cfg->flashLitFlag, &lit) && lit == 1 ? FLASH_ALREADY_LIT
                                                                                          : FLASH_CANT_USE_HERE;
  }
  // Only a callback the record names runs (never a pointer we don't know); the cut-in reads the slot a few frames on.
  bool known = false;
  for (int i = 0; i < 2; i++)
    known |= cfg->flashCallbacks[i] && (cfg->flashCallbacks[i] & ~1u) == (post & ~1u);
  if (!known)
    return FLASH_FAILED;
  return call(core, post, 0, NULL) ? FLASH_OK : FLASH_FAILED;
}

const char *flash_reason(enum flash_result r) {
  switch (r) {
  case FLASH_NOT_NOW: return "Not now: close the game's menus and battles first.";
  case FLASH_IN_BATTLE: return "You can't do that during a battle.";
  case FLASH_NO_BADGE: return "FLASH needs a new BADGE first.";
  case FLASH_NO_FLASHER: return "No one in your party knows FLASH";
  case FLASH_CANT_USE_HERE: return "It isn't dark here";
  case FLASH_ALREADY_LIT: return "This place is lit already.";
  case FLASH_OK: return NULL;
  default: return "It can't be used here.";
  }
}
