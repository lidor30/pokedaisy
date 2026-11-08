// The TRAINER CARD: what it shows (TrainerCard.kt's readTrainerCard, from the
// snapshot's save reads) and the card itself drawn from the player's ROM pixel
// for pixel (TrainerCardArt.kt: both decomps' src/trainer_card.c - its layers,
// coordinates and strings, over the card's own tiles, palettes, badges,
// trainer pic and FONT_NORMAL found by pd_romart).
#ifndef PD_CARD_INCLUDED
#define PD_CARD_INCLUDED

#include <stdbool.h>
#include <stdint.h>

#include "pd_game.h"
#include "pd_snapshot.h"

#define PD_CARD_W 240
#define PD_CARD_H 160

struct pd_card_icon {
    bool ok;
    uint8_t tiles[0x200];
    uint32_t pal[16];
};

// What the card prints, as SetPlayerCardData gathers it (TrainerCardInfo).
struct pd_card_info {
    int style;           // PD_CARD_*
    uint8_t name[8];     // the game's encoding
    int nameLen;
    bool female;
    int trainerId, hours, minutes;
    long money;
    int dexCaught;       // regional until the National Dex; -1 = no POKéDEX yet
    int badges;          // bit i = badge i+1
    int stars;
    // Recharged Yellow's back: six icons (first frame's 16 tiles, 16 colours), ok = there.
    struct pd_card_icon icons[6];
    int drawnStars;      // the stars drawn (Lazarus counts them by a rule of its own)
    int hofDebut;        // h << 16 | m << 8 | s of the first Hall of Fame; 0 = none
    int linkWins, linkLosses, trades, unionRoom, berryCrush, linkContests, linkPokeblocks, battlePoints;
    int stickers[3];     // FireRed's Sticker Man: a level 1-4 each, 0 = none
};

// The card for this save, or false (no card for this game, or no save loaded).
bool pd_card_info(const struct pd_game* g, const struct pd_snapshot* s, struct pd_card_info* out);

// The card as the game shows it, 240x160 ARGB (0xFFRRGGBB; 0 = nothing there),
// front or back; colon = the play time's blinking colon; backdrop false leaves
// out the screen behind the card. False when the ROM lacks its art.
bool pd_card_render(const struct pd_game* g, const struct pd_card_info* c, bool back, bool colon, bool backdrop,
                    uint32_t* out);

// The card's own pixels in a render without the backdrop: left, top, right,
// bottom (exclusive).
void pd_card_bounds(const uint32_t* img, int* l, int* t, int* r, int* b);

#endif
