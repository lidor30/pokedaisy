#include "pk_coop_api.h"

#include <stdio.h>
#include <string.h>

#include "pk_call.h"
#include "pk_coop.h"
#include "pk_coop_after.h"
#include "pk_coop_field.h"
#include "pk_coop_lock.h"
#include "pk_session.h"
#include "pk_wide.h"

uint32_t pk_coop_game_call(struct mCore* core, uint32_t fn, const uint32_t args[4]) {
    uint32_t r0;
    return pk_call(core, fn, args, &r0) ? r0 : 0xFFFFFFFFu;
}

bool pk_api_coop_enable(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    bool enabled = g.core && pk_is_gba(g.core) && pk_coop_install(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
    return enabled;
}

bool pk_api_coop_link_enable(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    bool enabled = g.core && pk_is_gba(g.core) && pk_coop_link_install(g.core, pk_coop_game_call);
    pthread_rwlock_unlock(&pk_coreLock);
    return enabled;
}

void pk_api_coop_disable(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) {
        pk_coop_link_uninstall(g.core);
        pk_coop_uninstall(g.core);
    }
    pthread_rwlock_unlock(&pk_coreLock);
}

void pk_api_coop_refusal(bool link, char* out, size_t capacity) {
    if (!out || !capacity) return;
    pthread_rwlock_rdlock(&pk_coreLock);
    const char* refusal = link ? pk_coop_link_refused() : pk_coop_refused();
    snprintf(out, capacity, "%s", refusal ? refusal : "");
    pthread_rwlock_unlock(&pk_coreLock);
}

