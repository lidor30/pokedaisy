captured: 2026-10-15 (headless mgba_dump, by hand; files as the gold fixture's)
rom / save: as the gold fixture.
boot: as the gold_battle_party fixture, then UP (PIDGEY), A, wait 60, DOWN: dumped on BattleMonMenu (SWITCH / STATS /
  CANCEL) with the cursor on STATS - w2DMenuCursorInitY 12, X 12, 3 rows, offsets 0x20, wMenuCursorY 2, a filled ▶ at
  (12, 14). Its first frame with the filled ▶ (12, 12) is 18 frames after the A that opens it; one-frame A's 0, 2 and 4
  frames after that were dropped, the one 6 frames after took (the ▶ turned hollow, 0xEC; checked on the menu over
  CYNDAQUIL, which then went in). One A 20 frames after the menu showed (over PIDGEY) took at once: wCurBattleMon 1.
