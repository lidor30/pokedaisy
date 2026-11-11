// See pk_nds_wide.h. The polygon set-up below is GPU3D::SubmitPolygon's own (melonDS 1.1), from clipping on, writing
// into the slices' lists instead of the game's: keep it in step with that function when the submodule moves.

#include "pk_nds_wide.h"

#include <NDS.h>
#include <GPU.h>
#include <GPU3D.h>
#include <GPU3D_Soft.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <array>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

using namespace melonDS;

namespace {

constexpr int MAX_POLYS = 2048;
constexpr int MAX_VERTS = 8192;   // a clipped polygon has up to 10 vertices; no strip sharing here
constexpr int W3D = 256;
constexpr int H3D = 192;
// The slices overlap the screen by this much: a polygon cut at a slice's own edge gets that edge outlined (edge
// marking, antialiasing), so the margin is taken from inside the slice, never its border.
constexpr int OVERLAP = 4;
// Columns / rows drawn past the outermost one shown: edge marking compares a pixel with its neighbours, and an
// undrawn neighbour outlined the last one.
constexpr int SPARE = 2;
constexpr int MX = PK_NDS_WIDE_MAX_MARGIN_X, MY = PK_NDS_WIDE_MAX_MARGIN_Y;
constexpr int OW = PK_NDS_WIDE_MAX_W, OH = PK_NDS_WIDE_MAX_H;
// A battle sprite's edge row goes on into the margin only where this many pixels in from the screen's edge are one colour,
// and only a sprite that has kept its place across the screen EDGE_STEADY frames (a box that has slid in; not one sliding).
constexpr int EDGE_BAND = 5;
constexpr int EDGE_STEADY = 8;
constexpr u32 NONE = 0xFFFFFFFFu;   // not drawn (a 3D colour's alpha is 5 bits: never this)
// Threads drawing the slices: the slices are independent, and the 3x zoom step's (eight of them, ~4.7 screens) took
// ~6 ms a frame on one thread on an M-series Mac, more on a handheld. They draw while the next frame runs (drawSlices).
constexpr int HELPERS = 4;
// GPU mode (pk_nds_wide_gpu): the whole view's list, at most these (GLRenderer's buffers hold 4096 / 24576).
constexpr u32 GPU_MAX_POLYS = 4096;
constexpr u32 GPU_MAX_VERTS = 20000;
// GPU mode: texels the view's 3D may take (one and three quarter 8x screens: a 16:9 window at 1x and its pad, at 8x),
// so a far zoom-out draws at a lower scale - it shows the DS pixels smaller anyway.
constexpr s64 GPU_BUDGET = 256 * 8 * 192 * 8 * 7 / 4;
// GPU mode: DS pixels the list takes past what the window needs, each way. It's drawn a frame after it's built, and
// only the part the window needs then (WidePolygonList's crop): a window moving out by up to this much a frame has
// the 3D there.
constexpr int GPU_HEADROOM = 32;
// ... and the part drawn, this much past what the window needs each way: a window that starts moving has no step yet to
// be drawn ahead for (pk_nds_wide_need), its first one shows this much more.
constexpr int GPU_PAD = 6;

// A slice's picture as drawn: 256 x 192 in the 3D engine's colours (RGB6 + alpha5), the rectangle drawn in the
// screen's pixels.
struct Image {
    std::vector<u32> px;
    int x0 = 0, x1 = 0, y0 = 0, y1 = 0;
    bool valid = false;
};

struct Slice {
    int sx = 0, sy = 0;   // -1 / 0 / 1: left / screen / right, above / screen / below
    bool active = false;  // gets polygons and is drawn (the window reaches it)
    std::vector<Vertex> verts[2];
    std::vector<Polygon> polys[2];
    int bank = 0;
    u32 numVerts = 0, numPolys = 0, numOpaque = 0;
    std::array<Polygon*, MAX_POLYS> render {};
    u32 renderNum = 0;
    int x0 = 0, x1 = 0, y0 = 0, y1 = 0;   // what the drawing under way draws (sliceRect when it was handed out)
    Image image;   // drawn after the last frame: what the next composed frame shows
    Image shown;   // what the last composed frame used (pk_nds_wide_check)
    int lastCount = 0;
};

// A renderer of its own and its scratch: one per helper thread.
struct Drawer {
    std::unique_ptr<SoftRenderer> renderer;
    std::vector<Polygon*> visible;   // a slice's polygons that reach what it draws
};

// The helper threads: each frame's slices go into [jobs] and every helper takes the next one until none are left, while
// the next frame runs (waitSlices says when they must be done).
struct Pool {
    std::vector<std::thread> threads;
    std::mutex lock;
    std::condition_variable wake, done;
    u64 generation = 0;
    bool quit = false;
    int busy = 0;
    std::vector<Slice*> jobs;
    std::atomic<int> next {0};
    bool pending = false;   // handed out, not waited for yet
};

struct Wide {
    NDS* nds = nullptr;
    bool on = false;
    Slice slices[9];      // (sy + 1) * 3 + sx + 1; 4, the screen itself, unused
    Drawer drawers[HELPERS];
    Pool pool;
    std::vector<u32> out;   // OW x OH, the game frame's (0, 0) at (MX, MY)
    int cx0 = 0, cx1 = 0, cy0 = 0, cy1 = 0;   // what the last composed frame covers, in the game frame's pixels
    // The sprites last frame, by what they are (tiles, palette, shape, size, x - not y, nor their OAM slot, which the
    // game reshuffles as sprites come and go), and the frames each has held: only a sprite that has stopped (a box
    // that slid in) carries its edge, not one sliding in.
    std::vector<std::pair<u64, u8>> objHeld, objHeldNext;
    // Each row's edge pixel, left / right, last frame, and the frames it has held (give or take a row: a box bobbing a
    // pixel still holds) - a sprite that stays put while its picture changes (the trainer throwing) doesn't.
    u32 edgeSeen[2][192] = {};
    u8 edgeSteady[2][192] = {};
    // Frames run since the last drawn one: what's held counts game frames, so fast-forward (one frame drawn in a few)
    // carries a box on at the same frame as 1x does.
    int undrawn = 0;
    int needL = 0, needR = 0, needT = 0, needB = 0;
    int askL = 0, askR = 0, askT = 0, askB = 0;   // what the window asked for last (pk_nds_wide_need)
    // GPU mode: the GPU renderer draws the 3D, and EXPAND's is one list of the whole view - its margins and the screen
    // between them, one picture - built from the same submissions as the slices', handed to it as gpuList. wide.out
    // then holds the margins' plan for the compositor (pk_nds_wide_plan), not their colours.
    bool gpu = false;
    int gpuScale = 1, gpuMaxTexture = 4096;
    bool gpuWant = false;        // the host: the margins show the 3D (the overworld)
    bool gpuBuilding = false;    // this flush's submissions go into the list
    bool gpuComplete = true;     // ... all of them (one it couldn't take: the game's own list is drawn instead)
    int gpuL = 0, gpuR = 0, gpuT = 0, gpuB = 0;   // the margins it's built for
    std::vector<Vertex> gpuVerts[2];
    std::vector<Polygon> gpuPolys[2];
    int gpuBank = 0;
    u32 gpuNumVerts = 0, gpuNumPolys = 0, gpuNumOpaque = 0;
    std::vector<Polygon*> gpuRender;
    WidePolygonList gpuList;
    PkNdsWidePlan plan;
};

Wide wide;

// Engine A as it drew the frame on screen (GPU::VisibleEndHook, at line 192): the game's VBlank handler goes on to set
// the next frame's fade, scroll, palettes and sprites before the margins are made, which ran them a frame ahead of the
// screen (a battle's fade-in a step brighter beside it, sprites a step on). Its tiles and maps are read live.
struct Shown2D {
    u32 DispCnt = 0;
    u16 BGCnt[4] = {}, BGXPos[4] = {}, BGYPos[4] = {};
    u8 WinCnt[4] = {};
    u16 BlendCnt = 0;
    u8 EVA = 0, EVB = 0, EVY = 0;
    u16 MasterBrightness = 0;
    u8 Palette[0x400] = {};   // engine A's BG and OBJ palettes
    u8 OAM[0x400] = {};
    bool valid = false;
};
Shown2D shown2D;

void takeShown2D(Shown2D& d, const GPU& gpu)
{
    const GPU2D::Unit& u = gpu.GPU2D_A;
    d.DispCnt = u.DispCnt;
    for (int i = 0; i < 4; i++) {
        d.BGCnt[i] = u.BGCnt[i];
        d.BGXPos[i] = u.BGXPos[i];
        d.BGYPos[i] = u.BGYPos[i];
        d.WinCnt[i] = u.WinCnt[i];
    }
    d.BlendCnt = u.BlendCnt;
    d.EVA = u.EVA;
    d.EVB = u.EVB;
    d.EVY = u.EVY;
    d.MasterBrightness = u.MasterBrightness;
    std::memcpy(d.Palette, gpu.Palette, sizeof(d.Palette));
    std::memcpy(d.OAM, gpu.OAM, sizeof(d.OAM));
    d.valid = true;
}

void waitSlices();

// Line 192: VBlank is next, which latches the 3D registers the slices still being drawn read (waitSlices).
void onVisibleEnd(GPU& gpu, void*)
{
    waitSlices();
    takeShown2D(shown2D, gpu);
}

// What the frame on screen was drawn with: the snapshot, or (no frame run since the hook went in) the live state.
const Shown2D& shown(const NDS* nds)
{
    if (!shown2D.valid) takeShown2D(shown2D, nds->GPU);
    return shown2D;
}

inline Slice& sliceAt(int sx, int sy) { return wide.slices[(sy + 1) * 3 + sx + 1]; }

// Where a polygon goes: a slice's lists, or GPU mode's.
struct Store {
    Vertex* verts;
    Polygon* polys;
    u32& numVerts;
    u32& numPolys;
    u32& numOpaque;
    int screenH;     // the picture's height: a polygon reaching below it is degenerate
    bool wideKeys;   // sort keys for a picture taller than 255 rows
};

void storePolygon(GPU3D& g, const Store& st, Vertex* cv, int nverts, bool facingview, int polytype);
void updateCrop();

void addPolygon(GPU3D& g, Slice& s, Vertex* cv, int nverts, bool facingview, int polytype)
{
    if (s.numPolys >= MAX_POLYS || s.numVerts + (u32) nverts > MAX_VERTS) return;

    // compute screen coordinates
    for (int i = 0; i < nverts; i++) {
        Vertex* vtx = &cv[i];
        vtx->Position[3] &= 0x00FFFFFF;
        u32 posX, posY;
        u32 w = vtx->Position[3];
        if (w == 0) {
            posX = 0;
            posY = 0;
        } else {
            posX = vtx->Position[0] + w;
            posY = -vtx->Position[1] + w;
            u32 den = w;
            if (w > 0xFFFF) {
                posX >>= 1;
                posY >>= 1;
                den >>= 1;
            }
            den <<= 1;
            posX = ((posX * g.Viewport[4]) / den) + g.Viewport[0];
            posY = ((posY * g.Viewport[5]) / den) + g.Viewport[3];
        }
        vtx->FinalPosition[0] = posX & 0x1FF;
        vtx->FinalPosition[1] = posY & 0xFF;
        if (w != 0) {
            posX = ((((s64)(vtx->Position[0] + w) * g.Viewport[4]) << 4) / (((s64) w) << 1)) + (g.Viewport[0] << 4);
            posY = ((((s64)(-vtx->Position[1] + w) * g.Viewport[5]) << 4) / (((s64) w) << 1)) + (g.Viewport[3] << 4);
            vtx->HiresPosition[0] = posX & 0x1FFF;
            vtx->HiresPosition[1] = posY & 0xFFF;
        }
    }
    storePolygon(g, Store {s.verts[s.bank].data(), s.polys[s.bank].data(), s.numVerts, s.numPolys, s.numOpaque, H3D, false},
        cv, nverts, facingview, polytype);
}

// The rest of SubmitPolygon from its screen coordinates on: the zero-dot check, the polygon's attributes, its
// vertices' colours, bounds, Z and W.
void storePolygon(GPU3D& g, const Store& st, Vertex* cv, int nverts, bool facingview, int polytype)
{
    // zero-dot W check
    if (!(g.CurPolygonAttr & (1 << 13))) {
        bool zerodot = true, allbehind = true;
        for (int i = 0; i < nverts; i++) {
            Vertex* vtx = &cv[i];
            if (vtx->FinalPosition[0] != cv[0].FinalPosition[0] || vtx->FinalPosition[1] != cv[0].FinalPosition[1]) {
                zerodot = false;
                break;
            }
            if (vtx->Position[3] <= (s32) g.ZeroDotWLimit) {
                allbehind = false;
                break;
            }
        }
        if (zerodot && allbehind) return;
    }

    Polygon* poly = &st.polys[st.numPolys++];
    poly->NumVertices = 0;
    poly->Attr = g.CurPolygonAttr;
    poly->TexParam = g.TexParam;
    poly->TexPalette = g.TexPalette;
    poly->Degenerate = false;
    poly->FacingView = facingview;
    u32 texfmt = (g.TexParam >> 26) & 0x7;
    u32 polyalpha = (g.CurPolygonAttr >> 16) & 0x1F;
    poly->Translucent = (texfmt == 1 || texfmt == 6) || (polyalpha > 0 && polyalpha < 31);
    poly->IsShadowMask = ((g.CurPolygonAttr & 0x3F000030) == 0x00000030);
    poly->IsShadow = ((g.CurPolygonAttr & 0x30) == 0x30) && !poly->IsShadowMask;
    if (!poly->Translucent) st.numOpaque++;
    poly->Type = polytype;

    for (int i = 0; i < nverts; i++) {
        Vertex* vtx = &st.verts[st.numVerts++];
        *vtx = cv[i];
        poly->Vertices[i] = vtx;
        poly->NumVertices++;
        for (int c = 0; c < 3; c++) {
            vtx->FinalColor[c] = vtx->Color[c] >> 12;
            if (vtx->FinalColor[c]) vtx->FinalColor[c] = ((vtx->FinalColor[c] << 4) + 0xF);
        }
    }

    // bounds, W shift and normalisation
    u32 vtop = 0, vbot = 0;
    s32 ytop = st.screenH, ybot = 0;
    s32 xtop = 256, xbot = 0;
    u32 wsize = 0;
    for (int i = 0; i < nverts; i++) {
        Vertex* vtx = poly->Vertices[i];
        if (vtx->FinalPosition[1] < ytop) {
            xtop = vtx->FinalPosition[0];
            ytop = vtx->FinalPosition[1];
            vtop = i;
        }
        if (vtx->FinalPosition[1] > ybot || (vtx->FinalPosition[1] == ybot && vtx->FinalPosition[0] > xbot)) {
            xbot = vtx->FinalPosition[0];
            ybot = vtx->FinalPosition[1];
            vbot = i;
        }
        u32 w = (u32) vtx->Position[3];
        if (w == 0) poly->Degenerate = true;
        while ((w >> wsize) && (wsize < 32)) wsize += 4;
    }
    poly->VTop = vtop; poly->VBottom = vbot;
    poly->YTop = ytop; poly->YBottom = ybot;
    poly->XTop = xtop; poly->XBottom = xbot;
    if (ybot > st.screenH) poly->Degenerate = true;
    if (st.wideKeys) {
        poly->SortKey = (ybot << 10) | ytop;
        if (poly->Translucent) poly->SortKey |= 0x100000;
    } else {
        poly->SortKey = (ybot << 8) | ytop;
        if (poly->Translucent) poly->SortKey |= 0x10000;
    }
    poly->WBuffer = (g.FlushAttributes & 0x2);

    for (int i = 0; i < nverts; i++) {
        Vertex* vtx = poly->Vertices[i];
        s32 w, wshifted;
        if (wsize < 16) {
            w = vtx->Position[3] << (16 - wsize);
            wshifted = w >> (16 - wsize);
        } else {
            w = vtx->Position[3] >> (wsize - 16);
            wshifted = w << (wsize - 16);
        }
        s32 z;
        if (g.FlushAttributes & 0x2)
            z = wshifted;
        else if (vtx->Position[3])
            z = ((((s64) vtx->Position[2] * 0x4000) / vtx->Position[3]) + 0x3FFF) * 0x200;
        else
            z = 0x7FFE00;
        if (z < 0) z = 0;
        else if (z > 0xFFFFFF) z = 0xFFFFFF;
        poly->FinalZ[i] = z;
        poly->FinalW[i] = w;
    }
}

// GPU mode: the polygon into the whole view's list. The view - L / R / T / B pixels past the screen's sides - is
// mapped onto clip space as the box test's retry maps it (x' = (256 x - (R - L) w) / (256 + L + R), y the same), clipped
// once, and placed in the view's own pixels (no 9-bit wrap: the view is wider than the DS's 512).
void submitGpu(GPU3D& g, const Vertex* in, int nverts, bool facingview, int polytype)
{
    if (!wide.gpuBuilding || !wide.gpuComplete) return;
    // A viewport that isn't the whole screen (a picture in a corner): the list can't hold it - the game's own is drawn.
    if (g.Viewport[0] != 0 || g.Viewport[4] != 256 || g.Viewport[3] != 0 || g.Viewport[5] != 192) {
        wide.gpuComplete = false;
        return;
    }
    s64 l = wide.gpuL, r = wide.gpuR, t = wide.gpuT, b = wide.gpuB;
    s64 vw = W3D + l + r, vh = H3D + t + b;
    Vertex cv[10];
    bool allL = true, allR = true, allT = true, allB = true;
    for (int i = 0; i < nverts; i++) {
        cv[i] = in[i];
        s64 x = cv[i].Position[0], y = cv[i].Position[1], w = cv[i].Position[3];
        x = (W3D * x - (r - l) * w) / vw;
        y = (H3D * y - (t - b) * w) / vh;
        cv[i].Position[0] = (s32) x;
        cv[i].Position[1] = (s32) y;
        if (w <= 0) {
            allL = allR = allT = allB = false;
        } else {
            allL &= x < -w;
            allR &= x > w;
            allB &= y < -w;
            allT &= y > w;
        }
    }
    if (allL || allR || allT || allB) return;
    int n = g.ClipPolygonForHook(cv, nverts);
    if (n <= 0) return;
    if (wide.gpuNumPolys >= GPU_MAX_POLYS || wide.gpuNumVerts + (u32) n > GPU_MAX_VERTS) {
        wide.gpuComplete = false;
        return;
    }
    for (int i = 0; i < n; i++) {
        Vertex* vtx = &cv[i];
        vtx->Position[3] &= 0x00FFFFFF;
        s64 w = vtx->Position[3];
        if (w == 0) {
            vtx->HiresPosition[0] = vtx->HiresPosition[1] = 0;
        } else {
            vtx->HiresPosition[0] = (s32) ((((s64) vtx->Position[0] + w) * vw * 16) / (2 * w));
            vtx->HiresPosition[1] = (s32) ((((s64) -vtx->Position[1] + w) * vh * 16) / (2 * w));
        }
        vtx->FinalPosition[0] = vtx->HiresPosition[0] >> 4;
        vtx->FinalPosition[1] = vtx->HiresPosition[1] >> 4;
    }
    storePolygon(g, Store {wide.gpuVerts[wide.gpuBank].data(), wide.gpuPolys[wide.gpuBank].data(), wide.gpuNumVerts,
        wide.gpuNumPolys, wide.gpuNumOpaque, (int) vh, true}, cv, n, facingview, polytype);
}

void onSubmit(GPU3D& g, const Vertex* in, int nverts, bool facingview, int polytype, void*)
{
    if (wide.gpu) {
        submitGpu(g, in, nverts, facingview, polytype);
        return;
    }
    // Only a full-screen viewport continues past the screen's edges.
    if (g.Viewport[0] != 0 || g.Viewport[4] != 256 || g.Viewport[3] != 0 || g.Viewport[5] != 192) return;
    // The slice left of the screen is clip-space x in [-3w, -w]: moved by +2w it's what [-w, w] was. Moved by a little
    // less (OVERLAP pixels: 2w * (256 - OVERLAP) / 256), the slice's own edge sits inside the screen. Above / below
    // the same in y (clip-space y runs up the screen).
    s64 shiftX[10], shiftY[10];
    for (int i = 0; i < nverts; i++) {
        shiftX[i] = (s64) in[i].Position[3] * (W3D - OVERLAP) / (W3D / 2);
        shiftY[i] = (s64) in[i].Position[3] * (H3D - OVERLAP) / (H3D / 2);
    }
    for (Slice& s : wide.slices) {
        if (!s.active) continue;
        Vertex cv[10];
        bool fits = true;
        // Wholly past one of the slice's sides (every vertex in front of the eye): nothing to clip.
        bool allL = true, allR = true, allT = true, allB = true;
        for (int i = 0; i < nverts; i++) {
            cv[i] = in[i];
            s64 w = cv[i].Position[3];
            s64 x = (s64) cv[i].Position[0] - s.sx * shiftX[i];
            s64 y = (s64) cv[i].Position[1] + s.sy * shiftY[i];
            if (x > INT32_MAX || x < INT32_MIN || y > INT32_MAX || y < INT32_MIN) fits = false;
            cv[i].Position[0] = (s32) x;
            cv[i].Position[1] = (s32) y;
            if (w <= 0) {
                allL = allR = allT = allB = false;
            } else {
                allL &= x < -w;
                allR &= x > w;
                allB &= y < -w;
                allT &= y > w;
            }
        }
        if (!fits || allL || allR || allT || allB) continue;
        int n = g.ClipPolygonForHook(cv, nverts);
        if (n > 0) addPolygon(g, s, cv, n, facingview, polytype);
    }
}

// A box test that missed the screen (Platinum culls its buildings, trees and signs with them): its corners mapped from
// the window's wider view onto clip space, so the test asks "in the window?" - the game then draws the model, the screen
// clips it away as before and the slices get it. x in [-(1 + 2L/256) w, (1 + 2R/256) w] becomes [-w, w]: x' = (256 x -
// (R - L) w) / (256 + L + R); y the same (clip-space y runs up: above is +y).
bool onBoxTest(GPU3D& g, Vertex* cube, void*)
{
    if (g.Viewport[0] != 0 || g.Viewport[4] != 256 || g.Viewport[3] != 0 || g.Viewport[5] != 192) return false;
    s64 l = wide.needL, r = wide.needR, t = wide.needT, b = wide.needB;
    if (wide.gpu) {
        // The list's own view (headroom included): a model culled there would pop in as the window moves.
        if (!wide.gpuBuilding) return false;
        l = wide.gpuL; r = wide.gpuR; t = wide.gpuT; b = wide.gpuB;
    }
    if (!(l | r | t | b)) return false;
    // A little more each way: a model's box can be a pixel short of what it draws.
    l += 4; r += 4; t += 4; b += 4;
    for (int i = 0; i < 8; i++) {
        s64 x = cube[i].Position[0], y = cube[i].Position[1], w = cube[i].Position[3];
        cube[i].Position[0] = (s32) ((W3D * x - (r - l) * w) / (W3D + l + r));
        cube[i].Position[1] = (s32) ((H3D * y - (t - b) * w) / (H3D + t + b));
    }
    return true;
}

// GPU mode: the scale the view's 3D is drawn at - the one asked for, less where the part drawn ([cw] x [ch]) would take
// more than the budget or the whole view ([vw] x [vh], the viewport) a side longer than the GPU's textures.
int gpuViewScale(int cw, int ch, int vw, int vh)
{
    int s = std::max(1, wide.gpuScale);
    while (s > 1 && ((s64) cw * ch * s * s > GPU_BUDGET || vw * s > wide.gpuMaxTexture || vh * s > wide.gpuMaxTexture)) s--;
    return s;
}

// GPU mode: the part of the list's view drawn - what the window needs now, SPARE and GPU_PAD more, within what the list
// was built for - and its scale. Before the list is drawn (line 215): at the swap, and as the window changes between
// frames.
void updateCrop()
{
    WidePolygonList& list = wide.gpuList;
    if (!list.Active) return;
    auto side = [](int need, int built) { return std::min(built, need + SPARE + GPU_PAD); };
    int l = side(wide.needL, list.OffX), r = side(wide.needR, list.ViewW - W3D - list.OffX);
    int t = side(wide.needT, list.OffY), b = side(wide.needB, list.ViewH - H3D - list.OffY);
    list.CropX = list.OffX - l;
    list.CropY = list.OffY - t;
    list.CropW = W3D + l + r;
    list.CropH = H3D + t + b;
    list.Scale = gpuViewScale(list.CropW, list.CropH, list.ViewW, list.ViewH);
}

// GPU mode's swap: the list built over this flush becomes what the renderer draws (if it holds every polygon), and the
// next flush's is set up for the margins the window needs now.
void swapGpu(GPU3D& g, bool rendering)
{
    if (rendering) {
        WidePolygonList& list = wide.gpuList;
        list.Active = wide.gpuBuilding && wide.gpuComplete;
        if (list.Active) {
            Polygon* polys = wide.gpuPolys[wide.gpuBank].data();
            wide.gpuRender.resize(wide.gpuNumPolys);
            u32 io = 0, it = wide.gpuNumOpaque;
            for (u32 i = 0; i < wide.gpuNumPolys; i++) {
                Polygon* poly = &polys[i];
                if (poly->Translucent) wide.gpuRender[it++] = poly;
                else wide.gpuRender[io++] = poly;
            }
            std::stable_sort(wide.gpuRender.begin(),
                wide.gpuRender.begin() + ((g.FlushAttributes & 0x1) ? wide.gpuNumOpaque : wide.gpuNumPolys),
                [](Polygon* a, Polygon* b) { return a->SortKey < b->SortKey; });
            list.Polygons = wide.gpuRender.data();
            list.NumPolygons = (int) wide.gpuNumPolys;
            list.ViewW = W3D + wide.gpuL + wide.gpuR;
            list.ViewH = H3D + wide.gpuT + wide.gpuB;
            list.OffX = wide.gpuL;
            list.OffY = wide.gpuT;
            updateCrop();
        }
    } else {
        // The game's lists don't move on (rendering off): its own is drawn, ours is about to be written over.
        wide.gpuList.Active = false;
    }
    if (getenv("PK_WIDE_DEBUG"))
        fprintf(stderr, "swapGpu: rendering %d want %d building %d complete %d polys %u verts %u -> active %d\n", rendering,
            wide.gpuWant, wide.gpuBuilding, wide.gpuComplete, wide.gpuNumPolys, wide.gpuNumVerts, wide.gpuList.Active);
    wide.gpuBank ^= 1;
    wide.gpuNumVerts = wide.gpuNumPolys = wide.gpuNumOpaque = 0;
    wide.gpuComplete = true;
    // A side's margin, SPARE more (edge marking compares a pixel with its neighbours) and the headroom - every side,
    // the window can move out on any. None at all while the window is the screen: the game's own list is drawn.
    bool any = (wide.needL | wide.needR | wide.needT | wide.needB) != 0;
    auto margin = [&](int need, int max) { return any ? std::min(max, need + SPARE + GPU_HEADROOM) : 0; };
    wide.gpuL = margin(wide.needL, MX + SPARE);
    wide.gpuR = margin(wide.needR, MX + SPARE);
    wide.gpuT = margin(wide.needT, MY + SPARE);
    wide.gpuB = margin(wide.needB, MY + SPARE);
    wide.gpuBuilding = wide.on && wide.gpu && wide.gpuWant && (wide.gpuL | wide.gpuR | wide.gpuT | wide.gpuB);
    if (wide.gpuBuilding && wide.gpuVerts[0].empty()) {
        for (int b = 0; b < 2; b++) {
            wide.gpuVerts[b].resize(GPU_MAX_VERTS);
            wide.gpuPolys[b].resize(GPU_MAX_POLYS);
        }
    }
}

void onSwap(GPU3D& g, bool rendering, void*)
{
    waitSlices();   // (onVisibleEnd has, unless the hook wasn't in)
    if (wide.gpu) {
        swapGpu(g, rendering);
        return;
    }
    for (Slice& s : wide.slices) {
        if (s.verts[0].empty()) continue;
        if (rendering) {
            u32 io = 0, it = s.numOpaque;
            for (u32 i = 0; i < s.numPolys; i++) {
                Polygon* poly = &s.polys[s.bank][i];
                if (poly->Translucent) s.render[it++] = poly;
                else s.render[io++] = poly;
            }
            std::stable_sort(s.render.begin(), s.render.begin() + ((g.FlushAttributes & 0x1) ? s.numOpaque : s.numPolys),
                [](Polygon* a, Polygon* b) { return a->SortKey < b->SortKey; });
            s.renderNum = s.numPolys;
            s.lastCount = (int) s.numPolys;
        }
        s.bank ^= 1;
        s.numVerts = s.numPolys = s.numOpaque = 0;
    }
}

inline u32 rgba(u32 r, u32 g, u32 b) { return 0xFF000000u | (b << 16) | (g << 8) | r; }

// The 3D engine's colour (6 bits a channel) through the top screen's master brightness, as RGBA.
inline u32 shade(u32 r6, u32 g6, u32 b6, u32 mb)
{
    u32 mode = mb >> 14, f = std::min<u32>(mb & 0x1F, 16);
    if (mode == 1) {
        r6 += ((63 - r6) * f) >> 4; g6 += ((63 - g6) * f) >> 4; b6 += ((63 - b6) * f) >> 4;
    } else if (mode == 2) {
        r6 -= (r6 * f + 0xF) >> 4; g6 -= (g6 * f + 0xF) >> 4; b6 -= (b6 * f + 0xF) >> 4;
    }
    return rgba((r6 << 2) | (r6 >> 4), (g6 << 2) | (g6 >> 4), (b6 << 2) | (b6 >> 4));
}

inline u32 shade(u32 c, u32 mb) { return shade(c & 0x3F, (c >> 8) & 0x3F, (c >> 16) & 0x3F, mb); }

// A colour (6 bits a channel, r | g << 8 | b << 16) through BLDCNT's brightness up / down ([fx]: mode << 14 | EVY, as the
// master brightness keeps them), still 6 bits a channel: melonDS's ColorBrightnessUp / Down with BLDCNT's biases (8, 7).
inline u32 fx6(u32 c, u32 fx)
{
    u32 mode = fx >> 14, f = std::min<u32>(fx & 0x1F, 16);
    u32 r = c & 0x3F, g = (c >> 8) & 0x3F, b = (c >> 16) & 0x3F;
    if (mode == 1) {
        r += ((63 - r) * f + 8) >> 4; g += ((63 - g) * f + 8) >> 4; b += ((63 - b) * f + 8) >> 4;
    } else if (mode == 2) {
        r -= (r * f + 7) >> 4; g -= (g * f + 7) >> 4; b -= (b * f + 7) >> 4;
    }
    return r | g << 8 | b << 16;
}

inline u32 toRgba(u32 argb) { return 0xFF000000u | ((argb & 0xFF) << 16) | (argb & 0xFF00) | ((argb >> 16) & 0xFF); }

// Two RGBA colours within a texel's difference of each other: the slices sample textures a texel off here and there
// (a polygon cut at other planes interpolates along other edges), a text box or a sprite is far off.
inline bool near(u32 a, u32 b)
{
    for (int sh = 0; sh < 24; sh += 8) {
        int d = (int) ((a >> sh) & 0xFF) - (int) ((b >> sh) & 0xFF);
        if (d > 40 || d < -40) return false;
    }
    return true;
}

// A BGR555 colour as the 2D engine widens it (c << 1 a channel), in the 3D engine's layout (r | g << 8 | b << 16).
inline u32 widen(u16 c) { return ((c & 0x1F) << 1) | (((c >> 5) & 0x1F) << 9) | (((c >> 10) & 0x1F) << 17); }

// melonDS's 2D blends (GPU2D_Soft.h), 6 bits a channel: a layer over another by EVA / EVB, and the 3D on top by its
// own alpha.
inline u32 blend4(u32 a, u32 b, u32 eva, u32 evb)
{
    u32 r = std::min<u32>(0x3F, ((a & 0x3F) * eva + (b & 0x3F) * evb + 8) >> 4);
    u32 g = std::min<u32>(0x3F, (((a >> 8) & 0x3F) * eva + ((b >> 8) & 0x3F) * evb + 8) >> 4);
    u32 bl = std::min<u32>(0x3F, (((a >> 16) & 0x3F) * eva + ((b >> 16) & 0x3F) * evb + 8) >> 4);
    return r | g << 8 | bl << 16;
}

inline u32 blend5(u32 a, u32 b)
{
    u32 eva = ((a >> 24) & 0x1F) + 1, evb = 32 - eva;
    if (eva == 32) return a & 0x3F3F3F;
    u32 r = std::min<u32>(0x3F, ((a & 0x3F) * eva + (b & 0x3F) * evb + 0x10) >> 5);
    u32 g = std::min<u32>(0x3F, (((a >> 8) & 0x3F) * eva + ((b >> 8) & 0x3F) * evb + 0x10) >> 5);
    u32 bl = std::min<u32>(0x3F, (((a >> 16) & 0x3F) * eva + ((b >> 16) & 0x3F) * evb + 0x10) >> 5);
    return r | g << 8 | bl << 16;
}

// How the 2D engine puts the 3D layer on the top screen, for a pixel of the wide frame: a text BG alpha-blended over
// the 3D (BLDCNT's first target, the 3D its second) drawn on past the screen and blended the same way - Platinum's fog
// is a white BG2 at EVA 9 / EVB 7 over the 3D's own fog (GitHub #226: the margins showed the 3D alone, or the screen's
// edge columns streaked across them) - the 3D's alpha against the backdrop, the master brightness.
struct Compose {
    NDS* nds = nullptr;
    u32 mb = 0;
    u32 backdrop = 0;   // widened
    u32 blendCnt = 0, eva = 0, evb = 0;
    // BLDCNT's brightness up / down where nothing's over the 3D (Black 2's X menu darkens the field behind it): the mode
    // and EVY as the master brightness keeps them (mode << 14 | EVY: 1 up, 2 down, 0 none), its first targets 3D / backdrop.
    u32 fx = 0;
    bool fx3D = false, fxBackdrop = false;
    struct Layer {
        int bg = 0, sx = 0, sy = 0, w = 256, h = 256;
        // Covers the screen's whole outline: a sheet over all of it (fog), carried on as it tiles. Otherwise only the
        // rows it reaches the screen's side on, in that edge pixel's colour (edge[side][y], 0 where it's clear).
        bool full = false;
        u16 edge[2][H3D] = {};
        std::vector<u16> rows;   // the BG's pixels, w x h, filled a row at a time as the margins need them
        std::vector<u8> rowDone;
    };
    Layer layers[3];
    int count = 0;
    u8 bright[64] = {};   // a channel through the master brightness, widened to 8 bits (shade's, per channel)
    u32 finish(u32 c) const { return 0xFF000000u | bright[c & 0x3F] | bright[(c >> 8) & 0x3F] << 8 | bright[(c >> 16) & 0x3F] << 16; }
};

Compose compose;

bool isOverlay(const Compose& k, int bg)
{
    for (int i = 0; i < k.count; i++) if (k.layers[i].bg == bg) return true;
    return false;
}

// [k] for the frame on screen now; [threeD]: the 3D is what the margins show (no layers over it otherwise).
void setupCompose(Compose& k, NDS* nds, bool threeD)
{
    const Shown2D& u = shown(nds);
    k.nds = nds;
    k.mb = u.MasterBrightness;
    if (!(u.DispCnt & 0x30000)) k.mb = 0;   // display off / VRAM modes: the 2D engine's call
    for (u32 v = 0; v < 64; v++) k.bright[v] = (u8) (shade(v, k.mb) & 0xFF);
    k.backdrop = widen((u16) (u.Palette[0] | (u.Palette[1] << 8)));
    k.blendCnt = u.BlendCnt;
    k.eva = u.EVA;
    k.evb = u.EVB;
    k.count = 0;
    {
        int fmode = (k.blendCnt >> 6) & 3;
        bool windows = (u.DispCnt & 0xE000) != 0;
        bool outsideFx = !windows || (u.WinCnt[2] & 0x20);   // WINOUT's colour-effect bit: the margins are outside
        k.fx = threeD && outsideFx && fmode >= 2 ? (u32) (fmode - 1) << 14 | std::min<u32>(16, u.EVY) : 0;
        k.fx3D = (k.blendCnt & 1) != 0;
        k.fxBackdrop = (k.blendCnt & 0x20) != 0;
    }
    // The windows aren't followed (the 3D past the screen never is): the margins go on through a window's wipe as
    // they do on any map.
    if (!threeD || ((k.blendCnt >> 6) & 3) != 1 || !(k.blendCnt & 0x100)) return;
    int mode = (int) (u.DispCnt & 7), basePrio = u.BGCnt[0] & 3;
    int order[3], n = 0;
    for (int bg = 1; bg <= 3; bg++) {
        bool text = mode == 0 || (mode == 1 && bg < 3) || (mode == 2 && bg < 2);
        if (!text || !(u.DispCnt & (1 << (8 + bg)))) continue;
        if ((u.BGCnt[bg] & 3) >= basePrio || !(k.blendCnt & (1 << bg))) continue;
        order[n++] = bg;
    }
    // Topmost first: the lower priority number, then the lower BG.
    std::sort(order, order + n, [&](int a, int b) { return (u.BGCnt[a] & 3) != (u.BGCnt[b] & 3) ? (u.BGCnt[a] & 3) < (u.BGCnt[b] & 3) : a < b; });
    for (int i = 0; i < n; i++) {
        Compose::Layer& l = k.layers[k.count++];
        l.bg = order[i];
        l.sx = u.BGXPos[l.bg];
        l.sy = u.BGYPos[l.bg];
        int size = u.BGCnt[l.bg] >> 14;
        l.w = (size & 1) ? 512 : 256;
        l.h = (size & 2) ? 512 : 256;
        l.full = true;
        for (int y = 0; y < H3D; y++)
            for (int side = 0; side < 2; side++) {
                l.edge[side][y] = pk_nds_bg_pixel(nds, l.bg, (side ? W3D - 1 : 0) + l.sx, y + l.sy);
                l.full &= l.edge[side][y] != 0;
            }
        for (int x = 0; x < W3D && l.full; x++)
            l.full = pk_nds_bg_pixel(nds, l.bg, x + l.sx, l.sy) && pk_nds_bg_pixel(nds, l.bg, x + l.sx, H3D - 1 + l.sy);
        if (l.full) {
            l.rows.resize((size_t) l.w * l.h);
            l.rowDone.assign(l.h, 0);
        }
    }
}

// BG row [by] of layer [l] whole into [out] (l.w pixels): pk_nds_bg_pixel's reads, a map entry a tile.
void decodeRow(NDS* nds, const Compose::Layer& l, int by, u16* out)
{
    const GPU& gpu = nds->GPU;
    const Shown2D& u = shown(nds);
    u16 cnt = u.BGCnt[l.bg];
    u32 disp = u.DispCnt;
    u32 mapBase = 0x06000000u + ((disp >> 27) & 7) * 0x10000u + ((cnt >> 8) & 0x1F) * 0x800u;
    u32 charBase = 0x06000000u + ((disp >> 24) & 7) * 0x10000u + ((cnt >> 2) & 0xF) * 0x4000u;
    int ty = by >> 3, rowBlock = 0;
    if (ty >= 32) { rowBlock = l.w == 512 ? 2 : 1; ty -= 32; }
    for (int tx0 = 0; tx0 < l.w >> 3; tx0++) {
        int tx = tx0, block = rowBlock;
        if (tx >= 32) { block += 1; tx -= 32; }
        u16 e = gpu.ReadVRAM_BG<u16>(mapBase + (u32) block * 0x800u + (u32) (ty * 32 + tx) * 2u);
        int py = (e & 0x800) ? 7 - (by & 7) : by & 7;
        u32 tile = e & 0x3FF;
        u16* dst = out + tx0 * 8;
        for (int i = 0; i < 8; i++) {
            int px = (e & 0x400) ? 7 - i : i;
            u32 ci;
            if (cnt & 0x80) {
                ci = gpu.ReadVRAM_BG<u8>(charBase + tile * 64u + (u32) (py * 8 + px));
            } else {
                u8 b = gpu.ReadVRAM_BG<u8>(charBase + tile * 32u + (u32) (py * 4 + px / 2));
                ci = (px & 1) ? b >> 4 : b & 0xF;
                if (ci) ci += (e >> 12) * 16u;
            }
            dst[i] = ci ? (u16) (0x8000 | ((u.Palette[ci * 2] | (u.Palette[ci * 2 + 1] << 8)) & 0x7FFF)) : 0;
        }
    }
}

// The overlays' colour at wide-frame pixel (x, y) (0: none there).
inline u16 overlayPixel(Compose& k, int x, int y)
{
    bool inX = x >= 0 && x < W3D, inY = y >= 0 && y < H3D;
    for (int i = 0; i < k.count; i++) {
        Compose::Layer& l = k.layers[i];
        u16 c = 0;
        if (inX && inY) {
            c = pk_nds_bg_pixel(k.nds, l.bg, x + l.sx, y + l.sy);
        } else if (l.full) {
            int by = (y + l.sy) & (l.h - 1);
            u16* row = &l.rows[(size_t) by * l.w];
            if (!l.rowDone[by]) {
                decodeRow(k.nds, l, by, row);
                l.rowDone[by] = 1;
            }
            c = row[(x + l.sx) & (l.w - 1)];
        } else if (inY) {
            c = l.edge[x < 0 ? 0 : 1][y];
        }
        if (c) return c;
    }
    return 0;
}

// Engine A's sprite (OAM attributes [a0] [a1] [a2], [w] x [h]) at pixel ([px], [py]) of itself, as GPU2D_Soft's
// DrawSprite_Normal reads its tiles and the palettes: 0 clear, else 0x8000 | BGR555. Affine and bitmap sprites aren't
// read: OBJ_UNREAD all over.
constexpr u32 OBJ_UNREAD = 0x10000;

u32 objPixel(NDS* nds, u16 a0, u16 a1, u16 a2, int w, int h, int px, int py)
{
    if (px < 0 || px >= w || py < 0 || py >= h) return 0;
    if ((a0 & 0x100) || ((a0 >> 10) & 3) == 3) return OBJ_UNREAD;
    const GPU& gpu = nds->GPU;
    const Shown2D& u = shown(nds);
    u32 disp = u.DispCnt;
    if (a1 & 0x2000) py = h - 1 - py;
    if (a1 & 0x1000) px = w - 1 - px;
    bool c256 = (a0 & 0x2000) != 0;
    u32 tile = a2 & 0x3FF;
    if (disp & 0x10) tile = (tile << ((disp >> 20) & 3)) + (u32) ((py >> 3) * (w >> 3) << (c256 ? 1 : 0));
    else tile += (u32) (py >> 3) * 0x20;
    u32 addr = tile << 5, ci;
    if (c256) {
        addr += (u32) ((py & 7) << 3) + (u32) ((px & ~7) << 3) + (u32) (px & 7);
        ci = gpu.ReadVRAM_AOBJ<u8>(addr & 0x3FFFF);
    } else {
        addr += (u32) ((py & 7) << 2) + (u32) ((px & ~7) << 2) + (u32) ((px & 7) >> 1);
        u8 b = gpu.ReadVRAM_AOBJ<u8>(addr & 0x3FFFF);
        ci = (px & 1) ? b >> 4 : b & 0xF;
    }
    if (!ci) return 0;
    u32 pal = a2 >> 12;
    u16 c;
    if (c256 && (disp & 0x80000000u)) {
        // the extended palettes: VRAM F or G mapped there
        u32 off = (pal * 256 + ci) * 2 & 0x1FFF, m = gpu.VRAMMap_AOBJExtPal;
        c = 0;
        if (m & (1 << 5)) c |= (u16) (gpu.VRAM_F[off] | gpu.VRAM_F[off + 1] << 8);
        if (m & (1 << 6)) c |= (u16) (gpu.VRAM_G[off] | gpu.VRAM_G[off + 1] << 8);
    } else {
        u32 i = 0x200 + (c256 ? ci : pal * 16 + ci) * 2;
        c = (u16) (u.Palette[i] | (u.Palette[i + 1] << 8));
    }
    return 0x8000u | (c & 0x7FFF);
}

inline bool objOpaque(NDS* nds, u16 a0, u16 a1, u16 a2, int w, int h, int px, int py)
{
    return objPixel(nds, a0, a1, a2, w, h, px, py) != 0;
}

// Wide-frame pixel (x, y) as the screen would show it, its 3D colour [c] (NONE / alpha 0: the 3D clear there), as RGBA.
inline u32 composed(Compose& k, int x, int y, u32 c)
{
    bool has3D = c != NONE && (c >> 24);
    u32 under = has3D ? c & 0x3F3F3F : k.backdrop, out = under;
    u16 o = k.count ? overlayPixel(k, x, y) : 0;
    if (o) {
        bool target2 = (k.blendCnt & (has3D ? 0x100 : 0x2000)) != 0;
        out = target2 ? blend4(widen(o), under, k.eva, k.evb) : widen(o);
    } else if (has3D && (k.blendCnt & 0x2000)) {
        out = blend5(c, k.backdrop);   // the 3D on top, see-through onto the backdrop
    }
    if (!o && k.fx && (has3D ? k.fx3D : k.fxBackdrop)) out = fx6(out, k.fx);
    return k.finish(out);
}

// Screen pixel (x, y) from slice (sx, sy)'s image: NONE where it wasn't drawn.
inline u32 at(const Image& im, int sx, int sy, int x, int y)
{
    if (!im.valid || x < im.x0 || x >= im.x1 || y < im.y0 || y >= im.y1) return NONE;
    return im.px[(y - sy * (H3D - OVERLAP)) * W3D + x - sx * (W3D - OVERLAP)];
}

inline u32 at(int sx, int sy, int x, int y) { return at(sliceAt(sx, sy).image, sx, sy, x, y); }

// A margin pixel's 3D colour (NONE: none drawn). The slices level with the screen are cut where the screen is (its
// columns 0 / 255 above and below it, its rows 0 / 191 beside it) and outlined there like the screen's own edges: those
// two pixels come from the corner slice beside them, which has them inside.
inline u32 marginPixel(int x, int y)
{
    int sx = x < 0 ? -1 : x >= W3D ? 1 : 0, sy = y < 0 ? -1 : y >= H3D ? 1 : 0;
    if (sx == 0) {
        int ax = x < SPARE ? -1 : x >= W3D - SPARE ? 1 : 0;
        if (ax) {
            u32 c = at(ax, sy, x, y);
            if (c != NONE) return c;
        }
    } else if (sy == 0) {
        int ay = y < SPARE ? -1 : y >= H3D - SPARE ? 1 : 0;
        if (ay) {
            u32 c = at(sx, ay, x, y);
            if (c != NONE) return c;
        }
    }
    return at(sx, sy, x, y);
}

// The rectangle slice (sx, sy) draws, in the screen's pixels: the margin the window needs on its side(s), SPARE more,
// and its overlap with the screen.
void sliceRect(int sx, int sy, int* x0, int* x1, int* y0, int* y1)
{
    *x0 = sx < 0 ? -(wide.needL + SPARE) : sx == 0 ? 0 : W3D - OVERLAP;
    *x1 = sx < 0 ? OVERLAP : sx == 0 ? W3D : W3D + wide.needR + SPARE;
    *y0 = sy < 0 ? -(wide.needT + SPARE) : sy == 0 ? 0 : H3D - OVERLAP;
    *y1 = sy < 0 ? OVERLAP : sy == 0 ? H3D : H3D + wide.needB + SPARE;
}

void drawSlice(Slice& s, Drawer& d)
{
    int x0 = s.x0, x1 = s.x1, y0 = s.y0, y1 = s.y1;
    int ox = s.sx * (W3D - OVERLAP), oy = s.sy * (H3D - OVERLAP);   // screen pixel = slice pixel + (ox, oy)
    SoftRenderer& r = *d.renderer;
    r.ColumnMin = std::clamp(x0 - ox, 0, W3D);
    r.ColumnMax = std::clamp(x1 - ox, 0, W3D);
    r.RowMin = std::clamp(y0 - oy, 0, H3D);
    r.RowMax = std::clamp(y1 - oy, 0, H3D);
    // Polygons wholly outside those cost their per-line set-up all the same: left out (the order kept).
    d.visible.clear();
    for (u32 i = 0; i < s.renderNum; i++) {
        Polygon* p = s.render[i];
        if (p->YBottom < r.RowMin || p->YTop >= r.RowMax) continue;
        s32 lo = 512, hi = -1;
        for (u32 v = 0; v < p->NumVertices; v++) {
            lo = std::min(lo, p->Vertices[v]->FinalPosition[0]);
            hi = std::max(hi, p->Vertices[v]->FinalPosition[0]);
        }
        if (hi >= r.ColumnMin && lo < r.ColumnMax) d.visible.push_back(p);
    }
    r.RenderPolygonList(wide.nds->GPU, d.visible.data(), (int) d.visible.size());
    for (int y = r.RowMin; y < r.RowMax; y++)
        memcpy(&s.image.px[y * W3D + r.ColumnMin], r.GetLine(y) + r.ColumnMin, (size_t) (r.ColumnMax - r.ColumnMin) * 4);
    s.image.x0 = r.ColumnMin + ox;
    s.image.x1 = r.ColumnMax + ox;
    s.image.y0 = r.RowMin + oy;
    s.image.y1 = r.RowMax + oy;
    s.image.valid = true;
}

void takeJobs(Drawer& d)
{
    Pool& p = wide.pool;
    for (int i; (i = p.next.fetch_add(1)) < (int) p.jobs.size();) drawSlice(*p.jobs[i], d);
}

void helperLoop(int id)
{
    Pool& p = wide.pool;
    u64 seen = 0;
    for (;;) {
        {
            std::unique_lock<std::mutex> l(p.lock);
            p.wake.wait(l, [&] { return p.quit || p.generation != seen; });
            if (p.quit) return;
            seen = p.generation;
        }
        takeJobs(wide.drawers[id]);
        {
            std::lock_guard<std::mutex> l(p.lock);
            p.busy--;
        }
        p.done.notify_one();
    }
}

// The slices in [jobs] handed to the helpers, largest first, and left drawing while the game runs on: they read the
// lists the last VBlank swapped (ours, untouched until the next swap), the 3D registers it latched and the textures as
// the main renderer last copied them (GPU::VRAMFlat_*, at line 215), and the next frame changes none of those before its
// own VBlank at line 192 - where waitSlices holds the game until they're done (onVisibleEnd), about three quarters of a
// frame later. Drawn on the emulator's thread after each frame, they were most of EXPAND's cost there.
void drawSlices()
{
    Pool& p = wide.pool;
    if (p.jobs.empty() || p.threads.empty()) return;
    for (Slice* s : p.jobs) sliceRect(s->sx, s->sy, &s->x0, &s->x1, &s->y0, &s->y1);
    std::stable_sort(p.jobs.begin(), p.jobs.end(), [](Slice* a, Slice* b) {
        return (a->x1 - a->x0) * (a->y1 - a->y0) > (b->x1 - b->x0) * (b->y1 - b->y0);
    });
    p.next = 0;
    {
        std::lock_guard<std::mutex> l(p.lock);
        p.busy = (int) p.threads.size();
        p.generation++;
    }
    p.pending = true;
    p.wake.notify_all();
}

// The slices handed out by drawSlices drawn: before anything they read changes (a VBlank, a state load, the lists' swap)
// and before the images are composed.
void waitSlices()
{
    Pool& p = wide.pool;
    if (!p.pending) return;
    std::unique_lock<std::mutex> l(p.lock);
    p.done.wait(l, [&] { return p.busy == 0; });
    p.pending = false;
}

void stopPool()
{
    waitSlices();
    Pool& p = wide.pool;
    {
        std::lock_guard<std::mutex> l(p.lock);
        p.quit = true;
    }
    p.wake.notify_all();
    for (std::thread& t : p.threads) t.join();
    p.threads.clear();
    p.quit = false;
    // New helpers start at generation 0: one left over from before had them take an old job list at once, and count
    // busy below zero (EXPAND off and on again).
    p.generation = 0;
    p.busy = 0;
    p.jobs.clear();
}

} // namespace

