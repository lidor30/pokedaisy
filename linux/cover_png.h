/* The library's cover art: a PNG the firmware's scraper left (EmulationStation's images/), decoded with zlib's puff and
 * cut to a square the size the library draws it at (the app's ContentScale.Crop). No JPEG: such a game shows its
 * cartridge instead. */
#ifndef POCKET_COVER_PNG_H
#define POCKET_COVER_PNG_H

#include <stdbool.h>
#include <stdint.h>

/* [path]'s middle square, scaled to size x size (0xAARRGGBB, area-averaged) into out; false = not a PNG this reads.
 * [fit]: the whole picture inside the square instead (a portrait box, see-through beside it). */
bool cover_png_square(const char *path, int size, uint32_t *out, bool fit);

#endif
