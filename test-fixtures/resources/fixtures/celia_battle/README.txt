captured: 2026-10-13 (headless mgba_dump, by hand)
rom: Pokemon Celia's Stupid Romhack v1.1.4 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Celia's Stupid Romhack (v1.1.4).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Celia's Stupid Romhack (v1.1.4).sav
boot: native-capture/boot/default.txt (the field), wait 60, then a scripted wild battle: B6 1900 05 0000 B7 02
  (setwildbattle SPECIES_PIKACHU, 5; dowildbattle; end) poked at 0x0203FF00 and `call 0x0807676D 0x0203FF00`
  (its ScriptContext_SetupScript: rev 0's function moved - found by matching rev 0's code with the pool loads and
  calls masked), wait 600, A, wait 300, UP, LEFT: the action menu, FIGHT highlighted, dumped.
  Its battle input came from diffing these dumps (nothing of FireRed's sits where it did): gBattlerControllerFuncs
  0x03005380 (HandleInputChooseAction 0x08037AFD, HandleInputChooseMove 0x08038185, CompleteWhenChoseItem 0x0803A005,
  WaitForMonSelection 0x08039E99), the cursors 0x020240AC / 0x020240B0 (0 -> 1 -> 3 on RIGHT / DOWN), gPartyMenu
  0x0203B368 (slotId at +9, 0 -> 7 -> 0 with one Pokémon; a list: DOWN 0 -> 1 with three). Checked: a written action
  cell + A lands on its handler (1 the bag, 2 the party menu), a written move 1 + A spends its PP, and with the lead
  copied into slots 2-3, DOWN, A, SHIFT sent slot 1 in.
