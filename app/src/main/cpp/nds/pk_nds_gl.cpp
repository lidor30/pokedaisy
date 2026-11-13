// See pk_nds_gl.h. The compositor's colour maths are melonDS's own (its GL compositor, GPU_OpenGL_shaders.h, and the
// soft renderer's blends for EXPAND's margins, pk_nds_wide.cpp's composed()), in one shader of ours.

#include "pk_nds_gl.h"
#include "pk_nds_wide.h"

#include <NDS.h>
#include <GPU.h>
#include <GPU3D_OpenGL.h>
#include <GPU3D_Soft.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#include <android/native_window.h>
#define LOG_TAG "pokedaisy/nds-gl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
// nds_dump on the Mac (ANGLE): no window, frames kept in memory (pk_nds_gl_capture).
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

using namespace melonDS;

namespace {

constexpr int W = 256, H = 192;
// The window's texels at most (as pk_nds_wide.cpp's GPU_BUDGET for the 3D): a far zoom-out is put out at a lower scale.
constexpr long long OUT_BUDGET = 256LL * 8 * 192 * 8 * 3 / 2;

const char* kVS = R"(#version 300 es
in vec2 aPos;
uniform vec4 uWin;   // the window in DS pixels: x, y, w, h
uniform int uFlip;   // 1: the window's row 0 at the image's top (the SurfaceTexture), 0: at the target's row 0 (a readback)
out vec2 vDs;
void main()
{
    float fy = uFlip != 0 ? 0.5 - 0.5 * aPos.y : 0.5 + 0.5 * aPos.y;
    vDs = vec2(uWin.x + (0.5 + 0.5 * aPos.x) * uWin.z, uWin.y + fy * uWin.w);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
)";

const char* kFS = R"(#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;

uniform usampler2D uScreen;   // both screens' 2D lines, melonDS's accelerated layout: 3 x 256 + 1 words a line
uniform sampler2D u3D;        // the frame's 3D (B, G, R, A, as melonDS's renderer writes it)
uniform ivec4 u3DView;        // the screen's (0, 0) at (x, y) in it, w x h DS pixels
uniform int u3DScale;
uniform sampler2D uPlan;      // EXPAND's margins: colour, and what to do there in alpha (PK_NDS_PLAN_*)
uniform ivec4 uPlanRect;      // what it covers: x, y, w, h in DS pixels (w 0: no margins)
uniform int uBoth;            // 1: both screens, rows 0-383 (the 1x copy); 0: the top screen and its margins
uniform ivec3 uBackdrop;      // the margins' compose: the backdrop (6 bits a channel), BLDCNT, EVA, EVB, brightness
uniform ivec4 uBlend;
uniform ivec2 uFx;            // BLDCNT's brightness up / down where nothing's over the 3D: mode << 14 | EVY, its targets

in vec2 vDs;
out vec4 oColor;

ivec4 Fetch3D(vec2 p)
{
    ivec2 c = ivec2(floor((p + vec2(u3DView.xy)) * float(u3DScale)));
    if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, u3DView.zw * u3DScale))) return ivec4(0);
    return ivec4(texelFetch(u3D, c, 0).bgra * vec4(63.0, 63.0, 63.0, 31.0));
}

ivec4 Brightness(ivec4 pixel, int mode, int evy)
{
    evy = min(evy, 16);
    if (mode == 1) pixel += ((0x3F - pixel) * evy) >> 4;
    else if (mode == 2) pixel -= ((pixel * evy) + 0xF) >> 4;
    return pixel;
}

