// host/pd_draw_pace.h: the picture rhythm an Old 3DS's slow renderer gets.
#include <assert.h>
#include <stdio.h>

#include "../../host/pd_draw_pace.h"

#define REFRESH 16743u // one refresh at 59.73 Hz, in us

// Runs a whole cycle (a picture and its undrawn frames) and returns its length; checks the plans' order on the way.
static unsigned cycle(struct pd_draw_pace* p, unsigned renderUs) {
    enum pd_draw_plan first = pd_draw_pace_next(p, renderUs, REFRESH);
    if (first == PD_DRAW_NOW) return 1;
    assert(first == PD_DRAW_LATE);
    assert(pd_draw_plan_holds(first));
    unsigned n = 1;
    for (;;) {
        enum pd_draw_plan next = pd_draw_pace_next(p, renderUs, REFRESH);
        n++;
        assert(n <= PD_DRAW_MOST);
        if (next == PD_DRAW_SKIP_LAST) {
            assert(!pd_draw_plan_holds(next));
            return n;
        }
        assert(next == PD_DRAW_SKIP && pd_draw_plan_holds(next));
    }
}

int main(void) {
    struct pd_draw_pace p = { 0 };

    // A picture inside a refresh: every frame drawn and waited for, as before.
    for (int i = 0; i < 100; i++) assert(cycle(&p, 9000) == 1);
    assert(p.every == 1);

    // Just over 90% of a refresh: one picture in two.
    assert(cycle(&p, 15500) == 2);
    for (int i = 0; i < 20; i++) assert(cycle(&p, 15500) == 2);

    // A heavy screen: three frames, and no more than PD_DRAW_MOST however slow.
    assert(cycle(&p, 40000) == 3);
    assert(cycle(&p, 200000) == PD_DRAW_MOST);

    // One quick picture doesn't speed it up: the slowest of the last eight decides.
    assert(cycle(&p, 9000) == PD_DRAW_MOST);

    // Calm again: only after the eight slow ones are gone and PD_DRAW_CALM pictures in a row, one step at a time.
    unsigned pictures = 0;
    while (p.every > 1) {
        cycle(&p, 9000);
        assert(++pictures < 8 * PD_DRAW_CALM);
    }
    assert(pictures >= 7 + PD_DRAW_CALM);
    assert(cycle(&p, 9000) == 1);

    // Right at the edge (fits 90% but not 75% of the slower rhythm): it stays, rather than flipping back and forth.
    pd_draw_pace_reset(&p);
    assert(cycle(&p, 20000) == 2);
    for (int i = 0; i < 4 * PD_DRAW_CALM; i++) assert(cycle(&p, 14000) == 2);

    // Reset: every frame drawn at once.
    pd_draw_pace_reset(&p);
    assert(cycle(&p, 9000) == 1);

    puts("draw pace: ok");
    return 0;
}
