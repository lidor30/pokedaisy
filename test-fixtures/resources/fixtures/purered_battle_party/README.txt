captured: 2026-10-22 (headless mgba_dump, by hand)
rom / save: as the purered fixture (six Pokemon).
boot: as the purered_battle fixture up to the battle menu (wait 300), then RIGHT (PkMn), wait 10, A, wait 60, DOWN, wait 10,
  DOWN, wait 10: dumped on the battle's party menu ("Choose a POKeMON.") with the cursor on slot 2 (Fred) - wTopMenuItemY
  1, X 0, wCurrentMenuItem 2, wMaxMenuItem 5, a filled ▶ at (0, 5), wPartyMenuTypeOrMessageID (1:D07D) 0
  (NORMAL_PARTY_MENU), wPlayerMonNumber (0xCC2F) 0. From here A, then A on SWITCH 4 frames later, sent Fred in
  (wPlayerMonNumber 2, checked headless).
