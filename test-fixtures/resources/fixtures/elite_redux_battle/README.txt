captured: 2026-10-16 (headless mgba_dump, by hand)
rom: Pokemon Elite Redux 2.65.4.1b (~/Downloads/Game ROMs & Emulation/gba/Pokemon Elite Redux (2.65.4.1b).gba)
save: ~/Downloads/Game ROMs & Emulation/rom saves from discord/saves/Pokemon - Elite Redux.sav (the elite_redux fixtures')
boot: native-capture/boot/elite_redux.txt (the field), then a scripted wild battle: B6 0701 0500 0000 0000 0000 0000 B7 02
  (its setwildbattle takes six u16s - species ZIGZAGOON 263, level 5, item, a second mon's three - then dowildbattle,
  end) poked at 0x0203FF00 and `call 0x081F3985 0x0203FF00` (ScriptContext1_SetupScript), wait 200, A, wait 400: the
  action menu ("What will Zekrom do?": FIGHT / INFO over POKéMON / RUN, no BAG), dumped. gBattlerControllerFuncs[0] =
  HandleInputChooseActionPlayer.
checked from there (both builds; scratchpad replays, NATIVE_ELITE_REDUX_*): gActionSelectionCursor holds 0 FIGHT, 1
  POKéMON, 2 RUN, 3 INFO - DOWN from FIGHT gave 1, a written 2 + A ran ("Got away safely!"); gMoveSelectionCursor is a
  column of four (a written 1 + A used the second move: WING ATTACK PP 32 -> 31); the battle party menu is a two-column
  grid (gPartyMenu slotId +9: DOWN 0 -> 2, RIGHT 2 -> 3), and A, SHIFT on slot 2 put LUCINEON out
  (gBattlerPartyIndexes[0] = 2).
