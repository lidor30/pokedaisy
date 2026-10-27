// PokeDaisy — JNI for the co-op story lock (pk_coop_lock.c; Emerald, GBA only): CoopLockNative.kt. Emu thread, like pkCoop*.
// Install / uninstall change the ROM overlay and so take the core lock alone; the rest only read or write RAM (go also calls
// the game, between frames) and share it - the same locks pokedaisy_jni.c's co-op wrappers take.

#include <jni.h>
#include <stdint.h>

#include <mgba/core/core.h>

#include "pk_coop_lock.h"
#include "pk_core_access.h"

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_CoopLockNative_install(JNIEnv* env, jobject thiz) {
    pk_core_wrlock();
    struct mCore* core = pkMainCore();
    int ok = core && pk_core_is_gba(core) ? pk_coop_lock_install(core, pk_core_call) : 0;
    pk_core_unlock();
    return ok ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_CoopLockNative_uninstall(JNIEnv* env, jobject thiz) {
    pk_core_wrlock();
    struct mCore* core = pkMainCore();
    if (core) {
        pk_coop_lock_uninstall(core);
    }
    pk_core_unlock();
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_CoopLockNative_setActive(JNIEnv* env, jobject thiz, jboolean on) {
    pk_core_rdlock();
    struct mCore* core = pkMainCore();
    if (core && pk_core_is_gba(core)) {
        pk_coop_lock_set_active(core, on ? 1 : 0);
    }
    pk_core_unlock();
}

// [id, kind]: id 0 = nothing held.
JNIEXPORT jintArray JNICALL
Java_com_pokedaisy_app_CoopLockNative_held(JNIEnv* env, jobject thiz) {
    int kind = 0, id = 0;
    pk_core_rdlock();
    struct mCore* core = pkMainCore();
    if (core && pk_core_is_gba(core)) {
        id = pk_coop_lock_held(core, &kind);
    }
    pk_core_unlock();
    jintArray out = (*env)->NewIntArray(env, 2);
    if (out) {
        jint v[2] = {id, kind};
        (*env)->SetIntArrayRegion(env, out, 0, 2, v);
    }
    return out;
}

// 1 started / allowed, 0 refused (not held / not this id), -1 not now (ask again on a later frame).
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_CoopLockNative_go(JNIEnv* env, jobject thiz, jint id) {
    pk_core_rdlock();
    struct mCore* core = pkMainCore();
    int r = core && pk_core_is_gba(core) ? pk_coop_lock_go(core, id) : 0;
    pk_core_unlock();
    return r;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_CoopLockNative_cancel(JNIEnv* env, jobject thiz) {
    pk_core_rdlock();
    struct mCore* core = pkMainCore();
    if (core && pk_core_is_gba(core)) {
        pk_coop_lock_cancel(core);
    }
    pk_core_unlock();
}