static void applyNeed();

void pk_nds_wide_set(NDS* nds, bool on)
{
    if (nds == wide.nds && on == wide.on) return;
    waitSlices();
    if (wide.nds) {
        wide.nds->GPU.GPU3D.WideSubmitHook = nullptr;
        wide.nds->GPU.GPU3D.WideSwapHook = nullptr;
        wide.nds->GPU.GPU3D.WideBoxHook = nullptr;
        wide.nds->GPU.VisibleEndHook = nullptr;
    }
    shown2D.valid = false;
    wide.nds = nds;
    wide.on = nds && on;
    wide.gpuList = WidePolygonList();
    wide.gpuBuilding = false;
    wide.plan = PkNdsWidePlan();
    for (int i = 0; i < 9; i++) {
        wide.slices[i] = Slice();
        wide.slices[i].sx = i % 3 - 1;
        wide.slices[i].sy = i / 3 - 1;
    }
    wide.cx0 = wide.cx1 = wide.cy0 = wide.cy1 = 0;
    stopPool();
    if (!wide.on) {
        for (Drawer& d : wide.drawers) d = Drawer();
        wide.out.clear();
        wide.out.shrink_to_fit();
        return;
    }
    for (Drawer& d : wide.drawers) {
        d.renderer = std::make_unique<SoftRenderer>();
        d.renderer->Reset(nds->GPU);
    }
    for (int i = 0; i < HELPERS; i++) wide.pool.threads.emplace_back(helperLoop, i);
    wide.out.assign((size_t) OW * OH, 0xFF000000u);
    nds->GPU.GPU3D.WideHookUser = &wide;
    nds->GPU.GPU3D.WideSubmitHook = onSubmit;
    nds->GPU.GPU3D.WideSwapHook = onSwap;
    nds->GPU.GPU3D.WideBoxHook = onBoxTest;
    nds->GPU.VisibleEndHook = onVisibleEnd;
    applyNeed();
}

