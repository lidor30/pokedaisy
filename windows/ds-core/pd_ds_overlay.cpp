// The Windows DS core's overlay: the app's companion and quick menu over the running game. It draws through the same
// portable presentation the Linux DS core (linux/libretro-ds/) and the 3DS port use, so the two screens show what the
// app shows: the companion's tabs, its settings, the map in the player's own cartridge art. The DS reader itself
// (linux/dsperate/companion.cpp) is the Kotlin Gen4 / Gen5 readers' port and needs nothing from the engine but main RAM.
//
// The quick menu's PC, FLY and REPEL are the game's own: a job runs one of its functions from its main loop
// (app/src/main/cpp/nds/pk_nds_call.cpp, the same recipes the app uses), so the storage menu it opens, the flight it
// flies and the steps it arms are the game's, not ours.
#include "pd_ds_overlay.h"

#include "libretro.h"
#include "companion.h"
#include "companion_ui.h"
#include "data_gen.h"
#include "guide_view.h"
#include "layout.h"
#include "map_art.h"
#include "map_view.h"
#include "party_view.h"
#include "pd_canvas.h"
#include "pd_game.h"
#include "pd_ui.h"
#include "pk_nds_call.h"
#include "pk_nds_call_abi.h"
#include "pk_nds_call_memory.h"
#include "pk_nds_cores.h"
#include "pk_nds_field.h"
#include "sha1/sha1.hpp"
#include <cstddef>
extern "C" {
#include "hud.h"
#include "pd_icon.h"
#include "quick_menu.h"
}

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

DsCompanionUi shell;                       // the companion's tabs, settings and cursor state
DsCompanion snapshot;                      // what the save holds, read every twelve frames while it shows
DsMapArt map;                              // the game's map, from the cartridge
DsDexArt dexArt;                           // the dex pages, from the cartridge
std::vector<std::vector<uint32_t>> icons;  // party icons, from the cartridge
DsGame companionGame = DsGame::Unsupported;
const PkNdsFieldGame* fieldGame = nullptr; // where this game keeps its field, for the field's state and its functions
bool supported = false;                    // the exact ROM the DS companion's layout was checked on
bool soulSilver = false;
bool companionShown = false;               // the companion has the picture
bool quickShown = false;                   // the quick menu has it
int quickSelected = 0, quickW = 0, quickH = 0;
int holdFrames = 0;
int tick = 0;
const uint8_t* ram = nullptr;
size_t ramSize = 0;
std::vector<uint32_t> menu;                // the quick menu's own canvas (QUICK_CLEAR where nothing is drawn)

// The HUD over the field: the same pass the Linux DS core draws (hud_ds_draw, hud_ds_minimap), on the top screen's own
// 256x192, and the cursor the HUD shortcut puts on it. The scene policy is the Linux core's too: the field alone, lit,
// for a moment, owns the screen; a menu, a battle or a fade takes the HUD away.
DsScenePolicy scene;
std::vector<uint32_t> hudPixels;           // its canvas, QUICK_CLEAR where nothing is drawn
std::vector<std::vector<uint32_t>> hudItemIcons;
hud_hits hudHits {};
int hudFocus = -1;
int hudWild = -1;
int repelFull = 0;
int viewX = 0, viewY = 0;                  // the top screen's corner in the game picture
bool viewShown = false;                    // the top screen is the one in the picture
bool pointerDown = false;                  // last frame's mouse / touch press, for a click on the HUD or the menu
int frameFieldBits = 0;                    // this frame's field read, for the core's battle-wide picture

// The game's own jobs: the PC's storage menu, FLY's checks and its flight. One at a time, as the app runs them.
int pcJob = 0, flyJob = 0;
bool flyChecking = false;
const char* flyWhy = nullptr;
bool flyPicker = false;
int flySelected = 0, flySlot = -1;
uint32_t flyPersonality = 0;
struct FlyDestination { int x, y, header; const char* name; };
std::vector<FlyDestination> flyDestinations;

// REPEL: the three kinds the app offers, its pick-list and the answer of a use.
constexpr int repelItems[] = {79, 76, 77};
int repelChoices[3], repelCount = 0, repelSelected = 0;
std::string repelLabels[3];
bool repelPicker = false;

// An action's answer, drawn over the quick menu for two seconds (a frontend message is only logged).
std::string notice;
int noticeFrames = 0;

constexpr unsigned kAll = 0xFFFFu;
inline bool hit(unsigned pressed, unsigned id) { return (pressed & (1u << id)) != 0; }

void say(const char* text)
{
    notice = text;
    noticeFrames = 120;
}

bool field_free(int bits)
{
    return (bits & PK_NDS_FIELD_SCREEN) && !(bits & (PK_NDS_FIELD_LOCKED | PK_NDS_FIELD_BATTLE | PK_NDS_FIELD_MENU | PK_NDS_FIELD_TEXT));
}
bool field_free() { return field_free(pk_ndsm_field_bits()); }

// One colour over the whole screen (a blank top screen, both screens dark): the scene policy's cue for a fade or a menu.
bool one_colour(const uint32_t* pixels)
{
    if (!pixels) return true;
    const uint32_t first = pixels[0] & 0xFFFFFFu;
    for (int gy = 0; gy < 12; ++gy)
        for (int gx = 0; gx < 16; ++gx)
            if ((pixels[(gy * 16 + 8) * 256 + gx * 16 + 8] & 0xFFFFFFu) != first) return false;
    return true;
}

