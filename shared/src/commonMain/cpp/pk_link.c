// LINK PLAY (pk_link.h): up to four GBA games on mGBA's link cable, one process, one thread.
//
// mGBA's lockstep link (gba/sio/lockstep.c) only defines the hooks of struct mLockstep - signal / wait / addCycles /
// useCycles / unusedCycles / unload; its Qt frontend fills them in MultiplayerController.cpp with a thread per game
// that sleeps and wakes. Here every game runs on the caller's thread and "asleep" means "not run": the callbacks are
// Qt's, with mCoreThreadWaitFromThread / mCoreThreadStopWaiting turned into an awake flag. Game 0 is the link's
// parent; the others run only on the cycles it hands them (cyclesPosted) while the link is up, so none drifts more than
// one lockstep step (2000 cycles) from it. Off the link (an SIO mode the lockstep doesn't drive) they run free, kept to
// game 0's frame count. Checked headless first (native-capture/mgba_link.c builds this file): FireRed and LeafGreen
// traded through the Viridian cable club and both trade evolutions were saved.

#include "pk_link.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <mgba/core/blip_buf.h>
#include <mgba/core/config.h>
#include <mgba/core/lockstep.h>
#include <mgba-util/vfs.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/sio.h>
#include <mgba/internal/gba/sio/lockstep.h>

#include "pk_savevf.h"

#define PK_LINK_AUDIO_FRAMES 4096

struct PkLinkPlayer {
    struct mCore* core;
    color_t* video;
    unsigned w, h;
    struct GBASIOLockstepNode node;
    int awake;              // Qt's Player::awake: 0 = its thread would be waiting
    int32_t cyclesPosted;   // cycles the parent has let this game run ahead
    unsigned waitMask;      // the parent: the children it waits for
    int16_t audio[PK_LINK_AUDIO_FRAMES * 2];
    int audioFrames;
};

static struct PkLinkPlayer players[PK_LINK_MAX];
static int count;
static struct GBASIOLockstep lockstep;
static bool faulted;

/** Set by the app (pokedaisy_jni.c's RTC fix-up) to run on each game after its reset; NULL headless. */
void (*pk_link_after_reset)(struct mCore*) = NULL;

static struct PkLinkPlayer* player(int id) {
    // The lockstep renumbers nodes on a detach; ours never detach, but look them up by id as Qt does.
    for (int i = 0; i < count; i++) if (players[i].node.id == id) return &players[i];
    return &players[id];
}

// --- Qt's MultiplayerController callbacks, one thread ---

static void lsLock(struct mLockstep* ls) { (void) ls; }
static void lsUnlock(struct mLockstep* ls) { (void) ls; }

static bool lsSignal(struct mLockstep* ls, unsigned mask) {
    (void) ls;
    struct PkLinkPlayer* p = player(0);
    p->waitMask &= ~mask;
    if (!p->waitMask && p->awake < 1) {
        p->awake = 1;
        return true;
    }
    return false;
}

static bool lsWait(struct mLockstep* ls, unsigned mask) {
    (void) ls;
    struct PkLinkPlayer* p = player(0);
    p->waitMask |= mask;
    if (p->awake > 0) {
        p->awake = 0;
        return true;
    }
    return false;
}

static void lsAddCycles(struct mLockstep* ls, int id, int32_t cycles) {
    (void) ls;
    if (cycles < 0) return;   // Qt aborts; a bad step here would only slow a child, not crash the app
    if (!id) {
        for (int i = 1; i < count; i++) {
            struct PkLinkPlayer* p = player(i);
            if (p->node.d.p->mode > SIO_MULTI) continue;   // off the link: runs free
            p->cyclesPosted += cycles;
            if (p->awake < 1) p->node.nextEvent += p->cyclesPosted;
            p->awake = 1;
        }
    } else {
        player(id)->cyclesPosted += cycles;
    }
}

static int32_t lsUseCycles(struct mLockstep* ls, int id, int32_t cycles) {
    (void) ls;
    struct PkLinkPlayer* p = player(id);
    p->cyclesPosted -= cycles;
    if (p->cyclesPosted <= 0) p->awake = 0;
    return p->cyclesPosted;
}

static int32_t lsUnusedCycles(struct mLockstep* ls, int id) {
    (void) ls;
    return player(id)->cyclesPosted;
}

static void lsUnload(struct mLockstep* ls, int id) {
    if (id) {
        struct PkLinkPlayer* p = player(id);
        p->cyclesPosted = 0;
        struct PkLinkPlayer* parent = player(0);
        parent->waitMask &= ~(1u << id);
        if (!parent->waitMask && parent->awake < 1) parent->awake = 1;
    } else {
        for (int i = 1; i < count; i++) {
            struct PkLinkPlayer* p = player(i);
            p->cyclesPosted += ((struct GBASIOLockstep*) ls)->players[0]->eventDiff;
            if (p->awake < 1) {
                p->node.nextEvent += p->cyclesPosted;
                p->awake = 1;
            }
        }
    }
}

