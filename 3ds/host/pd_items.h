// USE on a Pokémon, GIVE and TAKE: the app's FieldItems.kt (partyTargets, useOnMon, giveToMon, takeFromMon) over the
// config's party functions and pk_call. Everything happens between frames, when pk_call_safe says it can.
#ifndef PD_ITEMS_H
#define PD_ITEMS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "../core/pd_game.h"
#include "../core/pd_snapshot.h"

struct mCore;

enum pd_item_outcome {
    PD_ITEM_USED, PD_ITEM_NOT_NOW, PD_ITEM_NONE_LEFT, PD_ITEM_FAILED, PD_ITEM_NO_EFFECT, PD_ITEM_GIVEN, PD_ITEM_BAG_FULL,
    PD_ITEM_PARTY_CHANGED, PD_ITEM_TAKEN,
};

enum pd_slot_verdict { PD_SLOT_OK, PD_SLOT_NO_EFFECT, PD_SLOT_EGG, PD_SLOT_HOLDS_MAIL, PD_SLOT_HOLDS_IT };

struct pd_party_target {
    uint32_t personality;
    int heldItem;
    enum pd_slot_verdict verdict;
    bool moves[4]; // a one-move PP item: which moves it would help
};

struct pd_party_targets {
    enum pd_item_outcome result; // PD_ITEM_USED = pick from these
    int count;
    struct pd_party_target slots[PD_PARTY_SIZE];
};

// What the ROM allows: built once a game (from its gItems). NULL party functions = nothing here.
bool pd_items_setup(const struct pd_config* cfg, const uint8_t* rom, size_t romSize);
bool pd_items_can_use(int item);
bool pd_items_can_give(int item);
bool pd_items_picks_move(int item);
bool pd_items_can_take(void);

void pd_items_targets(struct mCore* core, const struct pd_config* cfg, int item, bool give, bool busy, struct pd_party_targets* out);
enum pd_item_outcome pd_items_use(struct mCore* core, const struct pd_config* cfg, int item, int slot, uint32_t personality,
                                  int move, bool busy);
enum pd_item_outcome pd_items_give(struct mCore* core, const struct pd_config* cfg, int item, int slot, uint32_t personality,
                                   bool busy, int* swapped);
enum pd_item_outcome pd_items_take(struct mCore* core, const struct pd_config* cfg, int slot, uint32_t personality, bool busy,
                                   int* held);
// The PC's moves: BoxSwap.kt. Box numbers are 0-based; [boxCount] slots of 80 bytes per box as the game keeps them.
enum pd_box_outcome {
    PD_BOX_DONE, PD_BOX_NOT_NOW, PD_BOX_CHANGED, PD_BOX_HOLDS_MAIL, PD_BOX_LAST_MON, PD_BOX_PARTY_FULL, PD_BOX_FULL, PD_BOX_FAILED,
};
struct pd_box_swap;
bool pd_box_moves_ok(const struct pd_config* cfg, const struct pd_box_swap* swap, const uint8_t* rom, size_t romSize);
enum pd_box_outcome pd_box_withdraw(struct mCore* core, const struct pd_config* cfg, int box, int slot, uint32_t personality, bool busy);
enum pd_box_outcome pd_box_deposit(struct mCore* core, const struct pd_config* cfg, int partySlot, uint32_t personality, int box, bool busy);
enum pd_box_outcome pd_box_swap(struct mCore* core, const struct pd_config* cfg, int box, int slot, uint32_t boxPersonality, int partySlot,
                                uint32_t partyPersonality, bool busy);
const char* pd_box_outcome_text(enum pd_box_outcome o);

// The app's messages for what happened.
const char* pd_item_outcome_text(enum pd_item_outcome o);

#endif