void refresh()
{
    if (companionGame == DsGame::Unsupported || !ram || !ramSize) {
        snapshot = DsCompanion {};
        return;
    }
    DsCompanion next = ds_read_companion(ram, ramSize, companionGame);
    // On the newer games a read can catch the save mid decryption: the last coherent one stays. The rows are reloaded,
    // but the guide's cursor, scroll and open row stay as the player left them (the companion resets the HERE page
    // itself when the map changes, so a reset here would only snap the page back every few frames).
    if (next.valid || companionGame == DsGame::Platinum) {
        snapshot = std::move(next);
        shell.guideLoadedPage = -1;
    }
    ds_map_update(map, snapshot);
    // PD_DS_OVERLAY: the snapshot as the companion sees it, when what it holds changes.
    static const bool log = std::getenv("PD_DS_OVERLAY") != nullptr;
    static int lastCount = -1;
    if (log && (int) snapshot.party.size() != lastCount) {
        lastCount = (int) snapshot.party.size();
        fprintf(stderr, "overlay: snapshot valid %d party %d icons %d map %dx%d trainer '%s'\n",
                (int) snapshot.valid, (int) snapshot.party.size(), (int) icons.size(), map.width, map.height,
                snapshot.trainer.c_str());
    }
    if (shell.mapX < 0) {
        shell.mapX = snapshot.mapX;
        shell.mapY = snapshot.mapY;
    }
}

void show_companion()
{
    quickShown = false;
    flyPicker = repelPicker = false;
    companionShown = true;
    holdFrames = 0;
    refresh();
    shell.mapX = snapshot.mapX;
    shell.mapY = snapshot.mapY;
    shell.frame.overlay = PD_OVERLAY_NONE;
    shell.frame.moreOpened = false;
    shell.picker = -1;
}

// A frame of the game, stretched to cover the overlay (the wide canvas already has the window's shape, so that is a
// straight copy two pixels over; a stacked canvas keeps the middle of its top screen instead of squashing).
void cover(const std::vector<uint32_t>& from, int fromW, int fromH, std::vector<uint32_t>& to, int w, int h)
{
    const double scale = std::max((double) w / fromW, (double) h / fromH);
    const double offX = (fromW - w / scale) / 2, offY = (fromH - h / scale) / 2;
    for (int y = 0; y < h; ++y) {
        const int sy = std::clamp((int) (offY + y / scale), 0, fromH - 1);
        for (int x = 0; x < w; ++x) {
            const int sx = std::clamp((int) (offX + x / scale), 0, fromW - 1);
            const uint32_t p = from[(size_t) sy * fromW + sx];
            // Dimmed over the menu, as the app dims the game behind the quick menu.
            to[(size_t) y * w + x] = 0xFF000000u | ((((p >> 16) & 0xFFu) * 150 / 255) << 16)
                                                  | ((((p >> 8) & 0xFFu) * 150 / 255) << 8)
                                                  | (((p & 0xFFu) * 150) / 255);
        }
    }
}

// The bag item of a Repel kind, its pocket, and how many there are: what REPEL offers.
const DsItem* bag_item(int id)
{
    const auto item = std::find_if(snapshot.bag.begin(), snapshot.bag.end(), [&](const DsItem& i) {
        return i.id == id && i.pocket == 0 && i.count > 0;
    });
    return item == snapshot.bag.end() ? nullptr : &*item;
}

bool has_flyer()
{
    return std::any_of(snapshot.party.begin(), snapshot.party.end(), [](const DsMon& mon) {
        return !mon.egg && std::find(mon.moves.begin(), mon.moves.end(), 19) != mon.moves.end();
    });
}

bool fly_supported()
{
    // Gen 4 flies to its spawn ids; Gen 5 to the towns the save has arrived in (the map points and their flags).
    return pk_nds_call_supported(fieldGame) &&
           ((fieldGame && fieldGame->gen == 5) || !ds_fly_spots(companionGame).empty());
}

// Gen 5's destinations: every town whose arrival flag the save has set, as the app lists them (Gen5Field).
void gen5_destinations(const DsCompanion& current)
{
    const bool sequel = ds_unova_sequel(companionGame);
    const int first = sequel ? 2469 : 2480;
    auto add = [&](const auto& point) {
        const int kind = point[4], bit = point[5] - first;
        if ((kind != 0 && kind != 3 && kind != 5) || bit < 0 || bit >= 62) return;
        if (!(current.arrivals >> bit & 1)) return;
        flyDestinations.push_back(FlyDestination {point[2], point[3], point[0], ds_place(point[0], companionGame)});
    };
    if (sequel)
        for (const auto& point : pd_ds_data::sequelMapPoints) add(point);
    else
        for (const auto& point : pd_ds_data::unovaMapPoints) add(point);
}

// PC: the storage menu, as a Pokémon Center's PC opens it.
void open_pc()
{
    if (!field_free() || !pk_nds_call_supported(fieldGame)) {
        say("PC is unavailable here.");
        return;
    }
    const uint32_t args[4] = {};
    pcJob = pk_ndsm_action(PK_NDS_ACTION_PC, args);
    if (!pcJob) {
        say("PC is unavailable here.");
        return;
    }
    quickShown = false;
    holdFrames = 0;
}

void update_pc()
{
    if (!pcJob) return;
    const int status = pk_ndsm_action_status(pcJob);
    if (!status) return;
    uint32_t results[PK_NDS_MAX_RESULTS] = {};
    if (status < 0 || (fieldGame && fieldGame->gen == 5 && (!pk_ndsm_action_results(pcJob, results) || !results[1])))
        say("PC could not open here.");
    pcJob = 0;
}

