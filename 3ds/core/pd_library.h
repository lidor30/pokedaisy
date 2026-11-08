// The library (LibraryActivity): the games on the touch screen, GAMES / SETTINGS down a rail like the app's, the game
// under the cursor on the top screen. Immediate mode like the companion.
#ifndef PD_LIBRARY_H
#define PD_LIBRARY_H

#include <stdbool.h>

#include "pd_canvas.h"

#define PD_LIBRARY_MAX 512

enum pd_console { PD_CONSOLE_GBA, PD_CONSOLE_GB, PD_CONSOLE_GBC };

struct pd_library_game {
    const char* file;  // on the SD card
    const char* title; // what the library calls it
    enum pd_console console;
    bool hack;         // a ROM hack, not a retail game
    bool supported;    // the companion knows it
    bool recent;
};

enum pd_library_view { PD_VIEW_ALL, PD_VIEW_RECENT, PD_VIEW_GBA, PD_VIEW_GB, PD_VIEW_HACKS, PD_VIEW_COUNT };

struct pd_library {
    struct pd_library_game games[PD_LIBRARY_MAX];
    int count;
    int view;       // enum pd_library_view
    int selected;   // into the shown games
    int scroll;     // pixels
    int shown[PD_LIBRARY_MAX];
    int shownCount;
    bool touching, dragged;
    int touchX, touchY, lastY, pressed;
    int picked;     // a game to play (an index into games), -1 = none
    int columns;
};

void pd_library_init(struct pd_library* l);
// After the games are filled in.
void pd_library_refresh(struct pd_library* l);
void pd_library_draw(struct pd_library* l, struct pd_canvas* bottom);
// The top screen: the selected game, what A does.
void pd_library_draw_top(struct pd_library* l, struct pd_canvas* top, const char* line);
void pd_library_move(struct pd_library* l, int dx, int dy);
void pd_library_touch(struct pd_library* l, int x, int y, bool down);
// The title for a file name: the app's GameTitles.tidy, or the game's own by its file name.
void pd_library_title(const char* file, char* out, int len, bool* hack, bool* supported);

#endif
