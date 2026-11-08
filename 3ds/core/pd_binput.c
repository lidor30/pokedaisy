#include "pd_binput.h"

#include <string.h>

#include "pd_gb.h"

#define PARTY_MENU_SLOT_ID 9   // struct PartyMenu.slotId, both games
#define PARTY_MENU_CANCEL 7    // slotId on CANCEL (after the last Pokémon), both games
#define MENU_TIMEOUT_FRAMES 240 // the menu's fade-in drops presses for ~50 frames (headless, both games)
#define CURSOR_FRAMES 3         // after each press, before reading the cursor again (it moves every 3)
#define MAX_PICK_PRESSES 8      // a 2x2 grid needs two; the rest re-press dropped ones
#define MAX_CURSOR_PRESSES 16   // steps the cursor took: two laps of six slots + CANCEL, then give up
#define SUBMENU_FRAMES 8        // the SHIFT / SUMMARY / CANCEL menu opening (takes A after 6 on FireRed, 2 on Emerald)
#define CLOSE_TIMEOUT_FRAMES 240
#define RUN_PRESSES 6
#define RUN_FIRST_GAP 45        // the escape message starts printing
#define RUN_PRESS_GAP 30
#define HOLD_TAIL_FRAMES 12
#define WATCH_TIMEOUT_FRAMES 300    // a target screen / FIGHT that hasn't shown by then won't
#define EXCLUSIVE_TIMEOUT_FRAMES 1200 // a switch's worst case (every step timing out) is ~800
#define GB_PRESS_FRAMES 2       // Prism's party menu reads keys every other frame (headless)
#define GB_SETTLE_FRAMES 2      // after a Game Boy A: a taken pick has turned the cursor hollow
#define GB_REPRESS_FRAMES 8     // a Game Boy menu still waiting this long after an A dropped it
#define MAX_GB_A_PRESSES 12     // Crystal's SWITCH menu drops presses for ~30 frames after it opens
#define GB_PICK_SETTLE_FRAMES 4 // after a battle menu's cursor step: Polished Crystal drops presses for 3
#define MAX_GB_PICK_PRESSES 12  // two steps at most; the rest re-press dropped ones
#define GB_HOLD_TAIL_FRAMES 20  // white, then the battle back: ~8 frames on Yellow, ~14 on Crystal
#define SETTLE_FRAMES 20        // a real cursor move starts a palette fade that eats presses (Unbound's move screen)

enum { SWITCH_WAIT_MENU, SWITCH_NAVIGATE, SWITCH_CONFIRM, SWITCH_WAIT_CLOSE };

void pd_binput_init(struct pd_binput* b, const struct pd_game* g, pd_read_fn read, pd_write_fn write, void* ctx) {
    memset(b, 0, sizeof(*b));
    b->game = g;
    b->cfg = g->cfg;
    b->gb = g->cfg ? g->cfg->gb : NULL;
    b->gbPickCell = -1;
    b->read = read;
    b->write = write;
    b->ctx = ctx;
    b->pendingMove = -1;
    b->pickCell = -1;
}

bool pd_binput_available(const struct pd_binput* b) {
    if (b->gb) return true;
    return b->cfg && b->cfg->battlerControllerFuncs && b->cfg->handleInputChooseAction && b->cfg->handleInputChooseMove;
}

bool pd_binput_can_switch(const struct pd_binput* b) {
    if (b->gb) return true;
    return pd_binput_available(b) && b->cfg->partyMenu && b->cfg->waitForMonSelection;
}

static void enqueue(struct pd_binput* b, int bits, int frames) {
    if (b->queueCount >= PD_BINPUT_QUEUE) return;
    int at = (b->queueHead + b->queueCount++) % PD_BINPUT_QUEUE;
    b->queue[at].bits = (uint16_t) bits;
    b->queue[at].frames = (uint16_t) frames;
}

static void pulse(struct pd_binput* b, int bit, int hold, int release) {
    enqueue(b, bit, hold);
    enqueue(b, 0, release);
}

