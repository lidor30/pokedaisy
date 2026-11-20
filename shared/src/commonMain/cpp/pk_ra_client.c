#include "pk_ra_client.h"
#include "pk_ra_requests.h"
#include "pk_api.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

static rc_client_t* client;
static struct pk_ra_callbacks host;
static uint32_t reads, shortReads, lastShortAddress;

static uint32_t readMemory(uint32_t address, uint8_t* out, uint32_t bytes, rc_client_t* active) {
    (void) active;
    uint32_t n = host.read_memory ? host.read_memory(address, out, bytes) : pk_api_ra_read_memory(address, out, bytes);
    reads++;
    if (n < bytes) {
        shortReads++;
        lastShortAddress = address;
    }
    return n;
}

static void serverCall(const rc_api_request_t* request, rc_client_server_callback_t callback,
                       void* data, rc_client_t* active) {
    (void) active;
    int id = pk_ra_requests_begin(callback, data);
    if (!id) {
        if (host.log) host.log("server call dropped: too many in flight");
        rc_api_server_response_t error = { NULL, 0, RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR };
        callback(&error, data);
        return;
    }
    host.request(id, request->url, request->post_data, request->content_type);
}

static void logMessage(const char* message, const rc_client_t* active) {
    (void) active;
    if (host.log) host.log(message);
}

static void onEvent(const rc_client_event_t* event, rc_client_t* active) {
    if (!host.event) return;
    struct pk_ra_event out = { .type = event->type, .extra = "" };
    if (event->achievement) {
        const rc_client_achievement_t* a = event->achievement;
        out.id = a->id;
        out.points = a->points;
        out.title = a->title;
        out.description = a->description;
        out.badge_url = a->badge_url;
        out.extra = a->measured_progress;
    } else if (event->leaderboard) {
        const rc_client_leaderboard_t* l = event->leaderboard;
        out.id = l->id;
        out.title = l->title;
        out.description = l->description;
        out.extra = l->tracker_value;
    } else if (event->leaderboard_tracker) {
        out.id = event->leaderboard_tracker->id;
        out.extra = event->leaderboard_tracker->display;
    } else if (event->leaderboard_scoreboard) {
        const rc_client_leaderboard_scoreboard_t* s = event->leaderboard_scoreboard;
        out.id = s->leaderboard_id;
        out.points = s->new_rank;
        out.extra = s->submitted_score;
    } else if (event->server_error) {
        out.id = event->server_error->related_id;
        out.title = event->server_error->api;
        out.description = event->server_error->error_message;
    } else if (event->subset) {
        out.id = event->subset->id;
        out.title = event->subset->title;
        out.badge_url = event->subset->badge_url;
    }
    if (event->type == RC_CLIENT_EVENT_GAME_COMPLETED) {
        const rc_client_game_t* g = rc_client_get_game_info(active);
        if (g) {
            out.id = g->id;
            out.title = g->title;
            out.badge_url = g->badge_url;
        }
    }
    if (!out.extra) out.extra = "";
    host.event(&out);
}

static void onLogin(int result, const char* error, rc_client_t* active, void* userdata) {
    (void) userdata;
    if (!host.login) return;
    const rc_client_user_t* u = result == RC_OK ? rc_client_get_user_info(active) : NULL;
    if (!u) {
        host.login(result, error, NULL);
        return;
    }
    struct pk_ra_user user = {
        u->username, u->display_name, u->token, u->avatar_url, u->score, u->score_softcore
    };
    host.login(result, error, &user);
}

static void onGameLoaded(int result, const char* error, rc_client_t* active, void* userdata) {
    (void) userdata;
    if (!host.game_loaded) return;
    const rc_client_game_t* g = rc_client_get_game_info(active);
    rc_client_user_game_summary_t s = {0};
    if (result == RC_OK) rc_client_get_user_game_summary(active, &s);
    struct pk_ra_game game = {
        g ? g->id : 0, g ? g->title : NULL, g ? g->badge_url : NULL, g ? g->hash : NULL,
        s.num_core_achievements, s.num_unlocked_achievements, s.points_core, s.points_unlocked
    };
    host.game_loaded(result, error, &game);
}

