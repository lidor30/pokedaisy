# Playbook: gba-retail-variant (retail with a few bytes changed)

Identify saw 98%+ of retail's blocks at their offsets, or a supported retail header on a dump of another hash. Find out
which of these it is before writing anything:

- **A collection / repack dump** (trimmed end padding, a flash-cart save patch, a repack's own bytes in retail's 0xFF
  space): `RetailDumps` (`RetailDumpsGen.kt` from `scripts/gen_retail_dumps.py`). Bytes [0, data end), with the known
  ranges and GPIO zeroed, hash to retail's SHA-1. Run the generator's check against the file. A new kind of patch means a
  new range in the generator, found by diffing the dump against retail (CLAUDE.md "Collection dumps of retail"; the
  `$DECOMPS` FireRed map is shifted 0x93C, so find FireRed's ranges by bytes).
- **A Universal Pokemon Randomizer ZX output** (4.6.1, English FireRed rev 0 / 1 or Emerald): `RandomizedRetail`
  (`RandomizedRetailGen.kt` from `scripts/gen_randomized_retail.py`, `UPR_SRC` = a UPR checkout, facts only: UPR is
  GPLv3). `--check <folder>` proves a batch. Another UPR version means rerunning the generator at its tag
  (docs/DEVELOPMENT.md "Randomized ROMs").
- **Another randomizer, a QoL patch or anything else changing code**: not a variant. It's `gba-inplace.md`, and
  **RetailEngine** may already prove it (`RetailEngineGen.kt`, `scripts/gen_retail_engine.py --row HACK`: an unknown
  in-place hack proven by retail's code around each RAM global gets retail's cursors, gPartyMenu and EXPAND).
- **A bad dump** (`[b]`, an overdump): say so. It isn't something the app should learn to read unless the user wants it.

Tests: `RetailDumpsTest` / `RandomizedRetailTest` cases, and BestEffortTest by name (detection changed). Books: the
website's `retailDumps` / `randomizedRetail` in compat.json come from the export. README / manual only if a new kind of
dump becomes supported.
