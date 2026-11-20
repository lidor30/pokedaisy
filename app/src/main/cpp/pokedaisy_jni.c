// PokeDaisy — JNI bridge over the mGBA core (libmgba, vendored at 0.10.5) and the DS cores (nds/).
//
// The GBA / Game Boy game itself - starting it, its frames, picture, sound, keys and memory reads - is pk_session.c,
// behind the plain C API in pk_api.h that the iPad app calls too; the wrappers here add the DS games in front of it.
// The rest (cheats, EXPAND, REWIND, the game calls...) still works on its state here as `g`
// (pk_session.h) and moves under pk_api.h area by area.
// Everything here is called from a single Kotlin "emu thread", which also copies
// pkVideoBuffer()'s frames out for the GL thread (EmulatorView.publishFrame).

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <time.h>

#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/config.h>
#include <mgba/core/serialize.h>
#include <mgba-util/vfs.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gb/gb.h>

#include "pk_call.h"
#include "pk_cheats.h"
#include "pk_coop.h"
#include "pk_coop_api.h"
#include "pk_coop_after.h"
#include "pk_coop_field.h"
#include "pk_coop_lock.h"   // coop-lock (plan 5 Task 5)
#include "pk_core_access.h"   // coop-lock (plan 5 Task 5)
#include "pk_savevf.h"
#include "pk_wide.h"
#include "pk_flight.h"
#include "pk_gb_field.h"
#include "pk_gb_audio.h"
#include "pk_host.h"
#include "nds/pk_nds.h"
#include "pk_session.h"   // last: its `g` (the game's state) is a macro

#define LOG_TAG "pokedaisy/jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static struct pk_sound_renderer* soundRenderer;

