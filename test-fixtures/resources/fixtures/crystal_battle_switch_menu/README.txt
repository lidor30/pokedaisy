captured: 2026-10-14 (headless mgba_dump, by hand; files as the crystal fixture's)
rom / save: as the crystal fixture.
boot: as the crystal_battle_party fixture, then UP (PIDGEY), A, wait 60, DOWN: dumped on BattleMonMenu (SWITCH / STATS /
  CANCEL) with the cursor on STATS - w2DMenuCursorInitY 12, X 12, 3 rows, offsets 0x20, wMenuCursorY 2, a filled ▶ at
  (12, 14). The menu drops presses for a while after it shows: A 22 frames after it did nothing, A 40 frames after
  sent PIDGEY in (wCurBattleMon 1).
