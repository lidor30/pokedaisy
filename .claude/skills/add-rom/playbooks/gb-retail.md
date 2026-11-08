# Playbook: gb-retail (a Game Boy / Color retail cart the repo doesn't know)

Templates:
- The European Yellows (German / French / Italian / Spanish: English's code with their own text; RAM English's + 5 from
  wBattleMon up).
- Crystal rev 0 / rev 1 / Australia (pret builds all three).
- Red / Blue (Yellow's reader, RAM Yellow's + 1 from wOnSGB).
- Gold / Silver (pokegold's RAM).

## Steps

1. **pret first**: build the matching disassembly in Docker with its pinned rgbds (pokeyellow / pokered 1.0.3, pokecrystal
   1.0.4) and check the build's SHA-1 equals the cart. Then its `.sym` is every address. A cart pret doesn't build: map
   the English `.sym` into it with `scripts/port_gen1.py` (English's code matched in the cart; `--loose`, `--window`,
   `--data`). Check every table by contents (`gen_gen1_tables.py yellow_<l>` asserts the shared ones are English's
   bytes).
2. **Detection**: by SHA-1 (`gen1ConfigFor`, `CRYSTAL_SHA1S`...). Names come as a `GameText` keyed by the CGB
   manufacturer code (APSD / APSF / APSI / APSS), which feeds `romGameCode` / `romLanguage`, so AUTO language follows
   the cart.
3. **Text**: European carts swap some tiles for accented letters (`Gen1Charset`); each gets its own font file
   (`Gen1Art.yellowFont`). Dex pages are metric there (`Gen1DexTables.metric`).
4. **Save**: the English save usually loads. French / Italian / Spanish Yellow keep the map header's pointers elsewhere,
   so their boot scripts poke the cart's own header for the save's map (`native-capture/boot/yellow_<l>.txt`).
5. **Per-language bits**: the battle menu's cursor columns (`battleMenuLeftX` / `RightX`) and words
   (`gen1BattleMenuWords`); GbField's config per cart (`GbFieldTest`: each cart matches only its own); the guide's RAM
   offsets (`gen1GuideRam`).
6. **Fixtures**: `yellow_<l>`, `_battle*`. Books: the README row's languages, the manual's Yellow line, ROM_FEATURES.
