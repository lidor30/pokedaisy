// dsp_dump's stand-in for the NDK's <android/log.h>: the app's DS sources log to stderr here.
#pragma once
#include <stdarg.h>
#include <stdio.h>

enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };

static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "%s: ", tag);
    int n = vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
    return n;
}