// One real frame pressed, one released: JOY_NEW only fires on the frame a bit goes from unset to set, so back-to-back
// presses of the same button need a release between them.
static void press(struct pd_binput* b, int bit) {
    pulse(b, bit, 1, 1);
}

static void press_and_settle(struct pd_binput* b, int bit) {
    press(b, bit);
    enqueue(b, 0, SETTLE_FRAMES);
}

static bool exclusive(const struct pd_binput* b) {
    return b->queueCount > 0 || b->framesLeft > 0 || b->switchTarget || b->runPresses > 0 || b->pickCell >= 0 || b->gbPickCell >= 0;
}

bool pd_binput_exclusive(const struct pd_binput* b) {
    return b->field.result == PD_FIELD_RUNNING || exclusive(b);
}

bool pd_binput_busy(const struct pd_binput* b) {
    return b->field.result == PD_FIELD_RUNNING || exclusive(b) || b->pendingMove >= 0 || b->awaitingTarget;
}

enum pd_battle_state pd_binput_state(const struct pd_binput* b) {
    return b->state;
}

void pd_binput_cancel(struct pd_binput* b) {
    b->queueCount = 0;
    b->pickCell = b->gbPickCell = -1;
    pd_field_input_cancel(&b->field);
    b->pendingMove = -1;
    b->awaitingTarget = false;
    b->switchTarget = 0;
    b->runPresses = 0;
    b->currentBits = 0;
    b->framesLeft = 0;
    b->watchFrames = 0;
    b->exclusiveFrames = 0;
    b->holdTail = 0;
    b->holdFrame = false;
}

// The walk with no cursor known: LEFT / UP unconditionally (each a no-op if already there), then RIGHT / DOWN only if
// the target needs them, then A, each press followed by a settling gap.
static void queue_cell(struct pd_binput* b, int cell) {
    press_and_settle(b, PD_KEY_LEFT);
    press_and_settle(b, PD_KEY_UP);
    if (cell & 1) press_and_settle(b, PD_KEY_RIGHT);
    if (cell & 2) press_and_settle(b, PD_KEY_DOWN);
    press(b, PD_KEY_A);
}

static void pick_cell(struct pd_binput* b, int cell, bool move) {
    if (!b->cfg->actionCursor || !b->cfg->moveCursor) {
        queue_cell(b, cell);
        return;
    }
    b->pickCell = cell;
    b->pickMove = move;
    b->pickPresses = 0;
}

static void pick_fallback(struct pd_binput* b) {
    int cell = b->pickCell;
    b->pickCell = -1;
    queue_cell(b, cell);
}

// One step of a closed-loop pick: on the cell, A; else the cell is written (the game reads the cursor on A and draws
// it from there the next time the menu opens: one select sound, not one per step), and if that doesn't take, the
// press toward it (a dropped one is pressed again).
static void drive_pick(struct pd_binput* b) {
    if (b->pickCell < 0) return;
    uint32_t addr = (b->pickMove ? b->cfg->moveCursor : b->cfg->actionCursor) + (uint32_t) b->battler;
    uint8_t cur = 0xFF;
    if (!b->read(b->ctx, addr, &cur, 1) || cur > 3) {
        pick_fallback(b);
        return;
    }
    if (cur != b->pickCell && b->write && b->pickPresses == 0) {
        uint8_t cell = (uint8_t) b->pickCell, back = 0xFF;
        if (b->write(b->ctx, addr, &cell, 1) && b->read(b->ctx, addr, &back, 1) && back == cell) {
            b->pickCell = -1;
            press(b, PD_KEY_A);
            return;
        }
    }
    if (cur == b->pickCell) {
        b->pickCell = -1;
        press(b, PD_KEY_A);
        return;
    }
    if (++b->pickPresses > MAX_PICK_PRESSES) {
        pick_fallback(b);
        return;
    }
    if ((cur & 1) != (b->pickCell & 1)) press(b, b->pickCell & 1 ? PD_KEY_RIGHT : PD_KEY_LEFT);
    else press(b, (b->pickCell >> 1) > (cur >> 1) ? PD_KEY_DOWN : PD_KEY_UP);
}