void pk_nds_wide_gpu(bool on, int scale, int maxTexture)
{
    waitSlices();
    wide.gpuScale = std::max(1, scale);
    wide.gpuMaxTexture = std::max(256, maxTexture);
    if (on == wide.gpu) return;
    wide.gpu = on;
    wide.gpuList = WidePolygonList();
    wide.gpuBuilding = false;
    wide.gpuNumVerts = wide.gpuNumPolys = wide.gpuNumOpaque = 0;
    wide.plan = PkNdsWidePlan();
    for (Slice& s : wide.slices) {
        s.image.valid = s.shown.valid = false;
        s.renderNum = 0;
    }
    applyNeed();
}

const WidePolygonList* pk_nds_wide_gpu_list() { return &wide.gpuList; }

void pk_nds_wide_gpu_want(bool threeD) { wide.gpuWant = threeD; }

void pk_nds_wide_plan(PkNdsWidePlan* out)
{
    *out = wide.on && wide.gpu ? wide.plan : PkNdsWidePlan();
}

void pk_nds_wide_need(int left, int right, int top, int bottom)
{
    // The margins drawn now are shown with the next frame's window: one moving out (the look-around, a zoom easing
    // out) is drawn for where it'll be by then too - twice its last step further, SPARE more - else its leading edge
    // showed a strip of black.
    auto ahead = [](int ask, int& last) {
        int step = std::max(0, ask - last);
        last = ask;
        return step ? ask + 2 * step + SPARE : ask;
    };
    wide.needL = std::clamp(ahead(left, wide.askL), 0, MX);
    wide.needR = std::clamp(ahead(right, wide.askR), 0, MX);
    wide.needT = std::clamp(ahead(top, wide.askT), 0, MY);
    wide.needB = std::clamp(ahead(bottom, wide.askB), 0, MY);
    applyNeed();
}

