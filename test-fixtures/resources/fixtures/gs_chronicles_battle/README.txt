captured: 2026-10-13 (headless mgba_dump, by hand)
rom: Pokemon GS Chronicles v2.7.6 (~/Downloads/Game ROMs & Emulation/gba/Pokemon GS Chronicles (v2.7.6).gba, sha1 25f8840e...)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon GS Chronicles (v2.7.6).sav (the gs_chronicles fixture's new game)
boot: native-capture/boot/gs_chronicles.txt (the field in NEW BARK TOWN), wait 60, then a scripted wild battle:
  B6 A100 03 0000 B7 02 (setwildbattle SENTRET 161, 3; dowildbattle; end) poked at 0x0203F400 - not 0x0203FF00,
  which this hack uses (a battle started from there met a blank Lv0 foe) - and `call 0x08069AE5 0x0203F400`
  (ScriptContext_SetupScript, FireRed rev 0's address and code, kept by CFRU), wait 300, A, wait 200: the action menu
  ("What will Cyndaquil do?", FIGHT highlighted), dumped. gBattlerControllerFuncs[0] = 0x0802e439
  (HandleInputChooseAction), gActionSelectionCursor 0x02023FF8 = 0.
  The battle menus' cursors are FireRed's (0x02023FF8 / 0x02023FFC): RIGHT / DOWN / LEFT / UP walked the action cursor
  0 -> 1 -> 3 -> 2 -> 0, the move cursor 0 -> 1 (DOWN refused: three moves), and a cell written there before A is what
  the game takes (2 -> the party menu, WaitForMonSelection 0x08030685; move 1 -> LEER's PP 30 -> 29).
  The POKéMON switch: gPartyMenu 0x0203B0A0 (slotId +9) is CFRU's two-column grid - DOWN from slot 0 lands on 2 -
  and A, A on SHIFT sent CHIKORITA out: gBattlerPartyIndexes[0] (0x02023BCE) 0 -> 2.