// melonDS's compositor for screen pixel [ip] (its row [rowBase] + y in uScreen), the 3D read at [p].
ivec4 Screen(ivec2 ip, vec2 p, int rowBase)
{
    ivec4 pixel = ivec4(texelFetch(uScreen, ivec2(ip.x, ip.y + rowBase), 0));
    ivec4 mbright = ivec4(texelFetch(uScreen, ivec2(768, ip.y + rowBase), 0));
    int dispmode = mbright.b & 0x3;
    // mbright.a == HOFS bit0..7, mbright.b bit7 == HOFS bit8 (sign)
    float xoff = float(mbright.a - ((mbright.b & 0x80) * 2));

    if (dispmode == 1)
    {
        ivec4 val1 = pixel;
        ivec4 val2 = ivec4(texelFetch(uScreen, ivec2(ip.x + 256, ip.y + rowBase), 0));
        ivec4 val3 = ivec4(texelFetch(uScreen, ivec2(ip.x + 512, ip.y + rowBase), 0));
        int compmode = val3.a & 0xF;
        ivec4 _3dpix = compmode <= 4 ? Fetch3D(vec2(p.x + xoff, p.y)) : ivec4(0);

        if (compmode == 4)
        {
            // 3D on top, blending
            if (_3dpix.a > 0)
            {
                int eva = (_3dpix.a & 0x1F) + 1;
                int evb = 32 - eva;
                val1 = min(((_3dpix * eva) + (val1 * evb) + 0x10) >> 5, 0x3F);
            }
            else
                val1 = val2;
        }
        else if (compmode == 1)
        {
            // 3D on bottom, blending
            if (_3dpix.a > 0)
                val1 = min(((val1 * val3.g) + (_3dpix * val3.b) + 0x8) >> 4, 0x3F);
            else
                val1 = val2;
        }
        else if (compmode <= 3)
        {
            // 3D on top, normal / fade
            if (_3dpix.a > 0)
            {
                int evy = val3.g;
                val1 = _3dpix;
                if (compmode == 2) val1 += (((0x3F - val1) * evy) + 0x8) >> 4;
                else if (compmode == 3) val1 -= ((val1 * evy) + 0x7) >> 4;
            }
            else
                val1 = val2;
        }
        pixel = val1;
    }

    if (dispmode != 0) pixel = Brightness(pixel, mbright.g >> 6, mbright.r & 0x1F);
    return pixel;
}

vec4 Out(ivec3 c)
{
    c = (c << 2) | (c >> 4);
    return vec4(vec3(c) / 255.0, 1.0);
}

// A margin pixel by EXPAND's plan: pk_nds_wide.cpp's composed() - the 3D under the 2D blended over it, else the 3D's
// alpha against the backdrop - or a battle's 3D over its background, then the master brightness.
vec4 Margin(ivec2 ip, vec2 p)
{
    ivec2 q = ip - uPlanRect.xy;
    if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, uPlanRect.zw))) return vec4(0.0, 0.0, 0.0, 1.0);
    ivec4 plan = ivec4(texelFetch(uPlan, q, 0) * 255.0 + 0.5);
    if (plan.a == 0) return vec4(vec3(plan.rgb) / 255.0, 1.0);
    if (plan.a == 3)
    {
        ivec2 s = ivec2(plan.r | (plan.b << 8), plan.g);
        return Out(Screen(s, vec2(s) + 0.5, 0).rgb);
    }
    ivec4 c3 = Fetch3D(p);
    bool has3D = c3.a > 0;
    int blendCnt = uBlend.x;
    if (plan.a == 4)
    {
        // A battle: its 3D over the background, or a sprite (PK_NDS_PLAN_UNDER_OBJ)
        ivec3 c = plan.rgb & 0x3F;
        if (has3D)
        {
            int eva = (c3.a & 0x1F) + 1;
            bool target2 = (blendCnt & ((plan.r & 0x80) != 0 ? 0x1000 : 0x800)) != 0;
            c = target2 && eva < 32 ? min((c3.rgb * eva + c * (32 - eva) + 0x10) >> 5, ivec3(0x3F)) : c3.rgb;
        }
        return Out(Brightness(ivec4(c, 0), uBlend.w >> 14, uBlend.w & 0x1F).rgb);
    }
    ivec3 under = has3D ? c3.rgb : uBackdrop;
    ivec3 c = under;
    if (plan.a == 2)
    {
        bool target2 = (blendCnt & (has3D ? 0x100 : 0x2000)) != 0;
        c = target2 ? min((plan.rgb * uBlend.y + under * uBlend.z + 8) >> 4, ivec3(0x3F)) : plan.rgb;
    }
    else if (has3D && (blendCnt & 0x2000) != 0)
    {
        int eva = (c3.a & 0x1F) + 1;
        if (eva < 32) c = min((c3.rgb * eva + uBackdrop * (32 - eva) + 0x10) >> 5, ivec3(0x3F));
    }
    if (plan.a != 2 && (uFx.y & (has3D ? 1 : 0x20)) != 0)
    {
        // BLDCNT's brightness up / down (melonDS's biases: 8 up, 7 down; the master brightness's are 0 / 15).
        int evy = min(uFx.x & 0x1F, 16);
        if ((uFx.x >> 14) == 1) c += ((0x3F - c) * evy + 8) >> 4;
        else if ((uFx.x >> 14) == 2) c -= (c * evy + 7) >> 4;
    }
    c = Brightness(ivec4(c, 0), uBlend.w >> 14, uBlend.w & 0x1F).rgb;
    return Out(c);
}