// The margins wanted now: slices on / off, the GPU list's crop.
static void applyNeed()
{
    if (wide.gpu) updateCrop();
    if (!wide.on) return;
    for (Slice& s : wide.slices) {
        bool x = s.sx < 0 ? wide.needL > 0 : s.sx > 0 ? wide.needR > 0 : true;
        bool y = s.sy < 0 ? wide.needT > 0 : s.sy > 0 ? wide.needB > 0 : true;
        s.active = (s.sx || s.sy) && x && y && !wide.gpu;
        if (s.active && s.verts[0].empty()) {
            for (int b = 0; b < 2; b++) {
                s.verts[b].resize(MAX_VERTS);
                s.polys[b].resize(MAX_POLYS);
            }
            s.image.px.assign(W3D * H3D, 0);
            s.shown.px.assign(W3D * H3D, 0);
        }
    }
}

bool pk_nds_wide_frame(NDS* nds, const uint32_t* top, bool draw, bool margins, bool drawNext, bool battle)
{
    if (!wide.on || nds != wide.nds || !top) return false;
    waitSlices();
    int step = 1 + wide.undrawn;
    wide.undrawn = draw ? 0 : std::min(wide.undrawn + 1, 255);
    // Only while the 3D engine (engine A) is the top screen, and the caller says it's the overworld.
    bool aOnTop = (nds->PowerControl9 & (1 << 15)) != 0;
    bool threeDOnTop = margins && aOnTop;
    // A battle: its background layer, BG3 - a text BG (mode 0-2), shown - drawn on past the screen.
    const Shown2D& unitA = shown(nds);
    bool battleBg = battle && !threeDOnTop && aOnTop && (unitA.DispCnt & 7) <= 2 && (unitA.DispCnt & (1 << 11));
    // The battle's 3D over it: BG0 the 3D layer, shown, not below BG3.
    bool battle3D = battleBg && (unitA.DispCnt & (1 << 3)) && (unitA.DispCnt & (1 << 8)) &&
        (unitA.BGCnt[0] & 3) <= (unitA.BGCnt[3] & 3);
    int bgx = unitA.BGXPos[3], bgy = unitA.BGYPos[3];
    // BLDCNT on the background (BG3, or the backdrop where it's clear) - modes 2 / 3, brightness up / down - unless a
    // window keeps colour effects off outside it: +1 / -1, 0 none.
    int fade = 0;
    u32 evy = std::min<u32>(16, unitA.EVY);
    {
        u16 bc = unitA.BlendCnt;
        int fmode = (bc >> 6) & 3;
        bool windows = (unitA.DispCnt & 0xE000) != 0;
        bool outsideFx = !windows || (unitA.WinCnt[2] & 0x20);   // WINOUT's colour-effect bit
        if (battleBg && outsideFx && (bc & ((1 << 3) | (1 << 5))) && fmode >= 2) fade = fmode == 2 ? 1 : -1;
    }
    // A 2D layer over what the margins show (the 3D, or a battle's background) that's black at the screen's edge on a
    // row - a transition's wipe blocks, a battle intro's bands - blacks that row's margin out too: those layers aren't
    // pictured past the screen, but their black at its edge carries on. In a battle any colour does (the text box's dark
    // frame runs the screen's width), on the field any row the layer covers edge to edge: the row's margin takes the
    // screen's edge pixel.
    bool blackL[H3D] = {}, blackR[H3D] = {};
    // A layer blended over the 3D is drawn on into the margins instead (setupCompose).
    setupCompose(compose, nds, threeDOnTop);
    if (aOnTop && (threeDOnTop || battleBg)) {
        int mode = (int) (unitA.DispCnt & 7);
        int base = battleBg ? 3 : 0;
        int basePrio = unitA.BGCnt[base] & 3;
        for (int bg = 1; bg <= 3; bg++) {
            if (bg == base || !(unitA.DispCnt & (1 << (8 + bg))) || (threeDOnTop && isOverlay(compose, bg))) continue;
            bool text = mode == 0 || (mode == 1 && bg < 3) || (mode == 2 && bg < 2);
            int prio = unitA.BGCnt[bg] & 3;
            if (!text || prio > basePrio || (prio == basePrio && bg > base)) continue;
            for (int y = 0; y < H3D; y++) {
                u16 cl = pk_nds_bg_pixel(nds, bg, unitA.BGXPos[bg], y + unitA.BGYPos[bg]);
                u16 cr = pk_nds_bg_pixel(nds, bg, W3D - 1 + unitA.BGXPos[bg], y + unitA.BGYPos[bg]);
                // A row the layer covers from edge to edge (HG/SS's "look at the touch screen" bar under the field
                // menu) carries on too; a text box stops short of the edges.
                bool across = cl && cr;
                for (int side = 0; side < 2; side++) {
                    u16 c = side ? cr : cl;
                    if (c && (battleBg || across || !(c & 0x7FFF))) (side ? blackR : blackL)[y] = true;
                }
            }
        }
    }
    // Sprites at the screen's sides (the HP boxes, flush with them): their rows carry the screen's edge column on
    // through the margin, so a box still reaches the picture's edge instead of stopping in mid-air.
    bool edgeObjL[H3D] = {}, edgeObjR[H3D] = {};
    // Only frames that were drawn (fast-forward skips the rest: their buffers are an older frame's).
    for (int side = 0; side < 2 && draw; side++) {
        u32 now[H3D];
        for (int y = 0; y < H3D; y++) now[y] = top[y * W3D + (side ? W3D - 1 : 0)];
        for (int y = 0; y < H3D; y++) {
            const u32* was = wide.edgeSeen[side];
            bool held = now[y] == was[y] || (y > 0 && now[y] == was[y - 1]) || (y + 1 < H3D && now[y] == was[y + 1]);
            u8& n = wide.edgeSteady[side][y];
            n = held ? (u8) std::min(255, n + step) : 0;
        }
        std::memcpy(wide.edgeSeen[side], now, sizeof(now));
    }
    wide.objHeldNext.clear();
    // A battle's scenery sprites the screen shows part of, its sides crossing them (the platforms and the trainer sliding
    // in - behind the battle's 3D, the Pokemon): drawn on past it from their own tiles. Not one wholly off the screen -
    // its 9-bit x could put it on either side, and a game parks the ones it doesn't use there - unless it's the next
    // piece of one that's drawn (flush beside it, the same row, size, palette and priority: a platform is 64-pixel
    // pieces); nor the boxes and text over the 3D, which a game leaves untidy past the screen (a box's pieces sliding
    // on apart): those are carried below.
    struct Crossing { u16 a0, a1, a2; int x, y, w, h, prio; };
    Crossing crossing[128], offScreen[128];
    int numCrossing = 0, numOff = 0;
    if (draw && battleBg && (unitA.DispCnt & (1 << 12))) {
        static const int sizes[3][4][2] = {
            {{8, 8}, {16, 16}, {32, 32}, {64, 64}}, {{16, 8}, {32, 8}, {32, 16}, {64, 32}}, {{8, 16}, {8, 32}, {16, 32}, {32, 64}},
        };
        for (int i = 0; i < 128; i++) {
            const u8* o = &unitA.OAM[i * 8];
            u16 a0 = (u16) (o[0] | o[1] << 8), a1 = (u16) (o[2] | o[3] << 8);
            // Its place across the screen (and whether it shows): a box bobbing a pixel up and down while the game waits
            // for a command still counts as still.
            u16 a2 = (u16) (o[4] | o[5] << 8);
            u64 key = (u64) (a0 & 0xC300) | (u64) (a1 & 0xF1FF) << 16 | (u64) a2 << 32;
            u8 held = 0;
            for (auto& [k, n] : wide.objHeld) if (k == key) { held = (u8) std::min(255, n + step); break; }
            wide.objHeldNext.emplace_back(key, held);
            if (!(a0 & 0x100) && (a0 & 0x200)) continue;   // hidden
            int shape = (a0 >> 14) & 3;
            if (shape == 3) continue;
            int w = sizes[shape][(a1 >> 14) & 3][0], h = sizes[shape][(a1 >> 14) & 3][1];
            if ((a0 & 0x300) == 0x300) { w *= 2; h *= 2; }   // double-size affine
            int x = a1 & 0x1FF, y = a0 & 0xFF;
            if (x >= 256) x -= 512;
            if (y >= 192) y -= 256;
            int prio = (a2 >> 10) & 3;
            if (battle3D && prio > (unitA.BGCnt[0] & 3)) {
                bool onScreen = x < W3D && x + w > 0 && y < H3D && y + h > 0;
                if (((a0 >> 10) & 3) < 2 && !(a0 & 0x100)) {
                    // (the ones wholly on the screen too, for their off-screen neighbours: they add nothing past it)
                    if (onScreen) crossing[numCrossing++] = {a0, a1, a2, x, y, w, h, prio};
                    else offScreen[numOff++] = {a0, a1, a2, x, y, w, h, prio};
                }
                continue;   // scenery is never carried (a settled platform at the edge was, as a white strip)
            }
            if (held < EDGE_STEADY) continue;
            bool left = x <= 0 && x + w > 0, right = x < W3D && x + w >= W3D;
            // Only rows the sprite has a pixel on at the screen's edge.
            for (int r = std::max(0, y); r < std::min(H3D, y + h); r++) {
                edgeObjL[r] |= left && objOpaque(nds, a0, a1, a2, w, h, -x, r - y);
                edgeObjR[r] |= right && objOpaque(nds, a0, a1, a2, w, h, W3D - 1 - x, r - y);
            }
        }
    }
    // The pieces wholly off the screen that go on from a drawn one, either way its 9-bit x reads, piece by piece.
    for (bool more = numOff > 0; more;) {
        more = false;
        for (int i = 0; i < numOff; i++) {
            Crossing o = offScreen[i];
            for (int j = 0; j < numCrossing && !more; j++) {
                const Crossing& c = crossing[j];
                if (o.y != c.y || o.h != c.h || o.w != c.w || o.prio != c.prio || (o.a2 >> 12) != (c.a2 >> 12)) continue;
                for (int ox : {o.x, o.x + 512, o.x - 512}) {
                    if (ox + o.w != c.x && c.x + c.w != ox) continue;
                    o.x = ox;
                    crossing[numCrossing++] = o;
                    offScreen[i] = offScreen[--numOff];
                    more = true;
                    break;
                }
            }
            if (more) break;
        }
    }
    if (draw) wide.objHeld.swap(wide.objHeldNext);
    if (draw) {
        u32 mb = unitA.MasterBrightness;
        if (!(unitA.DispCnt & 0x30000)) mb = 0;   // display off / VRAM modes: the 2D engine's call
        u16 bd = (u16) (unitA.Palette[0] | (unitA.Palette[1] << 8));
        u32 backdrop = shade((bd & 0x1F) << 1, ((bd >> 5) & 0x1F) << 1, ((bd >> 10) & 0x1F) << 1, mb);
        u32 off = threeDOnTop || battleBg ? backdrop : 0xFF000000u;
        // BG3's colour as the screen shows it: BGR555 widened to 6 bits (a 2D layer's c << 1, ScanlineFinalPass),
        // BLDCNT's brightness up / down on it (a move's darkening, a flash - no other layer is over it out here, so its
        // alpha blending has nothing to mix), then the master brightness.
        auto bg6 = [&](u16 c) {
            u32 r = (c & 0x1F) << 1, g = ((c >> 5) & 0x1F) << 1, b = ((c >> 10) & 0x1F) << 1;
            if (fade) {
                auto f = [&](u32 v) { return fade > 0 ? v + (63 - v) * evy / 16 : v - v * evy / 16; };
                r = f(r); g = f(g); b = f(b);
            }
            return r | g << 8 | b << 16;
        };
        auto bgColor = [&](u16 c) { return shade(bg6(c), mb); };
        // Each row's colour carried past a box at the screen's side (0: none). A row whose edge band is one colour,
        // held EDGE_STEADY frames, carries its edge pixel; a row of the same box with a letter or a bar at the edge
        // takes the nearest such row's colour (the box's line or fill it's drawn over) - its own edge pixel would
        // streak the letter across the margin, and the background there cut the box into slats.
        u32 carry[2][H3D] = {};
        int carryRow[2][H3D] = {};   // the row it's taken from (GPU mode: the compositor reads it there)
        if (battleBg)
            for (int side = 0; side < 2; side++) {
                bool* objRows = side ? edgeObjR : edgeObjL;
                int ex = side ? W3D - 1 : 0;
                bool covered[H3D] = {}, sure[H3D] = {};
                bool* bgRows = side ? blackR : blackL;
                for (int y = 0; y < H3D; y++) {
                    // A layer over the background at the edge (the text box) has those rows: they end a box's run.
                    if (!objRows[y] || bgRows[y]) continue;
                    u16 under = pk_nds_bg_pixel(nds, 3, ex + bgx, y + bgy);
                    u32 shown = toRgba(top[y * W3D + ex]);
                    // Where the sprite leaves the background showing (a box's rounded corner), nothing to carry - the
                    // background's very colour: a box's light grey is near a daytime sky's.
                    u32 bgc = under ? bgColor(under) : backdrop;
                    bool same = true;
                    for (int sh = 0; sh < 24; sh += 8) same &= std::abs((int) ((shown >> sh) & 0xFF) - (int) ((bgc >> sh) & 0xFF)) <= 8;
                    if (same) continue;
                    covered[y] = true;
                    bool band = true;
                    for (int k = 1; k < EDGE_BAND && band; k++)
                        band = top[y * W3D + (side ? W3D - 1 - k : k)] == top[y * W3D + ex];
                    sure[y] = band && wide.edgeSteady[side][y] >= EDGE_STEADY;
                }
                for (int y = 0; y < H3D; y++) {
                    if (!covered[y]) continue;
                    int best = -1;
                    for (int d = 0; d < H3D && best < 0; d++) {
                        int up = y - d, down = y + d;
                        bool upIn = up >= 0, downIn = down < H3D;
                        // Within the box's run of rows only.
                        for (int k = y; upIn && k > up; k--) if (!covered[k - 1]) upIn = false;
                        for (int k = y; downIn && k < down; k++) if (!covered[k + 1]) downIn = false;
                        if (!upIn && !downIn) break;
                        if (upIn && sure[up]) best = up;
                        else if (downIn && sure[down]) best = down;
                    }
                    if (best >= 0) {
                        carry[side][y] = toRgba(top[best * W3D + ex]);
                        carryRow[side][y] = best;
                    }
                }
            }
        wide.cx0 = -wide.needL;
        wide.cx1 = W3D + wide.needR;
        wide.cy0 = -wide.needT;
        wide.cy1 = H3D + wide.needB;
        for (int y = wide.cy0; y < wide.cy1; y++) {
            u32* row = &wide.out[(size_t) (y + MY) * OW + MX];   // row[x], x from -MX
            if (y >= 0 && y < H3D && !wide.gpu) {
                const u32* src = top + y * W3D;
                for (int x = 0; x < W3D; x++) row[x] = toRgba(src[x]);
            }
            // GPU mode: what the compositor does there (pk_nds_wide.h's PK_NDS_PLAN_*) - colours it takes as they are,
            // the screen's own pixels where the margin repeats them, the 3D (and the 2D blended over it) to put together.
            auto color = [&](u32 rgba) { return wide.gpu ? (rgba & 0xFFFFFFu) | PK_NDS_PLAN_COLOR << 24 : rgba; };
            auto screen = [&](int sx, int sy) {
                if (!wide.gpu) return toRgba(top[sy * W3D + sx]);
                return PK_NDS_PLAN_SCREEN << 24 | (u32) (sx & 0xFF) | (u32) sy << 8 | (u32) (sx >> 8) << 16;
            };
            auto margin = [&](int x) {
                if (y >= 0 && y < H3D && (x < 0 ? blackL[y] : x >= W3D && blackR[y])) {
                    // The screen's own edge pixels as shown (brightness and fades included), the last two repeated so
                    // a dithered band (the field menu's bar) keeps its pattern.
                    int ex = x < 0 ? ((x % 2) + 2) % 2 : W3D - 2 + (x - (W3D - 2)) % 2;
                    row[x] = screen(ex, y);
                    return;
                }
                if (battleBg) {
                    // The first scenery sprite (in OAM order, as the 2D engine picks) with a pixel here: under the 3D,
                    // over the background unless that's above it (a sprite wins a tie with a BG).
                    u32 obj = 0;
                    int objPrio = 4;
                    bool objSemi = false;
                    for (int i = 0; i < numCrossing && !obj; i++) {
                        const Crossing& o = crossing[i];
                        if (x < o.x || x >= o.x + o.w || y < o.y || y >= o.y + o.h) continue;
                        obj = objPixel(nds, o.a0, o.a1, o.a2, o.w, o.h, x - o.x, y - o.y) & 0xFFFF;
                        objPrio = o.prio;
                        objSemi = ((o.a0 >> 10) & 3) == 1;
                    }
                    int side = x < 0 ? 0 : 1;
                    if (y >= 0 && y < H3D && carry[side][y]) {
                        row[x] = wide.gpu ? screen(side ? W3D - 1 : 0, carryRow[side][y]) : carry[side][y];
                        return;
                    }
                    u16 c = y >= 0 && y < H3D ? pk_nds_bg_pixel(nds, 3, x + bgx, y + bgy) : 0;
                    // The background, else the backdrop (its palette's colour 0), or a sprite over it - under the
                    // battle's 3D where it's drawn: its Pokemon slide in from past the screen's sides too.
                    u32 under = bg6(c ? c : bd);
                    bool objUnder = obj && objPrio <= (unitA.BGCnt[3] & 3);
                    if (objUnder) {
                        // A semi-transparent sprite blended onto the background where BLDCNT has it as the second target
                        u32 o6 = widen((u16) obj);
                        under = objSemi && (unitA.BlendCnt & 0x800) ? blend4(o6, under, compose.eva, compose.evb) : o6;
                    }
                    if (battle3D) {
                        // The 3D's alpha onto what's under it where BLDCNT has that (the sprite, BG3) as the second target
                        if (wide.gpu) {
                            row[x] = PK_NDS_PLAN_3D_ON << 24 | (objUnder ? PK_NDS_PLAN_UNDER_OBJ : 0) | under;
                            return;
                        }
                        u32 c3 = marginPixel(x, y);
                        if (c3 != NONE && (c3 >> 24)) {
                            bool target2 = (unitA.BlendCnt & (objUnder ? 0x1000 : 0x800)) != 0;
                            row[x] = shade(target2 ? blend5(c3, under) : c3 & 0x3F3F3F, mb);
                            return;
                        }
                    }
                    row[x] = color(shade(under, mb));
                    return;
                }
                if (threeDOnTop && wide.gpu) {
                    u16 o = compose.count ? overlayPixel(compose, x, y) : 0;
                    row[x] = o ? PK_NDS_PLAN_3D_OVER << 24 | widen(o) : PK_NDS_PLAN_3D << 24;
                    return;
                }
                row[x] = threeDOnTop ? composed(compose, x, y, marginPixel(x, y)) : color(off);
            };
            for (int x = wide.cx0; x < 0; x++) margin(x);
            if (y < 0 || y >= H3D)
                for (int x = 0; x < W3D; x++) margin(x);
            for (int x = W3D; x < wide.cx1; x++) margin(x);
        }
        // The frame's outer rows and columns: the DS outlines polygons where the screen cuts them (edge marking), a
        // dark line once the picture goes on past them. Taken from the slice beside them instead - where the next one
        // in shows the slice's own pixel, so it's the 3D layer there and not a text box or a sprite over it.
        if (threeDOnTop && !wide.gpu) {
            // The edge-marking colours as they come out on screen (ScanlineFinalPass's 5 -> 6 bit conversion): a frame
            // edge pixel showing one of them exactly is the outline. Where the slice shows texture a texel off, the
            // "next one in" test below fails on a busy texture, and the outline stayed as a dark dot.
            // Raw, opaque: put through composed() at the pixel tested (a layer blended over it there).
            u32 edgeColors[8];
            for (int i = 0; i < 8; i++) {
                u16 e = nds->GPU.GPU3D.RenderEdgeTable[i];
                u32 r = (e << 1) & 0x3E, g = (e >> 4) & 0x3E, b = (e >> 9) & 0x3E;
                edgeColors[i] = 0x1F000000u | (r ? r + 1 : 0) | (g ? g + 1 : 0) << 8 | (b ? b + 1 : 0) << 16;
            }
            bool edgeMarking = (nds->GPU.GPU3D.RenderDispCnt & (1 << 5)) != 0;
            auto fix = [&](int sx, int sy, int ex, int ey, int nx, int ny) {
                u32 cn = at(sx, sy, nx, ny), ce = at(sx, sy, ex, ey);
                if (cn == NONE || ce == NONE || !(cn >> 24) || !(ce >> 24)) return;
                u32 frame = toRgba(top[ey * W3D + ex]);
                bool outline = false;
                if (edgeMarking)
                    for (u32 c : edgeColors) outline |= composed(compose, ex, ey, c) == frame;
                if (!outline && !near(composed(compose, nx, ny, cn), toRgba(top[ny * W3D + nx]))) return;
                wide.out[(size_t) (ey + MY) * OW + MX + ex] = composed(compose, ex, ey, ce);
            };
            for (int side = -1; side <= 1; side += 2) {
                int ex = side < 0 ? 0 : W3D - 1, ey = side < 0 ? 0 : H3D - 1;
                if (sliceAt(side, 0).image.valid)
                    for (int y = 0; y < H3D; y++) fix(side, 0, ex, y, ex - side, y);
                if (sliceAt(0, side).image.valid)
                    for (int x = 0; x < W3D; x++) fix(0, side, x, ey, x, ey - side);
            }
            // Its corner pixels, outlined from both sides: from the corner slice, checked one in diagonally.
            for (int sy = -1; sy <= 1; sy += 2)
                for (int sx = -1; sx <= 1; sx += 2) {
                    int ex = sx < 0 ? 0 : W3D - 1, ey = sy < 0 ? 0 : H3D - 1;
                    fix(sx, sy, ex, ey, ex - sx, ey - sy);
                }
        }
    }
    if (draw && wide.gpu) {
        PkNdsWidePlan& p = wide.plan;
        p.valid = true;
        p.px = &wide.out[(size_t) MY * OW + MX];
        p.stride = OW;
        p.x0 = wide.cx0;
        p.x1 = wide.cx1;
        p.y0 = wide.cy0;
        p.y1 = wide.cy1;
        p.backdrop = compose.backdrop;
        p.blendCnt = compose.blendCnt;
        p.eva = compose.eva;
        p.evb = compose.evb;
        p.brightness = compose.mb;
        p.fx = compose.fx;
        p.fxTargets = (compose.fx3D ? 1u : 0u) | (compose.fxBackdrop ? 0x20u : 0u);
    }
    // The slices for the next frame: the lists swapped at this frame's VBlank are what the main renderer draws next.
    // Not for a frame no one will see: its slices would be drawn over before they're shown.
    wide.pool.jobs.clear();
    for (Slice& s : wide.slices) {
        if (s.verts[0].empty()) continue;
        std::swap(s.shown, s.image);   // the one just shown (pk_nds_wide_check); the other is drawn over
        s.image.valid = false;
        if (drawNext && s.active) wide.pool.jobs.push_back(&s);
    }
    drawSlices();
    return draw;
}

