# Playbook: ds-hack (a DS hack)

Templates:
- **Origin HeartGold** (an hg-engine rebuild over the Japanese HeartGold, IPKJ rev 0: its own tables and battle engine);
- **Renegade Platinum** (retail Platinum USA rev 1 edited in place: its own types, moves, chart, TMs, icons and wild rates
  read from the cart, on Platinum's companion).

Read both CLAUDE.md / DEVELOPMENT.md sections and `RenegadePlatinum.kt` / `OriginHeartGold`'s code first.

## Steps

1. **Today's behaviour**: a hack that keeps retail's header (identify says so) is already read as that retail game. Run it
   headless (nds_dump) and see what's right and what's wrong before changing anything. That tells you how far it is from
   retail.
2. **Tell it apart by a code word, not the header** (`OriginHeartGold.matches`, `RenegadePlatinum.matches`): a word at an
   offset only the hack has. Do it in the app, native (`pk_nds_field.cpp` / `pk_nds_call.cpp`) and the website (compat.json's
   `dsHacks`: offset + word). The SHA-1 constant (`V<ver>_SHA1`) gives the library title.
3. **Tables from the cart, never bundled**: species, moves, types, chart, icons, DEX pages, wild rates (`NdsArt.readOrigin`
   / `readRenegade`). Run it as the base `GameKind` with a flag (`originHeartGold`, `renegadePlatinum` in ActiveTables)
   unless it needs its own rules.
4. **What it changed underneath**: hg-engine's battle engine (Origin: `Gen4ManagedBattle`, no battle buttons); in-place
   edits keep retail's addresses (Renegade). Check the field reads (`pk_nds_field.cpp`), PC / FLY (`pk_nds_call.cpp`), WILD
   (its own rates, put back from the cart), EXPAND (`widecheck` counts against the base cart's).
5. **Save**: the base game's save may load (Renegade on Platinum's?). Otherwise play a new game headless (Platinum's intro
   wants a *touch* at each yes / no).
6. **Fixtures**: `<hack>`, `_battle`, `_trainer` via nds_dump.
7. **Books**: README row and the DS line naming the hacks, the manual, ROM_FEATURES, DEVELOPMENT.md's section. The manual
   can't name real Pokémon, so name the hack only.

Several builds of one hack (RNGPLAT's "Classic Version" beside the normal one): try the first build's code word and tables
on the second before anything else. They're often one config with two SHA-1s.
