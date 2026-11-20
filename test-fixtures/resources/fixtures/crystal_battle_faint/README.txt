captured: 2026-10-14 (headless mgba_dump, by hand; files as the crystal fixture's)
rom / save: as the crystal fixture.
boot: as the crystal_battle_party fixture up to the battle menu, then CHIKORITA's HP poked to 1 (wBattleMonHP 0xC63C and
  wPartyMon1HP bank 1 0xDD01 = 00 01) and HOOTHOOT's moves to TACKLE (wEnemyMonMoves bank 1 0xD208 = 21 21 21 21). Then
  A (FIGHT), wait 40, A (TACKLE), wait 400, A (held 3) every 90 frames through the turn and "Use next POKéMON?" (YES):
  the party menu after the faint; DOWN: dumped with the cursor on PIDGEY - the list as in crystal_battle_party but
  wPartyMenuActionText 2 (PARTYMENUACTION_SWITCH: A sends the slot out, no SWITCH menu; checked headless: DOWN, A put
  PIDGEY in, wCurBattleMon 1).
