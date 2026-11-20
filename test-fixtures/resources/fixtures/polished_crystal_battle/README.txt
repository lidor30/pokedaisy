captured: 2026-10-16 (headless mgba_dump, by hand; files as the polished_crystal fixture's)
rom / save: as the polished_crystal fixture (3.2.3).
boot: native-capture/boot/polished_crystal.txt, then LEFT / RIGHT (held 17 frames each, wait 3 after each) in ROUTE 29's
  grass: a wild SENTRET (Lv2, male) on the eleventh pair; wait 300, A (held 3), wait 300: the battle menu. RIGHT, DOWN (the
  polished_crystal_battle_menu dump: the cursor on Run), UP, LEFT, A (Fight), wait 40, DOWN: dumped on the move list with
  the cursor on LEER - w2DMenuCursorInitY 13, X 5, 2 rows (one per move), 1 column, w2DMenuFlags1 0x2C, offsets 0x10,
  wMenuCursorY 2, a filled ▶ (0xF0 in Polished's charset; ▷ is 0xF1) at (5, 14); wCurMoveNum 01:D0DB = 1,
  wMoveSelectionMenuType 01:D239 = 0, wBattleMode 01:D233 = 1, wCurBattleMon 01:D0DA = 0. The move list is one column
  (TACKLE at (6, 13), LEER (6, 14), "-" for the empty two) under an info box at the top left (category icon, type,
  power / accuracy "---P/100%", PP 30/30). Presses in the battle are one frame, 20 frames apart.
  Battlers (battle_struct, macros/ram.asm): CYNDAQUIL at wBattleMon 00:C4A5 (0x9B, ORAN BERRY 0x44, TACKLE / LEER, Lv5
  at +0x10, HP 13/19 at +0x13); SENTRET at wEnemyMon 01:D209 (0xA1, no item, TACKLE 0x21 / FORESIGHT 0xC1, Lv2, HP 13/13),
  wEnemyMonNickname C48F "Sentret".
