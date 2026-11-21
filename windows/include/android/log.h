// android/log.h for the Windows build: the app's DS engine (app/src/main/cpp/nds/pk_nds.cpp) logs through
// __android_log_print; on Windows it goes to stderr instead. The include path (windows/include) is only added to the
// Windows targets, so the Android build still gets the NDK's own header.
#ifndef PD_WIN_ANDROID_LOG_H
#define PD_WIN_ANDROID_LOG_H

#include <cstdarg>
#include <cstdio>

#define ANDROID_LOG_UNKNOWN 0
#define ANDROID_LOG_DEFAULT 1
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_FATAL 7
#define ANDROID_LOG_SILENT 8

#ifdef __cplusplus
extern "C" {
#endif

static inline int __android_log_print(int, const char* tag, const char* fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    if (tag && *tag) fprintf(stderr, "%s: ", tag);
    vfprintf(stderr, fmt, args);
    fputc('\n', stderr);
    fflush(stderr);
    va_end(args);
    return 0;
}

#ifdef __cplusplus
}
#endif

#endif
