// The library (LibraryActivity on a 3DS): a rail of pages down the touch screen's left - GAMES, SAVE EDITOR, TRADE SAVES,
// SETTINGS, each an icon (the app's ToolIcons) - and the page beside it; the top screen shows what the cursor is on (a
// game's save at a glance, a Pokémon in the editor, both sides of a trade). Immediate mode like the companion: the host
// feeds keys and the stylus, the library draws both screens and says when a game was picked.
//
// pd_library.c: the games, their names and tags, the pages' input. pd_library_draw.c: the touch screen. pd_library_top.c:
// the top screen. pd_library_saves.c: SAVE EDITOR and TRADE SAVES (pd_save.c underneath). pd_library_card.c: what a game
// looked like when it was last left (written by the host after a session, read for the top screen).
#ifndef PD_LIBRARY_H
#define PD_LIBRARY_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "pd_canvas.h"

struct pd_game;
struct pd_snapshot;
struct pd_settings;

#define PD_LIBRARY_MAX 256

enum pd_console { PD_CONSOLE_GBA, PD_CONSOLE_GB, PD_CONSOLE_GBC };

// The keys the host passes (its own buttons mapped to these).
enum {
    PD_LIB_A = 1 << 0, PD_LIB_B = 1 << 1, PD_LIB_X = 1 << 2, PD_LIB_Y = 1 << 3, PD_LIB_L = 1 << 4, PD_LIB_R = 1 << 5,
    PD_LIB_UP = 1 << 6, PD_LIB_DOWN = 1 << 7, PD_LIB_LEFT = 1 << 8, PD_LIB_RIGHT = 1 << 9, PD_LIB_START = 1 << 10,
    PD_LIB_SELECT = 1 << 11,
};

// What a game looked like when it was last left (the app's SnapshotCache, for the top screen): written by the host from
// the session's snapshot (pd_game_card_fill), kept beside the save as a file of these bytes.
#define PD_CARD_MAGIC 0x32434450u // "PDC2"
#define PD_CARD_ICON 32
struct pd_game_card {
    uint32_t magic;
    uint32_t savedAt; // the 3DS clock (seconds), when the game was left
    char trainer[16];
    char place[32];
    int32_t gender, trainerId, playHours, playMinutes, money, badges, badgeCount, dexCaught, partyCount;
    struct { uint16_t species; uint8_t level, shiny, egg, pad[3]; char name[16]; } party[6];
    // The party's icons as the game drew them (first frame), 0 = see-through.
    uint32_t icons[6][PD_CARD_ICON * PD_CARD_ICON];
};
// From the session as it ends; false if there's nothing worth keeping (no party read).
bool pd_game_card_fill(struct pd_game_card* card, const struct pd_game* g, const struct pd_snapshot* s, uint32_t now);

struct pd_library_game {
    const char* file; // on the SD card (the host's string)
    char title[64];   // what the library calls it
    char code[5];     // the ROM header's game code ("" until read)
    char sha1[41];    // "" until hashed (a known file name never needs it)
    long size;
    uint32_t lastPlayed; // the 3DS clock when it was last started, 0 = never here
    enum pd_console console;
    bool hack;      // a ROM hack, not a retail cart
    bool supported; // the companion knows it
    bool named;     // its title and tags are settled (by file name or hash)
};

// The host's side: files on the SD card. Every path is the host's (the ROM's file name is the key).
struct pd_library_host {
    void* ctx;
    // [len] bytes of the ROM from [offset]: how many were read (0 at its end), -1 = no file.
    long (*read_rom)(void* ctx, const char* file, long offset, void* buf, long len);
    // The save beside the ROM: its length, -1 = none.
    long (*read_save)(void* ctx, const char* file, uint8_t* buf, long max);
    // These saves written together - each backed up first, written, read back - or none (false, with why).
    bool (*write_saves)(void* ctx, int n, const char* const* files, const uint8_t* const* bytes, const long* lens, char* why, size_t whyLen);
    // The whole ROM in memory (one at a time: the next call may reuse the buffer), NULL = couldn't.
    const uint8_t* (*load_rom)(void* ctx, const char* file, size_t* size);
    bool (*read_card)(void* ctx, const char* file, struct pd_game_card* out);
    void (*settings_changed)(void* ctx); // write the settings file
    void (*cache_changed)(void* ctx);    // write pd_library_cache_write's text
    const char* version;                 // SETTINGS > APP
    const char* devLine;                 // the dev build's link address, else NULL
};

enum pd_library_page { PD_PAGE_GAMES, PD_PAGE_EDITOR, PD_PAGE_TRADE, PD_PAGE_SETTINGS, PD_PAGE_COUNT };
enum pd_library_view { PD_VIEW_ALL, PD_VIEW_RECENT, PD_VIEW_GBA, PD_VIEW_GB, PD_VIEW_HACKS, PD_VIEW_COUNT };

struct pd_library_saves; // pd_library_saves.c's state

// A list of games on the screen: their indexes, the cursor and the scroll (pixels).
struct pd_library_list {
    int items[PD_LIBRARY_MAX];
    int count, selected, scroll;
};

struct pd_library {
    struct pd_library_game games[PD_LIBRARY_MAX];
    int count;
    int batteryPct;  // the console's battery, -1 = unknown (the host sets it; every page's title shows it)
    struct pd_settings* settings;
    const struct pd_library_host* host;
    int page;       // enum pd_library_page
    int view;       // enum pd_library_view
    struct pd_library_list list; // the games this view lists, in its order
    int picked;     // a game to play (an index into games), -1 = none
    bool quit;      // START: close the app
    bool dirty;     // something to draw
    int columns;    // GRID's
    // The stylus: where it went down, whether it moved, the hit it went down on.
    bool touching, dragged;
    int touchX, touchY, lastY, pressedHit;
    // SETTINGS' list and its pick-list.
    int settingsSelected, settingsScroll, pickRow, pickSelected;
    bool settingsFollow; // the D-pad moved the row: the list scrolls to it (a drag leaves it where it was let go)
    // Naming the games: which one is being hashed, how far.
    int hashing;
    long hashedBytes;
    unsigned frame;
    struct pd_library_saves* saves;
};

void pd_library_init(struct pd_library* l, struct pd_settings* settings, const struct pd_library_host* host);
// After the games' files are filled in: names what the file names give, sorts, lists the view.
void pd_library_refresh(struct pd_library* l);
// Once a frame: names a few more games by their contents (header, hash), a slice at a time.
void pd_library_tick(struct pd_library* l);
// The keys pressed this frame (PD_LIB_*).
void pd_library_keys(struct pd_library* l, unsigned down);
void pd_library_touch(struct pd_library* l, int x, int y, bool down);
void pd_library_draw(struct pd_library* l, struct pd_canvas* bottom);
void pd_library_draw_top(struct pd_library* l, struct pd_canvas* top);
// Both screens while the picked game loads (drawn once, before the host starts reading it).
void pd_library_draw_loading(struct pd_library* l, struct pd_canvas* bottom, struct pd_canvas* top);
// The cursor on this game (the one just played, back from it).
void pd_library_select(struct pd_library* l, const char* file);
// The game was started (for RECENT and LAST PLAYED).
void pd_library_played(struct pd_library* l, int game, uint32_t now);

// The identification cache the host keeps (one line a game: sha1, code, size, last played, file name).
void pd_library_cache_read(struct pd_library* l, const char* text);
size_t pd_library_cache_write(const struct pd_library* l, char* out, size_t len);

// The title for a file name: the app's GameTitles.tidy, or the game's own by its file name.
void pd_library_title(const char* file, char* out, int len, bool* hack, bool* supported);

#endif
