# Playbook: gba-rebuild (no retail code at retail's offsets, no RHHEXP)

A pokefirered / pokeemerald rebuilt with modern gcc, or an engine of its own. Templates:
- Recharged Yellow (pokefirered + gcc);
- Recharged Emerald (pokeemerald + gcc, on Emerald's ids);
- Emerald Rogue (public source);
- Emerald Legacy (public source, agbcc: builds byte for byte);
- R.O.W.E. (carries its own symbol table);
- Monomyth (game code MONE, section anchors);
- Elite Redux (packed structs);
- Quetzal (its own engine and bag format).

## Steps

1. **Look for source first.** A public repo at the release's tag that builds byte for byte beats everything (Emerald
   Legacy: agbcc, `nm` + `offsetof`). Even a build that isn't byte for byte gives struct layouts (Rogue: its headers
   compiled with `clang --target=arm-none-eabi`).
2. **The ROM's own maps**:
   - The GF ROM header at 0x08000100 points at the species / item / base stat tables (Recharged Yellow).
   - Some carry a symbol table (R.O.W.E. at 0x08FB0670).
   - Debug menus name versions.
3. **RAM from dumps** (docs/DEVELOPMENT.md "How a new ROM gets supported"):
   - gPlayerParty by the checksum scan;
   - save-block pointers and the encryption key by pointers to confirmed addresses;
   - **as many independent captures as it takes**.
   - With section anchors (Monomyth), a pool word points *near* a variable: diff dumps instead of counting references.
4. **Save**: the base game's save rarely fits a rebuild's SaveBlock1. Convert it (`scripts/convert_save_recharged_emerald.py`,
   `trim_save_block1.py`) or play a new game headless. Recharged Yellow's one save loaded in every release.
5. **Struct surprises, each a switch on NativeConfig / the layouts now**:
   - Plaintext or XOR-keyed party mons (`PartyMonLayout`), 104-byte mons (`monStride`), packed mons without IVs
     (Elite Redux).
   - Bags by bit (`itemFlagsBag`) or a bit-stream (`Bag3Layout` / `Bag4Layout`).
   - Money without the key (`encryptionKeyOff = -1`).
   - Moved system flags (badges at 0x880, game clear 0x864).

   Reuse a switch before adding one.
6. **Tables**: a generator of its own only when no sibling's fits (`gen_rogue_tables.py`, `gen_rowe_tables.py`,
   `gen_monomyth_tables.py`, `gen_recharged_*`), names read from the ROM.
7. **Extras**: no retail code means each feature's functions are found through their callers
   (`find_field_items_recharged_emerald.py` walks gScriptCmdTable's additem / removeitem / checkitem down). Most rebuilds
   ship without EXPAND / FLY at first: say so.

Anything keyed on EMERALD + BPEE that assumes retail's RAM must check `runningEmeraldHack` / `emeraldLegacy` first (the
switch, RegionMapWatch, field items, the PC BOXES editor).
