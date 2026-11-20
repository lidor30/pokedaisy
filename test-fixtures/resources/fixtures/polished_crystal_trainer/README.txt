captured: 2026-10-16 (headless mgba_dump, by hand; files as the polished_crystal fixture's)
rom: as the polished_crystal fixture (3.2.3).
save: none yet - dumped during the new game that made the polished_crystal fixture's save, before it was saved: LYRA's
  battle in ELM's lab, which the game starts when the player first tries to leave with the starter (coord_event (4, 6),
  LyraBattleScript), a one-time scripted trainer battle; Route 29 has no trainers and the save can't reach this one again.
  Nothing poked before it but the text speed (wOptions1 00:CFF1 = 0xC3, INSTANT).
boot: (the new game) through the battle's intro, then 240 frames: dumped on the battle menu (cursor on Fight: wMenuCursorY
  1, X 1, the ▶ at (9, 14), the menu as in polished_crystal_battle_menu, wMenuJoypadFilter 0x0D; no Run shortcut on B in a
  trainer battle) - wBattleMode 01:D233 = 2, wOtherTrainerClass 01:D235 = 0x1E (LYRA1), wOtherTrainerID D237 = 1,
  wBattleType D236 = 0, wBattleScriptFlags D466 = 0x81; one in wOTPartyMons (wOTPartyCount 01:D283 = 1, wOTPartyMons
  D28B): CHIKORITA (0x98) Lv5 20/20, TACKLE / GROWL (0x2D), DVs BB BB BB, nicknamed "Chicory" (wOTPartyMonNicknames
  D3ED), out (wCurOTMon 00:C4DD = 0; wEnemyMon 01:D209); CYNDAQUIL (Lv5, 19/19, holding its ORAN BERRY, the only party
  member: wPartyCount 1) against it. In ELMS_LAB (wMapGroup 24, wMapNumber 5) at (4, 6).
