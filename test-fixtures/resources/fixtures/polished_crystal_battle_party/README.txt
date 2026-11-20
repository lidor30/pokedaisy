captured: 2026-10-16 (headless mgba_dump, by hand; files as the polished_crystal fixture's)
rom / save: as the polished_crystal fixture (3.2.3).
boot: the polished_crystal_battle fixture's battle (wild SENTRET Lv2), from its move list: B, RIGHT, A (<PK><MN>), wait 60,
  then DOWN until the cursor sat on Cancel: dumped on the battle's party menu ("Choose a Pokémon.") - w2DMenuCursorInitY 1,
  X 0, 3 rows (CYNDAQUIL, PIDGEY, Cancel), 1 column, w2DMenuFlags1 0x60, offsets 0x20, wMenuCursorY 3, a filled ▶ (0xF0) at
  (0, 5); names at (3, 1) / (3, 3) with the icons left of them, Cancel at (1, 5); wPartyMenuActionText 01:D113 = 0,
  wCurBattleMon 0 (CYNDAQUIL out), wCurPartyMon 01:D10C = 1 (stale). This list polls the pad every other frame
  (w2DMenuFlags1 bit 6, sprite animations: PlaySpriteAnimationsAndDelayFrame + DelayFrame a poll), so half the one-frame
  presses are dropped: the first two one-frame DOWNs here did nothing, presses held 2 frames always take.