// The DS game (if one is up) and then the GBA / Game Boy one closed: pkInit and pkDeinit.
static void pkTeardown(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    pk_nds_deinit();
    pthread_rwlock_unlock(&pk_coreLock);
    pk_api_deinit();
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkInit(JNIEnv* env, jobject thiz,
                                                  jstring jRomPath, jstring jSavePath) {
    pkTeardown();

    const char* romPath = (*env)->GetStringUTFChars(env, jRomPath, NULL);
    const char* savePath = jSavePath ? (*env)->GetStringUTFChars(env, jSavePath, NULL) : NULL;
    bool ok;
    if (pk_nds_is_rom(romPath)) {
        // A DS cart: melonDS (nds/pk_nds.cpp) instead of mGBA; g.core stays NULL, so every GBA-only call is a no-op.
        pthread_rwlock_wrlock(&pk_coreLock);
        ok = pk_nds_init(romPath, savePath, PK_SAMPLE_RATE);
        // EXPAND's window, as a GBA game's (pk_nds_wide_draw fills it)
        if (ok) g.wide = calloc((size_t) PK_WIDE_MAX_VIEW_W * PK_WIDE_MAX_VIEW_H, sizeof(uint32_t));
        pthread_rwlock_unlock(&pk_coreLock);
    } else {
        ok = pk_api_init(romPath, savePath);
    }
    if (savePath) (*env)->ReleaseStringUTFChars(env, jSavePath, savePath);
    (*env)->ReleaseStringUTFChars(env, jRomPath, romPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkDeinit(JNIEnv* env, jobject thiz) {
    pkTeardown();
}

JNIEXPORT jobject JNICALL
Java_com_pokedaisy_app_MgbaCore_pkVideoBuffer(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) {
        return (*env)->NewDirectByteBuffer(env, pk_nds_top(), (jlong) PK_NDS_W * PK_NDS_H * 4);
    }
    void* video = (void*) pk_api_video();
    if (!video) {
        return NULL;
    }
    return (*env)->NewDirectByteBuffer(env, video, (jlong) pk_api_video_width() * pk_api_video_height() * 4);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkVideoWidth(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) return PK_NDS_W;
    return pk_api_video_width();
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkVideoHeight(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) return PK_NDS_H;
    return pk_api_video_height();
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSampleRate(JNIEnv* env, jobject thiz) {
    return PK_SAMPLE_RATE;
}

// The rate the core's audio is resampled to, in samples per emulated second: PK_SAMPLE_RATE
// normally; a little off it while frames are paced by the display (EmulatorEngine's vsync
// pacing), so the audio device still gets PK_SAMPLE_RATE per real second.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSetAudioRate(JNIEnv* env, jobject thiz, jdouble rate) {
    if (pk_nds_active()) {
        pk_nds_set_audio_rate(rate);
        return;
    }
    pk_api_set_audio_rate(rate);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSetKeys(JNIEnv* env, jobject thiz, jint mask) {
    if (pk_nds_active()) {
        pk_nds_set_keys((uint32_t) mask);
        return;
    }
    pk_api_set_keys((uint32_t) mask);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFinishSave(JNIEnv* env, jobject thiz, jint maxFrames) {
    if (pk_nds_active()) return pk_nds_finish_save(maxFrames);
    return pk_api_finish_save(maxFrames);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRunFrame(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) {
        pk_nds_run_frame();
        g.audioAvail = 2 * pk_nds_read_audio(g.audio, PK_AUDIO_SCRATCH_SAMPLES);
        // EXPAND: the camera's window around the frame (the margins drawn on the overworld only)
        g.wideSkipped = false;
        // Not drawn (fast-forward's unseen frames, or one whose 3D the frame before skipped): nothing new to show.
        if (g.wide && g.wideCrc && g.viewW > 0 && g.viewH > 0 && (g.wideSkipNext || !pk_nds_frame_drawn())) {
            g.wideSkipped = true;
        } else if (pk_nds_scale() <= 1 && g.wide && g.wideCrc && g.viewW > 0 && g.viewH > 0 &&
                   pk_nds_wide_draw(g.wide, g.viewX, g.viewY, g.viewW, g.viewH)) {
            // (the GPU path puts its window out itself: pk_nds_presented)
            g.wideW = g.viewW;
            g.wideH = g.viewH;
        } else {
            g.wideW = g.wideH = 0;
        }
        return;
    }
    pk_api_run_frame();
}

// WIDE: draw the game whose ROM has this CRC32 (pk_wide_games.h; 0 = off). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideConfigure(JNIEnv* env, jobject thiz, jint crc) {
    if (pk_nds_active()) {
        // A DS game: any id but 0 turns its EXPAND on (WideScreen.NDS)
        g.wideCrc = (uint32_t) crc;
        pk_nds_wide_enable(crc != 0);
        if (!crc) g.wideW = g.wideH = 0;
        return;
    }
    pk_api_wide_configure((uint32_t) crc);
}

// SCENE SPEEDS / AUTO-RUN / RUN INDOORS / WILD ENCOUNTERS: read the field with the tables of the game whose ROM has
// this CRC32 (0 = none), and its wild-encounter switch at wildFlag (0 = not found). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFieldConfigure(JNIEnv* env, jobject thiz, jint crc, jint wildFlag, jboolean wildOff) {
    bool gba = g.core && pk_is_gba(g.core);
    pk_api_field_configure(gba ? (uint32_t) crc : 0);
    pk_api_field_wild(gba ? (uint32_t) wildFlag : 0, wildOff == JNI_TRUE);
}

// This frame's PK_FIELD_* bits (pk_field_state; 0 = not a game it knows); runIndoors lets the player run indoors,
// wildKeep (percent) thins out wild encounters. Emu thread, between frames.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFieldState(JNIEnv* env, jobject thiz, jboolean runIndoors, jint wildKeep) {
    if (pk_nds_active()) {
        // a DS game's own read (nds/pk_nds_field.cpp); WILD ENCOUNTERS is on / off there (any percent < 100 = off)
        pk_nds_set_wild_off(wildKeep < 100);
        return pk_nds_field_bits();
    }
    return pk_api_field_step(runIndoors == JNI_TRUE, wildKeep);
}

// PLAYER LOOK: the player's sprite set up again from its graphics record (after the look changed, or a state loaded).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkLookRefresh(JNIEnv* env, jobject thiz, jbyteArray pal) {
    jbyte* p = pal && (*env)->GetArrayLength(env, pal) == 32 ? (*env)->GetByteArrayElements(env, pal, NULL) : NULL;
    bool ok = pk_api_look_refresh((const uint8_t*) p, p ? 32 : 0);
    if (p) (*env)->ReleaseByteArrayElements(env, pal, p, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// FOLLOWER: its frames + palette (null = none), and whether it's shown this frame. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFollowerSet(JNIEnv* env, jobject thiz, jbyteArray frames, jbyteArray pal) {
    if (!frames || !pal || (*env)->GetArrayLength(env, frames) != PK_API_FOLLOWER_FRAME_BYTES ||
        (*env)->GetArrayLength(env, pal) != PK_API_FOLLOWER_PALETTE_BYTES) {
        pk_api_follower_set(NULL, 0, NULL, 0);
        return;
    }
    jbyte* f = (*env)->GetByteArrayElements(env, frames, NULL);
    jbyte* p = (*env)->GetByteArrayElements(env, pal, NULL);
    pk_api_follower_set((const uint8_t*) f, PK_API_FOLLOWER_FRAME_BYTES,
                       (const uint8_t*) p, PK_API_FOLLOWER_PALETTE_BYTES);
    (*env)->ReleaseByteArrayElements(env, frames, f, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, pal, p, JNI_ABORT);
}

// RIDE ON: the vehicle's views + palette (null = none) and whether it's drawn. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRideSet(JNIEnv* env, jobject thiz, jbyteArray frames, jbyteArray pal, jint width) {
    jsize nf = frames ? (*env)->GetArrayLength(env, frames) : 0;
    jsize np = pal ? (*env)->GetArrayLength(env, pal) : 0;
    jbyte* f = nf == PK_API_RIDE_FRAME_BYTES ? (*env)->GetByteArrayElements(env, frames, NULL) : NULL;
    jbyte* p = np == 32 ? (*env)->GetByteArrayElements(env, pal, NULL) : NULL;
    pk_api_ride_set((const uint8_t*) f, nf, (const uint8_t*) p, np, width);
    if (f) (*env)->ReleaseByteArrayElements(env, frames, f, JNI_ABORT);
    if (p) (*env)->ReleaseByteArrayElements(env, pal, p, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRideShow(JNIEnv* env, jobject thiz, jboolean on) {
    pk_api_ride_show(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFollowerShow(JNIEnv* env, jobject thiz, jboolean on) {
    pk_api_follower_show(on);
}

// Co-op presence: the player's map, fine position, facing, mode and step count this frame (pk_partner_self's
// PK_API_PARTNER_SELF_SIZE ints), or null off a GBA game the field reads know. Emu thread, once a frame between frames.
JNIEXPORT jintArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkPartnerSelf(JNIEnv* env, jobject thiz) {
    int32_t v[PK_API_PARTNER_SELF_SIZE];
    if (!pk_api_partner_self(v, PK_API_PARTNER_SELF_SIZE)) return NULL;
    jintArray out = (*env)->NewIntArray(env, PK_API_PARTNER_SELF_SIZE);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, PK_API_PARTNER_SELF_SIZE, (const jint*) v);
    return out;
}

// Co-op presence: the partner's art (PK_API_PARTNER_ART_SIZE: 42 32x32 frames + palette), or null / any other length = none
// (a wrong-sized array is refused, never read past its end). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkPartnerSet(JNIEnv* env, jobject thiz, jbyteArray art) {
    if (!art || (*env)->GetArrayLength(env, art) != PK_API_PARTNER_ART_SIZE) {
        pk_api_partner_set(NULL, 0);
        return;
    }
    jbyte* a = (*env)->GetByteArrayElements(env, art, NULL);
    if (!a) {
        pk_api_partner_set(NULL, 0);
        return;
    }
    pk_api_partner_set((const uint8_t*) a, PK_API_PARTNER_ART_SIZE);
    (*env)->ReleaseByteArrayElements(env, art, a, JNI_ABORT);
}

// Co-op presence: where the partner is drawn from the next frame on (pk_partner_place). Emu thread, between frames.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkPartnerPlace(JNIEnv* env, jobject thiz, jboolean visible, jint fineX, jint fineY, jint frame,
                                               jboolean hflip, jint blobFrame, jint bob) {
    pk_api_partner_place(visible, fineX, fineY, frame, hflip, blobFrame, bob);
}

// DAY AND NIGHT: the map's and sprites' palettes tinted (256 = as is). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSetTint(JNIEnv* env, jobject thiz, jint r, jint gr, jint b) {
    pk_api_set_tint(r, gr, b);
}

// HIDE BATTLE MENU: the battle's FIGHT / BAG and move menus off the frames drawn (1 = the arrow down, 2 = up, 0 = shown);
// [battleTypeFlags] gBattleTypeFlags' address, 0 = unknown. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkBattleMenuHide(JNIEnv* env, jobject thiz, jint mode, jint battleTypeFlags) {
    if (g.core && pk_is_gba(g.core)) pk_battle_menu_hide(g.core, mode, (uint32_t) battleTypeFlags);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkBattleMenuHidden(JNIEnv* env, jobject thiz) {
    (void) env; (void) thiz;
    return g.core && pk_is_gba(g.core) ? pk_battle_menu_hidden() : 0;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkBattleMenuSignWhy(JNIEnv* env, jobject thiz) {
    (void) env; (void) thiz;
    return g.core && pk_is_gba(g.core) ? pk_battle_menu_sign_why() : 0;
}

// SCENE SPEEDS' TEXT / AUTO-RUN / RUN INDOORS / WILD ENCOUNTERS: whether the field reads know the ROM with this CRC32.
// Any thread, no core needed.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFieldKnows(JNIEnv* env, jobject thiz, jint crc) {
    return pk_api_field_knows((uint32_t) crc) ? JNI_TRUE : JNI_FALSE;
}

