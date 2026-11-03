// The PC's boxes read live: the app's readPcBoxes (NativeReader.kt) and decodeBoxMon (Gen3Mon.kt). A box record's level
// comes from the ROM's growth rate and its stats from the ROM's base stats.
#ifndef PD_BOXES_H
#define PD_BOXES_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_game.h"
#include "pd_snapshot.h"

#define PD_BOX_SLOTS 30
#define PD_BOX_COLUMNS 6
#define PD_MAX_BOXES 25

enum pd_box_format { PD_BOX_ENCRYPTED, PD_BOX_PLAIN, PD_BOX_CFRU };

struct pd_boxes {
    bool ok;
    int boxCount, shown; // which box mons[] holds
    char names[PD_MAX_BOXES][24];
    uint8_t wallpaper[PD_MAX_BOXES], count[PD_MAX_BOXES];
    struct pd_mon mons[PD_BOX_SLOTS];
    bool has[PD_BOX_SLOTS];
};

// The game has a storage the reader knows.
bool pd_boxes_supported(const struct pd_game* g);
// Every box's name and count, and box [box]'s Pokémon.
void pd_boxes_read(struct pd_boxes* b, const struct pd_game* g, pd_read_fn read, void* ctx, int box);

#endif