bool pk_ra_init(const struct pk_ra_callbacks* callbacks) {
    if (client) return true;
    if (!callbacks || !callbacks->request) return false;
    host = *callbacks;
    client = rc_client_create(readMemory, serverCall);
    if (!client) return false;
    rc_client_enable_logging(client, RC_CLIENT_LOG_LEVEL_INFO, logMessage);
    rc_client_set_event_handler(client, onEvent);
    rc_client_set_hardcore_enabled(client, 0);
    return true;
}

bool pk_ra_ready(void) { return client != NULL; }

void pk_ra_user_agent(char* out, size_t capacity) {
    if (!out || !capacity) return;
    out[0] = '\0';
    if (client) rc_client_get_user_agent_clause(client, out, capacity);
}

void pk_ra_queue_response(int id, int status, const uint8_t* body, size_t length) {
    pk_ra_requests_respond(id, status, (const char*) body, length);
}

void pk_ra_pump(void) { pk_ra_requests_pump(); }

void pk_ra_login_password(const char* username, const char* password) {
    if (client && username && password) rc_client_begin_login_with_password(client, username, password, onLogin, NULL);
}

void pk_ra_login_token(const char* username, const char* token) {
    if (client && username && token) rc_client_begin_login_with_token(client, username, token, onLogin, NULL);
}

void pk_ra_logout(void) { if (client) rc_client_logout(client); }

bool pk_ra_load_game(const char* hash) {
    if (!client || !hash) return false;
    rc_client_begin_load_game(client, hash, onGameLoaded, NULL);
    return true;
}

void pk_ra_unload_game(void) { if (client) rc_client_unload_game(client); }
void pk_ra_frame(void) { if (client) rc_client_do_frame(client); }
void pk_ra_idle(void) { if (client) rc_client_idle(client); }
void pk_ra_reset(void) { if (client) rc_client_reset(client); }

uint8_t* pk_ra_serialize(size_t* size) {
    if (!size) return NULL;
    *size = 0;
    if (!client || !rc_client_is_game_loaded(client)) return NULL;
    size_t need = rc_client_progress_size(client);
    if (!need) return NULL;
    uint8_t* out = malloc(need);
    if (!out) return NULL;
    if (rc_client_serialize_progress_sized(client, out, need) != RC_OK) {
        free(out);
        return NULL;
    }
    *size = need;
    return out;
}

void pk_ra_deserialize(const uint8_t* data, size_t size) {
    if (client) rc_client_deserialize_progress_sized(client, data, size);
}

void pk_ra_debug_status(char* out, size_t size) {
    if (!out || !size) return;
    char rp[256];
    rp[0] = '\0';
    if (!client || !rc_client_is_game_loaded(client)) {
        snprintf(out, size, "no game");
        return;
    }
    rc_client_get_rich_presence_message(client, rp, sizeof(rp));
    int counts[4] = { 0, 0, 0, 0 };
    char odd[1024];
    size_t oddLen = 0;
    odd[0] = '\0';
    rc_client_achievement_list_t* list = rc_client_create_achievement_list(client,
        RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE_AND_UNOFFICIAL, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE);
    if (list) {
        for (uint32_t b = 0; b < list->num_buckets; b++) {
            for (uint32_t i = 0; i < list->buckets[b].num_achievements; i++) {
                const rc_client_achievement_t* a = list->buckets[b].achievements[i];
                if (a->state < 4) {
                    counts[a->state]++;
                }
                if (a->state != RC_CLIENT_ACHIEVEMENT_STATE_ACTIVE && oddLen < sizeof(odd) - 64) {
                    int n = snprintf(odd + oddLen, sizeof(odd) - oddLen, " [%u s%u %s]",
                        (unsigned) a->id, (unsigned) a->state, a->title ? a->title : "");
                    if (n > 0) {
                        oddLen += (size_t) n;
                    }
                }
            }
        }
        rc_client_destroy_achievement_list(list);
    }
    snprintf(out, size, "rp=\"%s\" reads=%u short=%u last_short=$%06X active=%d unlocked=%d disabled=%d inactive=%d%s",
        rp, (unsigned) reads, (unsigned) shortReads, (unsigned) lastShortAddress,
        counts[RC_CLIENT_ACHIEVEMENT_STATE_ACTIVE], counts[RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED],
        counts[RC_CLIENT_ACHIEVEMENT_STATE_DISABLED], counts[RC_CLIENT_ACHIEVEMENT_STATE_INACTIVE], odd);
}

