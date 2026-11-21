// DS RESOLUTION's GPU renderer (pk_nds_gl.cpp) is OpenGL ES 3.2 through EGL, on Android. The Windows core runs the
// software renderer, so these report no GPU path and do nothing: pk_nds.cpp asks before it uses any of it.
#include "pk_nds_gl.h"
#include "pk_nds_cores.h"

int pk_nds_gl_set_scale(melonDS::NDS*, int) { return 1; }
int pk_nds_gl_scale() { return 1; }
int pk_nds_gl_supported() { return 0; }
void pk_nds_gl_end(melonDS::NDS*) {}
void pk_nds_gl_begin_frame() {}
void pk_nds_gl_set_window(ANativeWindow*) {}
// BananaDS's own hooks: that third DS core isn't built for Windows.
void pk_ndsb_set_window(ANativeWindow*) {}
void pk_ndsb_set_wild_rates(const int32_t*, int) {}
bool pk_nds_gl_frame(melonDS::NDS*, int, int, int, int, uint32_t*, uint32_t*) { return false; }
void pk_nds_gl_capture(bool) {}
const uint32_t* pk_nds_gl_captured(int* w, int* h) { if (w) *w = 0; if (h) *h = 0; return nullptr; }
bool pk_nds_gl_frame_info(int64_t, int32_t*) { return false; }