void main()
{
    ivec2 ip = ivec2(floor(vDs));
    if (uBoth != 0)
    {
        int screen = ip.y >= 192 ? 1 : 0;
        ivec2 sp = ivec2(ip.x, ip.y - 192 * screen);
        oColor = Out(Screen(sp, vec2(vDs.x, vDs.y - 192.0 * float(screen)), 192 * screen).rgb);
        return;
    }
    if (ip.x >= 0 && ip.x < 256 && ip.y >= 0 && ip.y < 192)
        oColor = Out(Screen(ip, vDs, 0).rgb);
    else
        oColor = Margin(ip, vDs);
}
)";

struct Info {
    int64_t ts = -1;
    int32_t v[6] = {};
};

struct Gl {
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLConfig cfg = nullptr;
    EGLContext ctx = EGL_NO_CONTEXT;
    EGLSurface pbuf = EGL_NO_SURFACE;
    EGLSurface winSurf = EGL_NO_SURFACE;
    ANativeWindow* win = nullptr;
    int winW = 0, winH = 0;   // the buffers' size asked for
    int supported = -1;
    int scale = 1;
    int maxTexture = 4096;
    PFNEGLPRESENTATIONTIMEANDROIDPROC presentationTime = nullptr;

    GLRenderer* renderer = nullptr;   // owned by the NDS's GPU3D
    int shownColor = 0;               // the colour texture the frame now ending shows (recorded at its VBlank)
    GLRenderer::View shownView {};

    GLuint prog = 0, vao = 0, vbo = 0;
    GLuint screenTex = 0, planTex = 0;
    int planW = 0, planH = 0;
    GLuint copyFbo = 0, copyTex = 0;
    GLuint pbo[2] = {};
    bool pboFull[2] = {};
    int pboNext = 0;
    GLint uWin = -1, uFlip = -1, u3DView = -1, u3DScale = -1, uPlanRect = -1, uBoth = -1, uBackdrop = -1, uBlend = -1, uFx = -1;

    std::mutex winLock;
    bool winPending = false;
    ANativeWindow* winNext = nullptr;

    std::mutex infoLock;
    Info info[16];
    int infoNext = 0;

    // pk_nds_gl_capture: each frame's window also composed into capFbo and read back (nds_dump, no window there)
    bool capture = false;
    GLuint capFbo = 0, capTex = 0;
    int capW = 0, capH = 0;
    std::vector<uint32_t> captured;
    int capturedW = 0, capturedH = 0;
};

Gl gl;

const char* eglErr() {
    static char buf[16];
    snprintf(buf, sizeof(buf), "0x%04X", eglGetError());
    return buf;
}

bool makeCurrent()
{
    EGLSurface s = gl.winSurf != EGL_NO_SURFACE ? gl.winSurf : gl.pbuf;
    if (eglGetCurrentContext() == gl.ctx && eglGetCurrentSurface(EGL_DRAW) == s) return true;
    if (!eglMakeCurrent(gl.dpy, s, s, gl.ctx)) {
        LOGE("eglMakeCurrent: %s", eglErr());
        return false;
    }
    return true;
}

bool hasExtension(const char* name)
{
    GLint n = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &n);
    for (GLint i = 0; i < n; i++)
        if (!strcmp((const char*) glGetStringi(GL_EXTENSIONS, (GLuint) i), name)) return true;
    return false;
}

