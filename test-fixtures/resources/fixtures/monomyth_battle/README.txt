captured: 2026-10-31 (headless mgba_dump)
rom / save: as the monomyth fixture's README
boot script: native-capture/boot/monomyth_battle.txt: the field, then a scripted wild POLIWAG Lv5 (setwildbattle B6 / dowildbattle
  B7 through ScriptContext_SetupScript 0x08090871); A through "Wild Poliwag appeared!" and the send-out: the action menu
  ("What will Mishimizu do?", frame 3624), dumped.
gBattleTypeFlags 4; gBattlersCount 2; gBattlerPositions 00 01; gBattleMons[0]: Mishimizu Lv12 12/37, WATER, PP 35 / 35 / 20 /
  19, stats 23 / 17 / 21 / 20 / 18; gBattleMons[1]: Poliwag Lv5 20/20 (Mud Shot, Water Gun). gBattlerControllerFuncs[0] =
  HandleInputChooseAction (0x08048549); the action / move cursors 0.
From this point, headless: a 2 written into the action cursor + A opened the party menu, DOWN, A, SHIFT sent Joltik in
  (gBattlerPartyIndexes[0] = 2); a 3 written into the move cursor + A used Water Gun (its PP 19 -> 18).
