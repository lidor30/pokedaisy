# Playbook: ds-port (a supported DS game in another language or revision)

Since 2026-11-07 every DS language cart is a row of `gamedata/nds/ports.toml`, not hand-written code (CLAUDE.md "DS language
ports", docs/DEVELOPMENT.md "Language ports", "French carts").

1. **The words**: `python3 scripts/port_nds_fns.py US.nds OTHER.nds --file <the US list>`. The lists are the sections of
   `scripts/port_nds_fns_known.txt`. It matches functions by structure and call anchors and gives each word its votes, or
   UNRESOLVED: **an UNRESOLVED hint is a lead to check live, never an answer**. Name the overlay for code (overlays overlap;
   Black / White's overlay 10 shifts part-way through). `--check scripts/port_nds_fns_known.txt`
   (`scripts/test_port_nds_fns.py`) must stay at 0 wrong.
2. **The row**: code, rev, kind, base (heartgold / black / black2), name, title (the cart's own), SHA-1, the No-Intro cart
   name, then the words. `make gamedata` writes `pk_nds_ports_gen.h` and `NdsPortsGen.kt`.
3. **Headless beside its US cart, on the US save, with one script** (nds_dump): the field, the X menu, `widecheck`,
   `action 2` (PC), `action 1` (FLY). On HG / SS also the party, options and Pokédex pages from the X menu and a wild battle
   (`hgwild`'s `scriptgo`). Compare the `fieldinfo` bits and the `childInit`s with the US run.
   - Known differences: some intros are slower (Spanish / French White 2 stop at the C-Gear's "continue anyway?": one more
     A).
   - Korean HG / SS saves have their own footer magic (0x20070903).
   - JP / KR text banks are numbered their own way (`NdsArt`'s `*_JA` / `*_KO`).
4. **Missing menu functions** (0 / left out) mean its touch moments aren't followed (`DsLayout.followed` off). Say so.
5. **Tests and site**: DsPortsTest's cases; `website/test/compat.test.js` for the header; compat.json from the export.
6. **Books**:
   - the manual's language list for that game (docs/manual/supported-games.md);
   - the README row ("every language");
   - ROM_FEATURES note 31's list;
   - a line in DEVELOPMENT.md's "Language ports" if the cart had a surprise.

No fixtures expected (`make rom-checklist ... ARGS=--ds-port`): the headless run beside the US cart is the proof. Say what
it showed.