// FLY: the party menu's own check first (may this place fly, which towns are unlocked), then the flight itself.
void open_fly()
{
    if (!field_free()) {
        say("Fly is available in the overworld.");
        return;
    }
    const DsCompanion current = ds_read_companion(ram, ramSize, companionGame);
    if (!current.valid) {
        say("Party data is unavailable.");
        return;
    }
    const auto flyer = std::find_if(current.party.begin(), current.party.end(), [](const DsMon& mon) {
        return !mon.egg && std::find(mon.moves.begin(), mon.moves.end(), 19) != mon.moves.end();
    });
    if (flyer == current.party.end()) {
        say("No one in your party knows Fly.");
        return;
    }
    flySlot = int(flyer - current.party.begin());
    flyPersonality = flyer->personality;
    flyDestinations.clear();
    const auto& spots = ds_fly_spots(companionGame);
    const uint32_t args[] = {fieldGame && fieldGame->gen != 5 ? uint32_t(ds_fly_spots(companionGame).size()) : 0u, 0,
                             0, 0};
    flyJob = pk_ndsm_action(PK_NDS_ACTION_FLY_CHECK, args);
    if (!flyJob) {
        say("Fly is unavailable here.");
        return;
    }
    flyChecking = true;
    quickShown = false;
    holdFrames = 0;
}

void update_fly()
{
    if (!flyJob) return;
    const int status = pk_ndsm_action_status(flyJob);
    if (!status) return;
    uint32_t results[PK_NDS_MAX_RESULTS] = {};
    const bool ok = status > 0 && pk_ndsm_action_results(flyJob, results);
    flyJob = 0;
    if (!flyChecking) {
        if (!ok || (fieldGame && fieldGame->gen == 5 && !results[1])) say("Fly could not start here.");
        return;
    }
    flyChecking = false;
    if (!ok || results[1]) {
        const char* message = "Fly is unavailable here.";
        if (ok && fieldGame && fieldGame->gen != 5 && results[1] == 2) message = "You need the badge to use Fly.";
        if (ok && fieldGame && fieldGame->gen != 5 && results[1] == 3) message = "You cannot Fly with a partner.";
        flyWhy = message;
        quickShown = true;
        return;
    }
    const DsCompanion current = ds_read_companion(ram, ramSize, companionGame);
    if (fieldGame && fieldGame->gen == 5) {
        gen5_destinations(current);
    } else {
        const auto& spots = ds_fly_spots(companionGame);
        for (size_t i = 0; i < spots.size(); ++i) {
            const auto& spot = spots[i];
            const bool open = companionGame == DsGame::Platinum
                                  ? i + 2 < PK_NDS_MAX_RESULTS && results[i + 2] != 0
                                  : ds_fly_unlocked(current, spot);
            if (open) flyDestinations.push_back({spot.x, spot.z, spot.header, ds_place(spot.nameMap, companionGame)});
        }
    }
    if (flyDestinations.empty()) {
        flyWhy = "No Fly destinations unlocked";
        quickShown = true;
        return;
    }
    flySelected = 0;
    flyPicker = quickShown = true;
}

void confirm_fly()
{
    if (!field_free()) {
        say("Fly is unavailable here.");
        return;
    }
    const DsCompanion current = ds_read_companion(ram, ramSize, companionGame);
    if (flySlot < 0 || size_t(flySlot) >= current.party.size()) {
        say("Party data is unavailable.");
        return;
    }
    const DsMon& flyer = current.party[flySlot];
    if (flyer.personality != flyPersonality || flyer.egg ||
        std::find(flyer.moves.begin(), flyer.moves.end(), 19) == flyer.moves.end()) {
        say("Your Fly user has changed.");
        return;
    }
    const FlyDestination& spot = flyDestinations[flySelected];
    const uint32_t args[] = {uint32_t(flySlot), uint32_t(spot.header),
                             fieldGame && fieldGame->gen != 5 ? uint32_t(spot.x) : 0u,
                             fieldGame && fieldGame->gen != 5 ? uint32_t(spot.y) : 0u};
    flyJob = pk_ndsm_action(PK_NDS_ACTION_FLY, args);
    if (!flyJob) {
        say("Fly is unavailable here.");
        return;
    }
    flyPicker = quickShown = false;
}

// REPEL: the kinds in the bag, then the game's own use of the one picked.
void open_repel()
{
    if (!field_free()) {
        say("Repel is available in the overworld.");
        return;
    }
    refresh();
    if (snapshot.repelSteps) {
        say("Repel is still active.");
        return;
    }
    repelCount = 0;
    for (const int id : repelItems) {
        const DsItem* item = bag_item(id);
        if (!item) continue;
        repelChoices[repelCount] = id;
        repelLabels[repelCount++] = std::string(ds_item(id, companionGame)) + " x" + std::to_string(item->count);
    }
    if (!repelCount) {
        say("No Repel in the Bag.");
        return;
    }
    repelPicker = true;
    repelSelected = 0;
}

void use_repel()
{
    using R = DsRepelResult;
    const R result = field_free()
                         ? ds_use_repel(ram, ramSize, companionGame, repelChoices[repelSelected],
                                        [](uint32_t addr, uint8_t value) { pk_ndsm_write(addr, &value, 1); })
                         : R::NotAvailable;
    if (result == R::Used) refresh();
    say(result == R::Used         ? "Repel used."
        : result == R::StillActive ? "Repel is still active."
        : result == R::NoneLeft    ? "No Repel in the Bag."
                                   : "Repel is unavailable here.");
}

