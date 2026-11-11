/* What is still not on the armGBA core: Game Boy games (gb_field.c, pk_wide_gb.c, both written against mGBA's SM83). EXPAND (pk_wide), the game's own functions called between
 * frames (pk_call: FLY, PC, REPEL, BIKE, the wild-encounter switch) and the field read are written against mGBA's CPU and
 * renderer structures; armGBA's are its own. Until they are ported (plorpos-oe0.45.2 and .3) every one of them says "not
 * supported", so the companion (party, bag, dex, map, guide, settings, the HUD's readouts) runs and the rest is hidden.
 * GPL-3.0, see ../NOTICE. */
#include "field_items.h"
#include "fly.h"
#include "gb_field.h"
#include "pk_wide.h"
#include "pk_wide_gb_profile.h"
#include "polished_field.h"

#include <stdbool.h>
#include <stdint.h>

/* gb_field.c: Game Boy games are not on this core */
enum fly_result gb_field_fly(struct mCore *core, const struct gb_field *f, const uint8_t *rom, size_t len, int mapsec,
                             bool busy) {
  (void)core; (void)f; (void)rom; (void)len; (void)mapsec; (void)busy;
  return FLY_FAILED;
}
void gb_field_fly_targets(struct mCore *core, const struct gb_field *f, const uint8_t *rom, size_t len, bool busy,
                          struct fly_targets *out) {
  (void)core; (void)f; (void)rom; (void)len; (void)busy;
  *out = (struct fly_targets){.result = FLY_FAILED};
}
void gb_field_hold_wild(struct mCore *core, const struct gb_field *f) { (void)core; (void)f; }
enum item_result gb_field_pc(struct mCore *core, const struct gb_field *f, bool busy) {
  (void)core; (void)f; (void)busy;
  return ITEM_FAILED;
}
bool gb_field_release_wild(struct mCore *core, const struct gb_field *f, const uint8_t *rom, size_t len) {
  (void)core; (void)f; (void)rom; (void)len;
  return false;
}
bool gb_field_taken(struct mCore *core, const struct gb_field *f) { (void)core; (void)f; return false; }

/* pk_wide_gb.c, which pk_wide.c hands every call to while a Game Boy game is configured: none are on this core, so
 * pk_wide_gb_knows / _configure say no and the rest are never reached. */
int pk_wide_gb_knows(uint32_t crc) { (void)crc; return 0; }
int pk_wide_gb_configure(uint32_t crc) { (void)crc; return 0; }
void pk_wide_gb_reset(void) {}
int pk_wide_gb_scene(void) { return 0; }
int pk_wide_gb_wants_close(void) { return 0; }
int pk_wide_gb_last_mode(void) { return 0; }
int pk_wide_gb_last_score(void) { return 0; }
int pk_wide_gb_last_torn(void) { return 0; }
int pk_wide_gb_glow(struct mCore *core, const uint32_t *frame, uint32_t *out, struct PkWideView v) {
  (void)core; (void)frame; (void)out; (void)v;
  return 0;
}
int pk_wide_gb_view(struct mCore *core, const uint32_t *frame, uint32_t *out, struct PkWideView v, uint32_t *centre) {
  (void)core; (void)frame; (void)out; (void)v; (void)centre;
  return 0;
}

/* Polished Crystal (a Game Boy Color game): its PC and wild-encounter hooks work through the Game Boy adapter, and its wide
 * profile through pk_wide_gb.c, none of which are on this core. */
const char *polished_pc_reason(const char *sha, pd_read_fn read, void *ctx) {
  (void)sha; (void)read; (void)ctx;
  return "Not on this core";
}
bool polished_pc_open(struct mCore *core, const char *sha, pd_read_fn read, void *ctx) {
  (void)core; (void)sha; (void)read; (void)ctx;
  return false;
}
bool polished_wild_hold(struct mCore *core, const char *sha, pd_read_fn read, void *ctx) {
  (void)core; (void)sha; (void)read; (void)ctx;
  return false;
}
int pk_wide_gb_configure_polished(const uint32_t addresses[GB_CONFIG], pk_wide_gb_decode_fn decode) {
  (void)addresses; (void)decode;
  return 0;
}
