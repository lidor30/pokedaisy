captured: 2026-10-15 (headless mgba_dump, by hand; files as the gold fixture's)
rom / save: as the gold fixture.
boot: the gold_battle fixture's battle after PIDGEY had been sent in (the switch_menu fixture) and CYNDAQUIL switched back,
  on the battle menu: CYNDAQUIL's HP poked to 1 (wBattleMonHP 0xCB1C and wPartyMon1HP bank 1 0xDA4C = 00 01) and PIDGEY's
  moves to TACKLE (wEnemyMonMoves bank 1 0xD0F1 = 21 21 21 21). Then A (FIGHT), DOWN, A (LEER - TACKLE could have knocked
  the wild PIDGEY out first), A (held 3) every 90 frames through the turn and "Use next POKeMON?" (YES): the party menu
  after the faint ("Which PKMN?"); DOWN: dumped with the cursor on PIDGEY - the list as in gold_battle_party (CYNDAQUIL
  FNT 0/20) but wPartyMenuActionText 2 (A sends the slot out, no SWITCH menu; checked headless: A put PIDGEY in,
  wCurBattleMon 1).