// The quick menu's tiles: the game's own PC, FLY and REPEL where it has them, WILD ENCOUNTERS and the companion.
int quick_items(quick_item* items)
{
    int count = 0;
    const bool field = field_free();
    const char* fieldWhy = field ? nullptr : "Overworld only";
    const bool canCall = pk_nds_call_supported(fieldGame);
    const bool hasRepel = std::any_of(std::begin(repelItems), std::end(repelItems),
                                      [](int id) { return bag_item(id) != nullptr; });
    if (canCall) items[count++] = quick_item {QUICK_PC, false, false, fieldWhy, 0, 0, nullptr};
    if (fly_supported())
        items[count++] = quick_item {QUICK_FLY, false, false,
                                     fieldWhy ? fieldWhy : !has_flyer() ? "No party member knows Fly" : flyWhy,
                                     0, 0, nullptr};
    if (companionGame != DsGame::Unsupported)
        items[count++] = quick_item {QUICK_REPEL, false, false,
                                     fieldWhy ? fieldWhy : snapshot.repelSteps ? "Repel still active"
                                                                               : !hasRepel ? "No Repel in the Bag"
                                                                                           : nullptr,
                                     0, 0, nullptr};
    items[count++] = quick_item {QUICK_WILD, shell.settings.wildOff != 0, false, fieldWhy, 0, 0, nullptr};
    items[count++] = quick_item {QUICK_SAVE_STATE, false, false, nullptr, 0, 0, nullptr};
    items[count++] = quick_item {QUICK_LOAD_STATE, false, false,
                                 overlay_state_exists() ? nullptr : "No state saved yet", 0, 0, nullptr};
    items[count++] = quick_item {QUICK_FF, false, overlay_fast_forward(), nullptr, 0, 0, nullptr};
    if (hudHits.n > 0) items[count++] = quick_item {QUICK_HUD, false, false, nullptr, 0, 0, nullptr};
    items[count++] = quick_item {QUICK_COMPANION, false, false, nullptr, 0, 0, nullptr};
    return count;
}

// A slate tab under the top edge: what an action answered.
void draw_notice(uint32_t* out, int w, int h)
{
    pd_canvas c;
    pd_canvas_init(&c, w, h, out);
    const int scale = 2 * pd_text_width(notice.c_str()) <= w - 32 ? 2 : 1;
    const int boxW = std::min(w - 8, scale * pd_text_width(notice.c_str()) + 24), boxH = 16 * scale + 8;
    const int x = (w - boxW) / 2, y = 8;
    pd_round_rect(&c, x, y, boxW, boxH, 4, 0x182028);
    pd_round_rect(&c, x + 2, y + 2, boxW - 4, boxH - 4, 3, 0xf8f8f8);
    pd_round_rect(&c, x + 4, y + 4, boxW - 8, boxH - 8, 2, 0x283848);
    pd_clip(&c, x + 6, y + 4, boxW - 12, boxH - 8);
    pd_text_scaled(&c, x + 12, y + 4 - scale, notice.c_str(), 0xf8f8f8, 0x101820, scale);
    pd_unclip(&c);
}

// A HUD target the cursor picked: a party slot's summary, the map, the bag, the guide's HERE, the game's PC, or the
// companion itself, as the app's FieldHud does.
// A tile's action: what A on the selected tile (or a click on it) does.
void quick_activate()
{
    quick_item items[10] {};
    const int count = quick_items(items);
    if (count <= 0) return;
    quickSelected = std::clamp(quickSelected, 0, count - 1);
    const quick_item& item = items[quickSelected];
    if (item.why) return;
    switch (item.action) {
    case QUICK_PC: open_pc(); break;
    case QUICK_FLY: open_fly(); break;
    case QUICK_REPEL: open_repel(); break;
    case QUICK_WILD:
        shell.settings.wildOff = !shell.settings.wildOff;
        shell.save();
        pk_ndsm_set_wild_off(shell.settings.wildOff);
        quickShown = false;
        break;
    case QUICK_SAVE_STATE:
        quickShown = false;
        say(overlay_state_save() ? "State saved" : "State save failed");
        break;
    case QUICK_LOAD_STATE:
        quickShown = false;
        say(overlay_state_load() ? "State loaded" : "State load failed");
        break;
    case QUICK_FF: {
        quickShown = false;
        const bool on = !overlay_fast_forward();
        say(overlay_set_fast_forward(on) ? (on ? "Fast forward on" : "Fast forward off") : "Fast forward unavailable");
        break;
    }
    case QUICK_HUD:
        hudFocus = hud_first(&hudHits);
        quickShown = false;
        break;
    case QUICK_COMPANION: show_companion(); break;
    default: break;
    }
}

void hud_open_target(int target)
{
    if (target == HUD_PC) {
        open_pc();
        return;
    }
    if (target == HUD_COMPANION) {
        show_companion();
        return;
    }
    refresh();
    if (!snapshot.valid) return;
    show_companion();
    if (target >= HUD_SLOT && target < HUD_SLOT + int(snapshot.party.size())) {
        shell.frame.tab = PD_TAB_PARTY;
        shell.selected = target - HUD_SLOT;
        shell.summary = true;
        shell.page = shell.learnRow = 0;
    } else if (target == HUD_MAP || target == HUD_MAP_BUTTON) {
        shell.frame.tab = PD_TAB_MAP;
        shell.mapX = snapshot.mapX;
        shell.mapY = snapshot.mapY;
    } else if (target == HUD_WILD) {
        shell.frame.tab = PD_TAB_GUIDE;
        shell.frame.guidePage = int(GuidePage::Here);
        shell.guideLoadedPage = -1;
        pd_ui_guide_reset(&shell.frame);
    } else if (target == HUD_BALLS || target == HUD_REPEL || target == HUD_BAG) {
        shell.frame.tab = PD_TAB_BAG;
        shell.pocket = shell.scroll = 0;
    }
}

