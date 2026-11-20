captured: 2026-10-15 (headless mgba_dump, by hand)
rom / save: as the red fixture.
boot: native-capture/boot/red.txt, then a wild battle: wCurEnemyLevel (0xD127) = 12 and wCurOpponent (0xD059) = 0xB0
  (CHARMANDER's internal index) - the overworld loop starts it - wait 600, A (past "Wild CHARMANDER appeared!"), wait 300
  (the battle menu), A (FIGHT), wait 60: dumped on the move list, cursor on THUNDERBOLT (wTopMenuItemY 12, X 5,
  wCurrentMenuItem 1, a filled ▶ (0xED) at (5, 13)). Joseph (PIKACHU, Lv100, 182/182: THUNDERBOLT / THUNDER / SLAM /
  LIGHT SCREEN) out, wIsInBattle (0xD057) 1; the foe CHARMANDER Lv12, 34 HP (SCRATCH / GROWL / EMBER).
