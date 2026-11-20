// JNI adapter for the portable co-op story lock. The core calls run between emulator frames.
#include <jni.h>

#include "pk_coop_api.h"

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_CoopLockNative_install(JNIEnv* env, jobject thiz) {
    return pk_api_coop_lock_install() ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_CoopLockNative_uninstall(JNIEnv* env, jobject thiz) {
    pk_api_coop_lock_uninstall();
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_CoopLockNative_setActive(JNIEnv* env, jobject thiz, jboolean on) {
    pk_api_coop_lock_set_active(on);
}

JNIEXPORT jintArray JNICALL
Java_com_pokedaisy_app_CoopLockNative_held(JNIEnv* env, jobject thiz) {
    jint values[2];
    pk_api_coop_lock_held(values);
    jintArray out = (*env)->NewIntArray(env, 2);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 2, values);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_CoopLockNative_go(JNIEnv* env, jobject thiz, jint id) {
    return pk_api_coop_lock_go(id);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_CoopLockNative_cancel(JNIEnv* env, jobject thiz) {
    pk_api_coop_lock_cancel();
}