// The DS HUD over the field: the party row, the minimap, the Poké Balls and Repel steps left, and the PC / BAG / WILD /
// COMPANION buttons, drawn at 256x192 and blended over the top screen's pixels in the picture (viewX / viewY). The scene
// policy keeps it to a calm, lit overworld, and the settings say whether it shows at all.
void draw_hud(std::vector<uint32_t>& frame, int w, int h)
{
    if (!viewShown || !shell.settings.hud || companionGame == DsGame::Unsupported || !scene.hud || scene.dark ||
        !field_free() || viewX < 0 || viewY < 0 || viewX + PK_NDS_W > w || viewY + PK_NDS_H > h) {
        hudHits.n = 0;
        hudFocus = -1;
        return;
    }
    if (!snapshot.valid) refresh();
    if (!snapshot.valid) {
        hudHits.n = 0;
        hudFocus = -1;
        return;
    }

    pd_snapshot party {};
    party.valid = true;
    party.partyCount = std::min<int>(PD_PARTY_SIZE, (int) snapshot.party.size());
    for (int i = 0; i < party.partyCount; ++i) {
        const DsMon& mon = snapshot.party[i];
        pd_mon& out = party.party[i];
        out.species = mon.egg ? PD_SPECIES_EGG : mon.species;
        out.level = mon.level;
        out.hp = mon.hp;
        out.maxHp = mon.maxHp;
        out.status = mon.status;
    }
    // The balls in the bag: Unova keeps them in Items, the earlier games in their own pocket.
    party.bagOk = true;
    auto& balls = party.bag[PD_POCKET_BALLS];
    for (const DsItem& item : snapshot.bag) {
        const bool unovaBall = (item.id >= 1 && item.id <= 16) || (item.id >= 492 && item.id <= 500) || item.id == 576;
        const bool ball = ds_unova(companionGame) ? unovaBall : item.pocket == 6;
        if (ball && balls.count < PD_POCKET_SLOTS)
            balls.items[balls.count++] = {uint16_t(item.id), uint16_t(item.count)};
    }
    const int steps = snapshot.repelSteps;
    if (!steps) repelFull = 0;
    else if (steps > repelFull) repelFull = hud_repel_full(steps);
    // WILD's badge: this map's species the dex hasn't caught yet (0 = all caught, and the number is what it counts from).
    const DsGuideHere encounters = ds_guide_here(snapshot, soulSilver);
    hudWild = encounters.count > 0 ? 0 : -1;
    if (hudWild >= 0) {
        bool counted[650] {};
        for (int i = 0; i < encounters.count; ++i) {
            const unsigned id = encounters.entries[i].species;
            if (!id || id >= 650 || counted[id]) continue;
            counted[id] = true;
            if (id >= snapshot.dexCaught.size() || !snapshot.dexCaught[id]) ++hudWild;
        }
    }

    hudPixels.assign((size_t) PK_NDS_W * PK_NDS_H, QUICK_CLEAR);
    pd_canvas c;
    pd_canvas_init(&c, PK_NDS_W, PK_NDS_H, hudPixels.data());
    hud_state state {steps, repelFull, pk_nds_call_supported(fieldGame), hudWild, hudFocus};
    state.mini = shell.settings.hudSize != PD_HUD_LARGE;
    state.repelItem = 79;
    struct HudIcons { const pd_snapshot* party; bool small; };
    HudIcons hudIcons {&party, false};
    hud_ds_draw(&c, &party, &state, &shell.settings, &hudIcons,
                [](pd_canvas* canvas, void* ctx, const pd_mon* mon, int x, int y, bool) {
                    const HudIcons& hud = *static_cast<HudIcons*>(ctx);
                    const int slot = int(mon - hud.party->party);
                    if (slot < 0 || slot >= hud.party->partyCount) return;
                    const DsMon& captured = snapshot.party[slot];
                    const int size = hud.small ? 20 : 32;
                    if (hud.small) { x += 6; y += 5; }
                    const int id = captured.egg ? ds_egg_icon(companionGame) : captured.species;
                    if (id < 0 || size_t(id) >= icons.size() || icons[id].size() != 1024) {
                        if (companionGame != DsGame::Platinum) {
                            const char number[] = {char('1' + slot), 0};
                            pd_text(canvas, x + (size - 8) / 2, y + 8, number, 0xffffff, 0x282828);
                        }
                        return;
                    }
                    const std::vector<uint32_t>& art = icons[id];
                    for (int j = 0; j < size; ++j)
                        for (int i = 0; i < size; ++i) {
                            const uint32_t p = art[(j * 32 / size) * 32 + i * 32 / size];
                            if (p >> 24) pd_fill(canvas, x + i, y + j, 1, 1, p & 0xFFFFFFu);
                        }
                },
                [](pd_canvas* canvas, int id, int x, int y) {
                    if (id < 0 || size_t(id) >= hudItemIcons.size() || hudItemIcons[id].empty()) return false;
                    const std::vector<uint32_t>& art = hudItemIcons[id];
                    for (int row = 0; row < PD_ITEM_ICON; ++row)
                        for (int col = 0; col < PD_ITEM_ICON; ++col) {
                            const uint32_t p = art[(row * 32 / PD_ITEM_ICON) * 32 + col * 32 / PD_ITEM_ICON];
                            if (p >> 24) pd_fill(canvas, x + col, y + row, 1, 1, p & 0xFFFFFFu);
                        }
                    return true;
                },
                79, false, PK_NDS_W, &hudHits, &ds_party_palette(companionGame));
    const DsMapPosition player = ds_map_position(snapshot);
    if (!(shell.settings.hudHidden & (1 << PD_HUD_MAP)) && !map.background.empty() && player.x >= 0 && player.y >= 0)
        hud_ds_minimap(&c, &shell.settings, ds_place(snapshot.map, companionGame),
                       [](pd_canvas* canvas, int x, int y, int mw, int mh) { ds_draw_minimap(canvas, snapshot, map, x, y, mw, mh); },
                       &hudHits);
    hud_focus_draw(&c, &hudHits, hudFocus);
    if (hudHits.n <= 0) hudFocus = -1;
    // Over the picture: a HUD pixel's alpha is how much of it the game shows through, as the quick menu's.
    for (int y = 0; y < PK_NDS_H; ++y)
        for (int x = 0; x < PK_NDS_W; ++x) {
            const uint32_t p = hudPixels[(size_t) y * PK_NDS_W + x];
            if (p == QUICK_CLEAR) continue;
            uint32_t& old = frame[(size_t) (viewY + y) * w + viewX + x];
            // ARGB where 0 means opaque (hud.c draws its icons in 24-bit colours), as the Linux core's blend reads it.
            const unsigned a = (p >> 24) ? (p >> 24) : 255;
            if (a >= 255) old = p;
            else {
                const unsigned inv = 255 - a;
                old = 0xFF000000u | (((((p >> 16) & 0xFFu) * a + ((old >> 16) & 0xFFu) * inv) / 255) << 16)
                                   | (((((p >> 8) & 0xFFu) * a + ((old >> 8) & 0xFFu) * inv) / 255) << 8)
                                   | ((((p & 0xFFu) * a + (old & 0xFFu) * inv) / 255));
            }
        }
}

} // namespace