// --- games ---

static void drainAudio(struct PkLinkPlayer* p) {
    struct blip_t* left = p->core->getAudioChannel(p->core, 0);
    struct blip_t* right = p->core->getAudioChannel(p->core, 1);
    int avail = blip_samples_avail(left);
    int room = PK_LINK_AUDIO_FRAMES - p->audioFrames;
    if (avail > room) {
        // Nobody read the last frames (a game not being heard): drop the oldest.
        p->audioFrames = 0;
        room = PK_LINK_AUDIO_FRAMES;
        if (avail > room) avail = room;
    }
    if (avail <= 0) return;
    blip_read_samples(left, p->audio + p->audioFrames * 2, avail, 1);
    blip_read_samples(right, p->audio + p->audioFrames * 2 + 1, avail, 1);
    p->audioFrames += avail;
}

static void closeGame(struct PkLinkPlayer* p) {
    if (p->core) p->core->deinit(p->core);   // unloads the ROM, syncs and closes the save VFile
    free(p->video);
    memset(p, 0, sizeof(*p));
}

static bool openGame(struct PkLinkPlayer* p, const char* romPath, const char* savePath, int sampleRate) {
    struct VFile* rom = VFileOpen(romPath, O_RDONLY);
    if (!rom) return false;
    p->core = mCoreFindVF(rom);
    if (!p->core || p->core->platform(p->core) != mPLATFORM_GBA || !p->core->init(p->core)) {
        if (p->core) p->core->deinit(p->core);
        p->core = NULL;
        rom->close(rom);
        return false;
    }
    mCoreInitConfig(p->core, NULL);
    p->core->desiredVideoDimensions(p->core, &p->w, &p->h);
    p->video = calloc((size_t) p->w * p->h, sizeof(color_t));
    p->core->setVideoBuffer(p->core, p->video, p->w);
    p->core->setAudioBufferSize(p->core, 2048);
    blip_set_rates(p->core->getAudioChannel(p->core, 0), p->core->frequency(p->core), sampleRate);
    blip_set_rates(p->core->getAudioChannel(p->core, 1), p->core->frequency(p->core), sampleRate);
    if (!p->core->loadROM(p->core, rom)) {
        rom->close(rom);
        closeGame(p);
        return false;
    }
    // As pkInit: created if missing, read only if it can't be written, behind pk_save_vf (mGBA never checks its map).
    struct VFile* save = VFileOpen(savePath, O_RDWR | O_CREAT);
    if (!save) save = VFileOpen(savePath, O_RDONLY);
    save = pk_save_vf(save);
    if (save) p->core->loadSave(p->core, save);
    p->awake = 1;
    return true;
}

bool pk_link_open(int n, const char* const* roms, const char* const* saves, int sampleRate) {
    pk_link_close(0);
    if (n < 2 || n > PK_LINK_MAX) return false;
    for (int i = 0; i < n; i++) {
        if (!openGame(&players[i], roms[i], saves[i], sampleRate)) {
            for (int k = 0; k <= i; k++) closeGame(&players[k]);
            return false;
        }
    }
    count = n;
    faulted = false;
    GBASIOLockstepInit(&lockstep);
    mLockstepInit(&lockstep.d);
    lockstep.d.lock = lsLock;
    lockstep.d.unlock = lsUnlock;
    lockstep.d.signal = lsSignal;
    lockstep.d.wait = lsWait;
    lockstep.d.addCycles = lsAddCycles;
    lockstep.d.useCycles = lsUseCycles;
    lockstep.d.unusedCycles = lsUnusedCycles;
    lockstep.d.unload = lsUnload;
    // As Qt's attachGame: a node per game (ids in link order), the driver for both modes the lockstep knows.
    for (int i = 0; i < n; i++) {
        struct PkLinkPlayer* p = &players[i];
        struct GBA* gba = p->core->board;
        GBASIOLockstepNodeCreate(&p->node);
        GBASIOLockstepAttachNode(&lockstep, &p->node);
        GBASIOSetDriver(&gba->sio, &p->node.d, SIO_MULTI);
        GBASIOSetDriver(&gba->sio, &p->node.d, SIO_NORMAL_32);
    }
    for (int i = 0; i < n; i++) {
        players[i].core->reset(players[i].core);
        if (pk_link_after_reset) pk_link_after_reset(players[i].core);
    }
    return true;
}

int pk_link_count(void) {
    return count;
}

static uint32_t frames(int i) {
    return players[i].core->frameCounter(players[i].core);
}

