captured: 2026-10-16 (headless mgba_dump, by hand; files as the polished_crystal fixture's)
rom / save: as the polished_crystal fixture (3.2.3).
boot: as the polished_crystal_battle_party fixture, then the cursor to PIDGEY (held 2 frames), A (held 2), DOWN (held 2):
  dumped on the submenu over PIDGEY (Switch / Summary / Cancel) with the cursor on Summary - w2DMenuCursorInitY 12, X 11,
  3 rows, 1 column, w2DMenuFlags1 0x40, offsets 0x20, wMenuCursorY 2, a filled ▶ (0xF0) at (11, 14); Switch at (12, 12),
  Summary (12, 14), Cancel (12, 16) in a box (10, 11)-(19, 17); the list's own ▶ turned hollow (▷ 0xF1) at (0, 3);
  wPartyMenuActionText 0, wCurPartyMon 1. Its ▶ is in wTilemap 4-5 frames after the opening A's first frame. It polls
  every other frame like the list (flags bit 6): counting from the first frame its ▶ is in wTilemap (N), one-frame
  presses on frames N+1 and N+2 are dropped, N+3 is the first that takes, then only every other one (N+5, N+7, ...; N+4
  and N+6 dropped) - measured 18 times live with DOWN, and with A from a savestate of the list: A on N+3 switched
  (wCurBattleMon 1 after the send-out), A on N+1, N+2, N+4 did nothing, an A held 2 frames over N+2..N+3 switched.
