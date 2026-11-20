captured: 2026-10-16 (headless mgba_dump, by hand)
rom: Pokemon Ghost Grey v1.0.1 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Ghost Grey (v1.0.1).gba)
save: ~/Downloads/Game ROMs & Emulation/rom saves from discord/Pokémon Ghost Grey Version (1.0.3).srm (the ghost_grey fixtures')
boot: native-capture/boot/ghost_grey.txt (the field), then a scripted wild battle: B6 1300 1E 0000 B7 02 (setwildbattle
  GREAVARD 19, Lv30; dowildbattle; end) poked at 0x0203FF00 and `call 0x08069AE5 0x0203FF00` (ScriptContext_SetupScript,
  rev 0's address and code), wait 300, A, wait 350: the action menu ("What will Vivillain do?"), dumped.
  gBattlerControllerFuncs[0] = 0x0802e439 (HandleInputChooseAction, rev 0's).
checked from there (same boot, both builds): gActionSelectionCursor (0x02023FF8) walks 0 -> 1 -> 3 on RIGHT, DOWN; a
  written 2 + A opens the party menu (gPartyMenu 0x0203B0A0, slotId +9: 0, DOWN -> 1; a list), A, A (SHIFT) puts party
  slot 1 into gBattlerPartyIndexes[0] (0x02023BCE = 1). On v1.0.4: a written action 0 + A, a written
  gMoveSelectionCursor (0x02023FFC) 1 + A spends move 1's PP (gBattleMons[0].pp[1] 14 -> 13).
