captured: 2026-10-09 (headless mgba_dump, by hand)
rom: Pokemon Quetzal Spanish Alpha 9 v0 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Quetzal (Alpha 9.0) (Spanish).gba, sha1 fe346b5b...)
save: ~/Downloads/Game ROMs & Emulation/gba/Pokemon Quetzal (Alpha 9.0) (Spanish).sav
boot: as `quetzal_es`, then a scripted wild battle: B6 1000 03 0000 0000 00 0000 B7 02 (setwildbattle
  SPECIES_PIDGEY, 3, as for `quetzal_battle`) poked at 0x0203FF00 and `call 0x080DB9E5 0x0203FF00` (its
  ScriptContext_SetupScript, English's code 0x18 bytes later), wait 300, A, wait 200, A (past GYARADOS's
  Intimidate), wait 200: the action menu (gBattlerControllerFuncs[0] = 0x0808BD21; FIGHT then showed
  0x0808C995, POKéMON 0x0808EB29), dumped.
