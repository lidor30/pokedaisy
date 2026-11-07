# Playbook: expansion (pokeemerald-expansion builds)

Identify read the RHHEXP block (version, species / moves / items). Some builds have none (Dreamstone: its debug menu's
EXPANSION VERSION gave 1.11.2). Templates by version:

| expansion | templates |
|---|---|
| 1.9.x | Lazarus (1.9.4), Seaglass (Lazarus's layout, 8 bytes earlier) |
| 1.10.0 | Emerald Imperium (also packs experience into 21 bits) |
| 1.11.x | Dreamstone Mysteries (1.11.2) |
| 1.14.x | Team Rocket Edition 2026 (1.14.4, Spanish) |
| 1.15.x | Wishes of Tomorrow (1.15.3, with a run-time randomizer) |
| older / heavy forks | Heart and Soul (its source), SoulGold, TMT2, Elite Redux, Super Mariomon (agbcc!) |

## Steps

1. **A reference build**: clone pokeemerald-expansion at that version's tag and build it plain (`make`, Debian's
   arm-none-eabi-gcc 12, in a container). `nm` its ELF. The release's RAM globals sit within a few bytes of the reference's,
   found by literal-pool reference counts. **Building the hack's own source tag isn't the release** (Heart and Soul's was 4
   bytes off): check every address in dumps.
2. **Struct offsets**: `offsetof` compiled against that tag's headers. `struct BattlePokemon` and SaveBlock1 change between
   versions (`NativeConfig.battleMonLayout`, `saveBlock1PosOff`).
3. **Captures**: `scripts/capture_fixture_headless.sh <key> --script native-capture/boot/rhh_splash.txt` (a "PRET x RHH"
   splash), plus hand-run captures with a varied first `wait`.
   - A new game's party: `callnative ScrCmd_createmon` (Wishes 0x0A200DD5, Dreamstone 0x0A1EDA35).
   - Script commands run through the 0x0A000000 mirror: find gScriptCmdTable in the script context in RAM, not by a ROM scan
     for 0x08 pointers.
   - setwildbattle takes a second mon's fields on newer versions, and a `waitstate` must follow a warp.
4. **Tables**: a `GAMES` entry in `scripts/gen_expansion_tables.py`: species names, types, gender ratios, items, moves, the
   type chart, mapsec names, region maps. The GF ROM header (0x08000100) points at gSpeciesInfo / gItemsInfo / gMovesInfo.
   Copy the comment style of the entries there (what was checked against what).
5. **Gotchas already met**:
   - BoxPokemon packs `species:11|teraType:5`: mask before a "plausible species" filter.
   - Item icons may be smol-compressed (`Smol.kt`).
   - 1.11+ locks field controls with special flag 0x4005 (a bit), which `pk_field_state` can't read yet. No field reads /
     EXPAND / WILD there until it can.
   - gTrainers can be [3][N] by difficulty (`altTrainers`).
   - The RTC seeds the RNG: pace in grass and dump every step.
   - A Mega Evolution can be its own species id.
6. **Extras**:
   - EXPAND / field reads: gcc builds are found by `scripts/find_wide_addrs.py` + `find_wide_objects.py`, then
     `gen_wide_tables.py` HACKS and `verify_wide.py`.
   - FLY / PC: `FieldItemFns.bagActions = false`. Functions are found by masked first instructions from the reference
     build, else through their callers' BLs. Mind MON_DATA renumbering (`FlyFns.monMoveField` / `monEggField`).
   - WILD ENCOUNTERS: `WildEncounters.gccRead`.
   - One-press cursors: usually +0x10 / +0x14 past the gBattleStruct pointer (check by dump diff).
7. **Look**: the DEX from gSpeciesInfo (`POKEDEX_*`), a party palette and backdrop sampled headless, the region map by
   RomArt sigs (`gen_rom_art_sigs.py`).
