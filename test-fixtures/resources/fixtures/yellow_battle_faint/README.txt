captured: 2026-10-14 (headless mgba_dump, by hand)
rom / save: as the yellow fixture.
boot: as the yellow_battle_party fixture up to the battle menu, then three things poked to have Joseph faint: its HP
  to 1 (wBattleMonHP 0xD014 and wPartyMon1HP 0xD16B = 00 01) and CHARMANDER's moves all SCRATCH (wEnemyMonMoves
  0xCFEC = 0A 0A 0A 0A). Then A (FIGHT), DOWN x3, A (LIGHT SCREEN), A every 90 frames through the turn until "Use next
  POKéMON?", A (YES), wait 90, DOWN: dumped on the party menu after the faint with the cursor on slot 1 - the list as
  in yellow_battle_party but wPartyMenuTypeOrMessageID 2 (BATTLE_PARTY_MENU: A on a slot sends it out, no SWITCH
  menu; checked headless: DOWN, DOWN, A put slot 2 in, wPlayerMonNumber 2).
