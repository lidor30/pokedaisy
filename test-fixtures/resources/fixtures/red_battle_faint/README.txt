captured: 2026-10-15 (headless mgba_dump, by hand)
rom / save: as the red fixture.
boot: as the red_battle fixture up to the battle menu (wait 300), then three things poked to have Joseph faint: its HP to 1
  (wBattleMonHP 0xD015 and wPartyMon1HP 0xD16C = 00 01) and CHARMANDER's moves all SCRATCH (wEnemyMonMoves 0xCFED = 0A 0A
  0A 0A). Then A (FIGHT), DOWN x3, A (LIGHT SCREEN) (10 frames apart), wait 400, A + wait 90 six times (through the turn,
  "Joseph fainted!", then "Use next POKeMON?" and YES), DOWN, wait 10: dumped on the party menu after the faint ("Bring
  out which POKeMON?") with the cursor on slot 1 (Liam) - wTopMenuItemY 1, X 0, wCurrentMenuItem 1, a filled ▶ at (0, 3),
  wPartyMenuTypeOrMessageID 2 (BATTLE_PARTY_MENU: A on a slot sends it out, no SWITCH menu; checked headless: A from here
  put Liam in, wPlayerMonNumber 1), Joseph 0/182 FNT.
