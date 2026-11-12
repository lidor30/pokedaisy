/* FLIGHT AREA for the libretro core: the app's FlightArea.kt (Keeper, readMaps, seed, saveKey, SeenMaps) in C, so TAKE OFF
 * crosses only into maps the player has seen (VISITED) or anywhere (ANYWHERE). One save's maps are kept in a text file
 * beside the core's settings (<save dir>/pokedaisy/flight-area/<rom crc32>-<save key>.txt, a `group.num` line per map, as
 * the app writes them), seeded once a run from what the save proves (the towns its Fly map lists, the maps between two of
 * them, maps with a beaten trainer), the map the player stands on added as they go. GPL-3.0, see ../NOTICE. */
#ifndef PD_FLIGHT_AREA_H
#define PD_FLIGHT_AREA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct mCore;

#define PD_FLIGHT_AREA_GROUPS 64

/* What FLIGHT AREA reads, from the game's FLIGHT_* record (FlightFns), its FLY record (FlyFns) and that one's HealWarp
 * (Emerald's): pd_flight_gen.h, written by gen_flight.py. */
struct pd_flight_area_cfg {
  uint32_t mapGroups, mapGroupsXor;   // gMapGroups (a pointer per group to its map headers), XORed into each
  int mapGroupCount;
  uint8_t mapGroupSizes[PD_FLIGHT_AREA_GROUPS];
  uint32_t healLocations;             // sHealLocations: {s8 group, s8 num, s16 x, s16 y} from id 1
  int healLocationSize, healLocationCount;
  uint32_t saveBlock1Ptr, saveBlock2Ptr; // pointers to the blocks, or (saveBlockStatic) the blocks themselves
  int saveBlock1PosOff;               // SaveBlock1's pos (x, y; location's group / num 4 bytes on)
  bool saveBlockStatic;
  uint16_t noFly[4];                  // maps never crossed (group << 8 | num), 0xFFFF none
  uint32_t flagGet, getMapsecType, destinations; // FlagGet, GetMapsecType, sMapFlyDestinations ({group, num, heal})
  int firstMapsec, count;             // the table's first map section and its length
  int flyTypes[4], extraMapsecs[4];   // GetMapsecType's answers that fly, the sections past the table (-1 none)
  bool healWarp;                      // Emerald's Fly map exit: the home town by gender, a few sections by a table
  uint32_t hwSaveBlock2Ptr;
  bool hwSaveBlock2Static;
  int genderMapsec, maleHeal, femaleHeal;
  int special[4][2];                  // {map section, heal location}, -1 none
};

/* A game started: its config (NULL = no FLIGHT AREA), the ROM (bytes from 0x08000000), its CRC32 and the folder the seen
 * maps go in (<save dir>/pokedaisy; NULL keeps them in memory only). Tells pk_flight "anywhere" until the first sync. */
void pd_flight_area_begin(const struct pd_flight_area_cfg *cfg, const uint8_t *rom, size_t len, uint32_t romCrc,
                          const char *dir);
void pd_flight_area_end(void);
/* The next sync sends the maps again (a state load, a reset, the setting changed). */
void pd_flight_area_resend(void);
/* Keeper.tick: [anywhere] tells pk_flight so (once); else the save checked, seeded the first time it's met this run
 * (the game's own GetMapsecType / FlagGet through pk_call: only between frames where pk_call_safe says so - else it
 * waits for the next sync), the player's map added when [standing] (the free field, not flying), and pk_flight given
 * the maps when they changed. */
void pd_flight_area_sync(struct mCore *core, bool anywhere, bool standing);
/* The maps kept for the loaded save (-1 before one is known), and whether map [id] is one (tests, the report). */
int pd_flight_area_count(void);
bool pd_flight_area_has(int id);
/* The file the loaded save's maps are kept in ("" before one is known). */
const char *pd_flight_area_file(void);

#endif
