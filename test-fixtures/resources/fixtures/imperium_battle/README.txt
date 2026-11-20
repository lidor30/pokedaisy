captured: 2026-10-09 (headless mgba_dump, by hand)
rom: Pokemon Emerald Imperium v1.3.1 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Emerald Imperium (v1.3.1).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Emerald Imperium (v1.3.1).sav
boot: native-capture/boot/rhh_splash.txt (the field, Route 101), then hold DOWN 40 and 6x (hold LEFT 17,
  hold RIGHT 17) in the grass - a wild Shinx Lv4 appears - wait 150, A, wait 150: the action menu
  (Charmander Lv5, 20/20 HP), dumped. From there FIGHT / BAG / POKéMON put 0x08056EA1 / 0x08058CE9 /
  0x08058C19 in gBattlerControllerFuncs[0] (0x03004F5C); the action menu is 0x0805648D.
