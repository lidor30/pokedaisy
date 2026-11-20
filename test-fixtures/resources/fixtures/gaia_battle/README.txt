captured: 2026-10-13 (headless mgba_dump, by hand)
rom: Pokemon Gaia v3.2 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Gaia (v3.2).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Gaia (v3.2).sav
boot: native-capture/boot/default.txt (the field), wait 60, then a scripted wild battle: B6 1900 05 0000 B7 02
  (setwildbattle SPECIES_PIKACHU, 5; dowildbattle; end) poked at 0x0203FF00 and `call 0x08069AE5 0x0203FF00`
  (ScriptContext_SetupScript, FireRed rev 0's), wait 600, A, wait 300 (its intro is slower than Unbound's: at 300 the
  "Wild PIKACHU appeared!" line was still up): the action menu, FIGHT highlighted (gBattlerControllerFuncs[0] =
  0x0802e439), dumped. The battle menus' cursors are FireRed's (0x02023FF8 / 0x02023FFC): RIGHT / DOWN walked the
  action cursor 0 -> 1 -> 3, a written 2 + A opened the party menu (0x08030685), a written move 1 + A spent its PP.
  Its party menu is FireRed's own list (slot 0 left, 1-5 down the right): with the lead copied into slots 2-3, DOWN
  0 -> 1, then A, SHIFT put 1 into gBattlerPartyIndexes[0] (gPartyMenu 0x0203B0A0, slotId at +9, no grid).
