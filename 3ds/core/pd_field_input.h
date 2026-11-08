/* GPL-3.0. Observations and requests for the shared controller's Polished Crystal field actions. */
#ifndef PD_FIELD_INPUT_H
#define PD_FIELD_INPUT_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "pd_snapshot.h"
enum pd_field_input_result {
  PD_FIELD_IDLE,
  PD_FIELD_RUNNING,
  PD_FIELD_DONE,
  PD_FIELD_REJECTED,
  PD_FIELD_TIMEOUT,
  PD_FIELD_CANCELLED
};
struct pd_field_input {
  enum pd_field_input_result result;
  unsigned frames, phase, phaseFrame, stable;
  int target;
  unsigned item, quantity, pocket, slot;
  unsigned flyMenu;
  uint8_t partyRecord[64];
  unsigned useKind, beforeState, healSlot, beforeHP;
  bool effectSeen;
};
const char *pd_field_input_reason(const char *sha, pd_read_fn, void *ctx);
/* Supported only for verified normal Polished Crystal 3.2.3.
 * Opens the original Bag and hands control back to the player. */
bool pd_field_input_begin(struct pd_field_input *, const char *sha,
                         pd_read_fn, void *ctx);
/* Find a unique ordinary or key item and open its original action menu.
 * No selection is made inside that menu; the game owns legality and targeting. */
bool pd_field_input_begin_item(struct pd_field_input *, const char *sha, unsigned item,
                              pd_read_fn, void *ctx);
/* Complete only the verified Bicycle / Repel actions, including returning to
 * the free overworld. R2's general item-action menu remains a separate API. */
bool pd_field_input_begin_use(struct pd_field_input *,const char *sha,unsigned item,
                            pd_read_fn,void *ctx);
bool pd_field_input_begin_heal(struct pd_field_input *,const char *,unsigned,unsigned,pd_read_fn,void *);
/* Fly uses the original party submenu and destination map. No destination,
 * badge, move, cursor or party writes; the user selects the actual flight. */
int pd_field_input_flyer(const char *sha, pd_read_fn, void *ctx);
const char *pd_field_input_fly_reason(const char *sha, pd_read_fn, void *ctx);
bool pd_field_input_begin_fly(struct pd_field_input *,const char *sha,
                            const uint8_t *rom,size_t romSize,pd_read_fn,void *ctx);
unsigned pd_field_input_tick(struct pd_field_input *, pd_read_fn,
                            void *ctx);
void pd_field_input_cancel(struct pd_field_input *);
const char *pd_field_input_message(const struct pd_field_input *);
#endif