// WIDE: whether it has tables for the ROM with this CRC32. Any thread, no core needed.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideKnows(JNIEnv* env, jobject thiz, jint crc) {
    return pk_api_wide_knows((uint32_t) crc) ? JNI_TRUE : JNI_FALSE;
}

// WIDE and the field reads: a hack RetailEngine.kt proved to keep a retail build's engine, by its own row. Any thread, no core
// needed; before the game is configured.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideAddDerived(JNIEnv* env, jobject thiz, jint crc, jint baseCrc, jintArray row) {
    if (!row) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, row);
    jint* a = (*env)->GetIntArrayElements(env, row, NULL);
    if (!a) return JNI_FALSE;
    int ok = pk_api_wide_add_derived((uint32_t) crc, (uint32_t) baseCrc, (const int32_t*) a, (int) n);
    (*env)->ReleaseIntArrayElements(env, row, a, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// WIDE: the window to draw from the next frame on, in the frame's pixels (its top-left (0, 0)); w 0 = none.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideSetView(JNIEnv* env, jobject thiz, jint x, jint y, jint w, jint h) {
    pk_api_wide_set_view(x, y, w, h);
    if (pk_nds_active()) pk_nds_wide_window(x, y, w, h);
}

// The wide frame buffer (room for the largest window; the last frame is pkWideWidth() x pkWideHeight()).
JNIEXPORT jobject JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideBuffer(JNIEnv* env, jobject thiz) {
    const void* wide = pk_api_wide_video();
    if (!wide) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*) wide, (jlong) PK_WIDE_MAX_VIEW_W * PK_WIDE_MAX_VIEW_H * sizeof(uint32_t));
}

// The last frame's window size, or 0 when it wasn't drawn wide (the screen shows pkVideoBuffer). Emu thread.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideWidth(JNIEnv* env, jobject thiz) {
    return pk_api_wide_width();
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideHeight(JNIEnv* env, jobject thiz) {
    return pk_api_wide_height();
}

// WIDE: draw the next frame's window (true), or only keep its RAM (false: the screen won't show it). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideSetDraw(JNIEnv* env, jobject thiz, jboolean draw) {
    pk_api_wide_set_draw(draw == JNI_TRUE);
}

// WIDE: the last frame wasn't drawn (pkWideSetDraw(false)): nothing new to publish. Emu thread.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideSkipped(JNIEnv* env, jobject thiz) {
    return pk_api_wide_skipped() ? JNI_TRUE : JNI_FALSE;
}

// WIDE: the signal that switched it off for this game (pk_wide_view's fault guard), or 0. Any thread.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideFault(JNIEnv* env, jobject thiz) {
    return pk_api_wide_fault();
}

// ZOOM: whether the last frame wants the game close up (a battle, a menu, a text box). Emu thread.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideWantsClose(JNIEnv* env, jobject thiz) {
    return pk_api_wide_wants_close() ? JNI_TRUE : JNI_FALSE;
}

// ZOOM: what the last frame is (PK_WIDE_SCENE_*: the field, a text box, an overlay, a battle starting, off). Emu thread.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideScene(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) return pk_nds_wide_scene();
    return pk_api_wide_scene();
}

// REWIND: keep [entries] states (0 = off, the buffer freed). Emu thread, like pkRunFrame.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSetRewind(JNIEnv* env, jobject thiz, jint entries) {
    pk_api_set_rewind(entries);
}

// While [on], each frame steps back through the REWIND buffer instead of forward.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSetRewinding(JNIEnv* env, jobject thiz, jboolean on) {
    pk_api_set_rewinding(on);
}

// The Android bridge owns the same separate render session the iOS host can open.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderInit(JNIEnv* env, jobject thiz, jstring jRomPath) {
    pk_api_sound_close(soundRenderer);
    const char* romPath = (*env)->GetStringUTFChars(env, jRomPath, NULL);
    soundRenderer = pk_api_sound_open(romPath);
    if (romPath) (*env)->ReleaseStringUTFChars(env, jRomPath, romPath);
    return soundRenderer ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderDeinit(JNIEnv* env, jobject thiz) {
    pk_api_sound_close(soundRenderer);
    soundRenderer = NULL;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderRunFrame(JNIEnv* env, jobject thiz) {
    pk_api_sound_frame(soundRenderer);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderSampleRate(JNIEnv* env, jobject thiz) {
    return PK_API_SAMPLE_RATE;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderReadAudio(JNIEnv* env, jobject thiz, jshortArray out) {
    if (!soundRenderer) return 0;
    int16_t samples[PK_AUDIO_SCRATCH_SAMPLES * 2];
    int count = pk_api_sound_audio(soundRenderer, samples, (*env)->GetArrayLength(env, out));
    if (count > 0) (*env)->SetShortArrayRegion(env, out, 0, count, samples);
    return count;
}

// Whether a pkCall on the player's core can run now (pk_call_safe): the field-item jobs wait a frame when it can't.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCallSafe(JNIEnv* env, jobject thiz) {
    return pk_api_call_safe() ? JNI_TRUE : JNI_FALSE;
}

// Calls the game's own function `fn` (r0-r3 = a0-a3) on the player's core, between
// frames (the emu thread), and returns what it returned in r0 - or -1 if it never did.
// FieldItems uses it to use an item from the companion the way the game does
// (VarSet / RemoveBagItem, ExecuteTableBasedItemEffect(mon, item, slot, move))
// instead of steering the game's menus.
JNIEXPORT jlong JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCall(JNIEnv* env, jobject thiz, jlong fn, jint a0, jint a1, jint a2, jint a3) {
    return (jlong) pk_api_call((uint32_t) fn, (uint32_t) a0, (uint32_t) a1, (uint32_t) a2, (uint32_t) a3);
}

// FREE FLIGHT (pk_flight.h): the game's functions through pk_call.
// The game's addresses (FlightFns.words(), PK_FLIGHT_WORDS of them), or null to switch it off. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightConfigure(JNIEnv* env, jobject thiz, jintArray words) {
    if (!words || (*env)->GetArrayLength(env, words) < PK_FLIGHT_WORDS) {
        pk_api_flight_configure(NULL, 0);
        return;
    }
    uint32_t w[PK_FLIGHT_WORDS];
    (*env)->GetIntArrayRegion(env, words, 0, PK_FLIGHT_WORDS, (jint*) w);
    pk_api_flight_configure(w, PK_FLIGHT_WORDS);
}

// Take off (PK_FLIGHT_OK, or why not: pk_flight.h). Emu thread, between frames.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightTakeOff(JNIEnv* env, jobject thiz) {
    return pk_api_flight_take_off();
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightLand(JNIEnv* env, jobject thiz) {
    pk_api_flight_land();
}

// The scenic FLY's route: directions 1 down, 2 up, 3 left, 4 right. Emu thread.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightRoute(JNIEnv* env, jobject thiz, jbyteArray dirs) {
    jsize n = (*env)->GetArrayLength(env, dirs);
    jbyte* b = (*env)->GetByteArrayElements(env, dirs, NULL);
    int r = pk_api_flight_route((const uint8_t*) b, n);
    (*env)->ReleaseByteArrayElements(env, dirs, b, JNI_ABORT);
    return r;
}

