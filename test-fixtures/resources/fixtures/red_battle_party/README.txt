captured: 2026-10-15 (headless mgba_dump, by hand)
rom / save: as the red fixture (six Pokemon: no party copying needed).
boot: as the red_battle fixture up to the battle menu (wait 300), then RIGHT (PkMn), wait 10, A, wait 60, DOWN, wait 10,
  DOWN, wait 10: dumped on the battle's party menu ("Choose a POKeMON.") with the cursor on slot 2 (Fred) - wTopMenuItemY
  1, wTopMenuItemX 0, wCurrentMenuItem 2, wMaxMenuItem 5, a filled ▶ (0xED) at (0, 5), wPartyMenuTypeOrMessageID (0xD07D)
  0 (NORMAL_PARTY_MENU), wPlayerMonNumber (0xCC2F) 0 (Joseph out).
