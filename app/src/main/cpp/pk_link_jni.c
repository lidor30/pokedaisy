// LINK PLAY's JNI (LinkCore.kt) over pk_link.c. Every call comes from LinkActivity's one link thread, the copies of
// the frames out included: nothing here is touched from two threads.

#include <jni.h>
#include <string.h>

#include "pk_host.h"
#include "pk_link.h"

#define PK_LINK_SAMPLE_RATE 48000

extern void (*pk_link_after_reset)(struct mCore*);

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkOpen(JNIEnv* env, jobject thiz, jobjectArray jRoms, jobjectArray jSaves) {
    int n = (*env)->GetArrayLength(env, jRoms);
    if (n < 2 || n > PK_LINK_MAX || (*env)->GetArrayLength(env, jSaves) != n) return JNI_FALSE;
    jstring romStrings[PK_LINK_MAX], saveStrings[PK_LINK_MAX];
    const char* roms[PK_LINK_MAX];
    const char* saves[PK_LINK_MAX];
    for (int i = 0; i < n; i++) {
        romStrings[i] = (jstring) (*env)->GetObjectArrayElement(env, jRoms, i);
        saveStrings[i] = (jstring) (*env)->GetObjectArrayElement(env, jSaves, i);
        roms[i] = (*env)->GetStringUTFChars(env, romStrings[i], NULL);
        saves[i] = (*env)->GetStringUTFChars(env, saveStrings[i], NULL);
    }
    pk_link_after_reset = pk_heal_rtc;   // the RTC fix-up, as the player's own core gets it
    bool ok = pk_link_open(n, roms, saves, PK_LINK_SAMPLE_RATE);
    for (int i = 0; i < n; i++) {
        (*env)->ReleaseStringUTFChars(env, romStrings[i], roms[i]);
        (*env)->ReleaseStringUTFChars(env, saveStrings[i], saves[i]);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkClose(JNIEnv* env, jobject thiz, jint maxFrames) {
    pk_link_close(maxFrames);
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkRunFrame(JNIEnv* env, jobject thiz) {
    return pk_link_run_frame() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkSetKeys(JNIEnv* env, jobject thiz, jint i, jint mask) {
    pk_link_set_keys(i, (uint32_t) mask);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkVideoWidth(JNIEnv* env, jobject thiz, jint i) {
    return (jint) pk_link_video_w(i);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkVideoHeight(JNIEnv* env, jobject thiz, jint i) {
    return (jint) pk_link_video_h(i);
}

/** Game [i]'s frame into [dst] (a direct buffer of w * h * 4 bytes) as RGBA with alpha set: mGBA keeps a pixel's layer
 * in its top byte, which a Bitmap would read as transparency. False if it doesn't fit. */
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkCopyVideo(JNIEnv* env, jobject thiz, jint i, jobject dst) {
    const color_t* video = pk_link_video(i);
    unsigned w = pk_link_video_w(i), h = pk_link_video_h(i);
    uint32_t* out = dst ? (uint32_t*) (*env)->GetDirectBufferAddress(env, dst) : NULL;
    if (!video || !out || sizeof(color_t) != 4 || (*env)->GetDirectBufferCapacity(env, dst) < (jlong) w * h * 4) return JNI_FALSE;
    const uint32_t* in = (const uint32_t*) video;
    for (unsigned k = 0; k < w * h; k++) out[k] = in[k] | 0xFF000000u;
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkReadAudio(JNIEnv* env, jobject thiz, jint i, jshortArray out) {
    jsize len = (*env)->GetArrayLength(env, out);
    jshort* buf = (*env)->GetShortArrayElements(env, out, NULL);
    int frames = pk_link_read_audio(i, buf, len / 2);
    (*env)->ReleaseShortArrayElements(env, out, buf, 0);
    return frames;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkSetAudioRate(JNIEnv* env, jobject thiz, jdouble rate) {
    pk_link_set_audio_rate(rate);
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkSampleRate(JNIEnv* env, jobject thiz) {
    return PK_LINK_SAMPLE_RATE;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkSaving(JNIEnv* env, jobject thiz) {
    return pk_link_saving() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_pokedaisy_app_LinkCore_pkLinkStatus(JNIEnv* env, jobject thiz) {
    char buf[512];
    pk_link_status(buf, sizeof(buf));
    return (*env)->NewStringUTF(env, buf);
}