/* The rc_client placeholders above this ID are also omitted from its own game summary. */
#define WARNING_ACHIEVEMENT_ID 101000001u

struct TextRecords {
    char* text;
    size_t size;
    size_t capacity;
};

static struct TextRecords recordsNew(size_t capacity) {
    return (struct TextRecords) { malloc(capacity), 0, capacity };
}

static void recordsAppend(struct TextRecords* records, const char* format, ...) __attribute__((format(printf, 2, 3)));
static void recordsAppend(struct TextRecords* records, const char* format, ...) {
    if (!records->text) return;
    va_list args;
    va_start(args, format);
    va_list copied;
    va_copy(copied, args);
    int count = vsnprintf(records->text + records->size, records->capacity - records->size, format, args);
    va_end(args);
    bool stored = count >= 0 && (size_t) count < records->capacity - records->size;
    if (!stored && count >= 0 && (size_t) count < SIZE_MAX - records->size) {
        size_t needed = records->size + (size_t) count + 1;
        size_t capacity = records->capacity <= SIZE_MAX / 2 ? records->capacity * 2 : needed;
        if (capacity < needed) capacity = needed;
        char* grown = realloc(records->text, capacity);
        if (grown) {
            records->text = grown;
            records->capacity = capacity;
            stored = vsnprintf(grown + records->size, capacity - records->size, format, copied) == count;
        }
    }
    va_end(copied);
    if (stored) records->size += (size_t) count;
    else {
        free(records->text);
        *records = (struct TextRecords) {0};
    }
}

