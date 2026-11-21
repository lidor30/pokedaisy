/* Android's window type, which the shared DS engine only passes through. The Windows core renders in software and never
 * opens one: these retain calls do nothing. */
#ifndef PD_WIN_ANDROID_NATIVE_WINDOW_H
#define PD_WIN_ANDROID_NATIVE_WINDOW_H

struct ANativeWindow;

static inline void ANativeWindow_acquire(struct ANativeWindow *window) { (void)window; }
static inline void ANativeWindow_release(struct ANativeWindow *window) { (void)window; }

#endif
