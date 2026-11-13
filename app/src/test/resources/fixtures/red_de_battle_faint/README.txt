rom / save: as the red_de fixture.
boot: as the red_de fixture, then a wild battle poked in on the field (wCurEnemyLevel 0xD12C = 12, wCurOpponent 0xD05E = 0xB0,
  CHARMANDER's internal id), wait 600, A, wait 300: the battle menu (KMPF / PkMn / ITEM / FLUCHT).
  Then three things poked to have Joseph faint: its HP to 1 (wBattleMonHP 0xD01A and wPartyMon1HP 0xD171 = 00 01) and
  GLUMANDA's moves all KRATZER (wEnemyMonMoves 0xCFF2 = 0A 0A 0A 0A). A (KMPF), DOWN x3, A (LICHTSCHILD) (10 frames apart),
  wait 400, A + wait 90 seven times (one more than English: the German text takes another box), DOWN, wait 10: the party
  menu after the faint with the cursor on slot 1 (Liam) - wTopMenuItemY 1, X 0, wCurrentMenuItem 1,
  wPartyMenuTypeOrMessageID 2; Joseph 0/182.
