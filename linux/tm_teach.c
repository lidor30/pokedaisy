/* USE on a TM / HM (tm_teach.h): app/.../companion/data/TmTeach.kt's tmStart / tmFinish over the config's generated TM
 * fields - keep the two in step. GPL-3.0. */
#include "tm_teach.h"

#include "../app/src/main/cpp/pk_call.h"
#include "field_items.h"
#include <mgba-util/crc32.h>
#include <mgba/core/core.h>

#define GBA_ROM 0x08000000u

static bool crc_ok(const uint8_t *rom, size_t len, uint32_t fn, uint32_t crc) {
  uint32_t addr = fn & ~1u;
  if (addr < GBA_ROM || addr - GBA_ROM > len || len - (addr - GBA_ROM) < 32)
    return false;
  return doCrc32(rom + (addr - GBA_ROM), 32) == crc;
}

bool tm_supported(const struct pd_config *cfg, const uint8_t *rom, size_t len) {
  if (!cfg || !cfg->tmShowParty || !rom || !field_items_supported(cfg, rom, len))
    return false;
  // TmFns.functions' order.
  const uint32_t fns[9] = {cfg->tmItemUseCbTmhm, cfg->tmShowParty, cfg->tmInitPartyMenu, cfg->tmSetMainCallback2,
                           cfg->tmReturnToField, cfg->tmCleanup, cfg->tmFade, cfg->tmLock, cfg->tmUnlock};
  for (int i = 0; i < 9; i++)
    if (!crc_ok(rom, len, fns[i], cfg->tmCrcs[i]))
      return false;
  return true;
}

bool tm_teaches(const struct pd_config *cfg, int item) {
  return cfg && cfg->tmShowParty && item >= cfg->tmFirst && item <= cfg->tmLast;
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

enum tm_result tm_start(struct mCore *core, const struct pd_config *cfg, int item, bool busy) {
  if (!tm_teaches(cfg, item) || !pk_call_safe(core))
    return TM_FAILED;
  uint32_t locked = 0, has = 0;
  if (busy || !call(core, cfg->fieldLocked, 0, 0, &locked) || locked)
    return TM_NOT_NOW;
  if (!call(core, cfg->fieldCheckBag, (uint32_t)item, 1, &has))
    return TM_FAILED;
  if (has != 1)
    return TM_NONE_LEFT;
  if (!core->busRead8(core, cfg->playerPartyCount))
    return TM_FAILED;
  if (!call(core, cfg->tmLock, 0, 0, NULL) || !call(core, cfg->tmFade, 1, 0, NULL))
    return TM_FAILED;
  return TM_STARTED;
}

bool tm_finish(struct mCore *core, const struct pd_config *cfg, int item, bool leftField) {
  if (leftField)
    return false;
  bool ok = call(core, cfg->tmCleanup, 0, 0, NULL);
  if (ok) {
    core->busWrite16(core, cfg->tmItemId, (uint16_t)item);
    core->busWrite32(core, cfg->tmItemUseCb, cfg->tmItemUseCbTmhm | 1);
    core->busWrite8(core, cfg->tmPartyMenu + (uint32_t)cfg->tmPartyMenuSlotOff, 0);
    ok = core->busRead16(core, cfg->tmItemId) == (uint16_t)item && call(core, cfg->tmShowParty, 0, 0, NULL);
  }
  if (ok) {
    if ((core->busRead32(core, cfg->tmMainCallback2) & ~1u) == (cfg->tmInitPartyMenu & ~1u))
      // The party menu is on its way: back to the field when it closes, not to a bag that never opened.
      core->busWrite32(core, cfg->tmPartyMenu, cfg->tmReturnToField | 1);
    else
      // No room for the menu (InitPartyMenu went straight to its exit, the bag): the field instead.
      ok = call(core, cfg->tmSetMainCallback2, cfg->tmReturnToField | 1, 0, NULL);
  }
  if (!ok) {
    call(core, cfg->tmFade, 0, 0, NULL);
    call(core, cfg->tmUnlock, 0, 0, NULL);
  }
  return ok;
}

uint32_t tm_main_callback(struct mCore *core, const struct pd_config *cfg) {
  return cfg && cfg->tmMainCallback2 ? core->busRead32(core, cfg->tmMainCallback2) : 0;
}

const char *tm_reason(enum tm_result r) {
  switch (r) {
  case TM_STARTED: return NULL;
  case TM_NOT_NOW: return "You can't use that now.";
  case TM_NONE_LEFT: return "None left in the bag.";
  default: return "That didn't work.";
  }
}