uint8_t* pk_ra_achievement_list(size_t* length) {
    if (!length) return NULL;
    *length = 0;
    if (!client || !rc_client_has_achievements(client)) return NULL;
    rc_client_achievement_list_t* list = rc_client_create_achievement_list(client,
        RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
    if (!list) return NULL;
    struct TextRecords records = recordsNew(4096);
    for (uint32_t b = 0; b < list->num_buckets; b++) {
        const rc_client_achievement_bucket_t* bucket = &list->buckets[b];
        for (uint32_t i = 0; i < bucket->num_achievements; i++) {
            const rc_client_achievement_t* a = bucket->achievements[i];
            if (a->id >= WARNING_ACHIEVEMENT_ID) continue;
            recordsAppend(&records, "%u\x1F%s\x1F%u\x1F%s\x1F%s\x1F%u\x1F%u\x1F%s\x1F%s\x1F%s\x1F%.3f\x1F%.3f\x1F%u\x1E",
                (unsigned) bucket->bucket_type, bucket->label ? bucket->label : "",
                (unsigned) a->id, a->title ? a->title : "", a->description ? a->description : "",
                (unsigned) a->points, (unsigned) a->unlocked, a->badge_url ? a->badge_url : "",
                a->badge_locked_url ? a->badge_locked_url : "", a->measured_progress,
                a->measured_percent, a->rarity, (unsigned) a->type);
        }
    }
    rc_client_destroy_achievement_list(list);
    *length = records.size;
    return (uint8_t*) records.text;
}

uint8_t* pk_ra_leaderboard_list(size_t* length) {
    if (!length) return NULL;
    *length = 0;
    if (!client || !rc_client_has_leaderboards(client)) return NULL;
    rc_client_leaderboard_list_t* list = rc_client_create_leaderboard_list(client, RC_CLIENT_LEADERBOARD_LIST_GROUPING_NONE);
    if (!list) return NULL;
    struct TextRecords records = recordsNew(4096);
    for (uint32_t b = 0; b < list->num_buckets; b++) {
        const rc_client_leaderboard_bucket_t* bucket = &list->buckets[b];
        for (uint32_t i = 0; i < bucket->num_leaderboards; i++) {
            const rc_client_leaderboard_t* l = bucket->leaderboards[i];
            recordsAppend(&records, "%s\x1F%u\x1F%s\x1F%s\x1F%u\x1E", bucket->label ? bucket->label : "",
                (unsigned) l->id, l->title ? l->title : "", l->description ? l->description : "", (unsigned) l->lower_is_better);
        }
    }
    rc_client_destroy_leaderboard_list(list);
    *length = records.size;
    return (uint8_t*) records.text;
}

struct BoardRequest {
    int token;
    bool aroundUser;
};

static void entriesFetched(int result, const char* error, rc_client_leaderboard_entry_list_t* list,
                           rc_client_t* active, void* userdata) {
    (void) active;
    struct BoardRequest* request = userdata;
    struct TextRecords records = {0};
    int total = 0;
    int userIndex = -1;
    if (list) {
        records = recordsNew(1024);
        for (uint32_t i = 0; i < list->num_entries; i++) {
            const rc_client_leaderboard_entry_t* e = &list->entries[i];
            recordsAppend(&records, "%u\x1F%s\x1F%s\x1E", (unsigned) e->rank, e->user ? e->user : "", e->display);
        }
        total = (int) list->total_entries;
        userIndex = (int) list->user_index;
        rc_client_destroy_leaderboard_entry_list(list);
    }
    if (host.leaderboard) host.leaderboard(request->token, request->aroundUser, result, error,
        (const uint8_t*) records.text, records.size, total, userIndex);
    free(records.text);
    free(request);
}

void pk_ra_fetch_leaderboard(int token, uint32_t id, bool aroundUser, uint32_t first, uint32_t count) {
    if (!client || !host.leaderboard) return;
    struct BoardRequest* request = malloc(sizeof(*request));
    if (!request) {
        host.leaderboard(token, aroundUser, RC_OUT_OF_MEMORY, NULL, NULL, 0, 0, -1);
        return;
    }
    *request = (struct BoardRequest) {token, aroundUser};
    if (aroundUser) rc_client_begin_fetch_leaderboard_entries_around_user(client, id, count, entriesFetched, request);
    else rc_client_begin_fetch_leaderboard_entries(client, id, first, count, entriesFetched, request);
}

static void allProgressFetched(int result, const char* error, rc_client_all_user_progress_t* list,
                               rc_client_t* active, void* userdata) {
    (void) active;
    (void) error;
    struct TextRecords records = {0};
    if (list) {
        records = recordsNew(4096);
        for (uint32_t i = 0; i < list->num_entries; i++) {
            const rc_client_all_user_progress_entry_t* e = &list->entries[i];
            recordsAppend(&records, "%u\x1F%u\x1F%u\x1F%u\x1E", (unsigned) e->game_id, (unsigned) e->num_achievements,
                (unsigned) e->num_unlocked_achievements, (unsigned) e->num_unlocked_achievements_hardcore);
        }
        rc_client_destroy_all_user_progress(list);
    }
    if (host.all_progress) host.all_progress((int) (intptr_t) userdata, result, (const uint8_t*) records.text, records.size);
    free(records.text);
}

void pk_ra_fetch_all_progress(int token, uint32_t console) {
    if (client && host.all_progress) {
        rc_client_begin_fetch_all_user_progress(client, console, allProgressFetched, (void*) (intptr_t) token);
    }
}
