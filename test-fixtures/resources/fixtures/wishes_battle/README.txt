captured: 2026-10-22 (headless mgba_dump, built from the repo)
rom: Pokemon Wishes of Tomorrow v1.2.6 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Wishes of Tomorrow (v1.2.6).gba,
  sha1 60182b93..., FlatFoot Labs, pokeemerald-expansion 1.15.3)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Wishes of Tomorrow (v1.2.6).sav
boot script: native-capture/boot/wishes_battle.txt
A scripted wild ZIGZAGOON Lv5 (setwildbattle / dowildbattle through ScriptContext_SetupScript 0x081FDCC9), dumped at
  "What will Cyndaquil do?" (Battle / Bag / Pokemon / Run). From this state, headless: RIGHT walked gActionSelectionCursor
  (0x020003AC) 0 -> 1, DOWN 0 -> 2; in FIGHT, DOWN walked gMoveSelectionCursor (0x020003B0) 0 -> 2; a written move cell 1 + A
  spent LEER's PP (30 -> 29); a written action cell 2 + A opened the party menu (gPartyMenu 0x02031BB0, slotId +9 0 -> 1 on
  DOWN), A, SHIFT sent PIKACHU in (gBattlerPartyIndexes[0] = 1). gBattlerControllerFuncs[0] (0x03002450) read 0x0805DA1D at
  the action menu, 0x0805C91D in FIGHT, 0x0805C075 in the BAG, 0x0805AEED in the party menu, and 0x0805C42D on the target
  screen of a double battle (HEX MANIAC The Oracle, trainer 862).
