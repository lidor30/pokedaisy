captured: 2026-10-22 (headless mgba_dump, by hand)
rom / save: as the purered fixture.
boot: as the purered_battle fixture up to the battle menu (wait 300), then Joseph's HP to 1 (wBattleMonHP 1:D015 and
  wPartyMon1HP 1:D174 = 00 01) and CHARMANDER's moves all SCRATCH (wEnemyMonMoves 0xCFEB = 0A 0A 0A 0A). Then A (FIGHT),
  DOWN x3, A (LIGHT SCREEN) (10 frames apart), wait 400, A + wait 90 six times (the turn, "Joseph fainted!", "Use next
  POKeMON?" and YES), DOWN, wait 10: dumped on "Bring out which POKeMON?" with the cursor on slot 1 (Liam) - wTopMenuItemY
  1, X 0, wCurrentMenuItem 1, wPartyMenuTypeOrMessageID 1: PureRGB's BATTLE_PARTY_MENU (it renumbered the party menus;
  Red's is 2). A from here put Liam in (wPlayerMonNumber 1, checked headless). Joseph 0/182 FNT.