// Debugging (PK_WIDE_EDGES=1, nds_dump's widecheck): the composed frame's outer rows and columns against the slices'
// own pixels there - what's left far off after the outline fix.
static void edgeReport(NDS* nds)
{
    setupCompose(compose, nds, true);
    int left = 0, right = 0, topRow = 0, bottomRow = 0, printed = 0;
    auto test = [&](int sx, int sy, int x, int y, int& count) {
        u32 c = at(sliceAt(sx, sy).shown, sx, sy, x, y);
        if (c == NONE || !(c >> 24)) return;
        u32 got = wide.out[(size_t) (y + MY) * OW + MX + x], want = composed(compose, x, y, c);
        if (near(got, want)) return;
        count++;
        if (printed++ < 10) fprintf(stderr, "edge (%d, %d): shown %06X slice %06X\n", x, y, got & 0xFFFFFF, want & 0xFFFFFF);
    };
    for (int y = 0; y < H3D; y++) { test(-1, 0, 0, y, left); test(1, 0, W3D - 1, y, right); }
    for (int x = 0; x < W3D; x++) { test(0, -1, x, 0, topRow); test(0, 1, x, H3D - 1, bottomRow); }
    fprintf(stderr, "edges off: left %d right %d top %d bottom %d\n", left, right, topRow, bottomRow);
}