static void end_switch(struct pd_binput* b) {
    b->switchTarget = 0;
    b->holdTail = HOLD_TAIL_FRAMES;
}

// gPlayerParty's slot holding the personality (the party menu's slot while it's open: OpenPartyMenuInBattle reorders
// it to battle order), or -1; *count = the slots holding a Pokémon.
static int slot_of(struct pd_binput* b, uint32_t personality, int* count) {
    int found = -1, n = 0;
    for (int i = 0; i < PD_PARTY_SIZE; i++) {
        uint32_t p = 0;
        if (!b->read(b->ctx, b->cfg->playerParty + (uint32_t) (i * (uint32_t) b->cfg->monStride), &p, 4)) return -1;
        if (p) n++;
        if (p == personality && found < 0) found = i;
    }
    *count = n ? n : 1;
    return found;
}

// DOWN or UP, the shorter way from slot to want round a wrapping list of count Pokémon, then CANCEL.
static int list_key(int slot, int want, int count) {
    int ring[PD_PARTY_SIZE + 1], size = 0;
    for (int i = 0; i < count; i++) ring[size++] = i;
    ring[size++] = PARTY_MENU_CANCEL;
    int i = -1, j = -1;
    for (int k = 0; k < size; k++) {
        if (ring[k] == slot) i = k;
        if (ring[k] == want) j = k;
    }
    if (i < 0 || j < 0) return PD_KEY_DOWN;
    int down = (j - i + size) % size;
    return down <= size - down ? PD_KEY_DOWN : PD_KEY_UP;
}

static void drive_gb_switch(struct pd_binput* b);

static void drive_switch(struct pd_binput* b) {
    if (!b->switchTarget) return;
    b->switchFrames++;
    if (b->gb) {
        drive_gb_switch(b);
        return;
    }
    switch (b->switchPhase) {
    case SWITCH_WAIT_MENU:
        // No fixed settle: NAVIGATE presses at once, and what the menu drops while it fades in is pressed again.
        if (b->state == PD_BATTLE_PARTY_OPEN) {
            b->switchPhase = SWITCH_NAVIGATE;
            b->switchFrames = 0;
            b->switchLastSlot = -1;
        } else if (b->switchFrames > MENU_TIMEOUT_FRAMES) {
            end_switch(b);
        }
        break;
    case SWITCH_NAVIGATE: {
        uint8_t slot = 0;
        if (!b->read(b->ctx, b->cfg->partyMenu + PARTY_MENU_SLOT_ID, &slot, 1)) {
            end_switch(b);
            break;
        }
        int count = 0, want = slot_of(b, b->switchTarget, &count);
        if (want < 0) {
            end_switch(b);
            break;
        }
        // Steps the cursor really took (dropped presses don't count).
        if (slot != b->switchLastSlot) {
            if (b->switchLastSlot >= 0) b->switchPresses++;
            b->switchLastSlot = slot;
        }
        if (slot == want) {
            press(b, PD_KEY_A); // the Pokémon -> its SHIFT / SUMMARY / CANCEL menu
            enqueue(b, 0, SUBMENU_FRAMES);
            b->switchPhase = SWITCH_CONFIRM;
        } else if (b->switchPresses > MAX_CURSOR_PRESSES || b->switchFrames > MENU_TIMEOUT_FRAMES) {
            end_switch(b);
        } else {
            int key;
            if (!b->cfg->partyMenuGrid) key = list_key(slot, want, count);
            else if (slot >= PD_PARTY_SIZE) key = PD_KEY_UP; // CANCEL: back into the grid
            else if (slot % 2 != want % 2) key = want % 2 ? PD_KEY_RIGHT : PD_KEY_LEFT;
            else key = want > slot ? PD_KEY_DOWN : PD_KEY_UP;
            press(b, key);
            enqueue(b, 0, CURSOR_FRAMES);
        }
        break;
    }
    case SWITCH_CONFIRM:
        press(b, PD_KEY_A); // SHIFT, the menu's first entry
        b->switchPhase = SWITCH_WAIT_CLOSE;
        b->switchFrames = 0;
        break;
    case SWITCH_WAIT_CLOSE:
        // The player's keys stay out until the game has taken the switch.
        if (b->state != PD_BATTLE_PARTY_OPEN || b->switchFrames > CLOSE_TIMEOUT_FRAMES) end_switch(b);
        break;
    }
}

