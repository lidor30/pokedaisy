captured: 2026-10-09 (headless mgba_dump, by hand)
rom: Pokemon Quetzal English Alpha 9 v0 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Quetzal (Alpha 9.0) (English).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Quetzal (Alpha 9.0) (English).sav
boot: native-capture/boot/rhh_splash.txt (the field, Route 1 - Quetzal's wild Pokémon walk the grass,
  so no random encounter), then a scripted wild battle: B6 1000 03 0000 0000 00 0000 B7 02
  (setwildbattle SPECIES_PIDGEY, 3 - its setwildbattle takes a second Pokémon's fields too; dowildbattle)
  poked at 0x0203FF00 and `call 0x080DB9CC 0x0203FF00` (ScriptContext_SetupScript), wait 300, A,
  wait 200: the action menu (Charmander Lv5, 10/19 HP; gBattlerControllerFuncs[0] = 0x0808BD45), dumped.
