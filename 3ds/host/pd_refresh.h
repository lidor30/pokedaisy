#ifndef PD_REFRESH_H
#define PD_REFRESH_H

#include <stdbool.h>
#include <stdint.h>

// The caller chooses clock units. A delayed update runs once, without a
// catch-up burst; urgent input and transitions may bypass the background cap.
struct pd_refresh_timer {
    uint64_t last;
    bool started;
};

static inline bool pd_refresh_due(const struct pd_refresh_timer* timer, uint64_t now,
                                  uint64_t interval, bool urgent) {
    return urgent || !timer->started || now - timer->last >= interval;
}

static inline void pd_refresh_mark(struct pd_refresh_timer* timer, uint64_t now) {
    timer->last = now;
    timer->started = true;
}

#endif
