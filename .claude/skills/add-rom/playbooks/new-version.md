# Playbook: new-version (another build of a supported hack)

Templates: Monomyth v1.1 over v1.0, SoulGold v1.2 / v1.2B / v1.2C / v1.2.1, Quetzal Alpha 9 v1 over v0, Recharged Emerald
2.2.8 over 2.2.5, Ghost Grey 1.0.1 / 1.0.2 / 1.0.4, Crystal Clear 2.6.0 over 2.5.11. Read the sibling's CLAUDE.md
paragraph: most say how its versions differ.

## 1. Boot the old version's save first

Identify lists the sibling's saves. Boot that save (a copy, through host_roms.conf) in the new build and dump it. A party
on the field means the save format held. Monomyth v1.1 and Quetzal v1 loaded their predecessor's saves; SoulGold v1.2.1
loaded v1.2's.

## 2. Map the ROM before anything else

Pair the old build's words with the new one's: the literal pools (`port_retail.py`'s `map_literal`, the "word pairs" of
CLAUDE.md's Monomyth v1.1 note) give each old ROM address its new place. The RAM words decide the shape of the work:
- **RAM words all agree** (Monomyth v1.1, Quetzal v1): the new config is the old one with its ROM addresses moved. Put it in
  the same TOML file, beside the old one.
- **Part of the RAM moved** (SoulGold v1.2.1: EWRAM shifted, IWRAM and battle globals kept): move each group and prove each
  in a dump.
- **Nothing agrees**: it's a rebuild. Go to `gba-rebuild.md` / `expansion.md` with the old version as the template.

Without the old build's ROM on this machine (a contributor's): read the new one's GF header (0x08000100) and RHHEXP
block for the moved tables (gSpeciesInfo, gItemsInfo, gMovesInfo, icon palettes, gAbilitiesInfo), find the rest by
content (the chart's Normal row, 8-byte map-section runs, a trainer's name at its record offset, 84-byte wild headers)
with a Counter of every aligned ROM word (never a search per offset), and for code pointers try one shift for all of a
file's functions at once: the shift under which every old pointer lands on a pool word (Team Rocket 2026 v3.1: +0x68
for all five battle handlers), then read them live. The generator's `same_as` checks a build's tables equal another's
without writing them.

## 3. Implement

- Config: a new `[config.<name>_v<ver>]` in the sibling's TOML (gamedata/gen3/hacks/<game>.toml), then `make gamedata`.
- Detection: a `*_V<VER>_SHA1` constant beside the old one (Poller.kt's family set / `SUPPORTED_HACK_SHA1S`), the title in
  `GameTitles.BY_SHA1`. An "is it v1.1" flag in ActiveTables only if tables differ (`amethystV141`, `mariomonV13`,
  `quetzalV1`, `eliteRedux265_3`: set by the Poller).
- Tables: rerun the sibling's generator with both ROMs and have it **check that the shared tables agree**
  (`gen_monomyth_tables.py` with both ROMs, `gen_quetzal_tables.py --v1`). Write a second table set only for what differs.
- Version in the header? Monomyth v1.1's code turned BPEE (v1.0 had MONE): anything keyed by game code then sees Emerald,
  so check `fanfareSongId`, RegionMapWatch and the field items' gates.

## 4. Fixtures and tests

- `<key>_v<ver>`, `_battle`, `_trainer` like the sibling's.
- Add the new configs to the tests the sibling sits in (`git log --stat --grep=<sibling>`): its DecodeTest, MoneyTest,
  BattleCursorTest, WildEncountersTest, RomItemTextTest, RomArtTest, M4aSongsTest, NuzlockeFixturesTest. Also
  GameDataMigrationTest's count.

## 5. Features the sibling has

Each needs the new build's own check:
- **EXPAND**: a row in `gen_wide_tables.py`'s HACKS, then `verify_wide.py`.
- **One-press cursors**: write, A, PP spent.
- **The switch's gPartyMenu**.
- **Field items / FLY**: CRCs of the moved functions.
- **STEADY**: the song table's signature.
- **ROM art**: new sigs only if the art moved compressed.

Whatever isn't checked stays off for the new build, and its ROM_FEATURES row says so.

## 6. Books

The README / ROM_FEATURES / manual rows list the versions ("1.0, 1.1"). DEVELOPMENT.md's row gets the new version and what
moved. CLAUDE.md: one sentence in the sibling's paragraph, and only if a later build will need it ("The next build: map the
ROM by word pairs ...").
