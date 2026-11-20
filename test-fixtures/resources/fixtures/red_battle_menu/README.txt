captured: 2026-10-15 (headless mgba_dump, by hand)
rom / save: as the red fixture.
boot: as the red_battle fixture up to the battle menu (wait 300), then DOWN (ITEM), wait 10, RIGHT (RUN), wait 10: dumped
  with the cursor on RUN - wTopMenuItemY 14, wTopMenuItemX 15, wCurrentMenuItem 1, a filled ▶ (0xED) at (15, 16), as in
  Yellow. On FIGHT (before the presses) the same menu reads wTopMenuItemY 14, X 9, wCurrentMenuItem 0, ▶ at (9, 14): FIGHT /
  ITEM are column X 9, PkMn / RUN column X 15.
