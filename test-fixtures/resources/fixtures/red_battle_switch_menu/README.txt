captured: 2026-10-15 (headless mgba_dump, by hand)
rom / save: as the red fixture.
boot: as the red_battle_party fixture, then A (on Fred: the SWITCH / STATS / CANCEL menu opens), wait 20, DOWN, wait 10:
  dumped with the cursor on STATS - wTopMenuItemY 12, wTopMenuItemX 12, wCurrentMenuItem 1, wMaxMenuItem 2, a filled ▶
  (0xED) at (12, 14), the party list's ▶ hollow (0xEC) at (0, 5). The menu is in wTileMap by the end of the opening A's
  press (held 1 frame, let go 1); an A on SWITCH pressed straight after (0 or 1 frames of wait) is dropped, one after 2
  frames (the 5th frame counting the opening A's as the 1st) takes and sends Fred in (wPlayerMonNumber 2; checked headless
  at waits of 0, 1, 2, 4, ... 30 frames).