// An OpenGL ES 3 context of our own (a 16 x 16 pbuffer until a window comes): 3.2, or 3.0 / 3.1 with
// OES / EXT_draw_buffers_indexed (melonDS's renderer masks its two colour targets apart) - once per process.
bool ensureEgl()
{
    if (gl.ctx != EGL_NO_CONTEXT) return makeCurrent();
    if (gl.supported == 0) return false;
    gl.supported = 0;
    gl.dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (gl.dpy == EGL_NO_DISPLAY || !eglInitialize(gl.dpy, nullptr, nullptr)) {
        LOGE("no EGL display: %s", eglErr());
        return false;
    }
    const EGLint attrs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLint n = 0;
    if (!eglChooseConfig(gl.dpy, attrs, &gl.cfg, 1, &n) || n < 1) {
        LOGE("no OpenGL ES 3 config: %s", eglErr());
        return false;
    }
    int minor = 2;
    for (; minor >= 0 && gl.ctx == EGL_NO_CONTEXT; minor--) {
        const EGLint ctxAttrs[] = {EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, minor, EGL_NONE};
        gl.ctx = eglCreateContext(gl.dpy, gl.cfg, EGL_NO_CONTEXT, ctxAttrs);
    }
    if (gl.ctx == EGL_NO_CONTEXT) {
        LOGI("no OpenGL ES 3 context (%s): the DS's 3D stays on the CPU", eglErr());
        return false;
    }
    const EGLint pbAttrs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    gl.pbuf = eglCreatePbufferSurface(gl.dpy, gl.cfg, pbAttrs);
    if (gl.pbuf == EGL_NO_SURFACE || !makeCurrent()) {
        LOGE("no pbuffer: %s", eglErr());
        if (gl.pbuf != EGL_NO_SURFACE) eglDestroySurface(gl.dpy, gl.pbuf);
        eglDestroyContext(gl.dpy, gl.ctx);
        gl.ctx = EGL_NO_CONTEXT;
        gl.pbuf = EGL_NO_SURFACE;
        return false;
    }
    // ES 3.2 has glColorMaski; before it, the extension's.
    const char* names[] = {"glColorMaski", "glColorMaskiOES", "glColorMaskiEXT"};
    const char* exts[] = {nullptr, "GL_OES_draw_buffers_indexed", "GL_EXT_draw_buffers_indexed"};
    for (int i = 0; i < 3 && !OpenGL::ColorMaski; i++) {
        if (i == 0 ? minor + 1 < 2 : !hasExtension(exts[i])) continue;
        OpenGL::ColorMaski = (PFNGLCOLORMASKIPROC) eglGetProcAddress(names[i]);
    }
    if (!OpenGL::ColorMaski) {
        LOGI("OpenGL ES %s without draw_buffers_indexed: the DS's 3D stays on the CPU", (const char*) glGetString(GL_VERSION));
        eglMakeCurrent(gl.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(gl.dpy, gl.pbuf);
        eglDestroyContext(gl.dpy, gl.ctx);
        gl.ctx = EGL_NO_CONTEXT;
        gl.pbuf = EGL_NO_SURFACE;
        return false;
    }
#ifdef __ANDROID__
    gl.presentationTime = (PFNEGLPRESENTATIONTIMEANDROIDPROC) eglGetProcAddress("eglPresentationTimeANDROID");
#endif
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &gl.maxTexture);
    LOGI("GPU renderer's context: %s, %s (textures up to %d)", (const char*) glGetString(GL_VERSION),
        (const char*) glGetString(GL_RENDERER), gl.maxTexture);
    gl.supported = 1;
    return true;
}

