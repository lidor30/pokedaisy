// The app's pk_call.h / pk_host.h / pk_cheats.h entry points the 3DS port uses, for whichever core runs the game: armGBA
// (a GBA game, pd_armgba.h) or mGBA (a Game Boy game). The 3DS builds compile the app's pk_call.c, pk_host.c and
// pk_cheats.c with these names given an _mgba ending (ctr/CMakeLists.txt, desktop/sim/CMakeLists.txt), so the app's
// files stay as they are.
#include "pd_armgba.h"

#include <mgba/core/core.h>

#include "../../app/src/main/cpp/pk_call.h"
#include "../../app/src/main/cpp/pk_cheats.h"
#include "../../app/src/main/cpp/pk_host.h"

int pk_call_mgba(struct mCore* core, uint32_t fn, const uint32_t args[4], uint32_t* r0);
int pk_call_safe_mgba(struct mCore* core);
void pk_call_set_cap_mgba(long steps);
void pk_rest_note_mgba(struct mCore* core);
void pk_rest_reset_mgba(void);
int pk_save_dirty_mgba(struct mCore* core);
int pk_finish_save_mgba(struct mCore* core, int maxFrames, void (*afterFrame)(void));
void pk_heal_rtc_mgba(struct mCore* core);
void pk_cheats_clear_mgba(struct mCore* core);
int pk_cheats_apply_mgba(struct mCore* core, const char* text, size_t len);

int pk_call(struct mCore* core, uint32_t fn, const uint32_t args[4], uint32_t* r0) {
    return pd_armgba_is(core) ? pd_armgba_call(fn, args, r0) : pk_call_mgba(core, fn, args, r0);
}

int pk_call_safe(struct mCore* core) {
    return pd_armgba_is(core) ? pd_armgba_call_safe() : pk_call_safe_mgba(core);
}

void pk_call_set_cap(long steps) {
    pd_armgba_call_set_cap(steps);
    pk_call_set_cap_mgba(steps);
}

void pk_rest_note(struct mCore* core) {
    if (pd_armgba_is(core)) pd_armgba_rest_note();
    else pk_rest_note_mgba(core);
}

void pk_rest_reset(void) {
    pd_armgba_rest_reset();
    pk_rest_reset_mgba();
}

int pk_save_dirty(struct mCore* core) {
    return pd_armgba_is(core) ? pd_armgba_save_dirty() : pk_save_dirty_mgba(core);
}

// A save the game is writing finishes before the core stops: frames run, unshown, until its writes have stopped and
// the file is written.
int pk_finish_save(struct mCore* core, int maxFrames, void (*afterFrame)(void)) {
    if (!pd_armgba_is(core)) return pk_finish_save_mgba(core, maxFrames, afterFrame);
    int n = 0;
    if (!pd_armgba_save_dirty()) return 0;
    core->setKeys(core, 0);
    pd_armgba_skip_video(true);
    while (n < maxFrames && pd_armgba_save_dirty()) {
        core->runFrame(core);
        if (afterFrame) afterFrame();
        n++;
    }
    pd_armgba_skip_video(false);
    pd_armgba_flush_save();
    return n;
}

void pk_heal_rtc(struct mCore* core) {
    if (pd_armgba_is(core)) pd_armgba_heal_rtc();
    else pk_heal_rtc_mgba(core);
}

void pk_cheats_clear(struct mCore* core) {
    if (pd_armgba_is(core)) pd_armgba_cheats_clear();
    else pk_cheats_clear_mgba(core);
}

int pk_cheats_apply(struct mCore* core, const char* text, size_t len) {
    return pd_armgba_is(core) ? pd_armgba_cheats_apply(text, len) : pk_cheats_apply_mgba(core, text, len);
}