bool pk_api_coop_battle_starting(void) {
    pthread_rwlock_rdlock(&pk_coreLock);
    bool starting = g.core && pk_is_gba(g.core) && pk_coop_battle_starting(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
    return starting;
}

bool pk_api_coop_on_free_field(void) {
    pthread_rwlock_rdlock(&pk_coreLock);
    bool free = g.core && pk_is_gba(g.core) && pk_coop_on_free_field(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
    return free;
}

bool pk_api_coop_partner_free(void) {
    pthread_rwlock_rdlock(&pk_coreLock);
    bool free = g.core && pk_is_gba(g.core) && pk_coop_partner_free(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
    return free;
}

int pk_api_coop_join_kind(void) {
    pthread_rwlock_rdlock(&pk_coreLock);
    int kind = g.core && pk_is_gba(g.core) ? pk_coop_join_kind(g.core) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return kind;
}

void pk_api_coop_join_trainers(int out[2]) {
    if (!out) return;
    out[0] = out[1] = 0;
    pthread_rwlock_rdlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) pk_coop_join_trainers(g.core, out);
    pthread_rwlock_unlock(&pk_coreLock);
}

/* Post-battle operations call the game at its next safe point, returning -1 when it must retry. */
#define COOP_AFTER_CALL(expr)                                  \
    pthread_rwlock_rdlock(&pk_coreLock);                        \
    int result = -1;                                           \
    if (g.core && pk_is_gba(g.core)) {                          \
        pk_coop_after_set_call(pk_coop_game_call);              \
        pk_coop_after_set_cap(pk_call_set_cap);                 \
        result = (expr);                                       \
    }                                                          \
    pthread_rwlock_unlock(&pk_coreLock);                        \
    return result;

int pk_api_coop_give_mon(const uint8_t* mon, size_t length) {
    if (!mon || length != PK_API_COOP_MON_SIZE) return 2;
    COOP_AFTER_CALL(pk_coop_give_mon(g.core, mon))
}

int pk_api_coop_remove_item(int item, int count) {
    COOP_AFTER_CALL(pk_coop_remove_item(g.core, item, count))
}

int pk_api_coop_set_trainer_flag(int trainer_id) {
    COOP_AFTER_CALL(pk_coop_set_trainer_flag(g.core, trainer_id))
}

int pk_api_coop_prize(int trainer_id, bool single) {
    pthread_rwlock_rdlock(&pk_coreLock);
    int prize = -1;
    if (g.core && pk_is_gba(g.core)) {
        prize = single ? pk_coop_prize_single(g.core, trainer_id) : pk_coop_prize(g.core, trainer_id);
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return prize;
}

int pk_api_coop_add_money(int amount) {
    COOP_AFTER_CALL(pk_coop_add_money(g.core, amount))
}

int pk_api_coop_write_party_mon(int slot, const uint8_t* mon, size_t length) {
    if (!mon || length != PK_API_COOP_MON_SIZE) return 0;
    COOP_AFTER_CALL(pk_coop_write_party_mon(g.core, slot, mon))
}

int pk_api_coop_white_out(void) {
    COOP_AFTER_CALL(pk_coop_white_out(g.core))
}

int pk_api_coop_evolve(int slot) {
    COOP_AFTER_CALL(pk_coop_evolve(g.core, slot))
}

int pk_api_coop_save_game(void) {
    COOP_AFTER_CALL(pk_coop_save_game(g.core))
}

int pk_api_coop_release_script(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    int result = -1;
    if (g.core && pk_is_gba(g.core)) {
        pk_coop_after_set_call(pk_coop_game_call);
        result = pk_coop_release_script(g.core);
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return result;
}

#undef COOP_AFTER_CALL

_Static_assert(PK_API_COOP_LINK_PACKET_SIZE == PK_COOP_LINK_WIRE_HEADER + PK_COOP_LINK_BLOCK_MAX, "co-op wire size");
_Static_assert(PK_API_COOP_LINK_RESULT_SIZE == PK_COOP_LINK_RESULT_SIZE, "co-op result size");
_Static_assert(PK_API_COOP_LINK_DEBUG_SIZE == 4 * (6 + PK_API_COOP_LINK_PACKET_SIZE), "co-op debug size");

#define COOP_LINK_CALL(lock, fallback, expr)                     \
    pthread_rwlock_##lock(&pk_coreLock);                         \
    int result = g.core && pk_is_gba(g.core) ? (expr) : fallback; \
    pthread_rwlock_unlock(&pk_coreLock);                        \
    return result;

static bool coop_other(struct pk_coop_link_setup* setup, const uint8_t* name, size_t length, int gender,
                       uint32_t ot_id, bool terminate) {
    if (!name || length < PK_API_COOP_NAME_SIZE) return false;
    memset(setup, 0, sizeof(*setup));
    memcpy(setup->partnerName, name, PK_API_COOP_NAME_SIZE);
    if (terminate) setup->partnerName[7] = 0xFF;
    setup->partnerGender = gender != 0;
    setup->partnerOtId = ot_id;
    return true;
}

bool pk_api_coop_link_begin(bool master, int kind, int trainer_a, int trainer_b,
                           const uint8_t* name, size_t length, int gender, uint32_t ot_id) {
    struct pk_coop_link_setup setup;
    if (kind < PK_COOP_LINK_WILD || kind > PK_COOP_LINK_STORY ||
        !coop_other(&setup, name, length, gender, ot_id, false)) return false;
    setup.master = master;
    setup.kind = (uint8_t) kind;
    setup.trainerA = (uint16_t) trainer_a;
    setup.trainerB = (uint16_t) trainer_b;
    COOP_LINK_CALL(wrlock, 0, pk_coop_link_begin(g.core, &setup))
}

int pk_api_coop_link_take(uint8_t* out, size_t capacity) {
    if (!out || capacity < PK_API_COOP_LINK_PACKET_SIZE) return 0;
    COOP_LINK_CALL(wrlock, 0, pk_coop_link_take(g.core, out, PK_API_COOP_LINK_PACKET_SIZE))
}

bool pk_api_coop_link_give(const uint8_t* block, size_t length) {
    if (!block || length < PK_COOP_LINK_WIRE_HEADER || length > PK_API_COOP_LINK_PACKET_SIZE) return false;
    COOP_LINK_CALL(wrlock, 0, pk_coop_link_give(g.core, block, (int) length))
}

int pk_api_coop_link_bad_blocks(uint8_t* out, size_t capacity) {
    if (!out || capacity < PK_API_COOP_LINK_DEBUG_SIZE) return 0;
    COOP_LINK_CALL(rdlock, 0, pk_coop_link_bad_blocks(out, PK_API_COOP_LINK_DEBUG_SIZE))
}

int pk_api_coop_link_state(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    int state = PK_COOP_LINK_IDLE;
    if (g.core && pk_is_gba(g.core)) {
        pk_coop_link_tick(g.core);
        state = pk_coop_link_state(g.core);
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return state;
}

void pk_api_coop_link_leave(bool blackout) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) pk_coop_link_leave(g.core, blackout);
    pthread_rwlock_unlock(&pk_coreLock);
}

int pk_api_coop_link_result(uint8_t* out, size_t capacity) {
    if (!out || capacity < PK_API_COOP_LINK_RESULT_SIZE) return 0;
    COOP_LINK_CALL(rdlock, 0, pk_coop_link_result(g.core, out, PK_API_COOP_LINK_RESULT_SIZE))
}

bool pk_api_coop_link_host_hold(void) {
    COOP_LINK_CALL(rdlock, 0, pk_coop_link_host_hold(g.core))
}

bool pk_api_coop_link_host_go(const uint8_t* name, size_t length, int gender, uint32_t ot_id) {
    struct pk_coop_link_setup setup;
    if (!coop_other(&setup, name, length, gender, ot_id, true)) return false;
    COOP_LINK_CALL(wrlock, 0, pk_coop_link_host_go(g.core, &setup))
}

int pk_api_coop_link_partner_go(int kind, int trainer_a, int trainer_b,
                               const uint8_t* name, size_t length, int gender, uint32_t ot_id) {
    struct pk_coop_link_setup setup;
    if (kind < PK_COOP_LINK_WILD || kind > PK_COOP_LINK_SINGLE || trainer_a < 0 || trainer_a > UINT16_MAX ||
        trainer_b < 0 || trainer_b > UINT16_MAX ||
        !coop_other(&setup, name, length, gender, ot_id, true)) return 0;
    setup.kind = (uint8_t) kind;
    setup.trainerA = (uint16_t) trainer_a;
    setup.trainerB = (uint16_t) trainer_b;
    COOP_LINK_CALL(wrlock, 0, pk_coop_link_partner_go(g.core, &setup))
}

bool pk_api_coop_story_arm(int trainer_id) {
    if (trainer_id <= 0 || trainer_id > UINT16_MAX) return false;
    COOP_LINK_CALL(wrlock, 0, pk_coop_story_arm(g.core, pk_coop_game_call, (uint16_t) trainer_id))
}

int pk_api_coop_story_state(void) {
    // Reading the story state can remove a stale hook.
    COOP_LINK_CALL(wrlock, PK_COOP_STORY_IDLE, pk_coop_story_state(g.core))
}

bool pk_api_coop_story_go(bool master, const uint8_t* name, size_t length, int gender, uint32_t ot_id) {
    struct pk_coop_link_setup setup;
    if (!coop_other(&setup, name, length, gender, ot_id, true)) return false;
    setup.master = master;
    COOP_LINK_CALL(wrlock, 0, pk_coop_story_go(g.core, &setup))
}

bool pk_api_coop_story_cancel(void) {
    COOP_LINK_CALL(wrlock, 0, pk_coop_story_cancel(g.core))
}

void pk_api_coop_story_disarm(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) pk_coop_story_disarm(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
}

int pk_api_coop_link_caught(uint8_t* out, size_t capacity) {
    if (!out || capacity < PK_API_COOP_LINK_CATCH_SIZE) return 0;
    pthread_rwlock_rdlock(&pk_coreLock);
    int owner = g.core && pk_is_gba(g.core) ? pk_coop_link_caught(g.core, out + 1) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    if (owner != 1 && owner != 2) return 0;
    out[0] = (uint8_t) owner;
    return PK_API_COOP_LINK_CATCH_SIZE;
}

void pk_api_coop_link_reset(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) {
        pk_coop_link_result_clear(g.core);
        pk_coop_link_reset(g.core);
    }
    pthread_rwlock_unlock(&pk_coreLock);
}

#undef COOP_LINK_CALL

_Static_assert(PK_API_PARTNER_SELF_SIZE == PK_PARTNER_SELF_N, "partner status size");
_Static_assert(PK_API_PARTNER_ART_SIZE == PK_PARTNER_ART_BYTES, "partner art size");

bool pk_api_partner_self(int32_t* out, size_t count) {
    if (!out || count < PK_API_PARTNER_SELF_SIZE) return false;
    // A read advances the sprite's step counter, so serialize it with the frame.
    pthread_rwlock_wrlock(&pk_coreLock);
    bool valid = g.core && pk_is_gba(g.core) && pk_partner_self(g.core, out);
    pthread_rwlock_unlock(&pk_coreLock);
    return valid;
}

void pk_api_partner_set(const uint8_t* art, size_t length) {
    pthread_rwlock_wrlock(&pk_coreLock);
    pk_partner_set(length == PK_API_PARTNER_ART_SIZE ? art : NULL);
    pthread_rwlock_unlock(&pk_coreLock);
}

void pk_api_partner_place(bool visible, int x, int y, int frame, bool mirrored, int blob_frame, int bob) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) pk_partner_place(g.core, visible, x, y, frame, mirrored, blob_frame, bob);
    pthread_rwlock_unlock(&pk_coreLock);
}

bool pk_api_coop_lock_install(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    bool ok = g.core && pk_is_gba(g.core) && pk_coop_lock_install(g.core, pk_coop_game_call);
    pthread_rwlock_unlock(&pk_coreLock);
    return ok;
}

void pk_api_coop_lock_uninstall(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core) pk_coop_lock_uninstall(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
}

void pk_api_coop_lock_set_active(bool on) {
    pthread_rwlock_rdlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) pk_coop_lock_set_active(g.core, on);
    pthread_rwlock_unlock(&pk_coreLock);
}

void pk_api_coop_lock_held(int out[2]) {
    if (!out) return;
    out[0] = out[1] = 0;
    pthread_rwlock_rdlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) out[0] = pk_coop_lock_held(g.core, &out[1]);
    pthread_rwlock_unlock(&pk_coreLock);
}

int pk_api_coop_lock_go(int id) {
    pthread_rwlock_rdlock(&pk_coreLock);
    int result = g.core && pk_is_gba(g.core) ? pk_coop_lock_go(g.core, id) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return result;
}

void pk_api_coop_lock_cancel(void) {
    pthread_rwlock_rdlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) pk_coop_lock_cancel(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
}