void pk_nds_wide_check(NDS* nds, const uint32_t* top, int* compared, int* differ, int* far)
{
    waitSlices();
    if (getenv("PK_WIDE_EDGES")) edgeReport(nds);
    *compared = *differ = *far = 0;
    if (!wide.on) return;
    // The frame's 3D under what the 2D engine blends onto it (a fog layer, the 3D's alpha), as the margins are drawn.
    setupCompose(compose, nds, true);
    auto check = [&](int sx, int sy, int x, int y) {
        u32 c = at(sliceAt(sx, sy).shown, sx, sy, x, y);
        if (c == NONE || !(c >> 24)) return;
        u32 want = composed(compose, x, y, c), got = toRgba(top[y * W3D + x]);
        (*compared)++;
        if (want != got) {
            bool isFar = !near(want, got);
            if (isFar) (*far)++;
            if (getenv("PK_WIDE_DEBUG") && isFar && *far < 40)
                fprintf(stderr, "slice (%d, %d) x %d y %d: slice %06X (raw %08X) frame %06X\n", sx, sy, x, y,
                    want & 0xFFFFFF, c, got & 0xFFFFFF);
            (*differ)++;
        }
    };
    // Past the screen's outline (its outer row / column) and the slice's (the overlap's innermost).
    for (int side = -1; side <= 1; side += 2) {
        for (int k = 1; k <= OVERLAP - 2; k++) {
            int x = side < 0 ? k : W3D - 1 - k, y = side < 0 ? k : H3D - 1 - k;
            for (int yy = 0; yy < H3D; yy++) check(side, 0, x, yy);
            for (int xx = 0; xx < W3D; xx++) check(0, side, xx, y);
        }
    }
}