static void drive_run(struct pd_binput* b) {
    // RUN's own pick (to the cell, then A) goes first.
    if (b->runPresses <= 0 || b->pickCell >= 0) return;
    if (b->state == PD_BATTLE_NONE || (b->runLeftMenu && b->state == PD_BATTLE_ACTION)) {
        b->runPresses = 0; // got away, or back on the menu (couldn't escape)
        return;
    }
    if (b->state != PD_BATTLE_ACTION) b->runLeftMenu = true;
    if (--b->runGap > 0) return;
    b->runGap = RUN_PRESS_GAP;
    switch (b->state) {
    case PD_BATTLE_BUSY: // a message or a YES / NO: A closes the one and says YES to the other
        b->runLeftMenu = true;
        press(b, PD_KEY_A);
        b->runPresses--;
        break;
    case PD_BATTLE_ACTION: // still leaving the menu, or back on it: nothing to press this time
        b->runPresses--;
        break;
    default: // a party menu after a faint: done
        b->runPresses = 0;
        break;
    }
}

// A Game Boy party menu's press: held and released two frames each (Prism's party menu reads keys every other frame).
static void press_gb(struct pd_binput* b, int bit) {
    pulse(b, bit, GB_PRESS_FRAMES, GB_PRESS_FRAMES);
}

static void gb_pick(struct pd_binput* b, enum pd_battle_state menu, int cell) {
    b->gbPickMenu = menu;
    b->gbPickCell = cell;
    b->gbPickPresses = 0;
    b->gbPickFrames = 0;
    b->gbPickA = -1;
}

// One step of a Game Boy pick, on the menu read straight from RAM: a press toward the cell, read again; A once there; A
// again while the menu still waits on that cell GB_REPRESS_FRAMES later (a taken pick turns the cursor hollow at once).
static void drive_gb_pick(struct pd_binput* b) {
    if (b->gbPickCell < 0) return;
    int cur;
    enum pd_battle_state state = pd_gb_battle_menu(b->gb, b->read, b->ctx, &cur);
    bool waiting = state == b->gbPickMenu;
    if (b->gbPickA >= 0) {
        if (!waiting || cur != b->gbPickCell) {
            b->gbPickCell = -1; // taken: the game moved on
            return;
        }
        if (b->gbPickFrames - b->gbPickA < GB_REPRESS_FRAMES) return;
        if (++b->gbPickPresses > MAX_GB_PICK_PRESSES) {
            b->gbPickCell = -1;
            return;
        }
        press(b, PD_KEY_A);
        b->gbPickA = b->gbPickFrames;
        return;
    }
    if (!waiting) {
        if (b->gbPickFrames > MENU_TIMEOUT_FRAMES) b->gbPickCell = -1;
        return;
    }
    if (cur == b->gbPickCell) {
        press(b, PD_KEY_A);
        b->gbPickA = b->gbPickFrames;
        return;
    }
    if (++b->gbPickPresses > MAX_GB_PICK_PRESSES) {
        b->gbPickCell = -1;
        return;
    }
    int key;
    if (b->gbPickMenu == PD_BATTLE_MOVE) key = b->gbPickCell > cur ? PD_KEY_DOWN : PD_KEY_UP;
    else if ((cur & 1) != (b->gbPickCell & 1)) key = (b->gbPickCell & 1) ? PD_KEY_RIGHT : PD_KEY_LEFT;
    else key = (b->gbPickCell >> 1) ? PD_KEY_DOWN : PD_KEY_UP;
    press(b, key);
    enqueue(b, 0, GB_PICK_SETTLE_FRAMES);
}

// Why the Pokémon keyed [key] can't go in (not in the party, fainted, already out).
static bool gb_refused(struct pd_binput* b, uint32_t key) {
    struct pd_gb_party_mon party[PD_PARTY_SIZE];
    int n = pd_gb_party(b->gb, b->read, b->ctx, party);
    for (int i = 0; i < n; i++)
        if (party[i].key == key) return party[i].hp == 0 || i == pd_gb_out_slot(b->gb, b->read, b->ctx);
    return true;
}

