captured: 2026-11-12 (headless mgba_dump, by hand; files as the gold_de fixture's)
rom / save: as the gold_de fixture.
boot: native-capture/boot/gold.txt, then 25 times LEFT then RIGHT (each held 17 frames, 3 apart) in Route 29's grass, wait 300, A (held 3), wait 600: the battle menu against a wild TAUBSI (Lv2); CYNDAQUIL's HP poked to 1 (wBattleMonHP 0xCB1C and wPartyMon1HP bank 1 0xDA4C
  = 00 01) and the TAUBSI's moves to TACKLE (wEnemyMonMoves bank 1 0xD0F1 = 21 21 21 21). Then A (KMPF), wait 40, DOWN, A
  (SILBERBLICK), wait 400, A (held 3) every 90 frames through the turn and the next-Pokémon question (JA): the party menu
  after the faint; DOWN: dumped with the cursor on PIDGEY - wPartyMenuActionText 2 (A sends the slot out), wCurBattleMon 0.
  A then sent PIDGEY in (wCurBattleMon 1).
