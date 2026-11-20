captured: 2026-10-15T05:02:15Z (headless, scripts/capture_fixture_headless.sh)
rom: Retail Sapphire rev 1 (scripted wild battle) (~/Downloads/Game ROMs & Emulation/gba/Pokemon - Sapphire Version (USA, Europe) (Rev 1).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon - Sapphire Version (USA, Europe) (Rev 1).sav
boot script: native-capture/boot/sapphire_battle.txt
state: the action menu ("What should WAILORD do?", FIGHT highlighted) against a wild Lv5 PIKACHU; gBattlerControllerFuncs[0]
  = HandleInputChooseAction (0x0802C099). From here, headless: RIGHT / DOWN walk gActionSelectionCursor 0 -> 1 -> 3; 0
  written + A opens FIGHT (HandleInputChooseMove); gMoveSelectionCursor 1 written + A uses ASTONISH (its PP 14 -> 13);
  POKéMON reaches WaitForMonSelection, BAG CompleteWhenChoseItem.