// FLIGHT AREA: a bit per map (group << 8 | num) the flight may cross into, or null = anywhere. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightArea(JNIEnv* env, jobject thiz, jbyteArray bits) {
    if (!bits) {
        pk_api_flight_area(NULL, 0);
        return;
    }
    jsize n = (*env)->GetArrayLength(env, bits);
    jbyte* b = (*env)->GetByteArrayElements(env, bits, NULL);
    pk_api_flight_area((const uint8_t*) b, n);
    (*env)->ReleaseByteArrayElements(env, bits, b, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightAreaTurned(JNIEnv* env, jobject thiz) {
    return pk_api_flight_area_turned() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightState(JNIEnv* env, jobject thiz) {
    return pk_api_flight_state();
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightRouteDone(JNIEnv* env, jobject thiz) {
    return pk_api_flight_route_done();
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderForceSong(JNIEnv* env, jobject thiz, jlong addr, jint songId, jint alt) {
    return pk_api_sound_song(soundRenderer, (uint32_t) addr, songId, alt) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderPark(JNIEnv* env, jobject thiz, jlong spin) {
    return pk_api_sound_park_gba(soundRenderer, (uint32_t) spin) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderGbPark(JNIEnv* env, jobject thiz, jint spin) {
    return pk_api_sound_park_gb(soundRenderer, (uint16_t) spin) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderGbCall(JNIEnv* env, jobject thiz, jint fn, jint a, jint c, jint ret) {
    return pk_api_sound_call_gb(soundRenderer, (uint16_t) fn, (uint8_t) a, (uint8_t) c, (uint16_t) ret) ? JNI_TRUE : JNI_FALSE;
}

// Copies up to out.length interleaved shorts of the most recent frame's audio
// into `out`; returns the count written.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkReadAudio(JNIEnv* env, jobject thiz, jshortArray out) {
    int n;
    const int16_t* audio = pk_api_audio(&n);
    if (n <= 0) {
        return 0;
    }
    jsize cap = (*env)->GetArrayLength(env, out);
    if (n > cap) {
        n = cap;
    }
    (*env)->SetShortArrayRegion(env, out, 0, n, audio);
    return n;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSaveState(JNIEnv* env, jobject thiz, jstring jPath) {
    if (pk_nds_active()) {
        const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
        bool ok = pk_nds_save_state(path);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
    bool ok = pk_api_save_state(path);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Does the save inside the state at [path] match the save file the game just
// opened? A state carries the save as it was when the state was made, and
// pkLoadState writes that copy over the file. Run before the auto-resume, so a
// save changed since (another emulator, another saves folder, a file manager)
// is never replaced by an older or empty one. 1 = same, 0 = different,
// -1 = the state holds no save (loading it leaves the file alone) or can't be read.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkStateMatchesSave(JNIEnv* env, jobject thiz, jstring jPath) {
    if (pk_nds_active()) {
        const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
        int r = pk_nds_state_matches_save(path);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return r;
    }
    const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
    int result = pk_api_state_matches_save(path);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkLoadState(JNIEnv* env, jobject thiz, jstring jPath) {
    if (pk_nds_active()) {
        const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
        pthread_rwlock_wrlock(&pk_coreLock);
        bool ok = pk_nds_load_state(path);
        pthread_rwlock_unlock(&pk_coreLock);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
    bool ok = pk_api_load_state(path);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// --- memory / ROM introspection (for the companion / telemetry read) --------

// pkReadBytes / pkReadInto: [len] bytes at [addr] into [dst], under the core lock; false with no core up.
static bool pk_read_any(uint32_t addr, uint8_t* dst, int len) {
    if (!pk_nds_active()) {
        return pk_api_read(addr, dst, len);
    }
    pthread_rwlock_rdlock(&pk_coreLock);
    bool ok = pk_nds_active();
    if (ok) pk_nds_read(addr, dst, len);
    pthread_rwlock_unlock(&pk_coreLock);
    return ok;
}

// [dst]'s length of bytes at [addr], into [dst] (no new array: RamSnapshot copies the game's RAM with it each sample).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkReadInto(JNIEnv* env, jobject thiz, jlong addr, jbyteArray dst) {
    jsize len = (*env)->GetArrayLength(env, dst);
    if (len <= 0 || len > (1 << 24)) {
        return JNI_FALSE;
    }
    uint8_t* p = (*env)->GetPrimitiveArrayCritical(env, dst, NULL);
    if (!p) {
        return JNI_FALSE;
    }
    bool ok = pk_read_any((uint32_t) addr, p, len);
    (*env)->ReleasePrimitiveArrayCritical(env, dst, p, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkReadBytes(JNIEnv* env, jobject thiz, jlong addr, jint len) {
    if (len <= 0 || len > (1 << 24)) {
        return NULL;
    }
    uint8_t* tmp = malloc((size_t) len);
    if (!tmp) {
        return NULL;
    }
    bool ok = pk_read_any((uint32_t) addr, tmp, len);
    jbyteArray out = ok ? (*env)->NewByteArray(env, len) : NULL;
    if (out) {
        (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte*) tmp);
    }
    free(tmp);
    return out;
}

// Writes `bytes` to the player's core at GBA address `addr` - EWRAM / IWRAM only,
// so nothing else (I/O, VRAM, ROM) can be poked through it. The emu thread, between
// frames: FieldItems puts a SetMonData argument in scratch RAM with it. A Game Boy
// game: its WRAM only, a Color bank by (bank << 16) | D000-DFFF (pk_gb_write).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWriteBytes(JNIEnv* env, jobject thiz, jlong addr, jbyteArray bytes) {
    jsize len = (*env)->GetArrayLength(env, bytes);
    uint32_t a = (uint32_t) addr;
    if (pk_nds_active()) {
        jbyte* b = (*env)->GetByteArrayElements(env, bytes, NULL);
        bool ok = b && pk_nds_write(a, (const uint8_t*) b, len);
        if (b) (*env)->ReleaseByteArrayElements(env, bytes, b, JNI_ABORT);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    jbyte* b = len > 0 ? (*env)->GetByteArrayElements(env, bytes, NULL) : NULL;
    bool ok = b && pk_api_write(a, (const uint8_t*) b, len);
    if (b) (*env)->ReleaseByteArrayElements(env, bytes, b, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// A Game Boy game's own call from its main loop (pk_gb_call_at): the game runs on to `at`, then calls `fn` with A = `a`,
// B = `b`, HL = `hl` and comes back to `at`. The emu thread, between frames - Yellow's PC anywhere (GbField.kt).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkGbCallAt(JNIEnv* env, jobject thiz, jint at, jint fn, jint a, jint b, jint hl) {
    return pk_api_gb_call_at((uint16_t) at, (uint16_t) fn, (uint8_t) a, (uint8_t) b, (uint16_t) hl) ? JNI_TRUE : JNI_FALSE;
}

// Same as pkReadBytes, from the FF-music render core: FfMusicRenderer
// reads which song header it actually started, like the live game's key.
JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderReadBytes(JNIEnv* env, jobject thiz, jlong addr, jint len) {
    if (!soundRenderer || len <= 0 || len > (1 << 24)) {
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (!out) {
        return NULL;
    }
    uint8_t* tmp = malloc((size_t) len);
    if (!tmp) {
        return out;
    }
    if (pk_api_sound_read(soundRenderer, (uint32_t) addr, tmp, len)) {
        (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte*) tmp);
    }
    free(tmp);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomCode(JNIEnv* env, jobject thiz) {
    char code[5];
    if (pk_nds_rom_code(code)) {
        return (*env)->NewStringUTF(env, code);   // the DS header's game code (CPUE: Platinum)
    }
    // the GBA header's ("" for a Game Boy game: 0xAC would be GB ROM bytes)
    return pk_api_rom_code(code) ? (*env)->NewStringUTF(env, code) : NULL;
}

// The cartridge's own bytes [off, off + len) - a Game Boy cart is bank-switched,
// so its ROM can't be read whole over the bus (the Poller hashes it this way).
JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomRead(JNIEnv* env, jobject thiz, jlong off, jint len) {
    if (off < 0 || len <= 0 || len > (1 << 24)) {
        return NULL;
    }
    uint8_t* tmp = malloc((size_t) len);
    if (!tmp) {
        return NULL;
    }
    jbyteArray out = pk_api_rom_read((int64_t) off, tmp, len) ? (*env)->NewByteArray(env, len) : NULL;
    if (out) {
        (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte*) tmp);
    }
    free(tmp);
    return out;
}

// 0 = GBA, 1 = Game Boy / Color, 2 = Nintendo DS, -1 = no core.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkPlatform(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) {
        return 2;
    }
    return pk_api_platform();
}

JNIEXPORT jlong JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomSize(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) return (jlong) pk_nds_rom_size();
    return (jlong) pk_api_rom_size();
}

// --- cheats (pk_cheats.c) ----------------------------------------------------

// Checks a cheat's code lines without a core (Settings can run with no game).
// Returns "<directive>\n<'1' / '0' per line>", null on failure.
JNIEXPORT jstring JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCheatsCheck(JNIEnv* env, jobject thiz, jstring jCode, jint type, jstring jDirective) {
    const char* code = (*env)->GetStringUTFChars(env, jCode, NULL);
    const char* directive = (*env)->GetStringUTFChars(env, jDirective, NULL);
    char* result = pk_api_cheats_check(code, (int) type, directive);
    (*env)->ReleaseStringUTFChars(env, jDirective, directive);
    (*env)->ReleaseStringUTFChars(env, jCode, code);
    if (!result) {
        return NULL;
    }
    // Directive names and '0'/'1' only: plain ASCII, safe for NewStringUTF.
    jstring out = (*env)->NewStringUTF(env, result);
    free(result);
    return out;
}

// Emu thread: replaces the player's core's cheats with `text` (mGBA .cheats
// format, enabled cheats only; empty = none). Returns the sets loaded, -1 if it
// didn't parse. Under the core lock, so a ROM read never sees half a swap.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCheatsApply(JNIEnv* env, jobject thiz, jstring jText) {
    const char* text = (*env)->GetStringUTFChars(env, jText, NULL);
    int n = pk_api_cheats_apply(text);
    (*env)->ReleaseStringUTFChars(env, jText, text);
    return n;
}

// Emu thread: a game option's ROM patch (ReusableTms.kt) - `value` at `addr`, only
// where the ROM holds `expect` from `expectAddr` (the code around it). Under the
// core lock, so a ROM read never sees half a patch.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomPatch(JNIEnv* env, jobject thiz, jlong expectAddr, jbyteArray jExpect,
                                           jlong addr, jbyteArray jValue) {
    jsize expectLen = (*env)->GetArrayLength(env, jExpect);
    jsize len = (*env)->GetArrayLength(env, jValue);
    uint8_t expect[64];
    uint8_t value[4];
    if (expectLen < 1 || expectLen > (jsize) sizeof(expect) || len < 1 || len > (jsize) sizeof(value)) {
        return JNI_FALSE;
    }
    (*env)->GetByteArrayRegion(env, jExpect, 0, expectLen, (jbyte*) expect);
    (*env)->GetByteArrayRegion(env, jValue, 0, len, (jbyte*) value);
    return pk_api_rom_patch((uint32_t) expectAddr, expect, expectLen, (uint32_t) addr, value, len) ? JNI_TRUE : JNI_FALSE;
}

// Emu thread: takes every game option's ROM patch out again.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomUnpatch(JNIEnv* env, jobject thiz) {
    pk_api_rom_unpatch();
}

// Emu thread: PLAYER LOOK's blocks (PlayerLook.kt) in place of any before - block i is
// `lens[i]` bytes of `data` (back to back) at `addrs[i]`, only where the ROM's CRC32 there is
// `crcs[i]`. All or none: returns how many went in, or -1 (and none in) if one didn't.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomBlocks(JNIEnv* env, jobject thiz, jintArray jAddrs, jintArray jLens,
                                            jintArray jCrcs, jbyteArray jData) {
    jsize n = (*env)->GetArrayLength(env, jAddrs);
    if (n != (*env)->GetArrayLength(env, jLens) || n != (*env)->GetArrayLength(env, jCrcs)) {
        return -1;
    }
    jint* addrs = (*env)->GetIntArrayElements(env, jAddrs, NULL);
    jint* lens = (*env)->GetIntArrayElements(env, jLens, NULL);
    jint* crcs = (*env)->GetIntArrayElements(env, jCrcs, NULL);
    jsize size = (*env)->GetArrayLength(env, jData);
    jbyte* data = (*env)->GetByteArrayElements(env, jData, NULL);
    int done = addrs && lens && crcs && data ?
        pk_api_look_blocks(addrs, lens, crcs, n, (const uint8_t*) data, size) : -1;
    if (data) (*env)->ReleaseByteArrayElements(env, jData, data, JNI_ABORT);
    if (crcs) (*env)->ReleaseIntArrayElements(env, jCrcs, crcs, JNI_ABORT);
    if (lens) (*env)->ReleaseIntArrayElements(env, jLens, lens, JNI_ABORT);
    if (addrs) (*env)->ReleaseIntArrayElements(env, jAddrs, addrs, JNI_ABORT);
    return done;
}

// Emu thread: every PLAYER LOOK block out again.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomUnblock(JNIEnv* env, jobject thiz) {
    pk_api_look_blocks(NULL, NULL, NULL, 0, NULL, 0);
}

// --- co-op (pk_coop.c's retail check, the link bridge, the after-battle steps; Emerald, GBA only) ---------
// Emu thread, like pkCall. The bridge's install / begin change the ROM overlay and so take the core lock alone (like
// pkRomPatch); the rest only read or write RAM and share it.

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopInstall(JNIEnv* env, jobject thiz) {
    return pk_api_coop_enable() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopRefused(JNIEnv* env, jobject thiz) {
    char reason[160];
    pk_api_coop_refusal(false, reason, sizeof(reason));
    return (*env)->NewStringUTF(env, reason);
}

JNIEXPORT jstring JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkRefused(JNIEnv* env, jobject thiz) {
    char reason[160];
    pk_api_coop_refusal(true, reason, sizeof(reason));
    return (*env)->NewStringUTF(env, reason);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopUninstall(JNIEnv* env, jobject thiz) {
    pk_api_coop_disable();
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopBattleStarting(JNIEnv* env, jobject thiz) {
    return pk_api_coop_battle_starting() ? JNI_TRUE : JNI_FALSE;
}

// --- plan 6: the link bridge (pk_coop_link.c). Main core only, never the render core. ---
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkInstall(JNIEnv* env, jobject thiz) {
    return pk_api_coop_link_enable() ? JNI_TRUE : JNI_FALSE;
}

static bool read_coop_name(JNIEnv* env, jbyteArray input, uint8_t out[PK_API_COOP_NAME_SIZE]) {
    if (!input || (*env)->GetArrayLength(env, input) < PK_API_COOP_NAME_SIZE) return false;
    (*env)->GetByteArrayRegion(env, input, 0, PK_API_COOP_NAME_SIZE, (jbyte*) out);
    return true;
}

static jbyteArray coop_byte_array(JNIEnv* env, const uint8_t* bytes, int length) {
    if (length <= 0) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, length);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, length, (const jbyte*) bytes);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkBegin(JNIEnv* env, jobject thiz, jboolean master, jint kind, jint trainerA,
                                                jint trainerB, jbyteArray jName, jint gender, jint otId) {
    uint8_t name[PK_API_COOP_NAME_SIZE];
    return read_coop_name(env, jName, name) &&
           pk_api_coop_link_begin(master, kind, trainerA, trainerB, name, sizeof(name), gender, (uint32_t) otId);
}

JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkTake(JNIEnv* env, jobject thiz) {
    uint8_t bytes[PK_API_COOP_LINK_PACKET_SIZE];
    int length = pk_api_coop_link_take(bytes, sizeof(bytes));
    return coop_byte_array(env, bytes, length);
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkGive(JNIEnv* env, jobject thiz, jbyteArray jBlock) {
    if (!jBlock) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, jBlock);
    if (length > PK_API_COOP_LINK_PACKET_SIZE) return JNI_FALSE;
    uint8_t bytes[PK_API_COOP_LINK_PACKET_SIZE];
    (*env)->GetByteArrayRegion(env, jBlock, 0, length, (jbyte*) bytes);
    return pk_api_coop_link_give(bytes, (size_t) length);
}

JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkBadBlocks(JNIEnv* env, jobject thiz) {
    uint8_t bytes[PK_API_COOP_LINK_DEBUG_SIZE];
    int length = pk_api_coop_link_bad_blocks(bytes, sizeof(bytes));
    return coop_byte_array(env, bytes, length);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkState(JNIEnv* env, jobject thiz) {
    return pk_api_coop_link_state();
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkLeave(JNIEnv* env, jobject thiz, jboolean blackout) {
    pk_api_coop_link_leave(blackout);
}

JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkResult(JNIEnv* env, jobject thiz) {
    uint8_t bytes[PK_API_COOP_LINK_RESULT_SIZE];
    int length = pk_api_coop_link_result(bytes, sizeof(bytes));
    return length == sizeof(bytes) ? coop_byte_array(env, bytes, length) : NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkHostHold(JNIEnv* env, jobject thiz) {
    return pk_api_coop_link_host_hold();
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkHostGo(JNIEnv* env, jobject thiz, jbyteArray jName, jint gender, jint otId) {
    uint8_t name[PK_API_COOP_NAME_SIZE];
    return read_coop_name(env, jName, name) &&
           pk_api_coop_link_host_go(name, sizeof(name), gender, (uint32_t) otId);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkPartnerGo(JNIEnv* env, jobject thiz, jint kind, jint trainerA, jint trainerB,
                                                    jbyteArray jName, jint gender, jint otId) {
    uint8_t name[PK_API_COOP_NAME_SIZE];
    if (!read_coop_name(env, jName, name)) return 0;
    return pk_api_coop_link_partner_go(kind, trainerA, trainerB, name, sizeof(name), gender, (uint32_t) otId);
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopStoryArm(JNIEnv* env, jobject thiz, jint trainerId) {
    return pk_api_coop_story_arm(trainerId);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopStoryState(JNIEnv* env, jobject thiz) {
    return pk_api_coop_story_state();
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopStoryGo(JNIEnv* env, jobject thiz, jboolean master, jbyteArray jName, jint gender, jint otId) {
    uint8_t name[PK_API_COOP_NAME_SIZE];
    return read_coop_name(env, jName, name) &&
           pk_api_coop_story_go(master, name, sizeof(name), gender, (uint32_t) otId);
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopStoryCancel(JNIEnv* env, jobject thiz) {
    return pk_api_coop_story_cancel();
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopStoryDisarm(JNIEnv* env, jobject thiz) {
    pk_api_coop_story_disarm();
}

JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkCaught(JNIEnv* env, jobject thiz) {
    uint8_t bytes[PK_API_COOP_LINK_CATCH_SIZE];
    int length = pk_api_coop_link_caught(bytes, sizeof(bytes));
    return coop_byte_array(env, bytes, length);
}

// A story script held by its trainerbattle is let go (pk_coop_release_script): 1 released, 0 nothing held, -1 not yet (ask again).
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopReleaseScript(JNIEnv* env, jobject thiz) {
    return pk_api_coop_release_script();
}

// The battle is over and its result read: the bridge goes (hooks out, area zeroed) and the result is dropped.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkReset(JNIEnv* env, jobject thiz) {
    pk_api_coop_link_reset();
}

// Emu thread: 1 while the game stands free on the field (pk_coop_on_free_field).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopOnFreeField(JNIEnv* env, jobject thiz) {
    return pk_api_coop_on_free_field() ? JNI_TRUE : JNI_FALSE;
}

// Emu thread: 0 not joinable, 1 wild, 2 two trainers (while Task_BattleStart runs; pk_coop_join_kind).
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopJoinKind(JNIEnv* env, jobject thiz) {
    return pk_api_coop_join_kind();
}

// Emu thread: [trainerA, trainerB] of a two-trainer battle, zeros otherwise.
JNIEXPORT jintArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopJoinTrainers(JNIEnv* env, jobject thiz) {
    int ids[2];
    pk_api_coop_join_trainers(ids);
    jintArray out = (*env)->NewIntArray(env, 2);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 2, (const jint*) ids);
    return out;
}

// Emu thread: free on the field, or on the START menu's top level.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopPartnerFree(JNIEnv* env, jobject thiz) {
    return pk_api_coop_partner_free() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopGiveMon(JNIEnv* env, jobject thiz, jbyteArray jMon) {
    if (!jMon || (*env)->GetArrayLength(env, jMon) != PK_API_COOP_MON_SIZE) return 2;
    uint8_t mon[PK_API_COOP_MON_SIZE];
    (*env)->GetByteArrayRegion(env, jMon, 0, sizeof(mon), (jbyte*) mon);
    return pk_api_coop_give_mon(mon, sizeof(mon));
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopRemoveItem(JNIEnv* env, jobject thiz, jint item, jint count) {
    return pk_api_coop_remove_item(item, count);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopSetTrainerFlag(JNIEnv* env, jobject thiz, jint id) {
    return pk_api_coop_set_trainer_flag(id);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopPrize(JNIEnv* env, jobject thiz, jint id, jboolean single) {
    return pk_api_coop_prize(id, single != JNI_FALSE);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopAddMoney(JNIEnv* env, jobject thiz, jint amount) {
    return pk_api_coop_add_money(amount);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopWritePartyMon(JNIEnv* env, jobject thiz, jint slot, jbyteArray jMon) {
    if (!jMon || (*env)->GetArrayLength(env, jMon) != PK_API_COOP_MON_SIZE) return 0;
    uint8_t mon[PK_API_COOP_MON_SIZE];
    (*env)->GetByteArrayRegion(env, jMon, 0, sizeof(mon), (jbyte*) mon);
    return pk_api_coop_write_party_mon(slot, mon, sizeof(mon));
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopWhiteOut(JNIEnv* env, jobject thiz) {
    return pk_api_coop_white_out();
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopEvolve(JNIEnv* env, jobject thiz, jint slot) {
    return pk_api_coop_evolve(slot);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopSaveGame(JNIEnv* env, jobject thiz) {
    return pk_api_coop_save_game();
}

// Emu thread: the 32-byte palettes in `from` (back to back) swapped for `to` wherever the
// game has one loaded (pk_cheats_repalette). Returns how many.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRepalette(JNIEnv* env, jobject thiz, jbyteArray jFrom, jbyteArray jTo) {
    jsize size = (*env)->GetArrayLength(env, jFrom);
    if (size < 32 || size % 32 || (*env)->GetArrayLength(env, jTo) != 32) return 0;
    jbyte* from = (*env)->GetByteArrayElements(env, jFrom, NULL);
    if (!from) return 0;
    uint8_t to[32];
    (*env)->GetByteArrayRegion(env, jTo, 0, 32, (jbyte*) to);
    int swapped = pk_api_look_repalette((const uint8_t*) from, size, to, sizeof to);
    (*env)->ReleaseByteArrayElements(env, jFrom, from, JNI_ABORT);
    return swapped;
}

// --- Nintendo DS (nds/pk_nds.cpp) --------------------------------------------

// The DS's bottom screen (the touch screen), as pkVideoBuffer is its top one; null without a DS. Emu thread.
JNIEXPORT jobject JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsBottomBuffer(JNIEnv* env, jobject thiz) {
    if (!pk_nds_active()) return NULL;
    return (*env)->NewDirectByteBuffer(env, pk_nds_bottom(), (jlong) PK_NDS_W * PK_NDS_H * 4);
}

// A DS game's frames no one will see (fast-forward's): this one / the next skip their drawing (pk_nds_set_draw).
// The cart's wild encounter rates by map header id ([map, grass, surf] triples), for WILD ENCOUNTERS back on. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsWildRates(JNIEnv* env, jobject thiz, jintArray triples) {
    if (!pk_nds_active() || !triples) return;
    jsize len = (*env)->GetArrayLength(env, triples);
    jint* t = (*env)->GetIntArrayElements(env, triples, NULL);
    pk_nds_set_wild_rates((const int32_t*) t, len / 3);
    (*env)->ReleaseIntArrayElements(env, triples, t, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsSetDraw(JNIEnv* env, jobject thiz, jboolean draw, jboolean drawNext) {
    if (pk_nds_active()) pk_nds_set_draw(draw == JNI_TRUE, drawNext == JNI_TRUE);
}

// DS CPU BOOST: the ARM9 at twice the DS's clock. Emu thread, between frames.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsCpuBoost(JNIEnv* env, jobject thiz, jboolean on) {
    if (pk_nds_active()) pk_nds_set_cpu_boost(on == JNI_TRUE);
}

// DS CORE: which DS core the next pkInit of a DS cart starts (companion.DsCore.CORE_*: 0 melonDS, 1 DSperate, 2 BananaDS). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsSetCore(JNIEnv* env, jobject thiz, jint core) {
    pk_nds_set_core(core);
}

// The DS core that's up (companion.DsCore.CORE_*), or -1 with no DS game.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsCore(JNIEnv* env, jobject thiz) {
    return pk_nds_core();
}

// A DS game's Action Replay codes (cheats/Cheats.kt DsCheats): words, and how many belong to each cheat. Emu thread.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsCheats(JNIEnv* env, jobject thiz, jintArray jWords, jintArray jLengths) {
    if (!pk_nds_active()) return -1;
    jsize nWords = (*env)->GetArrayLength(env, jWords);
    jsize nCheats = (*env)->GetArrayLength(env, jLengths);
    jint* words = (*env)->GetIntArrayElements(env, jWords, NULL);
    jint* lengths = (*env)->GetIntArrayElements(env, jLengths, NULL);
    int n = pk_nds_set_cheats((const uint32_t*) words, nWords, (const int32_t*) lengths, nCheats);
    (*env)->ReleaseIntArrayElements(env, jLengths, lengths, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jWords, words, JNI_ABORT);
    return n;
}

// EXP SHARE on the DS (pk_nds.cpp's expShareTick): mode 0 off / 1 SPLIT / 2 FULL EXP, the party's non-egg slots. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsExpShare(JNIEnv* env, jobject thiz, jint mode, jint mask) {
    if (pk_nds_active()) pk_nds_set_exp_share((int) mode, (uint32_t) mask);
}

// INSTANT TEXT on the DS: our code into the game (pk_nds_code_write). Emu thread.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsCodeWrite(JNIEnv* env, jobject thiz, jlong addr, jbyteArray bytes) {
    if (!pk_nds_active()) return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, bytes);
    jbyte* b = len > 0 ? (*env)->GetByteArrayElements(env, bytes, NULL) : NULL;
    bool ok = b && pk_nds_code_write((uint32_t) addr, (const uint8_t*) b, len);
    if (b) (*env)->ReleaseByteArrayElements(env, bytes, b, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// DS LAYOUT floats the X menu on one screen: it comes back after a menu screen left by touch. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsKeepStartMenu(JNIEnv* env, jobject thiz, jboolean on) {
    if (pk_nds_active()) pk_nds_set_keep_start_menu(on == JNI_TRUE);
}

// DS LAYOUT's transition hold (EmulatorEngine.dsHold): the two screens' brightness fade (pk_nds_fade). Emu thread, after a frame.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsFade(JNIEnv* env, jobject thiz) {
    return pk_nds_active() ? pk_nds_fade() : 0;
}

// DS RESOLUTION (nds/pk_nds_gl.h): 1 = the software renderer, 2-8 the GPU's. Taken before the next frame. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsSetScale(JNIEnv* env, jobject thiz, jint scale) {
    if (pk_nds_active()) pk_nds_set_scale(scale);
}

// The scale in effect (1: software), and whether the GPU path can run here (-1 not tried yet, 0 no, 1 yes).
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsScale(JNIEnv* env, jobject thiz) {
    return pk_nds_active() ? pk_nds_scale() : 1;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsGpuSupported(JNIEnv* env, jobject thiz) {
    return pk_nds_gpu_supported();
}

// The game screen's SurfaceTexture's Surface for the GPU path's frames (null: none). Any thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsGlSetWindow(JNIEnv* env, jobject thiz, jobject surface) {
    pk_nds_set_window(surface ? ANativeWindow_fromSurface(env, surface) : NULL);
}

// Whether the last frame went out through the GPU path (the view then takes it from the SurfaceTexture). Emu thread.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsGlPresented(JNIEnv* env, jobject thiz) {
    return pk_nds_active() && pk_nds_presented() ? JNI_TRUE : JNI_FALSE;
}

// The GPU path's frame presented at [timestamp] (SurfaceTexture.getTimestamp): x, y, w, h in DS pixels, texels wide,
// high into out (6 ints). False when it's not known. Any thread.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsGlFrameInfo(JNIEnv* env, jobject thiz, jlong timestamp, jintArray out) {
    int32_t v[6];
    if (!out || (*env)->GetArrayLength(env, out) < 6 || !pk_nds_gpu_frame_info((int64_t) timestamp, v)) return JNI_FALSE;
    (*env)->SetIntArrayRegion(env, out, 0, 6, (const jint*) v);
    return JNI_TRUE;
}

// STEADY FF music for a DS game (nds/pk_nds_music.h): the song id the game plays (-1 = not mapped), a copy of the game
// that plays on from now at 1x, a frame of its audio, its fade-out, and stopping it. Emu thread, between frames.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsBgm(JNIEnv* env, jobject thiz) {
    return pk_nds_active() ? pk_nds_bgm() : -1;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsBgmState(JNIEnv* env, jobject thiz) {
    return pk_nds_active() ? pk_nds_bgm_state() : -1;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsMusicStart(JNIEnv* env, jobject thiz) {
    return pk_nds_active() && pk_nds_music_start() ? JNI_TRUE : JNI_FALSE;
}

// Fills out (interleaved stereo shorts) with one frame of the copy's audio; returns the shorts written.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsMusicRead(JNIEnv* env, jobject thiz, jshortArray out) {
    if (!pk_nds_active()) return 0;
    jsize len = (*env)->GetArrayLength(env, out);
    jshort* buf = (*env)->GetShortArrayElements(env, out, NULL);
    int n = pk_nds_music_read(buf, len / 2);
    (*env)->ReleaseShortArrayElements(env, out, buf, 0);
    return 2 * n;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsMusicFade(JNIEnv* env, jobject thiz, jint frames) {
    if (pk_nds_active()) pk_nds_music_fade_out(frames);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsMusicStop(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) pk_nds_music_stop();
}

// A DS game's own functions (pk_nds_call.h): posts an action for the next frame (0 = not now), and how it did.
// Emu thread, between frames.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsAction(JNIEnv* env, jobject thiz, jint action, jint a0, jint a1, jint a2, jint a3) {
    uint32_t args[4] = {(uint32_t) a0, (uint32_t) a1, (uint32_t) a2, (uint32_t) a3};
    return pk_nds_active() ? pk_nds_action((int) action, args) : 0;
}

// The job's results once it ran (op i's into [i]), or null.
JNIEXPORT jintArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsActionResults(JNIEnv* env, jobject thiz, jint seq) {
    uint32_t res[32];
    if (!pk_nds_active() || !pk_nds_action_results((int) seq, res)) return NULL;
    jintArray out = (*env)->NewIntArray(env, 32);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 32, (const jint*) res);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsActionStatus(JNIEnv* env, jobject thiz, jint seq) {
    return pk_nds_active() ? pk_nds_action_status((int) seq) : -1;
}

// The stylus on the DS's bottom screen at (x, y) in its pixels, or lifted. Any thread: applied on the next frame.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsTouch(JNIEnv* env, jobject thiz, jboolean down, jint x, jint y) {
    pk_nds_touch(down == JNI_TRUE, (int) x, (int) y);
}
