captured: 2026-11-12 (headless mgba_dump, by hand; files as the crystal_de fixture's)
rom / save: as the crystal_de fixture.
boot: as the crystal_de_battle_party fixture up to the battle menu, then ENDIVIE's HP poked to 1 (wBattleMonHP 0xC63C and
  wPartyMon1HP bank 1 0xDD01 = 00 01) and TAUBSI's moves to TACKLE (wEnemyMonMoves bank 1 0xD208 = 21 21 21 21). Then A
  (KMPF), wait 40, A (TACKLE), wait 400, A (held 3) every 90 frames through the turn and the next-Pokémon question (JA):
  the party menu after the faint; DOWN: dumped with the cursor on TAUBSI - wPartyMenuActionText 2 (A sends the slot out),
  wCurBattleMon 0.
