/* FREE FLIGHT, FLIGHT AREA and the scenic FLY: the app's Flight.kt, FlightArea.kt and ScenicFly.kt in C, over the config's
 * flight fields (pd_config's flight*, generated from gamedata's FLIGHT_* records). The flying itself is the app's own
 * pk_flight.c (after every frame); this decides when it may, what it's told, and the scenic route. GPL-3.0, see ../NOTICE. */
#ifndef POCKET_FLIGHT_H
#define POCKET_FLIGHT_H
#include "../app/src/main/cpp/pk_flight.h"
#include "fly.h"
#include "pd_game.h"
#include "pd_snapshot.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct mCore;

/* FLY ON (the app's FlightVehicle): pk_flight.h's PK_FLIGHT_VEHICLE word. */
enum flight_vehicle { FLIGHT_BIRD, FLIGHT_BALLOON, FLIGHT_HELICOPTER, FLIGHT_ZEPPELIN, FLIGHT_VEHICLES };
const char *flight_vehicle_name(int v);

/* Whether this ROM has the config's flight functions (and FLY's) where it says. [retail]: a retail hash (or a dump or a
 * randomized copy of one; LeafGreen by its code): the app lets FREE FLIGHT fly only games whose own maps were flown. */
bool flight_supported(const struct pd_config *cfg, const uint8_t *rom, size_t len, bool retail);
/* pk_flight_configure's words for [cfg] with FLY ON's [vehicle]. */
void flight_words(const struct pd_config *cfg, int vehicle, uint32_t out[PK_FLIGHT_WORDS]);
/* FLY's gates for a take-off (Fly.kt's flightCheck): the field free, the badge, a map FLY works from, someone with the
 * move. Calls the game: between frames, when pk_call_safe says so. */
enum fly_result flight_check(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                             const struct pd_snapshot *snap, bool busy, bool battle);
/* Why pk_flight_take_off said no, in the app's words. */
const char *flight_reason(int code);

/* A map as pk_flight.h numbers it: group << 8 | num. */
struct flight_spot {
  int map, x, y;
};
/* Where the player stands (SaveBlock1's location and pos). */
bool flight_here(struct mCore *core, const struct pd_config *cfg, struct flight_spot *out);
/* The landing spot FLY sets for [mapsec] (its heal location), or false for a town with none. */
bool flight_destination(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len, int mapsec,
                        struct flight_spot *out);

/* FLIGHT AREA (FlightArea.kt): the maps one save has seen, kept in a file per ROM and save, seeded once a run from the
 * save itself (its Fly map's towns, the maps between two of them, the maps with a beaten trainer), the player's map
 * added as they go. */
struct flight_area {
  uint8_t bits[PK_FLIGHT_AREA_BYTES];
  int count;      // maps in bits
  uint32_t key;   // the save's (flight_save_key), 0 = none yet
  bool seeded;    // the save read into it this run
  bool dirty;     // changed since the host last wrote it
  int sent;       // count when last given to pk_flight, -1 = give again
};
/* Which save is loaded (the player's name, gender and ID, CRC'd), 0 before one is. */
uint32_t flight_save_key(struct mCore *core, const struct pd_config *cfg);
void flight_area_clear(struct flight_area *a, uint32_t key);
bool flight_area_has(const struct flight_area *a, int map);
/* The host's file: a line per map, "group.num" (the app's SeenMaps). */
void flight_area_parse(struct flight_area *a, const char *text, size_t n);
size_t flight_area_format(const struct flight_area *a, char *out, size_t n);
/* The save's maps (FlightArea.seed); calls the game (GetMapsecType, FlagGet): between frames, pk_call_safe. */
void flight_area_seed(struct flight_area *a, struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len);
/* Adds [map]; true when it was new. */
bool flight_area_add(struct flight_area *a, int map);

/* The scenic FLY (ScenicFly.kt): the directions (1 down, 2 up, 3 left, 4 right) from [from] to [to] over the maps the game
 * joins, [area] the maps it may cross (NULL = all); the count, or -1 with no way there. */
#define FLIGHT_MAX_STEPS 4096
int flight_route(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len, const struct flight_spot *from,
                 const struct flight_spot *to, const struct flight_area *area, uint8_t out[FLIGHT_MAX_STEPS]);

#endif
