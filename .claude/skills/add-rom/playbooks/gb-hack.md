# Playbook: gb-hack (a Game Boy hack, usually a pret fork)

Templates:
- Polished Crystal (a .sym per release, its own structs);
- Yellow Legacy / Crystal Legacy (pret forks: only what changed is generated, `shares`);
- PureRed / PureBlue (WRAM bank 1, its own tables);
- Crystal Clear (no symbols: `port_gen1.py --loose`, packed text);
- Prism (its own species ids and text engine).

## Steps

1. **Find the repo and tag.** Build it in Docker with the rgbds it pins (Yellow Legacy 0.6.1, Crystal Legacy 0.5.2,
   PureRGB 1.0.3, Polished Crystal its own). If the build equals the release byte for byte, its `.sym` gives every
   address: **use that, never guess**. No source: map pret's `.sym` into it with `scripts/port_gen1.py --loose` and check
   each address in dumps (Crystal Clear).
2. **Kind**: a `GameKind` in `isGen1` / `isCrystalFamily` / `isGen2` when it needs its own rules. Detected by SHA-1, each
   build listed (Polished Crystal's 16).
3. **Save**: retail's save often doesn't read right:
   - The Legacy hacks need `scripts/convert_save_legacy.py`, and a converted Yellow save must be walked in.
   - Crystal Clear's moved map ids break Crystal's save.
   - Otherwise play a new game headless (`savestate` / `loadstate` in steps; `gbfar` / `gbinject` assume Crystal's hROMBank
     / FarCall).
   - Hold presses 3-6 frames in its menus.
4. **Tables**: generate only what changed (`gen_gen1_tables.py` / `gen_gen2_tables.py <game>`, `--base` checks the shared
   ones; Polished: `gen_polished_crystal_tables.py`). Names keep the game's casing.
5. **RAM rules**: WRAM bank 1 (D000-DFFF) is read by bank (`wramx(1, addr)`), never a bus peek mid-frame.
6. **Extras**:
   - GUIDE over `GuideWorld` (generated wild / boss data, `readGen*Progress`).
   - EXPAND: a `pk_wide_gb.c` row, `widecheck` exact on a sweep (Crystal Clear's: every map warped to).
   - GbField: PC / FLY / WILD, code checked in the cart first.
   - STEADY: `Gen1MusicRenderer` tables. Check the hack's sound wrappers: Prism's matched none.
   - The POKéMON switch: `GbPartySwitch`. Every pick steers on the menu read each frame; some menus read keys every other
     frame.
7. **Look**: the game's own (the user's rule: match it even with fewer colours), sampled headless.
