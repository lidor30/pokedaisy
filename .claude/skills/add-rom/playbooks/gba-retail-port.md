# Playbook: gba-retail-port (FireRed / LeafGreen / Emerald / Ruby / Sapphire in another language or revision)

These are generated, not hand-ported (CLAUDE.md "Other-language FireRed / LeafGreen / Ruby / Sapphire", docs/DEVELOPMENT.md
from "The other-language FireRed / LeafGreen / Ruby / Sapphire").

1. **Name and place**: the cart keeps its No-Intro name in `~/Downloads/Game ROMs & Emulation/gba/` beside the others
   (`retailRomFiles` finds retail by its "Pokemon - " / "Pocket Monsters - " prefix).
2. **Port it**: `python3 scripts/port_retail.py "~/Downloads/Game ROMs & Emulation/gba"`. It picks the closest English ROM,
   maps every address (literal pools, code bytes, fallbacks), detects the shrunk Japanese layouts, and writes
   `gamedata/gen3/ports/<game>.toml`, `GameText<Code>Gen.kt`, `RetailPortsGen.kt` (titles by SHA-1) and
   `build/ports/ports.json` (how each address was found). It runs `gen_gamedata.py` itself.
   **Emerald** has its own path: `gen_emerald_lang_tables.py` (`EmeraldText<Lang>Gen.kt`) and the `NATIVE_EMERALD_<LANG>`
   configs; Japanese Emerald is its own build (`NATIVE_EMERALD_JA`).
3. **Verify it headless**: `python3 scripts/verify_ports.py` boots every port beside its English ROM with the English save,
   dumps the field and a scripted wild battle, and compares (PortsVerifyTest). Read `build/ports/report.md` and
   `build/ports/fields.png`. Japanese FireRed / LeafGreen can't read English saves: they boot with Japanese ones
   (`firered_ja` / `leafgreen_ja`) and are checked absolutely.
4. **The per-feature ports**, each by its own script and check:
   - Field items, PC, FLY: `scripts/port_field_items.py <dir>` -> `FieldItemsPortsGen.kt`, then each checked headless (the
     call, an in-game save, a reboot).
   - BOXES' moves: `BOX_SWAP_PORTS` via port_field_items.py, `scripts/verify_box_swap.py`.
   - EXPAND: `gen_wide_tables.py` / `verify_wide.py`, since retail covers every language and revision.
   - Party art a European FireRed redrew: `FR_<LANG>_*` sigs in `gen_rom_art_sigs.py`.
   - Item descriptions: port_retail.py writes `itemDescs`.
   - GUIDE text: `trGuide()`'s tables. A language the guides lack fails GuideTranslationsTest.
5. **Fixtures**: a `<game>_<lang>` host_roms.conf line (the English save loads in the European carts), a field fixture and
   a case in the language test (EmeraldLanguagesTest, PortedRomsTest...).
6. **Books**: the README row says "every language" or lists them; ROM_FEATURES' language notes and the manual's language
   list for that game; compat.json's `retail` list (by code + revisions).

Rerun port_retail.py and verify_ports.py after touching any FireRed / LeafGreen / Ruby / Sapphire config: CLAUDE.md's rule.
