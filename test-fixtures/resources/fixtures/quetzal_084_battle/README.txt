captured: 2026-10-13 (headless mgba_dump, by hand)
rom: Pokemon Quetzal English Alpha 0.8.4 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Quetzal (Alpha 0.8.4) (English).gba, sha1 6806c714...)
save: ~/Downloads/Game ROMs & Emulation/gba/Pokemon Quetzal (Alpha 9.0) (Spanish).sav (Quetzal Spanish Alpha 9 v0's: 0.8.4 loads it)
boot: as `quetzal_084`, then a scripted wild battle: B6 1000 03 0000 0000 00 0000 B7 02 (setwildbattle SPECIES_PIDGEY, 3,
  as for `quetzal_battle`) poked at 0x0203FF00 and `call 0x080D34C5 0x0203FF00` (its ScriptContext_SetupScript), wait 300,
  A, wait 200, A, wait 200, A (past GYARADOS's Intimidate), wait 200: the action menu (gBattlerControllerFuncs[0] =
  0x08087FED), dumped.