// One frame of game 0, the others interleaved. The parent runs while it isn't waiting; after each of its run loops a
// child runs as far as it may: on the link, its posted cycles (useCycles puts it to sleep at 0); off it, up to the
// parent's frame. Every game waiting at once would be a lockstep bug: reported, never hung.
bool pk_link_run_frame(void) {
    if (count < 2 || faulted) return false;
    uint32_t start = frames(0);
    long guard = 0;
    while (frames(0) == start) {
        bool ran = false;
        if (players[0].awake > 0) {
            players[0].core->runLoop(players[0].core);
            ran = true;
        }
        for (int i = 1; i < count; i++) {
            struct PkLinkPlayer* p = &players[i];
            while (p->awake > 0 && (players[0].awake < 1 || frames(i) <= frames(0))) {
                p->core->runLoop(p->core);
                ran = true;
                if (++guard > 20000000) break;
            }
        }
        if (!ran || ++guard > 20000000) {
            faulted = true;
            return false;
        }
    }
    for (int i = 0; i < count; i++) drainAudio(&players[i]);
    return true;
}

struct mCore* pk_link_core(int i) {
    return i >= 0 && i < count ? players[i].core : NULL;
}

const color_t* pk_link_video(int i) {
    return i >= 0 && i < count ? players[i].video : NULL;
}

unsigned pk_link_video_w(int i) {
    return i >= 0 && i < count ? players[i].w : 0;
}

unsigned pk_link_video_h(int i) {
    return i >= 0 && i < count ? players[i].h : 0;
}

void pk_link_set_keys(int i, uint32_t keys) {
    if (i >= 0 && i < count) players[i].core->setKeys(players[i].core, keys);
}

int pk_link_read_audio(int i, int16_t* out, int maxFrames) {
    if (i < 0 || i >= count) return 0;
    struct PkLinkPlayer* p = &players[i];
    int n = p->audioFrames < maxFrames ? p->audioFrames : maxFrames;
    memcpy(out, p->audio, (size_t) n * 2 * sizeof(int16_t));
    memmove(p->audio, p->audio + n * 2, (size_t) (p->audioFrames - n) * 2 * sizeof(int16_t));
    p->audioFrames -= n;
    return n;
}

void pk_link_set_audio_rate(double rate) {
    if (rate <= 0) return;
    for (int i = 0; i < count; i++) {
        struct mCore* c = players[i].core;
        blip_set_rates(c->getAudioChannel(c, 0), c->frequency(c), rate);
        blip_set_rates(c->getAudioChannel(c, 1), c->frequency(c), rate);
    }
}

bool pk_link_saving(void) {
    for (int i = 0; i < count; i++) {
        if (((struct GBA*) players[i].core->board)->memory.savedata.dirty) return true;
    }
    return false;
}

void pk_link_close(int maxFrames) {
    if (count) {
        // A game mid-"SAVING..." finishes first, the link still running (a link save writes on every side).
        for (int i = 0; i < count; i++) pk_link_set_keys(i, 0);
        for (int n = 0; n < maxFrames && pk_link_saving(); n++) {
            if (!pk_link_run_frame()) break;
        }
        for (int i = 0; i < count; i++) {
            struct GBA* gba = players[i].core->board;
            GBASIOSetDriver(&gba->sio, NULL, SIO_MULTI);
            GBASIOSetDriver(&gba->sio, NULL, SIO_NORMAL_32);
        }
    }
    for (int i = 0; i < PK_LINK_MAX; i++) closeGame(&players[i]);
    count = 0;
    faulted = false;
}

static const char* modeName(int mode) {
    switch (mode) {
    case SIO_NORMAL_8: return "NORMAL8";
    case SIO_NORMAL_32: return "NORMAL32";
    case SIO_MULTI: return "MULTI";
    case SIO_UART: return "UART";
    case SIO_GPIO: return "GPIO";
    case SIO_JOYBUS: return "JOYBUS";
    default: return "?";
    }
}

void pk_link_status(char* out, size_t size) {
    size_t used = 0;
    if (size) out[0] = 0;
    for (int i = 0; i < count && used < size; i++) {
        struct PkLinkPlayer* p = &players[i];
        struct GBA* gba = p->core->board;
        used += (size_t) snprintf(out + used, size - used, "game %d frame %u sio %s siocnt %04X awake %d posted %d; ", i,
            frames(i), modeName(gba->sio.mode), gba->sio.siocnt, p->awake, p->cyclesPosted);
    }
    if (used < size) {
        snprintf(out + used, size - used, "phase %d multi %d normal %d%s", lockstep.d.transferActive,
            lockstep.attachedMulti, lockstep.attachedNormal, faulted ? " FAULT" : "");
    }
}