static int gb_list_key(int slot, int want, int count, bool hasCancel) {
    int rows = count + (hasCancel ? 1 : 0);
    int from = slot == PD_GB_PARTY_CANCEL ? count : slot;
    int down = (want - from + rows) % rows;
    return down <= rows - down ? PD_KEY_DOWN : PD_KEY_UP;
}

static void drive_gb_switch(struct pd_binput* b) {
    struct pd_gb_party_menu menu = pd_gb_party_menu(b->gb, b->read, b->ctx);
    switch (b->switchPhase) {
    case SWITCH_WAIT_MENU:
        if (menu.open) {
            b->switchPhase = SWITCH_NAVIGATE;
            b->switchFrames = 0;
            b->switchLastSlot = -1;
        } else if (b->switchFrames > MENU_TIMEOUT_FRAMES) {
            end_switch(b);
        }
        break;
    case SWITCH_NAVIGATE: {
        if (b->switchFrames > MENU_TIMEOUT_FRAMES) {
            end_switch(b);
            break;
        }
        if (!menu.open) break; // being drawn
        if (menu.submenu) {    // a SWITCH menu we didn't open: back to the list
            press_gb(b, PD_KEY_B);
            enqueue(b, 0, GB_REPRESS_FRAMES);
            break;
        }
        if (gb_refused(b, b->switchTarget)) {
            end_switch(b);
            break;
        }
        struct pd_gb_party_mon party[PD_PARTY_SIZE];
        int n = pd_gb_party(b->gb, b->read, b->ctx, party), want = -1;
        for (int i = 0; i < n; i++)
            if (party[i].key == b->switchTarget) want = i;
        if (menu.cursor != b->switchLastSlot) {
            if (b->switchLastSlot >= 0) b->switchPresses++;
            b->switchLastSlot = menu.cursor;
        }
        if (menu.cursor == want) {
            press_gb(b, PD_KEY_A); // the Pokémon: SWITCH / STATS / CANCEL, or out after a faint
            enqueue(b, 0, GB_SETTLE_FRAMES);
            b->gbForced = menu.forced;
            b->gbLastA = 0;
            b->gbAPresses = 0;
            b->switchPhase = SWITCH_CONFIRM;
            b->switchFrames = 0;
        } else if (b->switchPresses > MAX_CURSOR_PRESSES) {
            end_switch(b);
        } else {
            press_gb(b, gb_list_key(menu.cursor, want, n, b->gb->gen == 2));
            enqueue(b, 0, CURSOR_FRAMES);
        }
        break;
    }
    case SWITCH_CONFIRM:
        if (!menu.open) { // the pick was taken: the cursor went hollow and the game moves on
            if (b->gbForced || b->gbSwitchPressed) {
                b->switchPhase = SWITCH_WAIT_CLOSE;
                b->switchFrames = 0;
            } else if (b->switchFrames > MENU_TIMEOUT_FRAMES) {
                end_switch(b);
            }
        } else if (menu.submenu) {
            if (menu.cursor != 0) {
                press_gb(b, PD_KEY_UP); // SWITCH is the first item
                enqueue(b, 0, CURSOR_FRAMES);
            } else if (!b->gbSwitchPressed || b->switchFrames - b->gbLastA >= GB_REPRESS_FRAMES) {
                if (++b->gbAPresses > MAX_GB_A_PRESSES) {
                    end_switch(b);
                    break;
                }
                press_gb(b, PD_KEY_A);
                enqueue(b, 0, GB_SETTLE_FRAMES);
                b->gbSwitchPressed = true;
                b->gbLastA = b->switchFrames;
            }
        } else if (b->gbSwitchPressed) {
            end_switch(b); // back on the list after SWITCH: the game said no
        } else if (b->switchFrames >= GB_REPRESS_FRAMES) {
            b->switchPhase = SWITCH_NAVIGATE; // still on the list: the A on the Pokémon was dropped
            b->switchFrames = 0;
            b->switchLastSlot = -1;
        }
        break;
    case SWITCH_WAIT_CLOSE:
        if (!menu.open || b->switchFrames > CLOSE_TIMEOUT_FRAMES) {
            end_switch(b);
            b->holdTail = GB_HOLD_TAIL_FRAMES;
        }
        break;
    }
}

