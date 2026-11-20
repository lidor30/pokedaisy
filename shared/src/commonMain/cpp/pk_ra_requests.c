#include "pk_ra_requests.h"

#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#define MAX_CALLS 64

struct Request {
    int id;
    rc_client_server_callback_t callback;
    void* data;
    bool answered;
    rc_api_server_response_t response;
    char* body;
};

static struct Request requests[MAX_CALLS];
static pthread_mutex_t requestsLock = PTHREAD_MUTEX_INITIALIZER;
static atomic_int readyCount;
static bool pumping;
static int nextId = 1;

static struct Request* find(int id) {
    for (int slot = 0; slot < MAX_CALLS; slot++) {
        if (requests[slot].id == id) return &requests[slot];
    }
    return NULL;
}

int pk_ra_requests_begin(rc_client_server_callback_t callback, void* callbackData) {
    if (!callback) return 0;
    pthread_mutex_lock(&requestsLock);
    struct Request* freeSlot = find(0);
    int id = 0;
    if (freeSlot) {
        do {
            id = nextId;
            nextId = id == INT_MAX ? 1 : id + 1;
        } while (find(id));
        *freeSlot = (struct Request) { .id = id, .callback = callback, .data = callbackData };
    }
    pthread_mutex_unlock(&requestsLock);
    return id;
}

bool pk_ra_requests_respond(int id, int status, const char* body, size_t length) {
    if (id <= 0) return false;
    pthread_mutex_lock(&requestsLock);
    struct Request* request = find(id);
    bool accepted = request && !request->answered;
    if (accepted) {
        char* copy = NULL;
        if (body) {
            copy = length == SIZE_MAX ? NULL : malloc(length + 1);
            if (copy) {
                memcpy(copy, body, length);
                copy[length] = '\0';
            } else {
                status = RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR;
            }
        }
        request->body = copy;
        request->response = (rc_api_server_response_t) { copy, copy ? length : 0, status };
        request->answered = true;
        atomic_fetch_add_explicit(&readyCount, 1, memory_order_relaxed);
    }
    pthread_mutex_unlock(&requestsLock);
    return accepted;
}

void pk_ra_requests_pump(void) {
    if (!atomic_load_explicit(&readyCount, memory_order_relaxed)) return;
    pthread_mutex_lock(&requestsLock);
    if (pumping) {
        pthread_mutex_unlock(&requestsLock);
        return;
    }
    pumping = true;
    while (atomic_load_explicit(&readyCount, memory_order_relaxed)) {
        for (int i = 0; i < MAX_CALLS; i++) {
            if (!requests[i].answered) continue;
            struct Request current = requests[i];
            requests[i] = (struct Request) {0};
            atomic_fetch_sub_explicit(&readyCount, 1, memory_order_relaxed);
            pthread_mutex_unlock(&requestsLock);
            current.callback(&current.response, current.data);
            free(current.body);
            pthread_mutex_lock(&requestsLock);
        }
    }
    pumping = false;
    pthread_mutex_unlock(&requestsLock);
}
