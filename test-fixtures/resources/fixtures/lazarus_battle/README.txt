captured: 2026-10-07 (headless mgba_dump, by hand)
rom: Pokemon Lazarus v2.0 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Lazarus (v2.0).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Lazarus (v2.0).srm
boot: native-capture/boot/default.txt + 3x B, then a scripted wild battle - the bytes
  B6 1900 05 0000 0000 00 0000 B7 02 (setwildbattle SPECIES_PIKACHU, 5; dowildbattle; end)
  poked at 0x0203FF00 and `call 0x0820B2B0 0x0203FF00` (ScriptContext_SetupScript),
  wait 60+200+300, press A, wait 200: the action menu, "What will Astaroth do?" -
  the player's Lv31 Charjabug (85/85 HP) vs a wild Lv5 Pikachu (20/20).
