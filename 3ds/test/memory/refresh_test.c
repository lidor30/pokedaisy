#include "../../host/pd_refresh.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    struct pd_refresh_timer timer = { 0 };
    assert(pd_refresh_due(&timer, 0, 334, false));
    pd_refresh_mark(&timer, 0);
    assert(!pd_refresh_due(&timer, 333, 334, false));
    assert(pd_refresh_due(&timer, 334, 334, false));
    // A pause yields one update, with no accumulated work on the next loop.
    pd_refresh_mark(&timer, 5000);
    assert(!pd_refresh_due(&timer, 5000, 334, false));
    assert(pd_refresh_due(&timer, 5001, 334, true));
    pd_refresh_mark(&timer, 5001);
    assert(!pd_refresh_due(&timer, 5002, 334, false));

    // Low FPS and extra emulated frames at fast-forward must not increase
    // the wall-time polling rate. Emulate 1x and 8x at several host cadences.
    const unsigned steps[] = { 16, 33, 67 };
    for (unsigned k = 0; k < sizeof(steps) / sizeof(*steps); ++k) {
        for (unsigned speed = 1; speed <= 8; speed *= 8) {
            timer = (struct pd_refresh_timer){ 0 };
            pd_refresh_mark(&timer, 0);
            unsigned reads = 0;
            for (unsigned now = steps[k]; now <= 10000; now += steps[k]) {
                for (unsigned frame = 0; frame < speed; ++frame) {
                    if (pd_refresh_due(&timer, now, 334, false)) {
                        pd_refresh_mark(&timer, now);
                        ++reads;
                    }
                }
            }
            assert(reads >= 24 && reads <= 30);
        }
    }

    // At the 3DS's actual 59.83 Hz cadence, a dirty bottom canvas paints
    // every other presentation. The top screen can still present each time.
    timer = (struct pd_refresh_timer){ 0 };
    unsigned paints = 0;
    const uint64_t interval = (268111856ULL + 29) / 30;
    for (unsigned frame = 0; frame < 60; ++frame) {
        uint64_t now = frame * 4481136ULL;
        if (pd_refresh_due(&timer, now, interval, false)) {
            pd_refresh_mark(&timer, now);
            ++paints;
        }
    }
    assert(paints == 30);
    // A delayed dirty image remains eligible; input and battle transitions
    // are allowed immediately, even after the previous paint.
    assert(!pd_refresh_due(&timer, timer.last + 1, interval, false));
    assert(pd_refresh_due(&timer, timer.last + 1, interval, true));
    assert(pd_refresh_due(&timer, timer.last + interval, interval, false));
    puts("PASS: 3 Hz polling, fast-forward, delayed updates, 30 FPS paint cap, urgent bypass");
    return 0;
}