void overlay_load(const char* romPath, const char* saveDir)
{
    overlay_reset();
    ram = pk_ndsm_main_ram();
    ramSize = pk_ndsm_main_ram_size();

    std::vector<uint8_t> rom;
    if (FILE* f = romPath ? fopen(romPath, "rb") : nullptr) {
        fseek(f, 0, SEEK_END);
        const long size = ftell(f);
        fseek(f, 0, SEEK_SET);
        if (size > 0) {
            rom.resize((size_t) size);
            if (fread(rom.data(), 1, rom.size(), f) != rom.size()) rom.clear();
        }
        fclose(f);
    }
    if (rom.size() >= 512) {
        unsigned char digest[20];
        SHA1_CTX context;
        SHA1Init(&context);
        SHA1Update(&context, rom.data(), (uint32_t) rom.size());
        SHA1Final(digest, &context);
        char hex[41];
        for (int i = 0; i < 20; ++i) std::snprintf(hex + 2 * i, 3, "%02x", digest[i]);
        const char* id = ds_canonical_sha1(hex);
        supported = std::strcmp(id, pd_ds_data::sha1) == 0;
        soulSilver = std::strcmp(id, pd_ds_data::soulSilverSha1) == 0;
        companionGame = ds_companion_game(id);
        fieldGame = pk_nds_field_game_rom(rom.data(), (uint32_t) rom.size());
        // PD_DS_OVERLAY: which ROM the companion took, and the digest it read (a dump it doesn't know gets no tabs).
        static const bool log = std::getenv("PD_DS_OVERLAY") != nullptr;
        if (log)
            fprintf(stderr, "overlay: sha1 %s companion %d field %s\n", id, (int) companionGame,
                    fieldGame ? fieldGame->name : "none");
        if (companionGame != DsGame::Unsupported) {
            map = ds_map_art(rom, companionGame);
            icons = ds_party_icons(rom, companionGame);
            dexArt = ds_dex_art(rom, companionGame);
            hudItemIcons = ds_hud_item_icons(rom, companionGame);
        }
    }
    shell.init(saveDir ? saveDir : "", 0, false);
    shell.set_game(companionGame, soulSilver);
    shell.dexArt = std::move(dexArt);
    pk_ndsm_set_wild_off(shell.settings.wildOff);
    refresh();
}

void overlay_reset()
{
    companionShown = quickShown = false;
    holdFrames = 0;
    quickSelected = 0;
    quickW = quickH = 0;
    snapshot = DsCompanion {};
    tick = 0;
    pcJob = flyJob = 0;
    flyChecking = false;
    flyWhy = nullptr;
    flyPicker = repelPicker = false;
    flyDestinations.clear();
    flySlot = -1;
    repelCount = repelSelected = 0;
    noticeFrames = 0;
    notice.clear();
    scene.reset();
    hudPixels.clear();
    hudHits.n = 0;
    hudFocus = -1;
    hudWild = -1;
    repelFull = 0;
    frameFieldBits = 0;
}

bool overlay_open() { return companionShown; }

bool overlay_menu_open() { return companionShown || quickShown; }

bool overlay_touch_screen()
{
    return scene.touch && !scene.bottomBlank;
}

bool overlay_battle()
{
    return (frameFieldBits & PK_NDS_FIELD_BATTLE) != 0;
}

void overlay_view(int topX, int topY, bool topShown)
{
    viewX = topX;
    viewY = topY;
    viewShown = topShown;
}