void pk_nds_wide_view(uint32_t* out, int x0, int y0, int w, int h)
{
    for (int y = 0; y < h; y++) {
        int sy = y0 + y;
        u32* dst = out + (size_t) y * w;
        if (!wide.on || sy < wide.cy0 || sy >= wide.cy1) {
            std::fill(dst, dst + w, 0xFF000000u);
            continue;
        }
        const u32* src = &wide.out[(size_t) (sy + MY) * OW + MX];
        for (int x = 0; x < w; x++) {
            int sx = x0 + x;
            dst[x] = sx >= wide.cx0 && sx < wide.cx1 ? src[sx] : 0xFF000000u;
        }
    }
}

void pk_nds_wide_rect(int* x, int* y, int* w, int* h)
{
    *x = wide.cx0;
    *y = wide.cy0;
    *w = wide.cx1 - wide.cx0;
    *h = wide.cy1 - wide.cy0;
}

uint16_t pk_nds_bg_pixel(NDS* nds, int bg, int bx, int by)
{
    const GPU& gpu = nds->GPU;
    const Shown2D& u = shown(nds);
    u16 cnt = u.BGCnt[bg & 3];
    u32 disp = u.DispCnt;
    int size = cnt >> 14;
    int w = (size & 1) ? 512 : 256, h = (size & 2) ? 512 : 256;
    bx &= w - 1;
    by &= h - 1;
    u32 mapBase = 0x06000000u + ((disp >> 27) & 7) * 0x10000u + ((cnt >> 8) & 0x1F) * 0x800u;
    u32 charBase = 0x06000000u + ((disp >> 24) & 7) * 0x10000u + ((cnt >> 2) & 0xF) * 0x4000u;
    // The map is 32 x 32-tile blocks: right of the first, then (a 512-pixel-wide map) the two under them.
    int tx = bx >> 3, ty = by >> 3, block = 0;
    if (tx >= 32) { block += 1; tx -= 32; }
    if (ty >= 32) { block += w == 512 ? 2 : 1; ty -= 32; }
    u16 e = gpu.ReadVRAM_BG<u16>(mapBase + (u32) block * 0x800u + (u32) (ty * 32 + tx) * 2u);
    int px = bx & 7, py = by & 7;
    if (e & 0x400) px = 7 - px;
    if (e & 0x800) py = 7 - py;
    u32 tile = e & 0x3FF;
    u32 ci;
    if (cnt & 0x80) {
        ci = gpu.ReadVRAM_BG<u8>(charBase + tile * 64u + (u32) (py * 8 + px));
        if (!ci) return 0;
    } else {
        u8 b = gpu.ReadVRAM_BG<u8>(charBase + tile * 32u + (u32) (py * 4 + px / 2));
        ci = (px & 1) ? b >> 4 : b & 0xF;
        if (!ci) return 0;
        ci += (e >> 12) * 16u;
    }
    u16 c = (u16) (u.Palette[ci * 2] | (u.Palette[ci * 2 + 1] << 8));
    return (u16) (0x8000 | (c & 0x7FFF));
}

void pk_nds_wide_sync() { waitSlices(); }

void pk_nds_wide_counts(int out[8])
{
    int n = 0;
    for (int i = 0; i < 9; i++)
        if (i != 4) out[n++] = wide.slices[i].lastCount;
}

