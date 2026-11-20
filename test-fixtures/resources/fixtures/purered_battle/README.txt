captured: 2026-10-22 (headless mgba_dump, by hand)
rom / save: as the purered fixture.
boot: native-capture/boot/purergb.txt, then a wild battle: wCurEnemyLevel (1:D12F) = 12 and wCurOpponent (1:D059) = 0xB0
  (CHARMANDER's internal index; gbpoke into WRAM bank 1) - the overworld loop starts it - wait 600, A, wait 300 (the battle
  menu), A (FIGHT), wait 60: dumped on the move list, cursor on THUNDERBOLT (wTopMenuItemY 12, X 5, wCurrentMenuItem 1, a
  filled ▶ at (5, 13): Red's places). Joseph (PIKACHU, Lv100, 182/182: THUNDERBOLT / THUNDER / FILTHY SLAM / LIGHT SCREEN)
  out, wIsInBattle (1:D057) 1; the foe CHARMANDER Lv12, 31 HP (wEnemyMon 0xCFE3, two bytes before Red's).