bool overlay_pointer(int x, int y, bool down)
{
    // PD_DS_POINTER: the presses as the overlay reads them, for a frontend whose mouse can't be felt on the window.
    static const bool log = std::getenv("PD_DS_POINTER") != nullptr;
    if (log && down && !pointerDown)
        fprintf(stderr, "overlay pointer: %d,%d hud %d view %d,%d hit %d,%d quick %d\n", x, y, (int) hudHits.n, viewX,
                viewY, (int) (x - viewX), (int) (y - viewY), (int) quickShown);
    const bool press = down && !pointerDown;
    bool consumed = false;
    // The companion has the picture and the pointer: every press is its own (a tab, a row, a button, the gear), and the
    // game's touch screen never sees one.
    if (companionShown) {
        shell.touch(x, y, down);
        pointerDown = down;
        return true;
    }
    // The quick menu has the picture and the pointer: a tile runs it, a list row picks it, a click elsewhere closes the
    // menu (as the app's tap anywhere else does).
    if (quickShown) {
        consumed = true;
        if (press) {
            if (repelPicker) {
                const int row = quick_list_hit(x, y);
                if (row >= 0) {
                    repelSelected = std::clamp(row, 0, std::max(0, repelCount - 1));
                    use_repel();
                    repelPicker = quickShown = false;
                } else {
                    repelPicker = false;
                }
            } else if (flyPicker) {
                const int row = quick_list_hit(x, y);
                if (row >= 0) {
                    flySelected = std::clamp(row, 0, std::max(0, int(flyDestinations.size()) - 1));
                    confirm_fly();
                } else {
                    flyPicker = false;
                }
            } else {
                const int tile = quick_hit(x, y);
                if (tile >= 0) {
                    quickSelected = tile;
                    quick_activate();
                } else {
                    quickShown = false;
                }
            }
        }
    } else if (!companionShown && hudHits.n > 0 && viewShown && x >= viewX && x < viewX + PK_NDS_W &&
               y >= viewY && y < viewY + PK_NDS_H) {
        // The HUD's own pixels take the click (and the touch screen doesn't): its hit boxes are in the top screen's
        // coordinate space, the pointer in the picture's.
        consumed = true;
        if (press) {
            const int hx = x - viewX, hy = y - viewY;
            for (int i = 0; i < hudHits.n; ++i) {
                const auto& at = hudHits.at[i];
                if (hx >= at.x && hx < at.x + at.w && hy >= at.y && hy < at.y + at.h) {
                    hudFocus = -1;
                    hud_open_target(at.id);
                    break;
                }
            }
        }
    }
    pointerDown = down;
    return consumed;
}

bool overlay_wheel(int notches)
{
    if (!notches) return false;
    if (companionShown) {
        shell.wheel(notches);
        return true;
    }
    return false;
}

void overlay_tick(int fieldBits)
{
    frameFieldBits = fieldBits;
    if (noticeFrames) --noticeFrames;
    update_pc();
    update_fly();
    // Windows has no battle strip, so the game's own command and move panels float in with the rest of its touch
    // screens, as they do with the app's ONE SCREEN layout.
    scene.tick(fieldBits, one_colour(pk_ndsm_top()), one_colour(pk_ndsm_bottom()), true);
    ++tick;
    const bool hudUp = shell.settings.hud && scene.hud && !scene.dark && companionGame != DsGame::Unsupported;
    if ((companionShown || quickShown || hudUp) && (tick % 12) == 0) refresh();
    if (hudUp && !snapshot.valid) refresh();
    if (!hudUp) {
        hudFocus = -1;
        hudHits.n = 0;
    }
}

bool overlay_compose(std::vector<uint32_t>& frame, int& w, int& h)
{
    if (!companionShown && !quickShown) {
        // No overlay: the HUD alone goes over the field, on the top screen's own pixels.
        draw_hud(frame, w, h);
        // SAVE STATE / LOAD STATE close the ring: their answer still goes over the game for a moment.
        if (noticeFrames) draw_notice(frame.data(), w, h);
        return false;
    }
    const int gameW = w, gameH = h;
    // The overlay keeps the window's shape. The companion is drawn at the height its layout was built for (341x256, the
    // Linux DS core's), where the party grid and the tab bar fit; the quick menu has the wider 4:3 it lays its ring out
    // in. The frontend scales either up to the window.
    const int baseH = companionShown ? 256 : 384;
    const int baseW = gameH == 192 ? std::max(companionShown ? 341 : 512, (int) llround((double) baseH * gameW / gameH))
                                   : (companionShown ? 341 : 512);
    const int outW = baseW, outH = baseH;
    std::vector<uint32_t> picture;
    picture.swap(frame);   // the game's frame (frame is empty now)

    frame.assign((size_t) outW * outH, 0xFF000000u);
    if (companionShown) {
        pd_canvas c;
        pd_canvas_init(&c, outW, outH, frame.data());
        shell.draw(&c, snapshot, companionGame != DsGame::Unsupported, map, icons);
        w = outW;
        h = outH;
        return true;
    }
    cover(picture, gameW, gameH, frame, outW, outH);
    menu.assign((size_t) outW * outH, QUICK_CLEAR);
    pd_canvas c;
    pd_canvas_init(&c, outW, outH, menu.data());
    pd_game presentation {};
    quick_item items[10] {};
    const int count = quick_items(items);
    quick_set_grid(shell.settings.quickGrid != 0);
    bool drawn = false;
    if (repelPicker) {
        const char* labels[3] = {repelLabels[0].c_str(), repelLabels[1].c_str(), repelLabels[2].c_str()};
        quick_list_draw(&c, "REPEL", labels, repelCount, repelSelected, shell.settings.hudLook);
        drawn = true;
    } else if (flyPicker) {
        std::vector<const char*> labels;
        for (const auto& destination : flyDestinations) labels.push_back(destination.name);
        quick_list_draw(&c, "FLY TO", labels.data(), int(labels.size()), flySelected, shell.settings.hudLook);
        drawn = true;
    } else if (count > 0) {
        quickSelected = std::clamp(quickSelected, 0, count - 1);
        drawn = quick_draw(&c, &presentation, items, count, quickSelected, shell.settings.hudLook);
    }
    if (drawn) {
        quick_finish(&c);
        quickW = outW;
        quickH = outH;
        for (size_t i = 0; i < menu.size(); ++i) {
            const uint32_t p = menu[i];
            if (p == QUICK_CLEAR) continue;
            const unsigned a = p >> 24;
            uint32_t& old = frame[i];
            if (a >= 255) old = p;
            else {
                const unsigned inv = 255 - a;
                old = 0xFF000000u | (((((p >> 16) & 0xFFu) * a + ((old >> 16) & 0xFFu) * inv) / 255) << 16)
                                   | (((((p >> 8) & 0xFFu) * a + ((old >> 8) & 0xFFu) * inv) / 255) << 8)
                                   | ((((p & 0xFFu) * a + (old & 0xFFu) * inv) / 255));
            }
        }
    }
    if (noticeFrames) draw_notice(frame.data(), outW, outH);
    w = outW;
    h = outH;
    return true;
}

