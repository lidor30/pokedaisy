// SHA-1 (FIPS 180-4), for telling ROMs apart the way the app does (Poller's *_SHA1, GameTitles.BY_SHA1).
#ifndef PD_SHA1_H
#define PD_SHA1_H

#include <stddef.h>
#include <stdint.h>

struct pd_sha1 {
    uint32_t h[5];
    uint64_t len;
    uint8_t buf[64];
    size_t fill;
};

void pd_sha1_init(struct pd_sha1* s);
void pd_sha1_update(struct pd_sha1* s, const void* data, size_t len);
// 40 lowercase hex digits and a NUL.
void pd_sha1_hex(struct pd_sha1* s, char out[41]);

#endif