// onState's watches: a move's third "who does this hit" screen, and a move waiting for FIGHT to open.
static void watch_state(struct pd_binput* b) {
    if (b->pickCell >= 0 || b->gbPickCell >= 0) return; // a pick still steering is still on its menu
    if (b->awaitingTarget && b->queueCount == 0 && b->framesLeft == 0) {
        if (b->state == PD_BATTLE_TARGET) {
            b->awaitingTarget = false;
            press(b, PD_KEY_A); // accept the game's own default target
        } else if (b->state != PD_BATTLE_BUSY) {
            b->awaitingTarget = false;
        }
    }
    if (b->pendingMove < 0) return;
    // Doubles' battler-to-position mapping isn't handled (the app's PLAN.md): a pending move is dropped rather than
    // fired into the partner's menu.
    if (b->battler != 0) {
        b->pendingMove = -1;
        return;
    }
    if (b->state == PD_BATTLE_MOVE && b->queueCount == 0 && b->framesLeft == 0) {
        int move = b->pendingMove;
        b->pendingMove = -1;
        if (b->gb) {
            gb_pick(b, PD_BATTLE_MOVE, move); // one list, no target screen
            return;
        }
        pick_cell(b, move, true);
        b->awaitingTarget = true;
    }
}

static void dequeue_frame(struct pd_binput* b) {
    if (b->framesLeft <= 0) {
        if (b->queueCount > 0) {
            b->currentBits = b->queue[b->queueHead].bits;
            b->framesLeft = b->queue[b->queueHead].frames;
            b->queueHead = (b->queueHead + 1) % PD_BINPUT_QUEUE;
            b->queueCount--;
        } else {
            b->currentBits = 0;
            b->framesLeft = 0;
        }
    }
    if (b->framesLeft > 0) b->framesLeft--;
}

bool pd_binput_field_start(struct pd_binput* b, enum pd_field_request request, unsigned item, unsigned slot) {
    if (!b->gb || pd_binput_busy(b)) return false;
    const char* sha = b->game->sha1;
    switch (request) {
    case PD_FIELD_BAG: return pd_field_input_begin(&b->field, sha, b->read, b->ctx);
    case PD_FIELD_ITEM: return pd_field_input_begin_item(&b->field, sha, item, b->read, b->ctx);
    case PD_FIELD_USE: return pd_field_input_begin_use(&b->field, sha, item, b->read, b->ctx);
    case PD_FIELD_HEAL: return pd_field_input_begin_heal(&b->field, sha, item, slot, b->read, b->ctx);
    case PD_FIELD_FLY: return pd_field_input_begin_fly(&b->field, sha, b->game->rom, b->game->romSize, b->read, b->ctx);
    }
    return false;
}

