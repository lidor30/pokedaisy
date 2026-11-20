#include "pk_ra_requests.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define REQUIRE(test) do { if (!(test)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #test); exit(1); } } while (0)

static int delivered;
static bool insideCallback;

static void reply(const rc_api_server_response_t* response, void* data) {
    REQUIRE(!insideCallback);
    REQUIRE(response->http_status_code == 200);
    REQUIRE(response->body_length == 3);
    REQUIRE(memcmp(response->body, data, 3) == 0);
    REQUIRE(response->body[3] == '\0');
    delivered++;
}

static void startsNext(const rc_api_server_response_t* response, void* data) {
    REQUIRE(response->http_status_code == 503 && response->body == NULL);
    insideCallback = true;
    int next = pk_ra_requests_begin(reply, data);
    REQUIRE(next > 0 && pk_ra_requests_respond(next, 200, data, 3));
    pk_ra_requests_pump();
    insideCallback = false;
}

int main(void) {
    const char original[] = {'a', '\0', 'b', '\0'};
    char copy[sizeof original];
    memcpy(copy, original, sizeof copy);

    int id = pk_ra_requests_begin(reply, (void*) original);
    REQUIRE(id > 0);
    REQUIRE(!pk_ra_requests_respond(id + 1, 200, copy, 3));
    REQUIRE(pk_ra_requests_respond(id, 200, copy, 3));
    REQUIRE(!pk_ra_requests_respond(id, 201, copy, 3));
    copy[0] = 'z';
    REQUIRE(delivered == 0);
    pk_ra_requests_pump();
    REQUIRE(delivered == 1);
    REQUIRE(!pk_ra_requests_respond(id, 200, copy, 3));

    id = pk_ra_requests_begin(startsNext, (void*) original);
    REQUIRE(id > 0 && pk_ra_requests_respond(id, 503, NULL, 0));
    pk_ra_requests_pump();
    REQUIRE(delivered == 2);

    int slots[64];
    for (int i = 0; i < 64; i++) {
        slots[i] = pk_ra_requests_begin(reply, (void*) original);
        REQUIRE(slots[i] > id);
        id = slots[i];
    }
    REQUIRE(pk_ra_requests_begin(reply, (void*) original) == 0);
    for (int i = 63; i >= 0; i--) REQUIRE(pk_ra_requests_respond(slots[i], 200, original, 3));
    pk_ra_requests_pump();
    REQUIRE(delivered == 66);
    REQUIRE(pk_ra_requests_begin(reply, (void*) original) > id);
    return 0;
}
