captured: 2026-10-16 (headless mgba_dump, by hand; files as the polished_crystal fixture's)
rom / save: as the polished_crystal fixture (3.2.3).
boot: the polished_crystal_battle_party fixture's battle (wild SENTRET Lv2, CYNDAQUIL out), back on the battle menu (B,
  B): CYNDAQUIL's HP poked to 1 (wBattleMonHP 00:C4B8 and wPartyMon1HP 01:DCF8 = 00 01) and SENTRET's moves to TACKLE
  (wEnemyMonMoves 01:D20B = 21 21 21 21). The foe's move for that turn was already picked (FORESIGHT), and CYNDAQUIL's
  ORAN BERRY healed it to 11 when the turn ended, so the HP was poked to 1 again (both places) on the next battle menu;
  then Fight, DOWN, A (LEER): SENTRET's TACKLE knocked CYNDAQUIL out, A (held 3) every 90 frames through the turn and
  "Use next Pokémon?" (Yes): the party menu after the faint ("Which <PK><MN>?"), the cursor already on PIDGEY (the game
  starts it on the first one that can fight): dumped there - the list as in polished_crystal_battle_party (CYNDAQUIL 0/19,
  no FNT drawn, its item 0 now) but wMenuCursorY 2, the ▶ at (0, 3), wPartyMenuActionText 01:D113 = 2 and wPartyMenuCursor
  01:D0DE = 2: A sends the slot out, no Switch submenu (checked: A held 2 put PIDGEY in, wCurBattleMon 1).