uint32_t pd_binput_tick(struct pd_binput* b, bool* excl) {
    *excl = false;
    if (!pd_binput_available(b)) return 0;
    if (b->field.result == PD_FIELD_RUNNING) {
        b->field.frames++;
        if (b->queueCount == 0 && b->framesLeft == 0) {
            const unsigned key = pd_field_input_tick(&b->field, b->read, b->ctx);
            // Start / Pack and text loops can miss a two-frame press depending on their polling phase.
            if (key == PD_KEY_A || key == PD_KEY_B)
                pulse(b, key, 3, GB_REPRESS_FRAMES + GB_SETTLE_FRAMES);
            else if (key)
                pulse(b, key, GB_PRESS_FRAMES, GB_PICK_SETTLE_FRAMES + GB_SETTLE_FRAMES);
        }
        *excl = true;
        dequeue_frame(b);
        return (uint32_t) b->currentBits;
    }
    if (b->gb) {
        int cursor;
        b->state = pd_gb_battle_menu(b->gb, b->read, b->ctx, &cursor);
        b->battler = 0;
        if (b->gbPickCell >= 0) b->gbPickFrames++;
    } else {
        b->state = pd_battle_state_read(b->cfg, b->read, b->ctx, &b->battler);
    }
    watch_state(b);
    // The closed-loop steps queue their next presses only once the last ones have played out.
    if (b->framesLeft <= 0 && b->queueCount == 0) {
        drive_pick(b);
        if (b->gb) drive_gb_pick(b);
        if (b->queueCount == 0 && b->gbPickCell < 0) {
            drive_switch(b);
            drive_run(b);
        }
    }
    dequeue_frame(b);
    // A target screen follows a move's A within a few frames, and FIGHT opens as fast: a watch still unanswered after
    // that is stale.
    if (b->queueCount == 0 && b->framesLeft <= 0 && (b->awaitingTarget || b->pendingMove >= 0)) {
        if (++b->watchFrames > WATCH_TIMEOUT_FRAMES) {
            b->awaitingTarget = false;
            b->pendingMove = -1;
            b->watchFrames = 0;
        }
    } else {
        b->watchFrames = 0;
    }
    // Every sequence is bounded on its own; this is the backstop.
    if (exclusive(b)) {
        if (++b->exclusiveFrames > EXCLUSIVE_TIMEOUT_FRAMES) pd_binput_cancel(b);
    } else {
        b->exclusiveFrames = 0;
    }
    if (!b->switchTarget && b->holdTail > 0) b->holdTail--;
    b->holdFrame = b->switchTarget || b->holdTail > 0;
    *excl = exclusive(b);
    return (uint32_t) b->currentBits;
}

// Gen 3's action order onto a Game Boy battle menu's cells: FIGHT and PkMn on top, ITEM and RUN below.
static void action_pick(struct pd_binput* b, enum pd_battle_action action) {
    static const int GB_CELL[4] = { 0, 2, 1, 3 };
    if (b->gb) gb_pick(b, PD_BATTLE_ACTION, GB_CELL[action]);
    else pick_cell(b, (int) action, false);
}

void pd_binput_action(struct pd_binput* b, enum pd_battle_action action) {
    if (b->field.result == PD_FIELD_RUNNING) return;
    b->pendingMove = -1;
    action_pick(b, action);
    if (action == PD_ACT_RUN) {
        // "Got away safely!" waits for a button: close it (a press while it's still printing just finishes the text).
        b->runPresses = b->runAsksFirst ? 0 : RUN_PRESSES;
        b->runGap = RUN_FIRST_GAP;
        b->runLeftMenu = false;
    }
}

void pd_binput_move(struct pd_binput* b, int index) {
    if (b->field.result == PD_FIELD_RUNNING) return;
    if (b->state == PD_BATTLE_ACTION) {
        b->pendingMove = index;
        action_pick(b, PD_ACT_FIGHT);
    } else if (b->gb) {
        b->pendingMove = -1;
        gb_pick(b, PD_BATTLE_MOVE, index);
    } else {
        b->pendingMove = -1;
        pick_cell(b, index, true);
        b->awaitingTarget = true;
    }
}

void pd_binput_switch(struct pd_binput* b, uint32_t personality) {
    if (b->field.result == PD_FIELD_RUNNING) return;
    if (!pd_binput_can_switch(b) || !personality) return;
    if (b->gb && gb_refused(b, personality)) return;
    b->pendingMove = -1;
    if (b->state == PD_BATTLE_ACTION) action_pick(b, PD_ACT_POKEMON);
    b->gbForced = b->gbSwitchPressed = false;
    b->gbAPresses = 0;
    b->switchTarget = personality;
    b->switchPhase = SWITCH_WAIT_MENU;
    b->switchFrames = 0;
    b->switchPresses = 0;
    b->switchLastSlot = -1;
}

void pd_binput_back(struct pd_binput* b) {
    if (b->field.result == PD_FIELD_RUNNING) return;
    b->pendingMove = -1;
    if (b->gb) press_gb(b, PD_KEY_B);
    else press(b, PD_KEY_B);
}