GLuint compile(GLenum type, const char* src)
{
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        LOGE("compositor shader: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

bool ensureCompositor()
{
    if (gl.prog) return true;
    GLuint vs = compile(GL_VERTEX_SHADER, kVS), fs = compile(GL_FRAGMENT_SHADER, kFS);
    if (!vs || !fs) return false;
    gl.prog = glCreateProgram();
    glAttachShader(gl.prog, vs);
    glAttachShader(gl.prog, fs);
    glBindAttribLocation(gl.prog, 0, "aPos");
    glLinkProgram(gl.prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(gl.prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(gl.prog, sizeof(log), nullptr, log);
        LOGE("compositor link: %s", log);
        glDeleteProgram(gl.prog);
        gl.prog = 0;
        return false;
    }
    glUseProgram(gl.prog);
    glUniform1i(glGetUniformLocation(gl.prog, "uScreen"), 0);
    glUniform1i(glGetUniformLocation(gl.prog, "u3D"), 1);
    glUniform1i(glGetUniformLocation(gl.prog, "uPlan"), 2);
    gl.uWin = glGetUniformLocation(gl.prog, "uWin");
    gl.uFlip = glGetUniformLocation(gl.prog, "uFlip");
    gl.u3DView = glGetUniformLocation(gl.prog, "u3DView");
    gl.u3DScale = glGetUniformLocation(gl.prog, "u3DScale");
    gl.uPlanRect = glGetUniformLocation(gl.prog, "uPlanRect");
    gl.uBoth = glGetUniformLocation(gl.prog, "uBoth");
    gl.uBackdrop = glGetUniformLocation(gl.prog, "uBackdrop");
    gl.uBlend = glGetUniformLocation(gl.prog, "uBlend");
    gl.uFx = glGetUniformLocation(gl.prog, "uFx");

    const float quad[] = {-1, -1, 1, -1, -1, 1, 1, 1};
    glGenVertexArrays(1, &gl.vao);
    glBindVertexArray(gl.vao);
    glGenBuffers(1, &gl.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, gl.vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glBindVertexArray(0);

    auto texture = [](GLuint& t) {
        glGenTextures(1, &t);
        glBindTexture(GL_TEXTURE_2D, t);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    };
    texture(gl.screenTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8UI, 256 * 3 + 1, 192 * 2, 0, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE, nullptr);
    texture(gl.planTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    gl.planW = gl.planH = 1;
    texture(gl.copyTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H * 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glGenFramebuffers(1, &gl.copyFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, gl.copyFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, gl.copyTex, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glGenBuffers(2, gl.pbo);
    for (GLuint b : gl.pbo) {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, b);
        glBufferData(GL_PIXEL_PACK_BUFFER, W * H * 2 * 4, nullptr, GL_STREAM_READ);
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    gl.pboFull[0] = gl.pboFull[1] = false;
    return true;
}

void dropCompositor()
{
    if (gl.prog) glDeleteProgram(gl.prog);
    if (gl.vao) glDeleteVertexArrays(1, &gl.vao);
    if (gl.vbo) glDeleteBuffers(1, &gl.vbo);
    GLuint tex[] = {gl.screenTex, gl.planTex, gl.copyTex};
    glDeleteTextures(3, tex);
    if (gl.copyFbo) glDeleteFramebuffers(1, &gl.copyFbo);
    glDeleteBuffers(2, gl.pbo);
    gl.prog = gl.vao = gl.vbo = gl.screenTex = gl.planTex = gl.copyTex = gl.copyFbo = 0;
    gl.pbo[0] = gl.pbo[1] = 0;
}

#ifdef __ANDROID__
void dropWindowSurface()
{
    if (gl.winSurf == EGL_NO_SURFACE) return;
    eglMakeCurrent(gl.dpy, gl.pbuf, gl.pbuf, gl.ctx);
    eglDestroySurface(gl.dpy, gl.winSurf);
    gl.winSurf = EGL_NO_SURFACE;
}

// A window handed over by pk_nds_gl_set_window: the old one's surface goes, the new one's comes.
void takeWindow()
{
    ANativeWindow* next;
    {
        std::lock_guard<std::mutex> l(gl.winLock);
        if (!gl.winPending) return;
        gl.winPending = false;
        next = gl.winNext;
        gl.winNext = nullptr;
    }
    dropWindowSurface();
    if (gl.win) ANativeWindow_release(gl.win);
    gl.win = next;
    if (!gl.win || gl.ctx == EGL_NO_CONTEXT) return;
    gl.winW = W * gl.scale;
    gl.winH = H * gl.scale;
    ANativeWindow_setBuffersGeometry(gl.win, gl.winW, gl.winH, WINDOW_FORMAT_RGBA_8888);
    gl.winSurf = eglCreateWindowSurface(gl.dpy, gl.cfg, gl.win, nullptr);
    if (gl.winSurf == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface: %s", eglErr());
        return;
    }
    makeCurrent();
    eglSwapInterval(gl.dpy, 0);   // never wait on the window: the SurfaceTexture keeps the newest frame
}
#else
void dropWindowSurface() {}
void takeWindow() {}
#endif

void onBlit(const GPU&, GLRenderer& renderer, void*)
{
    // At this frame's VBlank: its 3D is the colour texture drawn last (the next frame's is drawn at line 215).
    gl.shownColor = renderer.GetCurrentColor();
    gl.shownView = renderer.GetView(gl.shownColor);
}

int64_t nowNanos()
{
    timespec t {};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t) t.tv_sec * 1000000000LL + t.tv_nsec;
}

// Draws the compositor over [x, y, w, h] (DS pixels) into the bound framebuffer's viewport.
void compose(float x, float y, float w, float h, bool flip, bool both)
{
    glUniform4f(gl.uWin, x, y, w, h);
    glUniform1i(gl.uFlip, flip ? 1 : 0);
    glUniform1i(gl.uBoth, both ? 1 : 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

} // namespace

int pk_nds_gl_set_scale(NDS* nds, int scale)
{
    scale = std::clamp(scale, 1, 8);
    if (!nds) return 1;
    if (scale < 2) {
        if (gl.renderer) {
            makeCurrent();
            nds->GPU.SetRenderer3D(std::make_unique<SoftRenderer>());
            static_cast<SoftRenderer&>(nds->GPU.GetRenderer3D()).SetThreaded(true, nds->GPU);   // as pk_nds_init
            gl.renderer = nullptr;
            pk_nds_wide_gpu(false, 1, gl.maxTexture);
            LOGI("DS 3D back on the CPU");
        }
        gl.scale = 1;
        return 1;
    }
    if (!ensureEgl() || !ensureCompositor()) return pk_nds_gl_set_scale(nds, 1);
    if (!gl.renderer) {
        std::unique_ptr<GLRenderer> r = GLRenderer::New();
        if (!r) {
            LOGE("melonDS's OpenGL renderer didn't start: the DS's 3D stays on the CPU");
            gl.supported = 0;
            return pk_nds_gl_set_scale(nds, 1);
        }
        r->SetRenderSettings(false, scale);
        r->BlitHook = onBlit;
        r->Wide = pk_nds_wide_gpu_list();
        gl.renderer = r.get();
        nds->GPU.SetRenderer3D(std::move(r));
        LOGI("DS 3D on the GPU at %dx", scale);
    } else {
        gl.renderer->SetRenderSettings(false, scale);
    }
    gl.scale = scale;
    gl.shownColor = 0;
    gl.shownView = GLRenderer::View();
    gl.pboFull[0] = gl.pboFull[1] = false;
    pk_nds_wide_gpu(true, scale, gl.maxTexture);
    return scale;
}

int pk_nds_gl_scale() { return gl.renderer ? gl.scale : 1; }

int pk_nds_gl_supported() { return gl.supported; }

void pk_nds_gl_end(NDS* nds)
{
    if (gl.ctx == EGL_NO_CONTEXT) return;
    bool current = makeCurrent();
    if (nds && gl.renderer) pk_nds_gl_set_scale(nds, 1);
    if (current) dropCompositor();
    gl.prog = 0;
    dropWindowSurface();
#ifdef __ANDROID__
    if (gl.win) {
        ANativeWindow_release(gl.win);
        gl.win = nullptr;
    }
#endif
    if (current && gl.capFbo) {
        glDeleteFramebuffers(1, &gl.capFbo);
        glDeleteTextures(1, &gl.capTex);
    }
    gl.capFbo = gl.capTex = 0;
    gl.capW = gl.capH = 0;
    eglMakeCurrent(gl.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(gl.dpy, gl.pbuf);
    eglDestroyContext(gl.dpy, gl.ctx);
    gl.pbuf = EGL_NO_SURFACE;
    gl.ctx = EGL_NO_CONTEXT;
    gl.scale = 1;
    gl.supported = -1;   // a new game tries again (on this thread: the next emu thread may be another)
    // A window handed over meanwhile stays pending for the next game.
}

void pk_nds_gl_begin_frame()
{
    if (!gl.renderer) return;
    takeWindow();
    makeCurrent();
}

void pk_nds_gl_set_window(ANativeWindow* window)
{
#ifdef __ANDROID__
    std::lock_guard<std::mutex> l(gl.winLock);
    if (gl.winNext) ANativeWindow_release(gl.winNext);
    gl.winNext = window;
    gl.winPending = true;
#endif
}

void pk_nds_gl_capture(bool on) { gl.capture = on; }

const uint32_t* pk_nds_gl_captured(int* w, int* h)
{
    *w = gl.capturedW;
    *h = gl.capturedH;
    return gl.captured.empty() ? nullptr : gl.captured.data();
}

bool pk_nds_gl_frame(NDS* nds, int x, int y, int w, int h, uint32_t* top, uint32_t* bottom)
{
    if (!gl.renderer || !gl.prog) return false;
    GPU& gpu = nds->GPU;
    int fb = gpu.FrontBuffer;
    if (!gpu.Framebuffer[fb][0] || !gpu.Framebuffer[fb][1]) return false;

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glColorMaski(0, GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glUseProgram(gl.prog);
    glBindVertexArray(gl.vao);

    // The frame's 2D lines, both screens.
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, gl.screenTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 256 * 3 + 1, 192, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE, gpu.Framebuffer[fb][0].get());
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 192, 256 * 3 + 1, 192, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE, gpu.Framebuffer[fb][1].get());
    // Its 3D.
    const GLRenderer::View& v = gl.shownView;
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, gl.renderer->GetColorTexture(gl.shownColor));
    glUniform4i(gl.u3DView, v.OffX, v.OffY, v.ViewW, v.ViewH);
    glUniform1i(gl.u3DScale, v.Scale);
    // EXPAND's margins.
    PkNdsWidePlan plan;
    pk_nds_wide_plan(&plan);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, gl.planTex);
    int px0 = std::max(plan.x0, x), py0 = std::max(plan.y0, y);
    int px1 = std::min(plan.x1, x + w), py1 = std::min(plan.y1, y + h);
    if (plan.valid && px1 > px0 && py1 > py0) {
        int pw = px1 - px0, ph = py1 - py0;
        if (pw > gl.planW || ph > gl.planH) {
            gl.planW = std::max(pw, gl.planW);
            gl.planH = std::max(ph, gl.planH);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, gl.planW, gl.planH, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        }
        glPixelStorei(GL_UNPACK_ROW_LENGTH, plan.stride);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, pw, ph, GL_RGBA, GL_UNSIGNED_BYTE,
            plan.px + (ptrdiff_t) py0 * plan.stride + px0);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glUniform4i(gl.uPlanRect, px0, py0, pw, ph);
        glUniform3i(gl.uBackdrop, (GLint) (plan.backdrop & 0x3F), (GLint) ((plan.backdrop >> 8) & 0x3F),
            (GLint) ((plan.backdrop >> 16) & 0x3F));
        glUniform4i(gl.uBlend, (GLint) plan.blendCnt, (GLint) plan.eva, (GLint) plan.evb, (GLint) plan.brightness);
        glUniform2i(gl.uFx, (GLint) plan.fx, (GLint) plan.fxTargets);
    } else {
        glUniform4i(gl.uPlanRect, 0, 0, 0, 0);
    }

    // Both screens at 1x, read back while the next frame is made; the last one's copy into [top] / [bottom].
    glBindFramebuffer(GL_FRAMEBUFFER, gl.copyFbo);
    glViewport(0, 0, W, H * 2);
    compose(0, 0, W, H * 2, false, true);
    int prev = gl.pboNext ^ 1;
    glBindBuffer(GL_PIXEL_PACK_BUFFER, gl.pbo[gl.pboNext]);
    glReadPixels(0, 0, W, H * 2, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    gl.pboFull[gl.pboNext] = true;
    if (gl.pboFull[prev]) {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, gl.pbo[prev]);
        auto* px = (const uint32_t*) glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, W * H * 2 * 4, GL_MAP_READ_BIT);
        if (px) {
            if (top) memcpy(top, px, W * H * 4);
            if (bottom) memcpy(bottom, px + W * H, W * H * 4);
        }
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    gl.pboNext = prev;

    // The screen's window at the 3D's scale (lower where it would be too big) into the SurfaceTexture.
    bool presented = false;
    int s = std::max(1, v.Scale);
    while (s > 1 && ((long long) w * h * s * s > OUT_BUDGET || w * s > gl.maxTexture || h * s > gl.maxTexture)) s--;
    int ow = w * s, oh = h * s;
    if (gl.capture && w > 0 && h > 0) {
        if (!gl.capFbo || gl.capW != ow || gl.capH != oh) {
            if (!gl.capFbo) {
                glGenFramebuffers(1, &gl.capFbo);
                glGenTextures(1, &gl.capTex);
            }
            glBindTexture(GL_TEXTURE_2D, gl.capTex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, ow, oh, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            glBindFramebuffer(GL_FRAMEBUFFER, gl.capFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, gl.capTex, 0);
            gl.capW = ow;
            gl.capH = oh;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, gl.capFbo);
        glViewport(0, 0, ow, oh);
        compose((float) x, (float) y, (float) w, (float) h, false, false);
        gl.captured.resize((size_t) ow * oh);
        glReadPixels(0, 0, ow, oh, GL_RGBA, GL_UNSIGNED_BYTE, gl.captured.data());
        gl.capturedW = ow;
        gl.capturedH = oh;
    }
#ifdef __ANDROID__
    if (gl.winSurf != EGL_NO_SURFACE && w > 0 && h > 0) {
        // A new size takes from the next buffer the window hands out: this frame may still come at the last size,
        // which the frame's info says (the view scales either to the window's place).
        if (gl.winW != ow || gl.winH != oh) {
            gl.winW = ow;
            gl.winH = oh;
            ANativeWindow_setBuffersGeometry(gl.win, ow, oh, WINDOW_FORMAT_RGBA_8888);
        }
        EGLint sw = 0, sh = 0;
        eglQuerySurface(gl.dpy, gl.winSurf, EGL_WIDTH, &sw);
        eglQuerySurface(gl.dpy, gl.winSurf, EGL_HEIGHT, &sh);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, sw, sh);
        compose((float) x, (float) y, (float) w, (float) h, true, false);
        int64_t ts = nowNanos();
        if (gl.presentationTime) gl.presentationTime(gl.dpy, gl.winSurf, ts);
        {
            std::lock_guard<std::mutex> l(gl.infoLock);
            Info& in = gl.info[gl.infoNext];
            gl.infoNext = (gl.infoNext + 1) % 16;
            in.ts = ts;
            in.v[0] = x; in.v[1] = y; in.v[2] = w; in.v[3] = h; in.v[4] = sw; in.v[5] = sh;
        }
        presented = eglSwapBuffers(gl.dpy, gl.winSurf);
        if (!presented) {
            // The SurfaceTexture went (the view's context was replaced): its successor comes through set_window.
            LOGI("eglSwapBuffers: %s - window dropped", eglErr());
            dropWindowSurface();
        }
    }
#endif
    glBindVertexArray(0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glActiveTexture(GL_TEXTURE0);
    return presented;
}

bool pk_nds_gl_frame_info(int64_t timestamp, int32_t out[6])
{
    std::lock_guard<std::mutex> l(gl.infoLock);
    for (const Info& in : gl.info) {
        if (in.ts != timestamp) continue;
        memcpy(out, in.v, sizeof(in.v));
        return true;
    }
    // Not one of ours (a driver that doesn't pass presentation times on): the newest frame's, close enough.
    const Info& newest = gl.info[(gl.infoNext + 15) % 16];
    if (newest.ts < 0) return false;
    memcpy(out, newest.v, sizeof(newest.v));
    return true;
}
