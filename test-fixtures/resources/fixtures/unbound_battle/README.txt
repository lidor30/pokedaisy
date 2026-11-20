captured: 2026-10-13 (headless mgba_dump, by hand)
rom: Pokemon Unbound v2.1.1.1 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Unbound (v2.1.1.1).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon - Unbound (v2.1.1.1).srm
boot: native-capture/boot/default.txt (the field), wait 60, then a scripted wild battle: B6 1900 05 0000 B7 02
  (setwildbattle SPECIES_PIKACHU, 5; dowildbattle; end) poked at 0x0203FF00 and `call 0x08069AE5 0x0203FF00`
  (ScriptContext_SetupScript, FireRed rev 0's address and code, kept by CFRU), wait 300, A, wait 200: the action
  menu ("What will Gible do?", FIGHT highlighted; the player's Lv11 Gible vs a wild Lv0 Pikachu), dumped.
  gBattlerControllerFuncs[0] = 0x0802e439 (HandleInputChooseAction), gActionSelectionCursor 0x02023FF8 = 0.
  The battle menus' cursors are FireRed's (0x02023FF8 / 0x02023FFC): RIGHT / DOWN / LEFT / UP moved the action
  cursor 0 -> 1 -> 3 -> 2 -> 0, the move cursor 0 -> 1 (DOWN refused: three moves), and a cell written there
  before A is what the game takes (2 -> the party menu, WaitForMonSelection 0x08030685; move 2 -> its PP spent).
