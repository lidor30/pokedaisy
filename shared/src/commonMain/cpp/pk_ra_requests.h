#ifndef PK_RA_REQUESTS_H
#define PK_RA_REQUESTS_H

#include <stdbool.h>
#include "rc_client.h"

/** Positive request ID, or zero if all slots are occupied. */
int pk_ra_requests_begin(rc_client_server_callback_t callback, void* callbackData);
/** Thread-safe. Copies the first response for an active ID. Returns false for unknown or already-answered IDs. */
bool pk_ra_requests_respond(int id, int status, const char* body, size_t length);
/** Deliver on the host's client thread. A callback may enqueue another request; nested pumps are coalesced. */
void pk_ra_requests_pump(void);

#endif
