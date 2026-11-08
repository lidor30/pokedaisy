# Playbook: gba-inplace (retail code edited in place)

The hack kept retail's code at retail's offsets (identify: 25-97% of blocks equal). Retail's RAM almost always held. Which
kind of hack it is decides the path:

| shape | templates | tables from |
|---|---|---|
| 16 MB FireRed rev 0, names / types / moves rewritten | Ultra Violet (own code PUVV), Rocket Edition, Ash Gray, Orange Islands | `gen_firered_hack_tables.py` (a `HackText` over FireRed's, `fireRedHackFor`), UV's own `gen_ultra_violet_tables.py` |
| 32 MB FireRed rev 0, HexManiacAdvance | Ghost Grey, Let's Go | `gen_firered_hack_tables.py` (`groups` for builds a few bytes apart) |
| 32 MB FireRed rev 0, CFRU (+DPE species) | Unbound, Radical Red, Odyssey, Amethyst, Sword and Shield, GS Chronicles | `gen_cfru_tables.py <game>`: a retail rev 0 ROM's literal pools compared word for word |
| 16 / 32 MB Emerald | Glazed, Kaizo, Emerald QoL | `gen_glazed_tables.py`, `gen_emerald_lang_tables.py kaizo` (a `GameText`) |

Tell CFRU from HexManiacAdvance by the closest sibling identify shows (code likeness) and by trying the CFRU generator.
CFRU repoints, so the word at the same pool offset is the hack's table.

## Steps

1. **Detection**: 16 MB BPRE / BPEE hashes once in the small-ROM check (an unknown hash falls through to retail); 32 MB
   goes through the big-BPRE check (`fireRedHackConfig` runs before the CFRU hashes). A game code of its own goes in
   `HACK_GAME_CODES` (Ultra Violet's PUVV), plus everything that keys on game code (CompanionSupport, GameTitles' hashing,
   INFO's base game, the fanfare, RegionMapWatch, compat.json's `hackCodes`).
2. **Save**: retail's save usually boots (`native-capture/boot/firered_hack.txt` presses past Ash Gray's quest-log recap).
   Some hacks "delete" it (GS Chronicles, Let's Go): then play a new game headless.
3. **RAM**: retail's, checked in dumps (party, bag, map, battle). CFRU moves a few things (Unbound's boxes 20-25, its fly
   spots). Check each feature's addresses rather than assuming.
4. **ROM tables**: the generator over the hack. Names keep the ROM's casing (`ownCase` when mixed case). Watch for gItems
   indexed by slot (GS Chronicles), repointed wild headers / map groups (GuideId's area data from the ROM), a
   `RomRegionMap` map (FireRed 1.0's region_map.c repointed: needs a BPRE rev 0 retail ROM to follow).
5. **What retail's code gives for free, once proven**: one-press cursors (FireRed's / Emerald's EWRAM, checked by the
   write + A + PP test), the switch (`switchAddrsFor`), field items via `fieldItemsForGame` by GameKind (FireRed rev 0's
   functions, the hack's own gItems), FLY (GetMapsecType's 16-bit patch `GET_MAPSEC_TYPE_U16`), WILD ENCOUNTERS (found by
   code), EXPAND (a HACKS row in `gen_wide_tables.py`, mapped like a port; `verify_wide.py`), REUSABLE TMS (found by code).
   **RetailEngine** already proves an unknown in-place hack's cursors / EXPAND: compare what it gives
   (`gen_retail_engine.py --row`).
6. **Look**: party slots and bag by palette (`partyPaletteFor`, `BagPalette`), sampled headless from the hack's own menus,
   or its own party art through RomArt sigs.

Gotchas from earlier hacks:
- Ghost Grey's badge flags were set by leftover scripts.
- Orange Islands has two builds sharing every address.
- Sword and Shield's gyms reuse FireRed's maps (NEXT BOSS goes by badge).
- Kaizo's FLY shortcut crashed its map reload: keep FLY off there.