unsigned overlay_keys(unsigned pressed, unsigned heldKeys)
{
    // L3 (the desktop's QUICK MENU button): a press opens the ring, another closes it. L2 keeps its tap for the
    // companion and hold for the quick menu.
    if (hit(pressed, RETRO_DEVICE_ID_JOYPAD_L3)) {
        if (quickShown) {
            quickShown = false;
            flyPicker = repelPicker = false;
            holdFrames = 0;
            return kAll;
        }
        if (!companionShown) {
            hudFocus = -1;
            quickShown = true;
            quickSelected = 0;
            holdFrames = 0;
            flyPicker = repelPicker = false;
            refresh();
            return kAll;
        }
    }
    // The companion button the settings name (L2 to start with): a tap opens the companion, held for a moment the quick
    // menu. R2 stays the screen switch, as the host's keys are told.
    // The HUD cursor (hud.h's targets): its button puts the cursor on the first one, the arrows move it, A opens that
    // party slot / the map / the bag / the PC / the companion and B puts it away. While it is up the game gets nothing.
    const int hudButton = shell.hud_id();
    if (hudFocus >= 0) {
        if (hit(pressed, RETRO_DEVICE_ID_JOYPAD_B) || hit(pressed, RETRO_DEVICE_ID_JOYPAD_A) ||
            (hudButton >= 0 && hit(pressed, (unsigned) hudButton))) {
            const int target = hudFocus;
            hudFocus = -1;
            if (hit(pressed, RETRO_DEVICE_ID_JOYPAD_A) && !hit(pressed, RETRO_DEVICE_ID_JOYPAD_B)) hud_open_target(target);
        } else {
            const int dx = (int) hit(pressed, RETRO_DEVICE_ID_JOYPAD_RIGHT) - (int) hit(pressed, RETRO_DEVICE_ID_JOYPAD_LEFT);
            const int dy = (int) hit(pressed, RETRO_DEVICE_ID_JOYPAD_DOWN) - (int) hit(pressed, RETRO_DEVICE_ID_JOYPAD_UP);
            if (dx || dy) hudFocus = hud_step(&hudHits, hudFocus, dx, dy);
        }
        return kAll;
    }
    if (!companionShown && !quickShown && hudButton >= 0 && hudHits.n > 0 && field_free() &&
        hit(pressed, (unsigned) hudButton)) {
        hudFocus = hud_first(&hudHits);
        return kAll;
    }
    const unsigned companion = 1u << shell.companion_id();
    const bool l2 = (heldKeys & companion) != 0;
    if (!companionShown && !quickShown) {
        if (l2) {
            // Held for a moment: the quick menu. A tap (released before) opens the companion.
            if (++holdFrames >= 24) {
                quickShown = true;
                quickSelected = 0;
                holdFrames = 0;
                flyPicker = repelPicker = false;
                refresh();
                return kAll;
            }
        } else {
            if (holdFrames > 0) show_companion();
            holdFrames = 0;
        }
        return 0;
    }
    if (quickShown) {
        if (hit(pressed, RETRO_DEVICE_ID_JOYPAD_B) || (pressed & companion)) {
            if (repelPicker || flyPicker) {
                repelPicker = flyPicker = false;
                return kAll;
            }
            quickShown = false;
            holdFrames = l2 ? 1 : 0;
            return kAll;
        }
        const int dx = (int) hit(pressed, RETRO_DEVICE_ID_JOYPAD_RIGHT) - (int) hit(pressed, RETRO_DEVICE_ID_JOYPAD_LEFT);
        const int dy = (int) hit(pressed, RETRO_DEVICE_ID_JOYPAD_DOWN) - (int) hit(pressed, RETRO_DEVICE_ID_JOYPAD_UP);
        if (repelPicker) {
            repelSelected = std::clamp(repelSelected + dy, 0, std::max(0, repelCount - 1));
            if (hit(pressed, RETRO_DEVICE_ID_JOYPAD_A)) {
                use_repel();
                repelPicker = quickShown = false;
            }
            return kAll;
        }
        if (flyPicker) {
            flySelected = std::clamp(flySelected + dy, 0, std::max(0, int(flyDestinations.size()) - 1));
            if (hit(pressed, RETRO_DEVICE_ID_JOYPAD_A)) confirm_fly();
            return kAll;
        }
        quick_item items[10] {};
        const int count = quick_items(items);
        if (count <= 0) return kAll;
        quickSelected = std::clamp(quickSelected, 0, count - 1);
        if (dx || dy)
            quickSelected = quick_step(count, quickSelected, dx, dy, quickW ? quickW : 512, quickH ? quickH : 384);
        if (hit(pressed, RETRO_DEVICE_ID_JOYPAD_A)) quick_activate();
        return kAll;
    }
    if (pressed & companion) {
        companionShown = false;
        holdFrames = l2 ? 1 : 0;
        return kAll;
    }
    if (shell.control(pressed)) {
        companionShown = false;
        return kAll;
    }
    return kAll;
}
