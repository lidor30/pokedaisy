// The battle's touch controls: the app's BattleInputController.kt (Gen 3 games) in C. Drives the game's own battle
// menu (FIGHT / BAG / POKéMON / RUN, then a move, or a Pokémon to switch to) with button presses a player could
// make, closed loop on the game's menu cursors where they're known. No ROM patch; the host ORs nothing in: while
// pd_binput_tick says exclusive, the player's own keys stay out.
#ifndef PD_BINPUT_H
#define PD_BINPUT_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_game.h"
#include "pd_field_input.h"
#include "pd_snapshot.h"

enum pd_battle_action { PD_ACT_FIGHT, PD_ACT_BAG, PD_ACT_POKEMON, PD_ACT_RUN };

// The GBA's keys (GBA_KEY_*).
enum {
    PD_KEY_A = 1 << 0, PD_KEY_B = 1 << 1, PD_KEY_SELECT = 1 << 2, PD_KEY_START = 1 << 3, PD_KEY_RIGHT = 1 << 4,
    PD_KEY_LEFT = 1 << 5, PD_KEY_UP = 1 << 6, PD_KEY_DOWN = 1 << 7, PD_KEY_R = 1 << 8, PD_KEY_L = 1 << 9,
};

// Writes len bytes of game RAM; false if it can't.
typedef bool (*pd_write_fn)(void* ctx, uint32_t addr, const void* data, size_t len);

#define PD_BINPUT_QUEUE 32

struct pd_gb_config;

struct pd_binput {
    const struct pd_game* game;
    struct pd_field_input field;
    const struct pd_config* cfg;
    const struct pd_gb_config* gb; // a Game Boy game: its menus are read and steered instead
    pd_read_fn read;
    pd_write_fn write;
    void* ctx;

    struct { uint16_t bits, frames; } queue[PD_BINPUT_QUEUE];
    int queueHead, queueCount;
    int currentBits, framesLeft;
    enum pd_battle_state state;
    int battler;
    int pendingMove; // -1 = none

    int pickCell; // a closed-loop pick in flight, -1 = none
    bool pickMove;
    int pickPresses;

    bool awaitingTarget;

    uint32_t switchTarget; // the Pokémon's personality; 0 = no switch running
    int switchPhase, switchFrames, switchPresses, switchLastSlot;

    // A Game Boy pick (driveGbPickLocked): the menu it waits on, its cell, the frame of the last A (-1 = none yet).
    enum pd_battle_state gbPickMenu;
    int gbPickCell, gbPickPresses, gbPickFrames, gbPickA;
    bool gbForced, gbSwitchPressed;
    int gbLastA, gbAPresses;

    int runPresses, runGap;
    bool runLeftMenu;
    bool runAsksFirst; // the foe is shiny on a game that asks first: RUN stops at the pick

    int watchFrames, exclusiveFrames, holdTail;
    bool holdFrame; // the game's party menu is being driven: keep the last frame on screen
};

void pd_binput_init(struct pd_binput* b, const struct pd_game* g, pd_read_fn read, pd_write_fn write, void* ctx);
// The game has the handlers this needs (hasBattleInputAddrs).
bool pd_binput_available(const struct pd_binput* b);
// [switchTo] can run here: gPartyMenu is known.
bool pd_binput_can_switch(const struct pd_binput* b);

// Once per emulated frame, before the core's keys are set: what to press; *exclusive = the player's keys stay out.
uint32_t pd_binput_tick(struct pd_binput* b, bool* exclusive);
enum pd_battle_state pd_binput_state(const struct pd_binput* b);
// A sequence hasn't drained, or a move waits for its menu: taps wait.
// Presses playing or a closed loop steering (a field shortcut too): the player's keys stay out. Not the passive watches
// pd_binput_busy also counts (a pending move, a target confirm), which must never lock the player out.
bool pd_binput_exclusive(const struct pd_binput* b);
bool pd_binput_busy(const struct pd_binput* b);

// The field and battle actions use the same key queue and cannot run concurrently.
enum pd_field_request { PD_FIELD_BAG, PD_FIELD_ITEM, PD_FIELD_USE, PD_FIELD_HEAL, PD_FIELD_FLY };
bool pd_binput_field_start(struct pd_binput* b, enum pd_field_request request, unsigned item, unsigned slot);
void pd_binput_action(struct pd_binput* b, enum pd_battle_action action);
void pd_binput_move(struct pd_binput* b, int index);
void pd_binput_switch(struct pd_binput* b, uint32_t personality);
void pd_binput_back(struct pd_binput* b);
void pd_binput_cancel(struct pd_binput* b);

#endif
