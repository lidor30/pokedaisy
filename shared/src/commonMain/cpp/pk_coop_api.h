#ifndef PK_COOP_API_H
#define PK_COOP_API_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/** Co-op's retail check and battle bridge for the player's GBA core. Call mutations between frames. */
bool pk_api_coop_enable(void);
bool pk_api_coop_link_enable(void);
/* Removes the linked-battle bridge and clears co-op detection. */
void pk_api_coop_disable(void);
/** The most recent refusal, as NUL-terminated ASCII. Empty when the check has not reported a refusal. */
void pk_api_coop_refusal(bool link, char* out, size_t capacity);
bool pk_api_coop_battle_starting(void);
bool pk_api_coop_on_free_field(void);
bool pk_api_coop_partner_free(void);
/** 0 = no join, 1 = wild, 2 = two trainers, 3 = one trainer with enough Pokémon. */
int pk_api_coop_join_kind(void);
/** Writes the two trainer IDs, or zeros. */
void pk_api_coop_join_trainers(int out[2]);

/* Post-battle calls. See pk_coop_after.h for the per-operation return values. */
enum { PK_API_COOP_MON_SIZE = 100 };
int pk_api_coop_give_mon(const uint8_t* mon, size_t length);
int pk_api_coop_remove_item(int item, int count);
int pk_api_coop_set_trainer_flag(int trainer_id);
int pk_api_coop_prize(int trainer_id, bool single);
int pk_api_coop_add_money(int amount);
int pk_api_coop_write_party_mon(int slot, const uint8_t* mon, size_t length);
int pk_api_coop_white_out(void);
int pk_api_coop_evolve(int slot);
int pk_api_coop_save_game(void);
int pk_api_coop_release_script(void);

enum {
    PK_API_COOP_NAME_SIZE = 8,
    PK_API_COOP_LINK_PACKET_SIZE = 260,
    PK_API_COOP_LINK_DEBUG_SIZE = 1064,
    PK_API_COOP_LINK_RESULT_SIZE = 16,
    PK_API_COOP_LINK_CATCH_SIZE = 101,
};

bool pk_api_coop_link_begin(bool master, int kind, int trainer_a, int trainer_b,
                           const uint8_t* name, size_t name_length, int gender, uint32_t ot_id);
int pk_api_coop_link_take(uint8_t* out, size_t capacity);
bool pk_api_coop_link_give(const uint8_t* block, size_t length);
int pk_api_coop_link_bad_blocks(uint8_t* out, size_t capacity);
int pk_api_coop_link_state(void);
void pk_api_coop_link_leave(bool blackout);
int pk_api_coop_link_result(uint8_t* out, size_t capacity);
bool pk_api_coop_link_host_hold(void);
bool pk_api_coop_link_host_go(const uint8_t* name, size_t name_length, int gender, uint32_t ot_id);
int pk_api_coop_link_partner_go(int kind, int trainer_a, int trainer_b,
                              const uint8_t* name, size_t name_length, int gender, uint32_t ot_id);
bool pk_api_coop_story_arm(int trainer_id);
int pk_api_coop_story_state(void);
bool pk_api_coop_story_go(bool master, const uint8_t* name, size_t name_length, int gender, uint32_t ot_id);
bool pk_api_coop_story_cancel(void);
void pk_api_coop_story_disarm(void);
int pk_api_coop_link_caught(uint8_t* out, size_t capacity);
void pk_api_coop_link_reset(void);

enum { PK_API_PARTNER_SELF_SIZE = 8, PK_API_PARTNER_ART_SIZE = 42 * 512 + 32 };
bool pk_api_partner_self(int32_t* out, size_t count);
void pk_api_partner_set(const uint8_t* art, size_t length);
void pk_api_partner_place(bool visible, int x, int y, int frame, bool mirrored, int blob_frame, int bob);

bool pk_api_coop_lock_install(void);
void pk_api_coop_lock_uninstall(void);
void pk_api_coop_lock_set_active(bool on);
void pk_api_coop_lock_held(int out[2]);
int pk_api_coop_lock_go(int id);
void pk_api_coop_lock_cancel(void);

#endif
